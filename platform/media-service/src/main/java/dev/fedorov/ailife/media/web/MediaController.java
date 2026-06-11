package dev.fedorov.ailife.media.web;

import dev.fedorov.ailife.contracts.media.MediaObjectDto;
import dev.fedorov.ailife.media.service.FetchedMedia;
import dev.fedorov.ailife.media.service.MediaService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

/**
 * REST surface for the central media catalogue.
 *
 * <ul>
 *   <li>{@code POST /v1/media} — multipart upload (part {@code file} + form fields); returns the
 *       {@link MediaObjectDto} metadata.</li>
 *   <li>{@code GET /v1/media/{id}} — raw bytes with the stored content-type.</li>
 *   <li>{@code GET /v1/media/{id}/meta} — metadata only (JSON).</li>
 *   <li>{@code DELETE /v1/media/{id}} — drop object + row.</li>
 * </ul>
 *
 * No auth — internal-only by convention (reachable only on the docker network), same posture as
 * the other platform services. Validation failures (empty / oversized upload) surface as 400.
 */
@RestController
@RequestMapping("/v1/media")
public class MediaController {

    private final MediaService service;

    public MediaController(MediaService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file,
                                    @RequestParam("householdId") UUID householdId,
                                    @RequestParam(value = "ownerId", required = false) UUID ownerId,
                                    @RequestParam(value = "kind", required = false) String kind,
                                    @RequestParam(value = "source", required = false) String source) {
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(error("could not read upload: " + e.getMessage()));
        }
        try {
            MediaObjectDto dto = service.store(
                    householdId, ownerId, kind, source, file.getContentType(), bytes);
            return ResponseEntity.ok(dto);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<byte[]> download(@PathVariable UUID id) {
        return service.fetch(id)
                .map(this::asBytesResponse)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping(value = "/{id}/meta", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MediaObjectDto> meta(@PathVariable UUID id) {
        return service.meta(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        return service.delete(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    private ResponseEntity<byte[]> asBytesResponse(FetchedMedia media) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, media.mimeType())
                .body(media.bytes());
    }

    private static java.util.Map<String, String> error(String message) {
        return java.util.Map.of("error", message);
    }
}
