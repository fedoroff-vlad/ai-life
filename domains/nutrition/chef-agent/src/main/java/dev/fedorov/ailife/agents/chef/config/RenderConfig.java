package dev.fedorov.ailife.agents.chef.config;

import dev.fedorov.ailife.docrender.DocRenderer;
import dev.fedorov.ailife.docrender.DocTheme;
import dev.fedorov.ailife.docrender.HtmlDocRenderer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the shared {@code libs/doc-render} renderer for the chef's HTML recipe cards (CH-b). Uses
 * the lib's default {@link DocTheme} (the locked warm-beige editorial aesthetic) — chef doesn't yet
 * need its own env theming (the same seam stylist/nutrition use).
 */
@Configuration
public class RenderConfig {

    @Bean
    public DocRenderer docRenderer() {
        return new HtmlDocRenderer(new DocTheme());
    }
}
