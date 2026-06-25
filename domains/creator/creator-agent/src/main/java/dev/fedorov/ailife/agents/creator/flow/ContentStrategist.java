package dev.fedorov.ailife.agents.creator.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.coordinate.CoordinationResult;
import dev.fedorov.ailife.agentruntime.coordinate.Coordinator;
import dev.fedorov.ailife.agentruntime.deliver.DeliverablePublisher;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.creator.http.CreatorCacheClient;
import dev.fedorov.ailife.agents.creator.http.CreatorProfileClient;
import dev.fedorov.ailife.agents.creator.http.TrendGatherClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.creator.CreatorProfileDto;
import dev.fedorov.ailife.contracts.creator.SaveContentPieceInput;
import dev.fedorov.ailife.contracts.creator.SaveTrendInput;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.trends.TrendHit;
import dev.fedorov.ailife.docrender.Doc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The creator's headline flow (CR-d): trend → ideas → drafts. It resolves the person's creator track,
 * then gathers in parallel on the shared {@link Coordinator} — web search + YouTube + Reddit for the
 * niche (and a named RSS/Telegram feed if the request mentions one), each soft-failing independently.
 * The Coordinator folds the gathered {@code TrendHit} corpus into a {@code context} and runs ONE LLM
 * synthesis from {@code [AGENT.md, content-strategist SKILL.md] + {payload(profile, request), context}}
 * → fresh trends + post ideas + ready drafts + per-platform format recs. That text is rendered as an
 * HTML content-plan board through the shared {@link DocRenderer} ({@code libs/doc-render}) — with a
 * provenance links section built deterministically from the gathered hits — stored in media-service,
 * and returned as a link.
 *
 * <p>Token economy is structural: the gather is plain HTTP (no model cost); only the synthesis hits
 * the LLM. No niche (no profile + empty message) → an invite to set a profile (no LLM call). A
 * render/store failure still hands back the textual plan.
 */
@Component
public class ContentStrategist {

    private static final Logger log = LoggerFactory.getLogger(ContentStrategist.class);
    private static final String SKILL_NAME = "content-strategist";
    private static final int PER_SOURCE_LIMIT = 5;
    private static final int MAX_LINKS = 12;
    private static final List<String> SOURCE_ORDER = List.of("web", "youtube", "reddit", "feeds");

    private final Coordinator coordinator;
    private final CreatorProfileClient profiles;
    private final TrendGatherClient gather;
    private final CreatorCacheClient cache;
    private final DeliverablePublisher publisher;
    private final SkillRegistry skills;
    private final AgentManifest manifest;
    private final ObjectMapper json;

    public ContentStrategist(Coordinator coordinator,
                             CreatorProfileClient profiles,
                             TrendGatherClient gather,
                             CreatorCacheClient cache,
                             DeliverablePublisher publisher,
                             SkillRegistry skills,
                             AgentManifest manifest,
                             ObjectMapper json) {
        this.coordinator = coordinator;
        this.profiles = profiles;
        this.gather = gather;
        this.cache = cache;
        this.publisher = publisher;
        this.skills = skills;
        this.manifest = manifest;
        this.json = json;
    }

    public Mono<IntentResponse> run(NormalizedMessage msg) {
        return resolveProfile(msg)
                .flatMap(profile -> {
                    String niche = resolveNiche(profile.orElse(null), msg);
                    if (niche == null) {
                        return Mono.just(reply(
                                "О какой нише сделать контент-план? Напишите тему "
                                        + "(например «английский для IT») или задайте профиль создателя.", null));
                    }
                    return synthesize(msg, profile.orElse(null), niche);
                })
                .onErrorResume(e -> {
                    log.warn("content-strategist failed: {}", e.toString());
                    return Mono.just(reply("Не получилось собрать контент-план. Попробуйте позже.", null));
                });
    }

    private Mono<IntentResponse> synthesize(NormalizedMessage msg, CreatorProfileDto profile, String niche) {
        Map<String, Mono<JsonNode>> sources = new LinkedHashMap<>();
        sources.put("web", gather.web(niche, PER_SOURCE_LIMIT).map(this::toJson));
        sources.put("youtube", gather.youtube(niche, PER_SOURCE_LIMIT).map(this::toJson));
        sources.put("reddit", gather.reddit(niche, PER_SOURCE_LIMIT).map(this::toJson));
        String feed = feedToken(msg.text());
        if (feed != null) {
            sources.put("feeds", gather.feeds(feed, PER_SOURCE_LIMIT).map(this::toJson));
        }

        ObjectNode payload = json.createObjectNode();
        payload.put("niche", niche);
        if (msg.text() != null && !msg.text().isBlank()) payload.put("request", msg.text());
        if (profile != null) {
            putIf(payload, "audience", profile.audience());
            putIf(payload, "tone", profile.tone());
            putIf(payload, "goals", profile.goals());
            if (profile.platforms() != null && !profile.platforms().isNull()) {
                payload.set("platforms", profile.platforms());
            }
            if (profile.guardrails() != null && !profile.guardrails().isNull()) {
                payload.set("guardrails", profile.guardrails());
            }
        }

        return coordinator.coordinate(
                        List.of(manifest.body(), skillBody()),
                        payload,
                        sources,
                        LlmChannel.DEFAULT)
                .flatMap(result -> persist(msg, niche, result)
                        .then(store(msg, niche, result.text(), result.gathered())
                                .map(link -> reply(DeliverablePublisher.summary(result.text(), "Собрал контент-план по свежим трендам.")
                                        + "\n\nПолный контент-план: " + link, result.llmModel()))
                                .onErrorResume(e -> {
                                    log.warn("content-plan render/store failed: {}", e.toString());
                                    return Mono.just(reply(result.text(), result.llmModel()));
                                })));
    }

    /**
     * Persist the run into the {@code mcp-creator} cache (CR-e): the gathered {@code TrendHit} corpus
     * into {@code trend} (deduped per (owner, url) by the cache) and the synthesized plan into
     * {@code content_piece} as a draft, attributed to the speaker. Best-effort — the reply already
     * carries the deliverable, so a persist failure only logs and never breaks the user response.
     */
    private Mono<Void> persist(NormalizedMessage msg, String niche, CoordinationResult result) {
        List<SaveTrendInput> trendInputs = trendInputs(msg, result.gathered());
        Mono<Void> saveTrends = trendInputs.isEmpty()
                ? Mono.empty()
                : cache.saveTrends(trendInputs).then();
        SaveContentPieceInput draft = new SaveContentPieceInput(
                msg.householdId(), msg.userId(), "draft", null,
                "Контент-план: " + niche, result.text(), null, null, "new", null);
        return saveTrends
                .then(cache.saveContentPiece(draft).then())
                .onErrorResume(e -> {
                    log.warn("creator cache persist failed: {}", e.toString());
                    return Mono.empty();
                });
    }

    /** Flatten the gathered corpus into {@code SaveTrendInput}s, attributed to the speaker. */
    private List<SaveTrendInput> trendInputs(NormalizedMessage msg, JsonNode gathered) {
        List<SaveTrendInput> out = new ArrayList<>();
        if (gathered == null) {
            return out;
        }
        for (String source : SOURCE_ORDER) {
            JsonNode arr = gathered.get(source);
            if (arr == null || !arr.isArray()) {
                continue;
            }
            for (JsonNode node : arr) {
                TrendHit hit = json.convertValue(node, TrendHit.class);
                if (hit.title() == null || hit.title().isBlank()) {
                    continue;
                }
                out.add(new SaveTrendInput(msg.householdId(), msg.userId(), hit.source(),
                        hit.platform(), hit.title(), hit.url(), hit.summary(), hit.metrics(), null));
            }
        }
        return out;
    }

    /** Try the speaker's own track first, then the household-default; absent/error → empty. */
    private Mono<Optional<CreatorProfileDto>> resolveProfile(NormalizedMessage msg) {
        return profiles.get(msg.householdId(), msg.userId())
                .switchIfEmpty(Mono.defer(() -> profiles.get(msg.householdId(), null)))
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .onErrorReturn(Optional.empty());
    }

    private static String resolveNiche(CreatorProfileDto profile, NormalizedMessage msg) {
        if (profile != null && profile.niche() != null && !profile.niche().isBlank()) {
            return profile.niche().strip();
        }
        if (msg.text() != null && !msg.text().isBlank()) {
            return msg.text().strip();
        }
        return null;
    }

    /** Render the content-plan board (synthesis + a provenance links section), store it, return the link. */
    private Mono<String> store(NormalizedMessage msg, String niche, String planText, JsonNode gathered) {
        Doc.Builder doc = Doc.builder("Контент-план: " + niche)
                .kicker("Тренды · Идеи · Драфты")
                .subtitle("На основе свежих трендов из нескольких источников")
                .section("План", DeliverablePublisher.splitParagraphs(planText));
        for (Link l : provenance(gathered)) {
            doc.link(l.label(), l.url(), l.source());
        }
        return publisher.publish(msg.householdId(), msg.userId(), doc.build());
    }

    /** Build a deduped, capped link list from the gathered corpus so the board shows real provenance. */
    private List<Link> provenance(JsonNode gathered) {
        List<Link> links = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (gathered == null) {
            return links;
        }
        for (String source : SOURCE_ORDER) {
            JsonNode arr = gathered.get(source);
            if (arr == null || !arr.isArray()) {
                continue;
            }
            for (JsonNode hit : arr) {
                String url = text(hit, "url");
                if (url == null || !seen.add(url)) {
                    continue;
                }
                String title = text(hit, "title");
                links.add(new Link(title == null ? url : title, url, source));
                if (links.size() >= MAX_LINKS) {
                    return links;
                }
            }
        }
        return links;
    }

    private JsonNode toJson(List<?> list) {
        return json.valueToTree(list);
    }

    /**
     * The first feed-like token in the request — an RSS URL or a {@code @channel} handle — so the user
     * can fold a specific feed into the gather ("сделай контент-план, посмотри @itenglish"). Null when
     * none (the feeds source is then simply omitted — it has no keyword-search mode).
     */
    private static String feedToken(String text) {
        if (text == null) {
            return null;
        }
        for (String raw : text.split("\\s+")) {
            String t = raw.strip();
            if (t.startsWith("http://") || t.startsWith("https://")) {
                return t;
            }
            if (t.startsWith("@") && t.length() > 1) {
                return t.replaceAll("[.,;:!?)]+$", "");
            }
        }
        return null;
    }

    private static void putIf(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) node.put(field, value);
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText().strip();
        return s.isEmpty() ? null : s;
    }

    private String skillBody() {
        return skills.all().stream()
                .filter(s -> SKILL_NAME.equals(s.name()))
                .map(Skill::body)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "content-strategist SKILL.md not loaded — check skills-classpath"));
    }

    private IntentResponse reply(String text, String model) {
        return new IntentResponse(manifest.name(), text, model);
    }

    /** One provenance link: the visible label, the URL, and which source it came from. */
    private record Link(String label, String url, String source) {
    }
}
