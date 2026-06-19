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
 * LLM-driven extraction of structured relations ({@code subject —edge→ object})
 * from a message — the graph half of memory-from-chat (Stage 4). It is the
 * relation counterpart of {@link FactExtractor}: where the latter yields free-text
 * memories, this yields the edges that build the person graph
 * ({@code memory.relations}).
 *
 * <p>Best-effort by design: a malformed LLM reply, a parse failure, or a blank
 * input all yield an empty list rather than an exception — capture must never
 * break the path that triggered it. This component is latent until a later slice
 * resolves the labels to {@code core.people} UUIDs and writes the edges.
 */
@Component
public class RelationExtractor {

    private static final Logger log = LoggerFactory.getLogger(RelationExtractor.class);

    private static final String SYSTEM_PROMPT = """
            You extract durable relationships between people and things from a message,
            as structured triples: subject, edge, object. Capture stable relations worth
            remembering — preferences (likes/dislikes), allergies, kinship, where someone
            works or lives, what someone owns. Ignore transient or operational content:
            requests, commands, scheduling mechanics, questions, greetings, one-off events.
            Rules:
            - "subject" is the entity the relation is about. Use the literal "self" when
              the relation is about the speaker (e.g. "я люблю джаз" -> subject "self").
              Otherwise use the person's name as written.
            - "edge" is a short snake_case predicate in English (e.g. likes, dislikes,
              allergic_to, works_at, lives_in, owns, related_as).
            - "object" is the target, written in the same language as the message.
            Do not invent details that are not in the message. Respond with strict JSON
            only, no prose and no markdown fences:
            {"relations": [{"subject": "...", "edge": "...", "object": "..."}]}.
            If there is no durable relation, respond {"relations": []}.
            """;

    private final LlmClient llm;
    private final ObjectMapper json;

    public RelationExtractor(LlmClient llm, ObjectMapper json) {
        this.llm = llm;
        this.json = json;
    }

    /** Extract zero or more structured relations from a message. Never throws. */
    public List<ExtractedRelation> extract(String text) {
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
            return parseRelations(resp.content());
        } catch (Exception e) {
            log.warn("relation extraction failed: {}", e.toString());
            return List.of();
        }
    }

    private List<ExtractedRelation> parseRelations(String content) {
        String cleaned = stripFences(content).trim();
        // Tolerate any leading prose before the JSON object.
        int brace = cleaned.indexOf('{');
        if (brace > 0) {
            cleaned = cleaned.substring(brace);
        }
        List<ExtractedRelation> relations = new ArrayList<>();
        try {
            JsonNode arr = json.readTree(cleaned).get("relations");
            if (arr != null && arr.isArray()) {
                for (JsonNode r : arr) {
                    String subject = r.path("subject").asText("").trim();
                    String edge = r.path("edge").asText("").trim();
                    String object = r.path("object").asText("").trim();
                    if (!subject.isBlank() && !edge.isBlank() && !object.isBlank()) {
                        relations.add(new ExtractedRelation(subject, edge, object));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("could not parse relations JSON: {}", e.toString());
        }
        return relations;
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
