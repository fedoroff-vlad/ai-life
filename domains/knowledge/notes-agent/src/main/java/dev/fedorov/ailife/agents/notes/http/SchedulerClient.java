package dev.fedorov.ailife.agents.notes.http;

import dev.fedorov.ailife.agents.notes.config.NotesAgentProperties;
import dev.fedorov.ailife.contracts.schedule.CreateScheduleRequest;
import dev.fedorov.ailife.contracts.schedule.ScheduleDto;
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
 * Thin client to scheduler-service for the auto-registered household {@code notes.resurface} cron
 * (R-c) — the wake that drives proactive resurfacing. Idempotent: {@link #ensureResurfaceSchedule}
 * checks whether the household already has a resurface schedule before creating one, so it can be
 * fired on every note capture without piling up crons (the same "ensure on first use" shape calendar
 * uses to auto-issue an ICS feed).
 *
 * <p>Best-effort: every call soft-fails to empty so a scheduler outage never faults the note capture
 * that triggered it. No-op on a null household.
 */
@Component
public class SchedulerClient {

    private static final Logger log = LoggerFactory.getLogger(SchedulerClient.class);
    /** Trigger kind + owning agent for the resurface wake — must match the notes AGENT.md trigger. */
    static final String KIND = "notes.resurface";
    static final String OWNER_AGENT = "notes";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final ParameterizedTypeReference<List<ScheduleDto>> SCHEDULE_LIST =
            new ParameterizedTypeReference<>() {};

    private final WebClient http;
    private final String cron;

    public SchedulerClient(@Qualifier("schedulerWebClient") WebClient http, NotesAgentProperties props) {
        this.http = http;
        this.cron = props.getResurfaceCron();
    }

    /**
     * Ensure the household has a {@code notes.resurface} cron: list its schedules and create one only if
     * none exists yet. Best-effort — soft-fails to empty, never throws.
     */
    public Mono<Void> ensureResurfaceSchedule(UUID householdId) {
        if (householdId == null) {
            return Mono.empty();
        }
        return listByHousehold(householdId)
                .flatMap(schedules -> hasResurface(schedules)
                        ? Mono.empty()
                        : create(householdId).then())
                .onErrorResume(e -> {
                    log.warn("ensure resurface schedule failed for household={}: {}", householdId, e.toString());
                    return Mono.empty();
                });
    }

    private Mono<List<ScheduleDto>> listByHousehold(UUID householdId) {
        return http.get()
                .uri(uri -> uri.path("/v1/schedules").queryParam("householdId", householdId).build())
                .retrieve()
                .bodyToMono(SCHEDULE_LIST)
                .timeout(TIMEOUT);
    }

    private Mono<ScheduleDto> create(UUID householdId) {
        CreateScheduleRequest req = new CreateScheduleRequest(householdId, OWNER_AGENT, KIND, cron, null, null);
        return http.post()
                .uri("/v1/schedules")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .retrieve()
                .bodyToMono(ScheduleDto.class)
                .timeout(TIMEOUT)
                .doOnNext(s -> log.info("registered resurface schedule {} for household {} (cron={})",
                        s.id(), householdId, cron));
    }

    private static boolean hasResurface(List<ScheduleDto> schedules) {
        return schedules != null && schedules.stream().anyMatch(s -> KIND.equals(s.kind()));
    }
}
