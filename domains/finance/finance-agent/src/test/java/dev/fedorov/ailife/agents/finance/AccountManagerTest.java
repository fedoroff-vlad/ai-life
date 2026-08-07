package dev.fedorov.ailife.agents.finance;

import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.agents.finance.account.AccountManager;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.finance.FinAccountDto;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reactive {@code account-manager} flow (ADR-0002 slice 4b — chat-driven account creation, the finance
 * domain's sharing WRITE path). AccountManager asks the LLM for a plan, lets the shared
 * {@code SharingResolver} route the account to a concrete household (personal for a private account, the
 * family household for a joint one), then persists it via mcp-finance's {@code POST /internal/account}.
 * Three MockWebServers stand in for mcp-finance / llm-gateway / profile-service.
 */
@SpringBootTest
class AccountManagerTest {

    static MockWebServer mcpFinance;
    static MockWebServer llmGateway;
    static MockWebServer profileService;

    @BeforeAll
    static void start() throws Exception {
        mcpFinance = new MockWebServer();
        llmGateway = new MockWebServer();
        profileService = new MockWebServer();
        mcpFinance.start();
        llmGateway.start();
        profileService.start();
    }

    @AfterAll
    static void stop() throws Exception {
        mcpFinance.shutdown();
        llmGateway.shutdown();
        profileService.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("finance-agent.mcp-finance-url", () -> "http://localhost:" + mcpFinance.getPort());
        r.add("finance-agent.profile-service-url", () -> "http://localhost:" + profileService.getPort());
        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llmGateway.getPort());
        // Item 8: the SharingResolver now consults/records the learned-decision tally on memory-service.
        // These cases exercise the no-history path (static policy), so point it at a fast-fail address — the
        // learned lookup soft-fails to empty (→ static default) and any record is swallowed. Behaviour is
        // exactly as before item 8.
        r.add("finance-agent.memory-service-url", () -> "http://127.0.0.1:1");
    }

    @Autowired AccountManager manager;
    @Autowired dev.fedorov.ailife.sharing.SharingConfirm sharingConfirm;
    @Autowired ObjectMapper json;

    @Test
    void personalAccountRoutesToPersonalHousehold() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID personalHh = UUID.randomUUID();
        UUID sharedHh = UUID.randomUUID();
        UUID envelopeHh = UUID.randomUUID(); // the message arrived in some household — must be overridden

        // Plan: a personal card (joint=false).
        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large",
                "{\"name\":\"Тинькофф\",\"type\":\"card\",\"currency\":\"RUB\",\"joint\":false}",
                "stop", new LlmUsage(20, 15, 35)))));
        // household-routing: the user's personal + one shared household.
        profileService.enqueue(jsonResponse("{\"personalHouseholdId\":\"" + personalHh
                + "\",\"sharedHouseholdIds\":[\"" + sharedHh + "\"]}"));
        // The created account echoes back.
        mcpFinance.enqueue(jsonResponse(json.writeValueAsString(new FinAccountDto(
                UUID.randomUUID(), personalHh, null, "Тинькофф", "card", "RUB",
                BigDecimal.ZERO, false, Instant.now()))));

        var msg = new NormalizedMessage(userId, envelopeHh, MessageScope.PRIVATE,
                "заведи карту Тинькофф в рублях", List.of(), "telegram", "1", Instant.now());

        AccountManager.AccountResult result = manager.create(msg).block();

        assertThat(result).isNotNull();
        assertThat(result.text()).contains("Личный").contains("Тинькофф");
        assertThat(result.model()).isEqualTo("mock-large");

        // Routing split was read for the acting user.
        RecordedRequest routing = profileService.takeRequest(2, TimeUnit.SECONDS);
        assertThat(routing.getPath()).contains("/household-routing").contains(userId.toString());

        // The account was created under the PERSONAL household, not the envelope one.
        RecordedRequest post = mcpFinance.takeRequest(2, TimeUnit.SECONDS);
        assertThat(post.getMethod()).isEqualTo("POST");
        assertThat(post.getPath()).isEqualTo("/internal/account");
        String body = post.getBody().readUtf8();
        assertThat(body).contains(personalHh.toString())
                .doesNotContain(envelopeHh.toString())
                .contains("Тинькофф").contains("RUB");
    }

    @Test
    void jointAccountRoutesToSharedHousehold() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID personalHh = UUID.randomUUID();
        UUID sharedHh = UUID.randomUUID();

        // Plan: a joint/family account (joint=true).
        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large",
                "{\"name\":\"Семейный счёт\",\"type\":\"deposit\",\"currency\":\"EUR\",\"joint\":true}",
                "stop", new LlmUsage(20, 15, 35)))));
        profileService.enqueue(jsonResponse("{\"personalHouseholdId\":\"" + personalHh
                + "\",\"sharedHouseholdIds\":[\"" + sharedHh + "\"]}"));
        mcpFinance.enqueue(jsonResponse(json.writeValueAsString(new FinAccountDto(
                UUID.randomUUID(), sharedHh, null, "Семейный счёт", "deposit", "EUR",
                BigDecimal.ZERO, false, Instant.now()))));

        var msg = new NormalizedMessage(userId, personalHh, MessageScope.PRIVATE,
                "создай общий счёт для семьи в евро", List.of(), "telegram", "2", Instant.now());

        AccountManager.AccountResult result = manager.create(msg).block();

        assertThat(result).isNotNull();
        assertThat(result.text()).contains("Общий").contains("Семейный счёт");

        profileService.takeRequest(2, TimeUnit.SECONDS);
        // A joint account lands in the SHARED household.
        RecordedRequest post = mcpFinance.takeRequest(2, TimeUnit.SECONDS);
        String body = post.getBody().readUtf8();
        assertThat(body).contains(sharedHh.toString()).doesNotContain(personalHh.toString());
    }

    @Test
    void missingCurrencyAsksInsteadOfCreating() throws Exception {
        // The LLM omits the currency (the user did not name one) → the flow asks, and does NOT create.
        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large",
                "{\"name\":\"Наличные\",\"type\":\"cash\"}",
                "stop", new LlmUsage(20, 15, 35)))));

        var msg = new NormalizedMessage(UUID.randomUUID(), UUID.randomUUID(), MessageScope.PRIVATE,
                "заведи наличные", List.of(), "telegram", "3", Instant.now());

        AccountManager.AccountResult result = manager.create(msg).block();

        assertThat(result).isNotNull();
        assertThat(result.text()).contains("валюте").contains("Наличные");
        // No account created — mcp-finance was never called.
        assertThat(mcpFinance.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void ambiguousAccountAsksInsteadOfCreating() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID personalHh = UUID.randomUUID();
        UUID sharedHh = UUID.randomUUID();

        // Plan with NO `joint` field → the LLM couldn't tell → DS-N ambiguity → the agent must ask.
        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large",
                "{\"name\":\"Резерв\",\"type\":\"deposit\",\"currency\":\"RUB\"}",
                "stop", new LlmUsage(20, 15, 35)))));
        profileService.enqueue(jsonResponse("{\"personalHouseholdId\":\"" + personalHh
                + "\",\"sharedHouseholdIds\":[\"" + sharedHh + "\"]}"));

        var msg = new NormalizedMessage(userId, personalHh, MessageScope.PRIVATE,
                "заведи вклад Резерв в рублях", List.of(), "telegram", "amb-1", Instant.now());

        AccountManager.AccountResult result = manager.create(msg).block();

        assertThat(result).isNotNull();
        assertThat(result.text()).contains("личное или общее").contains("Резерв");
        // It deferred: a pendingAction is returned (→ orchestrator locks), and NOTHING was created yet.
        assertThat(result.pendingAction()).isNotNull();
        assertThat(result.pendingAction().path("flow").asString()).isEqualTo("sharing-confirm");
        assertThat(mcpFinance.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void resumeAfterAnswerCreatesIntoTheChosenHousehold() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID personalHh = UUID.randomUUID();
        UUID sharedHh = UUID.randomUUID();

        // 1) Ambiguous create → ask (pendingAction carries the routing the resume needs).
        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large",
                "{\"name\":\"Общий\",\"type\":\"deposit\",\"currency\":\"EUR\"}",
                "stop", new LlmUsage(20, 15, 35)))));
        profileService.enqueue(jsonResponse("{\"personalHouseholdId\":\"" + personalHh
                + "\",\"sharedHouseholdIds\":[\"" + sharedHh + "\"]}"));
        var msg = new NormalizedMessage(userId, personalHh, MessageScope.PRIVATE,
                "заведи вклад Общий в евро", List.of(), "telegram", "amb-2", Instant.now());
        AccountManager.AccountResult asked = manager.create(msg).block();
        assertThat(asked).isNotNull();
        assertThat(asked.pendingAction()).isNotNull();

        // 2) The owner answers «общий» → the resolver picks the shared household + the account is created.
        mcpFinance.enqueue(jsonResponse(json.writeValueAsString(new FinAccountDto(
                UUID.randomUUID(), sharedHh, null, "Общий", "deposit", "EUR",
                BigDecimal.ZERO, false, Instant.now()))));
        String reply = sharingConfirm.resume(asked.pendingAction(), "общий",
                manager::finishAccount).block().text();

        assertThat(reply).contains("Общий");
        RecordedRequest post = mcpFinance.takeRequest(2, TimeUnit.SECONDS);
        assertThat(post.getPath()).isEqualTo("/internal/account");
        String body = post.getBody().readUtf8();
        assertThat(body).contains(sharedHh.toString()).doesNotContain(personalHh.toString())
                .contains("Общий").contains("EUR");
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse().setHeader("content-type", "application/json").setBody(body);
    }
}
