package dev.fedorov.ailife.agents.notes.http;

import dev.fedorov.ailife.contracts.note.NoteBacklinksResponse;
import dev.fedorov.ailife.contracts.note.NoteDto;
import dev.fedorov.ailife.contracts.note.WriteNoteRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;

/**
 * Thin client over memory-service's {@code /v1/notes} surface — the authored-notes tier of the
 * second-brain substrate (SB-1..3). The notes agent captures via {@link #create} and resolves a
 * recall hit's {@code refId} back to its note via {@link #get}, then surfaces its {@link #backlinks}
 * (the connected notes). Reuses the shared {@code memoryServiceWebClient} — {@code /v1/notes} lives on
 * memory-service alongside {@code /v1/memories}.
 */
@Component
public class NoteClient {

    private final WebClient http;

    public NoteClient(@Qualifier("memoryServiceWebClient") WebClient http) {
        this.http = http;
    }

    public Mono<NoteDto> create(WriteNoteRequest req) {
        return http.post()
                .uri("/v1/notes")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .retrieve()
                .bodyToMono(NoteDto.class)
                .timeout(Duration.ofSeconds(10));
    }

    /** Replace the mutable fields of an existing note ({@code PUT /v1/notes/{id}}) — the lists path
     *  reads a list note, mutates its checklist body, and writes it back. */
    public Mono<NoteDto> update(UUID id, WriteNoteRequest req) {
        return http.put()
                .uri("/v1/notes/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .retrieve()
                .bodyToMono(NoteDto.class)
                .timeout(Duration.ofSeconds(10));
    }

    /** The household's notes ({@code GET /v1/notes?householdId=…}) — the lists path filters these to
     *  {@code type=list} to find a list by title (find-or-create). */
    public Mono<java.util.List<NoteDto>> list(UUID householdId, int limit) {
        return http.get()
                .uri(uri -> uri.path("/v1/notes")
                        .queryParam("householdId", householdId)
                        .queryParam("limit", limit)
                        .build())
                .retrieve()
                .bodyToFlux(NoteDto.class)
                .collectList()
                .timeout(Duration.ofSeconds(10));
    }

    /** One note by id — resolving a semantic-recall hit's {@code refId} back-pointer. */
    public Mono<NoteDto> get(UUID id) {
        return http.get()
                .uri("/v1/notes/{id}", id)
                .retrieve()
                .bodyToMono(NoteDto.class)
                .timeout(Duration.ofSeconds(10));
    }

    /**
     * Forget a note ({@code DELETE /v1/notes/{id}}) — the deterministic reversal behind the "отмени
     * последнее" undo primitive (road-test #486, Track H): notes-agent's {@code /actions/undo} calls it to
     * delete a just-captured note (memory-service also cleans up its recall seed + {@code [[wiki-link]]}
     * edges). A {@code 204} completes empty; an unknown id (404) or any error propagates so the caller
     * ({@code ActionController.undo}) can surface an honest "не нашёл заметку для отмены" instead of
     * pretending it undid something.
     */
    public Mono<Void> delete(UUID id) {
        return http.delete()
                .uri("/v1/notes/{id}", id)
                .retrieve()
                .bodyToMono(Void.class)
                .timeout(Duration.ofSeconds(10));
    }

    /** The notes that link to this note (SB-3 graph edges) — the "connected notes" of a recall hit. */
    public Mono<NoteBacklinksResponse> backlinks(UUID id) {
        return http.get()
                .uri("/v1/notes/{id}/backlinks", id)
                .retrieve()
                .bodyToMono(NoteBacklinksResponse.class)
                .timeout(Duration.ofSeconds(10));
    }

    /**
     * One stale note worth resurfacing for the household — the proactive-resurfacing wake source
     * ({@code GET /v1/notes/resurface}). A {@code 204} (nothing stale) yields an empty {@link Mono}.
     */
    public Mono<NoteDto> resurface(UUID householdId, int olderThanDays) {
        return http.get()
                .uri(uri -> uri.path("/v1/notes/resurface")
                        .queryParam("householdId", householdId)
                        .queryParam("olderThanDays", olderThanDays)
                        .build())
                .retrieve()
                .bodyToMono(NoteDto.class)
                .timeout(Duration.ofSeconds(10));
    }
}
