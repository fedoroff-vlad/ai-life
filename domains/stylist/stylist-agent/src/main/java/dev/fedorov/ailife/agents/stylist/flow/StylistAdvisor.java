package dev.fedorov.ailife.agents.stylist.flow;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.coordinate.Coordinator;
import dev.fedorov.ailife.agentruntime.deliver.DeliverablePublisher;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.stylist.http.ImageGenClient;
import dev.fedorov.ailife.agents.stylist.http.WardrobeReadClient;
import dev.fedorov.ailife.agents.stylist.http.WebSearchClient;
import dev.fedorov.ailife.docrender.Doc;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.wardrobe.WardrobeItemDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The capsule advisor (ST-e) — the stylist's reason for existing. On a capsule request it gathers,
 * in parallel on the shared {@link Coordinator}: the household's <b>wardrobe</b> items, the person's
 * <b>style profile</b>, and current <b>trends</b> (via {@code mcp-web}); the season is computed
 * locally and added to the payload. The Coordinator folds the successful sources into a
 * {@code context} and runs one LLM synthesis from {@code [AGENT.md, capsule-advisor SKILL.md] +
 * {payload(request, season), context}} → a capsule, which is rendered as a responsive HTML page
 * (a generated lookbook illustration via the shared {@code mcp-image-gen} capability + the garment
 * photos) through the {@link DocRenderer} seam, stored in media-service, and returned as a link.
 *
 * <p>Per-step soft-fail (a trends/profile outage just drops that constraint). An empty wardrobe →
 * an invite to catalogue first (no LLM call). Synthesis failure → a friendly message.
 */
@Component
public class StylistAdvisor {

    private static final Logger log = LoggerFactory.getLogger(StylistAdvisor.class);
    private static final String SKILL_NAME = "capsule-advisor";
    private static final int TREND_LIMIT = 5;
    private static final int MAX_GALLERY = 12;

    private final Coordinator coordinator;
    private final WardrobeReadClient wardrobe;
    private final WebSearchClient web;
    private final ImageGenClient imageGen;
    private final DeliverablePublisher publisher;
    private final SkillRegistry skills;
    private final AgentManifest manifest;
    private final ObjectMapper json;

    public StylistAdvisor(Coordinator coordinator,
                          WardrobeReadClient wardrobe,
                          WebSearchClient web,
                          ImageGenClient imageGen,
                          DeliverablePublisher publisher,
                          SkillRegistry skills,
                          AgentManifest manifest,
                          ObjectMapper json) {
        this.coordinator = coordinator;
        this.wardrobe = wardrobe;
        this.web = web;
        this.imageGen = imageGen;
        this.publisher = publisher;
        this.skills = skills;
        this.manifest = manifest;
        this.json = json;
    }

    public Mono<IntentResponse> advise(NormalizedMessage msg) {
        String season = currentSeason();
        return wardrobe.listItems(msg.householdId(), null)
                .onErrorReturn(List.of())
                .flatMap(items -> {
                    if (items.isEmpty()) {
                        return Mono.just(reply(
                                "В вашем гардеробе пока нет вещей. Пришлите фото одежды — я их "
                                        + "каталогизирую, и тогда соберу капсулу.", null));
                    }
                    return synthesize(msg, items, season);
                })
                .onErrorResume(e -> {
                    log.warn("capsule advise failed: {}", e.toString());
                    return Mono.just(reply("Не получилось собрать капсулу. Попробуйте позже.", null));
                });
    }

    private Mono<IntentResponse> synthesize(NormalizedMessage msg, List<WardrobeItemDto> items, String season) {
        Map<String, Mono<JsonNode>> gather = new LinkedHashMap<>();
        gather.put("wardrobe", Mono.just(json.valueToTree(items)));
        gather.put("profile", wardrobe.getProfile(msg.householdId(), msg.userId())
                .map(p -> (JsonNode) json.valueToTree(p)));
        gather.put("trends", web.search(trendQuery(season, msg.text()), TREND_LIMIT)
                .map(r -> (JsonNode) json.valueToTree(r)));

        ObjectNode payload = json.createObjectNode();
        if (msg.text() != null && !msg.text().isBlank()) payload.put("request", msg.text());
        payload.put("season", season);

        return coordinator.coordinate(
                        List.of(manifest.body(), skillBody()),
                        payload,
                        gather,
                        LlmChannel.DEFAULT)
                .flatMap(result -> store(msg, items, season, result.text())
                        .map(link -> reply(DeliverablePublisher.summary(result.text(), "Собрал капсулу из вашего гардероба.")
                                + "\n\nКапсула: " + link, result.llmModel()))
                        .onErrorResume(e -> {
                            log.warn("capsule render/store failed: {}", e.toString());
                            // Still hand back the textual capsule if the page couldn't be stored.
                            return Mono.just(reply(result.text(), result.llmModel()));
                        }));
    }

    /**
     * Render the capsule board (editorial chrome + a generated lookbook illustration + the looks + a
     * garment-photo gallery), store, link. The illustration comes from the shared {@code mcp-image-gen}
     * capability (a placeholder stub today, a real GPU model once flipped by config) — soft-failed, so
     * a generation outage just drops the visual and the textual capsule still ships.
     */
    private Mono<String> store(NormalizedMessage msg, List<WardrobeItemDto> items, String season, String capsuleText) {
        return illustrate(msg, capsuleText, season).flatMap(featured -> {
            Doc.Builder b = Doc.builder("Капсула")
                    .kicker("Curated · Strategic · Aligned")
                    .subtitle(subtitle(season, msg.text()))
                    .section("Образы", DeliverablePublisher.splitParagraphs(capsuleText));
            featured.ifPresent(b::featured);
            for (String url : galleryUrls(items)) b.galleryImage(url);
            return publisher.publish(msg.householdId(), msg.userId(), b.build());
        });
    }

    /** Generate the board's featured lookbook illustration; soft-fail to no illustration. */
    private Mono<Optional<String>> illustrate(NormalizedMessage msg, String capsuleText, String season) {
        return imageGen.generate(msg.householdId(), msg.userId(), illustrationPrompt(capsuleText, season))
                .mapNotNull(r -> publisher.mediaUrl(r.mediaId()))
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .onErrorResume(e -> {
                    log.warn("capsule illustration failed (dropping it): {}", e.toString());
                    return Mono.just(Optional.empty());
                });
    }

    /** A concise editorial-illustration prompt grounded in the synthesized capsule + season. */
    private static String illustrationPrompt(String capsuleText, String season) {
        String looks = DeliverablePublisher.summary(capsuleText, "Собрал капсулу из вашего гардероба.");
        return "Editorial fashion illustration, luxury minimalist style, neutral warm-beige palette, "
                + "a curated seasonal capsule for " + season + ": " + looks;
    }

    private List<String> galleryUrls(List<WardrobeItemDto> items) {
        List<String> urls = new ArrayList<>();
        for (WardrobeItemDto item : items) {
            if (item.imageMediaId() == null) continue;
            urls.add(publisher.mediaUrl(item.imageMediaId()));
            if (urls.size() >= MAX_GALLERY) break;
        }
        return urls;
    }

    private static String trendQuery(String season, String request) {
        String r = (request == null || request.isBlank()) ? "" : " " + request;
        return "модные тренды одежда " + season + r;
    }

    /** Board subtitle: the season plus the user's occasion/request when they gave one. */
    private static String subtitle(String season, String request) {
        StringBuilder sb = new StringBuilder("Сезон: ").append(season);
        if (request != null && !request.isBlank()) sb.append(" · ").append(request.strip());
        return sb.toString();
    }

    /** Northern-hemisphere season by month (RU label) — a deterministic payload hint, no model needed. */
    private static String currentSeason() {
        int month = LocalDate.now(ZoneOffset.UTC).getMonthValue();
        return switch (month) {
            case 12, 1, 2 -> "зима";
            case 3, 4, 5 -> "весна";
            case 6, 7, 8 -> "лето";
            default -> "осень";
        };
    }

    private String skillBody() {
        return skills.all().stream()
                .filter(s -> SKILL_NAME.equals(s.name()))
                .map(Skill::body)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "capsule-advisor SKILL.md not loaded — check skills-classpath"));
    }

    private IntentResponse reply(String text, String model) {
        return new IntentResponse(manifest.name(), text, model);
    }
}
