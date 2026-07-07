package dev.fedorov.ailife.agents.coach.http;

import dev.fedorov.ailife.agents.coach.config.CoachAgentProperties;
import dev.fedorov.ailife.contracts.note.NoteDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Reads the subject's <b>own</b> self-reflection material from the second brain: the household's recent
 * notes ({@code GET /v1/notes}) filtered down to {@code journal|reflection|goal} notes <b>owned by the
 * subject</b>. The owner filter is the privacy boundary (coach Decision 0): household-shared and other
 * members' notes never enter a coaching session, even though the list endpoint returns them.
 * Soft-fails to empty — thin material degrades the session, it must not sink it.
 */
@Component
public class SubjectNotesClient {

    private static final Logger log = LoggerFactory.getLogger(SubjectNotesClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    private static final Set<String> REFLECTIVE_TYPES = Set.of("journal", "reflection", "goal");
    private static final ParameterizedTypeReference<List<NoteDto>> NOTE_LIST =
            new ParameterizedTypeReference<>() {};

    private final WebClient http;
    private final CoachAgentProperties props;

    public SubjectNotesClient(@Qualifier("memoryServiceWebClient") WebClient http,
                              CoachAgentProperties props) {
        this.http = http;
        this.props = props;
    }

    /** The subject's own journal/reflection/goal notes, newest first, capped for the prompt. */
    public Mono<List<NoteDto>> subjectNotes(UUID householdId, UUID subject) {
        if (householdId == null || subject == null) {
            return Mono.just(List.of());
        }
        return http.get()
                .uri(b -> b.path("/v1/notes")
                        .queryParam("householdId", householdId)
                        .queryParam("limit", props.getNotesScanLimit()).build())
                .retrieve()
                .bodyToMono(NOTE_LIST)
                .timeout(TIMEOUT)
                .map(notes -> notes.stream()
                        .filter(n -> subject.equals(n.ownerId()))
                        .filter(n -> n.type() != null && REFLECTIVE_TYPES.contains(n.type()))
                        .limit(props.getGatheredNotesMax())
                        .toList())
                .onErrorResume(e -> {
                    log.warn("subject notes read failed for household={}: {}", householdId, e.toString());
                    return Mono.just(List.of());
                });
    }
}
