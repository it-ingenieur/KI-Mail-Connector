package com.fourwt.mailconnector;

public final class KiMailConnectorApplication {

    private KiMailConnectorApplication() {
    }

    public static void main(String[] args) throws Exception {
        MailConfiguration configuration = MailConfiguration.fromEnvironment(System.getenv());
        ImapMailGateway mailGateway = new ImapMailGateway(configuration);
        McpHttpServer server = new McpHttpServer(
                mailGateway,
                configuration.mcpBind(),
                configuration.mcpPort()
        );

        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "ki-mail-connector-shutdown"));
        server.start();

        System.out.printf(
                "KI-Mail-Connector v0.1 listening on http://%s:%d/mcp%n",
                configuration.mcpBind(),
                configuration.mcpPort()
        );

        Thread.currentThread().join();
    }
}
