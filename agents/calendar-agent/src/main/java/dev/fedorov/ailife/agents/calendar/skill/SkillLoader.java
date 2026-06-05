package dev.fedorov.ailife.agents.calendar.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scans the classpath for {@code SKILL.md} files matching the configured
 * pattern (default: {@code classpath*:skills/calendar/} *{@code /SKILL.md}) and
 * parses them into {@link Skill}s. The repo's {@code skills/calendar/} dir is
 * copied onto the classpath at build time (see {@code pom.xml} {@code <resources>}).
 *
 * <p>Failures parsing one skill are logged and skipped — the rest still load.
 * Empty registry is fine; the agent just won't handle any trigger kinds until
 * a SKILL.md ships for them.
 */
@Configuration
public class SkillLoader {

    private static final Logger log = LoggerFactory.getLogger(SkillLoader.class);

    private static final Pattern FRONTMATTER = Pattern.compile(
            "^---\\s*\\R(.*?)\\R---\\s*\\R(.*)$",
            Pattern.DOTALL);

    @Bean
    public SkillRegistry skillRegistry(
            @Value("${calendar-agent.skills-classpath:classpath*:skills/calendar/*/SKILL.md}")
                    String pattern) {
        List<Skill> loaded = new ArrayList<>();
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(pattern);
            for (Resource r : resources) {
                try (InputStream in = r.getInputStream()) {
                    String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    Skill s = parse(content);
                    loaded.add(s);
                    log.info("loaded skill {} (triggers={})", s.name(), s.triggers());
                } catch (RuntimeException | IOException e) {
                    log.warn("skipped malformed skill at {}: {}", r.getDescription(), e.toString());
                }
            }
        } catch (IOException e) {
            log.warn("skill scan failed for pattern={}: {}", pattern, e.toString());
        }
        return new SkillRegistry(loaded);
    }

    static Skill parse(String content) {
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

    @SuppressWarnings("unchecked")
    private static List<String> listOfStrings(Object v) {
        if (v == null) return List.of();
        if (v instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        throw new IllegalStateException("Expected list of strings, got: " + v.getClass());
    }
}
