package dev.fedorov.ailife.agents.travel.web;

import dev.fedorov.ailife.agents.travel.chat.TravelChat;
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
 *   <li>otherwise → the {@link TravelChat} fallback (plan-a-trip requests land here until the TR-d
 *       trip-planner flow is wired; plus plain questions).</li>
 * </ul>
 * The cue split is a deterministic keyword heuristic — good enough for the MVP, MockWebServer-testable,
 * and replaceable by an LLM classifier later. The planner cue is deliberately NOT matched yet: TR-c
 * ships only the profiler write; a plan request falls through to the conversational fallback until TR-d.
 */
@RestController
@RequestMapping("/agents/travel")
public class IntentController {

    private static final Set<String> PROFILE_CUES = Set.of(
            "мои предпочтения", "настройки путешествий", "настрой путешествия", "летаем из", "летаю из",
            "вылетаем из", "люблю отдых", "любим отдых", "предпочитаю отдых", "тип отдыха", "мой профиль путешеств",
            "set up my travel", "my travel preferences", "configure my travel", "travel profile",
            "we fly from", "i fly from", "flying from");

    private final TravelProfiler profiler;
    private final TravelChat chat;

    public IntentController(TravelProfiler profiler, TravelChat chat) {
        this.profiler = profiler;
        this.chat = chat;
    }

    @PostMapping("/intent")
    public Mono<IntentResponse> intent(@RequestBody NormalizedMessage message) {
        if (isMatch(message.text(), PROFILE_CUES)) {
            return profiler.setProfile(message);
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
