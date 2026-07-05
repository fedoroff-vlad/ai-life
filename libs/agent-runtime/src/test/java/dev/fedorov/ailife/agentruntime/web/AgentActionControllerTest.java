package dev.fedorov.ailife.agentruntime.web;

import tools.jackson.databind.node.JsonNodeFactory;
import dev.fedorov.ailife.contracts.agent.AgentActionRequest;
import dev.fedorov.ailife.contracts.agent.AgentActionResult;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AgentActionControllerTest {

    /** A tiny concrete controller: one good action, one that fails, one that rejects bad args. */
    private static final class TestController extends AgentActionController {
        TestController() {
            super("test");
            register("ping", req -> Mono.just(AgentActionResult.ok(
                    JsonNodeFactory.instance.objectNode().put("pong", true))));
            register("boom", req -> Mono.error(new IllegalStateException("kaboom")));
            register("validate", req -> Mono.just(AgentActionResult.error("validate requires args.x")));
        }

        Mono<AgentActionResult> call(String action) {
            return dispatch(action, req(action));
        }
    }

    private static AgentActionRequest req(String action) {
        return new AgentActionRequest("test", action, UUID.randomUUID(), UUID.randomUUID(), "caller", null);
    }

    private final TestController controller = new TestController();

    @Test
    void knownActionRunsHandler() {
        AgentActionResult r = controller.call("ping").block();
        assertThat(r.ok()).isTrue();
        assertThat(r.result().get("pong").asBoolean()).isTrue();
    }

    @Test
    void unknownActionIsStructuredError() {
        AgentActionResult r = controller.call("nope").block();
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).isEqualTo("test: unknown action 'nope'");
    }

    @Test
    void handlerFailureWrappedAsActionFailed() {
        AgentActionResult r = controller.call("boom").block();
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).isEqualTo("boom failed: kaboom");
    }

    @Test
    void handlerBadArgsErrorPassesThroughUnwrapped() {
        AgentActionResult r = controller.call("validate").block();
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).isEqualTo("validate requires args.x");   // not re-wrapped as "validate failed:"
    }
}
