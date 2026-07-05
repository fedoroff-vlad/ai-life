package dev.fedorov.ailife.agents.creator.web;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.agentruntime.web.AgentActionController;
import dev.fedorov.ailife.agents.creator.flow.GreetingDrafter;
import dev.fedorov.ailife.contracts.agent.AgentActionRequest;
import dev.fedorov.ailife.contracts.agent.AgentActionResult;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Inter-agent action endpoint (CR-g). The orchestrator forwards an {@link AgentActionRequest} here
 * when another agent invokes the creator — specifically the calendar birthday wake (the
 * gift-recommender's sibling) asking for a greeting, closing the Stage-4 chain
 * {@code calendar.birthday_upcoming → creator.draft_greeting → notifier.send}. The only action is
 * {@code draft_greeting}: it reads {@code args.person} (required) + {@code args.occasion} (optional,
 * defaults to a birthday), runs {@link GreetingDrafter#draft}, and returns {@code {greeting, model}}
 * so the caller can deliver the text via notifier.
 *
 * <p>Always replies with an {@link AgentActionResult} (never an HTTP error), so the caller gets a
 * structured {@code ok=false} on a bad request or LLM failure and can soft-fail. Mirrors the chef's
 * {@code ActionController}.
 */
@RestController
public class ActionController extends AgentActionController {

    private final GreetingDrafter greetingDrafter;
    private final ObjectMapper json;

    public ActionController(GreetingDrafter greetingDrafter, ObjectMapper json) {
        super("creator");
        this.greetingDrafter = greetingDrafter;
        this.json = json;
        register("draft_greeting", this::draftGreeting);
    }

    @PostMapping("/agents/creator/actions/{action}")
    public Mono<AgentActionResult> action(@PathVariable String action,
                                          @RequestBody AgentActionRequest request) {
        return dispatch(action, request);
    }

    private Mono<AgentActionResult> draftGreeting(AgentActionRequest request) {
        JsonNode args = request.args();
        String person = text(args, "person");
        if (person == null) {
            return Mono.just(AgentActionResult.error("draft_greeting requires args.person"));
        }
        String occasion = text(args, "occasion");
        return greetingDrafter.draft(person, occasion)
                .map(draft -> {
                    var result = json.createObjectNode();
                    if (draft.text() != null) result.put("greeting", draft.text());
                    if (draft.model() != null) result.put("model", draft.model());
                    return AgentActionResult.ok(result);
                });
    }

    private static String text(JsonNode args, String field) {
        if (args == null || !args.hasNonNull(field)) return null;
        String s = args.get(field).asText().strip();
        return s.isEmpty() ? null : s;
    }
}
