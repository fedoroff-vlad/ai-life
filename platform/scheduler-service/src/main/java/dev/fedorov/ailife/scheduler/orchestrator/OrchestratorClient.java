package dev.fedorov.ailife.scheduler.orchestrator;

import dev.fedorov.ailife.contracts.schedule.AgentWakeRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public class OrchestratorClient {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorClient.class);

    private final WebClient http;

    public OrchestratorClient(WebClient orchestratorWebClient) {
        this.http = orchestratorWebClient;
    }

    /**
     * Fire-and-wait wake-up of an agent. Throws if orchestrator returns non-2xx so the
     * caller can roll back the schedule's last_run_ts update.
     */
    public void wake(AgentWakeRequest request) {
        http.post()
                .uri("/v1/agents/wake")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .toBodilessEntity()
                .doOnError(e -> log.error("wake call failed for schedule={}", request.scheduleId(), e))
                .block();
    }
}
