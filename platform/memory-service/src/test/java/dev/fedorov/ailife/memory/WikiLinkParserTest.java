package dev.fedorov.ailife.memory;

import dev.fedorov.ailife.memory.note.WikiLinkParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SB-3 — {@code [[wiki-link]]} extraction. Pure/stateless, no Spring/Docker.
 */
class WikiLinkParserTest {

    @Test
    void extractsTargetsPreservingOrder() {
        assertThat(WikiLinkParser.parse("see [[Alpha]] then [[Beta]] and [[Gamma]]"))
                .containsExactly("Alpha", "Beta", "Gamma");
    }

    @Test
    void aliasKeepsOnlyTheTargetPart() {
        assertThat(WikiLinkParser.parse("ask [[Мама|мою маму]] about it"))
                .containsExactly("Мама");
    }

    @Test
    void deduplicatesCaseInsensitivelyKeepingFirstCasing() {
        assertThat(WikiLinkParser.parse("[[Мама]] и снова [[мама]] и [[МАМА]]"))
                .containsExactly("Мама");
    }

    @Test
    void ignoresBlankTargetsAndUnbracketedText() {
        assertThat(WikiLinkParser.parse("plain text, empty [[  ]], and [[ Real ]]"))
                .containsExactly("Real");
    }

    @Test
    void emptyOrNullBodyYieldsNoLinks() {
        assertThat(WikiLinkParser.parse(null)).isEmpty();
        assertThat(WikiLinkParser.parse("   ")).isEmpty();
        assertThat(WikiLinkParser.parse("no links here")).isEmpty();
    }
}
