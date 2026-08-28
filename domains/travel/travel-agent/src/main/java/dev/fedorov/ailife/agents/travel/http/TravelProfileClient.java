package dev.fedorov.ailife.agents.travel.http;

import dev.fedorov.ailife.contracts.travel.SetTravelProfileInput;
import dev.fedorov.ailife.contracts.travel.TravelProfileDto;
import dev.fedorov.ailife.profile.PersonalizationProfileClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * The travel domain's typed binding of the shared {@link PersonalizationProfileClient} (ADR-0005): the
 * {@code get(householdId, ownerId)} / {@code set(input)} shape over {@code mcp-travel}'s
 * {@code /internal/travel-profile} passthrough is generic; only the path + DTO + set-input are
 * travel-specific. The read-resolution fallback (self → own-default → family-default) lives in the shared
 * {@code ProfileScopeResolver}, and the extract→write flow (with the geocode + vocabulary post-steps) in the
 * travel {@code ProfileSpec} on the {@code PersonalizationProfiler} template. The generic client's 404→empty
 * covers mcp-travel's "none set" (204/404 both resolve to an empty read).
 */
@Component
public class TravelProfileClient
        extends PersonalizationProfileClient<TravelProfileDto, SetTravelProfileInput> {

    public TravelProfileClient(@Qualifier("mcpTravelWebClient") WebClient http) {
        super(http, "/internal/travel-profile", TravelProfileDto.class);
    }
}
