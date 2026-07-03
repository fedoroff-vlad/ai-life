package dev.fedorov.ailife.agents.notes.web;

import dev.fedorov.ailife.agents.notes.chat.NotesChat;
import dev.fedorov.ailife.agents.notes.find.NoteFinder;
import dev.fedorov.ailife.agents.notes.write.NoteWriter;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Locale;

/**
 * Hit by the orchestrator when intent routing selects {@code notes}:
 * <ul>
 *   <li>a "запомни …" / "note this" capture cue → {@link NoteWriter#capture} (structure → store);</li>
 *   <li>a "что я думал про …" recall cue → {@link NoteFinder#find} (distil → recall → reply);</li>
 *   <li>otherwise → the {@link NotesChat} fallback.</li>
 * </ul>
 * The find/write split is a deterministic keyword heuristic — good enough for the MVP,
 * MockWebServer-testable, replaceable by an LLM classifier later. The find check comes first because
 * "что я думал про …" is unambiguously a recall, whereas a bare capture is the natural default verb.
 */
@RestController
@RequestMapping("/agents/notes")
public class IntentController {

    private static final List<String> FIND_CUES = List.of(
            "что я думал", "что я думаю", "что я записывал", "что я записал", "что я отмечал",
            "что я сохранял", "что я знаю про", "напомни, что я", "напомни что я",
            "what did i think", "what did i note", "what did i write about", "what did i save",
            "what do i know about", "find my note", "recall my");

    private static final List<String> WRITE_CUES = List.of(
            "запомни", "запиши", "заметка:", "заметку", "сохрани, что", "сохрани что", "отметь, что",
            "отметь что", "note this", "note that", "remember that", "remember this", "take a note",
            "make a note", "jot down");

    private final NoteWriter writer;
    private final NoteFinder finder;
    private final NotesChat chat;

    public IntentController(NoteWriter writer, NoteFinder finder, NotesChat chat) {
        this.writer = writer;
        this.finder = finder;
        this.chat = chat;
    }

    @PostMapping("/intent")
    public Mono<IntentResponse> intent(@RequestBody NormalizedMessage message) {
        String text = message == null ? null : message.text();
        if (isMatch(text, FIND_CUES)) {
            return finder.find(message);
        }
        if (isMatch(text, WRITE_CUES)) {
            return writer.capture(message);
        }
        return chat.reply(message);
    }

    private static boolean isMatch(String text, List<String> cues) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String t = text.toLowerCase(Locale.ROOT);
        return cues.stream().anyMatch(t::contains);
    }
}
