package dev.fedorov.ailife.agents.coordinator.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * The <b>confidence self-check</b> of the bounded coordinator loop (#290, Slice E-later). After a
 * synthesis round it judges — in one cheap {@link LlmChannel#FAST} call — whether the draft answer
 * already, fully and confidently, addresses the owner's request, or whether a concrete piece of
 * domain information is still missing that <i>one more gather round</i> could fill. It is the signal
 * {@link MultiDomainCoordinator} uses to decide between "return this answer" and "spend another
 * plan → gather → re-synthesize round" (within the {@code max-rounds} budget).
 *
 * <p><b>Fail-safe toward stopping:</b> a parse failure, an LLM error, or an empty reply all resolve to
 * {@link Assessment#sufficient() sufficient}. A broken judge must never drive the loop to spend more
 * rounds — cheap-first, and the loop is also hard-bounded by {@code max-rounds} regardless.
 */
@Component
public class SufficiencyAssessor {

    private static final Logger log = LoggerFactory.getLogger(SufficiencyAssessor.class);

    /** Stable substring the test harness keys on to recognise a self-check turn; keep in sync with the prompt. */
    public static final String ASSESSOR_MARKER = "self-check step of a cross-cutting assistant";

    private static final String ASSESSOR_SYSTEM = """
            You are the %s. You are given the owner's request and a draft answer just synthesized for it.
            Judge whether the draft ALREADY answers the request fully, confidently, and grounded — or
            whether one concrete, gatherable piece of domain information is still missing (e.g. a budget
            figure, an upcoming date, a specific fact a domain specialist could supply). Reply with a JSON
            object exactly of the form {"sufficient": true|false, "missing": "<short focus phrase or empty>"}.
            Set "sufficient": true when the draft is good enough to send as is; set it false ONLY when a
            single extra gather round would clearly improve it, and put a short focus phrase for that round
            in "missing". Output ONLY the JSON object — no prose, no code fence.""".formatted(ASSESSOR_MARKER);

    private final LlmClient llm;
    private final ObjectMapper json;

    public SufficiencyAssessor(LlmClient llm, ObjectMapper json) {
        this.llm = llm;
        this.json = json;
    }

    /**
     * Assess the draft answer for the request. Any failure (LLM error, unparseable reply, blank draft)
     * resolves to {@link Assessment#sufficient()} — the loop then stops on best-effort.
     */
    public Mono<Assessment> assess(String query, String draftAnswer) {
        if (draftAnswer == null || draftAnswer.isBlank()) {
            return Mono.just(Assessment.complete());
        }
        List<LlmMessage> messages = List.of(
                LlmMessage.system(ASSESSOR_SYSTEM),
                LlmMessage.user("Request: " + (query == null ? "" : query) + "\n\nDraft answer:\n" + draftAnswer));
        return llm.chat(LlmChatRequest.of(LlmChannel.FAST, messages, 0.0))
                .map(resp -> parse(resp.content()))
                .onErrorResume(e -> {
                    log.warn("sufficiency self-check failed, treating draft as sufficient: {}", e.toString());
                    return Mono.just(Assessment.complete());
                });
    }

    /** Parse the judge's JSON object; anything unparseable or without an explicit {@code false} → sufficient. */
    private Assessment parse(String content) {
        if (content == null) {
            return Assessment.complete();
        }
        int open = content.indexOf('{');
        int close = content.lastIndexOf('}');
        if (open < 0 || close <= open) {
            return Assessment.complete();
        }
        try {
            JsonNode obj = json.readTree(content.substring(open, close + 1));
            boolean sufficient = obj.path("sufficient").asBoolean(true);
            if (sufficient) {
                return Assessment.complete();
            }
            String missing = obj.path("missing").asText("").strip();
            return Assessment.needsMore(missing);
        } catch (Exception e) {
            log.warn("could not parse self-check reply '{}': {}", content, e.toString());
            return Assessment.complete();
        }
    }

    /**
     * The judge's verdict: whether the draft is good enough to send, and — when not — a short focus phrase
     * to sharpen the next gather round.
     */
    public record Assessment(boolean sufficient, String missing) {
        public static Assessment complete() {
            return new Assessment(true, "");
        }

        public static Assessment needsMore(String missing) {
            return new Assessment(false, missing == null ? "" : missing);
        }
    }
}
