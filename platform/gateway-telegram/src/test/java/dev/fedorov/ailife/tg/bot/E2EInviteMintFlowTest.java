package dev.fedorov.ailife.tg.bot;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import dev.fedorov.ailife.contracts.profile.HouseholdInviteDto;
import dev.fedorov.ailife.contracts.profile.UserDto;
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
 * Inbound E2E closer for the owner-side <b>invite mint</b> command (ADR-0001 slice 4b-ii) — proves
 * {@code /invite <name> as <relationship>} flows through the gateway's <b>real</b> code across HTTP
 * boundaries: identity resolve → {@code POST /v1/invites}, and that the mint request body carries the
 * owner's household + id + relationship as {@code libs/contracts} expect, and the reply surfaces the
 * {@code t.me/<bot>?start=<token>} deep-link built from the returned {@link HouseholdInviteDto}.
 */
@SpringBootTest(properties = "gateway.telegram.bot-token=")
class E2EInviteMintFlowTest {

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
    void inviteCommandMintsAndRepliesWithDeepLink() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID household = UUID.randomUUID();
        long ownerTg = 42L;
        String token = "Xy9-_mintTOKEN";

        // Hop 1: identity resolve — the owner is a returning user.
        profile.enqueue(jsonBody(json.writeValueAsString(new UserDto(
                ownerId, household, "vlad", "ru", ownerTg, "admin", Instant.now()))));
        // Hop 2: mint the invite into the owner's household → returns the token.
        profile.enqueue(jsonBody(json.writeValueAsString(new HouseholdInviteDto(
                UUID.randomUUID(), token, household, ownerId, "daughter", true,
                "pending", null, Instant.now(), null))));

        var incoming = new MessageProcessor.IncomingMessage(
                ownerTg, "vlad", "ru", null,
                dev.fedorov.ailife.contracts.agent.MessageScope.PRIVATE, "11");

        String reply = processor.mintInvite(incoming, "Masha", "daughter").block();

        assertThat(reply).isNotNull();
        assertThat(reply).contains("https://t.me/ai_life_bot?start=" + token);
        assertThat(reply).contains("Masha").contains("daughter");

        // Hop 1 asserted: identity lookup by the owner's Telegram id.
        RecordedRequest resolve = profile.takeRequest();
        assertThat(resolve.getPath()).isEqualTo("/v1/users/by-telegram/" + ownerTg);

        // Hop 2 asserted via the CONTRACT: the mint body carries the owner's household, id, relationship.
        RecordedRequest mint = profile.takeRequest();
        assertThat(mint.getPath()).isEqualTo("/v1/invites");
        JsonNode body = json.readTree(mint.getBody().readUtf8());
        assertThat(body.path("familyHouseholdId").asString()).isEqualTo(household.toString());
        assertThat(body.path("inviterUserId").asString()).isEqualTo(ownerId.toString());
        assertThat(body.path("relationship").asString()).isEqualTo("daughter");
    }

    private static MockResponse jsonBody(String body) {
        return new MockResponse().setHeader("content-type", "application/json").setBody(body);
    }
}
