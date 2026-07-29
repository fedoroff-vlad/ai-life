package dev.fedorov.ailife.agents.tasks.intent;

import dev.fedorov.ailife.agentruntime.intent.SkillClassifier;
import dev.fedorov.ailife.agentruntime.intent.SkillClassifier.Choice;
import dev.fedorov.ailife.agentruntime.intent.SkillClassifier.ToolSpec;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.tasks.tools.ToolDispatcher;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;

/**
 * Routes a user message into either an mcp-tasks tool call, one of the agent's intent skills, or a
 * plain LLM reply.
 *
 * <p>The prompt-build + strict-JSON-parse halves live in the shared {@link SkillClassifier}
 * ({@code libs/agent-runtime}, skills-vs-flows Bucket 1) — this agent keeps only the parts that are
 * genuinely its own: the LLM round-trip, the {@link ToolSpec}s it maps from its wired MCP tools, and
 * how the one {@code skill} {@link Choice} resolves (a {@link SkillClassifier.FlowCall} whose
 * {@code node.name} names an intent skill for {@code IntentController} to run).
 *
 * <p>Pipeline (single LLM round-trip):
 * <ol>
 *   <li>If there are neither MCP tools ({@link ToolDispatcher#availableToolDefinitions()}) nor intent
 *   skills wired, skip routing and fall back to plain chat — back-compat with the skeleton.</li>
 *   <li>Otherwise prompt the LLM with AGENT.md body + the classifier prompt (tool list + the intent
 *   skills as one {@code skill} choice + the strict-JSON contract), then hand the raw response to
 *   {@link SkillClassifier#parse}.</li>
 *   <li>{@link SkillClassifier.ToolCall} → dispatch via {@link ToolDispatcher};
 *   {@link SkillClassifier.FlowCall} (the {@code skill} choice) → a {@link RouterResult} naming the
 *   intent skill (unknown name → chat fallback); {@link SkillClassifier.Chat} → the reply text.</li>
 * </ol>
 *
 * <p>Intent skills are skills with no {@code triggers} (a trigger skill is fired by scheduler-service
 * via {@code TriggerController}, not by a user message). {@code inbox-clarify} is the first such
 * skill; {@code IntentController} owns the per-skill flow (this router only classifies).
 *
 * <p>Tool-dispatch failures are caught and surfaced as a short Russian-locale error naming the
 * tool — no retry/re-route here (that's the orchestrator's concern).
 */
@Component
public class IntentRouter {

    private static final Logger log = LoggerFactory.getLogger(IntentRouter.class);

    /** The single classifier action the intent skills are offered under (one action, many skills). */
    private static final String SKILL_ACTION = "skill";

    private final LlmClient llm;
    private final ToolDispatcher dispatcher;
    private final AgentManifest manifest;
    private final SkillRegistry skills;
    private final SkillClassifier classifier;

    public IntentRouter(LlmClient llm,
                        ToolDispatcher dispatcher,
                        AgentManifest manifest,
                        SkillRegistry skills,
                        SkillClassifier classifier) {
        this.llm = llm;
        this.dispatcher = dispatcher;
        this.manifest = manifest;
        this.skills = skills;
        this.classifier = classifier;
    }

    public Mono<RouterResult> route(String userText) {
        List<ToolDefinition> tools = dispatcher.availableToolDefinitions();
        List<Skill> intentSkills = intentSkills();
        if (tools.isEmpty() && intentSkills.isEmpty()) {
            return chatOnly(userText);
        }

        List<ToolSpec> toolSpecs = toolSpecs(tools);
        List<Choice> choices = choices(intentSkills);
        LlmChatRequest req = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.system(manifest.body()),
                LlmMessage.system(buildClassifierPrompt(tools, intentSkills)),
                LlmMessage.user(userText)));

        return llm.chat(req).flatMap(resp -> {
            String raw = resp.content() == null ? "" : resp.content().trim();
            String model = resp.model();
            return switch (classifier.parse(raw, toolSpecs, choices)) {
                case SkillClassifier.ToolCall t -> invokeTool(t.name(), t.argsJson(), model);
                case SkillClassifier.FlowCall f -> resolveSkill(f, intentSkills, raw, model);
                case SkillClassifier.Chat c -> Mono.just(new RouterResult(c.text(), null, model, null));
            };
        });
    }

    /**
     * Resolve the {@code skill} choice: the LLM named an intent skill in {@code node.name}. A known
     * name → a {@link RouterResult} carrying it (the {@code IntentController} runs the flow, so the
     * text is null); an unknown name → the lenient chat fallback (the raw body, or its {@code text}
     * field), matching the pre-migration behaviour.
     */
    private Mono<RouterResult> resolveSkill(SkillClassifier.FlowCall f, List<Skill> intentSkills,
                                            String raw, String model) {
        String skillName = f.node().path("name").asText(null);
        if (skillName != null && intentSkills.stream().anyMatch(s -> skillName.equals(s.name()))) {
            return Mono.just(new RouterResult(null, null, model, skillName));
        }
        log.warn("LLM routed to unknown intent skill '{}' — falling back to chat", skillName);
        String text = f.node().has("text") ? f.node().get("text").asText() : raw;
        return Mono.just(new RouterResult(text, null, model, null));
    }

    /** Skills with no triggers are user-invoked (intent) rather than scheduler-fired. */
    private List<Skill> intentSkills() {
        return skills.all().stream()
                .filter(s -> s.triggers() == null || s.triggers().isEmpty())
                .toList();
    }

    private Mono<RouterResult> chatOnly(String userText) {
        LlmChatRequest req = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.system(manifest.body()),
                LlmMessage.user(userText)));
        return llm.chat(req).map(r -> new RouterResult(
                r.content() == null ? "" : r.content(), null, r.model(), null));
    }

    private Mono<RouterResult> invokeTool(String name, String argsJson, String llmModel) {
        return Mono.fromCallable(() -> dispatcher.dispatch(name, argsJson))
                .subscribeOn(Schedulers.boundedElastic())
                .map(result -> new RouterResult(result, name, llmModel, null))
                .onErrorResume(e -> {
                    log.warn("tool dispatch failed for {}: {}", name, e.toString());
                    return Mono.just(new RouterResult(
                            "Не удалось вызвать инструмент «" + name + "»: " + e.getMessage(),
                            name, llmModel, null));
                });
    }

    /** Map the wired MCP tools to the classifier's domain-agnostic {@link ToolSpec}s at the call site. */
    private List<ToolSpec> toolSpecs(List<ToolDefinition> tools) {
        List<ToolSpec> specs = new ArrayList<>(tools.size());
        for (ToolDefinition t : tools) {
            specs.add(new ToolSpec(t.name(), t.description(), t.inputSchema()));
        }
        return specs;
    }

    /**
     * The classifier choices: the intent skills collapsed into one {@code skill} action (one action,
     * many skills — the LLM names the skill in {@code node.name}). Empty when no intent skill is loaded
     * (then only tool/chat are offered — the router still runs because a tool is wired).
     */
    private List<Choice> choices(List<Skill> intentSkills) {
        if (intentSkills.isEmpty()) {
            return List.of();
        }
        StringBuilder block = new StringBuilder(
                "Available skills (multi-step flows — pick one only when the user clearly asks for it):");
        for (Skill s : intentSkills) {
            block.append("\n- ").append(s.name()).append(": ").append(s.description());
        }
        return List.of(new Choice(SKILL_ACTION, block.toString(),
                "{\"action\":\"skill\",\"name\":\"<skill-name>\"}"));
    }

    private String buildClassifierPrompt(List<ToolDefinition> tools, List<Skill> intentSkills) {
        return classifier.buildPrompt(
                "You can reply directly to the user, invoke one MCP tool, or run one skill.",
                toolSpecs(tools),
                choices(intentSkills),
                "Decide: does the user want to run a tool, run a skill, or just talk?");
    }

    /**
     * Outcome of a single {@link #route} call: {@code text} for the orchestrator to show (null when
     * an intent skill was chosen — {@code IntentController} produces the text by running the flow),
     * {@code invokedTool} (non-null when a tool ran — informational), {@code llmModel} from the
     * routing turn (preserves the {@code IntentResponse.llmModel} contract), and
     * {@code invokedSkill} (non-null when the LLM routed to an intent skill).
     */
    public record RouterResult(String text, String invokedTool, String llmModel, String invokedSkill) {
    }
}
