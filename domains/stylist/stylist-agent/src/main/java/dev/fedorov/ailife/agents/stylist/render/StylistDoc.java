package dev.fedorov.ailife.agents.stylist.render;

import java.util.List;

/**
 * The render-format-agnostic model of a stylist deliverable (the analysis page, ST-d; the capsule
 * page, ST-e). A {@link StylistRenderer} turns this into concrete bytes (HTML today, PDF later via
 * the same seam). Sections are ordered titled blocks of paragraphs; {@code imageUrls} is an optional
 * gallery (the capsule embeds the garment photos). Kept deliberately small — the flow fills it from
 * the LLM synthesis + the gathered data, and the renderer owns all styling.
 */
public record StylistDoc(String title, String subtitle, List<Section> sections, List<String> imageUrls) {

    /** Convenience for a text-only deliverable (no image gallery) — e.g. the ST-d analysis. */
    public StylistDoc(String title, String subtitle, List<Section> sections) {
        this(title, subtitle, sections, null);
    }

    public record Section(String heading, List<String> paragraphs) {
    }
}
