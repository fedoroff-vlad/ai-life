package dev.fedorov.ailife.agents.calendar.system;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Indexes all {@link SystemTriggerHandler} beans by their {@link SystemTriggerHandler#kind()}.
 * TriggerController consults this BEFORE SkillRegistry — system triggers run without
 * the LLM. If two handlers claim the same kind, last one wins (deliberate: makes
 * overriding in tests trivial via a {@code @Primary} bean).
 */
@Component
public class SystemTriggerRegistry {

    private final Map<String, SystemTriggerHandler> byKind;

    public SystemTriggerRegistry(List<SystemTriggerHandler> handlers) {
        this.byKind = handlers.stream().collect(Collectors.toMap(
                SystemTriggerHandler::kind, Function.identity(),
                (a, b) -> b));
    }

    public Optional<SystemTriggerHandler> forKind(String kind) {
        return Optional.ofNullable(byKind.get(kind));
    }
}
