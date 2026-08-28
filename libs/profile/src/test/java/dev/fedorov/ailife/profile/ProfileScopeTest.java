package dev.fedorov.ailife.profile;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The write-scope rule (ADR-0005): {@code self → the speaker}, {@code household → the shared default}. */
class ProfileScopeTest {

    private final UUID user = UUID.randomUUID();

    @Test
    void selfScopeWritesTheSpeakerAsOwner() {
        assertThat(ProfileScope.ownerId("self", user)).isEqualTo(user);
        assertThat(ProfileScope.isHousehold("self")).isFalse();
    }

    @Test
    void householdScopeWritesTheDefault() {
        assertThat(ProfileScope.ownerId("household", user)).isNull();
        assertThat(ProfileScope.ownerId("HOUSEHOLD", user)).isNull();
        assertThat(ProfileScope.isHousehold("Household")).isTrue();
    }

    @Test
    void unknownOrNullScopeDefaultsToSelf() {
        assertThat(ProfileScope.ownerId(null, user)).isEqualTo(user);
        assertThat(ProfileScope.ownerId("", user)).isEqualTo(user);
        assertThat(ProfileScope.ownerId("shared", user)).isEqualTo(user);
    }
}
