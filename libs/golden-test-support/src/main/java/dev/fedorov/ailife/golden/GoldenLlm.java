package dev.fedorov.ailife.golden;

import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillParser;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.llm.LlmClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Shared plumbing for the Stage 5 golden tests (#199) — everything except the per-surface fixtures and
 * assertions. A golden test reaches the real model through a live llm-gateway; these helpers build the
 * client and load the real prompts off the classpath so each test only carries what's unique to it.
 *
 * <p>Use with {@link GoldenLlmTest} (the opt-in gate). All methods are static and side-effect free.
 */
public final class GoldenLlm {

    private GoldenLlm() {
    }

    /** Where the golden tests reach a running llm-gateway ({@code GOLDEN_LLM_GATEWAY_URL}, default 8081). */
    public static String gatewayUrl() {
        String url = System.getenv("GOLDEN_LLM_GATEWAY_URL");
        return (url == null || url.isBlank()) ? "http://localhost:8081" : url.trim();
    }

    /** An {@link LlmClient} pointed at the live gateway ({@link #gatewayUrl()}). */
    public static LlmClient client() {
        return new LlmClient(WebClient.builder().baseUrl(gatewayUrl()).build());
    }

    /** A private-scope message with random household + user ids. */
    public static NormalizedMessage message(String text) {
        return message(UUID.randomUUID(), UUID.randomUUID(), text);
    }

    /** A private-scope message with a fixed household (so a mocked per-household client matches) + random user. */
    public static NormalizedMessage message(UUID householdId, String text) {
        return message(householdId, UUID.randomUUID(), text);
    }

    /** A private-scope message with fixed household + user ids. */
    public static NormalizedMessage message(UUID householdId, UUID userId, String text) {
        return new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                text, List.of(), "telegram", "golden", Instant.now());
    }

    /**
     * The agent's system prompt — its {@code AGENT.md} body (YAML frontmatter stripped), loaded off the
     * given classloader's root. Falls back to a generic line if the resource is absent (never in
     * practice — it's packaged with the agent).
     */
    public static String agentBody(ClassLoader cl) {
        try (InputStream in = cl.getResourceAsStream("AGENT.md")) {
            if (in == null) {
                return "You are an ai-life agent.";
            }
            String md = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (md.startsWith("---")) {
                int close = md.indexOf("\n---", 3);
                if (close >= 0) {
                    int bodyStart = md.indexOf('\n', close + 1);
                    if (bodyStart >= 0) {
                        return md.substring(bodyStart + 1).strip();
                    }
                }
            }
            return md.strip();
        } catch (Exception e) {
            return "You are an ai-life agent.";
        }
    }

    /**
     * Parse a real {@code SKILL.md} off the classpath at {@code resourcePath} (e.g.
     * {@code "skills/tasks/inbox-clarify/SKILL.md"}) into a {@link Skill}.
     */
    public static Skill skill(ClassLoader cl, String resourcePath) {
        try (InputStream in = cl.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("SKILL.md not on the test classpath: " + resourcePath);
            }
            return SkillParser.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("failed to load SKILL.md: " + resourcePath, e);
        }
    }
}
