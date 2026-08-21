package dev.fedorov.ailife.agents.calendar.chat;

import dev.fedorov.ailife.agents.calendar.config.CalendarAgentProperties;
import dev.fedorov.ailife.agents.calendar.http.CaldavFeedClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.calendar.CalendarFeedDto;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * The calendar agent's chat fallback: a message that is not an event-op cue (create / cancel / move) gets a
 * conversational reply with AGENT.md as the system prompt. Lifted out of {@code IntentController} when the
 * in-agent {@code SkillRouter} (#475 / Track H.2) took over routing, so the router's soft-fail lands here.
 *
 * <p><b>Auto-issue calendar feed (#195):</b> on a user's <b>first</b> calendar message (when
 * {@code calendar-agent.public-feed-base-url} is set) the agent issues a read-only ICS feed via mcp-caldav
 * and appends the subscribe link — so each member gets their personal calendar link without asking.
 * Subsequent messages reuse the feed and don't nag. The whole nudge is best-effort: any failure just
 * returns the plain LLM reply.
 */
@Component
public class CalendarChat {

    private static final Logger log = LoggerFactory.getLogger(CalendarChat.class);

    private final LlmClient llm;
    private final AgentManifest manifest;
    private final CaldavFeedClient feeds;
    private final CalendarAgentProperties props;

    public CalendarChat(LlmClient llm, AgentManifest manifest,
                        CaldavFeedClient feeds, CalendarAgentProperties props) {
        this.llm = llm;
        this.manifest = manifest;
        this.feeds = feeds;
        this.props = props;
    }

    public Mono<IntentResponse> reply(NormalizedMessage message) {
        var request = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.system(manifest.body()),
                LlmMessage.user(message.text())));
        return llm.chat(request)
                .map(resp -> new IntentResponse(manifest.name(), resp.content(), resp.model()))
                .flatMap(reply -> maybeAppendFeedLink(message, reply));
    }

    /** On the user's first calendar message (and only then), issue a feed and append the subscribe link. */
    private Mono<IntentResponse> maybeAppendFeedLink(NormalizedMessage message, IntentResponse reply) {
        String base = props.getPublicFeedBaseUrl();
        if (base == null || base.isBlank() || message.householdId() == null || message.userId() == null) {
            return Mono.just(reply);
        }
        return feeds.ensureFeed(message.householdId(), message.userId(), "ai-life")
                .map(ensured -> ensured.created()
                        ? new IntentResponse(reply.agent(),
                                reply.text() + feedNudge(base, ensured.feed()), reply.llmModel())
                        : reply)
                .onErrorResume(e -> {
                    log.warn("feed auto-issue failed for household {}: {}", message.householdId(), e.toString());
                    return Mono.just(reply);
                });
    }

    private static String feedNudge(String base, CalendarFeedDto feed) {
        String url = base.replaceAll("/+$", "") + "/ics/" + feed.token() + ".ics";
        return "\n\n📅 Подписаться на свой календарь (Apple / Google / Yandex), чтобы видеть все события:\n" + url;
    }
}
