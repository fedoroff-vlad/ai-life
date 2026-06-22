package dev.fedorov.ailife.docrender;

/**
 * The render-format seam (LOCKED with the owner 2026-06-21): a deliverable is built as a
 * format-agnostic {@link Doc}, then turned into bytes here. The MVP ships {@link HtmlDocRenderer};
 * a future HTML→PDF integration drops in as another implementation without touching the callers.
 *
 * <p>Lifted from stylist-agent into this shared lib on the second consumer (nutrition/chef) per the
 * "lift on the second copy" rule. Each consumer agent exposes a {@code DocRenderer} bean built from
 * its own {@link DocTheme}.
 */
public interface DocRenderer {

    RenderedDoc render(Doc doc);
}
