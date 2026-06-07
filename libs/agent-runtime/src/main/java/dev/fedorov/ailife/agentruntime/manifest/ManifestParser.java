package dev.fedorov.ailife.agentruntime.manifest;

import dev.fedorov.ailife.contracts.agent.AgentManifest;
import org.yaml.snakeyaml.Yaml;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Parses an AGENT.md file (YAML frontmatter + markdown body) into an
 * {@link AgentManifest}. Pure: no Spring, no I/O.
 *
 * <p>Lifted verbatim from calendar-agent + finance-agent (PR9b / PR22) — the
 * parsers were byte-identical except for the package, so this is the shared
 * canonical implementation. New agents do not write their own parser.
 */
public final class ManifestParser {

    private static final Pattern FRONTMATTER = Pattern.compile(
            "^---\\s*\\R(.*?)\\R---\\s*\\R(.*)$",
            Pattern.DOTALL);

    private ManifestParser() {
    }

    public static AgentManifest parse(String content) {
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

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : v.toString();
    }

    private static Integer intOrNull(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        return Integer.parseInt(v.toString());
    }

    private static List<String> listOfStrings(Object v) {
        if (v == null) return List.of();
        if (v instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        throw new IllegalStateException("Expected list of strings, got: " + v.getClass());
    }

    private static List<Map<String, String>> listOfMaps(Object v) {
        if (v == null) return List.of();
        if (v instanceof List<?> list) {
            return list.stream()
                    .map(item -> {
                        if (!(item instanceof Map<?, ?> map)) {
                            throw new IllegalStateException("Expected map, got: " + item);
                        }
                        return map.entrySet().stream()
                                .collect(Collectors.toUnmodifiableMap(
                                        e -> e.getKey().toString(),
                                        e -> e.getValue() == null ? "" : e.getValue().toString()));
                    })
                    .toList();
        }
        throw new IllegalStateException("Expected list of maps, got: " + v.getClass());
    }
}
