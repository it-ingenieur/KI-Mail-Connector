/**
 * KI-Mail-Connector – minimales Referenz- und Lernprojekt für Java, IMAP und MCP.
 *
 * <h2>Architektur</h2>
 * <pre>
 * ChatGPT / MCP-Client
 *        |
 *        | HTTP + JSON-RPC / MCP
 *        v
 * McpHttpServer
 *        |
 *        | MailGateway
 *        v
 * ImapMailGateway
 *        |
 *        | IMAPS
 *        v
 * Mailserver
 * </pre>
 *
 * <h2>Bewusste Grenzen der Version 0.1</h2>
 * <ul>
 *   <li>Mails suchen und lesen.</li>
 *   <li>Entwürfe per IMAP speichern.</li>
 *   <li>Kein SMTP-Versand.</li>
 *   <li>Keine Datenbank.</li>
 *   <li>Kein Webframework.</li>
 *   <li>Keine Binärübertragung von Anhängen.</li>
 * </ul>
 *
 * <p>Das Projekt soll bewusst klein bleiben, damit die zugrunde liegenden
 * Mechanismen sichtbar und nachvollziehbar bleiben.</p>
 */
package com.fourwt.mailconnector;
