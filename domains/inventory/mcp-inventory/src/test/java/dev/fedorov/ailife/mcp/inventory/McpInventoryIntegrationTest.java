package dev.fedorov.ailife.mcp.inventory;

import dev.fedorov.ailife.contracts.inventory.ContainerDto;
import dev.fedorov.ailife.contracts.inventory.ContainerViewDto;
import dev.fedorov.ailife.contracts.inventory.ItemDto;
import dev.fedorov.ailife.contracts.inventory.ItemLocationDto;
import dev.fedorov.ailife.contracts.inventory.SaveContainerInput;
import dev.fedorov.ailife.contracts.inventory.SaveItemInput;
import dev.fedorov.ailife.contracts.inventory.SaveZoneInput;
import dev.fedorov.ailife.contracts.inventory.StorageZoneDto;
import dev.fedorov.ailife.mcp.inventory.tools.InventoryMcpTools;
import dev.fedorov.ailife.test.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * IN-a acceptance (plans/inventory.md): container-by-token, rename keeps the printed identity, item
 * search returns the location.
 *
 * Tests aren't isolated across methods (shared SpringBootTest context + DB) — assertions scope on
 * per-test households to stay deterministic (mirrors mcp-docs / mcp-briefing).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class McpInventoryIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired InventoryMcpTools tools;
    @Autowired JdbcTemplate jdbc;
    @org.springframework.boot.test.web.server.LocalServerPort int port;

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry registry) {
        registerDataSource(registry);
    }

    @BeforeAll
    static void applyOnce() {
        applySchema("test-schema.sql");
    }

    @Test
    void saveZoneUpsertsOnNameCaseInsensitively() {
        UUID h = UUID.randomUUID();
        seedHousehold(h);

        StorageZoneDto first = tools.saveZone(new SaveZoneInput(h, null, "Кладовка", "closet", "yellow", null));
        StorageZoneDto again = tools.saveZone(new SaveZoneInput(h, null, "кладовка", null, null, "верхняя полка"));

        assertThat(again.id()).isEqualTo(first.id());
        assertThat(again.kind()).isEqualTo("closet");          // untouched by the second call
        assertThat(again.labelColour()).isEqualTo("yellow");   // colour of the label stock, kept
        assertThat(again.note()).isEqualTo("верхняя полка");
        assertThat(tools.listZones(h)).hasSize(1);
    }

    @Test
    void getContainerByTokenReturnsZoneAndItemsInInsertionOrder() {
        UUID h = UUID.randomUUID();
        seedHousehold(h);
        UUID owner = seedUser(h);
        StorageZoneDto zone = tools.saveZone(new SaveZoneInput(h, owner, "Гараж", "garage", "green", null));

        ContainerDto box = tools.saveContainer(new SaveContainerInput(
                null, h, owner, zone.id(), null, "Новый год", null, null, null, null));
        assertThat(box.code()).isEqualTo("B-01");
        assertThat(box.qrToken()).isNotBlank();
        assertThat(box.status()).isEqualTo("open");
        assertThat(box.kind()).isEqualTo("box");

        tools.saveItem(new SaveItemInput(box.id(), "media-1", "ёлочная гирлянда", "белая, 10 м", null, null));
        tools.saveItem(new SaveItemInput(box.id(), "media-2", "ёлочные игрушки", "коробка шаров", null, 12));

        ContainerViewDto view = tools.getContainerByToken(box.qrToken());
        assertThat(view).isNotNull();
        assertThat(view.container().id()).isEqualTo(box.id());
        assertThat(view.zone().name()).isEqualTo("Гараж");
        assertThat(view.items()).extracting(ItemDto::mediaId).containsExactly("media-1", "media-2");
        assertThat(view.items().get(0).qty()).isEqualTo(1);   // defaulted
        assertThat(view.items().get(1).qty()).isEqualTo(12);

        assertThat(tools.getContainerByToken("no-such-token")).isNull();
    }

    @Test
    void renameAndZoneMoveKeepThePrintedIdentity() {
        UUID h = UUID.randomUUID();
        seedHousehold(h);
        StorageZoneDto closet = tools.saveZone(new SaveZoneInput(h, null, "Кладовка", "closet", null, null));
        StorageZoneDto dacha = tools.saveZone(new SaveZoneInput(h, null, "Дача", "dacha", null, null));

        ContainerDto box = tools.saveContainer(new SaveContainerInput(
                null, h, null, closet.id(), null, "кухня — посуда", null, null, null, null));
        String printedToken = box.qrToken();
        String printedCode = box.code();

        // Rename + move to another zone + close it: everything a user does after the label is stuck on.
        ContainerDto renamed = tools.saveContainer(new SaveContainerInput(
                box.id(), null, null, dacha.id(), null, "кухня — посуда и кастрюли",
                null, "packed", "кухня", null));

        assertThat(renamed.qrToken()).isEqualTo(printedToken);
        assertThat(renamed.code()).isEqualTo(printedCode);
        assertThat(renamed.label()).isEqualTo("кухня — посуда и кастрюли");
        assertThat(renamed.zoneId()).isEqualTo(dacha.id());
        assertThat(renamed.status()).isEqualTo("packed");
        assertThat(renamed.destination()).isEqualTo("кухня");
        assertThat(renamed.closedAt()).isNotNull();

        // The label stuck on the box still resolves.
        assertThat(tools.getContainerByToken(printedToken)).isNotNull();
    }

    @Test
    void searchItemsReturnsWhereTheThingIs() {
        UUID h = UUID.randomUUID();
        seedHousehold(h);
        StorageZoneDto closet = tools.saveZone(new SaveZoneInput(h, null, "Кладовка", "closet", null, null));
        ContainerDto box = tools.saveContainer(new SaveContainerInput(
                null, h, null, closet.id(), "B-07", "Новый год", null, null, null, null));
        tools.saveItem(new SaveItemInput(box.id(), "m1", "ёлочная гирлянда", "белая", null, null));

        // Another household's identical item must not leak into the answer.
        UUID other = UUID.randomUUID();
        seedHousehold(other);
        ContainerDto otherBox = tools.saveContainer(new SaveContainerInput(
                null, other, null, null, null, "чужая коробка", null, null, null, null));
        tools.saveItem(new SaveItemInput(otherBox.id(), "m2", "ёлочная гирлянда", "чужая", null, null));

        List<ItemLocationDto> found = tools.searchItems(h, "гирлянда", null);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).item().mediaId()).isEqualTo("m1");
        assertThat(found.get(0).container().code()).isEqualTo("B-07");
        assertThat(found.get(0).container().label()).isEqualTo("Новый год");
        assertThat(found.get(0).zone().name()).isEqualTo("Кладовка");

        assertThat(tools.searchItems(h, "нетакого", null)).isEmpty();
    }

    @Test
    void listContainersNarrowsByZoneAndStatus() {
        UUID h = UUID.randomUUID();
        seedHousehold(h);
        StorageZoneDto garage = tools.saveZone(new SaveZoneInput(h, null, "Гараж", "garage", null, null));
        ContainerDto packed = tools.saveContainer(new SaveContainerInput(
                null, h, null, garage.id(), null, "инструменты", null, "packed", null, null));
        tools.saveContainer(new SaveContainerInput(
                null, h, null, null, null, "без зоны", "loose", null, null, null));

        assertThat(tools.listContainers(h, garage.id(), null, null))
                .extracting(ContainerDto::id).containsExactly(packed.id());
        assertThat(tools.listContainers(h, null, "packed", null))
                .extracting(ContainerDto::id).containsExactly(packed.id());
        assertThat(tools.listContainers(h, null, null, null)).hasSize(2);
    }

    @Test
    void deleteItemReportsWhetherItExisted() {
        UUID h = UUID.randomUUID();
        seedHousehold(h);
        ContainerDto box = tools.saveContainer(new SaveContainerInput(
                null, h, null, null, null, "коробка", null, null, null, null));
        ItemDto item = tools.saveItem(new SaveItemInput(box.id(), "m", "чайник", null, null, null));

        assertThat(tools.deleteItem(item.id())).isTrue();
        assertThat(tools.deleteItem(item.id())).isFalse();
        assertThat(tools.listItems(box.id())).isEmpty();
    }

    @Test
    void requiredFieldsAndUnknownReferencesAreRejected() {
        UUID h = UUID.randomUUID();
        seedHousehold(h);

        assertThatThrownBy(() -> tools.saveZone(new SaveZoneInput(h, null, "  ", null, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("name");
        assertThatThrownBy(() -> tools.saveContainer(new SaveContainerInput(
                null, null, null, null, null, "нет household", null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("householdId");
        assertThatThrownBy(() -> tools.saveItem(new SaveItemInput(UUID.randomUUID(), "m", null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No such container");
        assertThatThrownBy(() -> tools.saveContainer(new SaveContainerInput(
                UUID.randomUUID(), h, null, null, null, "нет такой", null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No such container");
    }

    @Test
    void internalEndpointsCoverTheAgentSurface() throws Exception {
        UUID h = UUID.randomUUID();
        seedHousehold(h);
        UUID owner = seedUser(h);

        WebTestClient client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port).build();

        StorageZoneDto zone = client.post().uri("/internal/zones")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new SaveZoneInput(h, owner, "Балкон", "balcony", "red", null))
                .exchange().expectStatus().isOk()
                .expectBody(StorageZoneDto.class).returnResult().getResponseBody();
        assertThat(zone).isNotNull();
        assertThat(zone.labelColour()).isEqualTo("red");

        ContainerDto box = client.post().uri("/internal/containers")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new SaveContainerInput(null, h, owner, zone.id(), null, "зимние вещи",
                        null, null, null, null))
                .exchange().expectStatus().isOk()
                .expectBody(ContainerDto.class).returnResult().getResponseBody();
        assertThat(box).isNotNull();

        client.post().uri("/internal/items")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new SaveItemInput(box.id(), "media-http", "лыжные ботинки", "чёрные, 43",
                        MAPPER.readTree("[\"winter\"]"), null))
                .exchange().expectStatus().isOk();

        // A scanned label → the whole card in one read.
        ContainerViewDto scanned = client.get()
                .uri("/internal/containers/by-token/{t}", box.qrToken())
                .exchange().expectStatus().isOk()
                .expectBody(ContainerViewDto.class).returnResult().getResponseBody();
        assertThat(scanned).isNotNull();
        assertThat(scanned.zone().name()).isEqualTo("Балкон");
        assertThat(scanned.items()).extracting(ItemDto::title).containsExactly("лыжные ботинки");

        // Unknown token → 404, so a scan of a stale label is distinguishable from an empty box.
        client.get().uri("/internal/containers/by-token/{t}", "nope")
                .exchange().expectStatus().isNotFound();

        // Missing required field → the tool's guard surfaces as 400.
        client.post().uri("/internal/items")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new SaveItemInput(null, null, null, null, null, null))
                .exchange().expectStatus().isBadRequest();

        List<ItemLocationDto> found = client.get()
                .uri(b -> b.path("/internal/items/search")
                        .queryParam("householdId", h).queryParam("query", "ботинки").build())
                .exchange().expectStatus().isOk()
                .expectBodyList(ItemLocationDto.class).returnResult().getResponseBody();
        assertThat(found).hasSize(1);
        assertThat(found.get(0).container().id()).isEqualTo(box.id());

        List<ItemDto> listed = client.get()
                .uri(b -> b.path("/internal/items").queryParam("containerId", box.id()).build())
                .exchange().expectStatus().isOk()
                .expectBodyList(ItemDto.class).returnResult().getResponseBody();
        assertThat(listed).hasSize(1);

        client.delete().uri("/internal/items/{id}", listed.get(0).id())
                .exchange().expectStatus().isNoContent();
        client.delete().uri("/internal/items/{id}", listed.get(0).id())
                .exchange().expectStatus().isNotFound();
    }

    private void seedHousehold(UUID id) {
        jdbc.update("INSERT INTO core.households (id, name) VALUES (?, ?)", id, "h-" + id);
    }

    private UUID seedUser(UUID household) {
        UUID userId = UUID.randomUUID();
        jdbc.update("INSERT INTO core.users (id, household_id, display_name) VALUES (?, ?, ?)",
                userId, household, "owner-" + userId);
        return userId;
    }
}
