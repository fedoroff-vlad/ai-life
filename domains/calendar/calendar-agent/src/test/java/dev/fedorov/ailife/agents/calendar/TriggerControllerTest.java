package dev.fedorov.ailife.agents.calendar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.contracts.agent.AgentActionResult;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.contracts.memory.MemoryDto;
import dev.fedorov.ailife.contracts.memory.PersonRelationsResponse;
import dev.fedorov.ailife.contracts.memory.RecallMemoryHit;
import dev.fedorov.ailife.contracts.memory.RelationDto;
import dev.fedorov.ailife.contracts.profile.PersonDto;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Trigger flow: scheduler-shaped wake → SkillRegistry resolves the kind to a
 * skill → personId in payload is resolved against profile-service → LLM is
 * called with manifest+skill prompts and {payload, person} as user message →
 * household members are pulled from profile-service → each one receives a
 * notify POST with the generated text.
 *
 * <p>profile-service handles BOTH calls (person lookup + by-household) via a
 * Dispatcher; the test seeds the person + member list per case.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TriggerControllerTest {

    static MockWebServer llmGateway;
    static MockWebServer profileService;
    static MockWebServer notifier;
    static MockWebServer icsImport;
    static MockWebServer memoryService;
    static MockWebServer orchestrator;

    static ProfileDispatcher profileDispatcher;
    static MemoryDispatcher memoryDispatcher;
    static OrchestratorDispatcher orchestratorDispatcher;

    @BeforeAll
    static void start() throws Exception {
        llmGateway = new MockWebServer();
        llmGateway.start();
        profileService = new MockWebServer();
        profileDispatcher = new ProfileDispatcher();
        profileService.setDispatcher(profileDispatcher);
        profileService.start();
        notifier = new MockWebServer();
        notifier.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest req) {
                return new MockResponse().setResponseCode(202);
            }
        });
        notifier.start();
        icsImport = new MockWebServer();
        icsImport.start();
        memoryService = new MockWebServer();
        memoryDispatcher = new MemoryDispatcher();
        memoryService.setDispatcher(memoryDispatcher);
        memoryService.start();
        orchestrator = new MockWebServer();
        orchestratorDispatcher = new OrchestratorDispatcher();
        orchestrator.setDispatcher(orchestratorDispatcher);
        orchestrator.start();
    }

    @AfterAll
    static void stop() throws Exception {
        llmGateway.shutdown();
        profileService.shutdown();
        notifier.shutdown();
        icsImport.shutdown();
        memoryService.shutdown();
        orchestrator.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("ailife.llm-client.base-url",
                () -> "http://localhost:" + llmGateway.getPort());
        r.add("calendar-agent.profile-service-url",
                () -> "http://localhost:" + profileService.getPort());
        r.add("calendar-agent.notifier-url",
                () -> "http://localhost:" + notifier.getPort());
        r.add("calendar-agent.ics-import-url",
                () -> "http://localhost:" + icsImport.getPort());
        r.add("calendar-agent.memory-service-url",
                () -> "http://localhost:" + memoryService.getPort());
        r.add("calendar-agent.orchestrator-url",
                () -> "http://localhost:" + orchestrator.getPort());
    }

    @BeforeEach
    void resetDispatchers() throws InterruptedException {
        profileDispatcher.reset();
        memoryDispatcher.reset();
        orchestratorDispatcher.reset();
        // Drain any requests a prior test left unconsumed so per-test takeRequest assertions are clean
        // (the servers are static and shared across methods).
        drain(llmGateway);
        drain(profileService);
        drain(notifier);
        drain(icsImport);
        drain(memoryService);
        drain(orchestrator);
    }

    private static void drain(MockWebServer server) throws InterruptedException {
        while (server.takeRequest(10, TimeUnit.MILLISECONDS) != null) {
            // discard
        }
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;
    @Autowired SkillRegistry skills;

    @Test
    void bothSkillsRegisteredFromClasspath() {
        assertThat(skills.forTrigger("birthday.greet")).isPresent();
        assertThat(skills.forTrigger("birthday.greet").orElseThrow().name())
                .isEqualTo("birthday-greeter");
        assertThat(skills.forTrigger("gift.recommend")).isPresent();
        assertThat(skills.forTrigger("gift.recommend").orElseThrow().name())
                .isEqualTo("gift-recommender");
    }

    @Test
    void birthdayWakeAsksCreatorToDraftGreetingAndFansOut() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID personId = UUID.randomUUID();
        UUID vladId = UUID.randomUUID();
        UUID wifeId = UUID.randomUUID();

        profileDispatcher.person = new PersonDto(
                personId, householdId, "Maria", "sister", "ru-RU",
                json.createArrayNode().add("books"), "loves earl grey", null, Instant.now());
        profileDispatcher.householdMembers = List.of(
                new UserDto(vladId, householdId, "Vlad", "ru-RU", 1L, "admin", Instant.now()),
                new UserDto(wifeId, householdId, "Wife", "ru-RU", 2L, "admin", Instant.now()));

        // CR-g2: the creator agent drafts the greeting over the orchestrator hub.
        orchestratorDispatcher.greeting = json.createObjectNode()
                .put("greeting", "С днём рождения, Маша!")
                .put("model", "mock-large");

        var payload = json.createObjectNode().put("personId", personId.toString());
        var wake = new AgentWakeRequest(
                UUID.randomUUID(), householdId, "calendar", "birthday.greet", payload);

        http.post().uri("/agents/calendar/triggers/birthday.greet")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(wake)
                .exchange()
                .expectStatus().isAccepted();

        // The hub invoke was creator.draft_greeting with the person name + occasion.
        RecordedRequest invoke = orchestrator.takeRequest(2, TimeUnit.SECONDS);
        assertThat(invoke).isNotNull();
        assertThat(invoke.getPath()).isEqualTo("/v1/agents/invoke");
        assertThat(invoke.getBody().readUtf8())
                .contains("\"action\":\"draft_greeting\"")
                .contains("\"targetAgent\":\"creator\"")
                .contains(householdId.toString())
                .contains("Maria")        // person.displayName as args.person
                .contains("birthday");    // args.occasion

        // The drafted greeting fanned out to both members; no local LLM call (creator drafted it).
        RecordedRequest first = notifier.takeRequest(2, TimeUnit.SECONDS);
        RecordedRequest second = notifier.takeRequest(2, TimeUnit.SECONDS);
        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        String bodies = first.getBody().readUtf8() + second.getBody().readUtf8();
        assertThat(bodies)
                .contains(vladId.toString())
                .contains(wifeId.toString())
                .contains("С днём рождения");
        assertThat(llmGateway.takeRequest(200, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void birthdayWakeFallsBackToLocalSkillWhenCreatorUnavailable() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID personId = UUID.randomUUID();
        UUID vladId = UUID.randomUUID();

        profileDispatcher.person = new PersonDto(
                personId, householdId, "Maria", "sister", "ru-RU",
                json.createArrayNode().add("books"), "loves earl grey", null, Instant.now());
        profileDispatcher.householdMembers = List.of(
                new UserDto(vladId, householdId, "Vlad", "ru-RU", 1L, "admin", Instant.now()));

        // Creator can't help (structured ok=false) → fall back to the local birthday-greeter skill.
        orchestratorDispatcher.creatorDeclines = true;

        llmGateway.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(new LlmChatResponse(
                        "mock-large", "С днём рождения, Маша!", "stop", new LlmUsage(80, 8, 88)))));

        var payload = json.createObjectNode().put("personId", personId.toString());
        var wake = new AgentWakeRequest(
                UUID.randomUUID(), householdId, "calendar", "birthday.greet", payload);

        http.post().uri("/agents/calendar/triggers/birthday.greet")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(wake)
                .exchange()
                .expectStatus().isAccepted();

        // The creator was tried first…
        RecordedRequest invoke = orchestrator.takeRequest(2, TimeUnit.SECONDS);
        assertThat(invoke).isNotNull();
        assertThat(invoke.getBody().readUtf8()).contains("\"action\":\"draft_greeting\"");

        // …then the local skill ran (LLM with the resolved person) and the greeting still fanned out.
        RecordedRequest llmReq = llmGateway.takeRequest(2, TimeUnit.SECONDS);
        assertThat(llmReq).isNotNull();
        assertThat(llmReq.getBody().readUtf8()).contains("Maria").contains("sister");
        RecordedRequest notifyReq = notifier.takeRequest(2, TimeUnit.SECONDS);
        assertThat(notifyReq).isNotNull();
        assertThat(notifyReq.getBody().readUtf8())
                .contains(vladId.toString())
                .contains("С днём рождения");
    }

    @Test
    void giftRecommendWakeCoordinatesBudgetAndMemoryThenFansOut() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID personId = UUID.randomUUID();
        UUID vladId = UUID.randomUUID();

        profileDispatcher.person = new PersonDto(
                personId, householdId, "Maria", "sister", "ru-RU",
                json.createArrayNode().add("hiking").add("books"),
                "into trail running lately", null, Instant.now());
        profileDispatcher.householdMembers = List.of(
                new UserDto(vladId, householdId, "Vlad", "ru-RU", 1L, "admin", Instant.now()));

        // gift.recommend benefits the most from memory enrichment.
        memoryDispatcher.recallHits = List.of(new RecallMemoryHit(new MemoryDto(
                UUID.randomUUID(), householdId, null, personId, "chat",
                "Maria signed up for the Lake District ultra in October.",
                null, Instant.now()), 0.07));
        memoryDispatcher.relations = new PersonRelationsResponse(
                personId,
                List.of(new RelationDto(
                        UUID.randomUUID(), householdId, "person", personId, "owns",
                        "label", null, "Hoka Speedgoat 5", 1.0f, "chat", null, Instant.now())),
                List.of());

        // D2c: the coordinator invokes finance's get_gift_budget through the
        // orchestrator hub. Stub the envelope the invoke returns.
        orchestratorDispatcher.giftBudget = json.createObjectNode()
                .put("hasGiftBudget", true)
                .put("amount", 5000)
                .put("currency", "RUB")
                .put("remaining", 3000);

        llmGateway.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(new LlmChatResponse(
                        "mock-large",
                        "1. Trail running shoes from a local store...\n2. ...\n3. ...",
                        "stop", new LlmUsage(120, 60, 180)))));

        var payload = json.createObjectNode().put("personId", personId.toString());
        var wake = new AgentWakeRequest(
                UUID.randomUUID(), householdId, "calendar", "gift.recommend", payload);

        http.post().uri("/agents/calendar/triggers/gift.recommend")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(wake)
                .exchange()
                .expectStatus().isAccepted();

        // The coordinator asked finance for the gift budget through the hub.
        RecordedRequest invoke = orchestrator.takeRequest(2, TimeUnit.SECONDS);
        assertThat(invoke).isNotNull();
        assertThat(invoke.getPath()).isEqualTo("/v1/agents/invoke");
        String invokeBody = invoke.getBody().readUtf8();
        assertThat(invokeBody)
                .contains("\"action\":\"get_gift_budget\"")
                .contains("\"targetAgent\":\"finance\"")
                .contains(householdId.toString())
                // D3d: the person's relationship is forwarded so finance can
                // return the relationship-tiered budget rule.
                .contains("\"relationship\":\"sister\"");

        // The LLM saw the skill body, the person fields, the recall hit, the
        // relation AND the gathered budget envelope.
        RecordedRequest llmReq = llmGateway.takeRequest(2, TimeUnit.SECONDS);
        assertThat(llmReq).isNotNull();
        String body = llmReq.getBody().readUtf8();
        assertThat(body)
                .contains("suggest gift ideas")             // gift-recommender SKILL.md body
                .contains("hiking")                         // person.interests (payload.person)
                .contains("trail running")                  // person.notes
                .contains("Lake District ultra in October") // memory recall hit (context)
                .contains("Hoka Speedgoat 5")               // relation object_label (context)
                .contains("hasGiftBudget")                  // gathered budget (context.budget)
                .contains("RUB");

        // D3e: one gift.recommend wake fans out TWO messages per member — the
        // birthday reminder first, then the gift ideas.
        RecordedRequest reminder = notifier.takeRequest(2, TimeUnit.SECONDS);
        assertThat(reminder).isNotNull();
        String reminderBody = reminder.getBody().readUtf8();
        assertThat(reminderBody)
                .contains(vladId.toString())
                .contains("день рождения")   // deterministic reminder
                .contains("Maria");          // person.displayName

        RecordedRequest gifts = notifier.takeRequest(2, TimeUnit.SECONDS);
        assertThat(gifts).isNotNull();
        assertThat(gifts.getBody().readUtf8())
                .contains(vladId.toString())
                .contains("Trail running shoes"); // LLM-synthesized gift ideas
    }

    @Test
    void memoryServiceFailureDoesNotBlockSkill() throws Exception {
        // memory-service returns 500 on every endpoint → MemoryClient soft-fails to empty.
        memoryDispatcher.simulate5xx = true;

        UUID householdId = UUID.randomUUID();
        UUID personId = UUID.randomUUID();
        UUID vladId = UUID.randomUUID();

        profileDispatcher.person = new PersonDto(
                personId, householdId, "Maria", "sister", "ru-RU",
                json.createArrayNode().add("books"), "n/a", null, Instant.now());
        profileDispatcher.householdMembers = List.of(
                new UserDto(vladId, householdId, "Vlad", "ru-RU", 1L, "admin", Instant.now()));

        // Drive the local-skill fallback (CR-g2): the creator declines, so the local birthday-greeter
        // skill runs — that's the path memory enrichment (and its soft-fail) belongs to.
        orchestratorDispatcher.creatorDeclines = true;

        llmGateway.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(new LlmChatResponse(
                        "mock-large", "Happy birthday!", "stop", new LlmUsage(40, 4, 44)))));

        var payload = json.createObjectNode().put("personId", personId.toString());
        var wake = new AgentWakeRequest(
                UUID.randomUUID(), householdId, "calendar", "birthday.greet", payload);

        http.post().uri("/agents/calendar/triggers/birthday.greet")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(wake)
                .exchange()
                .expectStatus().isAccepted();

        // LLM was still called (skill still ran) and no memories/relations leaked into the body.
        RecordedRequest llmReq = llmGateway.takeRequest(2, TimeUnit.SECONDS);
        assertThat(llmReq).isNotNull();
        String body = llmReq.getBody().readUtf8();
        assertThat(body)
                .contains("Maria")
                .doesNotContain("\"memories\"")
                .doesNotContain("\"relations\"");

        // Notify still happened.
        assertThat(notifier.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
    }

    @Test
    void unknownTriggerKindReturns404() {
        var wake = new AgentWakeRequest(
                UUID.randomUUID(), UUID.randomUUID(),
                "calendar", "no.such.kind", null);
        http.post().uri("/agents/calendar/triggers/no.such.kind")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(wake)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void icsPullTriggerForwardsToMcpIcsImportWithoutLlmOrNotifier() throws Exception {
        UUID subscriptionId = UUID.randomUUID();
        UUID householdId = UUID.randomUUID();

        icsImport.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody("{\"subscriptionId\":\"" + subscriptionId
                        + "\",\"eventsUpserted\":3,\"eventsRemoved\":0}"));

        var payload = json.createObjectNode()
                .put("subscriptionId", subscriptionId.toString());
        var wake = new AgentWakeRequest(
                UUID.randomUUID(), householdId, "calendar", "ics.pull", payload);

        http.post().uri("/agents/calendar/triggers/ics.pull")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(wake)
                .exchange()
                .expectStatus().isAccepted();

        RecordedRequest forwarded = icsImport.takeRequest(2, TimeUnit.SECONDS);
        assertThat(forwarded).isNotNull();
        assertThat(forwarded.getPath()).isEqualTo("/internal/pull/" + subscriptionId);
        assertThat(forwarded.getMethod()).isEqualTo("POST");

        // No LLM call (no skill was invoked) and no notifier fan-out (system trigger).
        assertThat(llmGateway.takeRequest(200, TimeUnit.MILLISECONDS)).isNull();
        assertThat(notifier.takeRequest(200, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void icsPullSwallowsDownstreamErrorAndStillReturns202() throws Exception {
        UUID subscriptionId = UUID.randomUUID();

        icsImport.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));

        var payload = json.createObjectNode()
                .put("subscriptionId", subscriptionId.toString());
        var wake = new AgentWakeRequest(
                UUID.randomUUID(), UUID.randomUUID(), "calendar", "ics.pull", payload);

        http.post().uri("/agents/calendar/triggers/ics.pull")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(wake)
                .exchange()
                .expectStatus().isAccepted();

        // Forward attempt happened.
        assertThat(icsImport.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse().setHeader("content-type", "application/json").setBody(body);
    }

    /**
     * Routes the orchestrator hub ({@code POST /v1/agents/invoke}) by the action in the body, so the
     * gift ({@code get_gift_budget}) and birthday ({@code draft_greeting}) paths don't depend on
     * enqueue order across methods. {@code creatorDeclines} returns {@code ok=false} for
     * {@code draft_greeting} so the caller falls back to the local skill. Reads the body via a buffer
     * clone so the test's own {@code takeRequest} can still inspect it.
     */
    private static class OrchestratorDispatcher extends Dispatcher {
        private static final ObjectMapper M = new ObjectMapper().findAndRegisterModules();
        volatile JsonNode giftBudget;       // get_gift_budget ok payload (null → error)
        volatile JsonNode greeting;         // draft_greeting ok payload (null → error)
        volatile boolean creatorDeclines;   // force draft_greeting to ok=false

        void reset() {
            giftBudget = null;
            greeting = null;
            creatorDeclines = false;
        }

        @Override
        public MockResponse dispatch(RecordedRequest req) {
            try {
                JsonNode root = M.readTree(req.getBody().clone().readUtf8());
                String action = root.path("action").asText("");
                AgentActionResult result;
                if ("draft_greeting".equals(action)) {
                    result = (creatorDeclines || greeting == null)
                            ? AgentActionResult.error("creator unavailable")
                            : AgentActionResult.ok(greeting);
                } else if ("get_gift_budget".equals(action)) {
                    result = giftBudget == null
                            ? AgentActionResult.error("no budget")
                            : AgentActionResult.ok(giftBudget);
                } else {
                    return new MockResponse().setResponseCode(404);
                }
                return new MockResponse()
                        .setHeader("content-type", "application/json")
                        .setBody(M.writeValueAsString(result));
            } catch (Exception e) {
                return new MockResponse().setResponseCode(500).setBody(e.toString());
            }
        }
    }

    /**
     * Routes {@code /v1/people/{id}} and {@code /v1/users/by-household/{id}}.
     * Test seeds the row(s) per case; misses respond 404 / empty list.
     */
    private static class ProfileDispatcher extends Dispatcher {
        private static final ObjectMapper M = new ObjectMapper().findAndRegisterModules();
        volatile PersonDto person;
        volatile List<UserDto> householdMembers = List.of();

        void reset() {
            person = null;
            householdMembers = List.of();
        }

        @Override
        public MockResponse dispatch(RecordedRequest req) {
            String path = req.getPath() == null ? "" : req.getPath();
            try {
                if (path.startsWith("/v1/people/")) {
                    if (person == null) {
                        return new MockResponse().setResponseCode(404);
                    }
                    return new MockResponse()
                            .setHeader("content-type", "application/json")
                            .setBody(M.writeValueAsString(person));
                }
                if (path.startsWith("/v1/users/by-household/")) {
                    return new MockResponse()
                            .setHeader("content-type", "application/json")
                            .setBody(M.writeValueAsString(householdMembers));
                }
            } catch (Exception e) {
                return new MockResponse().setResponseCode(500).setBody(e.toString());
            }
            return new MockResponse().setResponseCode(404);
        }
    }

    /**
     * Routes {@code POST /v1/memories/recall} and
     * {@code GET /v1/graph/person/{id}/relations}. Seeded per test; defaults to empty
     * lists. {@code simulate5xx} flips both endpoints to 500 to test soft-fail.
     */
    private static class MemoryDispatcher extends Dispatcher {
        private static final ObjectMapper M = new ObjectMapper().findAndRegisterModules();
        volatile List<RecallMemoryHit> recallHits = List.of();
        volatile PersonRelationsResponse relations;
        volatile boolean simulate5xx;

        void reset() {
            recallHits = List.of();
            relations = null;
            simulate5xx = false;
        }

        @Override
        public MockResponse dispatch(RecordedRequest req) {
            if (simulate5xx) {
                return new MockResponse().setResponseCode(500).setBody("boom");
            }
            String path = req.getPath() == null ? "" : req.getPath();
            try {
                if (path.startsWith("/v1/memories/recall")) {
                    return new MockResponse()
                            .setHeader("content-type", "application/json")
                            .setBody(M.writeValueAsString(recallHits));
                }
                if (path.startsWith("/v1/graph/person/") && path.contains("/relations")) {
                    PersonRelationsResponse body = relations == null
                            ? new PersonRelationsResponse(null, List.of(), List.of())
                            : relations;
                    return new MockResponse()
                            .setHeader("content-type", "application/json")
                            .setBody(M.writeValueAsString(body));
                }
            } catch (Exception e) {
                return new MockResponse().setResponseCode(500).setBody(e.toString());
            }
            return new MockResponse().setResponseCode(404);
        }
    }
}
