package dev.fedorov.ailife.agents.stylist.render;

/**
 * The render-format seam (LOCKED with the owner 2026-06-21): a stylist deliverable is built as a
 * format-agnostic {@link StylistDoc}, then turned into bytes here. The MVP ships an HTML renderer;
 * a future HTML→PDF integration drops in as another implementation without touching the flows.
 * "Lift to a shared {@code doc-render} capability-MCP once a second consumer needs it" (stylist.md).
 */
public interface StylistRenderer {

    RenderedDoc render(StylistDoc doc);
}
