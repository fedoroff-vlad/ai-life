package dev.fedorov.ailife.agents.notes.intent;

import dev.fedorov.ailife.agentruntime.http.MemoryClient;
import dev.fedorov.ailife.agentruntime.intent.CandidateView;
import dev.fedorov.ailife.agentruntime.intent.Phrasing;
import dev.fedorov.ailife.agentruntime.intent.PickConfirmActRunner;
import dev.fedorov.ailife.agentruntime.intent.TargetedActionFlow;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.agent.ResumeRequest;
import dev.fedorov.ailife.contracts.memory.MemoryDto;
import dev.fedorov.ailife.llm.LlmClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Runs the reactive {@code fact-forget} intent skill (road-test #488, MQ-2 — the fact-tier analog of the
 * note delete/edit holes): <b>forget</b> or <b>correct</b> a remembered fact by just chatting ("забудь, что
 * я курю", "это неверно, на самом деле я живу в Казани"), behind a <b>confirm-before-change</b> gate. When
 * {@code NotesIntentRouter} classifies a message as a fact-forget request it dispatches to {@link #forget};
 * the confirming reply routes back through {@code ResumeController} to {@link #resume}.
 *
 * <p>The pick→confirm→act loop itself lives in the shared {@link PickConfirmActRunner} (ADR-0004); this
 * class is the fact adapter. Candidates are the household's remembered facts ({@link MemoryClient#listMemories},
 * scoped to the owner + household-shared, enumerated by recency like MQ-1's digest); note-seed rows
 * ({@code source=note}) are excluded — those are the same notes shown/deleted by the note flows, so
 * forgetting one belongs to {@code note-delete} (which also drops the seed). The LLM picks the target fact
 * and — only when the user restated it — extracts a {@code correction}, threaded through the
 * {@code pendingAction}. The terminal {@link #act}:
 * <ul>
 *   <li><b>forget</b> (no correction) → {@link MemoryClient#deleteMemory} (memory-service
 *   {@code DELETE /v1/memories/{id}}), so the fact no longer surfaces in recall;</li>
 *   <li><b>correct</b> → re-read the row for its household/user ({@link MemoryClient#getMemory} — mirrors how
 *   {@code TransactionEditor}/{@code NoteEditor} re-read their target), forget it, then write the corrected
 *   fact under the same scope ({@link MemoryClient#remember}).</li>
 * </ul>
 * Every stage soft-fails to a friendly reply.
 */
@Component
public class FactForgetter
        implements TargetedActionFlow<MemoryDto>, CandidateView<MemoryDto>, Phrasing<MemoryDto> {

    public static final String SKILL_NAME = "fact-forget";
    /** pendingAction discriminator the notes ResumeController dispatches on. */
    public static final String FLOW = "fact-forget-confirm";
    /** Source tag a corrected fact is stored under (so an audit can tell a correction from an ambient fact). */
    private static final String CORRECTION_SOURCE = "correction";
    /** memory-service source tag a note's recall seed carries (SB-2) — excluded so notes stay note-flow's job. */
    private static final String NOTE_SEED_SOURCE = "note";
    private static final int MAX_CANDIDATES = 40;
    private static final int SNIPPET_LEN = 120;

    private final MemoryClient memory;
    private final PickConfirmActRunner<MemoryDto> runner;

    public FactForgetter(LlmClient llm, AgentManifest manifest, SkillRegistry skills,
                         MemoryClient memory, ObjectMapper json) {
        this.memory = memory;
        this.runner = new PickConfirmActRunner<>(llm, manifest, skills, json, this);
    }

    /** Turn 1: read the household's remembered facts, let the LLM pick (+ any correction), and confirm. */
    public Mono<IntentResponse> forget(NormalizedMessage msg) {
        return runner.pick(msg);
    }

    /** Turn 2: an affirmative forgets (and, for a correction, writes the fixed fact); anything else leaves it. */
    public Mono<IntentResponse> resume(ResumeRequest req) {
        return runner.resume(req);
    }

    // ----- TargetedActionFlow -----------------------------------------------------------------------

    @Override
    public String skillName() {
        return SKILL_NAME;
    }

    @Override
    public String flow() {
        return FLOW;
    }

    @Override
    public String idField() {
        return "memoryId";
    }

    @Override
    public String labelField() {
        return "fact";
    }

    @Override
    public Set<String> extraAffirmatives() {
        return Set.of("забудь", "забыть", "исправь", "исправить");
    }

    @Override
    public Mono<List<MemoryDto>> candidates(NormalizedMessage msg) {
        return memory.listMemories(msg.householdId(), msg.userId(), null, MAX_CANDIDATES)
                .map(FactForgetter::forgettable);
    }

    @Override
    public CandidateView<MemoryDto> view() {
        return this;
    }

    @Override
    public Phrasing<MemoryDto> phrasing() {
        return this;
    }

    @Override
    public Mono<Void> act(UUID targetId, JsonNode pending) {
        String correction = text(pending, "correction");
        if (correction == null) {
            return memory.deleteMemory(targetId);
        }
        // Correct = forget-then-write: re-read the row for its household/user so the fixed fact lands under
        // the same scope (a missing row → error → actFailed wording, never a silent lost correction).
        return memory.getMemory(targetId)
                .switchIfEmpty(Mono.error(new IllegalStateException("memory gone: " + targetId)))
                .flatMap(m -> memory.deleteMemory(targetId)
                        .then(memory.remember(m.householdId(), m.userId(), CORRECTION_SOURCE, correction, null)));
    }

    /** Note-seed facts mirror {@code memory.note} rows — forgetting one is note-delete's job (it drops the seed). */
    private static List<MemoryDto> forgettable(List<MemoryDto> all) {
        if (all == null) {
            return List.of();
        }
        List<MemoryDto> out = new ArrayList<>();
        for (MemoryDto m : all) {
            if (!NOTE_SEED_SOURCE.equalsIgnoreCase(m.source())) {
                out.add(m);
            }
            if (out.size() >= MAX_CANDIDATES) {
                break;
            }
        }
        return out;
    }

    // ----- CandidateView ----------------------------------------------------------------------------

    @Override
    public UUID id(MemoryDto m) {
        return m.id();
    }

    /** Plain (unquoted) fact snippet — the {@code Phrasing} wording adds the «…» itself. Stored as the label. */
    @Override
    public String label(MemoryDto m) {
        return snippet(m.text());
    }

    @Override
    public void describe(ObjectNode node, MemoryDto m) {
        node.put("fact", snippet(m.text()));
    }

    // ----- Phrasing ---------------------------------------------------------------------------------

    @Override
    public String askWhich() {
        return "Что забыть или исправить?";
    }

    @Override
    public String noHousehold() {
        return "Не понял, к какому хозяйству относится запрос.";
    }

    @Override
    public String emptyPool() {
        return "Я пока ничего про тебя не запомнил, что можно было бы забыть.";
    }

    @Override
    public String noMatch() {
        return "Не нашёл такого факта. Уточни, что забыть.";
    }

    @Override
    public String readFailed() {
        return "Не смог поднять, что я запомнил. Попробуй ещё раз позже.";
    }

    @Override
    public String notReady() {
        return "Нечего забывать — повтори запрос, пожалуйста.";
    }

    @Override
    public String ambiguous(List<MemoryDto> picks) {
        StringBuilder sb = new StringBuilder("Нашёл несколько подходящих фактов — какой забыть?");
        for (MemoryDto m : picks) {
            sb.append("\n• «").append(snippet(m.text())).append("»");
        }
        return sb.toString();
    }

    @Override
    public String confirm(MemoryDto target, JsonNode pick) {
        String correction = text(pick, "correction");
        if (correction != null) {
            return "Исправить: «" + snippet(target.text()) + "» → «" + correction
                    + "»? Ответь «да», чтобы сохранить.";
        }
        return "Забыть, что «" + snippet(target.text()) + "»? Ответь «да», чтобы забыть.";
    }

    @Override
    public String declined(JsonNode pending) {
        return "Оставил как есть: «" + label(pending) + "».";
    }

    @Override
    public String done(JsonNode pending) {
        return text(pending, "correction") != null
                ? "Исправил: теперь «" + text(pending, "correction") + "»."
                : "Забыл, что «" + label(pending) + "».";
    }

    @Override
    public String actFailed(JsonNode pending) {
        return "Не смог " + (text(pending, "correction") != null ? "исправить" : "забыть")
                + " «" + label(pending) + "» — возможно, факт уже удалён.";
    }

    /** The fact label stashed in the pendingAction (the {@code labelField}). */
    private static String label(JsonNode pending) {
        return pending.path("fact").asString("этот факт");
    }

    /** A present, non-blank string field, else null. */
    private static String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        String v = node.get(field).asString().strip();
        return v.isEmpty() ? null : v;
    }

    private static String snippet(String text) {
        if (text == null || text.isBlank()) {
            return "факт";
        }
        String flat = text.strip().replaceAll("\\s+", " ");
        return flat.length() > SNIPPET_LEN ? flat.substring(0, SNIPPET_LEN) + "…" : flat;
    }
}
