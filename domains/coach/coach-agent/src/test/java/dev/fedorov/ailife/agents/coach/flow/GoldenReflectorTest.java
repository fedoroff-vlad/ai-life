package dev.fedorov.ailife.agents.coach.flow;

import dev.fedorov.ailife.agentruntime.coordinate.Coordinator;
import dev.fedorov.ailife.agentruntime.http.MemoryClient;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.coach.http.CoachStoreClient;
import dev.fedorov.ailife.agents.coach.http.SubjectNotesClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.coach.AddCoachHypothesisInput;
import dev.fedorov.ailife.contracts.coach.AddCoachObservationInput;
import dev.fedorov.ailife.contracts.coach.CoachHypothesisDto;
import dev.fedorov.ailife.contracts.coach.CoachObservationDto;
import dev.fedorov.ailife.contracts.coach.CoachSessionDto;
import dev.fedorov.ailife.contracts.coach.StartCoachSessionInput;
import dev.fedorov.ailife.contracts.note.NoteDto;
import dev.fedorov.ailife.golden.GoldenLlm;
import dev.fedorov.ailife.golden.GoldenLlmTest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Golden test — the {@code reflect} <b>strict-JSON synthesis</b> against a <b>real model</b> (local
 * Ollama {@code qwen2.5:7b} via a running llm-gateway), asserting <b>structure, not text</b>: over a
 * strongly-patterned journal fixture the model must return an object the production {@link Reflector}
 * parses and persists — a {@code reflect} session plus at least one observation carrying a whitelisted
 * method tag. Wording is never asserted.
 *
 * <p>Opt-in / gated via {@link GoldenLlmTest} ({@code GOLDEN_LLM}); skipped in CI. Run:
 * {@code scripts/golden.sh -pl domains/coach/coach-agent -Dtest=GoldenReflectorTest}.
 */
@GoldenLlmTest
class GoldenReflectorTest {

    private static final Set<String> METHODS = Set.of("cbt", "act", "mi", "sfbt", "ifs");

    private final ObjectMapper json = new ObjectMapper();
    private final MemoryClient memory = mock(MemoryClient.class);
    private final SubjectNotesClient notes = mock(SubjectNotesClient.class);
    private final CoachStoreClient store = mock(CoachStoreClient.class);
    private final AgentManifest manifest = new AgentManifest(
            "coach", "coach agent", "0.1.0", 8122,
            List.of(), List.of(),
            List.<Map<String, String>>of(), List.<Map<String, String>>of(),
            GoldenLlm.agentBody(GoldenReflectorTest.class.getClassLoader()));
    private final SkillRegistry skills = new SkillRegistry(List.of(
            GoldenLlm.skill(GoldenReflectorTest.class.getClassLoader(), "skills/coach/reflect/SKILL.md")));
    private final Reflector reflector = new Reflector(
            new Coordinator(GoldenLlm.client(), json), memory, notes, store, skills, manifest, json);

    @Test
    void reflectProducesParseableSessionWithMethodTaggedObservations() {
        UUID household = UUID.randomUUID();
        UUID subject = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        when(notes.subjectNotes(household, subject)).thenReturn(Mono.just(List.of(
                journal(household, subject, "Опять новый проект",
                        "Забросил курс по ML на середине и начал писать своего телеграм-бота. "
                        + "Как обычно — когда на работе аврал и тревожно из-за денег."),
                journal(household, subject, "Неделя без прогресса",
                        "Бот заброшен, как и курс. Чувствую вину. Заметил: каждый раз, когда "
                        + "тревожно, хочется начать что-то новое, а не доделать старое."),
                journal(household, subject, "Про спокойные периоды",
                        "В отпуске спокойно доделал две старые задачи и получил удовольствие. "
                        + "Без тревоги — доделываю, с тревогой — начинаю новое."))));
        when(memory.recall(any(), any(), any(), any())).thenReturn(Mono.just(List.of()));
        when(store.profile(household, subject)).thenReturn(Mono.empty());
        when(store.recentSessions(any(), any(), anyInt())).thenReturn(Mono.just(List.of()));
        when(store.startSession(any(StartCoachSessionInput.class))).thenAnswer(inv -> {
            StartCoachSessionInput in = inv.getArgument(0);
            return Mono.just(new CoachSessionDto(sessionId, in.householdId(), in.subject(),
                    in.mode(), in.summary(), Instant.now()));
        });
        when(store.addObservation(any(AddCoachObservationInput.class))).thenAnswer(inv -> {
            AddCoachObservationInput in = inv.getArgument(0);
            return Mono.just(new CoachObservationDto(UUID.randomUUID(), in.householdId(), in.subject(),
                    in.sessionId(), in.text(), in.method(), in.evidenceRefs(), Instant.now()));
        });
        when(store.addHypothesis(any(AddCoachHypothesisInput.class))).thenAnswer(inv -> {
            AddCoachHypothesisInput in = inv.getArgument(0);
            return Mono.just(new CoachHypothesisDto(UUID.randomUUID(), in.householdId(), in.subject(),
                    in.text(), "open", in.confidence(), null, null, Instant.now(), Instant.now()));
        });

        IntentResponse resp = reflector.reflect(GoldenLlm.message(household, subject,
                        "Помоги разобраться, почему я опять всё бросил на полпути"))
                .block(Duration.ofSeconds(180));

        assertThat(resp).as("null result — is llm-gateway up at %s?", GoldenLlm.gatewayUrl()).isNotNull();
        assertThat(resp.text()).as("reply must reach the user (degraded: %s)", resp.text()).isNotBlank();

        // Reaching the session write means the model produced the parseable strict-JSON envelope.
        ArgumentCaptor<StartCoachSessionInput> session = ArgumentCaptor.forClass(StartCoachSessionInput.class);
        verify(store).startSession(session.capture());
        assertThat(session.getValue().mode()).isEqualTo("reflect");
        assertThat(session.getValue().subject()).isEqualTo(subject);

        // Structure, not text: at least one observation, every one tagged with a sanctioned method.
        ArgumentCaptor<AddCoachObservationInput> observations =
                ArgumentCaptor.forClass(AddCoachObservationInput.class);
        verify(store, atLeastOnce()).addObservation(observations.capture());
        for (AddCoachObservationInput o : observations.getAllValues()) {
            assertThat(o.text()).isNotBlank();
            assertThat(METHODS).contains(o.method());
            assertThat(o.sessionId()).isEqualTo(sessionId);
        }
    }

    private static NoteDto journal(UUID household, UUID owner, String title, String body) {
        return new NoteDto(UUID.randomUUID(), household, owner, title, "journal", List.of(), "user",
                null, body, null, Instant.now(), Instant.now());
    }
}
