package dev.fedorov.ailife.contracts.agent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Where the message came from — affects which memory scope agents read/write. */
public enum MessageScope {
    PRIVATE,
    HOUSEHOLD,
    GROUP_CHAT;

    @JsonValue
    public String wire() {
        return name().toLowerCase();
    }

    @JsonCreator
    public static MessageScope fromWire(String value) {
        if (value == null) {
            return PRIVATE;
        }
        return MessageScope.valueOf(value.trim().toUpperCase());
    }
}
