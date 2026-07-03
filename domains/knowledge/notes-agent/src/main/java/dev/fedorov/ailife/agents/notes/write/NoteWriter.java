package dev.fedorov.ailife.agents.notes.write;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.notes.http.NoteClient;
import dev.fedorov.ailife.agents.notes.http.SchedulerClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.contracts.note.NoteDto;
import dev.fedorov.ailife.contracts.note.WriteNoteRequest;
import dev.fedorov.ailife.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * Captures a "запомни …" request into a durable note (SB-4).
 *
 * <p>Pipeline: one llm-gateway {@code DEFAULT} turn with the {@code note-writer} SKILL as the system
 * prompt distils a structured note (title / type / tags / body, strict JSON, temperature=0) from the
 * user's message → {@code POST /v1/notes} on memory-service stores it (which auto-seeds recall +
 * {@code [[wiki-link]]} graph edges, SB-2/SB-3) → reply confirming the title. Every stage soft-fails to
 * a friendly message; a model that produces no usable title falls back to the user's own words so
 * nothing is silently dropped.
 */
@Component
public class NoteWriter {

    private static final Logger log = LoggerFactory.getLogger(NoteWriter.class);
    private static final String SKILL_NAME = "note-writer";
    private static final String DEFAULT_SOURCE = "user";   // user-authored via the agent (manifest convention)
    private static final int TITLE_FALLBACK_CHARS = 60;

    private final NoteClient notes;
    private final SchedulerClient scheduler;
    private final LlmClient llm;
    private final SkillRegistry skills;
    private final AgentManifest manifest;
    private final ObjectMapper json;

    public NoteWriter(NoteClient notes, SchedulerClient scheduler, LlmClient llm, SkillRegistry skills,
                      AgentManifest manifest, ObjectMapper json) {
        this.notes = notes;
        this.scheduler = scheduler;
        this.llm = llm;
        this.skills = skills;
        this.manifest = manifest;
        this.json = json;
    }

    public Mono<IntentResponse> capture(NormalizedMessage msg) {
        String userText = msg == null ? null : msg.text();
        if (userText == null || userText.isBlank()) {
            return Mono.just(reply("Что запомнить? Напишите, например: «запомни, что …».", null));
        }
        // temperature=0: structuring the note must be faithful to the user, not creative.
        LlmChatRequest request = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.system(skillBody()),
                LlmMessage.user(userText)), 0.0);
        return llm.chat(request)
                .flatMap(r -> store(msg, userText, r.content(), r.model()))
                .onErrorResume(e -> {
                    log.warn("note capture failed: {}", e.toString());
                    return Mono.just(reply("Не удалось сохранить заметку. Попробуйте позже.", null));
                });
    }

    private Mono<IntentResponse> store(NormalizedMessage msg, String userText, String llmContent, String model) {
        JsonNode draft = parseDraft(llmContent);
        WriteNoteRequest req = buildRequest(msg, userText, draft);
        return notes.create(req)
                .map(saved -> {
                    // R-c: on a successful capture, make sure the household has a resurface cron. Idempotent
                    // + best-effort + off the reply path — the note is already saved, so a scheduler blip
                    // never affects the confirmation.
                    scheduler.ensureResurfaceSchedule(msg.householdId()).subscribe();
                    return reply(successText(saved.title()), model);
                })
                .onErrorResume(e -> {
                    log.warn("create note failed: {}", e.toString());
                    return Mono.just(reply("Не смог записать заметку в базу. Попробуйте позже.", null));
                });
    }

    private WriteNoteRequest buildRequest(NormalizedMessage msg, String userText, JsonNode draft) {
        String body = firstNonBlank(text(draft, "body"), userText);
        String title = firstNonBlank(text(draft, "title"), titleFrom(body));
        return new WriteNoteRequest(
                msg.householdId(),
                msg.userId(),                 // authored under the sender (null owner would be household-shared)
                title,
                text(draft, "type"),          // blank/null → memory-service defaults it to "fact"
                tags(draft),
                DEFAULT_SOURCE,
                null,                         // person link resolution stays with the [[wiki-link]] seed (SB-3)
                body,
                null);
    }

    /** A short title distilled from the body when the model gave none, so the note is still findable. */
    private static String titleFrom(String body) {
        if (body == null || body.isBlank()) {
            return "Заметка";
        }
        String oneLine = body.strip().replaceAll("\\s+", " ");
        return oneLine.length() > TITLE_FALLBACK_CHARS
                ? oneLine.substring(0, TITLE_FALLBACK_CHARS).strip() + "…"
                : oneLine;
    }

    private List<String> tags(JsonNode draft) {
        if (draft == null || !draft.hasNonNull("tags") || !draft.get("tags").isArray()) {
            return null;
        }
        List<String> tags = new ArrayList<>();
        for (JsonNode t : draft.get("tags")) {
            String v = t.asText();
            if (v != null && !v.isBlank()) {
                tags.add(v.trim());
            }
        }
        return tags.isEmpty() ? null : tags;
    }

    private static String successText(String title) {
        String what = (title != null && !title.isBlank()) ? "«" + title + "»" : "заметку";
        return "Запомнил: " + what + ". Спросите «что я думал про …», чтобы найти.";
    }

    private String skillBody() {
        return skills.all().stream()
                .filter(s -> SKILL_NAME.equals(s.name()))
                .map(Skill::body)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "note-writer SKILL.md not loaded — check skills-classpath"));
    }

    /** Lenient JSON extraction: tolerate markdown fences / leading prose around the object. */
    private JsonNode parseDraft(String content) {
        if (content == null) {
            return null;
        }
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            JsonNode node = json.readTree(content.substring(start, end + 1));
            return node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) return null;
        String v = node.get(field).asText();
        return v.isBlank() ? null : v;
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }

    private IntentResponse reply(String text, String model) {
        return new IntentResponse(manifest.name(), text, model);
    }
}
