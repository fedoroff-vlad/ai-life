package dev.fedorov.ailife.agents.stylist.config;

import dev.fedorov.ailife.docrender.DocRenderer;
import dev.fedorov.ailife.docrender.HtmlDocRenderer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the shared {@code libs/doc-render} renderer with the stylist's own theme. The renderer lives
 * in the lib (lifted on the second consumer per stylist.md); the agent supplies its env-overridable
 * {@link StylistThemeProperties} mapped to a {@code DocTheme}, so a redeploy re-skins without code.
 */
@Configuration
public class RenderConfig {

    @Bean
    public DocRenderer docRenderer(StylistThemeProperties theme) {
        return new HtmlDocRenderer(theme.toDocTheme());
    }
}
