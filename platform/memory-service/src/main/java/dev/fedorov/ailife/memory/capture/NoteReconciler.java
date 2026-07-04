package dev.fedorov.ailife.memory.capture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * LLM-driven <b>reconciliation</b> for ambient capture (plans/ambient-capture.md, AC-5) — the counterpart of
 * {@link NoteWorthinessExtractor} for the moment a new note turns out to be a near-duplicate of one already
 * stored. Instead of always skipping (the AC-3 default), it decides whether the new mention adds a
 * {@link ReconcileAction#ENRICH detail}, {@link ReconcileAction#SUPERSEDE contradicts} the existing note, or
 * says {@link ReconcileAction#SKIP nothing new} — and for the first two returns the full updated body.
 *
 * <p>Best-effort like every capture LLM step: a malformed reply, a parse failure, or a blank body all
 * collapse to {@link NoteReconciliation#skip()} — on any uncertainty we leave the existing note untouched
 * rather than risk a bad rewrite.
 */
@Component
public class NoteReconciler {

    private static final Logger log = LoggerFactory.getLogger(NoteReconciler.class);

    private static final String SYSTEM_PROMPT = """
            You maintain a person's second brain. A new note has turned out to be about the same thing as a
            note that already exists. Decide what to do so the knowledge base stays clean and current, and
            return the result as strict JSON.

            Compare the EXISTING note with the INCOMING one and choose exactly one action:
            - "enrich": the incoming note adds a genuinely new detail the existing one is missing. Return
              "body" = the existing body with the new detail merged in, keeping everything already there.
            - "supersede": the incoming note contradicts or updates the existing one (a change of mind, a
              correction, "уже не так", "передумал"). Return "body" = the corrected, current body.
            - "skip": the incoming note says nothing the existing one does not already cover. Return an empty
              "body".

            Keep the merged body faithful — never invent facts, keep the person's own wording and any
            [[wiki-links]] / #tags, and stay in the note's language. Prefer "skip" when unsure.

            Respond with strict JSON only, no prose and no markdown fences:
            {"action": "enrich|supersede|skip", "body": "..."}.
            """;

    private final LlmClient llm;
    private final ObjectMapper json;

    public NoteReconciler(LlmClient llm, ObjectMapper json) {
        this.llm = llm;
        this.json = json;
    }

    /**
     * Decide how the incoming note relates to the existing one. Never throws — any failure yields
     * {@link NoteReconciliation#skip()} (leave the existing note as-is).
     */
    public NoteReconciliation reconcile(String existingTitle, String existingBody,
                                        String incomingTitle, String incomingBody) {
        try {
            LlmChatResponse resp = llm.chat(LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                    LlmMessage.system(SYSTEM_PROMPT),
                    LlmMessage.user(payload(existingTitle, existingBody, incomingTitle, incomingBody)))))
                    .block();
            if (resp == null || resp.content() == null) {
                return NoteReconciliation.skip();
            }
            return parse(resp.content());
        } catch (Exception e) {
            log.warn("note reconciliation failed: {}", e.toString());
            return NoteReconciliation.skip();
        }
    }

    private String payload(String existingTitle, String existingBody, String incomingTitle, String incomingBody) {
        ObjectNode root = json.createObjectNode();
        ObjectNode existing = root.putObject("existing");
        existing.put("title", existingTitle == null ? "" : existingTitle);
        existing.put("body", existingBody == null ? "" : existingBody);
        ObjectNode incoming = root.putObject("incoming");
        incoming.put("title", incomingTitle == null ? "" : incomingTitle);
        incoming.put("body", incomingBody == null ? "" : incomingBody);
        return root.toString();
    }

    private NoteReconciliation parse(String content) {
        String cleaned = stripFences(content).trim();
        int brace = cleaned.indexOf('{');
        if (brace > 0) {
            cleaned = cleaned.substring(brace);
        }
        try {
            JsonNode node = json.readTree(cleaned);
            ReconcileAction action = toAction(node.path("action").asText("").trim());
            if (action == ReconcileAction.SKIP) {
                return NoteReconciliation.skip();
            }
            String body = node.path("body").asText("").trim();
            // An enrich/supersede with no usable body would blank the note — fall back to skip instead.
            return body.isBlank() ? NoteReconciliation.skip() : new NoteReconciliation(action, body);
        } catch (Exception e) {
            log.warn("could not parse reconciliation JSON: {}", e.toString());
            return NoteReconciliation.skip();
        }
    }

    /** Unknown / blank action → skip (the safe default). */
    private static ReconcileAction toAction(String raw) {
        if ("enrich".equalsIgnoreCase(raw)) {
            return ReconcileAction.ENRICH;
        }
        if ("supersede".equalsIgnoreCase(raw)) {
            return ReconcileAction.SUPERSEDE;
        }
        return ReconcileAction.SKIP;
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
