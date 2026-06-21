package dev.fedorov.ailife.agents.stylist;

import dev.fedorov.ailife.agents.stylist.render.HtmlStylistRenderer;
import dev.fedorov.ailife.agents.stylist.render.RenderedDoc;
import dev.fedorov.ailife.agents.stylist.render.StylistDoc;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit test for the editorial renderer (ST-f) — no Spring. Verifies the locked aesthetic
 * markers and that each optional board block (sections, palette, verdict grid, hero, gallery)
 * renders only when present, plus HTML escaping.
 */
class HtmlStylistRendererTest {

    private final HtmlStylistRenderer renderer = new HtmlStylistRenderer();

    @Test
    void rendersAllBlocksAndEditorialChrome() {
        StylistDoc doc = StylistDoc.builder("Wardrobe Audit Board")
                .kicker("Edited · Strategic · Aligned")
                .subtitle("Natural archetype")
                .section("Diagnosis", List.of("Strong natural foundation."))
                .swatch("#042C53", "deep blue")
                .verdict("Slip dress", StylistDoc.Verdict.QUESTION, "Straps don't support the line",
                        "https://files.example.test/v1/media/abc")
                .verdict("Logo tee", StylistDoc.Verdict.REMOVE, "Duplicates & lowers polish", null)
                .heroPiece("Wide-leg trousers", null, "Signature silhouette")
                .galleryImage("https://files.example.test/v1/media/xyz")
                .build();

        RenderedDoc out = renderer.render(doc);
        assertThat(out.mimeType()).isEqualTo("text/html");
        String html = new String(out.content(), StandardCharsets.UTF_8);

        assertThat(html).startsWith("<!DOCTYPE html>");
        assertThat(html).contains("Oranienbaum");                  // serif display loaded (Cyrillic)
        assertThat(html).contains("color-scheme: light");          // noble beige, no dark flip
        assertThat(html).contains("Wardrobe Audit Board")
                .contains("Edited · Strategic · Aligned")
                .contains("Natural archetype");
        assertThat(html).contains("Strong natural foundation.");
        assertThat(html).contains("#042C53");                      // palette swatch
        assertThat(html).contains("verdict question").contains("verdict remove"); // verdict tags
        assertThat(html).contains("Hero pieces").contains("Wide-leg trousers");
        assertThat(html).contains("/v1/media/abc").contains("/v1/media/xyz"); // photos embedded
    }

    @Test
    void textOnlyBoardSkipsEmptyBlocks() {
        StylistDoc doc = new StylistDoc("Анализ стиля", "Тип: классический",
                List.of(new StylistDoc.Section("Цветотип", List.of("Зима."))));

        String html = new String(renderer.render(doc).content(), StandardCharsets.UTF_8);
        assertThat(html).contains("Анализ стиля").contains("Зима.");
        // No verdict grid / hero / palette / gallery markers when those blocks are absent.
        assertThat(html).doesNotContain("class=\"verdict")
                .doesNotContain("Hero pieces")
                .doesNotContain("class=\"pal\"")
                .doesNotContain("class=\"gallery\"");
    }

    @Test
    void escapesHtmlInContent() {
        StylistDoc doc = new StylistDoc("T", null,
                List.of(new StylistDoc.Section("H", List.of("a <script> & \"x\""))));
        String html = new String(renderer.render(doc).content(), StandardCharsets.UTF_8);
        assertThat(html).contains("a &lt;script&gt; &amp; &quot;x&quot;");
        assertThat(html).doesNotContain("<script>");
    }
}
