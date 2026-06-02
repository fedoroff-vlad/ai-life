package dev.fedorov.ailife.contracts.llm;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum LlmRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL;

    @JsonValue
    public String wire() {
        return name().toLowerCase();
    }

    @JsonCreator
    public static LlmRole fromWire(String value) {
        return LlmRole.valueOf(value.trim().toUpperCase());
    }
}
