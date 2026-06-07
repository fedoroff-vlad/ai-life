package dev.fedorov.ailife.agentruntime.config;

import dev.fedorov.ailife.agentruntime.http.MemoryClient;
import dev.fedorov.ailife.agentruntime.http.NotifierClient;
import dev.fedorov.ailife.agentruntime.http.ProfileClient;
import dev.fedorov.ailife.agentruntime.manifest.ManifestParser;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillParser;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Wires the agent-runtime beans into a Spring Boot agent: the parsed
 * {@link AgentManifest} (singleton) and a {@link SkillRegistry} keyed by
 * trigger kind. The library does NOT use {@code @Component} scanning — agents
 * opt in with {@code @Import(AgentRuntimeConfig.class)} on their main class
 * because the runtime's package sits outside their auto-scan root.
 *
 * <p>Both beans throw at startup if {@code agent.manifest-classpath} points at
 * a missing or malformed file — surfacing config errors loudly is the point.
 * Per-skill parse failures are logged and skipped so one broken SKILL.md
 * doesn't take down the whole agent.
 */
@Configuration
@EnableConfigurationProperties(AgentRuntimeProperties.class)
public class AgentRuntimeConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntimeConfig.class);

    @Bean
    public AgentManifest agentManifest(AgentRuntimeProperties props) {
        String path = props.getManifestClasspath();
        String content;
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read AGENT.md from classpath: " + path, e);
        }
        AgentManifest manifest = ManifestParser.parse(content);
        log.info("loaded AGENT.md manifest: name={} version={} mcp={} triggers={}",
                manifest.name(), manifest.version(), manifest.mcp(),
                manifest.triggers() == null ? 0 : manifest.triggers().size());
        return manifest;
    }

    @Bean
    public ProfileClient profileClient(
            @Qualifier("profileServiceWebClient") WebClient profileServiceWebClient) {
        return new ProfileClient(profileServiceWebClient);
    }

    @Bean
    public NotifierClient notifierClient(
            @Qualifier("notifierWebClient") WebClient notifierWebClient) {
        return new NotifierClient(notifierWebClient);
    }

    @Bean
    public MemoryClient memoryClient(
            @Qualifier("memoryServiceWebClient") WebClient memoryServiceWebClient,
            AgentRuntimeProperties props) {
        return new MemoryClient(memoryServiceWebClient, props);
    }

    @Bean
    public SkillRegistry skillRegistry(AgentRuntimeProperties props) {
        String pattern = props.getSkillsClasspath();
        List<Skill> loaded = new ArrayList<>();
        if (pattern == null || pattern.isBlank()) {
            log.info("agent.skills-classpath is unset — registry will be empty");
            return new SkillRegistry(loaded);
        }
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(pattern);
            for (Resource r : resources) {
                try (InputStream in = r.getInputStream()) {
                    String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    Skill s = SkillParser.parse(content);
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
}
