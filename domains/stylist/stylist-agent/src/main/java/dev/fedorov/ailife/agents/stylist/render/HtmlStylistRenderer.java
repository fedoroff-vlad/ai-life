package dev.fedorov.ailife.agents.stylist.render;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Default {@link StylistRenderer}: a self-contained, responsive <b>luxury-editorial</b> HTML board
 * (the aesthetic LOCKED with the owner 2026-06-21 — ivory ground, serif display caps, grid, gold
 * hero accent, KEEP/QUESTION/REMOVE verdict tags, palette swatches). Mobile-first and dark-mode
 * aware; the only external dependency is Google Fonts (loads fine when the page is served from
 * media-service to a browser). A PDF renderer can later wrap this behind the same seam.
 */
@Component
public class HtmlStylistRenderer implements StylistRenderer {

    @Override
    public RenderedDoc render(StylistDoc doc) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("<!DOCTYPE html>\n<html lang=\"ru\">\n<head>\n")
          .append("<meta charset=\"utf-8\">\n")
          .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
          .append("<title>").append(esc(doc.title())).append("</title>\n")
          .append("<link rel=\"preconnect\" href=\"https://fonts.googleapis.com\">\n")
          .append("<link rel=\"preconnect\" href=\"https://fonts.gstatic.com\" crossorigin>\n")
          .append("<link href=\"https://fonts.googleapis.com/css2?")
          .append("family=Cormorant+Garamond:wght@500;600&family=Jost:wght@300;400;500&display=swap\" ")
          .append("rel=\"stylesheet\">\n")
          .append("<style>").append(CSS).append("</style>\n")
          .append("</head>\n<body>\n<div class=\"board\">\n");

        // Header
        sb.append("<header>\n<h1>").append(esc(doc.title())).append("</h1>\n");
        if (notBlank(doc.kicker())) {
            sb.append("<p class=\"kicker\">").append(esc(doc.kicker())).append("</p>\n");
        }
        if (notBlank(doc.subtitle())) {
            sb.append("<p class=\"sub\">").append(esc(doc.subtitle())).append("</p>\n");
        }
        sb.append("</header>\n");

        // Text sections
        if (doc.sections() != null) {
            for (StylistDoc.Section section : doc.sections()) {
                if (section == null) continue;
                sb.append("<section>\n<h2>").append(esc(section.heading())).append("</h2>\n");
                if (section.paragraphs() != null) {
                    for (String p : section.paragraphs()) {
                        if (notBlank(p)) sb.append("<p>").append(esc(p)).append("</p>\n");
                    }
                }
                sb.append("</section>\n");
            }
        }

        // Verdict grid (audit)
        if (doc.verdicts() != null && !doc.verdicts().isEmpty()) {
            sb.append("<div class=\"grid\">\n");
            for (StylistDoc.VerdictItem v : doc.verdicts()) {
                if (v == null) continue;
                String cls = v.verdict() == null ? "keep" : v.verdict().name().toLowerCase(Locale.ROOT);
                sb.append("<div class=\"cell\">\n");
                if (notBlank(v.name())) {
                    sb.append("<p class=\"cap\">").append(esc(v.name())).append("</p>\n");
                }
                if (notBlank(v.imageUrl())) {
                    sb.append("<img class=\"photo\" loading=\"lazy\" src=\"")
                      .append(esc(v.imageUrl())).append("\" alt=\"\">\n");
                }
                sb.append("<div class=\"verdict ").append(cls).append("\"><span class=\"dot\"></span>")
                  .append(verdictLabel(v.verdict())).append("</div>\n");
                if (notBlank(v.reason())) {
                    sb.append("<p class=\"reason\">").append(esc(v.reason())).append("</p>\n");
                }
                sb.append("</div>\n");
            }
            sb.append("</div>\n");
        }

        // Hero row
        if (doc.hero() != null && !doc.hero().isEmpty()) {
            sb.append("<div class=\"hero\">\n<h3>✦ Hero pieces ✦</h3>\n<div class=\"row\">\n");
            for (StylistDoc.HeroItem h : doc.hero()) {
                if (h == null) continue;
                sb.append("<div class=\"item\">\n");
                if (notBlank(h.imageUrl())) {
                    sb.append("<img class=\"photo\" loading=\"lazy\" src=\"")
                      .append(esc(h.imageUrl())).append("\" alt=\"\">\n");
                }
                if (notBlank(h.name())) {
                    sb.append("<div class=\"n\">").append(esc(h.name())).append("</div>\n");
                }
                if (notBlank(h.note())) {
                    sb.append("<div class=\"hn\">").append(esc(h.note())).append("</div>\n");
                }
                sb.append("</div>\n");
            }
            sb.append("</div>\n</div>\n");
        }

        // Palette
        if (doc.palette() != null && !doc.palette().isEmpty()) {
            sb.append("<section>\n<h2>Палитра</h2>\n<div class=\"pal\">\n");
            for (StylistDoc.Swatch s : doc.palette()) {
                if (s == null || !notBlank(s.hex())) continue;
                sb.append("<i style=\"background:").append(esc(s.hex())).append("\" title=\"")
                  .append(esc(s.label() == null ? "" : s.label())).append("\"></i>\n");
            }
            sb.append("</div>\n</section>\n");
        }

        // Gallery
        if (doc.gallery() != null && !doc.gallery().isEmpty()) {
            sb.append("<section>\n<h2>Из вашего гардероба</h2>\n<div class=\"gallery\">\n");
            for (String url : doc.gallery()) {
                if (notBlank(url)) {
                    sb.append("<img class=\"g\" loading=\"lazy\" src=\"").append(esc(url)).append("\" alt=\"\">\n");
                }
            }
            sb.append("</div>\n</section>\n");
        }

        sb.append("<footer>ai-life · stylist</footer>\n</div>\n</body>\n</html>\n");
        return new RenderedDoc(sb.toString().getBytes(StandardCharsets.UTF_8), "text/html", "stylist.html");
    }

    private static String verdictLabel(StylistDoc.Verdict v) {
        if (v == null) return "Keep";
        return switch (v) {
            case KEEP -> "Keep";
            case QUESTION -> "Question";
            case REMOVE -> "Remove";
        };
    }

    /** The locked luxury-editorial styling — ivory ground, serif caps, grid, gold hero, dark-mode aware. */
    private static final String CSS = """
            :root{ --paper:#f4efe6; --panel:#fbf8f2; --line:#e2dac9; --ink:#2a2722; --muted:#8c8475; \
            --keep:#5a6b4f; --question:#b8893b; --remove:#9b4a3a; --gold:#b08d4f; \
            --serif:"Cormorant Garamond",Georgia,serif; --sans:"Jost",-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif; }
            @media (prefers-color-scheme: dark){ :root{ --paper:#201d18; --panel:#262219; --line:#3a3326; --ink:#ece5d6; --muted:#a59a85; } }
            *{ box-sizing:border-box; }
            body{ margin:0; padding:1.25rem; background:var(--paper); color:var(--ink); font-family:var(--sans); font-weight:300; line-height:1.55; }
            .board{ max-width:880px; margin:0 auto; }
            header{ text-align:center; padding:1rem 0 .75rem; border-bottom:1px solid var(--line); margin-bottom:.5rem; }
            h1{ font-family:var(--serif); font-weight:600; letter-spacing:.16em; text-transform:uppercase; font-size:clamp(1.8rem,6vw,2.8rem); margin:0; }
            .kicker{ font-size:.7rem; letter-spacing:.4em; color:var(--muted); text-transform:uppercase; margin:.5rem 0 0; }
            .sub{ color:var(--muted); margin:.5rem 0 0; }
            section{ margin:1.5rem 0; }
            h2{ font-family:var(--serif); text-transform:uppercase; letter-spacing:.22em; font-size:1rem; color:var(--muted); margin:0 0 .6rem; }
            p{ margin:.4rem 0; }
            .grid{ display:grid; grid-template-columns:repeat(auto-fill,minmax(150px,1fr)); gap:1px; background:var(--line); border:1px solid var(--line); margin:1rem 0; }
            .cell{ background:var(--panel); padding:.85rem; }
            .cap{ font-size:.62rem; letter-spacing:.16em; text-transform:uppercase; color:var(--muted); margin:0 0 .5rem; }
            .photo{ width:100%; aspect-ratio:3/4; object-fit:cover; border-radius:6px; margin-bottom:.55rem; background:#ddd6c8; }
            .verdict{ display:flex; align-items:center; gap:.4rem; font-size:.72rem; letter-spacing:.12em; text-transform:uppercase; font-weight:500; }
            .dot{ width:9px; height:9px; border-radius:50%; display:inline-block; }
            .keep{ color:var(--keep);} .keep .dot{ background:var(--keep);} \
            .question{ color:var(--question);} .question .dot{ background:var(--question);} \
            .remove{ color:var(--remove);} .remove .dot{ background:var(--remove);}
            .reason{ font-size:.74rem; color:var(--muted); margin:.4rem 0 0; line-height:1.45; }
            .hero{ border:1px solid var(--line); background:var(--panel); margin:1.25rem 0; padding:1rem; }
            .hero h3{ font-family:var(--serif); text-transform:uppercase; letter-spacing:.22em; font-size:.85rem; color:var(--gold); text-align:center; margin:0 0 .9rem; }
            .hero .row{ display:grid; grid-template-columns:repeat(auto-fit,minmax(110px,1fr)); gap:.9rem; }
            .hero .item{ text-align:center; }
            .hero .item .photo{ aspect-ratio:1/1; }
            .hero .n{ font-size:.66rem; letter-spacing:.1em; text-transform:uppercase; margin-top:.4rem; }
            .hero .hn{ font-size:.7rem; color:var(--muted); margin-top:.2rem; }
            .pal{ display:flex; gap:.5rem; flex-wrap:wrap; }
            .pal i{ width:42px; height:42px; border-radius:50%; display:block; border:1px solid var(--line); }
            .gallery{ display:grid; grid-template-columns:repeat(auto-fill,minmax(130px,1fr)); gap:.7rem; }
            .gallery .g{ width:100%; aspect-ratio:3/4; object-fit:cover; border-radius:10px; background:#ddd6c8; }
            footer{ margin-top:2rem; padding-top:1rem; border-top:1px solid var(--line); color:var(--muted); font-size:.75rem; text-align:center; letter-spacing:.1em; }
            """;

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
