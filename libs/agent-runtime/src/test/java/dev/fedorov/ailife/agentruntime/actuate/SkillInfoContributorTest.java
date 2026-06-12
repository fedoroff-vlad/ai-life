package dev.fedorov.ailife.agentruntime.actuate;

import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.info.Info;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings("unchecked")
class SkillInfoContributorTest {

    @Test
    void exposesSortedCountNamesAndTriggers() {
        SkillRegistry registry = new SkillRegistry(List.of(
                new Skill("budget-alerts", "d", "1", "finance",
                        List.of("budget.alert"), List.of("en"), "body"),
                new Skill("transaction-categorizer", "d", "1", "finance",
                        List.of("transaction.uncategorised"), List.of("en"), "body")));

        Info.Builder builder = new Info.Builder();
        new SkillInfoContributor(registry).contribute(builder);
        Map<String, Object> skills = (Map<String, Object>) builder.build().getDetails().get("skills");

        assertThat(skills.get("count")).isEqualTo(2);
        // Names sorted alphabetically regardless of registry order.
        assertThat((List<String>) skills.get("names"))
                .containsExactly("budget-alerts", "transaction-categorizer");
        assertThat((List<String>) skills.get("triggers"))
                .containsExactly("budget.alert", "transaction.uncategorised");
    }

    @Test
    void emptyRegistryReportsZeroAndEmptyLists() {
        Info.Builder builder = new Info.Builder();
        new SkillInfoContributor(new SkillRegistry(List.of())).contribute(builder);
        Map<String, Object> skills = (Map<String, Object>) builder.build().getDetails().get("skills");

        assertThat(skills.get("count")).isEqualTo(0);
        assertThat((List<String>) skills.get("names")).isEmpty();
        assertThat((List<String>) skills.get("triggers")).isEmpty();
    }

    @Test
    void deduplicatesAndSortsTriggersAcrossSkills() {
        // A skill with a null trigger list must not NPE; shared/duplicate trigger
        // kinds across skills collapse to a distinct, sorted list.
        SkillRegistry registry = new SkillRegistry(List.of(
                new Skill("a", "d", "1", "x", List.of("z.kind", "a.kind"), List.of("en"), "b"),
                new Skill("b", "d", "1", "x", List.of("a.kind"), List.of("en"), "b"),
                new Skill("c", "d", "1", "x", null, List.of("en"), "b")));

        Info.Builder builder = new Info.Builder();
        new SkillInfoContributor(registry).contribute(builder);
        Map<String, Object> skills = (Map<String, Object>) builder.build().getDetails().get("skills");

        assertThat(skills.get("count")).isEqualTo(3);
        assertThat((List<String>) skills.get("triggers")).containsExactly("a.kind", "z.kind");
    }
}
