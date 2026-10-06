package com.fourwt.mailconnector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.fourwt.mailconnector.MailModels.DraftRequest;
import static com.fourwt.mailconnector.MailModels.DraftResult;
import static com.fourwt.mailconnector.MailModels.MailMessage;
import static com.fourwt.mailconnector.MailModels.MailSummary;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit-Tests der MCP-Schicht ohne realen Mailserver.
 *
 * <p>Das FakeMailGateway zeigt ein wichtiges Testprinzip: Die zu testende
 * Schicht erhält eine kontrollierte Ersatzimplementierung ihrer Abhängigkeit.
 * So testen wir MCP-Parsing und -Dispatching ohne Netzwerkzugriff.</p>
 *
 * <p>Zusätzlich sichern die Tests beide MCP-Lebenszyklen ab:</p>
 * <ul>
 *   <li>Legacy bis 2025-11-25 mit initialize-Handshake.</li>
 *   <li>Modern 2026-07-28 mit server/discover und per-Request-_meta.</li>
 * </ul>
 */
class McpHttpServerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Sicherheitsrelevanter Regressionstest:
     * Die öffentliche Tool-Liste darf kein send_mail enthalten.
     */
    @Test
    void exposesReadAndDraftToolsButNoSendTool() throws Exception {
        try (McpHttpServer server = new McpHttpServer(
                new FakeMailGateway(), "127.0.0.1", 0)) {

            JsonNode response = server.handleRpc(mapper.readTree("""
                    {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
                    """));

            String json = response.toString();
            assertTrue(json.contains("search_mail"));
            assertTrue(json.contains("read_mail"));
            assertTrue(json.contains("create_draft"));
            assertFalse(json.contains("send_mail"));
        }
    }

    /**
     * Prüft, dass create_draft nur an das MailGateway delegiert.
     */
    @Test
    void createDraftDelegatesWithoutSending() throws Exception {
        FakeMailGateway gateway = new FakeMailGateway();

        try (McpHttpServer server = new McpHttpServer(
                gateway, "127.0.0.1", 0)) {

            JsonNode response = server.handleRpc(mapper.readTree("""
                    {
                      "jsonrpc":"2.0",
                      "id":2,
                      "method":"tools/call",
                      "params":{
                        "name":"create_draft",
                        "arguments":{
                          "to":["recipient@example.com"],
                          "subject":"Test",
                          "body":"Draft only"
                        }
                      }
                    }
                    """));

            assertEquals("recipient@example.com", gateway.lastDraft.to().getFirst());
            assertEquals("Test", gateway.lastDraft.subject());
            assertFalse(response.path("result").path("isError").asBoolean());
        }
    }

    /**
     * Ein moderner Client fragt die Fähigkeiten über server/discover ab.
     *
     * <p>supportedVersions darf hier nur moderne, pro Request transportierbare
     * Revisionen nennen. Legacy-Versionen werden weiterhin über initialize
     * ausgehandelt.</p>
     */
    @Test
    void modernDiscoverAdvertises20260728AndServerInfo() throws Exception {
        try (McpHttpServer server = new McpHttpServer(
                new FakeMailGateway(), "127.0.0.1", 0)) {

            JsonNode response = server.handleRpc(mapper.readTree("""
                    {
                      "jsonrpc":"2.0",
                      "id":"discover-1",
                      "method":"server/discover",
                      "params":{
                        "_meta":{
                          "io.modelcontextprotocol/protocolVersion":"2026-07-28",
                          "io.modelcontextprotocol/clientCapabilities":{}
                        }
                      }
                    }
                    """));

            JsonNode result = response.path("result");

            assertEquals("complete", result.path("resultType").asText());
            assertEquals(1, result.path("supportedVersions").size());
            assertEquals("2026-07-28",
                    result.path("supportedVersions").get(0).asText());
            assertTrue(result.path("capabilities").has("tools"));
            assertEquals(0, result.path("ttlMs").asInt());
            assertEquals("private", result.path("cacheScope").asText());
            assertEquals(
                    "KI-Mail-Connector",
                    result.path("_meta")
                            .path("io.modelcontextprotocol/serverInfo")
                            .path("name")
                            .asText()
            );
        }
    }

    /**
     * Die moderne Revision trägt resultType und Serveridentität auf jedem
     * erfolgreichen Resultat, nicht nur bei server/discover.
     */
    @Test
    void modernToolsListCarriesModernResultMetadata() throws Exception {
        try (McpHttpServer server = new McpHttpServer(
                new FakeMailGateway(), "127.0.0.1", 0)) {

            JsonNode response = server.handleRpc(mapper.readTree("""
                    {
                      "jsonrpc":"2.0",
                      "id":3,
                      "method":"tools/list",
                      "params":{
                        "_meta":{
                          "io.modelcontextprotocol/protocolVersion":"2026-07-28",
                          "io.modelcontextprotocol/clientCapabilities":{}
                        }
                      }
                    }
                    """));

            JsonNode result = response.path("result");
            assertEquals("complete", result.path("resultType").asText());
            assertEquals(
                    "KI-Mail-Connector",
                    result.path("_meta")
                            .path("io.modelcontextprotocol/serverInfo")
                            .path("name")
                            .asText()
            );
            assertTrue(result.path("tools").isArray());
        }
    }

    /**
     * Die moderne Revision darf nicht irrtümlich über initialize ausgehandelt
     * werden. Ein Legacy-initialize mit modernem Versionswunsch erhält die
     * neueste von uns unterstützte Handshake-Version zurück.
     */
    @Test
    void legacyInitializeNeverNegotiatesModernProtocol() throws Exception {
        try (McpHttpServer server = new McpHttpServer(
                new FakeMailGateway(), "127.0.0.1", 0)) {

            JsonNode response = server.handleRpc(mapper.readTree("""
                    {
                      "jsonrpc":"2.0",
                      "id":4,
                      "method":"initialize",
                      "params":{
                        "protocolVersion":"2026-07-28",
                        "capabilities":{},
                        "clientInfo":{"name":"legacy-test","version":"1.0"}
                      }
                    }
                    """));

            assertEquals(
                    "2025-11-25",
                    response.path("result").path("protocolVersion").asText()
            );
            assertFalse(response.path("result").has("resultType"));
        }
    }

    /**
     * Moderne Requests benötigen die Protokollversion und Client-Capabilities
     * im _meta-Block.
     */
    @Test
    void modernRequestRejectsMissingClientCapabilities() throws Exception {
        try (McpHttpServer server = new McpHttpServer(
                new FakeMailGateway(), "127.0.0.1", 0)) {

            JsonNode response = server.handleRpc(mapper.readTree("""
                    {
                      "jsonrpc":"2.0",
                      "id":5,
                      "method":"tools/list",
                      "params":{
                        "_meta":{
                          "io.modelcontextprotocol/protocolVersion":"2026-07-28"
                        }
                      }
                    }
                    """));

            assertEquals(-32602, response.path("error").path("code").asInt());
        }
    }

    /**
     * Test-Double: speichert den letzten Entwurfsauftrag nur im Speicher.
     * Es findet keinerlei Mailserver- oder Netzwerkzugriff statt.
     */
    private static final class FakeMailGateway implements MailGateway {
        private DraftRequest lastDraft;

        @Override
        public List<MailSummary> search(String query, String folder, int limit, int offset) {
            return List.of();
        }

        @Override
        public MailMessage read(String folder, long uid) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DraftResult createDraft(DraftRequest request) {
            lastDraft = request;
            return new DraftResult("Drafts", "<test@example.com>");
        }
    }
}
