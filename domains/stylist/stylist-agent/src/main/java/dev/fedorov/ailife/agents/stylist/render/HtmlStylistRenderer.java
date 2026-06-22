package dev.fedorov.ailife.agents.stylist.render;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Default {@link StylistRenderer}: a self-contained, responsive <b>luxury-editorial poster</b> (the
 * aesthetic LOCKED with the owner 2026-06-21 — noble warm-beige ground, serif display caps,
 * hairline dividers instead of boxed cards, a centered photo anchor, gold hero accent,
 * KEEP/QUESTION/REMOVE verdict tags, palette swatches). Light-only; the only external dependency is
 * Google Fonts (Oranienbaum + Manrope, Cyrillic). A PDF renderer can later wrap this behind the seam.
 *
 * <p>Layout: header → centered featured photo (when present) → palette strip → text sections flowing
 * in a hairline-separated multi-column block → full-width verdict grid / hero row / image gallery.
 * Each block renders only when the {@link StylistDoc} carries it.
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
          .append("family=Oranienbaum&family=Manrope:wght@300;400;500&display=swap\" rel=\"stylesheet\">\n")
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

        // Centered photo anchor
        if (notBlank(doc.featuredImageUrl())) {
            sb.append("<img class=\"portrait\" src=\"").append(esc(doc.featuredImageUrl()))
              .append("\" alt=\"\">\n");
        }

        // Palette strip
        if (doc.palette() != null && !doc.palette().isEmpty()) {
            sb.append("<div class=\"palrow\">\n");
            for (StylistDoc.Swatch s : doc.palette()) {
                if (s == null || !notBlank(s.hex())) continue;
                sb.append("<i style=\"background:").append(esc(s.hex())).append("\" title=\"")
                  .append(esc(s.label() == null ? "" : s.label())).append("\"></i>\n");
            }
            sb.append("</div>\n");
        }

        // Text sections — hairline-separated multi-column flow
        if (doc.sections() != null && !doc.sections().isEmpty()) {
            sb.append("<div class=\"flow\">\n");
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
            sb.append("</div>\n");
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

        // Image gallery (capsule looks / wardrobe)
        if (doc.gallery() != null && !doc.gallery().isEmpty()) {
            sb.append("<div class=\"gallery\">\n");
            for (String url : doc.gallery()) {
                if (notBlank(url)) {
                    sb.append("<img class=\"g\" loading=\"lazy\" src=\"").append(esc(url)).append("\" alt=\"\">\n");
                }
            }
            sb.append("</div>\n");
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

    /** The locked luxury-editorial poster styling — warm-beige ground, serif caps, hairline dividers. */
    private static final String CSS = """
            :root{ color-scheme: light; --paper:#efe7d8; --ink:#3a352d; --soft:#6f675a; --muted:#9a907e; \
            --line:#d8cdb6; --gold:#a98a4e; --keep:#5a6b4f; --question:#b0823a; --remove:#9b4a3a; \
            --serif:"Oranienbaum",Georgia,"Times New Roman",serif; --sans:"Manrope",-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif; }
            *{ box-sizing:border-box; }
            body{ margin:0; padding:1.25rem; background:var(--paper); color:var(--ink); font-family:var(--sans); font-weight:300; line-height:1.55; }
            .board{ max-width:1000px; margin:0 auto; border:1px solid var(--line); padding:1.5rem clamp(1rem,3vw,2.25rem) 0; }
            header{ text-align:center; padding-bottom:1.1rem; border-bottom:1px solid var(--line); }
            h1{ font-family:var(--serif); font-weight:400; letter-spacing:.13em; text-transform:uppercase; font-size:clamp(1.9rem,6vw,3rem); margin:0; line-height:1.05; }
            .kicker{ font-size:.68rem; letter-spacing:.42em; color:var(--muted); text-transform:uppercase; margin:.55rem 0 0; }
            .sub{ color:var(--soft); margin:.55rem 0 0; font-size:.92rem; }
            .portrait{ display:block; width:min(360px,72%); aspect-ratio:3/4; object-fit:cover; margin:1.4rem auto .6rem; border:1px solid var(--line); border-radius:6px; }
            .palrow{ display:flex; gap:.45rem; flex-wrap:wrap; justify-content:center; padding:1rem 0 1.2rem; border-bottom:1px solid var(--line); }
            .palrow i{ width:34px; height:34px; border-radius:50%; border:1px solid var(--line); display:block; }
            .flow{ column-count:2; column-gap:2.5rem; padding:.4rem 0 1rem; }
            @media (max-width:640px){ .flow{ column-count:1; } }
            .flow section{ break-inside:avoid; margin:0; padding:1.1rem 0 .2rem; border-top:1px solid var(--line); }
            .flow section:first-child{ border-top:none; }
            h2{ font-family:var(--serif); text-transform:uppercase; letter-spacing:.2em; font-size:.95rem; color:var(--muted); margin:0 0 .45rem; }
            p{ margin:.35rem 0; font-size:.9rem; color:var(--soft); }
            .grid{ display:grid; grid-template-columns:repeat(auto-fill,minmax(150px,1fr)); gap:1px; background:var(--line); border:1px solid var(--line); margin:1rem 0; }
            .cell{ background:var(--paper); padding:.85rem; }
            .cap{ font-size:.62rem; letter-spacing:.16em; text-transform:uppercase; color:var(--muted); margin:0 0 .5rem; }
            .photo{ width:100%; aspect-ratio:3/4; object-fit:cover; border-radius:5px; margin-bottom:.55rem; background:#ddd2bd; }
            .verdict{ display:flex; align-items:center; gap:.4rem; font-size:.72rem; letter-spacing:.12em; text-transform:uppercase; font-weight:500; }
            .dot{ width:9px; height:9px; border-radius:50%; display:inline-block; }
            .keep{ color:var(--keep);} .keep .dot{ background:var(--keep);} \
            .question{ color:var(--question);} .question .dot{ background:var(--question);} \
            .remove{ color:var(--remove);} .remove .dot{ background:var(--remove);}
            .reason{ font-size:.74rem; color:var(--soft); margin:.4rem 0 0; line-height:1.45; }
            .hero{ border-top:1px solid var(--line); border-bottom:1px solid var(--line); margin:1.25rem 0; padding:1.1rem 0; }
            .hero h3{ font-family:var(--serif); text-transform:uppercase; letter-spacing:.22em; font-size:.85rem; color:var(--gold); text-align:center; margin:0 0 .9rem; }
            .hero .row{ display:grid; grid-template-columns:repeat(auto-fit,minmax(110px,1fr)); gap:.9rem; }
            .hero .item{ text-align:center; }
            .hero .item .photo{ aspect-ratio:1/1; }
            .hero .n{ font-size:.66rem; letter-spacing:.1em; text-transform:uppercase; margin-top:.4rem; }
            .hero .hn{ font-size:.7rem; color:var(--soft); margin-top:.2rem; }
            .gallery{ display:grid; grid-template-columns:repeat(auto-fill,minmax(130px,1fr)); gap:.7rem; padding:.4rem 0 1rem; }
            .gallery .g{ width:100%; aspect-ratio:3/4; object-fit:cover; border-radius:6px; border:1px solid var(--line); background:#ddd2bd; }
            footer{ margin-top:1rem; padding:1rem 0; border-top:1px solid var(--line); color:var(--muted); font-size:.7rem; text-align:center; letter-spacing:.14em; }
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
