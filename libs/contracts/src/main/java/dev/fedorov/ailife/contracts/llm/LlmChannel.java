package dev.fedorov.ailife.contracts.llm;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Logical channel an agent talks to. Concrete model is owned by llm-gateway and
 * controlled by env vars; callers never reference a model name directly.
 */
public enum LlmChannel {
    DEFAULT,
    FAST,
    VISION,
    EMBEDDING;

    @JsonValue
    public String wire() {
        return name().toLowerCase();
    }

    @JsonCreator
    public static LlmChannel fromWire(String value) {
        if (value == null) {
            return DEFAULT;
        }
        return LlmChannel.valueOf(value.trim().toUpperCase());
    }
}
