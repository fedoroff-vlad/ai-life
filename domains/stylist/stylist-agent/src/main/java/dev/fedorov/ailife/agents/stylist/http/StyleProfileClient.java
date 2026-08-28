package dev.fedorov.ailife.agents.stylist.http;

import dev.fedorov.ailife.contracts.wardrobe.SetStyleProfileInput;
import dev.fedorov.ailife.contracts.wardrobe.StyleProfileDto;
import dev.fedorov.ailife.profile.PersonalizationProfileClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * The stylist domain's typed binding of the shared {@link PersonalizationProfileClient} (ADR-0005, slice 7):
 * the {@code get(householdId, ownerId)} / {@code set(input)} shape over {@code mcp-wardrobe}'s
 * {@code /internal/profile} passthrough (ST-d/ST-e) is generic; only the path + DTO + set-input are
 * stylist-specific. This collapses the domain's formerly-split style-profile clients — the write-only
 * {@code StyleProfileClient.set} and {@code WardrobeReadClient.getProfile} — onto one generic
 * implementation ({@code WardrobeReadClient} now delegates its profile read here, keeping only its
 * wardrobe-items read).
 *
 * <p>Stylist adopts the shared <b>client</b> but not the {@code PersonalizationProfiler} template nor the
 * {@code ProfileScopeResolver}, by design: its write flow ({@code AnalyseMe}) is a vision-caption of a
 * self-photo (not a {@code DEFAULT}-chat {@code *-profiler} text extract) and is always self-scoped (a
 * person analyses themselves → {@code ownerId = userId}, never a household-default), and a style profile
 * (body shape / colour type) is intrinsically per-person — there is no meaningful household-default style
 * row to inherit, so the family-default read rule does not apply.
 */
@Component
public class StyleProfileClient
        extends PersonalizationProfileClient<StyleProfileDto, SetStyleProfileInput> {

    public StyleProfileClient(@Qualifier("mcpWardrobeWebClient") WebClient http) {
        super(http, "/internal/profile", StyleProfileDto.class);
    }
}
