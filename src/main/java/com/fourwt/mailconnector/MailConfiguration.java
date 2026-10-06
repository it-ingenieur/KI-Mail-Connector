package com.fourwt.mailconnector;

import java.util.Map;

/**
 * Unveränderliche Laufzeitkonfiguration des Connectors.
 *
 * <p>Ein Java-{@code record} eignet sich hier gut, weil die Klasse ausschließlich
 * Daten transportiert. Der Compiler erzeugt Konstruktor und Zugriffsmethoden automatisch.</p>
 *
 * <p><strong>Sicherheitsprinzip:</strong> Das Passwort wird nur aus der
 * Prozessumgebung gelesen und niemals in Git gespeichert.</p>
 */
public record MailConfiguration(
        String imapHost,
        int imapPort,
        String username,
        String password,
        String fromAddress,
        String draftsFolder,
        String mcpBind,
        int mcpPort
) {
    /**
     * Erzeugt die Konfiguration aus Umgebungsvariablen.
     *
     * <p>Die Map wird als Parameter übergeben, statt innerhalb der Methode direkt
     * {@code System.getenv()} aufzurufen. Das macht die Methode deterministisch
     * und später sehr einfach testbar.</p>
     */
    public static MailConfiguration fromEnvironment(Map<String, String> env) {
        String username = required(env, "MAIL_USERNAME");
        return new MailConfiguration(
                required(env, "MAIL_IMAP_HOST"),
                integer(env, "MAIL_IMAP_PORT", 993),
                username,
                required(env, "MAIL_PASSWORD"),
                value(env, "MAIL_FROM", username),
                value(env, "MAIL_DRAFTS_FOLDER", "Drafts"),
                value(env, "MCP_BIND", "127.0.0.1"),
                integer(env, "MCP_PORT", 8080)
        );
    }

    /**
     * Liest einen zwingend notwendigen String-Wert.
     */
    private static String required(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable: " + name);
        }
        return value.trim();
    }

    /**
     * Liest einen optionalen String-Wert und verwendet sonst den Standardwert.
     */
    private static String value(Map<String, String> env, String name, String defaultValue) {
        String value = env.get(name);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    /**
     * Liest einen optionalen Integer-Wert und meldet ungültige Werte ausdrücklich.
     */
    private static int integer(Map<String, String> env, String name, int defaultValue) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Invalid integer environment variable " + name + ": " + value, e);
        }
    }
}
