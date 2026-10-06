package com.fourwt.mailconnector;

import jakarta.mail.Address;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.search.BodyTerm;
import jakarta.mail.search.FromStringTerm;
import jakarta.mail.search.OrTerm;
import jakarta.mail.search.RecipientStringTerm;
import jakarta.mail.search.SearchTerm;
import jakarta.mail.search.SubjectTerm;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

import static com.fourwt.mailconnector.MailModels.AttachmentInfo;
import static com.fourwt.mailconnector.MailModels.DraftRequest;
import static com.fourwt.mailconnector.MailModels.DraftResult;
import static com.fourwt.mailconnector.MailModels.MailMessage;
import static com.fourwt.mailconnector.MailModels.MailSummary;

public final class ImapMailGateway implements MailGateway {

    private final MailConfiguration configuration;
    private final Session session;

    public ImapMailGateway(MailConfiguration configuration) {
        this.configuration = Objects.requireNonNull(configuration);
        Properties properties = new Properties();
        properties.setProperty("mail.store.protocol", "imaps");
        properties.setProperty("mail.imaps.ssl.enable", "true");
        properties.setProperty("mail.imaps.connectiontimeout", "10000");
        properties.setProperty("mail.imaps.timeout", "30000");
        properties.setProperty("mail.imaps.writetimeout", "30000");
        this.session = Session.getInstance(properties);
    }

    @Override
    public List<MailSummary> search(String query, String requestedFolder, int limit, int offset) throws Exception {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        int safeOffset = Math.max(0, offset);

        try (Store store = openStore()) {
            List<Folder> folders = requestedFolder == null || requestedFolder.isBlank()
                    ? readableFolders(store)
                    : List.of(requiredReadableFolder(store, requestedFolder));

            List<MailSummary> result = new ArrayList<>();
            SearchTerm searchTerm = searchTerm(query);

            for (Folder folder : folders) {
                try {
                    folder.open(Folder.READ_ONLY);
                    Message[] messages = searchTerm == null ? folder.getMessages() : folder.search(searchTerm);
                    for (Message message : messages) {
                        result.add(summary(folder, message));
                    }
                } finally {
                    close(folder);
                }
            }

            result.sort(Comparator.comparing(
                    MailSummary::receivedAt,
                    Comparator.nullsLast(Comparator.reverseOrder())
            ));

            if (safeOffset >= result.size()) {
                return List.of();
            }
            int end = Math.min(result.size(), safeOffset + safeLimit);
            return List.copyOf(result.subList(safeOffset, end));
        }
    }

    @Override
    public MailMessage read(String folderName, long uid) throws Exception {
        try (Store store = openStore()) {
            Folder folder = requiredReadableFolder(store, folderName);
            try {
                folder.open(Folder.READ_ONLY);
                Message message = messageByUid(folder, uid);
                if (message == null) {
                    throw new IllegalArgumentException("No message with UID " + uid + " in folder " + folderName);
                }

                BodyParts body = bodyParts(message);
                return new MailMessage(
                        folder.getFullName(),
                        uid,
                        firstHeader(message, "Message-ID"),
                        safe(message.getSubject()),
                        addresses(message.getFrom()),
                        addresses(message.getRecipients(Message.RecipientType.TO)),
                        addresses(message.getRecipients(Message.RecipientType.CC)),
                        addresses(message.getRecipients(Message.RecipientType.BCC)),
                        iso(message.getSentDate()),
                        iso(message.getReceivedDate()),
                        body.text(),
                        body.html(),
                        List.copyOf(body.attachments())
                );
            } finally {
                close(folder);
            }
        }
    }

    @Override
    public DraftResult createDraft(DraftRequest request) throws Exception {
        if (request == null || request.to() == null || request.to().isEmpty()) {
            throw new IllegalArgumentException("At least one recipient in 'to' is required");
        }

        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(configuration.fromAddress()));
        message.setRecipients(Message.RecipientType.TO, parseAddresses(request.to()));
        message.setRecipients(Message.RecipientType.CC, parseAddresses(request.cc()));
        message.setRecipients(Message.RecipientType.BCC, parseAddresses(request.bcc()));
        message.setSubject(safe(request.subject()), "UTF-8");
        message.setText(safe(request.body()), "UTF-8");
        message.setFlag(Flags.Flag.DRAFT, true);
        message.saveChanges();

        try (Store store = openStore()) {
            Folder drafts = store.getFolder(configuration.draftsFolder());
            if (!drafts.exists() && !drafts.create(Folder.HOLDS_MESSAGES)) {
                throw new MessagingException("Drafts folder does not exist and could not be created: "
                        + configuration.draftsFolder());
            }

            try {
                drafts.open(Folder.READ_WRITE);
                drafts.appendMessages(new Message[]{message});
            } finally {
                close(drafts);
            }
        }

        return new DraftResult(configuration.draftsFolder(), firstHeader(message, "Message-ID"));
    }

    private Store openStore() throws MessagingException {
        Store store = session.getStore("imaps");
        store.connect(
                configuration.imapHost(),
                configuration.imapPort(),
                configuration.username(),
                configuration.password()
        );
        return store;
    }

    private static List<Folder> readableFolders(Store store) throws MessagingException {
        List<Folder> result = new ArrayList<>();
        for (Folder folder : store.getDefaultFolder().list("*")) {
            if ((folder.getType() & Folder.HOLDS_MESSAGES) != 0) {
                result.add(folder);
            }
        }
        return result;
    }

    private static Folder requiredReadableFolder(Store store, String folderName) throws MessagingException {
        Folder folder = store.getFolder(folderName);
        if (!folder.exists()) {
            throw new IllegalArgumentException("Mail folder does not exist: " + folderName);
        }
        if ((folder.getType() & Folder.HOLDS_MESSAGES) == 0) {
            throw new IllegalArgumentException("Mail folder cannot contain messages: " + folderName);
        }
        return folder;
    }

    private static Message messageByUid(Folder folder, long uid) throws MessagingException {
        if (!(folder instanceof UIDFolder uidFolder)) {
            throw new IllegalStateException("IMAP folder does not support stable UIDs: " + folder.getFullName());
        }
        return uidFolder.getMessageByUID(uid);
    }

    private static MailSummary summary(Folder folder, Message message) throws MessagingException {
        long uid = folder instanceof UIDFolder uidFolder
                ? uidFolder.getUID(message)
                : message.getMessageNumber();

        Date date = message.getReceivedDate() != null ? message.getReceivedDate() : message.getSentDate();

        return new MailSummary(
                folder.getFullName(),
                uid,
                firstHeader(message, "Message-ID"),
                safe(message.getSubject()),
                addresses(message.getFrom()),
                addresses(message.getRecipients(Message.RecipientType.TO)),
                iso(date),
                message.isSet(Flags.Flag.SEEN)
        );
    }

    private static SearchTerm searchTerm(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String q = query.trim();
        return new OrTerm(new SearchTerm[]{
                new SubjectTerm(q),
                new BodyTerm(q),
                new FromStringTerm(q),
                new RecipientStringTerm(Message.RecipientType.TO, q),
                new RecipientStringTerm(Message.RecipientType.CC, q)
        });
    }

    private static Address[] parseAddresses(List<String> values) throws MessagingException {
        if (values == null || values.isEmpty()) {
            return new Address[0];
        }
        return InternetAddress.parse(String.join(",", values), false);
    }

    private static List<String> addresses(Address[] addresses) {
        if (addresses == null || addresses.length == 0) {
            return List.of();
        }
        List<String> result = new ArrayList<>(addresses.length);
        for (Address address : addresses) {
            result.add(address instanceof InternetAddress internetAddress
                    ? internetAddress.toUnicodeString()
                    : address.toString());
        }
        return List.copyOf(result);
    }

    private static String firstHeader(Message message, String name) throws MessagingException {
        String[] values = message.getHeader(name);
        return values == null || values.length == 0 ? null : values[0];
    }

    private static BodyParts bodyParts(Part part) throws MessagingException, IOException {
        BodyParts result = new BodyParts();
        collectPart(part, result);
        return result;
    }

    private static void collectPart(Part part, BodyParts target) throws MessagingException, IOException {
        String fileName = part.getFileName();
        String disposition = part.getDisposition();
        boolean attachment = Part.ATTACHMENT.equalsIgnoreCase(disposition) || fileName != null;

        if (attachment) {
            target.attachments().add(new AttachmentInfo(
                    fileName == null ? "(unnamed)" : fileName,
                    part.getContentType(),
                    part.getSize()
            ));
            return;
        }

        if (part.isMimeType("text/plain")) {
            Object content = part.getContent();
            if (content instanceof String text) {
                target.addText(text);
            }
            return;
        }

        if (part.isMimeType("text/html")) {
            Object content = part.getContent();
            if (content instanceof String html) {
                target.addHtml(html);
            }
            return;
        }

        if (part.isMimeType("multipart/*")) {
            Object content = part.getContent();
            if (content instanceof Multipart multipart) {
                for (int i = 0; i < multipart.getCount(); i++) {
                    collectPart(multipart.getBodyPart(i), target);
                }
            }
            return;
        }

        if (part.isMimeType("message/rfc822")) {
            Object content = part.getContent();
            if (content instanceof Part nested) {
                collectPart(nested, target);
            }
        }
    }

    private static String iso(Date date) {
        return date == null ? null : Instant.ofEpochMilli(date.getTime()).toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static void close(Folder folder) {
        if (folder == null || !folder.isOpen()) {
            return;
        }
        try {
            folder.close(false);
        } catch (MessagingException ignored) {
            // Best effort during resource cleanup.
        }
    }

    private static final class BodyParts {
        private final StringBuilder text = new StringBuilder();
        private final StringBuilder html = new StringBuilder();
        private final List<AttachmentInfo> attachments = new ArrayList<>();

        void addText(String value) {
            append(text, value);
        }

        void addHtml(String value) {
            append(html, value);
        }

        String text() {
            return text.toString();
        }

        String html() {
            return html.toString();
        }

        List<AttachmentInfo> attachments() {
            return attachments;
        }

        private static void append(StringBuilder target, String value) {
            if (value == null || value.isBlank()) {
                return;
            }
            if (!target.isEmpty()) {
                target.append(System.lineSeparator()).append(System.lineSeparator());
            }
            target.append(value);
        }
    }
}
