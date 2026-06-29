package dev.fedorov.ailife.agents.tasks.intent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.tasks.http.ClarifyClient;
import dev.fedorov.ailife.agents.tasks.http.TaskReviewClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.tasks.TaskItemDto;
import dev.fedorov.ailife.contracts.tasks.WeeklyReviewResult;
import dev.fedorov.ailife.golden.GoldenLlm;
import dev.fedorov.ailife.golden.GoldenLlmTest;
import dev.fedorov.ailife.llm.LlmClient;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Stage 5 <b>golden test</b> (#199) — exercises the {@code inbox-clarify} <b>skill</b> against a
 * <b>real model</b> (local Ollama {@code qwen2.5:7b} via a running llm-gateway), asserting <b>structure,
 * not text</b> (roadmap §Risks). Where the finance / orchestrator golden tests cover routing, this
 * covers the next layer the issue names — a skill must <b>return parseable output</b>: given the real
 * AGENT.md + the real {@code inbox-clarify} SKILL.md + a concrete inbox, the LLM must emit strict
 * {@code {"proposals":[…]}} JSON whose every entry has a known {@code status} and a {@code taskId}
 * copied <em>verbatim</em> from the inbox (the contract's hardest rule for a 7B — never invent an id).
 *
 * <p><b>Opt-in / gated.</b> Skipped unless {@code GOLDEN_LLM} is set (CI default = unset, so the suite
 * stays green on the mock provider without a model). To run it:
 * <pre>
 *   # 1. a real model — local Ollama with qwen2.5:7b pulled (see project memory / llm-gateway README)
 *   # 2. a llm-gateway pointed at it. A 7B on CPU generating multi-item JSON can exceed the default
 *   #    60 s upstream timeout — bump it for a slow local model (no effect on a GPU/cloud box):
 *   LLM_PROVIDER=openai-compatible LLM_BASE_URL=http://localhost:11434/v1 \
 *   LLM_DEFAULT_MODEL=qwen2.5:7b LLM_REQUEST_TIMEOUT_SECONDS=180 LLM_GATEWAY_PORT=8081 \
 *     mvn -q -pl platform/llm-gateway spring-boot:run
 *   # 3. the test, pointed at the gateway:
 *   GOLDEN_LLM=true GOLDEN_LLM_GATEWAY_URL=http://localhost:8081 \
 *     mvn -q -pl domains/tasks/tasks-agent -Dtest=GoldenInboxClarifyTest test
 * </pre>
 *
 * <p>{@link TaskReviewClient} (inbox source) and {@link ClarifyClient} (apply) are mocked — we drive a
 * fixed inbox in and assert the structure of the real model's proposals as parsed by the production
 * {@link InboxClarifier}, never the wording.
 */
@GoldenLlmTest
class GoldenInboxClarifyTest {

    /** The GTD statuses the skill contract allows. */
    private static final Set<String> STATUSES = Set.of("next", "waiting", "scheduled", "dropped");

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final LlmClient llm = GoldenLlm.client();
    private final TaskReviewClient review = mock(TaskReviewClient.class);
    private final ClarifyClient clarify = mock(ClarifyClient.class);
    private final AgentManifest manifest = new AgentManifest(
            "tasks", "tasks agent", "0.1.0", 8096,
            List.of(), List.of(),
            List.<Map<String, String>>of(), List.<Map<String, String>>of(),
            GoldenLlm.agentBody(GoldenInboxClarifyTest.class.getClassLoader()));
    private final SkillRegistry skills = new SkillRegistry(List.of(
            GoldenLlm.skill(GoldenInboxClarifyTest.class.getClassLoader(), "skills/tasks/inbox-clarify/SKILL.md")));
    private final InboxClarifier clarifier =
            new InboxClarifier(llm, manifest, skills, review, clarify, json);

    /**
     * STRUCTURE — the real model, given the real skill prompt and a concrete inbox, must return strict
     * {@code {"proposals":[…]}} JSON that the production parser turns into a non-empty pendingAction;
     * every proposal must carry a known status and a taskId that is one of the real inbox ids (verbatim,
     * never hallucinated). This is the "parseable output" assertion — it never checks wording.
     */
    @Test
    void skillEmitsWellFormedClarificationProposals() {
        UUID household = UUID.randomUUID();
        UUID milk = UUID.randomUUID();
        UUID dentist = UUID.randomUUID();
        UUID flights = UUID.randomUUID();
        Set<String> inboxIds = Set.of(milk.toString(), dentist.toString(), flights.toString());

        when(review.fetch(household)).thenReturn(Mono.just(new WeeklyReviewResult(
                household, 3, 0, 0,
                List.of(
                        inboxItem(milk, "купить молоко"),
                        inboxItem(dentist, "позвонить стоматологу и записаться"),
                        inboxItem(flights, "забронировать билеты на самолёт в июле")),
                List.of(), List.of())));

        IntentResponse r = clarifier.clarify(GoldenLlm.message(household, "разбери инбокс"))
                .block(Duration.ofSeconds(120));
        assertThat(r).as("null result — is llm-gateway up at %s?", GoldenLlm.gatewayUrl()).isNotNull();

        JsonNode pending = r.pendingAction();
        if (pending == null || pending.path("proposals").isMissingNode()) {
            fail("model did not produce parseable proposals — agent reply was:\n%s".formatted(r.text()));
        }
        assertThat(pending.path("flow").asText()).isEqualTo(InboxClarifier.FLOW);
        JsonNode proposals = pending.path("proposals");
        assertThat(proposals.isArray()).as("proposals is not an array: %s", pending).isTrue();
        assertThat(proposals).as("model proposed nothing for a clearly-actionable inbox").isNotEmpty();

        for (JsonNode p : proposals) {
            String taskId = p.path("taskId").asText("");
            String status = p.path("status").asText("");
            assertThat(inboxIds)
                    .as("hallucinated taskId '%s' (not in the inbox): %s", taskId, p)
                    .contains(taskId);
            assertThat(STATUSES)
                    .as("unknown status '%s': %s", status, p)
                    .contains(status);
            assertThat(p.path("title").asText(""))
                    .as("empty title: %s", p).isNotBlank();
            // Contract: context belongs only to 'next' actions, and is an @-tag when present.
            String context = p.path("context").asText("");
            if (!context.isBlank()) {
                assertThat(context).as("context is not an @-tag: %s", p).startsWith("@");
            }
        }
    }

    private static TaskItemDto inboxItem(UUID id, String title) {
        return new TaskItemDto(id, null, null, null, title, "inbox",
                null, null, null, null, null, "manual", null, null, null, Instant.now(), null);
    }
}
