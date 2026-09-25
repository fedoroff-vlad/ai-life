package dev.fedorov.ailife.agents.inventory.intent;

import dev.fedorov.ailife.agentruntime.intent.SkillClassifier;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.inventory.chat.InventoryChat;
import dev.fedorov.ailife.agents.inventory.edit.ContainerEditor;
import dev.fedorov.ailife.agents.inventory.edit.ItemRemover;
import dev.fedorov.ailife.agents.inventory.find.ItemFinder;
import dev.fedorov.ailife.agents.inventory.label.BoxLabeler;
import dev.fedorov.ailife.agents.inventory.pack.BoxPacker;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.golden.GoldenLlm;
import dev.fedorov.ailife.golden.GoldenLlmTest;
import dev.fedorov.ailife.llm.LlmClient;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Routing golden for the inventory agent — the <b>first</b> proof that a real model, not a
 * MockWebServer, picks the right flow in this domain. Sibling of {@code GoldenDocsRoutingTest} /
 * {@code GoldenNotesRoutingTest}; asserts <b>structure, not text</b>.
 *
 * <p>This domain has the widest trigger-less skill set in the repo (<b>six</b>), and two of them are
 * deliberately close: "что в коробке B-07" is {@code box-card} while "где лежит дрель" is
 * {@code item-finder} — a box the user can name versus a thing they cannot place. That pair is the
 * defect this test exists to catch: if the two SKILL descriptions blur, the owner asks where something
 * is and gets a card for a box they already knew about, and no unit test can see it.
 *
 * <p>Photos never reach the router (an open session route-locks them to {@code /resume}, and outside
 * one they are a deterministic pre-check), so every case here is text.
 *
 * <p>Opt-in / gated via {@link GoldenLlmTest} ({@code GOLDEN_LLM}); run with
 * {@code scripts/golden.sh -pl domains/inventory/inventory-agent -Dtest=GoldenInventoryRoutingTest}
 * (~1–2 min on a CPU-only box).
 */
@GoldenLlmTest
class GoldenInventoryRoutingTest {

    /** The actions the inventory classifier prompt allows (no MCP tools bound here → skill / chat). */
    private static final Set<String> ACTIONS = Set.of("skill", "chat");
    private static final Set<String> SKILLS =
            Set.of("box-packer", "item-finder", "box-label", "box-card", "box-editor", "item-remover");

    private final ObjectMapper json = new ObjectMapper();
    private final LlmClient llm = GoldenLlm.client();
    private final BoxPacker packer = mock(BoxPacker.class);
    private final ItemFinder finder = mock(ItemFinder.class);
    private final BoxLabeler labeler = mock(BoxLabeler.class);
    private final ContainerEditor editor = mock(ContainerEditor.class);
    private final ItemRemover remover = mock(ItemRemover.class);
    private final InventoryChat chat = mock(InventoryChat.class);
    private final AgentManifest manifest = new AgentManifest(
            "inventory", "inventory agent", "0.1.0", 8128, List.of(), List.of(),
            List.<Map<String, String>>of(), List.<Map<String, String>>of(),
            GoldenLlm.agentBody(GoldenInventoryRoutingTest.class.getClassLoader()));
    private final SkillRegistry skills = new SkillRegistry(List.of(
            skill("skills/inventory/box-packer/SKILL.md"),
            skill("skills/inventory/item-finder/SKILL.md"),
            skill("skills/inventory/box-label/SKILL.md"),
            skill("skills/inventory/box-card/SKILL.md"),
            skill("skills/inventory/box-editor/SKILL.md"),
            skill("skills/inventory/item-remover/SKILL.md")));
    private final InventoryIntentRouter router = new InventoryIntentRouter(
            llm, skills, new SkillClassifier(json), manifest, packer, finder, labeler, editor, remover, chat);

    /**
     * STRUCTURE — the real model, given the real router prompt, must return well-formed routing JSON: an
     * object with an {@code action} in the contract set and, when {@code action=skill}, one of the six
     * real skill names (never a hallucinated seventh).
     */
    @Test
    void classifierEmitsWellFormedRoutingJson() {
        String prompt = router.buildClassifierPrompt();
        for (String msg : List.of(
                "открой коробку «кухня — посуда» в кладовку",
                "где лежат ёлочные игрушки",
                "распечатай этикетку на B-07",
                "что в коробке B-07",
                "коробка B-07 теперь на даче",
                "убери из коробки старый чайник",
                "спасибо!")) {
            String raw = chat(prompt, msg);
            JsonNode node = extractJson(raw);
            if (node == null) {
                fail("Not parseable JSON for «%s» — raw model output was:\n%s".formatted(msg, raw));
            }
            assertThat(node.hasNonNull("action")).as("missing 'action' for «%s»: %s", msg, raw).isTrue();
            String action = node.get("action").asString();
            assertThat(ACTIONS).as("action '%s' not in the contract for «%s»: %s", action, msg, raw)
                    .contains(action);
            if ("skill".equals(action)) {
                assertThat(SKILLS).as("hallucinated skill '%s' for «%s»", node.path("name").asString(), msg)
                        .contains(node.path("name").asString());
            }
        }
    }

    /**
     * BEHAVIOUR — crisp requests must reach the right flow end-to-end through
     * {@link InventoryIntentRouter#route}. Every flow is stubbed with a sentinel, so the assertion is
     * purely "which flow did the router pick" — identity, never wording.
     */
    @Test
    void routesUnambiguousRequestsToTheRightFlow() {
        stubAll();
        // Warm-up (not asserted): the classifier system prompt carries six SKILL descriptions, so its
        // FIRST prefill on a CPU-only box can exceed the per-call block below. Ollama caches the prefix.
        router.route(GoldenLlm.message(UUID.randomUUID(), UUID.randomUUID(), "привет"))
                .block(Duration.ofSeconds(240));

        assertRoutesTo("открой коробку «кухня — посуда» в кладовку", "packer");
        assertRoutesTo("где лежат ёлочные игрушки", "finder");
        assertRoutesTo("распечатай этикетку на коробку B-07", "label");
        // The close pair: a box the user can name → its card, not a search for a thing.
        assertRoutesTo("что лежит в коробке B-07", "card");
        // A correction to the box itself, not a question about its contents.
        assertRoutesTo("коробка B-07 теперь стоит на даче", "editor");
        // Removing a THING is not editing the box, and not searching for it either.
        assertRoutesTo("убери из коробки старый чайник", "remover");
        assertRoutesTo("спасибо, очень помог", "chat");
    }

    private void assertRoutesTo(String text, String expectedFlow) {
        IntentResponse resp = router.route(GoldenLlm.message(UUID.randomUUID(), UUID.randomUUID(), text))
                .block(Duration.ofSeconds(120));
        assertThat(resp).as("null result for «%s» — is llm-gateway up at %s?", text, GoldenLlm.gatewayUrl())
                .isNotNull();
        assertThat(resp.text())
                .as("«%s» should route to the '%s' flow but got '%s'", text, expectedFlow, resp.text())
                .isEqualTo(expectedFlow);
    }

    /** One real round-trip with the exact router prompt shape (structure test). */
    private String chat(String classifierPrompt, String userText) {
        LlmChatRequest req = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.system(manifest.body()),
                LlmMessage.system(classifierPrompt),
                LlmMessage.user(userText)));
        LlmChatResponse resp = llm.chat(req).block(Duration.ofSeconds(180));
        assertThat(resp).as("no LLM response for «%s» — is llm-gateway up at %s?",
                userText, GoldenLlm.gatewayUrl()).isNotNull();
        return resp.content() == null ? "" : resp.content();
    }

    /** Lenient extraction: tolerate ```json fences / leading prose or &lt;think&gt; blocks. */
    private JsonNode extractJson(String raw) {
        if (raw == null) {
            return null;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            JsonNode n = json.readTree(raw.substring(start, end + 1));
            return n.isObject() ? n : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Stub every flow with a distinct sentinel so the reply names the flow the router picked. */
    private void stubAll() {
        when(packer.start(any())).thenReturn(Mono.just(sentinel("packer")));
        when(finder.find(any())).thenReturn(Mono.just(sentinel("finder")));
        when(labeler.label(any())).thenReturn(Mono.just(sentinel("label")));
        when(labeler.card(any())).thenReturn(Mono.just(sentinel("card")));
        when(editor.edit(any())).thenReturn(Mono.just(sentinel("editor")));
        when(remover.remove(any())).thenReturn(Mono.just(sentinel("remover")));
        when(chat.reply(any())).thenReturn(Mono.just(sentinel("chat")));
    }

    private static IntentResponse sentinel(String tag) {
        return new IntentResponse("inventory", tag, null);
    }

    private static Skill skill(String path) {
        return GoldenLlm.skill(GoldenInventoryRoutingTest.class.getClassLoader(), path);
    }
}
