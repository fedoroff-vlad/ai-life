package dev.fedorov.ailife.agents.stylist.render;

import java.util.List;

/**
 * The render-format-agnostic model of a stylist deliverable (an analysis page now, a capsule page
 * in ST-e). A {@link StylistRenderer} turns this into concrete bytes (HTML today, PDF later via the
 * same seam). Sections are ordered; each is a titled block of paragraphs. Kept deliberately small —
 * the flow fills it from the LLM synthesis + the style profile, and the renderer owns all styling.
 */
public record StylistDoc(String title, String subtitle, List<Section> sections) {

    public record Section(String heading, List<String> paragraphs) {
    }
}
