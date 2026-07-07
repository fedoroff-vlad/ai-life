package dev.fedorov.ailife.mcp.coach.tools;

import dev.fedorov.ailife.contracts.coach.AddCoachActionInput;
import dev.fedorov.ailife.contracts.coach.AddCoachHypothesisInput;
import dev.fedorov.ailife.contracts.coach.AddCoachIntakeAnswerInput;
import dev.fedorov.ailife.contracts.coach.AddCoachObservationInput;
import dev.fedorov.ailife.contracts.coach.AddCoachValueInput;
import dev.fedorov.ailife.contracts.coach.CoachActionDto;
import dev.fedorov.ailife.contracts.coach.CoachHypothesisDto;
import dev.fedorov.ailife.contracts.coach.CoachIntakeDto;
import dev.fedorov.ailife.contracts.coach.CoachObservationDto;
import dev.fedorov.ailife.contracts.coach.CoachProfileDto;
import dev.fedorov.ailife.contracts.coach.CoachSessionDto;
import dev.fedorov.ailife.contracts.coach.CoachValueDto;
import dev.fedorov.ailife.contracts.coach.StartCoachSessionInput;
import dev.fedorov.ailife.contracts.coach.UpdateCoachActionInput;
import dev.fedorov.ailife.contracts.coach.UpdateCoachHypothesisInput;
import dev.fedorov.ailife.contracts.coach.UpsertCoachProfileInput;
import dev.fedorov.ailife.mcp.coach.domain.CoachAction;
import dev.fedorov.ailife.mcp.coach.domain.CoachActionRepository;
import dev.fedorov.ailife.mcp.coach.domain.CoachHypothesis;
import dev.fedorov.ailife.mcp.coach.domain.CoachHypothesisRepository;
import dev.fedorov.ailife.mcp.coach.domain.CoachIntake;
import dev.fedorov.ailife.mcp.coach.domain.CoachIntakeRepository;
import dev.fedorov.ailife.mcp.coach.domain.CoachObservation;
import dev.fedorov.ailife.mcp.coach.domain.CoachObservationRepository;
import dev.fedorov.ailife.mcp.coach.domain.CoachProfile;
import dev.fedorov.ailife.mcp.coach.domain.CoachProfileRepository;
import dev.fedorov.ailife.mcp.coach.domain.CoachSession;
import dev.fedorov.ailife.mcp.coach.domain.CoachSessionRepository;
import dev.fedorov.ailife.mcp.coach.domain.CoachValue;
import dev.fedorov.ailife.mcp.coach.domain.CoachValueRepository;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * CO-1: subject-scoped CRUD over the coach.* coaching record. Every record is scoped by
 * (householdId, subject) — the per-person "vector": records never cross subjects. Reads/lists
 * filter on both; id-based mutations (update_*) trust the caller, since the id was obtained from
 * a subject-scoped read (mirrors mcp-tasks' id-based transitions — this MCP is intentionally
 * low-level). The agent layer (CO-2) resolves subject = the authenticated sender and never reads
 * another member's record. Statuses/methods/modes are validated here; the rest relies on DB
 * constraints. No agent, no reasoning — this is the store.
 */
@Component
public class CoachMcpTools {

    private static final int LIST_HARD_CAP = 200;
    private static final int DEFAULT_LIMIT = 50;
    private static final Set<String> VALID_SOURCES = Set.of("stated", "inferred");
    private static final Set<String> VALID_MODES = Set.of("reflect", "develop", "intake");
    private static final Set<String> VALID_METHODS = Set.of("cbt", "act", "mi", "sfbt", "ifs");
    private static final Set<String> VALID_HYPOTHESIS_STATUSES =
            Set.of("open", "supported", "revised", "dropped");
    private static final Set<String> VALID_ACTION_STATUSES =
            Set.of("proposed", "active", "done", "dropped");
    private static final Set<String> VALID_ASKED_BY = Set.of("onboarding", "session");

    private final CoachProfileRepository profiles;
    private final CoachValueRepository values;
    private final CoachSessionRepository sessions;
    private final CoachObservationRepository observations;
    private final CoachHypothesisRepository hypotheses;
    private final CoachActionRepository actions;
    private final CoachIntakeRepository intake;

    public CoachMcpTools(CoachProfileRepository profiles, CoachValueRepository values,
                         CoachSessionRepository sessions, CoachObservationRepository observations,
                         CoachHypothesisRepository hypotheses, CoachActionRepository actions,
                         CoachIntakeRepository intake) {
        this.profiles = profiles;
        this.values = values;
        this.sessions = sessions;
        this.observations = observations;
        this.hypotheses = hypotheses;
        this.actions = actions;
        this.intake = intake;
    }

    // ---------- coach_profile (the vector) ----------

    @Tool(description = """
            Create or update the coaching "vector" for a subject — at most one per
            (householdId, subject). methodWeights (a JSON object weighting cbt/act/mi/sfbt/ifs),
            tone, focusAreas (JSON array) and boundaries (JSON array of off-limits topics) are
            applied when non-null; `active` defaults to true. Shapes that person's session prompt.
            """)
    @Transactional
    public CoachProfileDto upsertCoachProfile(UpsertCoachProfileInput input) {
        requireField(input.householdId(), "householdId");
        requireField(input.subject(), "subject");
        CoachProfile entity = profiles
                .findByHouseholdIdAndSubject(input.householdId(), input.subject())
                .orElseGet(() -> new CoachProfile(UUID.randomUUID(), input.householdId(), input.subject()));
        if (input.methodWeights() != null) entity.setMethodWeights(input.methodWeights());
        if (input.tone() != null) entity.setTone(input.tone());
        if (input.focusAreas() != null) entity.setFocusAreas(input.focusAreas());
        if (input.boundaries() != null) entity.setBoundaries(input.boundaries());
        if (input.active() != null) entity.setActive(input.active());
        return profiles.save(entity).toDto();
    }

    @Tool(description = """
            Get the coaching vector for a subject, or null if none exists yet (the agent then
            falls back to defaults). Scoped to (householdId, subject).
            """)
    @Transactional(readOnly = true)
    public CoachProfileDto getCoachProfile(UUID householdId, UUID subject) {
        requireField(householdId, "householdId");
        requireField(subject, "subject");
        return profiles.findByHouseholdIdAndSubject(householdId, subject)
                .map(CoachProfile::toDto).orElse(null);
    }

    // ---------- coach_value ----------

    @Tool(description = """
            Record a value the subject holds (label + optional note). `source` ∈ stated|inferred
            (defaults to "stated"); `weight` is an optional relative importance. Subject-owned.
            """)
    @Transactional
    public CoachValueDto addCoachValue(AddCoachValueInput input) {
        requireField(input.householdId(), "householdId");
        requireField(input.subject(), "subject");
        requireField(input.label(), "label");
        String source = (input.source() == null || input.source().isBlank()) ? "stated" : input.source();
        requireOneOf(source, VALID_SOURCES, "source");
        CoachValue entity = new CoachValue(UUID.randomUUID(), input.householdId(), input.subject(),
                input.label(), input.note(), source, input.weight());
        return values.save(entity).toDto();
    }

    @Tool(description = """
            List a subject's values, newest first. Set `activeOnly` to true to exclude
            deactivated values. Scoped to (householdId, subject).
            """)
    @Transactional(readOnly = true)
    public List<CoachValueDto> listCoachValues(UUID householdId, UUID subject, Boolean activeOnly) {
        requireField(householdId, "householdId");
        requireField(subject, "subject");
        List<CoachValue> rows = Boolean.TRUE.equals(activeOnly)
                ? values.findByHouseholdIdAndSubjectAndActiveTrueOrderByCreatedAtDesc(householdId, subject)
                : values.findByHouseholdIdAndSubjectOrderByCreatedAtDesc(householdId, subject);
        return rows.stream().map(CoachValue::toDto).toList();
    }

    // ---------- coach_session ----------

    @Tool(description = """
            Open a coaching session envelope for a subject. `mode` ∈ reflect|develop|intake.
            Observations and actions reference the returned session id for continuity.
            """)
    @Transactional
    public CoachSessionDto startCoachSession(StartCoachSessionInput input) {
        requireField(input.householdId(), "householdId");
        requireField(input.subject(), "subject");
        requireField(input.mode(), "mode");
        requireOneOf(input.mode(), VALID_MODES, "mode");
        CoachSession entity = new CoachSession(UUID.randomUUID(), input.householdId(),
                input.subject(), input.mode(), input.summary());
        return sessions.save(entity).toDto();
    }

    @Tool(description = """
            List a subject's recent coaching sessions, newest first (for continuity — "last time
            you said…"). `limit` capped at 200, default 50. Scoped to (householdId, subject).
            """)
    @Transactional(readOnly = true)
    public List<CoachSessionDto> listRecentCoachSessions(UUID householdId, UUID subject, Integer limit) {
        requireField(householdId, "householdId");
        requireField(subject, "subject");
        return sessions.findByHouseholdIdAndSubjectOrderByCreatedAtDesc(
                        householdId, subject, PageRequest.of(0, cappedLimit(limit)))
                .stream().map(CoachSession::toDto).toList();
    }

    // ---------- coach_observation ----------

    @Tool(description = """
            Persist a grounded observation from a session. `method` ∈ cbt|act|mi|sfbt|ifs (the move
            that produced it); `sessionId` links it to a session that must belong to the same
            subject; `evidenceRefs` is an optional JSON array of note/brief ids it rests on.
            """)
    @Transactional
    public CoachObservationDto addCoachObservation(AddCoachObservationInput input) {
        requireField(input.householdId(), "householdId");
        requireField(input.subject(), "subject");
        requireField(input.text(), "text");
        requireField(input.method(), "method");
        requireOneOf(input.method(), VALID_METHODS, "method");
        if (input.sessionId() != null) {
            requireSessionInScope(input.sessionId(), input.householdId(), input.subject());
        }
        CoachObservation entity = new CoachObservation(UUID.randomUUID(), input.householdId(),
                input.subject(), input.sessionId(), input.text(), input.method(), input.evidenceRefs());
        return observations.save(entity).toDto();
    }

    @Tool(description = """
            List a subject's observations, newest first. Pass `sessionId` to scope to one session;
            omit it for all. `limit` capped at 200, default 50. Scoped to (householdId, subject).
            """)
    @Transactional(readOnly = true)
    public List<CoachObservationDto> listCoachObservations(UUID householdId, UUID subject,
                                                           UUID sessionId, Integer limit) {
        requireField(householdId, "householdId");
        requireField(subject, "subject");
        List<CoachObservation> rows = sessionId != null
                ? observations.findByHouseholdIdAndSubjectAndSessionIdOrderByCreatedAtDesc(
                        householdId, subject, sessionId)
                : observations.findByHouseholdIdAndSubjectOrderByCreatedAtDesc(
                        householdId, subject, PageRequest.of(0, cappedLimit(limit)));
        return rows.stream().map(CoachObservation::toDto).toList();
    }

    // ---------- coach_hypothesis ----------

    @Tool(description = """
            Propose a candidate recurring pattern — lands as status=open (explicitly a hypothesis,
            revised later). `confidence` (0–100) and supporting/contradicting observation-id JSON
            arrays are optional.
            """)
    @Transactional
    public CoachHypothesisDto addCoachHypothesis(AddCoachHypothesisInput input) {
        requireField(input.householdId(), "householdId");
        requireField(input.subject(), "subject");
        requireField(input.text(), "text");
        CoachHypothesis entity = new CoachHypothesis(UUID.randomUUID(), input.householdId(),
                input.subject(), input.text(), "open", input.confidence(),
                input.supportingObservationIds(), input.contradictingObservationIds());
        return hypotheses.save(entity).toDto();
    }

    @Tool(description = """
            Revise a hypothesis as the record sharpens. `id` required; `status`
            (open|supported|revised|dropped), `confidence`, and supporting/contradicting
            observation-id arrays are applied only when non-null. Throws on unknown id.
            """)
    @Transactional
    public CoachHypothesisDto updateCoachHypothesis(UpdateCoachHypothesisInput input) {
        requireField(input.id(), "id");
        CoachHypothesis entity = hypotheses.findById(input.id()).orElseThrow(
                () -> new IllegalArgumentException("Hypothesis not found: " + input.id()));
        if (input.status() != null) {
            requireOneOf(input.status(), VALID_HYPOTHESIS_STATUSES, "status");
            entity.setStatus(input.status());
        }
        if (input.confidence() != null) entity.setConfidence(input.confidence());
        if (input.supportingObservationIds() != null) {
            entity.setSupportingObservationIds(input.supportingObservationIds());
        }
        if (input.contradictingObservationIds() != null) {
            entity.setContradictingObservationIds(input.contradictingObservationIds());
        }
        return hypotheses.save(entity).toDto();
    }

    @Tool(description = """
            List a subject's hypotheses, most-recently-updated first. Pass `status`
            (open|supported|revised|dropped) to filter — e.g. "open" for still-live patterns; omit
            for all. `limit` capped at 200, default 50. Scoped to (householdId, subject).
            """)
    @Transactional(readOnly = true)
    public List<CoachHypothesisDto> listCoachHypotheses(UUID householdId, UUID subject,
                                                        String status, Integer limit) {
        requireField(householdId, "householdId");
        requireField(subject, "subject");
        PageRequest page = PageRequest.of(0, cappedLimit(limit));
        List<CoachHypothesis> rows;
        if (status == null || status.isBlank()) {
            rows = hypotheses.findByHouseholdIdAndSubjectOrderByUpdatedAtDesc(householdId, subject, page);
        } else {
            requireOneOf(status, VALID_HYPOTHESIS_STATUSES, "status");
            rows = hypotheses.findByHouseholdIdAndSubjectAndStatusOrderByUpdatedAtDesc(
                    householdId, subject, status, page);
        }
        return rows.stream().map(CoachHypothesis::toDto).toList();
    }

    // ---------- coach_action ----------

    @Tool(description = """
            Propose a values-tied next step — lands as status=proposed. Optionally link a `valueId`
            and/or `hypothesisId` (each must belong to the same subject); `dueAt` is optional.
            """)
    @Transactional
    public CoachActionDto addCoachAction(AddCoachActionInput input) {
        requireField(input.householdId(), "householdId");
        requireField(input.subject(), "subject");
        requireField(input.text(), "text");
        if (input.valueId() != null) requireValueInScope(input.valueId(), input.householdId(), input.subject());
        if (input.hypothesisId() != null) {
            requireHypothesisInScope(input.hypothesisId(), input.householdId(), input.subject());
        }
        CoachAction entity = new CoachAction(UUID.randomUUID(), input.householdId(), input.subject(),
                input.text(), input.valueId(), input.hypothesisId(), "proposed", input.dueAt());
        return actions.save(entity).toDto();
    }

    @Tool(description = """
            Advance an action's follow-up state. `id` required; `status`
            (proposed|active|done|dropped) and `dueAt` applied when non-null. Throws on unknown id.
            """)
    @Transactional
    public CoachActionDto updateCoachAction(UpdateCoachActionInput input) {
        requireField(input.id(), "id");
        CoachAction entity = actions.findById(input.id()).orElseThrow(
                () -> new IllegalArgumentException("Action not found: " + input.id()));
        if (input.status() != null) {
            requireOneOf(input.status(), VALID_ACTION_STATUSES, "status");
            entity.setStatus(input.status());
        }
        if (input.dueAt() != null) entity.setDueAt(input.dueAt());
        return actions.save(entity).toDto();
    }

    @Tool(description = """
            List a subject's actions, newest first. Pass `status` (proposed|active|done|dropped) to
            filter — e.g. "active" for the current follow-ups; omit for all. `limit` capped at 200,
            default 50. Scoped to (householdId, subject).
            """)
    @Transactional(readOnly = true)
    public List<CoachActionDto> listCoachActions(UUID householdId, UUID subject,
                                                 String status, Integer limit) {
        requireField(householdId, "householdId");
        requireField(subject, "subject");
        PageRequest page = PageRequest.of(0, cappedLimit(limit));
        List<CoachAction> rows;
        if (status == null || status.isBlank()) {
            rows = actions.findByHouseholdIdAndSubjectOrderByCreatedAtDesc(householdId, subject, page);
        } else {
            requireOneOf(status, VALID_ACTION_STATUSES, "status");
            rows = actions.findByHouseholdIdAndSubjectAndStatusOrderByCreatedAtDesc(
                    householdId, subject, status, page);
        }
        return rows.stream().map(CoachAction::toDto).toList();
    }

    // ---------- coach_intake ----------

    @Tool(description = """
            Store a deliberate intake question + answer. `askedBy` ∈ onboarding|session (defaults
            to "session"); `topic` is an optional short tag. coach_value/coach_profile are seeded
            from these later (CO-3).
            """)
    @Transactional
    public CoachIntakeDto addCoachIntakeAnswer(AddCoachIntakeAnswerInput input) {
        requireField(input.householdId(), "householdId");
        requireField(input.subject(), "subject");
        requireField(input.question(), "question");
        String askedBy = (input.askedBy() == null || input.askedBy().isBlank())
                ? "session" : input.askedBy();
        requireOneOf(askedBy, VALID_ASKED_BY, "askedBy");
        CoachIntake entity = new CoachIntake(UUID.randomUUID(), input.householdId(), input.subject(),
                input.topic(), input.question(), input.answer(), askedBy);
        return intake.save(entity).toDto();
    }

    @Tool(description = """
            List a subject's stored intake answers, newest first. `limit` capped at 200, default 50.
            Scoped to (householdId, subject).
            """)
    @Transactional(readOnly = true)
    public List<CoachIntakeDto> listCoachIntake(UUID householdId, UUID subject, Integer limit) {
        requireField(householdId, "householdId");
        requireField(subject, "subject");
        return intake.findByHouseholdIdAndSubjectOrderByCreatedAtDesc(
                        householdId, subject, PageRequest.of(0, cappedLimit(limit)))
                .stream().map(CoachIntake::toDto).toList();
    }

    // ---------- helpers ----------

    private void requireSessionInScope(UUID sessionId, UUID householdId, UUID subject) {
        CoachSession s = sessions.findById(sessionId).orElseThrow(
                () -> new IllegalArgumentException("Session not found: " + sessionId));
        requireSameSubject(s.getHouseholdId(), s.getSubject(), householdId, subject, "Session", sessionId);
    }

    private void requireValueInScope(UUID valueId, UUID householdId, UUID subject) {
        CoachValue v = values.findById(valueId).orElseThrow(
                () -> new IllegalArgumentException("Value not found: " + valueId));
        requireSameSubject(v.getHouseholdId(), v.getSubject(), householdId, subject, "Value", valueId);
    }

    private void requireHypothesisInScope(UUID hypothesisId, UUID householdId, UUID subject) {
        CoachHypothesis h = hypotheses.findById(hypothesisId).orElseThrow(
                () -> new IllegalArgumentException("Hypothesis not found: " + hypothesisId));
        requireSameSubject(h.getHouseholdId(), h.getSubject(), householdId, subject, "Hypothesis", hypothesisId);
    }

    private static void requireSameSubject(UUID rowHousehold, UUID rowSubject, UUID householdId,
                                           UUID subject, String what, UUID id) {
        if (!rowHousehold.equals(householdId) || !rowSubject.equals(subject)) {
            throw new IllegalArgumentException(
                    what + " does not belong to this subject: " + id);
        }
    }

    private static int cappedLimit(Integer limit) {
        return limit == null ? DEFAULT_LIMIT : Math.min(Math.max(limit, 1), LIST_HARD_CAP);
    }

    private static void requireOneOf(String value, Set<String> allowed, String name) {
        if (!allowed.contains(value)) {
            throw new IllegalArgumentException(
                    "Unsupported " + name + ": " + value + " (expected one of " + allowed + ")");
        }
    }

    private static void requireField(Object value, String name) {
        if (value == null) throw new IllegalArgumentException("Missing required field: " + name);
        if (value instanceof String s && s.isBlank()) {
            throw new IllegalArgumentException("Missing required field: " + name);
        }
    }
}
