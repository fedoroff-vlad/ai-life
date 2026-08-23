package dev.fedorov.ailife.agents.notes.intent;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.intent.CandidateView;
import dev.fedorov.ailife.agentruntime.intent.Nouns;
import dev.fedorov.ailife.agentruntime.intent.PickConfirmActRunner;
import dev.fedorov.ailife.agentruntime.intent.TargetedActionFlow;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.notes.http.NoteClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.agent.ResumeRequest;
import dev.fedorov.ailife.contracts.note.NoteDto;
import dev.fedorov.ailife.llm.LlmClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Runs the reactive {@code note-delete} intent skill (road-test #486, Track H.2 — the notes per-domain
 * delete hole): delete a saved note by just chatting ("удали заметку про X / последнюю заметку"), behind a
 * <b>confirm-before-delete</b> gate. When {@code NotesIntentRouter} classifies a message as a delete
 * request, it dispatches to {@link #delete}; the confirming reply routes back through {@code ResumeController}
 * to {@link #resume}.
 *
 * <p>The pick→confirm→act loop itself lives in the shared {@link PickConfirmActRunner} (ADR-0004); this
 * class is the notes adapter — it names the target ({@link #nouns}), reads the candidate pool (the
 * household's recent notes, with {@code type=list} notes excluded — lists are LI-a's job), renders a
 * candidate ({@link CandidateView}), and performs the delete ({@link #act} via memory-service
 * {@code DELETE /v1/notes/{id}} — the same reversal the undo primitive uses, which also drops the recall
 * seed + wiki-link edges).
 */
@Component
public class NoteDeleter implements TargetedActionFlow<NoteDto>, CandidateView<NoteDto> {

    public static final String SKILL_NAME = "note-delete";
    /** pendingAction discriminator the notes ResumeController dispatches on. */
    public static final String FLOW = "note-delete-confirm";
    private static final int MAX_CANDIDATES = 40;
    private static final int SNIPPET_LEN = 100;

    private final NoteClient notes;
    private final PickConfirmActRunner<NoteDto> runner;

    public NoteDeleter(LlmClient llm, AgentManifest manifest, SkillRegistry skills,
                       NoteClient notes, ObjectMapper json) {
        this.notes = notes;
        this.runner = new PickConfirmActRunner<>(llm, manifest, skills, json, this);
    }

    /** Turn 1: read the household's recent notes, let the LLM pick, and reply with a confirm {@code pendingAction}. */
    public Mono<IntentResponse> delete(NormalizedMessage msg) {
        return runner.pick(msg);
    }

    /** Turn 2: an affirmative deletes the stashed note; anything else leaves it. */
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
        return "noteId";
    }

    @Override
    public String labelField() {
        return "title";
    }

    @Override
    public Nouns nouns() {
        return new Nouns("заметку", "заметок", "заметка");
    }

    @Override
    public Set<String> extraAffirmatives() {
        return Set.of("забудь");
    }

    @Override
    public Mono<List<NoteDto>> candidates(NormalizedMessage msg) {
        return notes.list(msg.householdId(), MAX_CANDIDATES).map(NoteDeleter::deletable);
    }

    @Override
    public CandidateView<NoteDto> view() {
        return this;
    }

    @Override
    public Mono<Void> act(UUID targetId, JsonNode params) {
        return notes.delete(targetId).then();
    }

    /** List notes are maintained through the list-manager (LI-a), not deleted wholesale here. */
    private static List<NoteDto> deletable(List<NoteDto> all) {
        if (all == null) {
            return List.of();
        }
        List<NoteDto> out = new ArrayList<>();
        for (NoteDto n : all) {
            if (!"list".equalsIgnoreCase(n.type())) {
                out.add(n);
            }
            if (out.size() >= MAX_CANDIDATES) {
                break;
            }
        }
        return out;
    }

    // ----- CandidateView ----------------------------------------------------------------------------

    @Override
    public UUID id(NoteDto n) {
        return n.id();
    }

    @Override
    public String label(NoteDto n) {
        return "«" + safeTitle(n) + "»";
    }

    @Override
    public void describe(ObjectNode node, NoteDto n) {
        node.put("title", safeTitle(n));
        if (n.type() != null) {
            node.put("type", n.type());
        }
        String snippet = snippet(n.bodyMd());
        if (snippet != null) {
            node.put("snippet", snippet);
        }
    }

    private static String snippet(String bodyMd) {
        if (bodyMd == null || bodyMd.isBlank()) {
            return null;
        }
        String flat = bodyMd.strip().replaceAll("\\s+", " ");
        return flat.length() > SNIPPET_LEN ? flat.substring(0, SNIPPET_LEN) + "…" : flat;
    }

    private static String safeTitle(NoteDto n) {
        return (n.title() != null && !n.title().isBlank()) ? n.title() : "заметка";
    }
}
