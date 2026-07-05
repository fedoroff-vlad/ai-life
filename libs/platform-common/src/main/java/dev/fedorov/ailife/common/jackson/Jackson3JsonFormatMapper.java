package dev.fedorov.ailife.common.jackson;

import org.hibernate.type.format.AbstractJsonFormatMapper;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Type;

/**
 * Hibernate {@link org.hibernate.type.format.FormatMapper} for {@code jsonb}/JSON columns, backed by
 * <b>Jackson 3</b>.
 *
 * <p>Hibernate ORM 7.x only ships a Jackson 2 ({@code com.fasterxml.jackson}) JSON format mapper and
 * auto-detects it by looking for Jackson 2 on the classpath. Spring Boot 4 / Framework 7 moved to
 * Jackson 3 ({@code tools.jackson}), so that auto-detection fails at runtime with
 * <em>"Could not find a FormatMapper for the JSON format"</em>. This adapter re-implements the tiny
 * {@link AbstractJsonFormatMapper} contract on top of a Jackson 3 mapper.
 *
 * <p>Enabled per JPA service via {@code spring.jpa.properties.hibernate.type.json_format_mapper} set to
 * this class name. Hibernate instantiates it reflectively by name, so it must keep a public no-arg
 * constructor. java.time (JSR-310) support is built into Jackson 3 and auto-registered, so {@code Instant}
 * and friends in JSON columns round-trip without an extra module.
 */
public class Jackson3JsonFormatMapper extends AbstractJsonFormatMapper {

    private final ObjectMapper objectMapper;

    public Jackson3JsonFormatMapper() {
        this(JsonMapper.builder().build());
    }

    public Jackson3JsonFormatMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected <T> T fromString(CharSequence charSequence, Type type) {
        return objectMapper.readValue(charSequence.toString(), objectMapper.constructType(type));
    }

    @Override
    protected <T> String toString(T value, Type type) {
        return objectMapper.writeValueAsString(value);
    }
}
