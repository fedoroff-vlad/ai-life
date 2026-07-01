package dev.fedorov.ailife.agents.briefing.web;

import dev.fedorov.ailife.agents.briefing.chat.BriefingChat;
import dev.fedorov.ailife.agents.briefing.profile.BriefingProfiler;
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
 * Hit by the orchestrator when intent routing selects {@code briefing}:
 * <ul>
 *   <li>a preferences cue ("настрой брифинг", "показывай мне утром…", "set up my briefing") →
 *       {@link BriefingProfiler#setProfile} (one LLM extract + geocode → upsert the per-person prefs, BR-c);</li>
 *   <li>otherwise → the {@link BriefingChat} fallback (the digest flow arrives in BR-d).</li>
 * </ul>
 * The cue split is a deterministic keyword heuristic — good enough for the MVP, MockWebServer-testable,
 * and replaceable by an LLM classifier later.
 */
@RestController
@RequestMapping("/agents/briefing")
public class IntentController {

    private static final Set<String> PROFILE_CUES = Set.of(
            "настрой брифинг", "настрой дайджест", "настрой утренний", "показывай мне", "показывай каждое",
            "показывай утром", "хочу получать", "присылай мне", "присылай каждое", "мой брифинг",
            "мои настройки брифинга", "каждое утро", "по утрам", "утренний брифинг",
            "set up my briefing", "configure my briefing", "my briefing", "morning briefing",
            "send me every morning", "every morning show", "my digest", "set my briefing");

    private final BriefingProfiler profiler;
    private final BriefingChat chat;

    public IntentController(BriefingProfiler profiler, BriefingChat chat) {
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
