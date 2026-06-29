package dev.fedorov.ailife.agents.stylist.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.agentruntime.coordinate.Coordinator;
import dev.fedorov.ailife.agentruntime.deliver.DeliverablePublisher;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.stylist.http.WardrobeReadClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.wardrobe.WardrobeItemDto;
import dev.fedorov.ailife.golden.GoldenLlm;
import dev.fedorov.ailife.golden.GoldenLlmTest;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Stage 5 <b>golden test</b> (#199) — exercises the stylist {@code wardrobe-auditor} <b>JSON-synthesis
 * skill</b> against a <b>real model</b> (local Ollama {@code qwen2.5:7b} via a running llm-gateway),
 * asserting <b>structure, not text</b> (roadmap §Risks). Given a fixed catalogued wardrobe, the real
 * model must return a parseable {@code {"verdicts":[{name, verdict, reason}], …}} audit that the
 * production {@link WardrobeAuditor} turns into a verdict tally — i.e. a real KEEP/QUESTION/REMOVE
 * judgement per garment, not prose.
 *
 * <p><b>Opt-in / gated</b> via {@link GoldenLlmTest} ({@code GOLDEN_LLM}); see
 * {@code platform/llm-gateway/README.md} §Golden tests. {@link WardrobeReadClient} (wardrobe + profile)
 * and {@link DeliverablePublisher} (render→store→link) are mocked; the real {@link Coordinator} runs the
 * one synthesis hop over the real AGENT.md + wardrobe-auditor SKILL.md. The flow surfaces only its
 * summary tally (verdict counts) + the deliverable link, so we assert the audit was parsed into a
 * non-empty verdict set, never the wording.
 */
@GoldenLlmTest
class GoldenWardrobeAuditTest {

    private static final Pattern TALLY =
            Pattern.compile("оставить (\\d+), под вопросом (\\d+), убрать (\\d+)");

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final Coordinator coordinator = new Coordinator(GoldenLlm.client(), json);
    private final WardrobeReadClient wardrobe = mock(WardrobeReadClient.class);
    private final DeliverablePublisher publisher = mock(DeliverablePublisher.class);
    private final AgentManifest manifest = new AgentManifest(
            "stylist", "stylist agent", "0.1.0", 8102,
            List.of(), List.of(),
            List.<Map<String, String>>of(), List.<Map<String, String>>of(),
            GoldenLlm.agentBody(GoldenWardrobeAuditTest.class.getClassLoader()));
    private final SkillRegistry skills = new SkillRegistry(List.of(
            GoldenLlm.skill(GoldenWardrobeAuditTest.class.getClassLoader(), "skills/stylist/wardrobe-auditor/SKILL.md")));
    private final WardrobeAuditor auditor =
            new WardrobeAuditor(coordinator, wardrobe, publisher, skills, manifest, json);

    /**
     * STRUCTURE — the real model, given the real auditor prompt and a concrete wardrobe, must return a
     * parseable verdict audit: the production flow reaches its success path (a "оставить N, под вопросом
     * N, убрать N" tally + the stored link) with at least one garment judged. This is the "parseable
     * JSON output" assertion for a synthesis skill — it checks the verdict set was produced, never the
     * wording.
     */
    @Test
    void auditProducesAParseableVerdictSet() {
        UUID household = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        when(wardrobe.listItems(any(), any())).thenReturn(Mono.just(List.of(
                garment(household, user, "Чёрный шерстяной костюм", "suit", "black", "wool"),
                garment(household, user, "Белая хлопковая рубашка", "shirt", "white", "cotton"),
                garment(household, user, "Синие джинсы скинни", "jeans", "blue", "denim"),
                garment(household, user, "Неоновая спортивная толстовка", "hoodie", "neon", "polyester"))));
        // Profile is optional — an empty gather step is fine (Coordinator soft-fails it).
        when(wardrobe.getProfile(any(), any())).thenReturn(Mono.empty());
        when(publisher.publish(any(), any(), any()))
                .thenReturn(Mono.just("https://media-service:8088/v1/media/audit"));

        IntentResponse r = auditor.audit(GoldenLlm.message(household, user, "проведи ревизию гардероба"))
                .block(Duration.ofSeconds(150));

        assertThat(r).as("null result — is llm-gateway up at %s?", GoldenLlm.gatewayUrl()).isNotNull();
        // Success path = the model returned a parseable audit; the failure paths carry other text.
        assertThat(r.text())
                .as("audit did not reach the verdict tally (model likely returned non-parseable JSON):\n%s", r.text())
                .contains("Ревизия гардероба готова");
        Matcher m = TALLY.matcher(r.text());
        assertThat(m.find()).as("no verdict tally in:\n%s", r.text()).isTrue();
        int total = Integer.parseInt(m.group(1)) + Integer.parseInt(m.group(2)) + Integer.parseInt(m.group(3));
        assertThat(total)
                .as("audit judged zero garments (empty verdict set):\n%s", r.text())
                .isGreaterThanOrEqualTo(1);
        assertThat(r.text()).as("deliverable link missing").contains("https://media-service:8088/v1/media/audit");
    }

    private static WardrobeItemDto garment(UUID household, UUID user, String name,
                                           String category, String colour, String material) {
        return new WardrobeItemDto(UUID.randomUUID(), household, user, name, category, colour,
                material, "plain", "all-season", "smart", null, Instant.now());
    }
}
