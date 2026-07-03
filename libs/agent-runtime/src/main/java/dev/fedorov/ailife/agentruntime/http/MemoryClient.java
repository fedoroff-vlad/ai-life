package dev.fedorov.ailife.agentruntime.http;

import com.fasterxml.jackson.databind.JsonNode;
import dev.fedorov.ailife.agentruntime.config.AgentRuntimeProperties;
import dev.fedorov.ailife.contracts.memory.PersonRelationsResponse;
import dev.fedorov.ailife.contracts.memory.RecallMemoryHit;
import dev.fedorov.ailife.contracts.memory.RecallMemoryRequest;
import dev.fedorov.ailife.contracts.memory.WriteMemoryRequest;
import dev.fedorov.ailife.contracts.message.MessageReceivedEvent;
import dev.fedorov.ailife.contracts.note.NoteDto;
import dev.fedorov.ailife.contracts.note.WriteNoteRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Shared memory-service client. Lifted from calendar-agent's PR17 + finance-agent's
 * PR24c — the implementations were byte-identical except for the package, so this
 * is now the canonical version.
 *
 * <p>Strict no-throw contract: any error (network, 5xx, 500 ms timeout, missing
 * required field) collapses to an empty {@link List} / a
 * {@link PersonRelationsResponse} with empty arrays. Memory is enrichment — the
 * skill MUST still run if memory-service is down.
 */
public class MemoryClient {

    private static final Logger log = LoggerFactory.getLogger(MemoryClient.class);
    private static final Duration TIMEOUT = Duration.ofMillis(500);
    /**
     * A note write embeds + graph-projects server-side (SB-2/SB-3), so it needs more headroom than the
     * 500 ms enrichment budget — but it is still best-effort (the caller's primary write already
     * landed), so we cap it rather than block the reply indefinitely.
     */
    private static final Duration NOTE_TIMEOUT = Duration.ofSeconds(3);
    private static final ParameterizedTypeReference<List<RecallMemoryHit>> HIT_LIST =
            new ParameterizedTypeReference<>() {};

    private final WebClient http;
    private final AgentRuntimeProperties props;

    public MemoryClient(@Qualifier("memoryServiceWebClient") WebClient http,
                        AgentRuntimeProperties props) {
        this.http = http;
        this.props = props;
    }

    public Mono<List<RecallMemoryHit>> recall(UUID householdId,
                                              UUID userId,
                                              UUID personId,
                                              String query) {
        if (householdId == null || query == null || query.isBlank()) {
            return Mono.just(List.of());
        }
        RecallMemoryRequest req = new RecallMemoryRequest(
                householdId, userId, personId, query, props.getMemoryRecallK());
        return http.post()
                .uri("/v1/memories/recall")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .retrieve()
                .bodyToMono(HIT_LIST)
                .timeout(TIMEOUT)
                .onErrorResume(e -> {
                    log.warn("memory recall failed for household={} query={}: {}",
                            householdId, query, e.toString());
                    return Mono.just(List.of());
                });
    }

    public Mono<PersonRelationsResponse> personRelations(UUID householdId, UUID personId) {
        if (householdId == null || personId == null) {
            return Mono.just(emptyRelations(personId));
        }
        return http.get()
                .uri(uri -> uri.path("/v1/graph/person/{id}/relations")
                        .queryParam("householdId", householdId)
                        .build(personId))
                .retrieve()
                .bodyToMono(PersonRelationsResponse.class)
                .timeout(TIMEOUT)
                .onErrorResume(e -> {
                    log.warn("memory person-relations failed for household={} person={}: {}",
                            householdId, personId, e.toString());
                    return Mono.just(emptyRelations(personId));
                });
    }

    /**
     * Fire-and-forget: drop an observation at memory-service's durable
     * {@code POST /v1/observations} so it can learn durable facts from what this
     * agent saw (memory-from-chat / MFC-c). An agent is a capture producer for the
     * surfaces the orchestrator can't see — e.g. finance-agent emits a receipt
     * caption that arrives as an attachment-only message (which the orchestrator's
     * text capture skips by design).
     *
     * <p>Off the response path: never awaited, never affects the reply, any failure
     * swallowed (best-effort passive capture, same posture as {@link #recall}).
     * No-op on missing household or blank text.
     */
    public void observe(UUID householdId, UUID userId, String text, String source) {
        if (householdId == null || text == null || text.isBlank()) {
            return;
        }
        http.post()
                .uri("/v1/observations")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new MessageReceivedEvent(householdId, userId, text, source))
                .retrieve()
                .toBodilessEntity()
                .timeout(TIMEOUT)
                .onErrorResume(e -> {
                    log.warn("memory observe failed for household={}: {}", householdId, e.toString());
                    return Mono.empty();
                })
                .subscribe();
    }

    /**
     * Durably index a piece of text at memory-service's semantic store
     * ({@code POST /v1/memories}) so a later {@link #recall} can surface it. Unlike
     * {@link #observe} — which asks memory-service to EXTRACT durable facts from a piece of
     * dialogue — this stores {@code text} verbatim under {@code source} with optional
     * {@code metadata} (e.g. a {@code {kind, refId}} back-pointer to the owning row), because
     * the caller already holds the exact corpus to embed (a document's OCR text, say).
     *
     * <p>Enrichment, not the source of truth: returns a {@code Mono<Void>} that soft-fails to
     * empty on any error / 500 ms timeout so the caller can compose it into a write flow without
     * a memory-service outage sinking the primary write. Empty (no-op) on missing household or
     * blank text — same posture as {@link #recall}.
     */
    public Mono<Void> remember(UUID householdId, UUID userId, String source, String text, JsonNode metadata) {
        if (householdId == null || text == null || text.isBlank()) {
            return Mono.empty();
        }
        return http.post()
                .uri("/v1/memories")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new WriteMemoryRequest(householdId, userId, null, source, text, metadata))
                .retrieve()
                .toBodilessEntity()
                .timeout(TIMEOUT)
                .then()
                .onErrorResume(e -> {
                    log.warn("memory remember failed for household={} source={}: {}",
                            householdId, source, e.toString());
                    return Mono.empty();
                });
    }

    /**
     * The <b>universal write seam</b> (second-brain SB-5): durably capture an authored NOTE at
     * memory-service's second-brain tier ({@code POST /v1/notes}) — the note-tier analog of
     * {@link #remember}. Where {@code remember} drops an opaque {@code memory.memories} row, a note is a
     * first-class {@code memory.note} (title + body + frontmatter + tags) that memory-service auto-seeds
     * into recall (SB-2) and whose {@code [[wiki-links]]} it projects into the graph (SB-3). An agent that
     * learns a durable, human-readable fact writes a note here instead of a raw memory, so it lands in the
     * one store every agent reads. Carry a {@code {kind, refId}} back-pointer in {@code frontmatter} so a
     * later recall hit (which returns {@code {kind:note, refId:noteId}}) can be resolved — via
     * {@link #getNote} — back to the domain row the note was seeded from.
     *
     * <p>Enrichment posture, same soft-fail contract as {@link #remember}: returns the created
     * {@link NoteDto}, downgrading to empty on any error / {@link #NOTE_TIMEOUT} so a memory-service
     * outage never sinks the caller's primary write. Empty (no-op) on a missing household or blank title
     * (memory-service requires both).
     */
    public Mono<NoteDto> note(WriteNoteRequest req) {
        if (req == null || req.householdId() == null || req.title() == null || req.title().isBlank()) {
            return Mono.empty();
        }
        return http.post()
                .uri("/v1/notes")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .retrieve()
                .bodyToMono(NoteDto.class)
                .timeout(NOTE_TIMEOUT)
                .onErrorResume(e -> {
                    log.warn("note write failed for household={} source={}: {}",
                            req.householdId(), req.source(), e.toString());
                    return Mono.empty();
                });
    }

    /**
     * The read half of the note seam: resolve a note by id ({@code GET /v1/notes/{id}}). An agent that
     * recalls a note hit ({@code {kind:note, refId}}) fetches the note here to read its own
     * {@code frontmatter} back-pointer and reach the domain row it was seeded from (SB-5 finder path).
     * Soft-fails to empty (a stale/absent note simply drops out of the results), {@link #NOTE_TIMEOUT}.
     */
    public Mono<NoteDto> getNote(UUID id) {
        if (id == null) {
            return Mono.empty();
        }
        return http.get()
                .uri("/v1/notes/{id}", id)
                .retrieve()
                .bodyToMono(NoteDto.class)
                .timeout(NOTE_TIMEOUT)
                .onErrorResume(e -> {
                    log.warn("note fetch failed for id={}: {}", id, e.toString());
                    return Mono.empty();
                });
    }

    private static PersonRelationsResponse emptyRelations(UUID personId) {
        return new PersonRelationsResponse(personId, List.of(), List.of());
    }
}
