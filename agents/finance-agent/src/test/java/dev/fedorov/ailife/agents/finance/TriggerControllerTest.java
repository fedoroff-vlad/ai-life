package dev.fedorov.ailife.agents.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.contracts.finance.BudgetStatusResult;
import dev.fedorov.ailife.contracts.finance.FinRecurringDto;
import dev.fedorov.ailife.contracts.finance.FinTransactionDto;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.contracts.memory.MemoryDto;
import dev.fedorov.ailife.contracts.memory.RecallMemoryHit;
import dev.fedorov.ailife.contracts.profile.UserDto;
import dev.fedorov.ailife.contracts.schedule.AgentWakeRequest;
import okhttp3.mockwebserver.Dispatcher;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Three MockWebServers (LLM gateway + profile-service + notifier-service) wire
 * up the full trigger fan-out the way calendar-agent's PR10 test did. The
 * profile dispatcher always answers with a fixed pair of users in the wake's
 * household; the notifier dispatcher always 200s and we read the recorded
 * requests to assert per-user delivery.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TriggerControllerTest {

    static MockWebServer llmGateway;
    static MockWebServer profileService;
    static MockWebServer notifierService;
    static MockWebServer memoryService;
    static MockWebServer mcpFinance;
    static final MemoryDispatcher memoryDispatcher = new MemoryDispatcher();
    static final BudgetStatusDispatcher budgetStatusDispatcher = new BudgetStatusDispatcher();

    static final UUID userAlice = UUID.randomUUID();
    static final UUID userBob = UUID.randomUUID();
    static final UUID householdId = UUID.randomUUID();

    @BeforeAll
    static void startMocks() throws Exception {
        llmGateway = new MockWebServer();
        llmGateway.start();
        profileService = new MockWebServer();
        profileService.setDispatcher(new ProfileDispatcher());
        profileService.start();
        notifierService = new MockWebServer();
        notifierService.setDispatcher(new NotifierDispatcher());
        notifierService.start();
        memoryService = new MockWebServer();
        memoryService.setDispatcher(memoryDispatcher);
        memoryService.start();
        mcpFinance = new MockWebServer();
        mcpFinance.setDispatcher(budgetStatusDispatcher);
        mcpFinance.start();
    }

    @AfterAll
    static void stopMocks() throws Exception {
        llmGateway.shutdown();
        profileService.shutdown();
        notifierService.shutdown();
        memoryService.shutdown();
        mcpFinance.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("ailife.llm-client.base-url",
                () -> "http://localhost:" + llmGateway.getPort());
        r.add("finance-agent.profile-service-url",
                () -> "http://localhost:" + profileService.getPort());
        r.add("finance-agent.notifier-url",
                () -> "http://localhost:" + notifierService.getPort());
        r.add("finance-agent.memory-service-url",
                () -> "http://localhost:" + memoryService.getPort());
        r.add("finance-agent.mcp-finance-url",
                () -> "http://localhost:" + mcpFinance.getPort());
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    @BeforeEach
    void drainQueuedRequests() throws Exception {
        // SpringBootTest reuses the application context across methods, so leftover
        // recorded requests from a previous test would otherwise show up as phantom
        // counts. Drain all four queues so each test sees a clean slate, and reset
        // the per-test memory dispatcher state.
        drain(llmGateway);
        drain(profileService);
        drain(notifierService);
        drain(memoryService);
        drain(mcpFinance);
        memoryDispatcher.reset();
        budgetStatusDispatcher.reset();
    }

    private static void drain(MockWebServer srv) throws InterruptedException {
        while (srv.takeRequest(50, TimeUnit.MILLISECONDS) != null) {
            // discard
        }
    }

    @Test
    void unknownTriggerKindReturns404WithoutCallingDownstream() throws Exception {
        var wake = new AgentWakeRequest(
                UUID.randomUUID(), householdId, "finance", "no.such.kind",
                JsonNodeFactory.instance.objectNode());

        http.post().uri("/agents/finance/triggers/no.such.kind")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(wake)
                .exchange()
                .expectStatus().isNotFound();

        assertThat(llmGateway.takeRequest(200, TimeUnit.MILLISECONDS)).isNull();
        assertThat(profileService.takeRequest(200, TimeUnit.MILLISECONDS)).isNull();
        assertThat(notifierService.takeRequest(200, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void budgetAlertOvershootFansOutToBothHouseholdMembers() throws Exception {
        String alertText = "В этом месяце на «Кофе» потрачено 120 EUR — это на 20 EUR больше лимита.";
        enqueueLlm(alertText);
        memoryDispatcher.recallHits = List.of(
                new RecallMemoryHit(
                        new MemoryDto(UUID.randomUUID(), householdId, null, null,
                                "chat", "Кофе — это слабость Боба, он любит латте по утрам.",
                                null, Instant.now()),
                        0.18));

        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("categoryName", "Кофе");
        payload.put("limit", 100);
        payload.put("spent", 120);
        payload.put("currency", "EUR");
        payload.put("period", "March 2026");
        var wake = new AgentWakeRequest(
                UUID.randomUUID(), householdId, "finance", "budget.alert", payload);

        http.post().uri("/agents/finance/triggers/budget.alert")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(wake)
                .exchange()
                .expectStatus().isAccepted();

        RecordedRequest memReq = memoryService.takeRequest(2, TimeUnit.SECONDS);
        assertThat(memReq).isNotNull();
        assertThat(memReq.getPath()).isEqualTo("/v1/memories/recall");
        String memBody = memReq.getBody().readUtf8();
        // Recall query is anchored on categoryName when present, falls back to kind.
        assertThat(memBody).contains("budget.alert for Кофе");

        RecordedRequest llmReq = llmGateway.takeRequest(2, TimeUnit.SECONDS);
        assertThat(llmReq).isNotNull();
        assertThat(llmReq.getPath()).isEqualTo("/v1/chat");
        String llmBody = llmReq.getBody().readUtf8();
        assertThat(llmBody).contains("finance agent");
        assertThat(llmBody).contains("budget alert");
        assertThat(llmBody).contains("Кофе");
        // The recalled memory text was injected into the user message under "memories".
        assertThat(llmBody).contains("memories");
        assertThat(llmBody).contains("слабость Боба");

        RecordedRequest profileReq = profileService.takeRequest(2, TimeUnit.SECONDS);
        assertThat(profileReq).isNotNull();
        assertThat(profileReq.getPath()).isEqualTo("/v1/users/by-household/" + householdId);

        // Both household members must receive the same alert text. Order is not
        // contractual — collect into a set keyed by userId.
        List<RecordedRequest> notifies = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            RecordedRequest r = notifierService.takeRequest(2, TimeUnit.SECONDS);
            assertThat(r).isNotNull();
            assertThat(r.getPath()).isEqualTo("/v1/notify");
            notifies.add(r);
        }
        List<String> bodies = notifies.stream().map(r -> r.getBody().readUtf8()).toList();
        assertThat(bodies).allMatch(b -> b.contains(alertText));
        assertThat(bodies).anyMatch(b -> b.contains(userAlice.toString()));
        assertThat(bodies).anyMatch(b -> b.contains(userBob.toString()));
    }

    @Test
    void budgetAlertSkipSentinelNeverHitsProfileOrNotifier() throws Exception {
        enqueueLlm("SKIP");

        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("categoryName", "Groceries");
        payload.put("limit", 500);
        payload.put("spent", 100);
        payload.put("currency", "EUR");
        payload.put("period", "this week");
        var wake = new AgentWakeRequest(
                UUID.randomUUID(), householdId, "finance", "budget.alert", payload);

        http.post().uri("/agents/finance/triggers/budget.alert")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(wake)
                .exchange()
                .expectStatus().isAccepted();

        // LLM still consulted — SKIP is the LLM's call.
        assertThat(llmGateway.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
        // … but profile + notifier stay silent.
        assertThat(profileService.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
        assertThat(notifierService.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void singleNotifierFailureDoesNotBlockOtherUsersOrAffect202() throws Exception {
        enqueueLlm("Heads-up: вы потратили 80% бюджета на «Groceries».");

        // Flip the notifier dispatcher to 500 for Alice; Bob still 200s.
        notifierService.setDispatcher(new SelectiveFailingNotifierDispatcher(userAlice));

        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("categoryName", "Groceries");
        payload.put("limit", 100);
        payload.put("spent", 80);
        payload.put("currency", "EUR");
        payload.put("period", "this week");
        var wake = new AgentWakeRequest(
                UUID.randomUUID(), householdId, "finance", "budget.alert", payload);

        try {
            http.post().uri("/agents/finance/triggers/budget.alert")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(wake)
                    .exchange()
                    .expectStatus().isAccepted();

            // Both notifier calls were attempted — the controller didn't give up
            // after Alice's 500.
            int seen = 0;
            for (int i = 0; i < 2; i++) {
                if (notifierService.takeRequest(2, TimeUnit.SECONDS) != null) seen++;
            }
            assertThat(seen).isEqualTo(2);
        } finally {
            notifierService.setDispatcher(new NotifierDispatcher());
        }
    }

    @Test
    void memoryServiceFailureStillRunsSkillWithoutMemoriesBlock() throws Exception {
        enqueueLlm("Heads-up: вы потратили 90% бюджета на «Groceries».");
        memoryDispatcher.simulate5xx = true;

        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("categoryName", "Groceries");
        payload.put("limit", 100);
        payload.put("spent", 90);
        payload.put("currency", "EUR");
        payload.put("period", "this week");
        var wake = new AgentWakeRequest(
                UUID.randomUUID(), householdId, "finance", "budget.alert", payload);

        http.post().uri("/agents/finance/triggers/budget.alert")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(wake)
                .exchange()
                .expectStatus().isAccepted();

        // memory-service was called but blew up.
        assertThat(memoryService.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
        // LLM still ran — and the user message must NOT carry a "memories" block
        // (MemoryClient swallowed the 5xx and returned an empty list).
        RecordedRequest llmReq = llmGateway.takeRequest(2, TimeUnit.SECONDS);
        assertThat(llmReq).isNotNull();
        String llmBody = llmReq.getBody().readUtf8();
        assertThat(llmBody).doesNotContain("memories");
        // Profile + notifier still get the message — soft-fail enrichment doesn't
        // change downstream behaviour.
        assertThat(profileService.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
        int notifies = 0;
        for (int i = 0; i < 2; i++) {
            if (notifierService.takeRequest(2, TimeUnit.SECONDS) != null) notifies++;
        }
        assertThat(notifies).isEqualTo(2);
    }

    @Test
    void budgetAlertWithCategoryIdEnrichesPayloadFromMcpFinance() throws Exception {
        UUID categoryId = UUID.randomUUID();
        budgetStatusDispatcher.snapshot = new BudgetStatusResult(
                householdId, categoryId, "Coffee-enriched", "month",
                new BigDecimal("100.00"), new BigDecimal("120.00"), "EUR",
                Instant.parse("2026-05-01T00:00:00Z"), Instant.parse("2026-06-01T00:00:00Z"),
                new BigDecimal("1.2000"));
        String alertText = "За месяц на «Coffee-enriched» потрачено 120 EUR — на 20 больше лимита.";
        enqueueLlm(alertText);

        // Scheduler-driven payload: only categoryId + period. Enrichment must
        // pull the rest from mcp-finance before the LLM call.
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("categoryId", categoryId.toString());
        payload.put("period", "month");
        var wake = new AgentWakeRequest(
                UUID.randomUUID(), householdId, "finance", "budget.alert", payload);

        http.post().uri("/agents/finance/triggers/budget.alert")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(wake)
                .exchange()
                .expectStatus().isAccepted();

        RecordedRequest budgetReq = mcpFinance.takeRequest(2, TimeUnit.SECONDS);
        assertThat(budgetReq).isNotNull();
        assertThat(budgetReq.getPath()).startsWith("/internal/budget-status");
        assertThat(budgetReq.getPath()).contains("householdId=" + householdId);
        assertThat(budgetReq.getPath()).contains("categoryId=" + categoryId);
        assertThat(budgetReq.getPath()).contains("period=month");

        // LLM body now carries the enriched numbers, not just the raw ids.
        assertThat(memoryService.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
        RecordedRequest llmReq = llmGateway.takeRequest(2, TimeUnit.SECONDS);
        assertThat(llmReq).isNotNull();
        String llmBody = llmReq.getBody().readUtf8();
        assertThat(llmBody).contains("Coffee-enriched");
        assertThat(llmBody).contains("100");
        assertThat(llmBody).contains("120");
        assertThat(llmBody).contains("EUR");

        // Downstream fan-out still happens.
        assertThat(profileService.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
        int notifies = 0;
        for (int i = 0; i < 2; i++) {
            if (notifierService.takeRequest(2, TimeUnit.SECONDS) != null) notifies++;
        }
        assertThat(notifies).isEqualTo(2);
    }

    @Test
    void budgetAlert404FromMcpFinanceMarksPayloadAndStillRunsSkill() throws Exception {
        UUID categoryId = UUID.randomUUID();
        budgetStatusDispatcher.return404 = true;
        // The skill is expected to emit SKIP when status=no_active_budget.
        enqueueLlm("SKIP");

        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("categoryId", categoryId.toString());
        payload.put("period", "month");
        var wake = new AgentWakeRequest(
                UUID.randomUUID(), householdId, "finance", "budget.alert", payload);

        http.post().uri("/agents/finance/triggers/budget.alert")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(wake)
                .exchange()
                .expectStatus().isAccepted();

        assertThat(mcpFinance.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
        RecordedRequest llmReq = llmGateway.takeRequest(2, TimeUnit.SECONDS);
        assertThat(llmReq).isNotNull();
        String llmBody = llmReq.getBody().readUtf8();
        // The marker tells the skill there's no live budget — handle gracefully.
        assertThat(llmBody).contains("no_active_budget");
        // No notifier fan-out because the LLM emitted SKIP.
        assertThat(profileService.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
        assertThat(notifierService.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void budgetAlertMcpFinance5xxReturns503ForSchedulerRetry() throws Exception {
        UUID categoryId = UUID.randomUUID();
        budgetStatusDispatcher.simulate5xx = true;

        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("categoryId", categoryId.toString());
        payload.put("period", "month");
        var wake = new AgentWakeRequest(
                UUID.randomUUID(), householdId, "finance", "budget.alert", payload);

        http.post().uri("/agents/finance/triggers/budget.alert")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(wake)
                .exchange()
                .expectStatus().isEqualTo(503);

        // mcp-finance was called but blew up; LLM / profile / notifier untouched.
        assertThat(mcpFinance.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
        assertThat(llmGateway.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
        assertThat(profileService.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
        assertThat(notifierService.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void recurringDueWithIdEnrichesPayloadFromMcpFinance() throws Exception {
        UUID recurringId = UUID.randomUUID();
        Instant tomorrow = Instant.now().plusSeconds(86_400);
        budgetStatusDispatcher.recurring = new FinRecurringDto(
                recurringId, householdId, null, UUID.randomUUID(), null,
                "Apartment rent", new BigDecimal("-1600.00"), "EUR",
                "0 0 9 * * *", tomorrow, "Bank transfer", false, null, Instant.now());
        enqueueLlm("Завтра аренда — 1600 EUR. Не забудьте перевод.");

        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("recurringId", recurringId.toString());
        var wake = new AgentWakeRequest(
                UUID.randomUUID(), householdId, "finance", "recurring.due", payload);

        http.post().uri("/agents/finance/triggers/recurring.due")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(wake)
                .exchange()
                .expectStatus().isAccepted();

        RecordedRequest recReq = mcpFinance.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recReq).isNotNull();
        assertThat(recReq.getPath()).isEqualTo("/internal/recurring/" + recurringId);
        assertThat(memoryService.takeRequest(2, TimeUnit.SECONDS)).isNotNull();

        // Post-tick advance: a second hit on mcp-finance closes the loop so
        // fin_recurring.next_due stops being a stale snapshot (PR30).
        RecordedRequest advance = mcpFinance.takeRequest(2, TimeUnit.SECONDS);
        assertThat(advance).isNotNull();
        assertThat(advance.getMethod()).isEqualTo("POST");
        assertThat(advance.getPath()).isEqualTo("/internal/recurring/" + recurringId + "/advance");

        RecordedRequest llmReq = llmGateway.takeRequest(2, TimeUnit.SECONDS);
        assertThat(llmReq).isNotNull();
        String llmBody = llmReq.getBody().readUtf8();
        assertThat(llmBody).contains("Apartment rent");
        assertThat(llmBody).contains("-1600");
        assertThat(llmBody).contains("EUR");
        // The skill instructs the model to read `nextDue`; verify it's in the body.
        assertThat(llmBody).contains("nextDue");

        // Notifier fan-out happens to both household members.
        assertThat(profileService.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
        int notifies = 0;
        for (int i = 0; i < 2; i++) {
            if (notifierService.takeRequest(2, TimeUnit.SECONDS) != null) notifies++;
        }
        assertThat(notifies).isEqualTo(2);
    }

    @Test
    void recurringDue404FromMcpFinanceMarksPayloadAndSkipsNotifier() throws Exception {
        UUID recurringId = UUID.randomUUID();
        budgetStatusDispatcher.recurring404 = true;
        enqueueLlm("SKIP");

        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("recurringId", recurringId.toString());
        var wake = new AgentWakeRequest(
                UUID.randomUUID(), householdId, "finance", "recurring.due", payload);

        http.post().uri("/agents/finance/triggers/recurring.due")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(wake)
                .exchange()
                .expectStatus().isAccepted();

        assertThat(mcpFinance.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
        RecordedRequest llmReq = llmGateway.takeRequest(2, TimeUnit.SECONDS);
        assertThat(llmReq).isNotNull();
        assertThat(llmReq.getBody().readUtf8()).contains("no_active_recurring");
        assertThat(profileService.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
        assertThat(notifierService.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
        // No advance call either — the row is gone upstream, advancing would 404.
        assertThat(mcpFinance.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void transactionUncategorisedEnrichesPayloadFromMcpFinance() throws Exception {
        UUID transactionId = UUID.randomUUID();
        budgetStatusDispatcher.transaction = new FinTransactionDto(
                transactionId, householdId, UUID.randomUUID(), null, null,
                new BigDecimal("-4.50"), "EUR",
                Instant.parse("2026-05-01T08:30:00Z"),
                "Latte at central station", "telegram", null,
                Instant.parse("2026-05-01T08:31:00Z"));
        String suggestion = "Возможно категория «Кофе» — небольшая утренняя трата, похоже на латте.";
        enqueueLlm(suggestion);

        // Scheduler-driven payload: only transactionId. Enrichment must
        // pull amount / currency / note / source / ts from mcp-finance.
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("transactionId", transactionId.toString());
        var wake = new AgentWakeRequest(
                UUID.randomUUID(), householdId, "finance", "transaction.uncategorised", payload);

        http.post().uri("/agents/finance/triggers/transaction.uncategorised")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(wake)
                .exchange()
                .expectStatus().isAccepted();

        RecordedRequest txReq = mcpFinance.takeRequest(2, TimeUnit.SECONDS);
        assertThat(txReq).isNotNull();
        assertThat(txReq.getPath()).isEqualTo("/internal/transaction/" + transactionId);
        assertThat(memoryService.takeRequest(2, TimeUnit.SECONDS)).isNotNull();

        RecordedRequest llmReq = llmGateway.takeRequest(2, TimeUnit.SECONDS);
        assertThat(llmReq).isNotNull();
        String llmBody = llmReq.getBody().readUtf8();
        // Enriched payload reaches the LLM verbatim — amount/currency/note/source.
        assertThat(llmBody).contains("-4.5");
        assertThat(llmBody).contains("EUR");
        assertThat(llmBody).contains("Latte at central station");
        assertThat(llmBody).contains("telegram");

        // Suggestion fans out to both household members — for now the user reads
        // it and applies manually (auto-classification is a follow-up).
        assertThat(profileService.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
        int notifies = 0;
        for (int i = 0; i < 2; i++) {
            if (notifierService.takeRequest(2, TimeUnit.SECONDS) != null) notifies++;
        }
        assertThat(notifies).isEqualTo(2);
    }

    @Test
    void transactionUncategorised404FromMcpFinanceMarksPayloadAndSkipsNotifier() throws Exception {
        UUID transactionId = UUID.randomUUID();
        budgetStatusDispatcher.transaction404 = true;
        // The row was deleted / never landed — skill SKIPs cleanly.
        enqueueLlm("SKIP");

        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("transactionId", transactionId.toString());
        var wake = new AgentWakeRequest(
                UUID.randomUUID(), householdId, "finance", "transaction.uncategorised", payload);

        http.post().uri("/agents/finance/triggers/transaction.uncategorised")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(wake)
                .exchange()
                .expectStatus().isAccepted();

        assertThat(mcpFinance.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
        RecordedRequest llmReq = llmGateway.takeRequest(2, TimeUnit.SECONDS);
        assertThat(llmReq).isNotNull();
        assertThat(llmReq.getBody().readUtf8()).contains("no_active_transaction");
        assertThat(profileService.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
        assertThat(notifierService.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
    }

    private void enqueueLlm(String content) throws Exception {
        var fakeLlm = new LlmChatResponse(
                "mock-large", content, "stop", new LlmUsage(60, 30, 90));
        llmGateway.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(fakeLlm)));
    }

    /** Profile-service stub: any household returns Alice + Bob. */
    static final class ProfileDispatcher extends Dispatcher {
        @Override
        public MockResponse dispatch(RecordedRequest recordedRequest) {
            String path = recordedRequest.getPath() == null ? "" : recordedRequest.getPath();
            if (path.startsWith("/v1/users/by-household/")) {
                List<UserDto> users = List.of(
                        new UserDto(userAlice, householdId, "Alice", "ru-RU", 111L, "owner", Instant.now()),
                        new UserDto(userBob,   householdId, "Bob",   "ru-RU", 222L, "member", Instant.now()));
                String body;
                try {
                    body = new ObjectMapper().findAndRegisterModules().writeValueAsString(users);
                } catch (Exception e) {
                    return new MockResponse().setResponseCode(500);
                }
                return new MockResponse()
                        .setHeader("content-type", "application/json")
                        .setBody(body);
            }
            return new MockResponse().setResponseCode(404);
        }
    }

    /** Notifier stub: every POST /v1/notify succeeds with 202. */
    static final class NotifierDispatcher extends Dispatcher {
        @Override
        public MockResponse dispatch(RecordedRequest recordedRequest) {
            if ("/v1/notify".equals(recordedRequest.getPath())) {
                return new MockResponse().setResponseCode(202);
            }
            return new MockResponse().setResponseCode(404);
        }
    }

    /** Memory-service stub: returns pre-loaded recall hits or simulates a 5xx. */
    static final class MemoryDispatcher extends Dispatcher {
        private static final ObjectMapper M = new ObjectMapper().findAndRegisterModules();
        volatile List<RecallMemoryHit> recallHits = List.of();
        volatile boolean simulate5xx;

        void reset() {
            recallHits = List.of();
            simulate5xx = false;
        }

        @Override
        public MockResponse dispatch(RecordedRequest recordedRequest) {
            if (!"/v1/memories/recall".equals(recordedRequest.getPath())) {
                return new MockResponse().setResponseCode(404);
            }
            if (simulate5xx) {
                return new MockResponse().setResponseCode(500);
            }
            try {
                return new MockResponse()
                        .setHeader("content-type", "application/json")
                        .setBody(M.writeValueAsString(recallHits));
            } catch (Exception e) {
                return new MockResponse().setResponseCode(500);
            }
        }
    }

    /** mcp-finance stub: serves canned snapshots for both
     *  {@code /internal/budget-status} and {@code /internal/recurring/*}. Flags
     *  toggled per test cover 404 / 500 / per-endpoint matrix. */
    static final class BudgetStatusDispatcher extends Dispatcher {
        private static final ObjectMapper M = new ObjectMapper().findAndRegisterModules();
        volatile BudgetStatusResult snapshot;
        volatile boolean return404;
        volatile boolean simulate5xx;
        volatile FinRecurringDto recurring;
        volatile boolean recurring404;
        volatile FinTransactionDto transaction;
        volatile boolean transaction404;

        void reset() {
            snapshot = null;
            return404 = false;
            simulate5xx = false;
            recurring = null;
            recurring404 = false;
            transaction = null;
            transaction404 = false;
        }

        @Override
        public MockResponse dispatch(RecordedRequest recordedRequest) {
            String path = recordedRequest.getPath() == null ? "" : recordedRequest.getPath();
            if (path.startsWith("/internal/budget-status")) {
                if (simulate5xx) return new MockResponse().setResponseCode(500);
                if (return404 || snapshot == null) return new MockResponse().setResponseCode(404);
                try {
                    return new MockResponse()
                            .setHeader("content-type", "application/json")
                            .setBody(M.writeValueAsString(snapshot));
                } catch (Exception e) {
                    return new MockResponse().setResponseCode(500);
                }
            }
            if (path.startsWith("/internal/recurring/")) {
                if (recurring404 || recurring == null) return new MockResponse().setResponseCode(404);
                try {
                    return new MockResponse()
                            .setHeader("content-type", "application/json")
                            .setBody(M.writeValueAsString(recurring));
                } catch (Exception e) {
                    return new MockResponse().setResponseCode(500);
                }
            }
            if (path.startsWith("/internal/transaction/")) {
                if (transaction404 || transaction == null) return new MockResponse().setResponseCode(404);
                try {
                    return new MockResponse()
                            .setHeader("content-type", "application/json")
                            .setBody(M.writeValueAsString(transaction));
                } catch (Exception e) {
                    return new MockResponse().setResponseCode(500);
                }
            }
            return new MockResponse().setResponseCode(404);
        }
    }

    /** Notifier stub that 500s for one specific user-id, 202s for everyone else. */
    static final class SelectiveFailingNotifierDispatcher extends Dispatcher {
        private final UUID failFor;

        SelectiveFailingNotifierDispatcher(UUID failFor) {
            this.failFor = failFor;
        }

        @Override
        public MockResponse dispatch(RecordedRequest recordedRequest) {
            if (!"/v1/notify".equals(recordedRequest.getPath())) {
                return new MockResponse().setResponseCode(404);
            }
            String body = recordedRequest.getBody().readUtf8();
            if (body.contains(failFor.toString())) {
                return new MockResponse().setResponseCode(500);
            }
            return new MockResponse().setResponseCode(202);
        }
    }
}
