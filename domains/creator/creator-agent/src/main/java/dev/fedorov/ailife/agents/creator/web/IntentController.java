package dev.fedorov.ailife.agents.creator.web;

import dev.fedorov.ailife.agents.creator.chat.CreatorChat;
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
 * Hit by the orchestrator when intent routing selects {@code creator}. The CR-c creator-profile flow:
 * <ul>
 *   <li>a typed message with a creator-profile cue ("моя ниша…", "мой контент про…", "my niche…") →
 *       {@link CreatorProfiler#setProfile} (one LLM extract → upsert the per-person track);</li>
 *   <li>otherwise → the {@link CreatorChat} fallback.</li>
 * </ul>
 * The cue split is a deterministic keyword heuristic — good enough for the MVP, MockWebServer-testable,
 * and replaceable by an LLM classifier later. The trend → ideas → drafts synthesis lands in CR-d.
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

    private final CreatorProfiler creatorProfiler;
    private final CreatorChat chat;

    public IntentController(CreatorProfiler creatorProfiler, CreatorChat chat) {
        this.creatorProfiler = creatorProfiler;
        this.chat = chat;
    }

    @PostMapping("/intent")
    public Mono<IntentResponse> intent(@RequestBody NormalizedMessage message) {
        if (isMatch(message.text(), PROFILE_CUES)) {
            return creatorProfiler.setProfile(message);
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
