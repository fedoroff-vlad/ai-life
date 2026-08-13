package dev.fedorov.ailife.agents.travel.web;

import dev.fedorov.ailife.agents.travel.chat.TravelChat;
import dev.fedorov.ailife.agents.travel.flow.TripComposer;
import dev.fedorov.ailife.agents.travel.flow.WalletFlow;
import dev.fedorov.ailife.agents.travel.profile.TravelProfiler;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Locale;
import java.util.Set;

/**
 * Hit by the orchestrator when intent routing selects {@code travel}:
 * <ul>
 *   <li>a preferences cue ("мои предпочтения для путешествий", "летаем из Москвы", "set up my travel
 *       preferences") → {@link TravelProfiler#setProfile} (one LLM extract + geocode → upsert the
 *       per-person prefs, TR-c);</li>
 *   <li>a trip-wallet cue ("создай поездку", "завёл 500 $ по 90", "поменял 36000 ₽ на 40000 бат",
 *       "потратил 2000 бат на …", "сколько осталось / подведи итог") → {@link WalletFlow#handle} (the
 *       multi-currency family trip budget, EX-b);</li>
 *   <li>a plan-a-trip cue ("хочу на море в сентябре", "куда поехать", "найди билеты в …", "подбери отель",
 *       "plan me a trip", "find flights") → {@link TripComposer#plan} (resolve profile → gather
 *       budget/dates/season/research + live flight/hotel options when asked → one synthesis, TR-d/TR-f2);</li>
 *   <li>otherwise → the {@link TravelChat} fallback (plain questions).</li>
 * </ul>
 * The cue split is a deterministic keyword heuristic — good enough for the MVP, MockWebServer-testable,
 * and replaceable by an LLM classifier later. Preferences cues are checked before wallet/plan cues so an
 * explicit "set up my preferences" isn't swallowed by a stray travel word; wallet cues are checked before
 * plan cues so "создай поездку" (a wallet action) isn't read as a planning wish.
 */
@RestController
@RequestMapping("/agents/travel")
public class IntentController {

    private static final Set<String> PROFILE_CUES = Set.of(
            "мои предпочтения", "настройки путешествий", "настрой путешествия", "летаем из", "летаю из",
            "вылетаем из", "люблю отдых", "любим отдых", "предпочитаю отдых", "тип отдыха", "мой профиль путешеств",
            "set up my travel", "my travel preferences", "configure my travel", "travel profile",
            "we fly from", "i fly from", "flying from");

    // Trip-wallet cues (EX-b): manage a persisted multi-currency family trip budget. Checked BEFORE plan
    // cues so "создай поездку" (a wallet create) isn't swallowed by a planning word. The WalletExtractor
    // LLM turn does the fine-grained create/fund/exchange/spend/tally classification; these only route.
    private static final Set<String> WALLET_CUES = Set.of(
            "создай поездк", "создать поездк", "заведи поездк", "завести поездк", "новая поездка",
            "кошелёк поездк", "кошелек поездк", "бюджет поездк",
            "завёл", "завел", "закинул", "отложил",
            "поменял", "поменяли", "обменял", "обменяли",
            "потратил", "потратили", "потрачено", "оплатил",
            "сколько осталось", "сколько денег осталось", "подведи итог", "подбей итог", "останется",
            "остаток по поездк", "сколько потратили",
            "create a trip", "new trip", "trip wallet", "trip budget",
            "funded", "exchanged", "spent", "how much is left", "how much left", "tally the trip");

    // The orchestrator only routes a message here once it is already travel-intent, so these cues can be
    // broad ("хочу в …" won't be a cinema request by the time it reaches the travel agent).
    private static final Set<String> PLAN_CUES = Set.of(
            "спланируй поездк", "план поездк", "запланируй поездк", "спланируй отпуск", "план отпуска",
            "хочу на море", "хочу в отпуск", "хочу в ", "хочу поехать", "хочу съездить", "поехать в",
            "съездить в", "отдохнуть в", "куда поехать", "куда съездить", "куда бы съездить",
            "куда на отдых", "где отдохнуть", "где тепло", "на море в", "отпуск в ",
            // TR-f2 live-search cues: an explicit "find me tickets/hotels" still reaches the planner, which
            // then does the live search (the FAST scope's `live` flag decides). Routing here, not booking.
            "найди билет", "найти билет", "билеты в", "билеты на", "подбери отель", "подобрать отель",
            "найди отель", "перелёт в", "сколько стоит перелёт", "сколько стоят билеты",
            "plan a trip", "plan me a trip", "plan my trip", "plan a vacation", "plan a holiday",
            "where should we go", "where should i go", "where to go", "where's warm", "where is warm",
            "beach trip", "trip to", "go on holiday", "on vacation",
            "find flights", "find tickets", "find a hotel", "search flights", "search for flights");

    private final TravelProfiler profiler;
    private final WalletFlow wallet;
    private final TripComposer composer;
    private final TravelChat chat;

    public IntentController(TravelProfiler profiler, WalletFlow wallet, TripComposer composer, TravelChat chat) {
        this.profiler = profiler;
        this.wallet = wallet;
        this.composer = composer;
        this.chat = chat;
    }

    @PostMapping("/intent")
    public Mono<IntentResponse> intent(@RequestBody NormalizedMessage message) {
        if (isMatch(message.text(), PROFILE_CUES)) {
            return profiler.setProfile(message);
        }
        if (isMatch(message.text(), WALLET_CUES)) {
            return wallet.handle(message);
        }
        if (isMatch(message.text(), PLAN_CUES)) {
            return composer.plan(message);
        }
        return chat.reply(message);
    }

    private static boolean isMatch(String text, Set<String> cues) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String t = text.toLowerCase(Locale.ROOT);
        return cues.stream().anyMatch(t::contains);
    }
}
