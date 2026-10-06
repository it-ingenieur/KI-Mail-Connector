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

/**
 * Konkrete Umsetzung des {@link MailGateway} über IMAP/IMAPS.
 *
 * <p>Diese Klasse enthält die eigentliche Mail-Protokollarbeit. Version 0.1 nutzt
 * IMAP nicht nur zum Lesen, sondern auch zum Speichern von Entwürfen. Ein Entwurf
 * wird mit IMAP APPEND in den Drafts-Ordner geschrieben; SMTP ist dafür nicht nötig.</p>
 *
 * <p>Damit bleibt die Sicherheitsgrenze einfach nachvollziehbar: Solange kein
 * SMTP-Code und kein {@code send_mail}-Tool existieren, gibt es über die
 * öffentliche Schnittstelle keine Versandfunktion.</p>
 */
public final class ImapMailGateway implements MailGateway {

    private final MailConfiguration configuration;
    private final Session session;

    /**
     * Erzeugt die Jakarta-Mail-Session.
     *
     * <p>Eine {@link Session} ist noch keine Netzwerkverbindung. Die eigentliche
     * Verbindung wird erst in {@link #openStore()} aufgebaut.</p>
     */
    public ImapMailGateway(MailConfiguration configuration) {
        this.configuration = Objects.requireNonNull(configuration);

        // Jakarta Mail wird explizit auf IMAPS, also IMAP über TLS, festgelegt.
        Properties properties = new Properties();
        properties.setProperty("mail.store.protocol", "imaps");
        properties.setProperty("mail.imaps.ssl.enable", "true");
        properties.setProperty("mail.imaps.connectiontimeout", "10000");
        properties.setProperty("mail.imaps.timeout", "30000");
        properties.setProperty("mail.imaps.writetimeout", "30000");
        this.session = Session.getInstance(properties);
    }

    /**
     * Sucht Nachrichten über die von Jakarta Mail bereitgestellten Suchausdrücke.
     *
     * <p>Ohne Ordnerangabe werden alle Ordner betrachtet, die Nachrichten halten
     * können. So kann das dedizierte Konto vollständig durchsucht werden.</p>
     */
    @Override
    public List<MailSummary> search(String query, String requestedFolder, int limit, int offset) throws Exception {
        // Eingaben defensiv begrenzen. Ein Client soll nicht versehentlich
        // zehntausende Treffer in einer einzigen MCP-Antwort anfordern.
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
                    // Für Suche und Lesen reicht READ_ONLY; Schreibrechte werden
                    // nur beim Drafts-Ordner benötigt.
                    folder.open(Folder.READ_ONLY);
                    Message[] messages = searchTerm == null ? folder.getMessages() : folder.search(searchTerm);
                    for (Message message : messages) {
                        result.add(summary(folder, message));
                    }
                } finally {
                    close(folder);
                }
            }

            // Erst nach dem Einsammeln aller Ordner wird global nach Datum
            // sortiert. Dadurch entsteht eine gemeinsame Trefferliste.
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

    /**
     * Liest eine konkrete Nachricht anhand von Ordner und IMAP-UID.
     *
     * <p>UIDs sind innerhalb eines Ordners stabiler als laufende Message-Nummern,
     * die sich bei Änderungen im Ordner verschieben können.</p>
     */
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

                // MIME-Nachrichten können verschachtelt sein:
                // multipart/alternative, multipart/mixed, eingebettete Mails usw.
                // bodyParts() läuft diese Struktur rekursiv ab.
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

    /**
     * Erzeugt einen Entwurf und legt ihn per IMAP im Drafts-Ordner ab.
     *
     * <p>{@link MimeMessage#saveChanges()} finalisiert nur MIME-Header. Erst
     * {@link Folder#appendMessages(Message[])} schreibt die Nachricht auf den
     * Server. Keiner dieser Schritte versendet eine E-Mail.</p>
     */
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
        // Das IMAP-Systemflag \Draft kennzeichnet die Nachricht als Entwurf.
        message.setFlag(Flags.Flag.DRAFT, true);

        // Finalisiert u. a. Message-ID/Date; sendet ausdrücklich nichts.
        message.saveChanges();

        try (Store store = openStore()) {
            Folder drafts = store.getFolder(configuration.draftsFolder());
            if (!drafts.exists() && !drafts.create(Folder.HOLDS_MESSAGES)) {
                throw new MessagingException("Drafts folder does not exist and could not be created: "
                        + configuration.draftsFolder());
            }

            try {
                drafts.open(Folder.READ_WRITE);
                // IMAP APPEND: Nachricht auf dem Server ablegen.
                // Hier findet ausdrücklich kein SMTP-Versand statt.
                drafts.appendMessages(new Message[]{message});
            } finally {
                close(drafts);
            }
        }

        return new DraftResult(configuration.draftsFolder(), firstHeader(message, "Message-ID"));
    }

    /**
     * Öffnet eine neue authentifizierte IMAPS-Verbindung.
     *
     * <p>Für das kleine Projekt ist "eine Verbindung pro Operation" einfach und
     * robust. Connection Pooling wäre möglich, aber derzeit unnötige Komplexität.</p>
     */
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

    /**
     * Ermittelt alle Ordner, die Nachrichten enthalten dürfen.
     *
     * <p>IMAP kennt auch reine Container-Ordner. Das Bit
     * {@link Folder#HOLDS_MESSAGES} trennt diese von echten Mailordnern.</p>
     */
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

    /**
     * Liest eine Nachricht über ihre IMAP-UID.
     */
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

    /**
     * Baut einen Suchausdruck für Betreff, Body, Absender und Empfänger.
     *
     * <p>Jakarta Mail kann diese Suchobjekte bei IMAP in serverseitige
     * SEARCH-Kommandos übersetzen. Dadurch muss nicht erst der gesamte
     * Postfachinhalt zum Connector übertragen werden.</p>
     */
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

    /**
     * Zerlegt die MIME-Struktur in Text, HTML und Anhangsmetadaten.
     */
    private static BodyParts bodyParts(Part part) throws MessagingException, IOException {
        BodyParts result = new BodyParts();
        collectPart(part, result);
        return result;
    }

    /**
     * Rekursiver MIME-Visitor.
     *
     * <p>Eine E-Mail ist strukturell ein Baum aus MIME-Parts. Deshalb wird
     * hier rekursiv durch alle enthaltenen Teile gelaufen.</p>
     */
    private static void collectPart(Part part, BodyParts target) throws MessagingException, IOException {
        String fileName = part.getFileName();
        String disposition = part.getDisposition();
        boolean attachment = Part.ATTACHMENT.equalsIgnoreCase(disposition) || fileName != null;

        if (attachment) {
            // Version 0.1 liefert nur Metadaten. Der eigentliche Binärinhalt
            // des Anhangs wird absichtlich noch nicht gelesen.
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
            // Aufräumen nach einem abgeschlossenen Vorgang ist "best effort".
            // Ein Close-Fehler soll den eigentlichen Rückgabewert nicht verdecken.
        }
    }

    /**
     * Interner Akkumulator für die rekursive MIME-Zerlegung.
     */
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
