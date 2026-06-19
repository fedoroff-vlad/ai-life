package dev.fedorov.ailife.memory.capture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * LLM-driven extraction of durable, long-term facts from a message — the
 * reasoning half of memory-from-chat (Stage 4). Given a piece of dialogue it
 * returns zero or more self-contained fact sentences worth remembering; the
 * caller ({@code CaptureService}) persists them as memories.
 *
 * <p>Best-effort by design: a malformed LLM reply, a parse failure, or a blank
 * input all yield an empty list rather than an exception — capture must never
 * break the path that triggered it.
 */
@Component
public class FactExtractor {

    private static final Logger log = LoggerFactory.getLogger(FactExtractor.class);

    private static final String SYSTEM_PROMPT = """
            You extract durable, long-term facts worth remembering about the speaker
            or the people they mention — stable preferences, allergies, relationships,
            traits, important dates, habits. Ignore transient or operational content:
            requests, commands, scheduling mechanics, questions, greetings, and one-off
            events that carry no lasting fact. Each fact must be a self-contained
            sentence understandable without the original message, written in the same
            language as the message. Do not invent details that are not in the message.
            Respond with strict JSON only, no prose and no markdown fences:
            {"facts": ["...", "..."]}. If there is nothing durable to remember,
            respond {"facts": []}.
            """;

    private final LlmClient llm;
    private final ObjectMapper json;

    public FactExtractor(LlmClient llm, ObjectMapper json) {
        this.llm = llm;
        this.json = json;
    }

    /** Extract zero or more durable facts from a message. Never throws. */
    public List<String> extract(String text) {
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
            return parseFacts(resp.content());
        } catch (Exception e) {
            log.warn("fact extraction failed: {}", e.toString());
            return List.of();
        }
    }

    private List<String> parseFacts(String content) {
        String cleaned = stripFences(content).trim();
        // Tolerate any leading prose before the JSON object.
        int brace = cleaned.indexOf('{');
        if (brace > 0) {
            cleaned = cleaned.substring(brace);
        }
        List<String> facts = new ArrayList<>();
        try {
            JsonNode arr = json.readTree(cleaned).get("facts");
            if (arr != null && arr.isArray()) {
                for (JsonNode f : arr) {
                    String s = f.asText("").trim();
                    if (!s.isBlank()) {
                        facts.add(s);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("could not parse facts JSON: {}", e.toString());
        }
        return facts;
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
