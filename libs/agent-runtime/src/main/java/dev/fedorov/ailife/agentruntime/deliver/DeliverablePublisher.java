package dev.fedorov.ailife.agentruntime.deliver;

import dev.fedorov.ailife.agentruntime.http.MediaStoreClient;
import dev.fedorov.ailife.docrender.Doc;
import dev.fedorov.ailife.docrender.DocRenderer;
import dev.fedorov.ailife.docrender.DocTheme;
import dev.fedorov.ailife.docrender.HtmlDocRenderer;
import dev.fedorov.ailife.docrender.RenderedDoc;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The shared <b>render → store → link</b> deliverable seam for the gather→synthesize agents
 * (creator / chef / nutritionist / stylist). Every one of those flows ended in the same three steps —
 * render a {@link Doc} through the agent's {@link DocRenderer}, upload the bytes to media-service via
 * the shared {@link MediaStoreClient}, and turn the stored object into a public link — plus the same
 * {@code base()/link()} URL plumbing and {@code splitParagraphs()/summary()} text helpers, all
 * copy-pasted per flow. This lifts that once.
 *
 * <p>Pure logic, no Spring: each deliverable agent declares the {@code @Bean} in its own
 * {@code OutboundHttpConfig} (mirroring {@link MediaStoreClient}), constructing it from the agent's
 * {@link DocRenderer} bean, its {@link MediaStoreClient}, and its public-media base URL — so base URLs
 * stay per-agent and {@link #publish} is signature-identical across callers.
 */
public class DeliverablePublisher {

    private final DocRenderer renderer;
    private final MediaStoreClient media;
    private final String publicMediaBaseUrl;

    public DeliverablePublisher(DocRenderer renderer, MediaStoreClient media, String publicMediaBaseUrl) {
        this.renderer = renderer;
        this.media = media;
        this.publicMediaBaseUrl = trimTrailingSlash(publicMediaBaseUrl);
    }

    /**
     * Convenience for agents that render with the <b>default</b> warm-beige editorial theme (the common
     * case — only stylist themes its boards): builds the default {@link HtmlDocRenderer} so the agent
     * doesn't need its own {@code DocRenderer} bean / {@code RenderConfig}.
     */
    public DeliverablePublisher(MediaStoreClient media, String publicMediaBaseUrl) {
        this(new HtmlDocRenderer(new DocTheme()), media, publicMediaBaseUrl);
    }

    /** Render the doc, store it in media-service, and return the public link to open it. */
    public Mono<String> publish(UUID householdId, UUID ownerId, Doc doc) {
        RenderedDoc rendered = renderer.render(doc);
        return media.upload(householdId, ownerId, rendered.filename(), rendered.mimeType(), rendered.content())
                .map(stored -> mediaUrl(stored.id()));
    }

    /** Public URL of a stored media object by id; {@code null} id → {@code null} (callers null-check). */
    public String mediaUrl(UUID mediaId) {
        return mediaId == null ? null : publicMediaBaseUrl + "/v1/media/" + mediaId;
    }

    /** Public URL of a stored media object by raw id string; blank → {@code null}. */
    public String mediaUrl(String mediaId) {
        return (mediaId == null || mediaId.isBlank())
                ? null : publicMediaBaseUrl + "/v1/media/" + mediaId.trim();
    }

    /**
     * Split synthesized prose into non-blank, stripped lines for a {@code Doc} section. A non-null but
     * all-blank input yields a single empty line (matches the previous per-flow behaviour).
     */
    public static List<String> splitParagraphs(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String line : text.strip().split("\\R")) {
            if (!line.isBlank()) out.add(line.strip());
        }
        if (out.isEmpty()) out.add(text.strip());
        return out;
    }

    /**
     * The chat summary: the first line of the synthesis (the full version lives in the HTML), capped at
     * 280 chars with an ellipsis. A blank synthesis falls back to the caller-supplied message.
     */
    public static String summary(String text, String fallback) {
        if (text == null || text.isBlank()) return fallback;
        String firstLine = text.strip().split("\\R", 2)[0];
        return firstLine.length() > 280 ? firstLine.substring(0, 277) + "…" : firstLine;
    }

    private static String trimTrailingSlash(String base) {
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }
}
