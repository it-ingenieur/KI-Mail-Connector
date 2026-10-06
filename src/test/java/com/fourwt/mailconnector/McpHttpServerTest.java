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

class McpHttpServerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void exposesReadAndDraftToolsButNoSendTool() throws Exception {
        try (McpHttpServer server = new McpHttpServer(new FakeMailGateway(), "127.0.0.1", 0)) {
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

    @Test
    void createDraftDelegatesWithoutSending() throws Exception {
        FakeMailGateway gateway = new FakeMailGateway();
        try (McpHttpServer server = new McpHttpServer(gateway, "127.0.0.1", 0)) {
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
