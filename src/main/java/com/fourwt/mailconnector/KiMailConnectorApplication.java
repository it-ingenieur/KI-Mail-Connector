package com.fourwt.mailconnector;

/**
 * Einstiegspunkt der Anwendung.
 *
 * <p>Die Klasse ist absichtlich sehr klein. Sie übernimmt nur die Verdrahtung der
 * Komponenten ("Composition Root"):</p>
 * <ol>
 *   <li>Konfiguration aus Umgebungsvariablen lesen.</li>
 *   <li>IMAP-Zugriff erzeugen.</li>
 *   <li>MCP-HTTP-Server erzeugen.</li>
 *   <li>Server starten und den Prozess am Leben halten.</li>
 * </ol>
 *
 * <p>Für ein Lernprojekt ist diese Trennung wichtig: Fachlogik gehört nicht in
 * {@code main()}, sondern in klar testbare Klassen.</p>
 */
public final class KiMailConnectorApplication {

    /*
     * Utility-/Startklasse: Es soll keine Instanz dieser Klasse erzeugt werden.
     */
    private KiMailConnectorApplication() {
    }

    /**
     * Startet den Connector.
     *
     * @param args Kommandozeilenargumente; werden in Version 0.1 nicht verwendet
     * @throws Exception wenn Konfiguration oder Serverstart fehlschlagen
     */
    public static void main(String[] args) throws Exception {
        // Zugangsdaten werden bewusst nicht aus Dateien im Repository gelesen.
        // System.getenv() liefert nur die Umgebungsvariablen des laufenden Prozesses.
        MailConfiguration configuration = MailConfiguration.fromEnvironment(System.getenv());

        // Das Gateway kapselt den vollständigen Zugriff auf den Mailserver.
        // Die darüberliegende MCP-Schicht kennt dadurch keine IMAP-Details.
        ImapMailGateway mailGateway = new ImapMailGateway(configuration);
        McpHttpServer server = new McpHttpServer(
                mailGateway,
                configuration.mcpBind(),
                configuration.mcpPort()
        );

        // Der Shutdown-Hook sorgt dafür, dass beim Beenden der JVM auch der
        // HTTP-Server und sein Executor sauber geschlossen werden.
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "ki-mail-connector-shutdown"));
        server.start();

        System.out.printf(
                "KI-Mail-Connector v0.1 listening on http://%s:%d/mcp%n",
                configuration.mcpBind(),
                configuration.mcpPort()
        );

        // HttpServer arbeitet in eigenen Threads. Ohne diese Zeile würde die
        // main-Methode sofort enden. join() ohne Ziel wartet unbegrenzt.
        Thread.currentThread().join();
    }
}
