package dev.fedorov.ailife.golden;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a Stage 5 <b>golden test</b> (#199) — a class that exercises a real surface against a <b>real
 * model</b> (local Ollama via a running llm-gateway). Composes the two annotations every golden test
 * needs so they stay in one place:
 * <ul>
 *   <li>{@code @Tag("golden")} — lets CI/build filters single these out;</li>
 *   <li>{@code @EnabledIfEnvironmentVariable(GOLDEN_LLM)} — the opt-in gate: a normal {@code mvn test}
 *       (CI default, {@code GOLDEN_LLM} unset) <b>skips</b> them, so the suite stays green on the mock
 *       provider with no model.</li>
 * </ul>
 *
 * <p>Pair with {@link GoldenLlm} for the gateway client + AGENT.md/SKILL.md loaders. The per-test
 * fixtures and assertions are deliberately <b>not</b> shared — each golden test validates its own
 * surface's contract (a routing token vs strict JSON vs a grounded free-text answer).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Tag("golden")
@EnabledIfEnvironmentVariable(named = "GOLDEN_LLM", matches = "(?i)1|true|yes|on")
public @interface GoldenLlmTest {
}
