package dev.fedorov.ailife.agentruntime.profile;

import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.llm.LlmClient;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Orchestration tests for {@link PersonalizationProfiler} (ADR-0005): a tiny in-memory {@link ProfileSpec}
 * exercises every branch of the shared extract→parse→scope→write skeleton — the self / household scope
 * split (the {@link dev.fedorov.ailife.profile.ProfileScope} owner resolution), the unparseable reply, and
 * the write soft-fail. The five domain adapters keep their own behaviour tests; this proves the seam once.
 */
class PersonalizationProfilerTest {

    private final LlmClient llm = mock(LlmClient.class);
    private final ObjectMapper json = new ObjectMapper();
    private final AgentManifest manifest = new AgentManifest(
            "briefing", "test", "0.0.1", 0,
            List.of(), List.of(),
            List.<Map<String, String>>of(), List.<Map<String, String>>of(),
            "You are the briefing agent.");
    private final Skill skill = new Skill("briefing-profiler", "Configure the morning briefing.",
            "0.1.0", "briefing", List.of(), List.of("en", "ru"), "Emit the preferences JSON.");
    private final SkillRegistry skills = new SkillRegistry(List.of(skill));
    private final PersonalizationProfiler profiler = new PersonalizationProfiler(llm, manifest, skills, json);

    private final UUID user = UUID.randomUUID();
    private final NormalizedMessage msg = new NormalizedMessage(
            user, UUID.randomUUID(), null, "каждое утро погода в Москве", List.of(), "tg", "1", null);

    private record Input(UUID ownerId) {
    }

    private record Dto(String label) {
    }

    /** Records what scope the template resolved, and replies with a marker per outcome. */
    private class Spec implements ProfileSpec<Input, Dto> {
        final AtomicReference<UUID> builtOwner = new AtomicReference<>();
        boolean writeFails = false;

        @Override public String skillName() { return "briefing-profiler"; }

        @Override public Mono<Input> build(JsonNode draft, UUID ownerId, NormalizedMessage m) {
            builtOwner.set(ownerId);
            return Mono.just(new Input(ownerId));
        }

        @Override public Mono<Dto> write(Input input) {
            return writeFails ? Mono.error(new IllegalStateException("mcp down")) : Mono.just(new Dto("Москва"));
        }

        @Override public String success(boolean household, Dto saved) {
            return (household ? "ok-household:" : "ok-self:") + saved.label();
        }

        @Override public String unparseable() { return "не понял"; }

        @Override public String failure() { return "не смог сохранить"; }
    }

    private void llmReturns(String content) {
        when(llm.chat(any())).thenReturn(Mono.just(new LlmChatResponse("qwen", content, "stop", null)));
    }

    @Test
    void selfScopeWritesTheSpeakerAndRepliesSelf() {
        llmReturns("{\"scope\":\"self\",\"location\":\"Москва\"}");
        Spec spec = new Spec();

        StepVerifier.create(profiler.setProfile(spec, msg))
                .assertNext(r -> assertThat(r.text()).isEqualTo("ok-self:Москва"))
                .verifyComplete();
        assertThat(spec.builtOwner.get()).isEqualTo(user);
    }

    @Test
    void householdScopeWritesTheDefaultAndRepliesHousehold() {
        llmReturns("Sure! {\"scope\":\"household\",\"location\":\"Москва\"} done");
        Spec spec = new Spec();

        StepVerifier.create(profiler.setProfile(spec, msg))
                .assertNext(r -> assertThat(r.text()).isEqualTo("ok-household:Москва"))
                .verifyComplete();
        assertThat(spec.builtOwner.get()).isNull();
    }

    @Test
    void unparseableLlmOutputRepliesUnparseable() {
        llmReturns("I could not understand that request.");

        StepVerifier.create(profiler.setProfile(new Spec(), msg))
                .assertNext(r -> assertThat(r.text()).isEqualTo("не понял"))
                .verifyComplete();
    }

    @Test
    void writeFailureSoftFailsToFailureReply() {
        llmReturns("{\"scope\":\"self\"}");
        Spec spec = new Spec();
        spec.writeFails = true;

        StepVerifier.create(profiler.setProfile(spec, msg))
                .assertNext(r -> assertThat(r.text()).isEqualTo("не смог сохранить"))
                .verifyComplete();
    }
}
