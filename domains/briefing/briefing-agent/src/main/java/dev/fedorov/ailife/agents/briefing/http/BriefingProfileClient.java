package dev.fedorov.ailife.agents.briefing.http;

import dev.fedorov.ailife.contracts.briefing.BriefingProfileDto;
import dev.fedorov.ailife.contracts.briefing.SetBriefingProfileInput;
import dev.fedorov.ailife.profile.PersonalizationProfileClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * The briefing domain's typed binding of the shared {@link PersonalizationProfileClient} (ADR-0005): the
 * {@code get(householdId, ownerId)} / {@code set(input)} shape over {@code mcp-briefing}'s
 * {@code /internal/briefing-profile} passthrough is generic; only the path + DTO + set-input are
 * briefing-specific. The read-resolution fallback (self → own-default → family-default) lives in the shared
 * {@code ProfileScopeResolver}, and the extract→write flow in the {@code PersonalizationProfiler} template.
 */
@Component
public class BriefingProfileClient
        extends PersonalizationProfileClient<BriefingProfileDto, SetBriefingProfileInput> {

    public BriefingProfileClient(@Qualifier("mcpBriefingWebClient") WebClient http) {
        super(http, "/internal/briefing-profile", BriefingProfileDto.class);
    }
}
