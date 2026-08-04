package dev.fedorov.ailife.profile;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.contracts.profile.HouseholdDto;
import dev.fedorov.ailife.contracts.profile.HouseholdInviteDto;
import dev.fedorov.ailife.contracts.profile.HouseholdRoutingDto;
import dev.fedorov.ailife.contracts.profile.PersonDto;
import dev.fedorov.ailife.contracts.profile.UserDto;
import dev.fedorov.ailife.profile.web.dto.CreateHouseholdRequest;
import dev.fedorov.ailife.profile.web.dto.CreatePersonRequest;
import dev.fedorov.ailife.profile.web.dto.CreateUserRequest;
import dev.fedorov.ailife.profile.web.dto.MintInviteRequest;
import dev.fedorov.ailife.profile.web.dto.RedeemInviteRequest;
import dev.fedorov.ailife.profile.web.dto.UpdatePersonRequest;
import dev.fedorov.ailife.test.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProfileServiceIntegrationTest extends AbstractPostgresIntegrationTest {


    @DynamicPropertySource
    static void wireDatasource(DynamicPropertyRegistry registry) {
        registerDataSource(registry);
    }

    @LocalServerPort
    int port;

    @Autowired
    RestTemplateBuilder restBuilder;

    @Autowired
    ObjectMapper json;

    @BeforeAll
    static void initSchema() {
        applySchema("test-schema.sql");
    }

    @Test
    void householdAndUserLifecycle() {
        RestTemplate http = restBuilder.rootUri("http://localhost:" + port).build();

        ResponseEntity<HouseholdDto> created = http.postForEntity(
                "/v1/households",
                new CreateHouseholdRequest("Fedorov household"),
                HouseholdDto.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        HouseholdDto household = created.getBody();
        assertThat(household).isNotNull();
        assertThat(household.id()).isNotNull();
        assertThat(household.createdAt()).isNotNull();

        ResponseEntity<HouseholdDto> fetched = http.getForEntity(
                "/v1/households/" + household.id(), HouseholdDto.class);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody().name()).isEqualTo("Fedorov household");

        CreateUserRequest createUser = new CreateUserRequest(
                household.id(), "vlad", null, 123456789L, "admin");
        ResponseEntity<UserDto> userCreated = http.postForEntity(
                "/v1/users", createUser, UserDto.class);
        assertThat(userCreated.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UserDto user = userCreated.getBody();
        assertThat(user).isNotNull();
        assertThat(user.locale()).isEqualTo("ru-RU");
        assertThat(user.role()).isEqualTo("admin");
        assertThat(user.telegramUserId()).isEqualTo(123456789L);

        ResponseEntity<UserDto> byTelegram = http.getForEntity(
                "/v1/users/by-telegram/123456789", UserDto.class);
        assertThat(byTelegram.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(byTelegram.getBody().id()).isEqualTo(user.id());
    }

    @Test
    void usersByHouseholdListsMembersOrderedByName() {
        RestTemplate http = restBuilder.rootUri("http://localhost:" + port).build();
        HouseholdDto h = http.postForObject(
                "/v1/households",
                new CreateHouseholdRequest("members test"),
                HouseholdDto.class);

        http.postForObject("/v1/users",
                new CreateUserRequest(h.id(), "Zara", null, 1001L, null), UserDto.class);
        http.postForObject("/v1/users",
                new CreateUserRequest(h.id(), "Anna", null, 1002L, "admin"), UserDto.class);

        UserDto[] members = http.getForObject(
                "/v1/users/by-household/" + h.id(), UserDto[].class);
        assertThat(members).hasSize(2);
        assertThat(members[0].displayName()).isEqualTo("Anna");
        assertThat(members[1].displayName()).isEqualTo("Zara");
    }

    @Test
    void userHouseholdSetReflectsMembership() {
        RestTemplate http = restBuilder.rootUri("http://localhost:" + port).build();
        HouseholdDto h = http.postForObject(
                "/v1/households",
                new CreateHouseholdRequest("membership test"),
                HouseholdDto.class);

        UserDto user = http.postForObject("/v1/users",
                new CreateUserRequest(h.id(), "vlad", null, 4242L, "admin"), UserDto.class);

        java.util.UUID[] set = http.getForObject(
                "/v1/users/" + user.id() + "/households", java.util.UUID[].class);
        assertThat(set).containsExactly(h.id());
    }

    @Test
    void householdSetForUnknownUserIs404() {
        RestTemplate http = restBuilder.rootUri("http://localhost:" + port).build();
        RestClientResponseException ex = catchThrowableOfType(
                RestClientResponseException.class,
                () -> http.getForObject(
                        "/v1/users/" + java.util.UUID.randomUUID() + "/households",
                        java.util.UUID[].class));
        assertThat(ex).isNotNull();
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void householdRoutingSplitsPersonalFromShared() {
        RestTemplate http = restBuilder.rootUri("http://localhost:" + port).build();

        // A fresh user has only their personal (self-membership) household → no shared targets.
        HouseholdDto personal = http.postForObject("/v1/households",
                new CreateHouseholdRequest("anna"), HouseholdDto.class);
        UserDto invitee = http.postForObject("/v1/users",
                new CreateUserRequest(personal.id(), "anna", null, 6001L, "admin"), UserDto.class);

        HouseholdRoutingDto before = http.getForObject(
                "/v1/users/" + invitee.id() + "/household-routing", HouseholdRoutingDto.class);
        assertThat(before.personalHouseholdId()).isEqualTo(personal.id());
        assertThat(before.sharedHouseholdIds()).isEmpty();

        // After joining a family household via invite, the family household is a shared target while
        // the personal one stays the private-scope default.
        HouseholdDto family = http.postForObject("/v1/households",
                new CreateHouseholdRequest("Fedorov family"), HouseholdDto.class);
        UserDto owner = http.postForObject("/v1/users",
                new CreateUserRequest(family.id(), "vlad", null, 6002L, "admin"), UserDto.class);
        HouseholdInviteDto minted = http.postForObject("/v1/invites",
                new MintInviteRequest(family.id(), owner.id(), "wife", null), HouseholdInviteDto.class);
        http.postForObject("/v1/invites/by-token/" + minted.token() + "/redeem",
                new RedeemInviteRequest(invitee.id()), HouseholdInviteDto.class);

        HouseholdRoutingDto after = http.getForObject(
                "/v1/users/" + invitee.id() + "/household-routing", HouseholdRoutingDto.class);
        assertThat(after.personalHouseholdId()).isEqualTo(personal.id());
        assertThat(after.sharedHouseholdIds()).containsExactly(family.id());
    }

    @Test
    void householdRoutingForUnknownUserIs404() {
        RestTemplate http = restBuilder.rootUri("http://localhost:" + port).build();
        RestClientResponseException ex = catchThrowableOfType(
                RestClientResponseException.class,
                () -> http.getForObject(
                        "/v1/users/" + java.util.UUID.randomUUID() + "/household-routing",
                        HouseholdRoutingDto.class));
        assertThat(ex).isNotNull();
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void inviteRedeemAddsInviteeToFamilyHousehold() {
        RestTemplate http = restBuilder.rootUri("http://localhost:" + port).build();

        // Owner + their family household, and an invitee living in their own personal household.
        HouseholdDto family = http.postForObject("/v1/households",
                new CreateHouseholdRequest("Fedorov family"), HouseholdDto.class);
        UserDto owner = http.postForObject("/v1/users",
                new CreateUserRequest(family.id(), "vlad", null, 5001L, "admin"), UserDto.class);
        HouseholdDto inviteePersonal = http.postForObject("/v1/households",
                new CreateHouseholdRequest("anna"), HouseholdDto.class);
        UserDto invitee = http.postForObject("/v1/users",
                new CreateUserRequest(inviteePersonal.id(), "anna", null, 5002L, "admin"), UserDto.class);

        // Owner mints a pre-authorized invite; token round-trips via by-token lookup.
        HouseholdInviteDto minted = http.postForObject("/v1/invites",
                new MintInviteRequest(family.id(), owner.id(), "wife", null), HouseholdInviteDto.class);
        assertThat(minted.token()).isNotBlank();
        assertThat(minted.status()).isEqualTo("pending");
        assertThat(minted.grantSharedAccess()).isTrue();
        HouseholdInviteDto looked = http.getForObject(
                "/v1/invites/by-token/" + minted.token(), HouseholdInviteDto.class);
        assertThat(looked.id()).isEqualTo(minted.id());

        // Redeem → invitee joins the family household (keeping their personal one).
        HouseholdInviteDto redeemed = http.postForObject(
                "/v1/invites/by-token/" + minted.token() + "/redeem",
                new RedeemInviteRequest(invitee.id()), HouseholdInviteDto.class);
        assertThat(redeemed.status()).isEqualTo("accepted");
        assertThat(redeemed.inviteeUserId()).isEqualTo(invitee.id());

        java.util.UUID[] set = http.getForObject(
                "/v1/users/" + invitee.id() + "/households", java.util.UUID[].class);
        assertThat(set).containsExactlyInAnyOrder(inviteePersonal.id(), family.id());

        // Second redeem is rejected — a pending invite is single-use.
        RestClientResponseException ex = catchThrowableOfType(
                RestClientResponseException.class,
                () -> http.postForObject(
                        "/v1/invites/by-token/" + minted.token() + "/redeem",
                        new RedeemInviteRequest(invitee.id()), HouseholdInviteDto.class));
        assertThat(ex).isNotNull();
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void mintInviteForUnknownHouseholdIsUnprocessable() {
        RestTemplate http = restBuilder.rootUri("http://localhost:" + port).build();
        RestClientResponseException ex = catchThrowableOfType(
                RestClientResponseException.class,
                () -> http.postForObject("/v1/invites",
                        new MintInviteRequest(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                                "friend", true),
                        HouseholdInviteDto.class));
        assertThat(ex).isNotNull();
        assertThat(ex.getStatusCode().value()).isEqualTo(422);
    }

    @Test
    void duplicateTelegramIdRejectedAsConflict() {
        RestTemplate http = restBuilder.rootUri("http://localhost:" + port).build();
        HouseholdDto h = http.postForObject(
                "/v1/households",
                new CreateHouseholdRequest("dup test"),
                HouseholdDto.class);

        http.postForObject("/v1/users",
                new CreateUserRequest(h.id(), "vlad", null, 777L, null),
                UserDto.class);

        RestClientResponseException ex = catchThrowableOfType(
                RestClientResponseException.class,
                () -> http.postForObject("/v1/users",
                        new CreateUserRequest(h.id(), "wife", null, 777L, null),
                        UserDto.class));

        assertThat(ex).isNotNull();
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void peopleCrudRoundTrip() {
        RestTemplate http = restBuilder.rootUri("http://localhost:" + port).build();
        HouseholdDto h = http.postForObject(
                "/v1/households",
                new CreateHouseholdRequest("people test"),
                HouseholdDto.class);

        ArrayNode interests = json.createArrayNode().add("books").add("hiking");
        ObjectNode lead = json.createObjectNode().put("gift", 30).put("greeting", 1);
        ResponseEntity<PersonDto> created = http.postForEntity(
                "/v1/people",
                new CreatePersonRequest(h.id(), "Maria", "sister", "ru-RU",
                        interests, "favourite cake: napoleon", lead),
                PersonDto.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        PersonDto person = created.getBody();
        assertThat(person).isNotNull();
        assertThat(person.id()).isNotNull();
        assertThat(person.interests().isArray()).isTrue();
        assertThat(person.leadDaysOverride().get("gift").asInt()).isEqualTo(30);

        PersonDto fetched = http.getForObject("/v1/people/" + person.id(), PersonDto.class);
        assertThat(fetched.displayName()).isEqualTo("Maria");

        http.patchForObject(
                "/v1/people/" + person.id(),
                new UpdatePersonRequest(null, null, null, null, "napoleon + earl grey", null),
                PersonDto.class);
        PersonDto afterPatch = http.getForObject("/v1/people/" + person.id(), PersonDto.class);
        assertThat(afterPatch.notes()).isEqualTo("napoleon + earl grey");
        assertThat(afterPatch.displayName()).isEqualTo("Maria");

        PersonDto[] list = http.getForObject(
                "/v1/people/by-household/" + h.id(), PersonDto[].class);
        assertThat(list).hasSize(1);
        assertThat(list[0].id()).isEqualTo(person.id());
    }

    @Test
    void linkPersonToUserWhenContactBecomesOperator() {
        RestTemplate http = restBuilder.rootUri("http://localhost:" + port).build();
        HouseholdDto h = http.postForObject(
                "/v1/households", new CreateHouseholdRequest("link test"), HouseholdDto.class);

        // A contact known to the household, and the operator account they grow into (ADR-0001 item 6).
        PersonDto person = http.postForObject("/v1/people",
                new CreatePersonRequest(h.id(), "Sofia", "daughter", "ru-RU", null, null, null),
                PersonDto.class);
        assertThat(person.userId()).isNull();
        UserDto operator = http.postForObject("/v1/users",
                new CreateUserRequest(h.id(), "Sofia", null, 9001L, null), UserDto.class);

        PersonDto linked = http.exchange(
                "/v1/people/" + person.id() + "/user", org.springframework.http.HttpMethod.PUT,
                new org.springframework.http.HttpEntity<>(new dev.fedorov.ailife.profile.web.dto.LinkPersonUserRequest(operator.id())),
                PersonDto.class).getBody();
        assertThat(linked.userId()).isEqualTo(operator.id());
        // The link survives a re-fetch and leaves the household untouched.
        PersonDto refetched = http.getForObject("/v1/people/" + person.id(), PersonDto.class);
        assertThat(refetched.userId()).isEqualTo(operator.id());
        assertThat(refetched.householdId()).isEqualTo(h.id());
    }

    @Test
    void linkPersonToUnknownUserIsUnprocessable() {
        RestTemplate http = restBuilder.rootUri("http://localhost:" + port).build();
        HouseholdDto h = http.postForObject(
                "/v1/households", new CreateHouseholdRequest("bad link test"), HouseholdDto.class);
        PersonDto person = http.postForObject("/v1/people",
                new CreatePersonRequest(h.id(), "Ivan", "friend", "ru-RU", null, null, null),
                PersonDto.class);

        RestClientResponseException ex = catchThrowableOfType(
                RestClientResponseException.class,
                () -> http.exchange(
                        "/v1/people/" + person.id() + "/user", org.springframework.http.HttpMethod.PUT,
                        new org.springframework.http.HttpEntity<>(new dev.fedorov.ailife.profile.web.dto.LinkPersonUserRequest(java.util.UUID.randomUUID())),
                        PersonDto.class));
        assertThat(ex).isNotNull();
        assertThat(ex.getStatusCode().value()).isEqualTo(422);
    }
}
