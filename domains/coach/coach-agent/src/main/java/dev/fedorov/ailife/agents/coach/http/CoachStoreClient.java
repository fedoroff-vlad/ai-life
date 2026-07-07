package dev.fedorov.ailife.agents.coach.http;

import dev.fedorov.ailife.contracts.coach.AddCoachHypothesisInput;
import dev.fedorov.ailife.contracts.coach.AddCoachObservationInput;
import dev.fedorov.ailife.contracts.coach.CoachHypothesisDto;
import dev.fedorov.ailife.contracts.coach.CoachObservationDto;
import dev.fedorov.ailife.contracts.coach.CoachProfileDto;
import dev.fedorov.ailife.contracts.coach.CoachSessionDto;
import dev.fedorov.ailife.contracts.coach.StartCoachSessionInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Thin client over mcp-coach's {@code /internal/coach/*} REST passthroughs — the coach agent's durable
 * record. Reads are enrichment and soft-fail to empty (a store blip must not sink a session); writes
 * propagate errors so the caller decides (the Reflect flow logs and still replies — the synthesis
 * already happened, losing the record beats losing the conversation).
 */
@Component
public class CoachStoreClient {

    private static final Logger log = LoggerFactory.getLogger(CoachStoreClient.class);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration WRITE_TIMEOUT = Duration.ofSeconds(3);
    private static final ParameterizedTypeReference<List<CoachSessionDto>> SESSION_LIST =
            new ParameterizedTypeReference<>() {};

    private final WebClient http;

    public CoachStoreClient(@Qualifier("mcpCoachWebClient") WebClient http) {
        this.http = http;
    }

    /** The subject's coaching vector; empty when none exists yet (agent falls back to defaults). */
    public Mono<CoachProfileDto> profile(UUID householdId, UUID subject) {
        return http.get()
                .uri(b -> b.path("/internal/coach/profile")
                        .queryParam("householdId", householdId)
                        .queryParam("subject", subject).build())
                .retrieve()
                .bodyToMono(CoachProfileDto.class)
                .timeout(READ_TIMEOUT)
                .onErrorResume(e -> {
                    log.warn("coach profile read failed for subject={}: {}", subject, e.toString());
                    return Mono.empty();
                });
    }

    /** Recent sessions, newest first (continuity); soft-fails to empty. */
    public Mono<List<CoachSessionDto>> recentSessions(UUID householdId, UUID subject, int limit) {
        return http.get()
                .uri(b -> b.path("/internal/coach/sessions")
                        .queryParam("householdId", householdId)
                        .queryParam("subject", subject)
                        .queryParam("limit", limit).build())
                .retrieve()
                .bodyToMono(SESSION_LIST)
                .timeout(READ_TIMEOUT)
                .onErrorResume(e -> {
                    log.warn("coach sessions read failed for subject={}: {}", subject, e.toString());
                    return Mono.just(List.of());
                });
    }

    public Mono<CoachSessionDto> startSession(StartCoachSessionInput input) {
        return http.post()
                .uri("/internal/coach/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(input)
                .retrieve()
                .bodyToMono(CoachSessionDto.class)
                .timeout(WRITE_TIMEOUT);
    }

    public Mono<CoachObservationDto> addObservation(AddCoachObservationInput input) {
        return http.post()
                .uri("/internal/coach/observations")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(input)
                .retrieve()
                .bodyToMono(CoachObservationDto.class)
                .timeout(WRITE_TIMEOUT);
    }

    public Mono<CoachHypothesisDto> addHypothesis(AddCoachHypothesisInput input) {
        return http.post()
                .uri("/internal/coach/hypotheses")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(input)
                .retrieve()
                .bodyToMono(CoachHypothesisDto.class)
                .timeout(WRITE_TIMEOUT);
    }
}
