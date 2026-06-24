package dev.fedorov.ailife.agents.creator.config;

import dev.fedorov.ailife.docrender.DocRenderer;
import dev.fedorov.ailife.docrender.DocTheme;
import dev.fedorov.ailife.docrender.HtmlDocRenderer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the shared {@code libs/doc-render} renderer for the creator's HTML deliverable — the
 * content-plan board (CR-d). Uses the lib's default {@link DocTheme} (the locked editorial aesthetic),
 * like nutrition/chef; a creator-specific theme can be added the same way if it ever earns its keep.
 */
@Configuration
public class RenderConfig {

    @Bean
    public DocRenderer docRenderer() {
        return new HtmlDocRenderer(new DocTheme());
    }
}
