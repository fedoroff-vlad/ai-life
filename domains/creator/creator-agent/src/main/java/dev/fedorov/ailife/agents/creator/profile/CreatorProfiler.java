package dev.fedorov.ailife.agents.creator.profile;

import tools.jackson.databind.JsonNode;
import dev.fedorov.ailife.agentruntime.profile.PersonalizationProfiler;
import dev.fedorov.ailife.agentruntime.profile.ProfileSpec;
import dev.fedorov.ailife.agents.creator.http.CreatorProfileClient;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.creator.CreatorProfileDto;
import dev.fedorov.ailife.contracts.creator.SetCreatorProfileInput;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Turns a typed message describing a creator profile into a stored {@code creator_profile} row (CR-c1).
 * Since ADR-0005 (slice 4) this is a thin {@link ProfileSpec} on the shared {@link PersonalizationProfiler}
 * template: the LLM extract via the {@code creator-profiler} SKILL, the parse, the {@code self}/{@code
 * household} scope resolution, and the write soft-fail all live in the template. Only the domain-specific
 * field mapping into {@link SetCreatorProfileInput} + the Russian reply wording stay here. Mirrors
 * briefing's reference retrofit, minus any post-step.
 */
@Component
public class CreatorProfiler implements ProfileSpec<SetCreatorProfileInput, CreatorProfileDto> {

    private static final String SKILL_NAME = "creator-profiler";

    private final PersonalizationProfiler template;
    private final CreatorProfileClient profiles;

    public CreatorProfiler(PersonalizationProfiler template, CreatorProfileClient profiles) {
        this.template = template;
        this.profiles = profiles;
    }

    public Mono<IntentResponse> setProfile(NormalizedMessage msg) {
        return template.setProfile(this, msg);
    }

    @Override
    public String skillName() {
        return SKILL_NAME;
    }

    @Override
    public Mono<SetCreatorProfileInput> build(JsonNode draft, UUID ownerId, NormalizedMessage msg) {
        return Mono.just(new SetCreatorProfileInput(
                msg.householdId(),
                ownerId,                           // scoped by the template: self → sender, household → null
                text(draft, "niche"),
                text(draft, "audience"),
                text(draft, "tone"),
                draft.hasNonNull("platforms") ? draft.get("platforms") : null,
                text(draft, "goals"),
                draft.hasNonNull("guardrails") ? draft.get("guardrails") : null,
                text(draft, "notes")));
    }

    @Override
    public Mono<CreatorProfileDto> write(SetCreatorProfileInput input) {
        return profiles.set(input);
    }

    @Override
    public String success(boolean household, CreatorProfileDto saved) {
        StringBuilder sb = new StringBuilder(household
                ? "Обновил общий профиль создателя" : "Обновил ваш профиль создателя");
        if (saved.niche() != null && !saved.niche().isBlank()) {
            sb.append(" (ниша: ").append(saved.niche()).append(")");
        }
        sb.append(". Поправьте, если что-то не так.");
        return sb.toString();
    }

    @Override
    public String unparseable() {
        return "Не понял профиль. Напишите, например: "
                + "«моя ниша — английский для IT, аудитория — джуны, тон дружелюбный».";
    }

    @Override
    public String failure() {
        return "Не удалось обновить профиль создателя. Попробуйте позже.";
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asString() : null;
    }
}
