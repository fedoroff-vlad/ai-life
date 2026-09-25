package dev.fedorov.ailife.agents.inventory.edit;

import dev.fedorov.ailife.agentruntime.intent.CandidateView;
import dev.fedorov.ailife.agentruntime.intent.Phrasing;
import dev.fedorov.ailife.agentruntime.intent.PickConfirmActRunner;
import dev.fedorov.ailife.agentruntime.intent.TargetedActionFlow;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.inventory.http.InventoryClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.agent.ResumeRequest;
import dev.fedorov.ailife.contracts.inventory.ContainerDto;
import dev.fedorov.ailife.contracts.inventory.SaveContainerInput;
import dev.fedorov.ailife.contracts.inventory.SaveZoneInput;
import dev.fedorov.ailife.llm.LlmClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Correcting a container that already exists (IN-g): it physically <b>moved</b> ("коробка B-07 теперь на
 * даче"), it was <b>unpacked</b> ("распаковал B-07"), or it is <b>called</b> something else now.
 *
 * <p>Why this is the slice that makes the domain survive a real move: a store of boxes goes stale the
 * moment the boxes do. Without a correction path the owner's only options are to live with a wrong
 * answer to "где лежит X" or to stop trusting the domain — and the second is what actually happens.
 *
 * <p>Rides the shared {@link PickConfirmActRunner} (ADR-0004), so this class is only the inventory
 * adapter: the runner owns the pick → confirm → act loop, the LLM round-trip and the resume soft-fail.
 * It is an <b>edit</b> flow, not a delete, so it supplies its own {@link Phrasing} and uses the runner's
 * move seams — the changed fields ride the {@code pendingAction} (merged from the LLM selection node), a
 * picked box with nothing to change re-asks ({@link #missing}), and the resume needs a field to apply
 * ({@link #readyToAct}).
 *
 * <p><b>The printed identity is never touched.</b> {@code code} and {@code qr_token} stay put through
 * every edit here — that is the domain's founding invariant: a sticker already glued to a box must keep
 * resolving after the box is renamed, moved or emptied.
 */
@Component
public class ContainerEditor
        implements TargetedActionFlow<ContainerDto>, CandidateView<ContainerDto>, Phrasing<ContainerDto> {

    public static final String SKILL_NAME = "box-editor";
    /** pendingAction discriminator the inventory ResumeController dispatches on. */
    public static final String FLOW = "box-edit-confirm";

    /** A household has tens of containers — one page is the whole set to pick from. */
    private static final int CONTAINER_SCAN_LIMIT = 500;

    /**
     * The rename field the SKILL emits. Deliberately <b>not</b> {@code label}: the runner stores the
     * candidate's display label under {@code label} in the {@code pendingAction}, so reusing that name
     * would make every edit look like a rename to its own name.
     */
    private static final String NEW_LABEL = "newLabel";

    /** The container states the store accepts; anything else the model invents is dropped. */
    private static final Set<String> STATUSES = Set.of("open", "packed", "in_transit", "unpacked");

    private final InventoryClient inventory;
    private final PickConfirmActRunner<ContainerDto> runner;

    public ContainerEditor(LlmClient llm, InventoryClient inventory, SkillRegistry skills,
                           AgentManifest manifest, ObjectMapper json) {
        this.inventory = inventory;
        this.runner = new PickConfirmActRunner<>(llm, manifest, skills, json, this);
    }

    /** Turn 1: read the household's containers, let the LLM pick the box + what changed, ask to confirm. */
    public Mono<IntentResponse> edit(NormalizedMessage msg) {
        return runner.pick(msg);
    }

    /** Turn 2: an affirmative applies the change; anything else leaves the box alone. */
    public Mono<IntentResponse> resume(ResumeRequest req) {
        return runner.resume(req);
    }

    // ----- TargetedActionFlow -----------------------------------------------------------------------

    @Override
    public String skillName() {
        return SKILL_NAME;
    }

    @Override
    public String flow() {
        return FLOW;
    }

    @Override
    public Set<String> extraAffirmatives() {
        return Set.of("измени", "изменить", "перенеси", "переименуй", "обнови", "update");
    }

    @Override
    public Mono<List<ContainerDto>> candidates(NormalizedMessage msg) {
        return inventory.listContainers(msg.householdId(), CONTAINER_SCAN_LIMIT);
    }

    @Override
    public CandidateView<ContainerDto> view() {
        return this;
    }

    @Override
    public Phrasing<ContainerDto> phrasing() {
        return this;
    }

    /** Picked a box but named no change → ask what to change (no lock, nothing written). */
    @Override
    public Optional<String> missing(ContainerDto target, JsonNode pick) {
        return hasChange(pick)
                ? Optional.empty()
                : Optional.of("Что изменить у коробки " + name(target)
                        + " — зону, статус или название?");
    }

    /** The resume needs a field to apply, not just an id — else an affirmative would write nothing. */
    @Override
    public boolean readyToAct(JsonNode pending) {
        return hasChange(pending);
    }

    /**
     * Apply only the named fields. A new zone is resolved by <b>name</b> through the same upsert the
     * packing session uses (so "на даче" twice is one дача); a zone that cannot be resolved aborts the
     * edit rather than silently moving the box to nowhere — the owner would believe it had been filed.
     */
    @Override
    public Mono<Void> act(UUID targetId, JsonNode pending) {
        String status = status(pending);
        String label = string(pending, NEW_LABEL);
        return resolveZone(targetId, string(pending, "zone"))
                .flatMap(zoneId -> inventory.saveContainer(new SaveContainerInput(
                        targetId, null, null, zoneId.orElse(null), null, label, null, status, null, null)))
                .then();
    }

    /**
     * Upsert the spoken zone name → its id; empty when no zone was named (the other edits need none).
     *
     * <p>The household comes from the <b>container itself</b>, read back here, not from the confirming
     * message: a resume carries no envelope the runner would pass on, and a zone must in any case belong
     * to the household that owns the box — filing a family box's new place under someone's personal
     * household would hide it from everyone else.
     */
    private Mono<Optional<UUID>> resolveZone(UUID containerId, String zoneName) {
        if (zoneName == null) {
            return Mono.just(Optional.empty());
        }
        return inventory.getContainer(containerId)
                .flatMap(view -> inventory.saveZone(new SaveZoneInput(
                                view.container().householdId(), view.container().ownerId(),
                                zoneName, null, null, null))
                        .map(zone -> Optional.of(zone.id())));
    }

    // ----- CandidateView ----------------------------------------------------------------------------

    @Override
    public UUID id(ContainerDto c) {
        return c.id();
    }

    /** The stored label = the box's human name; the phrasing adds its own «…». */
    @Override
    public String label(ContainerDto c) {
        return name(c);
    }

    @Override
    public void describe(tools.jackson.databind.node.ObjectNode node, ContainerDto c) {
        node.put("code", c.code());
        if (c.label() != null) {
            node.put("label", c.label());
        }
        if (c.status() != null) {
            node.put("status", c.status());
        }
    }

    // ----- Phrasing ---------------------------------------------------------------------------------

    @Override
    public String askWhich() {
        return "Какую коробку изменить и что в ней поменять?";
    }

    @Override
    public String noHousehold() {
        return "Не понял, в каком хранилище искать коробку.";
    }

    @Override
    public String emptyPool() {
        return "Пока нет ни одной коробки, которую можно изменить.";
    }

    @Override
    public String noMatch() {
        return "Не нашёл такую коробку. Назовите её код (например B-07) или название.";
    }

    @Override
    public String readFailed() {
        return "Не смог изменить коробку. Попробуйте ещё раз позже.";
    }

    @Override
    public String notReady() {
        return "Нечего менять — повторите запрос, пожалуйста.";
    }

    @Override
    public String ambiguous(List<ContainerDto> picks) {
        StringBuilder sb = new StringBuilder("Нашёл несколько подходящих коробок — какую изменить?");
        for (ContainerDto c : picks) {
            sb.append("\n• ").append(name(c));
        }
        return sb.toString();
    }

    @Override
    public String confirm(ContainerDto target, JsonNode pick) {
        return "Изменить коробку " + name(target) + ": " + changes(pick)
                + "? Ответьте «да», чтобы применить.";
    }

    @Override
    public String declined(JsonNode pending) {
        return "Оставил коробку " + stored(pending) + " без изменений.";
    }

    @Override
    public String done(JsonNode pending) {
        return "Обновил коробку " + stored(pending) + ": " + changes(pending)
                + ". Этикетка на ней остаётся рабочей.";
    }

    @Override
    public String actFailed(JsonNode pending) {
        return "Не смог изменить коробку " + stored(pending) + " — возможно, её уже удалили.";
    }

    // ----- fields ----------------------------------------------------------------------------------

    /** The human-facing summary of what is about to change / just changed. */
    private static String changes(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        String zone = string(node, "zone");
        String status = status(node);
        String label = string(node, NEW_LABEL);
        if (zone != null) {
            append(sb, "зона → «" + zone + "»");
        }
        if (status != null) {
            append(sb, "статус → " + statusWord(status));
        }
        if (label != null) {
            append(sb, "название → «" + label + "»");
        }
        return sb.isEmpty() ? "ничего" : sb.toString();
    }

    private static void append(StringBuilder sb, String part) {
        if (!sb.isEmpty()) {
            sb.append(", ");
        }
        sb.append(part);
    }

    private static boolean hasChange(JsonNode node) {
        return string(node, "zone") != null || status(node) != null || string(node, NEW_LABEL) != null;
    }

    /** A status the store actually accepts — an invented one is dropped, never written. */
    private static String status(JsonNode node) {
        String raw = string(node, "status");
        if (raw == null) {
            return null;
        }
        String normalised = raw.toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        return STATUSES.contains(normalised) ? normalised : null;
    }

    private static String string(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        String s = node.get(field).asString().trim();
        return s.isEmpty() ? null : s;
    }

    private static UUID uuid(JsonNode node, String field) {
        String s = string(node, field);
        if (s == null) {
            return null;
        }
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String stored(JsonNode pending) {
        return pending == null ? "" : pending.path("label").asString("");
    }

    private static String name(ContainerDto c) {
        String code = c.code() == null ? "" : c.code();
        return (c.label() == null || c.label().isBlank()) ? code : code + " «" + c.label() + "»";
    }

    private static String statusWord(String status) {
        return switch (status) {
            case "open" -> "открыта";
            case "packed" -> "упакована";
            case "in_transit" -> "в пути";
            case "unpacked" -> "распакована";
            default -> status;
        };
    }
}
