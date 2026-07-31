package dev.fedorov.ailife.tg.identity;

import dev.fedorov.ailife.contracts.profile.UserDto;
import dev.fedorov.ailife.tg.config.GatewayProperties;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Maps a Telegram user to an internal {@link UserDto}, creating their **personal household** + user
 * on first contact (ADR-0001). Identity is 1:N: every new user gets their own isolated single-member
 * household (named after them), of which they are the {@code admin} — never auto-attached to another
 * user's household. profile-service records the self-membership on user creation. Joining a shared
 * (family) household is a separate, owner-gated invite/approve step (slice 4), not part of first
 * contact — so a friend who simply DMs the bot stays fully isolated in their own space.
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
        String name = displayName != null && !displayName.isBlank()
                ? displayName : "user-" + telegramUserId;
        return profile.findByTelegramId(telegramUserId)
                .switchIfEmpty(Mono.defer(() ->
                        profile.createHousehold(personalHouseholdName(name))
                                .flatMap(h -> profile.createUser(
                                        h.id().toString(),
                                        name,
                                        telegramUserId,
                                        locale,
                                        "admin"))));
    }

    /** Name for a user's personal household. Falls back to the configured default when unnamed. */
    private String personalHouseholdName(String userName) {
        return userName.startsWith("user-")
                ? props.getTelegram().getDefaultHouseholdName()
                : userName;
    }
}
