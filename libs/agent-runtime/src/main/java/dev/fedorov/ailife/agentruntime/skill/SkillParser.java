package dev.fedorov.ailife.agentruntime.skill;

import org.yaml.snakeyaml.Yaml;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a SKILL.md file (YAML frontmatter + markdown body) into a {@link Skill}.
 * Pure: no Spring, no I/O. Mirrors {@link dev.fedorov.ailife.agentruntime.manifest.ManifestParser}
 * — only the contract type differs.
 */
public final class SkillParser {

    private static final Pattern FRONTMATTER = Pattern.compile(
            "^---\\s*\\R(.*?)\\R---\\s*\\R(.*)$",
            Pattern.DOTALL);

    private SkillParser() {
    }

    public static Skill parse(String content) {
        Matcher m = FRONTMATTER.matcher(content);
        if (!m.find()) {
            throw new IllegalStateException("SKILL.md missing YAML frontmatter");
        }
        Map<String, Object> meta = new Yaml().load(m.group(1));
        if (meta == null) {
            throw new IllegalStateException("SKILL.md frontmatter is empty");
        }
        return new Skill(
                str(meta, "name"),
                str(meta, "description"),
                str(meta, "version"),
                str(meta, "domain"),
                listOfStrings(meta.get("triggers")),
                listOfStrings(meta.get("languages")),
                m.group(2).trim());
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : v.toString();
    }

    private static List<String> listOfStrings(Object v) {
        if (v == null) return List.of();
        if (v instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        throw new IllegalStateException("Expected list of strings, got: " + v.getClass());
    }
}
