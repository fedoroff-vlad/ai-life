package dev.fedorov.ailife.agents.notes.review;

import dev.fedorov.ailife.agentruntime.http.MemoryClient;
import dev.fedorov.ailife.agents.notes.http.NoteClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.memory.MemoryDto;
import dev.fedorov.ailife.contracts.note.NoteDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Answers "что ты про меня / про нас запомнил" — the <b>memory-review digest</b> (MQ-1, road-test
 * <a href="https://github.com/fedoroff-vlad/ai-life/issues/488">#488</a>): a readable audit of what the
 * system remembers, so the owner can see it and then prune what is wrong.
 *
 * <p>Read-only and deterministic — no LLM turn (routing already picked this flow; formatting a list needs
 * no model). It gathers two tiers from memory-service and folds them into one message:
 * <ul>
 *   <li><b>Notes</b> — the curated tier ({@link NoteClient#list}); {@code type=list} checklists are
 *   excluded (those are the lists capability's surface, not remembered facts about the owner).</li>
 *   <li><b>Facts</b> — the associative tier ({@link MemoryClient#listMemories}, scoped to the owner +
 *   household-shared); note-seed rows ({@code source=note}) are excluded because they are the same notes
 *   already shown above.</li>
 * </ul>
 * The digest ends with the drop/correct verbs so the owner can act on what they see — "удали заметку про
 * …" (note delete, H.2) and "забудь, что …" (fact forget, MQ-2). Every stage soft-fails: the underlying
 * clients already downgrade to empty on error, so a memory-service blip just yields a thinner digest, never
 * an exception.
 */
@Component
public class MemoryReviewer {

    private static final Logger log = LoggerFactory.getLogger(MemoryReviewer.class);
    private static final String LIST_TYPE = "list";
    /** memory-service source tag a note's recall seed carries (SB-2) — excluded so notes aren't double-listed. */
    private static final String NOTE_SEED_SOURCE = "note";
    private static final int NOTE_LIMIT = 15;
    private static final int FACT_LIMIT = 15;
    private static final int SNIPPET_CHARS = 120;

    private final NoteClient notes;
    private final MemoryClient memory;
    private final AgentManifest manifest;

    public MemoryReviewer(NoteClient notes, MemoryClient memory, AgentManifest manifest) {
        this.notes = notes;
        this.memory = memory;
        this.manifest = manifest;
    }

    public Mono<IntentResponse> review(NormalizedMessage msg) {
        if (msg == null || msg.householdId() == null) {
            return Mono.just(reply("Не могу показать память без контекста беседы."));
        }
        Mono<List<NoteDto>> noteList = notes.list(msg.householdId(), NOTE_LIMIT)
                .onErrorResume(e -> {
                    log.warn("memory-review note list failed: {}", e.toString());
                    return Mono.just(List.of());
                });
        Mono<List<MemoryDto>> factList =
                memory.listMemories(msg.householdId(), msg.userId(), null, FACT_LIMIT);
        return Mono.zip(noteList, factList)
                .map(t -> reply(format(t.getT1(), t.getT2())))
                .onErrorResume(e -> {
                    log.warn("memory-review failed: {}", e.toString());
                    return Mono.just(reply("Не удалось собрать, что я запомнил. Попробуйте позже."));
                });
    }

    private String format(List<NoteDto> notesRaw, List<MemoryDto> factsRaw) {
        List<NoteDto> notes = notesRaw.stream()
                .filter(n -> !LIST_TYPE.equalsIgnoreCase(n.type()))
                .toList();
        List<MemoryDto> facts = factsRaw.stream()
                .filter(m -> !NOTE_SEED_SOURCE.equalsIgnoreCase(m.source()))
                .toList();

        if (notes.isEmpty() && facts.isEmpty()) {
            return "Пока я про тебя ничего не запомнил. Скажи «запомни …», чтобы что-то сохранить.";
        }

        StringBuilder sb = new StringBuilder("Вот что я про тебя запомнил.");
        if (!notes.isEmpty()) {
            sb.append("\n\n📝 Заметки:");
            for (NoteDto n : notes) {
                sb.append("\n• ").append(noteLine(n));
            }
        }
        if (!facts.isEmpty()) {
            sb.append("\n\n💭 Факты:");
            for (MemoryDto m : facts) {
                sb.append("\n• ").append(snippet(m.text()));
            }
        }
        sb.append("\n\nЧтобы что-то убрать — скажи «удали заметку про …» или «забудь, что …».");
        return sb.toString();
    }

    private static String noteLine(NoteDto n) {
        String title = n.title() != null && !n.title().isBlank() ? n.title() : "заметка";
        String snippet = snippet(n.bodyMd());
        return snippet == null ? title : title + " — " + snippet;
    }

    private static String snippet(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        String oneLine = body.strip().replaceAll("\\s+", " ");
        return oneLine.length() > SNIPPET_CHARS
                ? oneLine.substring(0, SNIPPET_CHARS).strip() + "…"
                : oneLine;
    }

    private IntentResponse reply(String text) {
        return new IntentResponse(manifest.name(), text, null);
    }
}
