package com.fourwt.mailconnector;

import java.util.List;

/**
 * Kleine, unveränderliche Datenmodelle zwischen Mail- und MCP-Schicht.
 *
 * <p>Die Typen sind als {@code record} modelliert. Dadurch bleibt sichtbar,
 * welche Daten der Connector nach außen gibt; Jakarta-Mail-Objekte verlassen
 * die Mail-Schicht nicht.</p>
 */
public final class MailModels {

    private MailModels() {
    }

    /**
     * Kompakte Trefferansicht.
     *
     * <p>Ordner und UID gehören zusammen: Eine IMAP-UID ist nur innerhalb
     * eines Ordners eindeutig.</p>
     */
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

    /**
     * Metadaten eines Anhangs. Binärdaten werden in Version 0.1 nicht übertragen.
     */
    public record AttachmentInfo(
            String fileName,
            String contentType,
            int size
    ) {
    }

    /**
     * Vollständig gelesene Nachricht. Text und HTML bleiben getrennt.
     */
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

    /**
     * Auftrag zum Erzeugen eines Entwurfs.
     *
     * <p>Absichtlich enthält dieses Modell keinerlei Versandparameter.</p>
     */
    public record DraftRequest(
            List<String> to,
            List<String> cc,
            List<String> bcc,
            String subject,
            String body
    ) {
    }

    /**
     * Minimale Bestätigung nach erfolgreichem Speichern eines Entwurfs.
     */
    public record DraftResult(
            String folder,
            String messageId
    ) {
    }
}
