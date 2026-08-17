package dev.fedorov.ailife.agents.travel.intent;

import dev.fedorov.ailife.agentruntime.intent.SkillClassifier;
import dev.fedorov.ailife.agentruntime.intent.SkillRouter;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.travel.chat.TravelChat;
import dev.fedorov.ailife.agents.travel.flow.PackingFlow;
import dev.fedorov.ailife.agents.travel.flow.RouteFlow;
import dev.fedorov.ailife.agents.travel.flow.TripComposer;
import dev.fedorov.ailife.agents.travel.flow.WalletFlow;
import dev.fedorov.ailife.agents.travel.profile.TravelProfiler;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.llm.LlmClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Routes a <b>text</b> message the orchestrator sent to {@code travel} into one of the agent's four
 * text-intent flows — set travel preferences ({@link TravelProfiler#setProfile}), manage the trip wallet
 * ({@link WalletFlow#handle}), build a packing list ({@link PackingFlow#handle}), or plan a trip
 * ({@link TripComposer#plan}) — or a plain chat reply ({@link TravelChat}). The route-file attachment
 * import stays a deterministic pre-check in {@code IntentController} (a file is unambiguously an import,
 * not a text intent, like docs' photo pre-check), so it never reaches this router.
 *
 * <p>A thin binding over the shared {@link SkillRouter} ({@code libs/agent-runtime}, skills-vs-flows
 * Bucket 1 / #475): it supplies the travel-specific parts — the ordered {@code {skillName → flow}} dispatch
 * map ({@code travel-profiler}/{@code trip-wallet}/{@code packing-list}/{@code trip-composer}), the chat
 * fallback, and the intro/decide framing — and the shared router owns the LLM round-trip, the
 * {@link SkillClassifier} parse, and the soft-fail-to-chat dispatch. Each skill's SKILL.md {@code description}
 * is the routing SSOT, so a paraphrase outside the old {@code *_CUES} keyword lists routes correctly — this
 * is the agent whose packing paraphrase ("а что кинуть в чемодан") is #475's motivating misroute example, so
 * {@code packing-list} gained a routing-descriptor SKILL.md (the flow stays deterministic in code).
 *
 * <p>The <b>map-link import</b> (RT-d2) folds into the chat fallback rather than a separate cue set: a bare
 * map link with no classified intent is imported to the trip ({@link RouteFlow#handleLink}), while an
 * explicit "хочу на море {@code <link>}" is classified as {@code trip-composer} first, so the link still
 * plans. Everything else is a plain {@link TravelChat} reply.
 */
@Component
public class TravelIntentRouter {

    private static final String TRAVEL_PROFILER = "travel-profiler";
    private static final String TRIP_WALLET = "trip-wallet";
    private static final String PACKING_LIST = "packing-list";
    private static final String TRIP_COMPOSER = "trip-composer";

    private static final Pattern URL = Pattern.compile("(?i)((?:https?://|geo:)\\S+)");
    private static final Set<String> MAP_HOSTS = Set.of(
            "google.", "goo.gl", "yandex.", "openstreetmap.", "osm.org");

    private final SkillRouter router;

    public TravelIntentRouter(LlmClient llm, SkillRegistry skills, SkillClassifier classifier,
                              AgentManifest manifest, TravelProfiler profiler, WalletFlow wallet,
                              PackingFlow packing, TripComposer composer, RouteFlow route, TravelChat chat) {
        Map<String, Function<NormalizedMessage, Mono<IntentResponse>>> flows = new LinkedHashMap<>();
        flows.put(TRAVEL_PROFILER, profiler::setProfile);
        flows.put(TRIP_WALLET, wallet::handle);
        flows.put(PACKING_LIST, packing::handle);
        flows.put(TRIP_COMPOSER, composer::plan);
        // A bare map link with no classified intent is a route import (RT-d2); everything else is plain chat.
        Function<NormalizedMessage, Mono<IntentResponse>> chatFallback = msg -> {
            Optional<String> mapUrl = msg == null ? Optional.empty() : mapLink(msg.text());
            return mapUrl.isPresent() ? route.handleLink(msg, mapUrl.get()) : chat.reply(msg);
        };
        this.router = new SkillRouter(llm, skills, classifier, manifest,
                "You are routing a message for the travel agent. Reply directly to the user, or run one skill.",
                "Decide: does the user want to run a skill (set their travel preferences / manage the trip "
                        + "wallet / build a packing list / plan a trip) or just talk?",
                flows, chatFallback);
    }

    public Mono<IntentResponse> route(NormalizedMessage msg) {
        return router.route(msg);
    }

    /** The first map-provider URL (or {@code geo:} URI) in the text, trailing punctuation trimmed. */
    static Optional<String> mapLink(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Matcher m = URL.matcher(text);
        while (m.find()) {
            String url = m.group(1);
            String lower = url.toLowerCase(Locale.ROOT);
            if (lower.startsWith("geo:") || MAP_HOSTS.stream().anyMatch(lower::contains)) {
                return Optional.of(stripTrailingPunctuation(url));
            }
        }
        return Optional.empty();
    }

    private static String stripTrailingPunctuation(String url) {
        int end = url.length();
        while (end > 0 && ").,;!?»".indexOf(url.charAt(end - 1)) >= 0) {
            end--;
        }
        return url.substring(0, end);
    }

    /** The exact classifier prompt {@link #route} builds — replayed by the routing golden. */
    String buildClassifierPrompt() {
        return router.buildClassifierPrompt();
    }
}
