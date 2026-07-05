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
 * LLM-driven decision engine for <b>ambient / intuitive capture</b> (plans/ambient-capture.md, AC-1) — the
 * curated-note counterpart of {@link FactExtractor}. From an ordinary message it decides, on its own, what
 * is worth a note and about whom, emitting zero or more {@link NoteCandidate}s each tagged with the
 * three-way {@link CaptureOutcome} (explicit fixation → auto-save, important inferred → approve, trivial →
 * ignore). This slice extracts + classifies only; a later phase persists the surviving candidates.
 *
 * <p>Best-effort by design, exactly like {@code FactExtractor}: a malformed LLM reply, a parse failure, or
 * a blank input all yield an empty list rather than an exception — capture must never break the message
 * path that triggered it.
 */
@Component
public class NoteWorthinessExtractor {

    private static final Logger log = LoggerFactory.getLogger(NoteWorthinessExtractor.class);

    private static final String SYSTEM_PROMPT = """
            You maintain a person's second brain by deciding, from an ordinary message, what is worth
            keeping as a durable note and about whom. You do NOT write everything down — most chatter is
            not note-worthy. Classify each note-worthy thing you find into a candidate.

            A candidate is worth keeping only if it is a durable, self-contained fact: a stable preference,
            allergy, relationship, trait, decision, goal, idea, plan, or reflection about the speaker or a
            person they mention. Ignore transient or operational content: requests, commands, scheduling
            mechanics, questions, greetings, small-talk, and one-off events that carry no lasting fact.

            For each candidate decide two independent things:
            - "explicitFixation": true when the message explicitly asks to record this — cues like
              "запомни", "отметь", "зафиксируй", "не забудь", "запиши", "remember", "note that". false when
              you inferred the note-worthiness yourself with no such cue.
            - "importance": "important" when the fact is genuinely worth keeping even without a cue,
              otherwise "trivial". Only consulted when there is no explicit fixation.

            Fields per candidate:
            - "title": a short distinctive title the person would search for, in the message's language.
            - "type": one of person|fact|idea|goal|journal|reflection. Use "person" for a note about a
              specific other person, "fact" when unsure.
            - "body": the substance of the note, faithful to what was said; do not invent details.
            - "subject": who it is about — the literal "self" for the speaker, or the person's name as
              written, or null when it is about no specific person.
            - "importance": "important" or "trivial" (see above).
            - "explicitFixation": true or false (see above).

            Respond with strict JSON only, no prose and no markdown fences:
            {"candidates": [{"title": "...", "type": "...", "body": "...", "subject": "...",
            "importance": "important", "explicitFixation": false}]}.
            If there is nothing worth a note, respond {"candidates": []}.
            """;

    private final LlmClient llm;
    private final ObjectMapper json;

    public NoteWorthinessExtractor(LlmClient llm, ObjectMapper json) {
        this.llm = llm;
        this.json = json;
    }

    /** Extract + classify zero or more note candidates from a message. Never throws. */
    public List<NoteCandidate> extract(String text) {
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
            return parseCandidates(resp.content());
        } catch (Exception e) {
            log.warn("note-worthiness extraction failed: {}", e.toString());
            return List.of();
        }
    }

    private List<NoteCandidate> parseCandidates(String content) {
        String cleaned = stripFences(content).trim();
        // Tolerate any leading prose before the JSON object.
        int brace = cleaned.indexOf('{');
        int close = cleaned.lastIndexOf('}');
        if (brace >= 0 && close > brace) {
            cleaned = cleaned.substring(brace, close + 1);
        }
        List<NoteCandidate> candidates = new ArrayList<>();
        try {
            JsonNode arr = json.readTree(cleaned).get("candidates");
            if (arr != null && arr.isArray()) {
                for (JsonNode c : arr) {
                    NoteCandidate candidate = toCandidate(c);
                    if (candidate != null) {
                        candidates.add(candidate);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("could not parse note candidates JSON: {}", e.toString());
        }
        return candidates;
    }

    private NoteCandidate toCandidate(JsonNode c) {
        String title = c.path("title").asText("").trim();
        String body = c.path("body").asText("").trim();
        // A candidate needs at least something to store; drop empty shells.
        if (title.isBlank() && body.isBlank()) {
            return null;
        }
        String type = c.path("type").asText("").trim();
        String subject = normalizeSubject(c.path("subject").asText("").trim());
        String importance = c.path("importance").asText("").trim();
        boolean explicitFixation = c.path("explicitFixation").asBoolean(false);
        return new NoteCandidate(title, type, body, subject, importance, explicitFixation);
    }

    /** Blank or a literal "null" string collapses to a real {@code null} (about no specific person). */
    private static String normalizeSubject(String subject) {
        if (subject.isBlank() || "null".equalsIgnoreCase(subject)) {
            return null;
        }
        return subject;
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
