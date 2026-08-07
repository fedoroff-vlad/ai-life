package dev.fedorov.ailife.sharing;

import dev.fedorov.ailife.contracts.common.SharingScope;
import dev.fedorov.ailife.contracts.profile.HouseholdRoutingDto;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The reusable <b>confirm-on-ambiguity</b> plumbing for the sharing capability (ADR-0002 item 8, DS-N).
 * When {@link SharingResolver#resolve} yields a {@link SharingResolution.NeedsConfirm} — the default is
 * genuinely ambiguous — a domain agent can defer its write and ask the owner "личное или общее?"; this class
 * owns the two mechanical halves so every sharing domain (calendar/finance/tasks/nutrition/docs) reuses them
 * instead of hand-rolling the pending-action envelope + reply parsing five times:
 *
 * <ol>
 *   <li><b>Ask</b> — {@link #pendingAction} serialises the {@code NeedsConfirm} (routing + context + fallback)
 *       plus an opaque per-domain {@code stash} into the conversation-state {@code pendingAction} envelope the
 *       orchestrator locks on; {@link #question} is the standard Russian prompt.</li>
 *   <li><b>Resume</b> — {@link #resume} parses the owner's reply into a {@link SharingScope}, rebuilds the
 *       {@code NeedsConfirm}, calls {@link SharingResolver#confirm} (which records the now-explicit choice as
 *       the learn signal and picks the household), and hands the resolved household + the stash to a
 *       per-domain {@link Finish} callback — the <b>only</b> domain-specific part (capture the task / create
 *       the account / archive the document). An unparseable reply keeps the lock and re-asks.</li>
 * </ol>
 *
 * The privacy mechanism stays in {@link SharingResolver}; this is pure conversation plumbing over it. The
 * domain supplies only how it detects ambiguity (its {@link DefaultSharingPolicy#maybeDecide}) and what to do
 * with the confirmed household ({@link Finish}) — so DS-N-2…N are thin, mirroring the DS-4 wiring.
 */
public class SharingConfirm {

    /** The {@code pendingAction.flow} discriminator an agent's resume controller dispatches on. */
    public static final String FLOW = "sharing-confirm";

    // Stem cues — matched as substrings against the lower-cased reply. Deliberately conservative.
    private static final List<String> SHARED_CUES = List.of("общ", "семей", "совмест", "shared", "family");
    private static final List<String> PRIVATE_CUES = List.of("лич", "персонал", "private", "personal");

    private final SharingResolver resolver;
    private final ObjectMapper json;

    public SharingConfirm(SharingResolver resolver, ObjectMapper json) {
        this.resolver = resolver;
        this.json = json;
    }

    /**
     * Build the {@code pendingAction} the agent returns so the orchestrator locks the conversation: the
     * {@link SharingConfirm#FLOW} discriminator + the serialised {@code NeedsConfirm} + the domain's opaque
     * {@code stash} (e.g. the task title/note) handed back to {@link Finish} on resume.
     */
    public ObjectNode pendingAction(SharingResolution.NeedsConfirm needsConfirm, JsonNode stash) {
        ObjectNode pending = json.createObjectNode();
        pending.put("flow", FLOW);
        pending.set("routing", json.valueToTree(needsConfirm.routing()));
        pending.set("ctx", json.valueToTree(needsConfirm.ctx()));
        if (needsConfirm.fallbackHousehold() != null) {
            pending.put("fallback", needsConfirm.fallbackHousehold().toString());
        }
        if (stash != null) {
            pending.set("stash", stash);
        }
        return pending;
    }

    /** The standard ask, e.g. {@code question("«вынести мусор»")} → "«вынести мусор» — это личное или общее? …". */
    public String question(String itemLabel) {
        return itemLabel + " — это личное или общее? Ответьте «личное» или «общее».";
    }

    /**
     * Resume after the owner replies. Parses личное/общее → a {@link SharingScope}; on a clear answer,
     * {@link SharingResolver#confirm} finishes the routing + records the learn signal and {@link Finish}
     * performs the deferred domain write → {@link Reply} with no {@code keepPending} (lock cleared). An
     * unparseable reply re-asks with the same pending (lock kept).
     */
    public Mono<Reply> resume(JsonNode pending, String replyText, Finish finish) {
        Optional<SharingScope> chosen = parseScope(replyText);
        if (chosen.isEmpty()) {
            return Mono.just(new Reply(
                    "Не понял — это личное или общее? Ответьте «личное» или «общее».", pending));
        }
        SharingResolution.NeedsConfirm needsConfirm = needsConfirm(pending);
        UUID household = resolver.confirm(needsConfirm, chosen.get());
        JsonNode stash = pending == null ? null : pending.get("stash");
        return finish.apply(household, chosen.get(), stash)
                .map(text -> new Reply(text, null));
    }

    /** Rebuild the {@link SharingResolution.NeedsConfirm} the ask serialised into {@code pending}. */
    SharingResolution.NeedsConfirm needsConfirm(JsonNode pending) {
        HouseholdRoutingDto routing = json.treeToValue(pending.get("routing"), HouseholdRoutingDto.class);
        SharingContext ctx = json.treeToValue(pending.get("ctx"), SharingContext.class);
        UUID fallback = pending.hasNonNull("fallback")
                ? UUID.fromString(pending.get("fallback").asString()) : null;
        return new SharingResolution.NeedsConfirm(routing, ctx, fallback);
    }

    /** личное → PRIVATE, общее → SHARED; empty when the reply names neither (or both) → re-ask. */
    static Optional<SharingScope> parseScope(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String t = text.trim().toLowerCase(Locale.ROOT);
        boolean shared = SHARED_CUES.stream().anyMatch(t::contains);
        boolean personal = PRIVATE_CUES.stream().anyMatch(t::contains);
        if (shared == personal) {
            return Optional.empty();
        }
        return Optional.of(shared ? SharingScope.SHARED : SharingScope.PRIVATE);
    }

    /** The agent-neutral resume result: the reply text + {@code keepPending} (non-null → re-lock / re-ask). */
    public record Reply(String text, JsonNode keepPending) {
    }

    /**
     * The one domain-specific hook: given the confirmed household, the owner's chosen scope, and the stash
     * the ask stored, perform the deferred write and return the confirmation text.
     */
    @FunctionalInterface
    public interface Finish {
        Mono<String> apply(UUID household, SharingScope chosen, JsonNode stash);
    }
}
