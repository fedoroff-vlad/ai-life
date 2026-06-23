package dev.fedorov.ailife.agents.nutritionist.config;

import dev.fedorov.ailife.docrender.DocRenderer;
import dev.fedorov.ailife.docrender.DocTheme;
import dev.fedorov.ailife.docrender.HtmlDocRenderer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the shared {@code libs/doc-render} renderer for the nutrition HTML deliverables (analysis,
 * later basket/ration reports). Uses the lib's default {@link DocTheme} (the locked warm-beige
 * editorial aesthetic) — nutrition doesn't yet need its own env theming (stylist lifted that into
 * the lib; a nutrition-specific theme can be added the same way if it ever earns its keep).
 */
@Configuration
public class RenderConfig {

    @Bean
    public DocRenderer docRenderer() {
        return new HtmlDocRenderer(new DocTheme());
    }
}
