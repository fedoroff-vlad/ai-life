package dev.fedorov.ailife.agents.finance;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.contracts.agent.AgentActionRequest;
import dev.fedorov.ailife.contracts.agent.AgentActionResult;
import dev.fedorov.ailife.contracts.finance.GiftBudgetResult;
import dev.fedorov.ailife.contracts.finance.GiftBudgetRuleDto;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code get_gift_budget} inter-agent action (Stage 4 / Track D, D2b):
 * forces {@code householdId} from the envelope, reads mcp-finance's
 * {@code /internal/gift-budget} (PR93), and returns the budget as a structured
 * {@link AgentActionResult} (never an HTTP error).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class ActionControllerTest {

    static MockWebServer mcpFinance;

    @BeforeAll
    static void startMocks() throws Exception {
        mcpFinance = new MockWebServer();
        mcpFinance.start();
    }

    @AfterAll
    static void stopMocks() throws Exception {
        mcpFinance.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("finance-agent.mcp-finance-url", () -> "http://localhost:" + mcpFinance.getPort());
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    @BeforeEach
    void drainRecordedRequests() throws Exception {
        // SpringBootTest reuses the context (and this static MockWebServer)
        // across methods; drain leftover recorded requests so each test's
        // takeRequest() sees only its own call regardless of method order.
        while (mcpFinance.takeRequest(50, TimeUnit.MILLISECONDS) != null) {
            // discard
        }
    }

    @Test
    void getGiftBudgetReadsEnvelopeAndReturnsBudget() throws Exception {
        UUID household = UUID.randomUUID();
        mcpFinance.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(new GiftBudgetResult(
                        new BigDecimal("200.00"), "EUR", new BigDecimal("150.00")))));

        var req = new AgentActionRequest("finance", "get_gift_budget", household, null, "calendar", null);

        http.post().uri("/agents/finance/actions/get_gift_budget")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isOk()
                .expectBody(AgentActionResult.class)
                .value(res -> {
                    assertThat(res.ok()).isTrue();
                    assertThat(res.result().get("hasGiftBudget").asBoolean()).isTrue();
                    assertThat(res.result().get("amount").decimalValue()).isEqualByComparingTo("200.00");
                    assertThat(res.result().get("currency").asText()).isEqualTo("EUR");
                    assertThat(res.result().get("remaining").decimalValue()).isEqualByComparingTo("150.00");
                });

        RecordedRequest sent = mcpFinance.takeRequest();
        assertThat(sent.getPath()).isEqualTo("/internal/gift-budget?householdId=" + household);
    }

    @Test
    void relationshipTierRuleWinsOverEnvelope() throws Exception {
        UUID household = UUID.randomUUID();
        // A tier rule exists for "parent" → mcp-finance answers the rule read; the
        // envelope is never consulted (no second response enqueued).
        mcpFinance.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(new GiftBudgetRuleDto(
                        UUID.randomUUID(), household, "parent",
                        new BigDecimal("20000.00"), "RUB", Instant.now(), Instant.now()))));

        ObjectNode args = json.createObjectNode();
        args.put("relationship", "parent");
        var req = new AgentActionRequest("finance", "get_gift_budget", household, null, "calendar", args);

        http.post().uri("/agents/finance/actions/get_gift_budget")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isOk()
                .expectBody(AgentActionResult.class)
                .value(res -> {
                    assertThat(res.ok()).isTrue();
                    assertThat(res.result().get("hasGiftBudget").asBoolean()).isTrue();
                    assertThat(res.result().get("amount").decimalValue()).isEqualByComparingTo("20000.00");
                    assertThat(res.result().get("currency").asText()).isEqualTo("RUB");
                    assertThat(res.result().get("relationship").asText()).isEqualTo("parent");
                    assertThat(res.result().get("source").asText()).isEqualTo("rule");
                    // No spend window on a preference rule.
                    assertThat(res.result().has("remaining")).isFalse();
                });

        RecordedRequest sent = mcpFinance.takeRequest();
        assertThat(sent.getPath())
                .isEqualTo("/internal/gift-budget-rule?householdId=" + household + "&relationship=parent");
    }

    @Test
    void noTierRuleFallsBackToEnvelope() throws Exception {
        UUID household = UUID.randomUUID();
        // No "friend" tier rule → 404, then the action falls back to the Gifts envelope.
        mcpFinance.enqueue(new MockResponse().setResponseCode(404));
        mcpFinance.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(new GiftBudgetResult(
                        new BigDecimal("200.00"), "EUR", new BigDecimal("150.00")))));

        ObjectNode args = json.createObjectNode();
        args.put("relationship", "friend");
        var req = new AgentActionRequest("finance", "get_gift_budget", household, null, "calendar", args);

        http.post().uri("/agents/finance/actions/get_gift_budget")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isOk()
                .expectBody(AgentActionResult.class)
                .value(res -> {
                    assertThat(res.ok()).isTrue();
                    assertThat(res.result().get("hasGiftBudget").asBoolean()).isTrue();
                    assertThat(res.result().get("amount").decimalValue()).isEqualByComparingTo("200.00");
                    assertThat(res.result().get("source").asText()).isEqualTo("envelope");
                });

        // First the rule read, then the envelope read.
        RecordedRequest rule = mcpFinance.takeRequest();
        assertThat(rule.getPath()).startsWith("/internal/gift-budget-rule?");
        RecordedRequest env = mcpFinance.takeRequest();
        assertThat(env.getPath()).isEqualTo("/internal/gift-budget?householdId=" + household);
    }

    @Test
    void noGiftBudgetReturnsHasGiftBudgetFalse() {
        UUID household = UUID.randomUUID();
        // mcp-finance has no Gifts budget → 404; the action treats it as a valid
        // "no budget set" state, not an error.
        mcpFinance.enqueue(new MockResponse().setResponseCode(404));

        var req = new AgentActionRequest("finance", "get_gift_budget", household, null, "calendar", null);

        http.post().uri("/agents/finance/actions/get_gift_budget")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isOk()
                .expectBody(AgentActionResult.class)
                .value(res -> {
                    assertThat(res.ok()).isTrue();
                    assertThat(res.result().get("hasGiftBudget").asBoolean()).isFalse();
                    assertThat(res.result().has("amount")).isFalse();
                });
    }

    @Test
    void unknownActionReturnsErrorResult() {
        var req = new AgentActionRequest("finance", "frobnicate", UUID.randomUUID(), null, "calendar", null);
        http.post().uri("/agents/finance/actions/frobnicate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isOk()
                .expectBody(AgentActionResult.class)
                .value(res -> {
                    assertThat(res.ok()).isFalse();
                    assertThat(res.error()).contains("unknown action");
                });
    }

    @Test
    void missingHouseholdReturnsErrorResult() {
        // No householdId in the envelope → rejected before any mcp-finance call
        // (none enqueued — a stray call would surface as a different error).
        var req = new AgentActionRequest("finance", "get_gift_budget", null, null, "calendar", null);
        http.post().uri("/agents/finance/actions/get_gift_budget")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isOk()
                .expectBody(AgentActionResult.class)
                .value(res -> {
                    assertThat(res.ok()).isFalse();
                    assertThat(res.error()).contains("householdId");
                });
    }
}
