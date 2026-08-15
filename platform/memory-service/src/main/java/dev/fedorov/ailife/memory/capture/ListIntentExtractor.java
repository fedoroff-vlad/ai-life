package dev.fedorov.ailife.memory.capture;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM-driven decision engine for <b>ambient list capture</b> (plans/lists.md §LI-b, riding
 * plans/ambient-capture.md) — the lists counterpart of {@link NoteWorthinessExtractor}. From an ordinary
 * message it decides, on its own, whether the speaker signalled a "put this on a list" intent
 * ("надо купить молоко", "заканчивается кофе", "не забыть взять зонт"), emitting zero or more
 * {@link ListItemCandidate}s. It does NOT write anything — LI-b2 applies the write (auto-save + notify).
 *
 * <p>Best-effort by design, exactly like {@code FactExtractor}/{@code NoteWorthinessExtractor}: a malformed
 * LLM reply, a parse failure, or a blank input all yield an empty list rather than an exception — capture
 * must never break the message path that triggered it. The prompt is deliberately conservative (most
 * chatter is not a list intent) to keep the auto-save posture safe.
 */
@Component
public class ListIntentExtractor {

    private static final Logger log = LoggerFactory.getLogger(ListIntentExtractor.class);

    private static final String SYSTEM_PROMPT = """
            You maintain a person's everyday item lists (a shopping / to-buy / groceries list, or a
            things-to-pack list). From an ordinary message, decide whether the speaker signalled an intent
            to PUT one or more items on such a list. You do NOT act on most messages — only a genuine
            add-to-list intent produces output.

            Emit an item ONLY for a clear future need to acquire or bring something, such as:
            - "надо/нужно купить X", "купить X", "не забыть купить X", "need to buy X", "add X to the list".
            - running low, which implies buying: "заканчивается X", "кончился X", "we're out of X".
            - packing intent: "нужно взять X (в поездку)", "не забыть X", "pack X".

            Do NOT emit for:
            - a past purchase or something already done ("купил X", "взял X", "got the X") — that is not a
              thing to add.
            - questions, requests/commands to the assistant, scheduling, opinions, facts, or small-talk.
            - a durable personal fact/preference (that is a note, handled elsewhere), unless it is clearly an
              item to acquire.

            Fields per item:
            - "item": the single thing to put on the list, in the message's language, lightly cleaned
              (e.g. "молоко", "хлеб", "зонт"). One item per entry — split a list of things into several.
            - "list": the list the speaker means, as named or implied ("список покупок", "shopping list",
              "что взять в поездку"), or null when none is named.

            Respond with strict JSON only, no prose and no markdown fences:
            {"items": [{"item": "...", "list": "..."}]}.
            If there is no add-to-list intent, respond {"items": []}.
            """;

    private final LlmClient llm;
    private final ObjectMapper json;

    public ListIntentExtractor(LlmClient llm, ObjectMapper json) {
        this.llm = llm;
        this.json = json;
    }

    /** Extract zero or more list-add items from a message. Never throws. */
    public List<ListItemCandidate> extract(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        try {
            LlmChatResponse resp = llm.chat(LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                    LlmMessage.system(SYSTEM_PROMPT),
                    LlmMessage.user(text)))).block();
            if (resp == null || resp.content() == null) {
                return List.of();
            }
            return parseItems(resp.content());
        } catch (Exception e) {
            log.warn("list-intent extraction failed: {}", e.toString());
            return List.of();
        }
    }

    private List<ListItemCandidate> parseItems(String content) {
        String cleaned = stripFences(content).trim();
        // Tolerate any leading prose before the JSON object.
        int brace = cleaned.indexOf('{');
        int close = cleaned.lastIndexOf('}');
        if (brace >= 0 && close > brace) {
            cleaned = cleaned.substring(brace, close + 1);
        }
        List<ListItemCandidate> items = new ArrayList<>();
        try {
            JsonNode arr = json.readTree(cleaned).get("items");
            if (arr != null && arr.isArray()) {
                for (JsonNode c : arr) {
                    ListItemCandidate candidate = toCandidate(c);
                    if (candidate != null) {
                        items.add(candidate);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("could not parse list-intent JSON: {}", e.toString());
        }
        return items;
    }

    private ListItemCandidate toCandidate(JsonNode c) {
        String item = c.path("item").asString("").trim();
        // A candidate needs a concrete item; drop empty shells.
        if (item.isBlank()) {
            return null;
        }
        return new ListItemCandidate(item, normalizeList(c.path("list").asString("").trim()));
    }

    /** Blank or a literal "null" string collapses to a real {@code null} (no list named). */
    private static String normalizeList(String list) {
        if (list.isBlank() || "null".equalsIgnoreCase(list)) {
            return null;
        }
        return list;
    }

    private static String stripFences(String s) {
        String t = s.trim();
        if (t.startsWith("```")) {
            int firstNl = t.indexOf('\n');
            if (firstNl >= 0) {
                t = t.substring(firstNl + 1);
            }
            if (t.endsWith("```")) {
                t = t.substring(0, t.length() - 3);
            }
        }
        return t;
    }
}
