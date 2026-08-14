package dev.fedorov.ailife.agents.travel.flow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The deterministic packing-list engine (PK-a, #438): a <b>seed-and-combine</b> of static per-input item
 * sets — always-on essentials + climate-band clothing/footwear + rest-type activity gear + children items —
 * into a categorized list. It is <b>pure Java, never the LLM</b> (a correctness boundary like
 * {@link TripLedger} and {@code libs/sharing}, and the reason PK-a needs no golden and no new dependency).
 *
 * <p>Inputs come from the household's active trip (its destination's climate for the trip month → a
 * {@link ClimateBand}) and the person's {@code travel_profile} (rest types + companions + child ages);
 * {@link PackingFlow} gathers them and soft-fails each, so an unknown climate simply yields
 * {@link ClimateBand#UNKNOWN} (a weather-neutral list, flagged) rather than an error.
 *
 * <p>Items are <b>globally deduped</b> in category order — an item introduced by several rules (e.g.
 * sunscreen from a hot band and a beach rest type) appears once, under the first category that names it.
 * Empty categories are dropped. Display strings are Russian (user-facing); identifiers/comments English.
 */
public final class PackingListComposer {

    /** A destination's season fit for the trip month, derived from the climate normal's average °C. */
    public enum ClimateBand {
        /** ≥ 24 °C */ HOT,
        /** 18–24 °C */ WARM,
        /** 10–18 °C */ MILD,
        /** 2–10 °C */ COOL,
        /** < 2 °C */ COLD,
        /** no destination/date, or the climate source was unreachable */ UNKNOWN;

        /** Map an average monthly temperature (°C) to a band; null → {@link #UNKNOWN}. */
        public static ClimateBand ofAvgTempC(Double avgTempC) {
            if (avgTempC == null) {
                return UNKNOWN;
            }
            if (avgTempC >= 24) return HOT;
            if (avgTempC >= 18) return WARM;
            if (avgTempC >= 10) return MILD;
            if (avgTempC >= 2) return COOL;
            return COLD;
        }
    }

    /** The gathered inputs: preferred rest types, companions, child ages, the season band, and wetness. */
    public record PackingContext(
            List<String> restTypes,
            String companions,
            List<Integer> childAges,
            ClimateBand climate,
            boolean wet) {
    }

    /** One named group of items in the list (e.g. "Одежда"). */
    public record Category(String name, List<String> items) {
    }

    /** The composed list + whether the season was known (drives the "уточните прогноз" note in the flow). */
    public record PackingList(List<Category> categories, boolean weatherKnown) {
    }

    private static final String DOCS = "Документы и деньги";
    private static final String ELECTRONICS = "Электроника";
    private static final String HYGIENE = "Гигиена и аптечка";
    private static final String CLOTHING = "Одежда";
    private static final String FOOTWEAR = "Обувь";
    private static final String ACTIVITY = "Для отдыха";
    private static final String KIDS = "Дети";

    /** Fixed render order; a category is emitted only if it ended up with items after dedup. */
    private static final List<String> ORDER =
            List.of(DOCS, ELECTRONICS, HYGIENE, CLOTHING, FOOTWEAR, ACTIVITY, KIDS);

    private PackingListComposer() {
    }

    public static PackingList compose(PackingContext ctx) {
        Map<String, Set<String>> cats = new LinkedHashMap<>();
        for (String name : ORDER) {
            cats.put(name, new LinkedHashSet<>());
        }

        // Always-on essentials — present regardless of inputs.
        add(cats, DOCS, "Паспорт / загранпаспорт", "Билеты и посадочные талоны",
                "Медицинская страховка", "Банковские карты и наличные");
        add(cats, ELECTRONICS, "Телефон и зарядка", "Повербанк", "Наушники",
                "Универсальный переходник для розеток");
        add(cats, HYGIENE, "Зубная щётка и паста", "Дезодорант", "Расчёска",
                "Личные лекарства", "Пластырь и антисептик");

        List<String> rest = normalize(ctx.restTypes());
        boolean beachy = rest.contains("beach") || rest.contains("wellness");

        clothingByBand(cats, ctx.climate());
        footwearByBand(cats, ctx.climate(), rest);
        if (ctx.climate() == ClimateBand.HOT || beachy) {
            add(cats, HYGIENE, "Солнцезащитный крем");
        }
        if (ctx.wet()) {
            add(cats, CLOTHING, "Дождевик или зонт");
            add(cats, FOOTWEAR, "Непромокаемая обувь");
        }

        activityByRestTypes(cats, rest);
        if ("couple".equals(ctx.companions())) {
            add(cats, ACTIVITY, "Нарядный комплект для ужина");
        }
        children(cats, ctx.companions(), ctx.childAges());

        return new PackingList(dedupAndOrder(cats), ctx.climate() != ClimateBand.UNKNOWN);
    }

    private static void clothingByBand(Map<String, Set<String>> cats, ClimateBand band) {
        switch (band) {
            case HOT -> add(cats, CLOTHING, "Лёгкая одежда (футболки, шорты)", "Головной убор от солнца",
                    "Солнцезащитные очки", "Лёгкая кофта на вечер");
            case WARM -> add(cats, CLOTHING, "Футболки и лёгкие брюки", "Лёгкая кофта на вечер",
                    "Головной убор");
            case MILD -> add(cats, CLOTHING, "Свитер или кофта", "Ветровка", "Брюки");
            case COOL -> add(cats, CLOTHING, "Тёплая куртка", "Свитеры", "Тёплые носки",
                    "Шапка и перчатки");
            case COLD -> add(cats, CLOTHING, "Тёплый пуховик", "Термобельё", "Шапка, шарф, перчатки",
                    "Тёплые носки");
            case UNKNOWN -> add(cats, CLOTHING, "Базовый набор одежды (уточните прогноз погоды)",
                    "Кофта на смену погоды");
        }
    }

    private static void footwearByBand(Map<String, Set<String>> cats, ClimateBand band, List<String> rest) {
        switch (band) {
            case HOT, WARM -> add(cats, FOOTWEAR, "Лёгкая обувь / сандалии");
            case MILD -> add(cats, FOOTWEAR, "Удобная закрытая обувь");
            case COOL, COLD -> add(cats, FOOTWEAR, "Тёплая непромокаемая обувь");
            case UNKNOWN -> add(cats, FOOTWEAR, "Удобная обувь");
        }
        if (rest.contains("beach")) add(cats, FOOTWEAR, "Шлёпанцы");
        if (rest.contains("active")) add(cats, FOOTWEAR, "Треккинговые ботинки");
        if (rest.contains("city")) add(cats, FOOTWEAR, "Удобная обувь для ходьбы");
    }

    private static void activityByRestTypes(Map<String, Set<String>> cats, List<String> rest) {
        for (String r : rest) {
            switch (r) {
                case "beach" -> add(cats, ACTIVITY, "Купальник", "Пляжное полотенце", "Крем после загара");
                case "active" -> add(cats, ACTIVITY, "Рюкзак", "Многоразовая бутылка для воды",
                        "Быстросохнущая одежда", "Налобный фонарь", "Походная аптечка");
                case "ski" -> add(cats, ACTIVITY, "Горнолыжный костюм", "Термобельё",
                        "Горнолыжные перчатки", "Маска / очки", "Бальзам для губ");
                case "city" -> add(cats, ACTIVITY, "Городской рюкзак или сумка", "Список мест для посещения");
                case "wellness" -> add(cats, ACTIVITY, "Купальник", "Удобная одежда для отдыха",
                        "Книга или электронная книга");
                case "couple" -> add(cats, ACTIVITY, "Нарядный комплект для ужина");
                default -> { /* family / unknown → no activity-specific gear */ }
            }
        }
    }

    private static void children(Map<String, Set<String>> cats, String companions, List<Integer> childAges) {
        if (!"family".equals(companions)) {
            return;
        }
        add(cats, KIDS, "Детская аптечка", "Головные уборы для детей", "Сменная детская одежда");
        List<Integer> ages = childAges == null ? List.of() : childAges;
        boolean infant = ages.stream().anyMatch(a -> a != null && a < 3);
        boolean youngChild = ages.stream().anyMatch(a -> a != null && a < 10);
        if (infant) {
            add(cats, KIDS, "Подгузники", "Влажные салфетки", "Детское питание и бутылочки");
        }
        if (youngChild) {
            add(cats, KIDS, "Игрушки и раскраски", "Перекусы для детей");
        }
    }

    /** Lowercase, trim, drop blanks/nulls, keep order + uniqueness — the rest-type vocabulary. */
    private static List<String> normalize(List<String> restTypes) {
        List<String> out = new ArrayList<>();
        if (restTypes == null) {
            return out;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String r : restTypes) {
            if (r == null || r.isBlank()) {
                continue;
            }
            String v = r.trim().toLowerCase(Locale.ROOT);
            if (seen.add(v)) {
                out.add(v);
            }
        }
        return out;
    }

    private static void add(Map<String, Set<String>> cats, String category, String... items) {
        Set<String> set = cats.get(category);
        for (String item : items) {
            set.add(item);
        }
    }

    /** Emit categories in {@link #ORDER}, globally deduping items (first category wins) and dropping empties. */
    private static List<Category> dedupAndOrder(Map<String, Set<String>> cats) {
        List<Category> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String name : ORDER) {
            List<String> items = new ArrayList<>();
            for (String item : cats.get(name)) {
                if (seen.add(item.toLowerCase(Locale.ROOT))) {
                    items.add(item);
                }
            }
            if (!items.isEmpty()) {
                result.add(new Category(name, items));
            }
        }
        return result;
    }
}
