package dev.fedorov.ailife.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.contracts.profile.HouseholdDto;
import dev.fedorov.ailife.contracts.profile.PersonDto;
import dev.fedorov.ailife.contracts.profile.UserDto;
import dev.fedorov.ailife.profile.web.dto.CreateHouseholdRequest;
import dev.fedorov.ailife.profile.web.dto.CreatePersonRequest;
import dev.fedorov.ailife.profile.web.dto.CreateUserRequest;
import dev.fedorov.ailife.profile.web.dto.UpdatePersonRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ProfileServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("ailife")
            .withUsername("ailife")
            .withPassword("ailife")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("test-schema.sql"),
                    "/docker-entrypoint-initdb.d/00-test-schema.sql");

    @DynamicPropertySource
    static void wireDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @LocalServerPort
    int port;

    @Autowired
    RestTemplateBuilder restBuilder;

    @Autowired
    ObjectMapper json;

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
                () -> http.postForObject("/v1/users",
                        new CreateUserRequest(h.id(), "wife", null, 777L, null),
                        UserDto.class),
                RestClientResponseException.class);

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
}
