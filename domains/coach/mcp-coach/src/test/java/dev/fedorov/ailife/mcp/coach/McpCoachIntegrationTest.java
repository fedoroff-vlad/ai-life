package dev.fedorov.ailife.mcp.coach;

import dev.fedorov.ailife.contracts.coach.AddCoachActionInput;
import dev.fedorov.ailife.contracts.coach.AddCoachHypothesisInput;
import dev.fedorov.ailife.contracts.coach.AddCoachIntakeAnswerInput;
import dev.fedorov.ailife.contracts.coach.AddCoachObservationInput;
import dev.fedorov.ailife.contracts.coach.AddCoachValueInput;
import dev.fedorov.ailife.contracts.coach.CoachActionDto;
import dev.fedorov.ailife.contracts.coach.CoachHypothesisDto;
import dev.fedorov.ailife.contracts.coach.CoachObservationDto;
import dev.fedorov.ailife.contracts.coach.CoachProfileDto;
import dev.fedorov.ailife.contracts.coach.CoachSessionDto;
import dev.fedorov.ailife.contracts.coach.CoachValueDto;
import dev.fedorov.ailife.contracts.coach.StartCoachSessionInput;
import dev.fedorov.ailife.contracts.coach.UpdateCoachActionInput;
import dev.fedorov.ailife.contracts.coach.UpdateCoachHypothesisInput;
import dev.fedorov.ailife.contracts.coach.UpsertCoachProfileInput;
import dev.fedorov.ailife.mcp.coach.tools.CoachMcpTools;
import dev.fedorov.ailife.test.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * mcp-coach store IT. Tests share one SpringBootTest context + DB (not isolated across methods),
 * so each test scopes on its own household/subject to stay deterministic (mirrors mcp-tasks).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class McpCoachIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry registry) {
        registerDataSource(registry);
    }

    @Autowired CoachMcpTools tools;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;

    static UUID householdId;

    @BeforeAll
    static void seed(@Autowired JdbcTemplate jdbc) {
        applySchema("test-schema.sql");
        householdId = UUID.randomUUID();
        jdbc.update("INSERT INTO core.households (id, name) VALUES (?, ?)", householdId, "test household");
    }

    private UUID freshHousehold() {
        UUID h = UUID.randomUUID();
        jdbc.update("INSERT INTO core.households (id, name) VALUES (?, ?)", h, "h-" + h);
        return h;
    }

    // ---------- profile (the vector) ----------

    @Test
    void upsertProfileCreatesThenUpdatesInPlace() {
        UUID subject = UUID.randomUUID();
        CoachProfileDto created = tools.upsertCoachProfile(new UpsertCoachProfileInput(
                householdId, subject, MAPPER.readTree("{\"cbt\":0.6,\"act\":0.4}"), "warm",
                MAPPER.readTree("[\"flow states\"]"), null, null));
        assertThat(created.id()).isNotNull();
        assertThat(created.active()).isTrue(); // default
        assertThat(created.tone()).isEqualTo("warm");
        assertThat(created.methodWeights().get("cbt").asDouble()).isEqualTo(0.6);

        CoachProfileDto updated = tools.upsertCoachProfile(new UpsertCoachProfileInput(
                householdId, subject, null, "warm, direct", null, MAPPER.readTree("[\"no diagnosis\"]"), false));
        assertThat(updated.id()).isEqualTo(created.id()); // same row (unique per subject)
        assertThat(updated.tone()).isEqualTo("warm, direct");
        assertThat(updated.methodWeights().get("cbt").asDouble()).isEqualTo(0.6); // untouched
        assertThat(updated.boundaries().get(0).asText()).isEqualTo("no diagnosis");
        assertThat(updated.active()).isFalse();
    }

    @Test
    void getProfileReturnsNullWhenAbsent() {
        assertThat(tools.getCoachProfile(householdId, UUID.randomUUID())).isNull();
    }

    // ---------- values ----------

    @Test
    void addValueDefaultsSourceAndListFiltersActive() {
        UUID subject = UUID.randomUUID();
        CoachValueDto v = tools.addCoachValue(new AddCoachValueInput(
                householdId, subject, "time with family", null, null, 3));
        assertThat(v.source()).isEqualTo("stated"); // default
        assertThat(v.active()).isTrue();

        tools.addCoachValue(new AddCoachValueInput(householdId, subject, "deep work", null, "inferred", null));
        assertThat(tools.listCoachValues(householdId, subject, false)).hasSize(2);
        assertThat(tools.listCoachValues(householdId, subject, true)).hasSize(2);
    }

    @Test
    void addValueRejectsBadSource() {
        assertThatThrownBy(() -> tools.addCoachValue(new AddCoachValueInput(
                householdId, UUID.randomUUID(), "x", null, "guessed", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported source");
    }

    // ---------- session + observation (scope guard) ----------

    @Test
    void observationLinksToSessionAndRejectsForeignSession() {
        UUID subject = UUID.randomUUID();
        CoachSessionDto session = tools.startCoachSession(new StartCoachSessionInput(
                householdId, subject, "reflect", "first session"));
        assertThat(session.mode()).isEqualTo("reflect");

        CoachObservationDto obs = tools.addCoachObservation(new AddCoachObservationInput(
                householdId, subject, session.id(), "spins up rescue projects under anxiety", "cbt",
                MAPPER.readTree("[\"note-1\"]")));
        assertThat(obs.sessionId()).isEqualTo(session.id());
        assertThat(tools.listCoachObservations(householdId, subject, session.id(), null)).hasSize(1);

        // A session belonging to a different subject must be rejected.
        UUID otherSubject = UUID.randomUUID();
        CoachSessionDto foreign = tools.startCoachSession(new StartCoachSessionInput(
                householdId, otherSubject, "reflect", null));
        assertThatThrownBy(() -> tools.addCoachObservation(new AddCoachObservationInput(
                householdId, subject, foreign.id(), "x", "act", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong to this subject");
    }

    @Test
    void sessionRejectsBadModeAndObservationBadMethod() {
        assertThatThrownBy(() -> tools.startCoachSession(new StartCoachSessionInput(
                householdId, UUID.randomUUID(), "chat", null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unsupported mode");
        assertThatThrownBy(() -> tools.addCoachObservation(new AddCoachObservationInput(
                householdId, UUID.randomUUID(), null, "x", "freud", null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unsupported method");
    }

    // ---------- hypothesis ----------

    @Test
    void hypothesisLandsOpenThenRevised() {
        UUID subject = UUID.randomUUID();
        CoachHypothesisDto h = tools.addCoachHypothesis(new AddCoachHypothesisInput(
                householdId, subject, "rescue projects mask financial anxiety", 40, null, null));
        assertThat(h.status()).isEqualTo("open");
        assertThat(h.confidence()).isEqualTo(40);

        CoachHypothesisDto revised = tools.updateCoachHypothesis(new UpdateCoachHypothesisInput(
                h.id(), "supported", 70, MAPPER.readTree("[\"obs-1\",\"obs-2\"]"), null));
        assertThat(revised.status()).isEqualTo("supported");
        assertThat(revised.confidence()).isEqualTo(70);
        assertThat(revised.supportingObservationIds()).hasSize(2);

        assertThat(tools.listCoachHypotheses(householdId, subject, "supported", null)).hasSize(1);
        assertThat(tools.listCoachHypotheses(householdId, subject, "open", null)).isEmpty();
    }

    // ---------- action (links + guard) ----------

    @Test
    void actionLinksValueAndHypothesisThenAdvances() {
        UUID subject = UUID.randomUUID();
        CoachValueDto value = tools.addCoachValue(new AddCoachValueInput(
                householdId, subject, "calm family day", null, null, null));
        CoachHypothesisDto hyp = tools.addCoachHypothesis(new AddCoachHypothesisInput(
                householdId, subject, "overworks weekends", null, null, null));

        CoachActionDto action = tools.addCoachAction(new AddCoachActionInput(
                householdId, subject, "block Sunday morning for family", value.id(), hyp.id(), null));
        assertThat(action.status()).isEqualTo("proposed");
        assertThat(action.valueId()).isEqualTo(value.id());
        assertThat(action.hypothesisId()).isEqualTo(hyp.id());

        CoachActionDto active = tools.updateCoachAction(new UpdateCoachActionInput(
                action.id(), "active", null));
        assertThat(active.status()).isEqualTo("active");
        assertThat(tools.listCoachActions(householdId, subject, "active", null)).hasSize(1);
    }

    @Test
    void actionRejectsForeignValue() {
        UUID subject = UUID.randomUUID();
        UUID otherSubject = UUID.randomUUID();
        CoachValueDto foreignValue = tools.addCoachValue(new AddCoachValueInput(
                householdId, otherSubject, "someone else's value", null, null, null));
        assertThatThrownBy(() -> tools.addCoachAction(new AddCoachActionInput(
                householdId, subject, "step", foreignValue.id(), null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong to this subject");
    }

    // ---------- intake ----------

    @Test
    void intakeStoresAndDefaultsAskedBy() {
        UUID subject = UUID.randomUUID();
        tools.addCoachIntakeAnswer(new AddCoachIntakeAnswerInput(
                householdId, subject, "values", "what matters most to you?", "my kids", null));
        List<?> answers = tools.listCoachIntake(householdId, subject, null);
        assertThat(answers).hasSize(1);
    }

    // ---------- subject scoping (no cross-leak) ----------

    @Test
    void valuesScopedToSubjectNoCrossLeak() {
        UUID h = freshHousehold();
        UUID s1 = UUID.randomUUID();
        UUID s2 = UUID.randomUUID();
        tools.addCoachValue(new AddCoachValueInput(h, s1, "s1 value", null, null, null));
        tools.addCoachValue(new AddCoachValueInput(h, s2, "s2 value", null, null, null));
        assertThat(tools.listCoachValues(h, s1, false)).singleElement()
                .satisfies(v -> assertThat(v.label()).isEqualTo("s1 value"));
    }

    // ---------- internal REST passthrough ----------

    @Test
    void internalProfileEndpointRoundTripsAnd400OnBadInput() {
        UUID subject = UUID.randomUUID();
        WebTestClient client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port).build();

        CoachProfileDto saved = client.post().uri("/internal/coach/profile")
                .bodyValue(new UpsertCoachProfileInput(householdId, subject, null, "curious", null, null, null))
                .exchange()
                .expectStatus().isOk()
                .expectBody(CoachProfileDto.class)
                .returnResult().getResponseBody();
        assertThat(saved).isNotNull();
        assertThat(saved.tone()).isEqualTo("curious");

        CoachProfileDto fetched = client.get()
                .uri(b -> b.path("/internal/coach/profile")
                        .queryParam("householdId", householdId).queryParam("subject", subject).build())
                .exchange()
                .expectStatus().isOk()
                .expectBody(CoachProfileDto.class)
                .returnResult().getResponseBody();
        assertThat(fetched).isNotNull();
        assertThat(fetched.id()).isEqualTo(saved.id());

        // Missing required subject → the tool's validation surfaces as 400.
        client.post().uri("/internal/coach/values")
                .bodyValue(new AddCoachValueInput(householdId, null, "x", null, null, null))
                .exchange()
                .expectStatus().isBadRequest();
    }
}
