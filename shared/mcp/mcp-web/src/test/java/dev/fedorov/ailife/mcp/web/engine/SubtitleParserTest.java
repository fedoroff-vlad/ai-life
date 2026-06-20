package dev.fedorov.ailife.mcp.web.engine;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-tests the WebVTT → plain-text extraction in isolation (no yt-dlp, no network) — the
 * deterministic core of the transcript engine.
 */
class SubtitleParserTest {

    @Test
    void stripsHeaderTimingsCueIdsInlineTagsAndCollapsesRepeats() {
        String vtt = """
                WEBVTT
                Kind: captions
                Language: en

                1
                00:00:01.000 --> 00:00:04.000
                Heat the bed to sixty degrees

                2
                00:00:04.000 --> 00:00:08.000
                <00:00:04.500><c>Heat the bed to sixty degrees</c>

                3
                00:00:08.000 --> 00:00:11.000
                then run the paper test
                """;

        String text = SubtitleParser.parseVtt(vtt);

        assertThat(text).isEqualTo("Heat the bed to sixty degrees then run the paper test");
        assertThat(text).doesNotContain("-->").doesNotContain("WEBVTT").doesNotContain("<c>");
    }

    @Test
    void emptyOrBlankInputYieldsEmptyString() {
        assertThat(SubtitleParser.parseVtt(null)).isEmpty();
        assertThat(SubtitleParser.parseVtt("   ")).isEmpty();
        assertThat(SubtitleParser.parseVtt("WEBVTT\n\n")).isEmpty();
    }
}
