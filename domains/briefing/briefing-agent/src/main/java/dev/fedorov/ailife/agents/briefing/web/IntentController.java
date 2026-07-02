package dev.fedorov.ailife.agents.briefing.web;

import dev.fedorov.ailife.agents.briefing.chat.BriefingChat;
import dev.fedorov.ailife.agents.briefing.flow.BriefingComposer;
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
 *   <li>a produce-now cue ("собери мне брифинг", "брифинг на сегодня", "brief me") →
 *       {@link BriefingComposer#digest} (gather the enabled sections → one LLM synthesis, BR-d);</li>
 *   <li>a preferences cue ("настрой брифинг", "показывай мне утром…", "set up my briefing") →
 *       {@link BriefingProfiler#setProfile} (one LLM extract + geocode → upsert the per-person prefs, BR-c);</li>
 *   <li>otherwise → the {@link BriefingChat} fallback (plain questions).</li>
 * </ul>
 * The cue split is a deterministic keyword heuristic — good enough for the MVP, MockWebServer-testable,
 * and replaceable by an LLM classifier later. The digest cue is checked first: a "brief me now" request
 * is the common ask and its phrases don't overlap the config phrases.
 */
@RestController
@RequestMapping("/agents/briefing")
public class IntentController {

    private static final Set<String> DIGEST_CUES = Set.of(
            "собери брифинг", "собери мне брифинг", "собери дайджест", "брифинг на сегодня",
            "дайджест на сегодня", "покажи брифинг", "брифинг сейчас", "что у меня сегодня",
            "что сегодня", "мой день сегодня",
            "brief me", "today's briefing", "todays briefing", "today's digest", "todays digest",
            "my briefing today", "give me my briefing", "generate my briefing", "what's my day");

    private static final Set<String> PROFILE_CUES = Set.of(
            "настрой брифинг", "настрой дайджест", "настрой утренний", "показывай мне", "показывай каждое",
            "показывай утром", "хочу получать", "присылай мне", "присылай каждое", "мой брифинг",
            "мои настройки брифинга", "каждое утро", "по утрам", "утренний брифинг",
            "set up my briefing", "configure my briefing", "my briefing", "morning briefing",
            "send me every morning", "every morning show", "my digest", "set my briefing");

    private final BriefingComposer composer;
    private final BriefingProfiler profiler;
    private final BriefingChat chat;

    public IntentController(BriefingComposer composer, BriefingProfiler profiler, BriefingChat chat) {
        this.composer = composer;
        this.profiler = profiler;
        this.chat = chat;
    }

    @PostMapping("/intent")
    public Mono<IntentResponse> intent(@RequestBody NormalizedMessage message) {
        if (isMatch(message.text(), DIGEST_CUES)) {
            return composer.digest(message);
        }
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
