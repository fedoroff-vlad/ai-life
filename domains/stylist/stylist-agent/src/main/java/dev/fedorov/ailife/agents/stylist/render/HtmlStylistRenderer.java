package dev.fedorov.ailife.agents.stylist.render;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Default {@link StylistRenderer}: a self-contained, **responsive** HTML page (mobile-first, inline
 * CSS so it renders anywhere with no external assets — the user opens the link on any device). The
 * MVP template; refined once the owner sends example layouts. A PDF renderer can later wrap this
 * (HTML→PDF) behind the same seam.
 */
@Component
public class HtmlStylistRenderer implements StylistRenderer {

    @Override
    public RenderedDoc render(StylistDoc doc) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("<!DOCTYPE html>\n<html lang=\"ru\">\n<head>\n")
          .append("<meta charset=\"utf-8\">\n")
          .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
          .append("<title>").append(esc(doc.title())).append("</title>\n")
          .append("<style>").append(CSS).append("</style>\n")
          .append("</head>\n<body>\n<main class=\"card\">\n")
          .append("<h1>").append(esc(doc.title())).append("</h1>\n");
        if (doc.subtitle() != null && !doc.subtitle().isBlank()) {
            sb.append("<p class=\"subtitle\">").append(esc(doc.subtitle())).append("</p>\n");
        }
        if (doc.sections() != null) {
            for (StylistDoc.Section section : doc.sections()) {
                if (section == null) continue;
                sb.append("<section>\n<h2>").append(esc(section.heading())).append("</h2>\n");
                if (section.paragraphs() != null) {
                    for (String p : section.paragraphs()) {
                        if (p == null || p.isBlank()) continue;
                        sb.append("<p>").append(esc(p)).append("</p>\n");
                    }
                }
                sb.append("</section>\n");
            }
        }
        sb.append("</main>\n</body>\n</html>\n");
        return new RenderedDoc(sb.toString().getBytes(StandardCharsets.UTF_8), "text/html", "analysis.html");
    }

    /** Minimal, dependency-free responsive styling — readable on a phone and a desktop alike. */
    private static final String CSS = """
            :root { color-scheme: light dark; }
            * { box-sizing: border-box; }
            body { margin: 0; padding: 1rem; font-family: -apple-system, BlinkMacSystemFont, \
            "Segoe UI", Roboto, Helvetica, Arial, sans-serif; line-height: 1.55; \
            background: #f4f4f5; color: #1f2933; }
            .card { max-width: 720px; margin: 0 auto; background: #fff; border-radius: 16px; \
            padding: clamp(1rem, 4vw, 2.5rem); box-shadow: 0 6px 24px rgba(0,0,0,.08); }
            h1 { font-size: clamp(1.5rem, 6vw, 2rem); margin: 0 0 .25rem; }
            .subtitle { color: #6b7280; margin: 0 0 1.5rem; }
            h2 { font-size: clamp(1.1rem, 4vw, 1.3rem); margin: 1.75rem 0 .5rem; \
            border-bottom: 2px solid #ececef; padding-bottom: .3rem; }
            p { margin: .5rem 0; }
            @media (prefers-color-scheme: dark) { \
            body { background: #18181b; color: #e4e4e7; } \
            .card { background: #232327; box-shadow: none; } \
            .subtitle { color: #a1a1aa; } \
            h2 { border-color: #3f3f46; } }
            """;

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
