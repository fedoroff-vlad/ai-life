# doc-render

Shared **deliverable renderer**: a format-agnostic `Doc` model + a luxury-editorial **HTML** renderer
behind a `DocRenderer` seam (PDF later via the same seam). **Pure Java, no Spring** — each consumer
agent supplies its own theme and wires a `DocRenderer` bean.

Lifted from `stylist-agent` in DR-a once a **second consumer** appeared (nutrition / chef HTML
deliverables) — the repo's "lift on the second copy" rule. Plan: [plans/nutrition.md](../../plans/nutrition.md)
§doc-render lift.

## Why a lib (not a capability-MCP)
Rendering HTML from a model is a **pure function** (no external resource, no schema) → a compile-time
lib adds **no new container and no HTTP hop** (leanest). It upgrades to an `mcp-doc-render`
capability-MCP only when a renderer needs process isolation (the deferred **PDF** path —
wkhtmltopdf/Chromium). The `render(Doc) → RenderedDoc` seam is identical, so lib→MCP is internal.

## Key classes
- `Doc` — the render-format-agnostic board: header (kicker/title/subtitle + optional `featuredImageUrl`)
  + optional keyed `sections`, colour `palette` swatches, a `verdicts` grid (status tile per item), a
  `hero` row, and an image `gallery`. Fluent `builder` + back-compat constructors. The flow fills only
  what its board needs; the renderer skips empties.
- `DocRenderer` (seam) — `RenderedDoc render(Doc)`.
- `HtmlDocRenderer` — the default: a responsive **luxury-editorial poster** (warm-beige ground, serif
  display caps, hairline dividers, centered photo anchor, gold hero, KEEP/QUESTION/REMOVE verdict
  tags, palette swatches; light-only; Google Fonts). Plain class — `new HtmlDocRenderer(docTheme)`.
  Builds the `:root` CSS variables + the fonts `<link>` from the theme; the layout rules are constant.
- `DocTheme` — POJO of the palette + font stacks + Google-Fonts `css2?` query. No-arg defaults = the
  aesthetic LOCKED with the owner 2026-06-21 (Oranienbaum + Manrope). A consumer maps its own
  `@ConfigurationProperties` into one (e.g. stylist's `STYLIST_THEME_*` → `toDocTheme()`).
- `RenderedDoc` — the rendered `byte[]` + MIME type + suggested filename.

## Consuming it
```java
@Bean DocRenderer docRenderer(MyThemeProperties t) { return new HtmlDocRenderer(t.toDocTheme()); }
```
Build a `Doc` via the builder and `render(doc)`; store the `RenderedDoc` bytes (e.g. media-service)
and hand the user a link.

## Field generalisation
The model is the stylist board lifted intact (verdict tones reused for nutrition "good/watch/cut").
A generic tile tone and a recipe-link list land with the first nutrition consumer that needs them.
