package dev.fedorov.ailife.tg.identity;

import dev.fedorov.ailife.contracts.profile.UserDto;
import dev.fedorov.ailife.tg.config.GatewayProperties;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Maps a Telegram user to an internal {@link UserDto}, creating the household + user
 * on first contact. Stage 0 behaviour: one household per first user, role=admin; if a
 * user shows up later with a different telegram id, they are attached to the *first*
 * household by default. This will be refined when invitations land in a later stage.
 */
@Component
public class IdentityResolver {

    private final ProfileClient profile;
    private final GatewayProperties props;

    public IdentityResolver(ProfileClient profile, GatewayProperties props) {
        this.profile = profile;
        this.props = props;
    }

    public Mono<UserDto> resolve(long telegramUserId, String displayName, String languageCode) {
        String locale = languageCode != null && !languageCode.isBlank() ? languageCode : "ru-RU";
        return profile.findByTelegramId(telegramUserId)
                .switchIfEmpty(Mono.defer(() ->
                        profile.createHousehold(props.getTelegram().getDefaultHouseholdName())
                                .flatMap(h -> profile.createUser(
                                        h.id().toString(),
                                        displayName != null ? displayName : "user-" + telegramUserId,
                                        telegramUserId,
                                        locale,
                                        "admin"))));
    }
}
