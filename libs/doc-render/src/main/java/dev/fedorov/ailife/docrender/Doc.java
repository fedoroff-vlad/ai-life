package dev.fedorov.ailife.docrender;

import java.util.ArrayList;
import java.util.List;

/**
 * The render-format-agnostic model of a deliverable board (stylist analysis/audit/capsule/gap; later
 * nutrition basket breakdown / ration / recipe card). A {@link DocRenderer} turns it into concrete
 * bytes (luxury-editorial HTML today, PDF later via the same seam). The board is a header
 * ({@code kicker}/{@code title}/{@code subtitle} + an optional {@code featuredImageUrl}) plus any of:
 * keyed text {@code sections}, a colour {@code palette}, a {@code verdicts} grid (a status tile per
 * item), a {@code hero} row, and an image {@code gallery}. Each is optional — a flow fills only what
 * its board needs; the renderer skips empties. The flow supplies content; the renderer owns styling.
 *
 * <p>Field generalisation for nutrition (a generic tile tone / a recipe-link list) lands with the
 * first nutrition consumer that needs it — the structure here is the stylist board lifted intact.
 */
public record Doc(
        String kicker,
        String title,
        String subtitle,
        String featuredImageUrl,
        List<Section> sections,
        List<Swatch> palette,
        List<VerdictItem> verdicts,
        List<HeroItem> hero,
        List<String> gallery) {

    /** Back-compat: a text-only board (no gallery) — e.g. an early analysis page. */
    public Doc(String title, String subtitle, List<Section> sections) {
        this(null, title, subtitle, null, sections, null, null, null, null);
    }

    /** Back-compat: text board + an image gallery — e.g. an early capsule page. */
    public Doc(String title, String subtitle, List<Section> sections, List<String> gallery) {
        this(null, title, subtitle, null, sections, null, null, null, gallery);
    }

    public record Section(String heading, List<String> paragraphs) {
    }

    /** One colour-harmony swatch: a CSS colour plus an optional label (tooltip). */
    public record Swatch(String hex, String label) {
    }

    public enum Verdict { KEEP, QUESTION, REMOVE }

    /** One audited item: name, verdict, one-line reason, and an optional photo URL. */
    public record VerdictItem(String name, Verdict verdict, String reason, String imageUrl) {
    }

    /** One hero piece (max-impact): name, optional photo URL, optional one-line note. */
    public record HeroItem(String name, String imageUrl, String note) {
    }

    public static Builder builder(String title) {
        return new Builder(title);
    }

    /** Fluent builder for the richer boards; keeps call sites readable. */
    public static final class Builder {
        private String kicker;
        private final String title;
        private String subtitle;
        private String featuredImageUrl;
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
        public Builder featured(String imageUrl) { this.featuredImageUrl = imageUrl; return this; }

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

        public Doc build() {
            return new Doc(kicker, title, subtitle, featuredImageUrl,
                    sections.isEmpty() ? null : sections,
                    palette.isEmpty() ? null : palette,
                    verdicts.isEmpty() ? null : verdicts,
                    hero.isEmpty() ? null : hero,
                    gallery.isEmpty() ? null : gallery);
        }
    }
}
