package com.fourwt.mailconnector;

import java.util.List;

import static com.fourwt.mailconnector.MailModels.DraftRequest;
import static com.fourwt.mailconnector.MailModels.DraftResult;
import static com.fourwt.mailconnector.MailModels.MailMessage;
import static com.fourwt.mailconnector.MailModels.MailSummary;

/**
 * Abstraktion des Mailzugriffs.
 *
 * <p>Der MCP-Server hängt nur von diesem Interface ab, nicht von IMAP.
 * Das erleichtert Tests und trennt Protokoll- von Fachlogik.</p>
 */
public interface MailGateway {

    /**
     * Sucht oder listet Nachrichten.
     */
    List<MailSummary> search(String query, String folder, int limit, int offset) throws Exception;

    /**
     * Liest genau eine Nachricht über Ordner und IMAP-UID.
     */
    MailMessage read(String folder, long uid) throws Exception;

    /**
     * Speichert einen Entwurf. Diese Operation versendet ausdrücklich keine E-Mail.
     */
    DraftResult createDraft(DraftRequest request) throws Exception;
}
