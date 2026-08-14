package dev.fedorov.ailife.agents.travel.flow;

import dev.fedorov.ailife.agents.travel.flow.PackingListComposer.Category;
import dev.fedorov.ailife.agents.travel.flow.PackingListComposer.ClimateBand;
import dev.fedorov.ailife.agents.travel.flow.PackingListComposer.PackingContext;
import dev.fedorov.ailife.agents.travel.flow.PackingListComposer.PackingList;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deterministic packing-list engine (PK-a, #438) — the §Packing-list WHEN/THEN scenarios. Pure unit
 * (no Spring, no MockWebServer): the seed-and-combine of essentials + climate band + rest types +
 * companions is a correctness boundary, so it is asserted on structure directly.
 */
class PackingListComposerTest {

    /** Scenario: beach + hot + family → documents (always) + beach items + hot clothing + kids, deduped. */
    @Test
    void beachHotFamilyListHasBeachHotAndKidsItems() {
        PackingList list = PackingListComposer.compose(new PackingContext(
                List.of("beach"), "family", List.of(4), ClimateBand.HOT, false));

        List<String> items = flatten(list);
        assertThat(items).contains("Купальник", "Солнцезащитный крем");           // beach
        assertThat(items).contains("Головной убор от солнца");                    // hot clothing
        assertThat(categoryNames(list)).contains("Дети");                         // family + child age
        assertThat(items).contains("Игрушки и раскраски", "Детская аптечка");     // age 4 → young-child items
        assertThat(items).doesNotContain("Подгузники");                           // age 4 is not an infant
        assertThat(items).contains("Паспорт / загранпаспорт");                    // essentials always
        assertThat(list.weatherKnown()).isTrue();
        assertNoDuplicates(list);
    }

    /** Scenario: a sub-zero ski trip flips the clothing band to warm/thermal and carries no beachwear. */
    @Test
    void coldSkiListIsWarmAndHasNoBeachwear() {
        PackingList list = PackingListComposer.compose(new PackingContext(
                List.of("ski"), "couple", List.of(), ClimateBand.COLD, false));

        List<String> items = flatten(list);
        assertThat(items).contains("Термобельё", "Тёплый пуховик");               // cold clothing
        assertThat(items).contains("Горнолыжный костюм", "Горнолыжные перчатки"); // ski gear
        assertThat(items).doesNotContain("Купальник", "Пляжное полотенце");       // no beachwear
        assertThat(items).contains("Нарядный комплект для ужина");                // couple
    }

    /** Scenario: an unknown climate still returns a weather-neutral list, flagged (weatherKnown=false). */
    @Test
    void unknownClimateGivesNeutralListFlagged() {
        PackingList list = PackingListComposer.compose(new PackingContext(
                List.of("city"), "solo", List.of(), ClimateBand.UNKNOWN, false));

        assertThat(list.weatherKnown()).isFalse();
        assertThat(flatten(list)).contains("Базовый набор одежды (уточните прогноз погоды)");
        assertThat(flatten(list)).contains("Городской рюкзак или сумка");         // city rest type still applied
        assertThat(flatten(list)).contains("Паспорт / загранпаспорт");            // essentials always
    }

    /** Scenario: essentials are present regardless of inputs — even an entirely empty context. */
    @Test
    void essentialsAlwaysPresentForEmptyContext() {
        PackingList list = PackingListComposer.compose(new PackingContext(
                List.of(), null, List.of(), ClimateBand.UNKNOWN, false));

        List<String> items = flatten(list);
        assertThat(items).contains("Паспорт / загранпаспорт", "Билеты и посадочные талоны",
                "Банковские карты и наличные", "Телефон и зарядка");
        assertThat(categoryNames(list)).doesNotContain("Дети");                   // no family → no kids section
    }

    /** Wet season adds rain gear regardless of the temperature band. */
    @Test
    void wetSeasonAddsRainGear() {
        PackingList list = PackingListComposer.compose(new PackingContext(
                List.of("city"), "solo", List.of(), ClimateBand.MILD, true));

        assertThat(flatten(list)).contains("Дождевик или зонт", "Непромокаемая обувь");
    }

    // --- helpers ---

    private static List<String> flatten(PackingList list) {
        return list.categories().stream().flatMap(c -> c.items().stream()).toList();
    }

    private static List<String> categoryNames(PackingList list) {
        return list.categories().stream().map(Category::name).toList();
    }

    private static void assertNoDuplicates(PackingList list) {
        List<String> lower = flatten(list).stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
        assertThat(lower).doesNotHaveDuplicates();
    }
}
