package dev.fedorov.ailife.agents.inventory.web;

import dev.fedorov.ailife.agentruntime.web.AgentActionController;
import dev.fedorov.ailife.agents.inventory.scan.BoxScanner;
import dev.fedorov.ailife.contracts.agent.AgentActionRequest;
import dev.fedorov.ailife.contracts.agent.AgentActionResult;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Inter-agent action endpoint for inventory (Stage 4 / Track C1 envelope).
 *
 * <p>Registers <b>{@code show_container}</b> — the scan path (IN-f). A scanned label carries no
 * sentence to classify, so the gateway does not route it as a message: it dispatches the decoded
 * {@code qrToken} through the hub ({@code POST /v1/agents/invoke}) and shows whatever this returns.
 * Reusing the existing C1 primitive keeps the scan deterministic (no LLM turn, no classifier guess) and
 * adds no new wire contract.
 *
 * <p>An unknown token answers {@code ok=false} with the user-facing text, which the caller surfaces
 * verbatim — the honest "не нашёл коробку по этой этикетке" rather than a nearby box.
 */
@RestController
public class ActionController extends AgentActionController {

    private final BoxScanner scanner;
    private final ObjectMapper json;

    public ActionController(BoxScanner scanner, ObjectMapper json) {
        super("inventory");
        this.scanner = scanner;
        this.json = json;
        register("show_container", this::showContainer);
    }

    @PostMapping("/agents/inventory/actions/{action}")
    public Mono<AgentActionResult> action(@PathVariable String action,
                                          @RequestBody AgentActionRequest request) {
        return dispatch(action, request);
    }

    /** A scanned label → its container's card. Args: {@code {qrToken}}. */
    private Mono<AgentActionResult> showContainer(AgentActionRequest request) {
        String qrToken = stringArg(request, "qrToken");
        if (qrToken == null) {
            return Mono.just(AgentActionResult.error("show_container requires args.qrToken"));
        }
        return scanner.scan(request.householdId(), request.userId(), qrToken)
                .map(scanned -> {
                    ObjectNode node = json.createObjectNode();
                    node.put("message", scanned.message());
                    if (scanned.cardUrl() != null) {
                        node.put("cardUrl", scanned.cardUrl());
                    }
                    return AgentActionResult.ok(node);
                })
                .defaultIfEmpty(AgentActionResult.error(
                        "Не нашёл коробку по этой этикетке. Возможно, её удалили — назовите код коробки."));
    }

    private static String stringArg(AgentActionRequest request, String field) {
        JsonNode args = request.args();
        if (args == null) {
            return null;
        }
        JsonNode v = args.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asString().trim();
        return s.isEmpty() ? null : s;
    }
}
