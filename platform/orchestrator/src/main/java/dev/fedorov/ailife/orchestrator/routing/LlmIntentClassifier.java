package dev.fedorov.ailife.orchestrator.routing;

import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.contracts.memory.RecallMemoryHit;
import dev.fedorov.ailife.llm.LlmClient;
import dev.fedorov.ailife.orchestrator.agent.AgentRegistry;
import dev.fedorov.ailife.orchestrator.memory.MemoryClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Picks an agent name for an incoming {@link NormalizedMessage} by asking the
 * LLM on the {@link LlmChannel#FAST} channel. The few-shot prompt is built once
 * from {@link AgentRegistry} (frozen at startup); per-request long-term context
 * is pulled from {@link MemoryClient} and injected as a second system message
 * when non-empty.
 *
 * <p>Decision contract: the model must reply with exactly one of the known agent
 * names (case-insensitive, trimmed). Any other reply — or any failure — resolves
 * to {@code echo}, the always-available fallback. We never throw from the
 * classifier; bad LLM output simply degrades to the safe default.
 */
@Component
public class LlmIntentClassifier {

    private static final Logger log = LoggerFactory.getLogger(LlmIntentClassifier.class);
    private static final String ECHO = "echo";

    private final LlmClient llm;
    private final MemoryClient memory;
    private final Set<String> knownAgents;
    private final String systemPrompt;

    public LlmIntentClassifier(LlmClient llm, MemoryClient memory, AgentRegistry registry) {
        this.llm = llm;
        this.memory = memory;
        this.knownAgents = buildKnownSet(registry);
        this.systemPrompt = buildPrompt(registry);
        log.info("intent classifier ready: agents={}", knownAgents);
    }

    public Mono<String> classify(NormalizedMessage message) {
        if (knownAgents.size() == 1) {
            // Only echo is registered — no remote agents discovered. Don't bother
            // recalling either: echo doesn't read context.
            return Mono.just(ECHO);
        }
        return memory.recall(message.householdId(), message.userId(), message.text())
                .flatMap(hits -> {
                    List<LlmMessage> chat = new ArrayList<>(3);
                    chat.add(LlmMessage.system(systemPrompt));
                    String contextBlock = renderMemories(hits);
                    if (contextBlock != null) {
                        chat.add(LlmMessage.system(contextBlock));
                    }
                    chat.add(LlmMessage.user(message.text()));
                    LlmChatRequest req = LlmChatRequest.of(LlmChannel.FAST, chat);
                    return llm.chat(req).map(resp -> normalize(resp.content()));
                })
                .onErrorResume(e -> {
                    log.warn("intent classification failed, falling back to {}: {}", ECHO, e.toString());
                    return Mono.just(ECHO);
                });
    }

    /** {@code null} when no memories to inject — avoids an empty system message. */
    private static String renderMemories(List<RecallMemoryHit> hits) {
        if (hits == null || hits.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("Relevant long-term context for this user (most similar first):\n");
        for (RecallMemoryHit hit : hits) {
            if (hit.memory() == null || hit.memory().text() == null) continue;
            sb.append("- ").append(hit.memory().text()).append('\n');
        }
        sb.append("Use this only if it helps choose the right agent; otherwise ignore.");
        return sb.toString();
    }

    private String normalize(String raw) {
        if (raw == null) return ECHO;
        String candidate = raw.trim().toLowerCase();
        // Tolerate "Calendar.", "calendar agent", etc. — match the first token.
        int firstNonAlpha = 0;
        while (firstNonAlpha < candidate.length()
                && (Character.isLetterOrDigit(candidate.charAt(firstNonAlpha))
                    || candidate.charAt(firstNonAlpha) == '-'
                    || candidate.charAt(firstNonAlpha) == '_')) {
            firstNonAlpha++;
        }
        String head = candidate.substring(0, firstNonAlpha);
        return knownAgents.contains(head) ? head : ECHO;
    }

    private static Set<String> buildKnownSet(AgentRegistry registry) {
        var set = new java.util.LinkedHashSet<String>();
        set.add(ECHO);
        set.addAll(registry.manifests().keySet());
        return Set.copyOf(set);
    }

    private static String buildPrompt(AgentRegistry registry) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an intent classifier. Choose exactly ONE agent name for the user's message. ");
        sb.append("Reply with only the agent name, lowercase, no punctuation. ");
        sb.append("If no specialized agent fits, reply 'echo'.\n\n");
        sb.append("Available agents:\n");
        sb.append("- echo: default fallback. Use for greetings, small talk, or anything not matching a specialized agent.\n");
        for (Map.Entry<String, AgentManifest> e : registry.manifests().entrySet()) {
            AgentManifest m = e.getValue();
            sb.append("- ").append(m.name())
                    .append(": ").append(m.description() == null ? "" : m.description()).append('\n');
            if (m.intents() != null) {
                for (Map<String, String> example : m.intents()) {
                    String ex = example.get("example");
                    if (ex != null && !ex.isBlank()) {
                        sb.append("    e.g. \"").append(ex).append("\"\n");
                    }
                }
            }
        }
        sb.append("\nReply with one agent name only.");
        return sb.toString();
    }
}
