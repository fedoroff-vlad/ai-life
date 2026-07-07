package dev.fedorov.ailife.agents.finance.category;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.finance.http.CategoryClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.finance.FinCategoryDto;
import dev.fedorov.ailife.contracts.finance.UpsertCategoryInput;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * On-request <b>chat-driven category management</b> (#291) — create and group finance categories from
 * a plain-language message ("заведи категорию «Кофейни» в группе Еда", "сгруппируй Такси и Метро под
 * Транспорт"). Routed here by the {@link dev.fedorov.ailife.agents.finance.intent.IntentRouter}
 * {@code category} action.
 *
 * <p>Flow: list the household's existing categories (so a parent can be resolved by name and we don't
 * duplicate), ask the LLM — with the {@code category-manager} SKILL as the instruction and the
 * existing categories as context — for a strict-JSON plan ({@code {categories:[{name,kind,parent?}]}}),
 * then apply it deterministically: parents first, resolving each {@code parent} name to an id from the
 * existing set plus anything created earlier in the same request, via mcp-finance's
 * {@code POST /internal/category} ({@code upsert_category} under the hood). Per-category soft-fail (a
 * duplicate or bad row is skipped, the rest proceed). Returns a short Russian confirmation.
 */
@Component
public class CategoryManager {

    private static final Logger log = LoggerFactory.getLogger(CategoryManager.class);
    private static final String SKILL_NAME = "category-manager";
    private static final int MAX_CATEGORIES = 20;
    private static final Set<String> KINDS = Set.of("income", "expense", "transfer");

    private final LlmClient llm;
    private final CategoryClient categories;
    private final SkillRegistry skills;
    private final AgentManifest manifest;
    private final ObjectMapper json;

    public CategoryManager(LlmClient llm,
                           CategoryClient categories,
                           SkillRegistry skills,
                           AgentManifest manifest,
                           ObjectMapper json) {
        this.llm = llm;
        this.categories = categories;
        this.skills = skills;
        this.manifest = manifest;
        this.json = json;
    }

    public Mono<CategoryResult> manage(NormalizedMessage msg) {
        UUID household = msg == null ? null : msg.householdId();
        if (household == null) {
            return Mono.just(new CategoryResult(
                    "Не вижу, чей это бюджет — не могу изменить категории.", null));
        }
        return categories.list(household)
                .flatMap(existing -> planAndApply(msg, household, existing))
                .onErrorResume(e -> {
                    log.warn("category-manager failed for household {}: {}", household, e.toString());
                    return Mono.just(new CategoryResult(
                            "Не смог изменить категории. Попробуйте ещё раз чуть позже.", null));
                });
    }

    private Mono<CategoryResult> planAndApply(NormalizedMessage msg, UUID household, List<FinCategoryDto> existing) {
        ObjectNode userMsg = json.createObjectNode();
        userMsg.put("userText", msg.text() == null ? "" : msg.text());
        ArrayNode existingArr = userMsg.putArray("existingCategories");
        for (FinCategoryDto c : existing) {
            ObjectNode n = existingArr.addObject();
            n.put("name", c.name());
            n.put("kind", c.kind());
        }

        LlmChatRequest req = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.system(manifest.body()),
                LlmMessage.system(skillBody()),
                LlmMessage.user(userMsg.toString())));

        return llm.chat(req).flatMap(resp -> {
            String model = resp.model();
            List<Planned> plan = parsePlan(resp.content());
            if (plan.isEmpty()) {
                return Mono.just(new CategoryResult(
                        "Не понял, какие категории создать или сгруппировать. "
                                + "Скажите, например: «заведи категорию Кофейни в группе Еда».", model));
            }
            // Seed the name→id map with existing categories so a parent can be an existing one.
            Map<String, UUID> byName = new LinkedHashMap<>();
            for (FinCategoryDto c : existing) {
                if (c.name() != null) byName.put(c.name().toLowerCase(Locale.ROOT), c.id());
            }
            // Parents (no parent of their own) first, so a child's parent id is resolvable.
            List<Planned> ordered = new ArrayList<>();
            plan.stream().filter(p -> p.parent() == null).forEach(ordered::add);
            plan.stream().filter(p -> p.parent() != null).forEach(ordered::add);

            List<String> done = new ArrayList<>();
            return Flux.fromIterable(ordered)
                    .concatMap(p -> upsertOne(household, p, byName, done))
                    .then(Mono.fromSupplier(() -> new CategoryResult(summary(done), model)));
        });
    }

    /** Upsert one planned category, resolving its parent by name; per-category soft-fail. */
    private Mono<FinCategoryDto> upsertOne(UUID household, Planned p, Map<String, UUID> byName, List<String> done) {
        UUID parentId = p.parent() == null ? null : byName.get(p.parent().toLowerCase(Locale.ROOT));
        UpsertCategoryInput input = new UpsertCategoryInput(
                null, household, parentId, p.name(), p.kind(), null);
        return categories.upsert(input)
                .doOnNext(dto -> {
                    if (dto != null && dto.name() != null) {
                        byName.put(dto.name().toLowerCase(Locale.ROOT), dto.id());
                        done.add(parentId != null ? dto.name() + " (в группе «" + p.parent() + "»)" : dto.name());
                    }
                })
                .onErrorResume(e -> {
                    log.warn("category upsert failed for '{}': {}", p.name(), e.toString());
                    return Mono.empty();
                });
    }

    /** Parse the LLM plan; tolerant of a wrapping object or a bare array. */
    private List<Planned> parsePlan(String raw) {
        List<Planned> out = new ArrayList<>();
        if (raw == null) return out;
        String trimmed = raw.trim();
        int start = trimmed.indexOf('{');
        int arr = trimmed.indexOf('[');
        if (arr >= 0 && (start < 0 || arr < start)) start = arr; // bare array
        if (start < 0) return out;
        int end = Math.max(trimmed.lastIndexOf('}'), trimmed.lastIndexOf(']'));
        if (end <= start) return out;
        JsonNode node;
        try {
            node = json.readTree(trimmed.substring(start, end + 1));
        } catch (Exception e) {
            return out;
        }
        JsonNode arrayNode = node.isArray() ? node : node.path("categories");
        if (!arrayNode.isArray()) return out;
        for (JsonNode item : arrayNode) {
            String name = item.path("name").asText("").trim();
            if (name.isEmpty()) continue;
            String kind = item.path("kind").asText("expense").trim().toLowerCase(Locale.ROOT);
            if (!KINDS.contains(kind)) kind = "expense";
            String parent = item.hasNonNull("parent") ? item.get("parent").asText().trim() : null;
            if (parent != null && (parent.isEmpty() || parent.equalsIgnoreCase(name))) parent = null;
            out.add(new Planned(name, kind, parent));
            if (out.size() >= MAX_CATEGORIES) break;
        }
        return out;
    }

    private String summary(List<String> done) {
        if (done.isEmpty()) {
            return "Ничего не изменил — категории уже есть или не удалось их создать.";
        }
        return "Готово. Категории: " + String.join(", ", done) + ".";
    }

    private String skillBody() {
        return skills.all().stream()
                .filter(s -> SKILL_NAME.equals(s.name()))
                .map(Skill::body)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "category-manager SKILL.md not loaded — check skills-classpath"));
    }

    /** One planned category: its name, kind (income|expense|transfer), and optional parent name. */
    private record Planned(String name, String kind, String parent) {
    }

    /** The chat reply (a short confirmation) plus the model that produced the plan. */
    public record CategoryResult(String text, String model) {
    }
}
