package dev.fedorov.ailife.agents.finance;

import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.agents.finance.category.CategoryManager;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.finance.FinCategoryDto;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reactive {@code category-manager} flow (#291) — chat-driven creation + grouping of finance
 * categories. CategoryManager lists the household's existing categories, asks the LLM (the
 * {@code category-manager} SKILL) for a plan, then applies it via mcp-finance's
 * {@code POST /internal/category}, resolving a child's parent by name. Two MockWebServers stand in for
 * mcp-finance / llm-gateway.
 */
@SpringBootTest
class CategoryManagerTest {

    static MockWebServer mcpFinance;
    static MockWebServer llmGateway;

    @BeforeAll
    static void start() throws Exception {
        mcpFinance = new MockWebServer();
        llmGateway = new MockWebServer();
        mcpFinance.start();
        llmGateway.start();
    }

    @AfterAll
    static void stop() throws Exception {
        mcpFinance.shutdown();
        llmGateway.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("finance-agent.mcp-finance-url", () -> "http://localhost:" + mcpFinance.getPort());
        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llmGateway.getPort());
    }

    @Autowired CategoryManager manager;
    @Autowired ObjectMapper json;

    @Test
    void createsParentThenGroupsChildUnderItByName() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID edaId = UUID.randomUUID();
        UUID cofId = UUID.randomUUID();

        // GET /internal/categories → the household already has "Транспорт" (not the parent we need).
        mcpFinance.enqueue(jsonResponse(json.writeValueAsString(List.of(
                new FinCategoryDto(UUID.randomUUID(), householdId, null, "Транспорт", "expense", null)))));
        // llm plan: create the parent "Еда" (no parent) then "Кофейни" under it.
        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large",
                "{\"categories\":[{\"name\":\"Еда\",\"kind\":\"expense\"},"
                        + "{\"name\":\"Кофейни\",\"kind\":\"expense\",\"parent\":\"Еда\"}]}",
                "stop", new LlmUsage(30, 20, 50)))));
        // POST /internal/category × 2 — parent first (returns its id), then the child.
        mcpFinance.enqueue(jsonResponse(json.writeValueAsString(
                new FinCategoryDto(edaId, householdId, null, "Еда", "expense", null))));
        mcpFinance.enqueue(jsonResponse(json.writeValueAsString(
                new FinCategoryDto(cofId, householdId, edaId, "Кофейни", "expense", null))));

        var msg = new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                "заведи категорию Кофейни в группе Еда", List.of(), "telegram", "13", Instant.now());

        CategoryManager.CategoryResult result = manager.manage(msg).block();

        assertThat(result).isNotNull();
        assertThat(result.text()).contains("Кофейни").contains("Еда");
        assertThat(result.model()).isEqualTo("mock-large");

        // Existing categories were read first (with the household scope).
        RecordedRequest list = mcpFinance.takeRequest(2, TimeUnit.SECONDS);
        assertThat(list.getMethod()).isEqualTo("GET");
        assertThat(list.getPath()).startsWith("/internal/categories").contains("householdId=" + householdId);

        // The plan prompt carried the user's text + the existing categories.
        RecordedRequest llmReq = llmGateway.takeRequest(2, TimeUnit.SECONDS);
        assertThat(llmReq.getBody().readUtf8()).contains("Транспорт").contains("Кофейни");

        // Parent "Еда" is created first, with no parentId.
        RecordedRequest parentReq = mcpFinance.takeRequest(2, TimeUnit.SECONDS);
        assertThat(parentReq.getMethod()).isEqualTo("POST");
        assertThat(parentReq.getPath()).isEqualTo("/internal/category");
        String parentBody = parentReq.getBody().readUtf8();
        assertThat(parentBody).contains("Еда").doesNotContain("parentId");

        // Child "Кофейни" is created under the freshly-created parent id (resolved by name).
        RecordedRequest childReq = mcpFinance.takeRequest(2, TimeUnit.SECONDS);
        assertThat(childReq.getMethod()).isEqualTo("POST");
        String childBody = childReq.getBody().readUtf8();
        assertThat(childBody).contains("Кофейни").contains(edaId.toString());
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse().setHeader("content-type", "application/json").setBody(body);
    }
}
