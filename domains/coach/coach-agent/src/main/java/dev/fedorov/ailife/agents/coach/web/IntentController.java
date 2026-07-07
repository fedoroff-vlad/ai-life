package dev.fedorov.ailife.agents.coach.web;

import dev.fedorov.ailife.agents.coach.flow.Reflector;
import dev.fedorov.ailife.agents.coach.safety.SafetyGate;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Hit by the orchestrator when intent routing selects {@code coach}. Order is load-bearing
 * (coach spec §Safety + Decision 0):
 * <ol>
 *   <li><b>private-scope guard</b> — coaching material is strictly the sender's own; a household or
 *       group chat gets a gentle redirect instead of a session, so nothing private leaks into a
 *       shared channel;</li>
 *   <li><b>{@link SafetyGate}</b> — a crisis signal drops the coaching frame entirely and refers out;
 *       it runs before any pattern analysis and short-circuits it;</li>
 *   <li><b>{@link Reflector}</b> — the CO-2 mode; {@code develop}/{@code intake} arrive in later
 *       slices.</li>
 * </ol>
 */
@RestController
@RequestMapping("/agents/coach")
public class IntentController {

    private static final String ASK_TEXT =
            "О чём хочется подумать? Расскажите, что происходит или что не даёт покоя.";
    private static final String PRIVATE_ONLY_TEXT =
            "Такие разговоры — только один на один. Напишите мне в личном чате, и продолжим там.";
    private static final String NO_SENDER_TEXT =
            "Не могу определить, кто пишет — а коуч-сессия строго личная. Попробуйте ещё раз чуть позже.";
    private static final String CRISIS_TEXT =
            "Слышу, что вам сейчас по-настоящему тяжело, и мне жаль, что вы через это проходите. "
            + "Здесь я не лучший помощник — с таким состоянием правильнее не оставаться один на один, "
            + "а поговорить с живым специалистом: психологом или службой экстренной психологической "
            + "помощи. Если есть угроза жизни — звоните 112. Я рядом и вернусь к нашим разговорам, "
            + "когда вам станет безопаснее.";

    private final SafetyGate safety;
    private final Reflector reflector;
    private final AgentManifest manifest;

    public IntentController(SafetyGate safety, Reflector reflector, AgentManifest manifest) {
        this.safety = safety;
        this.reflector = reflector;
        this.manifest = manifest;
    }

    @PostMapping("/intent")
    public Mono<IntentResponse> intent(@RequestBody NormalizedMessage message) {
        if (message == null || message.text() == null || message.text().isBlank()) {
            return Mono.just(reply(ASK_TEXT));
        }
        if (message.scope() != MessageScope.PRIVATE) {
            return Mono.just(reply(PRIVATE_ONLY_TEXT));
        }
        if (message.userId() == null || message.householdId() == null) {
            return Mono.just(reply(NO_SENDER_TEXT));
        }
        return safety.isCrisis(message.text())
                .flatMap(crisis -> crisis
                        ? Mono.just(reply(CRISIS_TEXT))
                        : reflector.reflect(message));
    }

    private IntentResponse reply(String text) {
        return new IntentResponse(manifest.name(), text, null);
    }
}
