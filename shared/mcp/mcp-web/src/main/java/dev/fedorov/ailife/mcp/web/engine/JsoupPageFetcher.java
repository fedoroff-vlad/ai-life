package dev.fedorov.ailife.mcp.web.engine;

import dev.fedorov.ailife.contracts.web.PageContent;
import dev.fedorov.ailife.mcp.web.config.McpWebProperties;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default {@link PageFetcher}: jsoup fetches + parses the page, strips boilerplate
 * ({@code script/style/nav/header/footer/aside/form/...}), prefers an {@code <article>}/{@code <main>}
 * region when present, and returns the readable text (capped). Lean and predictable; readability4j
 * is the upgrade behind this interface. Blocking ({@code Jsoup.connect}) — callers invoke it on a
 * blocking scheduler. Best-effort: any fetch/parse failure yields empty text (logged), so a single
 * bad page never sinks a research gather.
 */
@Component
public class JsoupPageFetcher implements PageFetcher {

    private static final Logger log = LoggerFactory.getLogger(JsoupPageFetcher.class);
    private static final String UA = "ai-life/mcp-web 0.0.1 (+research bot)";
    private static final String BOILERPLATE =
            "script, style, nav, header, footer, aside, form, noscript, iframe, svg";

    private final McpWebProperties props;

    public JsoupPageFetcher(McpWebProperties props) {
        this.props = props;
    }

    @Override
    public PageContent fetch(String url) {
        try {
            Document doc = Jsoup.connect(url)
                    .userAgent(UA)
                    .timeout(props.getFetchTimeoutMs())
                    .followRedirects(true)
                    .get();

            String title = blankToNull(doc.title());
            doc.select(BOILERPLATE).remove();

            Element main = doc.selectFirst("article");
            if (main == null) {
                main = doc.selectFirst("main");
            }
            Element base = main != null ? main : doc.body();
            String text = base == null ? "" : base.text(); // jsoup collapses whitespace

            int cap = props.getFetchMaxChars();
            boolean truncated = text.length() > cap;
            if (truncated) {
                text = text.substring(0, cap);
            }
            return new PageContent(url, title, text, truncated);
        } catch (Exception e) {
            log.warn("fetch_url failed for {}: {}", url, e.toString());
            return new PageContent(url, null, "", false);
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
