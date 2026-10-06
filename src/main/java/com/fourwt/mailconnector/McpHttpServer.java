package com.fourwt.mailconnector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.fourwt.mailconnector.MailModels.DraftRequest;

/**
 * Minimaler MCP-Server über HTTP und JSON-RPC.
 *
 * <p>MCP (Model Context Protocol) beschreibt, wie ein KI-Client Werkzeuge
 * entdecken und aufrufen kann. Version 0.1 stellt nur Suchen, Lesen und das
 * Erzeugen von Entwürfen bereit.</p>
 *
 * <p>Der Server unterstützt bewusst beide derzeit relevanten MCP-Lebenszyklen:</p>
 * <ul>
 *   <li><strong>Legacy bis 2025-11-25:</strong> {@code initialize}-Handshake.</li>
 *   <li><strong>Modern 2026-07-28:</strong> stateless, {@code server/discover}
 *       und Protokollinformationen in jedem Request.</li>
 * </ul>
 *
 * <p>Der Server verwendet absichtlich den im JDK enthaltenen {@link HttpServer}.
 * Ein zusätzliches Webframework würde in diesem kleinen Lernprojekt die
 * grundlegende MCP-Mechanik eher verdecken.</p>
 *
 * <p><strong>Sicherheitsgrenze:</strong> Ein {@code send_mail}-Tool wird nicht
 * registriert und kann daher vom MCP-Client nicht regulär aufgerufen werden.</p>
 */
public final class McpHttpServer implements AutoCloseable {

    /**
     * Moderne MCP-Revision.
     *
     * <p>Diese Revision wird nicht über {@code initialize} ausgehandelt.
     * Sie wird in jedem Request über {@code _meta} und bei HTTP zusätzlich
     * über den Header {@code MCP-Protocol-Version} angegeben.</p>
     */
    static final String MODERN_PROTOCOL = "2026-07-28";

    /**
     * Neueste von uns unterstützte Legacy-Revision.
     */
    static final String LATEST_LEGACY_PROTOCOL = "2025-11-25";

    /**
     * Versionen, die noch den klassischen initialize-Handshake verwenden.
     */
    private static final Set<String> LEGACY_PROTOCOLS = Set.of(
            "2025-11-25",
            "2025-06-18",
            "2025-03-26"
    );

    private static final String META_PROTOCOL_VERSION =
            "io.modelcontextprotocol/protocolVersion";
    private static final String META_CLIENT_CAPABILITIES =
            "io.modelcontextprotocol/clientCapabilities";
    private static final String META_SERVER_INFO =
            "io.modelcontextprotocol/serverInfo";

    private final MailGateway mailGateway;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpServer httpServer;
    private final ExecutorService executor;

    /**
     * Erstellt den lokalen MCP-HTTP-Server.
     *
     * @param mailGateway abstrahierter Mailzugriff
     * @param bindAddress Bind-Adresse, standardmäßig 127.0.0.1
     * @param port TCP-Port, standardmäßig 8080
     */
    public McpHttpServer(MailGateway mailGateway, String bindAddress, int port) throws IOException {
        this.mailGateway = mailGateway;
        this.httpServer = HttpServer.create(new InetSocketAddress(bindAddress, port), 0);

        // Java-21-Virtual-Threads eignen sich gut für blockierende I/O wie
        // HTTP und IMAP und vermeiden einen schweren klassischen Thread-Pool.
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.httpServer.setExecutor(executor);
        this.httpServer.createContext("/mcp", this::handleHttp);
    }

    /**
     * Startet die Annahme von HTTP-Anfragen.
     */
    public void start() {
        httpServer.start();
    }

    /**
     * Beendet HTTP-Server und Executor.
     */
    @Override
    public void close() {
        httpServer.stop(0);
        executor.shutdownNow();
    }

    /**
     * Verarbeitet eine JSON-RPC-Anfrage unabhängig vom HTTP-Transport.
     *
     * <p>Diese Trennung macht die MCP-Logik ohne echten TCP-Port testbar.
     * Die HTTP-spezifische Spiegelung in Header wird zusätzlich in
     * {@link #handleHttp(HttpExchange)} geprüft.</p>
     *
     * @param request vollständige JSON-RPC-Anfrage
     * @return JSON-RPC-Antwort oder {@code null} bei einer Notification
     */
    JsonNode handleRpc(JsonNode request) {
        JsonNode id = request.get("id");
        String method = request.path("method").asText("");

        // JSON-RPC-Nachrichten ohne id sind Notifications.
        // Auf Notifications wird keine Antwort gesendet.
        if (id == null || id.isNull()) {
            return null;
        }

        boolean modern = isModernRequest(request);

        try {
            if (modern) {
                validateModernRequestMetadata(request);
            }

            return switch (method) {
                case "server/discover" -> modern
                        ? success(id, discover(), true)
                        : error(id, -32601, "Method not found: " + method);
                case "initialize" -> modern
                        ? error(id, -32601, "Method not found in modern MCP: " + method)
                        : success(id, initializeLegacy(request.path("params")), false);
                case "ping" -> modern
                        ? error(id, -32601, "Method not found in modern MCP: " + method)
                        : success(id, mapper.createObjectNode(), false);
                case "tools/list" -> success(id, toolsList(modern), modern);
                case "tools/call" -> success(id, callTool(request.path("params")), modern);
                default -> error(id, -32601, "Method not found: " + method);
            };
        } catch (IllegalArgumentException e) {
            return error(id, -32602, e.getMessage());
        } catch (Exception e) {
            return error(id, -32603,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /**
     * Dünne HTTP-Transportebene um die JSON-RPC-Verarbeitung.
     *
     * <p>Die Revision 2026-07-28 spiegelt bestimmte Daten aus dem JSON-Body in
     * HTTP-Header. Das ermöglicht Routing und Sicherheitsprüfungen, ohne dass
     * vorgeschaltete Infrastruktur zuerst JSON parsen muss.</p>
     */
    private void handleHttp(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "POST");
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        JsonNode request;
        try {
            request = mapper.readTree(exchange.getRequestBody());
        } catch (Exception e) {
            writeJson(exchange, 400, error(null, -32700, "Parse error"));
            return;
        }

        /*
         * Moderne Requests müssen ihre Routing-Informationen sowohl im Body
         * als auch in den HTTP-Headern tragen. Bei Abweichungen antworten wir
         * mit HTTP 400 und einem MCP-Protokollfehler.
         */
        if (isModernHttpRequest(exchange, request)) {
            String mirrorError = validateModernHttpMirrors(exchange, request);
            if (mirrorError != null) {
                writeJson(exchange, 400, error(
                        request.get("id"),
                        -32020,
                        mirrorError
                ));
                return;
            }
        }

        JsonNode response = handleRpc(request);

        if (response == null) {
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
            return;
        }

        /*
         * JSON-RPC-Fehler bleiben grundsätzlich normale JSON-Antworten.
         * Nur der spezielle Fall "unbekannte moderne Methode" darf dem Client
         * über HTTP 404 zusätzlich signalisieren, dass die Methode nicht
         * unterstützt wird.
         */
        int status = isModernRequest(request)
                && response.has("error")
                && response.path("error").path("code").asInt() == -32601
                ? 404
                : 200;

        writeJson(exchange, status, response);
    }

    private void writeJson(HttpExchange exchange, int status, JsonNode response) throws IOException {
        byte[] body = mapper.writeValueAsBytes(response);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        } finally {
            exchange.close();
        }
    }

    /**
     * Antwort auf {@code server/discover} der modernen MCP-Ära.
     *
     * <p>Wichtig: {@code supportedVersions} nennt hier nur Versionen, die
     * pro Request über {@code _meta} funktionieren. Die älteren Handshake-
     * Versionen werden weiterhin über {@code initialize} unterstützt, aber
     * nicht hier angeboten.</p>
     */
    private ObjectNode discover() {
        ObjectNode result = mapper.createObjectNode();
        result.put("resultType", "complete");

        ArrayNode versions = result.putArray("supportedVersions");
        versions.add(MODERN_PROTOCOL);

        ObjectNode capabilities = result.putObject("capabilities");
        capabilities.putObject("tools").put("listChanged", false);

        result.put("instructions",
                "Read mail and create drafts only. No tool is available for sending mail.");

        // Konservative Cache-Hinweise: Die Antwort darf gespeichert werden,
        // gilt aber sofort wieder als veraltet und ist nicht zwischen Nutzern teilbar.
        result.put("ttlMs", 0);
        result.put("cacheScope", "private");

        addServerInfo(result);
        return result;
    }

    /**
     * Klassischer MCP-Handshake für Revisionen bis einschließlich 2025-11-25.
     *
     * <p>Ein moderner Versionsstring wie 2026-07-28 wird hier absichtlich
     * niemals zurückgegeben. Moderne Clients benutzen stattdessen
     * {@code server/discover}.</p>
     */
    private ObjectNode initializeLegacy(JsonNode params) {
        String requested = params.path("protocolVersion").asText("");
        String protocol = LEGACY_PROTOCOLS.contains(requested)
                ? requested
                : LATEST_LEGACY_PROTOCOL;

        ObjectNode result = mapper.createObjectNode();
        result.put("protocolVersion", protocol);

        ObjectNode capabilities = result.putObject("capabilities");
        capabilities.putObject("tools").put("listChanged", false);

        ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", "KI-Mail-Connector");
        serverInfo.put("version", "0.1.0");

        result.put("instructions",
                "Read mail and create drafts only. No tool is available for sending mail.");
        return result;
    }

    /**
     * Liefert die Werkzeugbeschreibung für den MCP-Client.
     *
     * <p>Die Tool-Liste ist zugleich eine technische Berechtigungsgrenze:
     * Nicht veröffentlichte Werkzeuge kann der Client nicht regulär nutzen.</p>
     */
    private ObjectNode toolsList(boolean modern) {
        ArrayNode tools = mapper.createArrayNode();
        tools.add(searchTool());
        tools.add(readTool());
        tools.add(createDraftTool());

        ObjectNode result = mapper.createObjectNode();
        result.set("tools", tools);

        if (modern) {
            // tools/list ist in der modernen Revision cachebar.
            result.put("ttlMs", 0);
            result.put("cacheScope", "private");
        }

        return result;
    }

    /**
     * Dispatcht einen MCP-Toolaufruf auf das {@link MailGateway}.
     *
     * <p>Diese Schicht übersetzt nur JSON in Java-Typen und zurück.
     * IMAP-Details bleiben vollständig im Gateway.</p>
     */
    private ObjectNode callTool(JsonNode params) {
        String name = params.path("name").asText("");
        JsonNode arguments = params.path("arguments");

        try {
            Object value = switch (name) {
                case "search_mail" -> mailGateway.search(
                        text(arguments, "query"),
                        text(arguments, "folder"),
                        integer(arguments, "limit", 25),
                        integer(arguments, "offset", 0)
                );
                case "read_mail" -> mailGateway.read(
                        requiredText(arguments, "folder"),
                        requiredLong(arguments, "uid")
                );

                // create_draft endet im IMAP-Gateway bei APPEND in den
                // Entwurfsordner. Ein send_mail-Zweig existiert absichtlich nicht.
                case "create_draft" -> mailGateway.createDraft(new DraftRequest(
                        strings(arguments.get("to")),
                        strings(arguments.get("cc")),
                        strings(arguments.get("bcc")),
                        textOrEmpty(arguments, "subject"),
                        textOrEmpty(arguments, "body")
                ));
                default -> throw new IllegalArgumentException("Unknown tool: " + name);
            };
            return toolResult(value, false);
        } catch (Exception e) {
            return toolResult(Map.of(
                    "error",
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()
            ), true);
        }
    }

    /**
     * Erkennt die moderne MCP-Ära ausschließlich an der per-Request-Metadaten-
     * Version im JSON-Body.
     *
     * <p>Das ist wichtig: Der Methodenname {@code server/discover} allein macht
     * einen Request noch nicht modern. Ein Legacy-Client darf die Methode testen
     * und muss dann sauber auf {@code initialize} zurückfallen können.</p>
     */
    private boolean isModernRequest(JsonNode request) {
        return MODERN_PROTOCOL.equals(protocolVersionFromBody(request));
    }

    /**
     * Für HTTP reicht zur Erkennung zusätzlich der Protokoll-Header.
     * Die eigentliche Gleichheit von Header und Body wird danach geprüft.
     */
    private boolean isModernHttpRequest(HttpExchange exchange, JsonNode request) {
        return MODERN_PROTOCOL.equals(header(exchange, "MCP-Protocol-Version"))
                || isModernRequest(request);
    }

    /**
     * Prüft die Pflichtfelder im {@code _meta}-Block eines modernen Requests.
     */
    private void validateModernRequestMetadata(JsonNode request) {
        JsonNode meta = request.path("params").path("_meta");

        if (!MODERN_PROTOCOL.equals(meta.path(META_PROTOCOL_VERSION).asText())) {
            throw new IllegalArgumentException(
                    "Modern MCP request requires _meta." + META_PROTOCOL_VERSION
                            + "=" + MODERN_PROTOCOL);
        }

        JsonNode clientCapabilities = meta.get(META_CLIENT_CAPABILITIES);
        if (clientCapabilities == null || !clientCapabilities.isObject()) {
            throw new IllegalArgumentException(
                    "Modern MCP request requires object _meta." + META_CLIENT_CAPABILITIES);
        }
    }

    /**
     * Prüft die für Streamable HTTP vorgeschriebene Spiegelung zwischen
     * JSON-Body und Headern.
     *
     * @return {@code null} bei Erfolg, sonst eine verständliche Fehlermeldung
     */
    private String validateModernHttpMirrors(HttpExchange exchange, JsonNode request) {
        String bodyVersion = protocolVersionFromBody(request);
        String headerVersion = header(exchange, "MCP-Protocol-Version");
        if (!MODERN_PROTOCOL.equals(bodyVersion) || !MODERN_PROTOCOL.equals(headerVersion)) {
            return "MCP-Protocol-Version header must mirror _meta protocolVersion";
        }

        String method = request.path("method").asText("");
        String headerMethod = header(exchange, "Mcp-Method");
        if (!method.equals(headerMethod)) {
            return "Mcp-Method header must mirror JSON-RPC method";
        }

        if ("tools/call".equals(method)) {
            String bodyName = request.path("params").path("name").asText("");
            String headerName = header(exchange, "Mcp-Name");
            if (bodyName.isBlank() || !bodyName.equals(headerName)) {
                return "Mcp-Name header must mirror params.name for tools/call";
            }
        }

        return null;
    }

    private String protocolVersionFromBody(JsonNode request) {
        return request.path("params")
                .path("_meta")
                .path(META_PROTOCOL_VERSION)
                .asText("");
    }

    private static String header(HttpExchange exchange, String name) {
        return exchange.getRequestHeaders().getFirst(name);
    }

    /**
     * Fügt die in der modernen Revision vorgeschriebene Serveridentität in
     * den {@code _meta}-Block eines Resultats ein.
     */
    private void addServerInfo(ObjectNode result) {
        ObjectNode meta = result.withObject("/_meta");
        ObjectNode serverInfo = meta.putObject(META_SERVER_INFO);
        serverInfo.put("name", "KI-Mail-Connector");
        serverInfo.put("version", "0.1.0");
    }

    /**
     * Definiert das JSON-Schema für search_mail.
     */
    private ObjectNode searchTool() {
        ObjectNode schema = objectSchema();
        schema.putObject("properties")
                .setAll(Map.of(
                        "query", stringProperty("Optional text in subject, body, sender or recipient."),
                        "folder", stringProperty("Optional exact IMAP folder. Omit to search all readable folders."),
                        "limit", integerProperty("Maximum number of results, 1..100. Default 25.", 1, 100),
                        "offset", integerProperty("Result offset for paging. Default 0.", 0, null)
                ));

        return tool(
                "search_mail",
                "Search or list mail. With no query it lists recent messages. With no folder it searches all readable IMAP folders.",
                schema,
                true,
                true
        );
    }

    /**
     * Definiert das JSON-Schema für read_mail.
     */
    private ObjectNode readTool() {
        ObjectNode schema = objectSchema();
        schema.putObject("properties")
                .setAll(Map.of(
                        "folder", stringProperty("Exact IMAP folder returned by search_mail."),
                        "uid", integerProperty("IMAP UID returned by search_mail.", 1, null)
                ));
        ArrayNode required = schema.putArray("required");
        required.add("folder");
        required.add("uid");

        return tool(
                "read_mail",
                "Read one complete mail including headers, text/HTML body and attachment metadata.",
                schema,
                true,
                true
        );
    }

    /**
     * Definiert das JSON-Schema für create_draft.
     *
     * <p>{@code readOnlyHint=false}, weil das Speichern eines Entwurfs den
     * Zustand des Mailkontos verändert, obwohl keine Mail versendet wird.</p>
     */
    private ObjectNode createDraftTool() {
        ObjectNode schema = objectSchema();
        ObjectNode properties = schema.putObject("properties");
        properties.set("to", stringArrayProperty("One or more recipient email addresses."));
        properties.set("cc", stringArrayProperty("Optional CC recipients."));
        properties.set("bcc", stringArrayProperty("Optional BCC recipients."));
        properties.set("subject", stringProperty("Draft subject."));
        properties.set("body", stringProperty("Plain-text draft body."));

        ArrayNode required = schema.putArray("required");
        required.add("to");
        required.add("subject");
        required.add("body");

        return tool(
                "create_draft",
                "Create an email draft in the configured Drafts folder. This never sends mail.",
                schema,
                false,
                false
        );
    }

    private ObjectNode tool(
            String name,
            String description,
            ObjectNode inputSchema,
            boolean readOnly,
            boolean idempotent
    ) {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("name", name);
        tool.put("description", description);
        tool.set("inputSchema", inputSchema);

        // MCP-Annotations beschreiben Seiteneffekte eines Werkzeugs.
        ObjectNode annotations = tool.putObject("annotations");
        annotations.put("readOnlyHint", readOnly);
        annotations.put("destructiveHint", false);
        annotations.put("idempotentHint", idempotent);
        annotations.put("openWorldHint", false);
        return tool;
    }

    /**
     * Verpackt ein Java-Ergebnis in das MCP-Content-Format.
     */
    private ObjectNode toolResult(Object value, boolean isError) {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode content = result.putArray("content");
        ObjectNode text = content.addObject();
        text.put("type", "text");
        try {
            text.put("text", mapper.writeValueAsString(value));
        } catch (Exception e) {
            text.put("text", "{\"error\":\"Could not serialize result\"}");
            isError = true;
        }
        result.put("isError", isError);
        return result;
    }

    private ObjectNode objectSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        return schema;
    }

    private ObjectNode stringProperty(String description) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "string");
        node.put("description", description);
        return node;
    }

    private ObjectNode integerProperty(String description, Integer minimum, Integer maximum) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "integer");
        node.put("description", description);
        if (minimum != null) {
            node.put("minimum", minimum);
        }
        if (maximum != null) {
            node.put("maximum", maximum);
        }
        return node;
    }

    private ObjectNode stringArrayProperty(String description) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "array");
        node.put("description", description);
        node.putObject("items").put("type", "string");
        return node;
    }

    /**
     * Baut eine erfolgreiche JSON-RPC-Antwort.
     *
     * <p>In der modernen Ära muss jedes Resultat {@code resultType} und die
     * Serveridentität im Resultat-{@code _meta} tragen.</p>
     */
    private ObjectNode success(JsonNode id, JsonNode rawResult, boolean modern) {
        ObjectNode result = rawResult.deepCopy();

        if (modern) {
            if (!result.has("resultType")) {
                result.put("resultType", "complete");
            }
            addServerInfo(result);
        }

        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("result", result);
        return response;
    }

    private ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        if (id == null) {
            response.putNull("id");
        } else {
            response.set("id", id);
        }
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return response;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.asText().isBlank()
                ? null
                : value.asText().trim();
    }

    private static String textOrEmpty(JsonNode node, String field) {
        String value = text(node, field);
        return value == null ? "" : value;
    }

    private static String requiredText(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null) {
            throw new IllegalArgumentException("Missing required argument: " + field);
        }
        return value;
    }

    private static long requiredLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToLong()) {
            throw new IllegalArgumentException(
                    "Missing or invalid required argument: " + field);
        }
        return value.asLong();
    }

    private static int integer(JsonNode node, String field, int defaultValue) {
        JsonNode value = node.get(field);
        return value == null || !value.isInt() ? defaultValue : value.asInt();
    }

    /**
     * Validiert ein JSON-Array als Liste nichtleerer Strings.
     */
    private static List<String> strings(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException("Expected an array of strings");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode value : node) {
            if (!value.isTextual() || value.asText().isBlank()) {
                throw new IllegalArgumentException(
                        "Email address entries must be non-empty strings");
            }
            result.add(value.asText().trim());
        }
        return List.copyOf(result);
    }
}
