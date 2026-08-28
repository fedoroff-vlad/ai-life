package dev.fedorov.ailife.agents.nutritionist.profile;

import tools.jackson.databind.JsonNode;
import dev.fedorov.ailife.agentruntime.profile.PersonalizationProfiler;
import dev.fedorov.ailife.agentruntime.profile.ProfileSpec;
import dev.fedorov.ailife.agents.nutritionist.http.DietProfileClient;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.nutrition.DietProfileDto;
import dev.fedorov.ailife.contracts.nutrition.SetDietProfileInput;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Turns a typed message stating dietary goals / restrictions into a stored {@code diet_profile} row
 * (NU-d1). Since ADR-0005 (slice 5) this is a thin {@link ProfileSpec} on the shared
 * {@link PersonalizationProfiler} template: the LLM extract via the {@code diet-profiler} SKILL, the
 * parse, the {@code self}/{@code household} scope resolution, and the write soft-fail all live in the
 * template. Only the domain field mapping into {@link SetDietProfileInput}, the reply wording, and the
 * write why-trace (#485/G2) stay here.
 *
 * <p>Named ad-hoc people (wife / infant) are carried inline in the ration request (NU-g), not stored as
 * separate owner rows — {@code diet_profile.owner_id} references a {@code core.users} account.
 */
@Component
public class DietProfiler implements ProfileSpec<SetDietProfileInput, DietProfileDto> {

    private static final String SKILL_NAME = "diet-profiler";

    private final PersonalizationProfiler template;
    private final DietProfileClient profiles;

    public DietProfiler(PersonalizationProfiler template, DietProfileClient profiles) {
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
    public Mono<SetDietProfileInput> build(JsonNode draft, UUID ownerId, NormalizedMessage msg) {
        return Mono.just(new SetDietProfileInput(
                msg.householdId(),
                ownerId,                           // scoped by the template: self → sender, household → null
                intOrNull(draft, "goal_kcal"),
                decimalOrNull(draft, "goal_protein_g"),
                decimalOrNull(draft, "goal_fat_g"),
                decimalOrNull(draft, "goal_carbs_g"),
                draft.hasNonNull("restrictions") ? draft.get("restrictions") : null,
                draft.hasNonNull("tastes") ? draft.get("tastes") : null,
                text(draft, "notes")));
    }

    @Override
    public Mono<DietProfileDto> write(SetDietProfileInput input) {
        return profiles.set(input);
    }

    @Override
    public String success(boolean household, DietProfileDto saved) {
        StringBuilder sb = new StringBuilder(household
                ? "Обновил профиль питания семьи" : "Обновил ваш профиль питания");
        if (saved.goalKcal() != null) {
            sb.append(" (цель ~").append(saved.goalKcal()).append(" ккал/день)");
        }
        sb.append(". Поправьте, если что-то не так.");
        return sb.toString();
    }

    @Override
    public String unparseable() {
        return "Не понял цели питания. Напишите, например: «моя цель 2000 ккал, белок 140 г, без орехов».";
    }

    @Override
    public String failure() {
        return "Не удалось обновить профиль питания. Попробуйте позже.";
    }

    /** Why-trace (#485/G2): setting the diet profile is a write — a payload-free "what I did" line. */
    @Override
    public String trace() {
        return "wrote: updated the diet profile";
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asString() : null;
    }

    private static Integer intOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) && node.get(field).isNumber() ? node.get(field).asInt() : null;
    }

    private static BigDecimal decimalOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) && node.get(field).isNumber()
                ? node.get(field).decimalValue() : null;
    }
}
