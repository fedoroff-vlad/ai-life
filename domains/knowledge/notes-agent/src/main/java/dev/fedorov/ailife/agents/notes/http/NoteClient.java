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

    /** One note by id — resolving a semantic-recall hit's {@code refId} back-pointer. */
    public Mono<NoteDto> get(UUID id) {
        return http.get()
                .uri("/v1/notes/{id}", id)
                .retrieve()
                .bodyToMono(NoteDto.class)
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
}
