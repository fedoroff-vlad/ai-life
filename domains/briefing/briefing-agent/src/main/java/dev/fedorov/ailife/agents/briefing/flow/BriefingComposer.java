package dev.fedorov.ailife.agents.briefing.flow;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.coordinate.Coordinator;
import dev.fedorov.ailife.agentruntime.deliver.DeliverablePublisher;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.briefing.http.BriefingProfileClient;
import dev.fedorov.ailife.agents.briefing.http.CalendarEventsClient;
import dev.fedorov.ailife.agents.briefing.http.FinanceSnapshotClient;
import dev.fedorov.ailife.agents.briefing.http.ForecastClient;
import dev.fedorov.ailife.agents.briefing.http.NewsSearchClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.briefing.BriefingProfileDto;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.web.WebSearchHit;
import dev.fedorov.ailife.contracts.web.WebSearchResult;
import dev.fedorov.ailife.docrender.Doc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The digest flow (BR-d): the briefing agent's reason for existing, and the system's first multi-domain
 * <b>read</b> coordinator. It (1) <b>resolves</b> the requester's {@code briefing_profile} (self →
 * household-default → a plain all-sections default), then (2) <b>gathers</b> each enabled section in
 * parallel over the deterministic {@code /internal/*} read passthroughs — weather for the profile's
 * geocoded coordinates, today's agenda from mcp-caldav, yesterday's spend snapshot from mcp-finance, and
 * news headlines from mcp-web (one search per interest) — then (3) folds them into a {@code context} and
 * runs <b>one</b> {@code briefing-composer} LLM synthesis on the shared {@link Coordinator}, and (4)
 * renders the synthesis into an HTML digest board (the synthesized text as a section + the news links as
 * grounded provenance) via the shared {@link DeliverablePublisher}, stores it in media-service, and
 * appends the open-link to the reply (BR-e). A store failure soft-fails to the text-only reply.
 *
 * <p><b>Token economy is structural:</b> steps 1–2 are plain HTTP (no model cost); only step 3 hits the
 * LLM, synthesizing a pre-gathered corpus rather than "browsing". Per-source soft-fail is built into the
 * Coordinator — a slow/broken/disabled source is simply omitted from the context, never faked. Sections
 * come from the profile (all four when unset); weather needs geocoded coordinates and news needs at least
 * one interest, else those steps are skipped. Household-scoped agenda/finance for now (per-person
 * filtering is briefing.md §Deferred). Mirrors researcher-agent's {@code Researcher} gather→synthesize.
 */
@Component
public class BriefingComposer {

    private static final Logger log = LoggerFactory.getLogger(BriefingComposer.class);
    private static final String SKILL_NAME = "briefing-composer";
    private static final Set<String> DEFAULT_SECTIONS = Set.of("weather", "agenda", "finance", "news");
    /** Bound the news fan-out so the digest stays cheap + fast (one search per topic). */
    private static final int MAX_TOPICS = 3;
    private static final int NEWS_PER_TOPIC = 3;
    /** Cap the board's provenance link list (the gathered news headlines). */
    private static final int MAX_LINKS = 8;

    private final Coordinator coordinator;
    private final BriefingProfileClient profiles;
    private final ForecastClient forecast;
    private final CalendarEventsClient calendar;
    private final FinanceSnapshotClient finance;
    private final NewsSearchClient news;
    private final DeliverablePublisher publisher;
    private final SkillRegistry skills;
    private final AgentManifest manifest;
    private final ObjectMapper json;

    public BriefingComposer(Coordinator coordinator,
                            BriefingProfileClient profiles,
                            ForecastClient forecast,
                            CalendarEventsClient calendar,
                            FinanceSnapshotClient finance,
                            NewsSearchClient news,
                            DeliverablePublisher publisher,
                            SkillRegistry skills,
                            AgentManifest manifest,
                            ObjectMapper json) {
        this.coordinator = coordinator;
        this.profiles = profiles;
        this.forecast = forecast;
        this.calendar = calendar;
        this.finance = finance;
        this.news = news;
        this.publisher = publisher;
        this.skills = skills;
        this.manifest = manifest;
        this.json = json;
    }

    public Mono<IntentResponse> digest(NormalizedMessage msg) {
        return resolveProfile(msg)
                .flatMap(profile -> compose(msg, profile))
                .onErrorResume(e -> {
                    log.warn("briefing digest failed: {}", e.toString());
                    return Mono.just(reply("Не удалось собрать брифинг. Попробуйте позже.", null));
                });
    }

    /** self → household-default → an empty all-sections default; a broken mcp-briefing soft-fails to it. */
    private Mono<BriefingProfileDto> resolveProfile(NormalizedMessage msg) {
        return profiles.get(msg.householdId(), msg.userId())
                .switchIfEmpty(profiles.get(msg.householdId(), null))
                .switchIfEmpty(Mono.just(empty(msg)))
                .onErrorResume(e -> {
                    log.warn("briefing profile resolve failed, using defaults: {}", e.toString());
                    return Mono.just(empty(msg));
                });
    }

    private Mono<IntentResponse> compose(NormalizedMessage msg, BriefingProfileDto profile) {
        Set<String> sections = enabledSections(profile);
        ZoneId zone = zone(profile.timezone());
        LocalDate today = LocalDate.now(zone);

        Map<String, Mono<JsonNode>> gather = new LinkedHashMap<>();
        if (sections.contains("weather") && profile.latitude() != null && profile.longitude() != null) {
            gather.put("weather", forecast.forecast(profile.latitude(), profile.longitude())
                    .map(json::valueToTree));
        }
        if (sections.contains("agenda")) {
            gather.put("agenda", calendar.eventsBetween(msg.householdId(),
                            today.atStartOfDay(zone).toInstant(),
                            today.plusDays(1).atStartOfDay(zone).toInstant())
                    .map(json::valueToTree));
        }
        if (sections.contains("finance")) {
            gather.put("finance", finance.spendingByCategory(msg.householdId(),
                            today.minusDays(1).atStartOfDay(zone).toInstant(),
                            today.atStartOfDay(zone).toInstant())
                    .map(json::valueToTree));
        }
        List<String> interests = interests(profile);
        if (sections.contains("news") && !interests.isEmpty()) {
            gather.put("news", gatherNews(interests));
        }

        ObjectNode payload = json.createObjectNode();
        payload.put("userText", msg.text() == null ? "" : msg.text());

        return coordinator.coordinate(
                        List.of(manifest.body(), skillBody()),
                        payload,
                        gather,
                        LlmChannel.DEFAULT)
                .flatMap(r -> publishBoard(msg, today, r.text(), r.gathered())
                        .map(link -> reply(withLink(r.text(), link), r.llmModel()))
                        .onErrorResume(e -> {
                            // A media/render hiccup must not sink the digest — hand back the text alone.
                            log.warn("briefing board store failed: {}", e.toString());
                            return Mono.just(reply(r.text(), r.llmModel()));
                        }))
                .onErrorResume(e -> {
                    log.warn("briefing synthesis failed: {}", e.toString());
                    return Mono.just(reply("Собрал данные, но не смог оформить брифинг. Попробуйте позже.", null));
                });
    }

    /** Render the synthesized text (+ the gathered news links as provenance) to an HTML board, store it. */
    private Mono<String> publishBoard(NormalizedMessage msg, LocalDate today, String text, JsonNode gathered) {
        Doc.Builder b = Doc.builder("Брифинг на сегодня")
                .kicker("Утро · Брифинг · " + today)
                .subtitle("Погода · повестка · финансы · новости")
                .section("Сегодня", DeliverablePublisher.splitParagraphs(text));
        for (Doc.LinkItem l : newsLinks(gathered)) {
            b.link(l.label(), l.url(), l.note());
        }
        return publisher.publish(msg.householdId(), msg.userId(), b.build());
    }

    /** Flatten {@code context.news[].hits[]} into a deduped, capped provenance link list for the board. */
    private List<Doc.LinkItem> newsLinks(JsonNode gathered) {
        List<Doc.LinkItem> links = new ArrayList<>();
        if (gathered == null) {
            return links;
        }
        JsonNode newsArr = gathered.get("news");
        if (newsArr == null || !newsArr.isArray()) {
            return links;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode topic : newsArr) {
            JsonNode hits = topic.get("hits");
            if (hits == null || !hits.isArray()) {
                continue;
            }
            for (JsonNode hit : hits) {
                String url = hit.hasNonNull("url") ? hit.get("url").asText() : null;
                if (url == null || url.isBlank() || !seen.add(url)) {
                    continue;
                }
                String title = hit.hasNonNull("title") ? hit.get("title").asText() : url;
                String snippet = hit.hasNonNull("snippet") ? hit.get("snippet").asText() : null;
                links.add(new Doc.LinkItem(title, url, snippet));
                if (links.size() >= MAX_LINKS) {
                    return links;
                }
            }
        }
        return links;
    }

    private static String withLink(String text, String link) {
        if (link == null || link.isBlank()) {
            return text;
        }
        return text + "\n\nОткрыть брифинг: " + link;
    }

    /** One search per interest, folded into an array of {@code {topic, hits:[{title, url, snippet}]}}. */
    private Mono<JsonNode> gatherNews(List<String> interests) {
        return Flux.fromIterable(interests)
                .flatMap(topic -> news.search(topic, NEWS_PER_TOPIC)
                        .map(res -> topicNode(topic, res))
                        .onErrorResume(e -> {
                            log.warn("news search failed for '{}': {}", topic, e.toString());
                            return Mono.empty();
                        }))
                .collectList()
                .map(nodes -> {
                    ArrayNode arr = json.createArrayNode();
                    nodes.forEach(arr::add);
                    return (JsonNode) arr;
                });
    }

    private ObjectNode topicNode(String topic, WebSearchResult res) {
        ObjectNode node = json.createObjectNode();
        node.put("topic", topic);
        ArrayNode hits = node.putArray("hits");
        List<WebSearchHit> found = res == null || res.hits() == null ? List.of() : res.hits();
        found.stream().limit(NEWS_PER_TOPIC).forEach(h -> {
            ObjectNode hn = hits.addObject();
            if (h.title() != null) hn.put("title", h.title());
            hn.put("url", h.url());
            if (h.snippet() != null) hn.put("snippet", h.snippet());
        });
        return node;
    }

    private static Set<String> enabledSections(BriefingProfileDto profile) {
        JsonNode arr = profile.sections();
        if (arr == null || !arr.isArray() || arr.isEmpty()) {
            return DEFAULT_SECTIONS;
        }
        Set<String> out = new LinkedHashSet<>();
        arr.forEach(n -> out.add(n.asText().toLowerCase(Locale.ROOT)));
        return out;
    }

    private static List<String> interests(BriefingProfileDto profile) {
        JsonNode arr = profile.interests();
        List<String> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode n : arr) {
                String tag = n.asText();
                if (tag != null && !tag.isBlank()) out.add(tag);
                if (out.size() >= MAX_TOPICS) break;
            }
        }
        return out;
    }

    /** Profile timezone (IANA), falling back to UTC when unset or unparseable — day windows must not fail. */
    private static ZoneId zone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return ZoneOffset.UTC;
        }
        try {
            return ZoneId.of(timezone);
        } catch (Exception e) {
            return ZoneOffset.UTC;
        }
    }

    private static BriefingProfileDto empty(NormalizedMessage msg) {
        return new BriefingProfileDto(null, msg.householdId(), msg.userId(), null, null, null, null,
                null, null, null, null, null, null);
    }

    private String skillBody() {
        return skills.all().stream()
                .filter(s -> SKILL_NAME.equals(s.name()))
                .map(Skill::body)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "briefing-composer SKILL.md not loaded — check skills-classpath"));
    }

    private IntentResponse reply(String text, String model) {
        return new IntentResponse(manifest.name(), text, model);
    }
}
