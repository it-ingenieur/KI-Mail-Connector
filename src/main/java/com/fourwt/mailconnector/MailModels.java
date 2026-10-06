package com.fourwt.mailconnector;

import java.util.List;

public final class MailModels {

    private MailModels() {
    }

    public record MailSummary(
            String folder,
            long uid,
            String messageId,
            String subject,
            List<String> from,
            List<String> to,
            String receivedAt,
            boolean seen
    ) {
    }

    public record AttachmentInfo(
            String fileName,
            String contentType,
            int size
    ) {
    }

    public record MailMessage(
            String folder,
            long uid,
            String messageId,
            String subject,
            List<String> from,
            List<String> to,
            List<String> cc,
            List<String> bcc,
            String sentAt,
            String receivedAt,
            String textBody,
            String htmlBody,
            List<AttachmentInfo> attachments
    ) {
    }

    public record DraftRequest(
            List<String> to,
            List<String> cc,
            List<String> bcc,
            String subject,
            String body
    ) {
    }

    public record DraftResult(
            String folder,
            String messageId
    ) {
    }
}
