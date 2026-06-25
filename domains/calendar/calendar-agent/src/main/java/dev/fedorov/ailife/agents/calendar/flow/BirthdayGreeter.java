package dev.fedorov.ailife.agents.calendar.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.http.NotifierClient;
import dev.fedorov.ailife.agentruntime.http.ProfileClient;
import dev.fedorov.ailife.agentruntime.http.OrchestratorInvokeClient;
import dev.fedorov.ailife.contracts.agent.AgentActionRequest;
import dev.fedorov.ailife.contracts.agent.AgentActionResult;
import dev.fedorov.ailife.contracts.profile.PersonDto;
import dev.fedorov.ailife.contracts.schedule.AgentWakeRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;

/**
 * Closes the Stage-4 inter-agent chain {@code calendar.birthday_upcoming → creator.draft_greeting →
 * notifier.send} (CR-g2). On a {@code birthday.greet} wake this asks the <b>creator</b> agent to draft
 * the greeting — content authoring is the creator's specialty, the calendar only owns the occasion —
 * by invoking {@code creator.draft_greeting} over the orchestrator hub (agents never call each other
 * directly), then fans the returned text out to every household member via notifier-service.
 *
 * <p>The gift-recommender's sibling: same invoke→notify shape, a greeting instead of budget-aware
 * gift ideas. <b>Best-effort and reversible:</b> {@link #greet} returns {@code false} whenever the
 * creator can't produce a greeting (no person to name, the hub/LLM errors, an {@code ok=false} result,
 * or an empty draft) so the caller falls back to the calendar's own {@code birthday-greeter} skill —
 * the wake always greets, even if the creator agent is down.
 */
@Component
public class BirthdayGreeter {

    private static final Logger log = LoggerFactory.getLogger(BirthdayGreeter.class);

    /** The greeting draft sits behind an LLM call on the creator side, so allow more than the hub default. */
    private static final Duration DRAFT_TIMEOUT = Duration.ofSeconds(30);
    private static final String OCCASION = "birthday";

    private final OrchestratorInvokeClient orchestrator;
    private final ProfileClient profile;
    private final NotifierClient notifier;
    private final ObjectMapper json;

    public BirthdayGreeter(OrchestratorInvokeClient orchestrator,
                           ProfileClient profile,
                           NotifierClient notifier,
                           ObjectMapper json) {
        this.orchestrator = orchestrator;
        this.profile = profile;
        this.notifier = notifier;
        this.json = json;
    }

    /**
     * Draft the greeting via the creator agent and fan it out. Resolves to {@code true} when a greeting
     * was produced and delivery was attempted, {@code false} when the creator couldn't help (caller
     * falls back to the local skill).
     */
    public Mono<Boolean> greet(AgentWakeRequest req, PersonDto person) {
        UUID household = req == null ? null : req.householdId();
        if (household == null || person == null
                || person.displayName() == null || person.displayName().isBlank()) {
            return Mono.just(false);
        }

        ObjectNode args = json.createObjectNode()
                .put("person", person.displayName().strip())
                .put("occasion", OCCASION);
        var request = new AgentActionRequest(
                "creator", "draft_greeting", household, null, "calendar", args);

        return orchestrator.invoke(request, DRAFT_TIMEOUT)
                .flatMap(result -> {
                    String greeting = greeting(result);
                    if (greeting == null) {
                        log.info("creator.draft_greeting unusable (ok={}) — falling back to local skill",
                                result.ok());
                        return Mono.just(false);
                    }
                    return notifyHousehold(household, greeting).thenReturn(true);
                })
                .onErrorResume(e -> {
                    log.warn("creator.draft_greeting invoke failed — falling back to local skill: {}",
                            e.toString());
                    return Mono.just(false);
                });
    }

    /** The drafted greeting text from a successful result, or null when there's nothing usable. */
    private static String greeting(AgentActionResult result) {
        if (result == null || !result.ok() || result.result() == null) {
            return null;
        }
        JsonNode g = result.result().get("greeting");
        if (g == null || g.isNull()) {
            return null;
        }
        String text = g.asText().strip();
        return text.isEmpty() ? null : text;
    }

    /** Fan the greeting out to every household member; a per-user notify failure is logged, not fatal. */
    private Mono<Void> notifyHousehold(UUID household, String greeting) {
        return profile.usersByHousehold(household)
                .flatMap(u -> notifier.notify(u.id(), greeting)
                        .doOnError(e -> log.warn("notify failed for user={}: {}", u.id(), e.toString()))
                        .onErrorResume(e -> Mono.empty()))
                .then();
    }
}
