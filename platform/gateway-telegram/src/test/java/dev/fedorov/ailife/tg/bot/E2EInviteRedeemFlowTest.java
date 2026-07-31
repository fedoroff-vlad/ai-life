package dev.fedorov.ailife.tg.bot;

import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.profile.HouseholdInviteDto;
import dev.fedorov.ailife.contracts.profile.UserDto;
import dev.fedorov.ailife.tg.identity.InviteOutcome;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Inbound E2E closer for the <b>family-invite redemption</b> path (ADR-0001 slice 4b) — proves a
 * {@code /start <token>} deep-link flows through the gateway's <b>real</b> code across HTTP
 * boundaries: identity resolve → {@code POST /v1/invites/by-token/{token}/redeem} → inviter lookup,
 * and that the {@code libs/contracts} wire DTO ({@link HouseholdInviteDto}) plus the redeem request
 * body survive serialisation. A wiring or serialisation regression on the invite path fails here.
 *
 * <p>Complements {@link dev.fedorov.ailife.tg.identity.IdentityResolverTest} (per-seam behaviour):
 * this one asserts the redeem request body carries the invitee id as the contract expects, and that
 * the resulting {@link InviteOutcome} names the holder to ping.
 */
@SpringBootTest(properties = "gateway.telegram.bot-token=")
class E2EInviteRedeemFlowTest {

    static MockWebServer profile;

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        profile = new MockWebServer();
        try {
            profile.start();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start mock server", e);
        }
        r.add("gateway.services.profile-base-url", () -> "http://localhost:" + profile.getPort());
    }

    @Autowired MessageProcessor processor;
    @Autowired ObjectMapper json;

    @Test
    void startTokenRedeemsInviteAndNamesHolderToPing() throws Exception {
        UUID family = UUID.randomUUID();
        UUID inviteeId = UUID.randomUUID();
        UUID inviterId = UUID.randomUUID();
        long inviteeTg = 555L;
        long inviterTg = 42L;
        String token = "Ab3-_tokenXYZ";

        // Hop 1: identity resolve — the opener is a returning user (creation path is unit-covered).
        profile.enqueue(jsonBody(json.writeValueAsString(new UserDto(
                inviteeId, UUID.randomUUID(), "Masha", "ru", inviteeTg, "admin", Instant.now()))));
        // Hop 2: redeem the pending invite → accepted, tagging the inviter + relationship.
        profile.enqueue(jsonBody(json.writeValueAsString(new HouseholdInviteDto(
                UUID.randomUUID(), token, family, inviterId, "daughter", true,
                "accepted", inviteeId, Instant.now(), Instant.now()))));
        // Hop 3: inviter lookup → resolves the holder's Telegram id for the join ping.
        profile.enqueue(jsonBody(json.writeValueAsString(new UserDto(
                inviterId, family, "vlad", "ru", inviterTg, "admin", Instant.now()))));

        var incoming = new MessageProcessor.IncomingMessage(
                inviteeTg, "Masha", "ru", null,
                dev.fedorov.ailife.contracts.agent.MessageScope.PRIVATE, "7");

        InviteOutcome outcome = processor.redeemInvite(incoming, token).block();

        assertThat(outcome).isNotNull();
        assertThat(outcome.inviteeReply()).contains("daughter");
        assertThat(outcome.holderTelegramId()).isEqualTo(inviterTg);
        assertThat(outcome.holderReply()).contains("Masha").contains("daughter");

        // Hop 1 asserted: identity lookup by the opener's Telegram id.
        RecordedRequest resolve = profile.takeRequest();
        assertThat(resolve.getPath()).isEqualTo("/v1/users/by-telegram/" + inviteeTg);

        // Hop 2 asserted via the CONTRACT: the redeem body carries the invitee id at the token path.
        RecordedRequest redeem = profile.takeRequest();
        assertThat(redeem.getPath()).isEqualTo("/v1/invites/by-token/" + token + "/redeem");
        assertThat(json.readTree(redeem.getBody().readUtf8()).path("inviteeUserId").asText())
                .isEqualTo(inviteeId.toString());

        // Hop 3 asserted: the inviter is looked up by id to resolve the holder ping target.
        RecordedRequest inviter = profile.takeRequest();
        assertThat(inviter.getPath()).isEqualTo("/v1/users/" + inviterId);
    }

    private static MockResponse jsonBody(String body) {
        return new MockResponse().setHeader("content-type", "application/json").setBody(body);
    }
}
