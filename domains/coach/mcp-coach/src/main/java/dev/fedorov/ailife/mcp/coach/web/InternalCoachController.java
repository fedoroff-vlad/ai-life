package dev.fedorov.ailife.mcp.coach.web;

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
import dev.fedorov.ailife.mcp.coach.tools.CoachMcpTools;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Non-MCP REST passthroughs over {@link CoachMcpTools} — for deterministic system callers (the
 * coach-agent, CO-2+) with no LLM tax. Every endpoint delegates straight to the matching tool, so
 * all invariants (subject scope, status/method/mode whitelists, cross-subject guards) apply
 * identically. Validation failures → 400 (the shared handler below). Mirrors mcp-tasks' and
 * mcp-finance's {@code /internal/*} passthroughs.
 */
@RestController
@RequestMapping("/internal/coach")
public class InternalCoachController {

    private final CoachMcpTools tools;

    public InternalCoachController(CoachMcpTools tools) {
        this.tools = tools;
    }

    // ---------- profile ----------

    @PostMapping("/profile")
    public CoachProfileDto upsertProfile(@RequestBody UpsertCoachProfileInput input) {
        return tools.upsertCoachProfile(input);
    }

    @GetMapping("/profile")
    public CoachProfileDto getProfile(@RequestParam UUID householdId, @RequestParam UUID subject) {
        return tools.getCoachProfile(householdId, subject);
    }

    // ---------- values ----------

    @PostMapping("/values")
    public CoachValueDto addValue(@RequestBody AddCoachValueInput input) {
        return tools.addCoachValue(input);
    }

    @GetMapping("/values")
    public List<CoachValueDto> listValues(@RequestParam UUID householdId, @RequestParam UUID subject,
                                          @RequestParam(required = false) Boolean activeOnly) {
        return tools.listCoachValues(householdId, subject, activeOnly);
    }

    // ---------- sessions ----------

    @PostMapping("/sessions")
    public CoachSessionDto startSession(@RequestBody StartCoachSessionInput input) {
        return tools.startCoachSession(input);
    }

    @GetMapping("/sessions")
    public List<CoachSessionDto> listSessions(@RequestParam UUID householdId, @RequestParam UUID subject,
                                              @RequestParam(required = false) Integer limit) {
        return tools.listRecentCoachSessions(householdId, subject, limit);
    }

    // ---------- observations ----------

    @PostMapping("/observations")
    public CoachObservationDto addObservation(@RequestBody AddCoachObservationInput input) {
        return tools.addCoachObservation(input);
    }

    @GetMapping("/observations")
    public List<CoachObservationDto> listObservations(@RequestParam UUID householdId,
                                                      @RequestParam UUID subject,
                                                      @RequestParam(required = false) UUID sessionId,
                                                      @RequestParam(required = false) Integer limit) {
        return tools.listCoachObservations(householdId, subject, sessionId, limit);
    }

    // ---------- hypotheses ----------

    @PostMapping("/hypotheses")
    public CoachHypothesisDto addHypothesis(@RequestBody AddCoachHypothesisInput input) {
        return tools.addCoachHypothesis(input);
    }

    @PatchMapping("/hypotheses")
    public CoachHypothesisDto updateHypothesis(@RequestBody UpdateCoachHypothesisInput input) {
        return tools.updateCoachHypothesis(input);
    }

    @GetMapping("/hypotheses")
    public List<CoachHypothesisDto> listHypotheses(@RequestParam UUID householdId,
                                                   @RequestParam UUID subject,
                                                   @RequestParam(required = false) String status,
                                                   @RequestParam(required = false) Integer limit) {
        return tools.listCoachHypotheses(householdId, subject, status, limit);
    }

    // ---------- actions ----------

    @PostMapping("/actions")
    public CoachActionDto addAction(@RequestBody AddCoachActionInput input) {
        return tools.addCoachAction(input);
    }

    @PatchMapping("/actions")
    public CoachActionDto updateAction(@RequestBody UpdateCoachActionInput input) {
        return tools.updateCoachAction(input);
    }

    @GetMapping("/actions")
    public List<CoachActionDto> listActions(@RequestParam UUID householdId, @RequestParam UUID subject,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(required = false) Integer limit) {
        return tools.listCoachActions(householdId, subject, status, limit);
    }

    // ---------- intake ----------

    @PostMapping("/intake")
    public CoachIntakeDto addIntakeAnswer(@RequestBody AddCoachIntakeAnswerInput input) {
        return tools.addCoachIntakeAnswer(input);
    }

    @GetMapping("/intake")
    public List<CoachIntakeDto> listIntake(@RequestParam UUID householdId, @RequestParam UUID subject,
                                           @RequestParam(required = false) Integer limit) {
        return tools.listCoachIntake(householdId, subject, limit);
    }

    /** Tool-layer validation (missing field, bad status/method/mode, cross-subject) → 400. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> onBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
