package dev.fedorov.ailife.agents.creator.http;

import dev.fedorov.ailife.contracts.creator.CreatorProfileDto;
import dev.fedorov.ailife.contracts.creator.SetCreatorProfileInput;
import dev.fedorov.ailife.profile.PersonalizationProfileClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * The creator domain's typed binding of the shared {@link PersonalizationProfileClient} (ADR-0005): the
 * {@code get(householdId, ownerId)} / {@code set(input)} shape over {@code mcp-creator}'s
 * {@code /internal/creator-profile} passthrough (CR-c1) is generic; only the path + DTO + set-input are
 * creator-specific. The read-resolution fallback (self → own-default → family-default) lives in the shared
 * {@code ProfileScopeResolver}, and the extract→write flow in the {@code PersonalizationProfiler} template.
 */
@Component
public class CreatorProfileClient
        extends PersonalizationProfileClient<CreatorProfileDto, SetCreatorProfileInput> {

    public CreatorProfileClient(@Qualifier("mcpCreatorWebClient") WebClient http) {
        super(http, "/internal/creator-profile", CreatorProfileDto.class);
    }
}
