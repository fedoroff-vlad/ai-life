package dev.fedorov.ailife.media;

import dev.fedorov.ailife.contracts.media.MediaObjectDto;
import dev.fedorov.ailife.test.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.client.MultipartBodyBuilder.PartBuilder;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@AutoConfigureWebTestClient
class MediaServiceIntegrationTest extends AbstractPostgresIntegrationTest {

    /**
     * The object store is <b>SeaweedFS</b>, not MinIO: MinIO stopped publishing its server image to
     * any public registry (Docker Hub 401, quay.io 401 since 2026-09-24, ghcr 403), so a fresh host
     * — CI or the deploy box — can no longer pull it at all. SeaweedFS is Apache-2.0, ships a public
     * image and speaks the same S3 API, so the service code is unchanged (see media-service/README).
     *
     * <p>{@code server -s3} runs master+volume+filer+S3 in one process — the whole store the service
     * needs. With no {@code -s3.config} identity file it accepts any credentials, so the keys below
     * are arbitrary; the deploy mounts a real identity file instead.
     */
    private static final int S3_PORT = 8333;

    @Container
    static GenericContainer<?> objectStore = new GenericContainer<>(
            DockerImageName.parse("chrislusf/seaweedfs:3.97"))
            .withCommand("server", "-dir=/data", "-s3", "-s3.port=" + S3_PORT, "-ip.bind=0.0.0.0")
            .withExposedPorts(S3_PORT)
            .waitingFor(Wait.forLogMessage(".*Start Seaweed S3 API Server.*", 1));

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry registry) {
        registerDataSource(registry);
        registry.add("media.s3.endpoint", () -> "http://" + objectStore.getHost() + ":"
                + objectStore.getMappedPort(S3_PORT));
        registry.add("media.s3.access-key", () -> "test-access");
        registry.add("media.s3.secret-key", () -> "test-secret");
        // Small cap so the oversized-rejection test can stay tiny.
        registry.add("media.max-bytes", () -> 1024);
    }

    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;

    static UUID household;

    @BeforeAll
    static void seedHousehold(@Autowired JdbcTemplate jdbc) {
        applySchema("test-schema.sql");
        household = UUID.randomUUID();
        jdbc.update("INSERT INTO core.households (id, name) VALUES (?, ?)", household, "alpha");
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void uploadStoresObjectAndDownloadReturnsSameBytes() {
        byte[] bytes = "fake-jpeg-bytes-receipt".getBytes(StandardCharsets.UTF_8);

        MediaObjectDto dto = upload(bytes, "receipt.jpg", MediaType.IMAGE_JPEG, "telegram", null);

        assertThat(dto).isNotNull();
        assertThat(dto.id()).isNotNull();
        assertThat(dto.householdId()).isEqualTo(household);
        assertThat(dto.kind()).isEqualTo("image");           // derived from image/* mime
        assertThat(dto.mimeType()).isEqualTo("image/jpeg");
        assertThat(dto.sizeBytes()).isEqualTo(bytes.length);
        assertThat(dto.sha256()).hasSize(64);
        assertThat(dto.source()).isEqualTo("telegram");

        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM media.media_object WHERE id = ?", Integer.class, dto.id());
        assertThat(rows).isEqualTo(1);

        // Bytes round-trip through the object store unchanged.
        byte[] back = client().get().uri("/v1/media/{id}", dto.id())
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.IMAGE_JPEG)
                .expectBody(byte[].class)
                .returnResult().getResponseBody();
        assertThat(back).isEqualTo(bytes);

        // Metadata endpoint mirrors the upload response.
        MediaObjectDto meta = client().get().uri("/v1/media/{id}/meta", dto.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody(MediaObjectDto.class)
                .returnResult().getResponseBody();
        assertThat(meta).isNotNull();
        assertThat(meta.sha256()).isEqualTo(dto.sha256());
    }

    @Test
    void deleteRemovesObjectAndRow() {
        MediaObjectDto dto = upload("to-delete".getBytes(StandardCharsets.UTF_8),
                "f.bin", MediaType.APPLICATION_OCTET_STREAM, null, null);
        assertThat(dto.kind()).isEqualTo("file");            // non-image mime → file

        client().delete().uri("/v1/media/{id}", dto.id())
                .exchange().expectStatus().isNoContent();

        client().get().uri("/v1/media/{id}", dto.id())
                .exchange().expectStatus().isNotFound();
        client().get().uri("/v1/media/{id}/meta", dto.id())
                .exchange().expectStatus().isNotFound();
        // Second delete is a clean 404, not an error.
        client().delete().uri("/v1/media/{id}", dto.id())
                .exchange().expectStatus().isNotFound();
    }

    @Test
    void oversizedUploadRejectedWith400() {
        byte[] tooBig = new byte[2048]; // cap is 1024 in this test context
        client().post().uri("/v1/media")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(multipart(
                        tooBig, "big.bin", MediaType.APPLICATION_OCTET_STREAM, null, null)))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void unknownIdReturns404() {
        client().get().uri("/v1/media/{id}", UUID.randomUUID())
                .exchange().expectStatus().isNotFound();
        client().get().uri("/v1/media/{id}/meta", UUID.randomUUID())
                .exchange().expectStatus().isNotFound();
    }

    private MediaObjectDto upload(byte[] bytes, String filename, MediaType type,
                                  String source, UUID ownerId) {
        return client().post().uri("/v1/media")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(multipart(bytes, filename, type, source, ownerId)))
                .exchange()
                .expectStatus().isOk()
                .expectBody(MediaObjectDto.class)
                .returnResult().getResponseBody();
    }

    private MultiValueMap<String, org.springframework.http.HttpEntity<?>> multipart(
            byte[] bytes, String filename, MediaType type, String source, UUID ownerId) {
        var builder = new org.springframework.http.client.MultipartBodyBuilder();
        PartBuilder file = builder.part("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        file.header(HttpHeaders.CONTENT_TYPE, type.toString());
        builder.part("householdId", household.toString());
        if (source != null) builder.part("source", source);
        if (ownerId != null) builder.part("ownerId", ownerId.toString());
        return builder.build();
    }
}
