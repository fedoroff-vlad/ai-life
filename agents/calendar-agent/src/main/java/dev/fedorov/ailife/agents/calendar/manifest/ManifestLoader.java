package dev.fedorov.ailife.agents.calendar.manifest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads {@code AGENT.md} from the classpath at startup and exposes the parsed
 * {@link AgentManifest} as a singleton bean. The file is expected at the path
 * configured by {@code calendar-agent.manifest-classpath} (default {@code AGENT.md}),
 * which the Maven build copies from {@code agents/<name>/AGENT.md} onto the classpath.
 */
@Configuration
public class ManifestLoader {

    private static final Logger log = LoggerFactory.getLogger(ManifestLoader.class);

    /** Matches the leading YAML frontmatter block: {@code ---\n…yaml…\n---\n}. */
    private static final Pattern FRONTMATTER = Pattern.compile(
            "^---\\s*\\R(.*?)\\R---\\s*\\R(.*)$",
            Pattern.DOTALL);

    @Bean
    public AgentManifest agentManifest(
            @Value("${calendar-agent.manifest-classpath:AGENT.md}") String classpathLocation) {
        String content = readClasspath(classpathLocation);
        AgentManifest manifest = parse(content);
        log.info("loaded AGENT.md manifest: name={} version={} mcp={} triggers={}",
                manifest.name(), manifest.version(), manifest.mcp(),
                manifest.triggers() == null ? 0 : manifest.triggers().size());
        return manifest;
    }

    static AgentManifest parse(String content) {
        Matcher m = FRONTMATTER.matcher(content);
        if (!m.find()) {
            throw new IllegalStateException("AGENT.md missing YAML frontmatter (--- … ---)");
        }
        String yamlBlock = m.group(1);
        String body = m.group(2).trim();

        Map<String, Object> meta = new Yaml().load(yamlBlock);
        if (meta == null) {
            throw new IllegalStateException("AGENT.md frontmatter is empty");
        }

        return new AgentManifest(
                str(meta, "name"),
                str(meta, "description"),
                str(meta, "version"),
                intOrNull(meta.get("port")),
                listOfStrings(meta.get("mcp")),
                listOfStrings(meta.get("skills")),
                listOfMaps(meta.get("triggers")),
                listOfMaps(meta.get("intents")),
                body);
    }

    private static String readClasspath(String location) {
        try (InputStream in = new ClassPathResource(location).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read AGENT.md from classpath: " + location, e);
        }
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : v.toString();
    }

    private static Integer intOrNull(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        return Integer.parseInt(v.toString());
    }

    @SuppressWarnings("unchecked")
    private static List<String> listOfStrings(Object v) {
        if (v == null) return List.of();
        if (v instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        throw new IllegalStateException("Expected list of strings, got: " + v.getClass());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> listOfMaps(Object v) {
        if (v == null) return List.of();
        if (v instanceof List<?> list) {
            return list.stream()
                    .map(item -> {
                        if (!(item instanceof Map<?, ?> map)) {
                            throw new IllegalStateException("Expected map, got: " + item);
                        }
                        return map.entrySet().stream()
                                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                                        e -> e.getKey().toString(),
                                        e -> e.getValue() == null ? "" : e.getValue().toString()));
                    })
                    .toList();
        }
        throw new IllegalStateException("Expected list of maps, got: " + v.getClass());
    }
}
