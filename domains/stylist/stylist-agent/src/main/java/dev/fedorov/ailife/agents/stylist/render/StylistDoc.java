package dev.fedorov.ailife.agents.stylist.render;

import java.util.ArrayList;
import java.util.List;

/**
 * The render-format-agnostic model of a stylist deliverable board (analysis, audit, capsule, gap).
 * A {@link StylistRenderer} turns it into concrete bytes (luxury-editorial HTML today, PDF later via
 * the same seam). The board is a header ({@code kicker}/{@code title}/{@code subtitle}) plus any of:
 * keyed text {@code sections}, a colour {@code palette}, a {@code verdicts} grid (KEEP/QUESTION/REMOVE
 * per garment), a {@code hero} row, and an image {@code gallery}. Each is optional — a flow fills only
 * what its board needs; the renderer skips empties. The flow supplies content; the renderer owns all
 * styling (the locked aesthetic).
 */
public record StylistDoc(
        String kicker,
        String title,
        String subtitle,
        List<Section> sections,
        List<Swatch> palette,
        List<VerdictItem> verdicts,
        List<HeroItem> hero,
        List<String> gallery) {

    /** Back-compat: a text-only board (no gallery) — e.g. the early analysis page. */
    public StylistDoc(String title, String subtitle, List<Section> sections) {
        this(null, title, subtitle, sections, null, null, null, null);
    }

    /** Back-compat: text board + an image gallery — e.g. the early capsule page. */
    public StylistDoc(String title, String subtitle, List<Section> sections, List<String> gallery) {
        this(null, title, subtitle, sections, null, null, null, gallery);
    }

    public record Section(String heading, List<String> paragraphs) {
    }

    /** One colour-harmony swatch: a CSS colour plus an optional label (tooltip). */
    public record Swatch(String hex, String label) {
    }

    public enum Verdict { KEEP, QUESTION, REMOVE }

    /** One audited garment: name, verdict, one-line reason, and an optional photo URL. */
    public record VerdictItem(String name, Verdict verdict, String reason, String imageUrl) {
    }

    /** One hero piece (max-impact): name, optional photo URL, optional one-line note. */
    public record HeroItem(String name, String imageUrl, String note) {
    }

    public static Builder builder(String title) {
        return new Builder(title);
    }

    /** Fluent builder for the richer boards (audit / analysis / gap); keeps call sites readable. */
    public static final class Builder {
        private String kicker;
        private final String title;
        private String subtitle;
        private final List<Section> sections = new ArrayList<>();
        private final List<Swatch> palette = new ArrayList<>();
        private final List<VerdictItem> verdicts = new ArrayList<>();
        private final List<HeroItem> hero = new ArrayList<>();
        private final List<String> gallery = new ArrayList<>();

        private Builder(String title) {
            this.title = title;
        }

        public Builder kicker(String kicker) { this.kicker = kicker; return this; }
        public Builder subtitle(String subtitle) { this.subtitle = subtitle; return this; }

        public Builder section(String heading, List<String> paragraphs) {
            sections.add(new Section(heading, paragraphs));
            return this;
        }

        public Builder swatch(String hex, String label) {
            palette.add(new Swatch(hex, label));
            return this;
        }

        public Builder verdict(String name, Verdict v, String reason, String imageUrl) {
            verdicts.add(new VerdictItem(name, v, reason, imageUrl));
            return this;
        }

        public Builder heroPiece(String name, String imageUrl, String note) {
            hero.add(new HeroItem(name, imageUrl, note));
            return this;
        }

        public Builder galleryImage(String url) {
            gallery.add(url);
            return this;
        }

        public StylistDoc build() {
            return new StylistDoc(kicker, title, subtitle,
                    sections.isEmpty() ? null : sections,
                    palette.isEmpty() ? null : palette,
                    verdicts.isEmpty() ? null : verdicts,
                    hero.isEmpty() ? null : hero,
                    gallery.isEmpty() ? null : gallery);
        }
    }
}
