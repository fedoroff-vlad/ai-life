package dev.fedorov.ailife.mcp.feeds.engine;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import dev.fedorov.ailife.contracts.trends.TrendHit;
import dev.fedorov.ailife.mcp.feeds.config.McpFeedsProperties;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Default {@link FeedSource}: latest items from an <b>RSS/Atom feed</b> (parsed with Rome) or a
 * <b>public Telegram channel</b> (the {@code t.me/s/{channel}} web preview, parsed with jsoup). One
 * HTTP fetch per call; each item maps to a uniform {@link TrendHit} (platform {@code rss} or
 * {@code telegram}). Selected by {@code feeds.source=romejsoup} (the default). No key — both surfaces
 * are public.
 *
 * <p>An empty feed / unknown channel yields an empty list (no data, not an error). A genuine
 * transport or parse failure propagates on the {@link Mono} (the caller's gather soft-fails per
 * source).
 */
@Component
@ConditionalOnProperty(name = "feeds.source", havingValue = "romejsoup", matchIfMissing = true)
public class RomeJsoupFeedSource implements FeedSource {

    private static final int SUMMARY_MAX = 500;
    private static final int TITLE_MAX = 120;

    private final WebClient http;
    private final McpFeedsProperties props;

    public RomeJsoupFeedSource(@Qualifier("feedsWebClient") WebClient http, McpFeedsProperties props) {
        this.http = http;
        this.props = props;
    }

    @Override
    public Mono<List<TrendHit>> items(String source, Integer maxResults) {
        String s = source == null ? "" : source.trim();
        if (s.isEmpty()) {
            return Mono.just(List.of());
        }
        int limit = maxResults == null || maxResults <= 0 ? props.getMaxResults() : maxResults;
        return isUrl(s) ? rss(s, limit) : telegram(stripHandle(s), limit);
    }

    private static boolean isUrl(String s) {
        return s.startsWith("http://") || s.startsWith("https://");
    }

    private static String stripHandle(String s) {
        return s.startsWith("@") ? s.substring(1) : s;
    }

    // --- RSS / Atom (Rome) ---

    private Mono<List<TrendHit>> rss(String feedUrl, int limit) {
        return http.get()
                .uri(URI.create(feedUrl))
                .retrieve()
                .bodyToMono(byte[].class)
                .timeout(Duration.ofSeconds(10))
                .map(bytes -> parseRss(bytes, limit));
    }

    private static List<TrendHit> parseRss(byte[] xml, int limit) {
        if (xml == null || xml.length == 0) {
            return List.of();
        }
        try {
            SyndFeed feed = new SyndFeedInput().build(new XmlReader(new ByteArrayInputStream(xml)));
            List<TrendHit> hits = new ArrayList<>();
            for (SyndEntry e : feed.getEntries()) {
                if (hits.size() >= limit) {
                    break;
                }
                String title = trimToNull(e.getTitle());
                if (title == null) {
                    continue;
                }
                String summary = e.getDescription() == null ? null : cap(stripHtml(e.getDescription().getValue()), SUMMARY_MAX);
                hits.add(new TrendHit("rss", "rss", title, trimToNull(e.getLink()), summary, rssMetrics(e)));
            }
            return hits;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse feed: " + ex.getMessage(), ex);
        }
    }

    private static JsonNode rssMetrics(SyndEntry e) {
        ObjectNode m = JsonNodeFactory.instance.objectNode();
        if (e.getPublishedDate() != null) {
            m.put("publishedAt", e.getPublishedDate().toInstant().toString());
        }
        String author = trimToNull(e.getAuthor());
        if (author != null) {
            m.put("author", author);
        }
        return m.isEmpty() ? null : m;
    }

    // --- Public Telegram channel (jsoup over t.me/s/) ---

    private Mono<List<TrendHit>> telegram(String channel, int limit) {
        if (channel.isEmpty()) {
            return Mono.just(List.of());
        }
        return http.get()
                .uri(URI.create(props.getTelegramBaseUrl() + "/s/" + channel))
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(10))
                .map(html -> parseTelegram(html, channel, limit));
    }

    private static List<TrendHit> parseTelegram(String html, String channel, int limit) {
        if (html == null || html.isBlank()) {
            return List.of();
        }
        Document doc = Jsoup.parse(html);
        List<TrendHit> all = new ArrayList<>();
        for (Element msg : doc.select("div.tgme_widget_message")) {
            Element textEl = msg.selectFirst(".tgme_widget_message_text");
            Element dateLink = msg.selectFirst("a.tgme_widget_message_date");
            String text = textEl == null ? null : trimToNull(textEl.text());
            String url = dateLink == null ? null : trimToNull(dateLink.attr("href"));
            if (text == null && url == null) {
                continue;
            }
            String title = text == null ? "Post in @" + channel : cap(text, TITLE_MAX);
            all.add(new TrendHit("telegram", "telegram", title, url,
                    text == null ? null : cap(text, SUMMARY_MAX), channelMetrics(channel)));
        }
        // The web preview lists oldest→newest; take the most recent `limit`, newest-first.
        Collections.reverse(all);
        return all.size() > limit ? new ArrayList<>(all.subList(0, limit)) : all;
    }

    private static JsonNode channelMetrics(String channel) {
        ObjectNode m = JsonNodeFactory.instance.objectNode();
        m.put("channel", channel);
        return m;
    }

    // --- helpers ---

    private static String stripHtml(String s) {
        return s == null ? null : Jsoup.parse(s).text();
    }

    private static String cap(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
