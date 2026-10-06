package com.fourwt.mailconnector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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
 * <p>Der Server verwendet absichtlich den im JDK enthaltenen {@link HttpServer}.
 * Ein zusätzliches Webframework würde in diesem kleinen Lernprojekt die
 * grundlegende MCP-Mechanik eher verdecken.</p>
 *
 * <p><strong>Sicherheitsgrenze:</strong> Ein {@code send_mail}-Tool wird nicht
 * registriert und kann daher vom MCP-Client nicht regulär aufgerufen werden.</p>
 */
public final class McpHttpServer implements AutoCloseable {

    /*
     * MCP-Clients teilen beim Handshake ihre Protokollversion mit.
     * Wir antworten mit einer unterstützten Version.
     */
    private static final String LATEST_PROTOCOL = "2026-07-28";
    private static final Set<String> SUPPORTED_PROTOCOLS = Set.of(
            "2026-07-28",
            "2025-06-18",
            "2025-03-26"
    );

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
     * <p>Diese Trennung macht die MCP-Logik ohne echten TCP-Port testbar.</p>
     */
    JsonNode handleRpc(JsonNode request) {
        JsonNode id = request.get("id");
        String method = request.path("method").asText("");

        // JSON-RPC-Nachrichten ohne id sind Notifications.
        // Auf Notifications wird keine Antwort gesendet.
        if (id == null || id.isNull()) {
            return null;
        }

        try {
            return switch (method) {
                case "initialize" -> success(id, initialize(request.path("params")));
                case "ping" -> success(id, mapper.createObjectNode());
                case "tools/list" -> success(id, toolsList());
                case "tools/call" -> success(id, callTool(request.path("params")));
                default -> error(id, -32601, "Method not found: " + method);
            };
        } catch (Exception e) {
            return error(id, -32603, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /**
     * Dünne HTTP-Transportebene um die JSON-RPC-Verarbeitung.
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

        String method = request.path("method").asText("");
        JsonNode response = handleRpc(request);

        if (response == null) {
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
            return;
        }

        // Manche Clients prüfen zuerst "server/discover". Eine saubere
        // Method-not-found-Antwort mit HTTP 404 erlaubt danach den normalen
        // MCP-Handshake über "initialize".
        int status = "server/discover".equals(method) ? 404 : 200;
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
     * MCP-Handshake: Version, Fähigkeiten und Serverinformationen.
     */
    private ObjectNode initialize(JsonNode params) {
        String requested = params.path("protocolVersion").asText("");
        String protocol = SUPPORTED_PROTOCOLS.contains(requested) ? requested : LATEST_PROTOCOL;

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
    private ObjectNode toolsList() {
        ArrayNode tools = mapper.createArrayNode();
        tools.add(searchTool());
        tools.add(readTool());
        tools.add(createDraftTool());

        ObjectNode result = mapper.createObjectNode();
        result.set("tools", tools);
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

        ObjectNode tool = tool(
                "search_mail",
                "Search or list mail. With no query it lists recent messages. With no folder it searches all readable IMAP folders.",
                schema,
                true,
                true
        );
        return tool;
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

    private ObjectNode success(JsonNode id, JsonNode result) {
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
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText().trim();
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
            throw new IllegalArgumentException("Missing or invalid required argument: " + field);
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
                throw new IllegalArgumentException("Email address entries must be non-empty strings");
            }
            result.add(value.asText().trim());
        }
        return List.copyOf(result);
    }
}
