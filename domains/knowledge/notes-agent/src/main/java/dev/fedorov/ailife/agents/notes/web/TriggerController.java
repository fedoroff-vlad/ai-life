package dev.fedorov.ailife.agents.notes.web;

import dev.fedorov.ailife.agents.notes.flow.NoteResurfacer;
import dev.fedorov.ailife.contracts.schedule.AgentWakeRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Hit by orchestrator when scheduler-service wakes this agent for a {@code kind} the notes manifest
 * declares — the same proactive path the briefing digest and calendar birthday wakes use. The one kind
 * is {@code notes.resurface}: surface one stale second-brain note to the household (see
 * {@link NoteResurfacer}). Returns 202 once the wake is accepted (the resurfacing runs best-effort and
 * never fails the schedule); an unbound kind is 404.
 */
@RestController
@RequestMapping("/agents/notes/triggers")
public class TriggerController {

    private static final Logger log = LoggerFactory.getLogger(TriggerController.class);
    private static final String RESURFACE = "notes.resurface";

    private final NoteResurfacer resurfacer;

    public TriggerController(NoteResurfacer resurfacer) {
        this.resurfacer = resurfacer;
    }

    @PostMapping("/{kind}")
    public Mono<ResponseEntity<Void>> trigger(@PathVariable String kind, @RequestBody AgentWakeRequest req) {
        if (!RESURFACE.equals(kind)) {
            log.warn("no trigger bound to kind={} (schedule={})", kind, req.scheduleId());
            return Mono.just(ResponseEntity.notFound().build());
        }
        return resurfacer.resurface(req).then(Mono.just(ResponseEntity.<Void>accepted().build()));
    }
}
