# KI-Mail-Connector

Minimal Java-21 connector between a dedicated mail account and ChatGPT.

## Scope 0.1

Exposed MCP tools:

- search_mail - search/list mail in one folder or across all readable IMAP folders
- read_mail - read one complete mail by folder and IMAP UID
- create_draft - create a message in the configured Drafts folder

There is deliberately **no send_mail tool** in version 0.1. Draft creation uses IMAP APPEND only. SMTP sending can be added later without changing the public read/draft model.

## Requirements

- JDK 21
- Maven 3.9+
- IMAP server with TLS/IMAPS
- dedicated mail account

## Configuration

Set environment variables; see .env.example.

Required:

- MAIL_IMAP_HOST
- MAIL_USERNAME
- MAIL_PASSWORD

Optional:

- MAIL_IMAP_PORT, default 993
- MAIL_FROM, default MAIL_USERNAME
- MAIL_DRAFTS_FOLDER, default Drafts
- MCP_BIND, default 127.0.0.1
- MCP_PORT, default 8080

No credentials are stored in Git.

## Start

From IntelliJ, run com.fourwt.mailconnector.KiMailConnectorApplication.

Or from Maven:

    mvn clean test
    mvn exec:java -Dexec.mainClass=com.fourwt.mailconnector.KiMailConnectorApplication

The MCP endpoint is:

    http://127.0.0.1:8080/mcp

The default local binding is intentional. The planned ChatGPT connection is through OpenAI Secure MCP Tunnel, so the mail connector itself does not need to be exposed publicly.

## Mail addressing

search_mail returns a stable pair of folder + uid for each IMAP message. read_mail uses that pair to retrieve the full message.

Drafts are written to MAIL_DRAFTS_FOLDER and flagged as Draft. The folder is created if the server permits it and it does not already exist.

## Security boundary

- Mail password remains only in the connector process environment.
- ChatGPT receives mail data only through the three MCP tools.
- No SMTP send operation is exposed.
- The MCP listener defaults to localhost.
