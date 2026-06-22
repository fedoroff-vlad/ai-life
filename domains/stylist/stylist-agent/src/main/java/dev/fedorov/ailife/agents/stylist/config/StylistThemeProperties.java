package dev.fedorov.ailife.agents.stylist.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The editorial-board theme — colours + fonts that {@code HtmlStylistRenderer} renders into the page's
 * {@code :root} CSS variables and the Google-Fonts link. The defaults are the aesthetic LOCKED with the
 * owner 2026-06-21 (noble warm-beige ground, Oranienbaum display + Manrope body). Lifting them here lets
 * a redeploy re-skin the deliverables without a code change (owner-suggested follow-up): override any
 * value via {@code STYLIST_THEME_*} env vars (e.g. {@code STYLIST_THEME_PAPER=#f3ece0}). The layout
 * rules themselves stay in the renderer — only the palette/typography are configurable.
 */
@ConfigurationProperties(prefix = "stylist-agent.theme")
public class StylistThemeProperties {

    /** Page ground / card background. */
    private String paper = "#efe7d8";
    /** Primary text (headings, body emphasis). */
    private String ink = "#3a352d";
    /** Secondary text (paragraphs). */
    private String soft = "#6f675a";
    /** Tertiary text (labels, kickers, footer). */
    private String muted = "#9a907e";
    /** Hairline dividers / borders. */
    private String line = "#d8cdb6";
    /** Hero accent. */
    private String gold = "#a98a4e";
    /** Verdict KEEP. */
    private String keep = "#5a6b4f";
    /** Verdict QUESTION. */
    private String question = "#b0823a";
    /** Verdict REMOVE. */
    private String remove = "#9b4a3a";

    /** CSS font stack for display/serif headings. */
    private String serifFamily = "\"Oranienbaum\",Georgia,\"Times New Roman\",serif";
    /** CSS font stack for body/sans text. */
    private String sansFamily = "\"Manrope\",-apple-system,BlinkMacSystemFont,\"Segoe UI\",Roboto,sans-serif";
    /** The query portion of the Google-Fonts {@code css2?} URL (so fonts can be swapped with the stacks). */
    private String googleFontsQuery = "family=Oranienbaum&family=Manrope:wght@300;400;500&display=swap";

    public String getPaper() { return paper; }
    public void setPaper(String paper) { this.paper = paper; }

    public String getInk() { return ink; }
    public void setInk(String ink) { this.ink = ink; }

    public String getSoft() { return soft; }
    public void setSoft(String soft) { this.soft = soft; }

    public String getMuted() { return muted; }
    public void setMuted(String muted) { this.muted = muted; }

    public String getLine() { return line; }
    public void setLine(String line) { this.line = line; }

    public String getGold() { return gold; }
    public void setGold(String gold) { this.gold = gold; }

    public String getKeep() { return keep; }
    public void setKeep(String keep) { this.keep = keep; }

    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }

    public String getRemove() { return remove; }
    public void setRemove(String remove) { this.remove = remove; }

    public String getSerifFamily() { return serifFamily; }
    public void setSerifFamily(String serifFamily) { this.serifFamily = serifFamily; }

    public String getSansFamily() { return sansFamily; }
    public void setSansFamily(String sansFamily) { this.sansFamily = sansFamily; }

    public String getGoogleFontsQuery() { return googleFontsQuery; }
    public void setGoogleFontsQuery(String googleFontsQuery) { this.googleFontsQuery = googleFontsQuery; }
}
