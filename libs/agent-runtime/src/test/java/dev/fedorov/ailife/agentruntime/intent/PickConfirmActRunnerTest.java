package dev.fedorov.ailife.agentruntime.intent;

import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.agent.ResumeRequest;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.llm.LlmClient;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Orchestration tests for {@link PickConfirmActRunner} (ADR-0004): a tiny in-memory {@link TargetedActionFlow}
 * over a {@code Widget} exercises every branch of the shared pick→confirm→act loop — blank / no-household /
 * empty-pool short-circuits (no LLM), no-match, ambiguous, the confirm gate (acts on nothing), the
 * {@link TargetedActionFlow#missing} re-ask, the {@code params} passthrough into the {@code pendingAction},
 * and the resume side (affirmative acts, decline leaves it, a broken id is graceful, an act failure
 * soft-fails). The five domain adapters keep their own behaviour tests; this proves the seam once.
 */
class PickConfirmActRunnerTest {

    private final LlmClient llm = mock(LlmClient.class);
    private final ObjectMapper json = new ObjectMapper();
    private final AgentManifest manifest = new AgentManifest(
            "widgets", "test", "0.0.1", 0,
            List.of(), List.of(),
            List.<Map<String, String>>of(), List.<Map<String, String>>of(),
            "You are the widgets agent.");
    private final Skill skill = new Skill("widget-delete", "Delete a widget the user names.",
            "0.1.0", "widgets", List.of(), List.of("en", "ru"), "Pick the widget to delete.");
    private final SkillRegistry skills = new SkillRegistry(List.of(skill));

    private final AtomicReference<UUID> acted = new AtomicReference<>();
    private final AtomicReference<JsonNode> actedParams = new AtomicReference<>();

    private record Widget(UUID id, String title) {
    }

    /** In-memory flow: candidates come from a settable list; a completeness gate + act failure are toggled. */
    private class WidgetFlow implements TargetedActionFlow<Widget>, CandidateView<Widget> {
        List<Widget> pool = List.of();
        boolean requireField = false;
        boolean actFails = false;
        ObjectNode asyncContext = null;

        @Override public String skillName() { return "widget-delete"; }
        @Override public String flow() { return "widget-delete-confirm"; }
        @Override public Nouns nouns() { return new Nouns("штуку", "штук", "штука"); }
        @Override public Mono<List<Widget>> candidates(NormalizedMessage msg) { return Mono.just(pool); }
        @Override public CandidateView<Widget> view() { return this; }

        @Override public Mono<ObjectNode> decorateAsync(NormalizedMessage msg) {
            return asyncContext == null ? Mono.empty() : Mono.just(asyncContext);
        }

        @Override public Optional<String> missing(Widget target, JsonNode pick) {
            return requireField && !pick.hasNonNull("when")
                    ? Optional.of("Когда?") : Optional.empty();
        }

        @Override public Mono<Void> act(UUID targetId, JsonNode params) {
            if (actFails) {
                return Mono.error(new IllegalStateException("gone"));
            }
            acted.set(targetId);
            actedParams.set(params);
            return Mono.empty();
        }

        @Override public UUID id(Widget w) { return w.id(); }
        @Override public String label(Widget w) { return "«" + w.title() + "»"; }
        @Override public void describe(ObjectNode node, Widget w) { node.put("title", w.title()); }
    }

    private final WidgetFlow flow = new WidgetFlow();
    private final PickConfirmActRunner<Widget> runner =
            new PickConfirmActRunner<>(llm, manifest, skills, json, flow);

    @Test
    void blankInputAsksWhich() {
        StepVerifier.create(runner.pick(message(UUID.randomUUID(), "  ")))
                .assertNext(r -> {
                    assertThat(r.text()).contains("Какую штуку удалить?");
                    assertThat(r.pendingAction()).isNull();
                })
                .verifyComplete();
        verify(llm, never()).chat(any());
    }

    @Test
    void nullHouseholdIsRefused() {
        StepVerifier.create(runner.pick(message(null, "удали штуку")))
                .assertNext(r -> assertThat(r.text()).contains("к какому хозяйству"))
                .verifyComplete();
        verify(llm, never()).chat(any());
    }

    @Test
    void emptyPoolShortCircuitsWithoutLlm() {
        flow.pool = List.of();
        StepVerifier.create(runner.pick(message(UUID.randomUUID(), "удали штуку")))
                .assertNext(r -> {
                    assertThat(r.text()).contains("Не нашёл штук");
                    assertThat(r.pendingAction()).isNull();
                })
                .verifyComplete();
        verify(llm, never()).chat(any());
    }

    @Test
    void noMatchAsks() {
        flow.pool = List.of(new Widget(UUID.randomUUID(), "красная"));
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(reply("{}")));
        StepVerifier.create(runner.pick(message(UUID.randomUUID(), "удали синюю")))
                .assertNext(r -> {
                    assertThat(r.text()).contains("Не нашёл такую штуку");
                    assertThat(r.pendingAction()).isNull();
                })
                .verifyComplete();
    }

    @Test
    void ambiguousIsClarified() {
        flow.pool = List.of(new Widget(UUID.randomUUID(), "красная"), new Widget(UUID.randomUUID(), "красноватая"));
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(reply("{\"ambiguous\":[1,2]}")));
        StepVerifier.create(runner.pick(message(UUID.randomUUID(), "удали красную")))
                .assertNext(r -> {
                    assertThat(r.text()).contains("несколько").contains("красная").contains("красноватая");
                    assertThat(r.pendingAction()).isNull();
                })
                .verifyComplete();
    }

    @Test
    void singleMatchConfirmsWithoutActing() {
        UUID id = UUID.randomUUID();
        flow.pool = List.of(new Widget(id, "красная"));
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(reply("{\"pick\":1}")));
        StepVerifier.create(runner.pick(message(UUID.randomUUID(), "удали красную")))
                .assertNext(r -> {
                    assertThat(r.text()).contains("Удалить штуку «красная»?");
                    assertThat(r.pendingAction()).isNotNull();
                    assertThat(r.pendingAction().path("flow").asString()).isEqualTo("widget-delete-confirm");
                    assertThat(r.pendingAction().path("targetId").asString()).isEqualTo(id.toString());
                    assertThat(r.pendingAction().path("label").asString()).isEqualTo("«красная»");
                })
                .verifyComplete();
        assertThat(acted.get()).isNull();   // confirm-before-act: nothing happened yet
    }

    @Test
    void missingFieldReAsksInsteadOfConfirming() {
        flow.pool = List.of(new Widget(UUID.randomUUID(), "красная"));
        flow.requireField = true;
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(reply("{\"pick\":1}")));
        StepVerifier.create(runner.pick(message(UUID.randomUUID(), "перенеси красную")))
                .assertNext(r -> {
                    assertThat(r.text()).isEqualTo("Когда?");
                    assertThat(r.pendingAction()).isNull();   // no lock — the flow re-asks
                })
                .verifyComplete();
    }

    @Test
    void extraLlmFieldsPassThroughIntoPendingActionAndAct() {
        UUID id = UUID.randomUUID();
        flow.pool = List.of(new Widget(id, "красная"));
        flow.requireField = true;
        when(llm.chat(any(LlmChatRequest.class)))
                .thenReturn(Mono.just(reply("{\"pick\":1,\"when\":\"2026-09-01T10:00:00Z\"}")));

        AtomicReference<JsonNode> pending = new AtomicReference<>();
        StepVerifier.create(runner.pick(message(UUID.randomUUID(), "перенеси красную на завтра")))
                .assertNext(r -> {
                    assertThat(r.pendingAction()).isNotNull();
                    assertThat(r.pendingAction().path("when").asString())
                            .isEqualTo("2026-09-01T10:00:00Z");
                    pending.set(r.pendingAction());
                })
                .verifyComplete();

        // resume threads params into act()
        StepVerifier.create(runner.resume(new ResumeRequest(message(UUID.randomUUID(), "да"), pending.get())))
                .assertNext(r -> assertThat(r.pendingAction()).isNull())
                .verifyComplete();
        assertThat(acted.get()).isEqualTo(id);
        assertThat(actedParams.get().path("when").asString()).isEqualTo("2026-09-01T10:00:00Z");
    }

    @Test
    void decorateAsyncMergesIntoLlmUserMessage() {
        UUID id = UUID.randomUUID();
        flow.pool = List.of(new Widget(id, "красная"));
        ObjectNode ctx = json.createObjectNode();
        ctx.putArray("categories").add("Еда").add("Такси");
        flow.asyncContext = ctx;

        org.mockito.ArgumentCaptor<LlmChatRequest> req =
                org.mockito.ArgumentCaptor.forClass(LlmChatRequest.class);
        when(llm.chat(req.capture())).thenReturn(Mono.just(reply("{\"pick\":1}")));

        StepVerifier.create(runner.pick(message(UUID.randomUUID(), "удали красную")))
                .assertNext(r -> assertThat(r.pendingAction()).isNotNull())
                .verifyComplete();

        // The async context is merged into the LLM user message alongside userText/candidates.
        String userMsg = req.getValue().messages().get(req.getValue().messages().size() - 1).content();
        assertThat(userMsg).contains("categories").contains("Еда").contains("Такси");
    }

    @Test
    void resumeAffirmativeActs() {
        UUID id = UUID.randomUUID();
        StepVerifier.create(runner.resume(new ResumeRequest(
                        message(UUID.randomUUID(), "да"), pending(id, "«красная»"))))
                .assertNext(r -> {
                    assertThat(r.text()).contains("Удалил штуку «красная».");
                    assertThat(r.pendingAction()).isNull();
                })
                .verifyComplete();
        assertThat(acted.get()).isEqualTo(id);
    }

    @Test
    void resumeDeclineLeavesIt() {
        StepVerifier.create(runner.resume(new ResumeRequest(
                        message(UUID.randomUUID(), "нет"), pending(UUID.randomUUID(), "«красная»"))))
                .assertNext(r -> assertThat(r.text()).contains("без изменений"))
                .verifyComplete();
        assertThat(acted.get()).isNull();
    }

    @Test
    void resumeBrokenIdIsGraceful() {
        ObjectNode broken = json.createObjectNode();
        broken.put("flow", "widget-delete-confirm");
        StepVerifier.create(runner.resume(new ResumeRequest(message(UUID.randomUUID(), "да"), broken)))
                .assertNext(r -> {
                    assertThat(r.text()).contains("Нечего удалять");
                    assertThat(r.pendingAction()).isNull();
                })
                .verifyComplete();
        assertThat(acted.get()).isNull();
    }

    @Test
    void resumeActFailureSoftFails() {
        flow.actFails = true;
        StepVerifier.create(runner.resume(new ResumeRequest(
                        message(UUID.randomUUID(), "да"), pending(UUID.randomUUID(), "«красная»"))))
                .assertNext(r -> {
                    assertThat(r.text()).contains("Не смог удалить").contains("уже удалена");
                    assertThat(r.pendingAction()).isNull();
                })
                .verifyComplete();
    }

    // ----- fixtures ---------------------------------------------------------------------------------

    private ObjectNode pending(UUID id, String label) {
        ObjectNode node = json.createObjectNode();
        node.put("flow", "widget-delete-confirm");
        node.put("targetId", id.toString());
        node.put("label", label);
        return node;
    }

    private static NormalizedMessage message(UUID household, String text) {
        return new NormalizedMessage(UUID.randomUUID(), household, MessageScope.PRIVATE,
                text, List.of(), "telegram", "1", Instant.now());
    }

    private static LlmChatResponse reply(String text) {
        return new LlmChatResponse("mock-large", text, "stop", new LlmUsage(10, 5, 15));
    }
}
