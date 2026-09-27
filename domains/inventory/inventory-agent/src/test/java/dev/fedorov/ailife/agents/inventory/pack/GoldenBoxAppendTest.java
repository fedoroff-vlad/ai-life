package dev.fedorov.ailife.agents.inventory.pack;

import dev.fedorov.ailife.agentruntime.http.CaptionClient;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.inventory.container.ContainerResolver;
import dev.fedorov.ailife.agents.inventory.find.ItemNotes;
import dev.fedorov.ailife.agents.inventory.http.InventoryClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.inventory.ContainerDto;
import dev.fedorov.ailife.contracts.inventory.ItemDto;
import dev.fedorov.ailife.contracts.inventory.SaveItemInput;
import dev.fedorov.ailife.golden.GoldenLlm;
import dev.fedorov.ailife.golden.GoldenLlmTest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Golden test for the {@code box-append} <b>caption split</b> against a real model (IN-g3): one sentence
 * carrying both <i>which box</i> and <i>what the thing is</i>.
 *
 * <p>Two defects it exists to catch, both invisible to a mocked-LLM test:
 * <ul>
 *   <li><b>Leakage into the title.</b> A model that answers {@code {"title":"добавь в B-07 гирлянду"}}
 *   stores the command as the thing's name, and "где лежит гирлянда" then matches a row whose title is a
 *   sentence. The sibling {@code box-editor} golden already caught exactly this class of defect (the
 *   few-shot index leaking into the answer), which is why the assertion here is on the <b>stored</b>
 *   title, not on the reply.</li>
 *   <li><b>An invented container.</b> A caption that names no box must produce <i>no</i> container, so the
 *   agent asks. A model that helpfully fills in a plausible code files the thing into a box nobody named —
 *   the one failure this whole domain exists to prevent, because it surfaces months later by accident.</li>
 * </ul>
 *
 * <p>Two containers are in the store so a split that answers with the whole sentence cannot resolve by
 * luck, and the store is asserted by <b>identity</b> (which container id was written), never by wording.
 *
 * <p>Opt-in / gated via {@link GoldenLlmTest}; run with
 * {@code scripts/golden.sh -pl domains/inventory/inventory-agent -Dtest=GoldenBoxAppendTest}.
 */
@GoldenLlmTest
class GoldenBoxAppendTest {

    private static final UUID B07 = UUID.randomUUID();
    private static final UUID B12 = UUID.randomUUID();
    private static final UUID HOUSEHOLD = UUID.randomUUID();
    private static final String MEDIA = "media-1";

    private final ObjectMapper json = new ObjectMapper();
    private final InventoryClient inventory = mock(InventoryClient.class);
    private final CaptionClient caption = mock(CaptionClient.class);
    private final AgentManifest manifest = new AgentManifest(
            "inventory", "inventory agent", "0.1.0", 8128, List.of(), List.of(),
            List.<Map<String, String>>of(), List.<Map<String, String>>of(),
            GoldenLlm.agentBody(GoldenBoxAppendTest.class.getClassLoader()));
    private final SkillRegistry skills = new SkillRegistry(List.of(
            GoldenLlm.skill(GoldenBoxAppendTest.class.getClassLoader(),
                    "skills/inventory/box-append/SKILL.md")));
    /** The IN-e2 note seed is stubbed silent: what is under test here is the caption split. */
    private final ItemNotes notes = mock(ItemNotes.class);
    private final BoxAppender appender = new BoxAppender(
            GoldenLlm.client(), skills, inventory, new ContainerResolver(inventory), caption, notes,
            manifest, json);

    {
        when(notes.seed(any(), any(), any(), any())).thenReturn(Mono.empty());
    }

    /** The caption's two halves land in the right places: the named box, and the thing without the command. */
    @Test
    void theCaptionSplitsIntoTheNamedBoxAndTheThingAlone() {
        stubStore();
        when(inventory.saveItem(any())).thenAnswer(call -> {
            SaveItemInput in = call.getArgument(0);
            return Mono.just(new ItemDto(UUID.randomUUID(), in.containerId(), in.mediaId(), in.title(),
                    null, null, 1, Instant.now()));
        });

        IntentResponse resp = appender.append(GoldenLlm.message(UUID.randomUUID(), HOUSEHOLD,
                "добавь в B-12 ёлочную гирлянду"), MEDIA).block(Duration.ofSeconds(180));

        assertThat(resp).as("null result — is llm-gateway up at %s?", GoldenLlm.gatewayUrl()).isNotNull();
        ArgumentCaptor<SaveItemInput> saved = ArgumentCaptor.forClass(SaveItemInput.class);
        verify(inventory).saveItem(saved.capture());
        // Identity: the thing went into the box the caption named, not the other one in the store.
        assertThat(saved.getValue().containerId()).as("filed into the wrong box (reply: %s)", resp.text())
                .isEqualTo(B12);
        // The title is the thing alone — not the command, not the box, not the whole caption.
        assertThat(saved.getValue().title()).as("the command leaked into the stored title")
                .isNotNull()
                .containsIgnoringCase("гирлянд")
                .doesNotContainIgnoringCase("добавь")
                .doesNotContainIgnoringCase("B-12");
        // The owner named the thing, so the vision model is never asked.
        verify(caption, never()).caption(any(), any());
    }

    /** No box in the caption → no box invented: nothing is written and the agent asks which one. */
    @Test
    void aCaptionNamingNoBoxFilesNothing() {
        stubStore();

        IntentResponse resp = appender.append(GoldenLlm.message(UUID.randomUUID(), HOUSEHOLD,
                "зарядка от ноутбука"), MEDIA).block(Duration.ofSeconds(180));

        assertThat(resp).as("null result — is llm-gateway up at %s?", GoldenLlm.gatewayUrl()).isNotNull();
        verify(inventory, never()).saveItem(any());
        assertThat(resp.text()).as("did not ask which box (reply: %s)", resp.text())
                .containsIgnoringCase("коробк");
    }

    /** Two candidates, so a split that answers with the whole sentence cannot match by luck. */
    private void stubStore() {
        when(inventory.listContainers(any(), any())).thenReturn(Mono.just(List.of(
                container(B07, "B-07", "кухня — посуда"),
                container(B12, "B-12", "новогодние украшения"))));
    }

    private static ContainerDto container(UUID id, String code, String label) {
        return new ContainerDto(id, HOUSEHOLD, null, UUID.randomUUID(), code, label, "box",
                "demo-" + code.toLowerCase(), "packed", null, null, Instant.now(), null);
    }
}
