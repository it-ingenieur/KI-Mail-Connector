package com.fourwt.mailconnector;

import java.util.Map;

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

    private static String required(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable: " + name);
        }
        return value.trim();
    }

    private static String value(Map<String, String> env, String name, String defaultValue) {
        String value = env.get(name);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

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
