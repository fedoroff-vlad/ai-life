package dev.fedorov.ailife.agents.calendar.intent;

import dev.fedorov.ailife.agentruntime.intent.SkillClassifier;
import dev.fedorov.ailife.agentruntime.intent.SkillRouter;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.calendar.chat.CalendarChat;
import dev.fedorov.ailife.agents.calendar.flow.EventCanceller;
import dev.fedorov.ailife.agents.calendar.flow.EventCapturer;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.llm.LlmClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Routes a message the orchestrator sent to {@code calendar} into one of the agent's user-facing event
 * flows — capture ({@link EventCapturer}, Track H.2 / HC-1) — or a plain chat reply ({@link CalendarChat},
 * which keeps the #195 ICS-feed nudge).
 *
 * <p>A thin binding over the shared {@link SkillRouter} ({@code libs/agent-runtime}, #475), same shape as
 * notes-agent: it supplies the calendar-specific dispatch map ({@code event-capture} create, {@code
 * event-cancel} delete) + the chat fallback + the intro/decide framing, and the shared router owns the LLM
 * round-trip, the {@link SkillClassifier} parse, and the soft-fail-to-chat dispatch. Each intent skill's
 * SKILL.md {@code description} is the routing SSOT (a paraphrase outside any keyword list routes correctly).
 * The birthday/gift skills stay off this map — they are wake-driven, not user-routable, so the router never
 * advertises them. HC-4 adds {@code event-move} to the map.
 */
@Component
public class CalendarIntentRouter {

    private static final String EVENT_CAPTURE = "event-capture";
    private static final String EVENT_CANCEL = "event-cancel";

    private final SkillRouter router;

    public CalendarIntentRouter(LlmClient llm, SkillRegistry skills, SkillClassifier classifier,
                                AgentManifest manifest, EventCapturer capturer, EventCanceller canceller,
                                CalendarChat chat) {
        Map<String, Function<NormalizedMessage, Mono<IntentResponse>>> flows = new LinkedHashMap<>();
        flows.put(EVENT_CAPTURE, capturer::capture);
        flows.put(EVENT_CANCEL, canceller::cancel);
        this.router = new SkillRouter(llm, skills, classifier, manifest,
                "You are routing a message for the calendar agent. Reply directly to the user, or run one skill.",
                "Decide: does the user want to run a skill (put an event on the calendar, or cancel one) or just talk?",
                flows, chat::reply);
    }

    public Mono<IntentResponse> route(NormalizedMessage msg) {
        return router.route(msg);
    }

    /** The exact classifier prompt {@link #route} builds — replayed by the routing golden. */
    String buildClassifierPrompt() {
        return router.buildClassifierPrompt();
    }
}
