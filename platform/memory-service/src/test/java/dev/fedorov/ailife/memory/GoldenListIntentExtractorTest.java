package dev.fedorov.ailife.memory;

import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.golden.GoldenLlm;
import dev.fedorov.ailife.golden.GoldenLlmTest;
import dev.fedorov.ailife.memory.capture.ListIntentExtractor;
import dev.fedorov.ailife.memory.capture.ListItemCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden test — exercises {@link ListIntentExtractor} against a <b>real model</b> (local Ollama
 * {@code qwen3:8b} via a running llm-gateway), asserting <b>structure, not wording</b>: an ordinary
 * buy-intent message yields a parseable item; a running-low mention yields the item; and small-talk /
 * a past purchase yield nothing. Mirrors {@code GoldenNoteWorthinessTest}.
 *
 * <p><b>Opt-in / gated</b> via {@link GoldenLlmTest} ({@code GOLDEN_LLM}).
 */
@GoldenLlmTest
class GoldenListIntentExtractorTest {

    private final ListIntentExtractor extractor =
            new ListIntentExtractor(GoldenLlm.client(), new ObjectMapper());

    @Test
    void detectsABuyIntent() {
        List<ListItemCandidate> out = extractor.extract("надо купить молоко");

        assertThat(out).as("real model produced no item — is llm-gateway up at %s?",
                GoldenLlm.gatewayUrl()).isNotEmpty();
        assertThat(out.get(0).item()).isNotBlank();
    }

    @Test
    void detectsARunningLowMention() {
        List<ListItemCandidate> out = extractor.extract("дома заканчивается кофе");

        assertThat(out).as("running-low should imply a buy item (got %s)", out).isNotEmpty();
        assertThat(out.get(0).item()).isNotBlank();
    }

    @Test
    void ignoresSmallTalk() {
        List<ListItemCandidate> out = extractor.extract("привет! как дела? какая сегодня погода?");
        assertThat(out).as("small-talk should yield no list item (got %s)", out).isEmpty();
    }

    @Test
    void ignoresAPastPurchase() {
        // "купил" = already bought — a check-off intent at most, never an add. Must not create an item.
        List<ListItemCandidate> out = extractor.extract("сегодня купил молоко и хлеб");
        assertThat(out).as("a past purchase should not add items (got %s)", out).isEmpty();
    }
}
