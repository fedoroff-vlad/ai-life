package dev.fedorov.ailife.agents.creator.web;

import dev.fedorov.ailife.agents.creator.chat.CreatorChat;
import dev.fedorov.ailife.agents.creator.flow.ContentStrategist;
import dev.fedorov.ailife.agents.creator.profile.CreatorProfiler;
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
 * Hit by the orchestrator when intent routing selects {@code creator}:
 * <ul>
 *   <li>a creator-profile cue ("моя ниша…", "мой контент про…", "my niche…") →
 *       {@link CreatorProfiler#setProfile} (one LLM extract → upsert the per-person track, CR-c);</li>
 *   <li>a trend/ideas/draft cue ("тренды", "идеи", "что постить", "draft", "post ideas") →
 *       {@link ContentStrategist#run} (cheap-first multi-source gather → one LLM synthesis → an HTML
 *       content-plan board, CR-d);</li>
 *   <li>otherwise → the {@link CreatorChat} fallback.</li>
 * </ul>
 * The cue split is a deterministic keyword heuristic — good enough for the MVP, MockWebServer-testable,
 * and replaceable by an LLM classifier later. Profile cues are checked first so "мой контент про X"
 * sets the track rather than triggering a plan.
 */
@RestController
@RequestMapping("/agents/creator")
public class IntentController {

    private static final Set<String> PROFILE_CUES = Set.of(
            "моя ниша", "мой контент", "мой блог", "мой канал", "веду блог", "веду канал",
            "моя аудитория", "мой профиль создателя", "мой профиль автора", "пишу про", "снимаю про",
            "контент про", "контент о", "тон контента",
            "my niche", "my content", "my channel", "my blog", "my audience", "creator profile",
            "i make content", "i create content", "i post about", "set my creator", "my brand voice",
            "my content profile");

    private static final Set<String> STRATEGIST_CUES = Set.of(
            "тренд", "идеи", "идею", "что постить", "о чём постить", "о чем постить", "контент-план",
            "контент план", "пост про", "сделай пост", "напиши пост", "черновик", "драфт",
            "trend", "idea", "draft", "post about", "what should i post", "what to post",
            "content plan", "post ideas", "content ideas");

    private final CreatorProfiler creatorProfiler;
    private final ContentStrategist strategist;
    private final CreatorChat chat;

    public IntentController(CreatorProfiler creatorProfiler, ContentStrategist strategist, CreatorChat chat) {
        this.creatorProfiler = creatorProfiler;
        this.strategist = strategist;
        this.chat = chat;
    }

    @PostMapping("/intent")
    public Mono<IntentResponse> intent(@RequestBody NormalizedMessage message) {
        if (isMatch(message.text(), PROFILE_CUES)) {
            return creatorProfiler.setProfile(message);
        }
        if (isMatch(message.text(), STRATEGIST_CUES)) {
            return strategist.run(message);
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
