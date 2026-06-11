package dev.fedorov.ailife.media.service;

import dev.fedorov.ailife.contracts.media.MediaObjectDto;
import dev.fedorov.ailife.media.config.MediaServiceProperties;
import dev.fedorov.ailife.media.domain.MediaRepository;
import dev.fedorov.ailife.media.domain.MediaRow;
import dev.fedorov.ailife.media.storage.ObjectStore;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Catalogue + object-store orchestration. Store: cap → hash → put-to-MinIO → insert-row. Fetch:
 * row lookup → stream bytes back. Delete: remove object then row. The object key is
 * {@code <householdId>/<objectId>} so a household's blobs cluster under one prefix (handy for
 * lifecycle rules / bulk eviction later) and key collisions are impossible (object id is a UUID).
 */
@Service
public class MediaService {

    private final MediaRepository repo;
    private final ObjectStore store;
    private final long maxBytes;

    public MediaService(MediaRepository repo, ObjectStore store, MediaServiceProperties props) {
        this.repo = repo;
        this.store = store;
        this.maxBytes = props.getMaxBytes();
    }

    public MediaObjectDto store(UUID householdId,
                                UUID ownerId,
                                String kind,
                                String source,
                                String mimeType,
                                byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("empty upload");
        }
        if (bytes.length > maxBytes) {
            throw new IllegalArgumentException(
                    "upload of " + bytes.length + " bytes exceeds the " + maxBytes + "-byte cap");
        }
        String mime = (mimeType == null || mimeType.isBlank()) ? "application/octet-stream" : mimeType;
        String resolvedKind = (kind == null || kind.isBlank()) ? kindFromMime(mime) : kind;

        UUID id = UUID.randomUUID();
        String key = householdId + "/" + id;
        store.put(key, bytes, mime);
        MediaRow row = repo.insert(id, householdId, ownerId, resolvedKind, mime,
                bytes.length, sha256(bytes), store.bucket(), key, source);
        return row.toDto();
    }

    public Optional<FetchedMedia> fetch(UUID id) {
        return repo.findById(id)
                .map(row -> new FetchedMedia(row.mimeType(), store.get(row.storageKey())));
    }

    public Optional<MediaObjectDto> meta(UUID id) {
        return repo.findById(id).map(MediaRow::toDto);
    }

    public boolean delete(UUID id) {
        Optional<MediaRow> row = repo.findById(id);
        if (row.isEmpty()) {
            return false;
        }
        store.remove(row.get().storageKey());
        return repo.deleteById(id);
    }

    private static String kindFromMime(String mime) {
        int slash = mime.indexOf('/');
        String top = slash > 0 ? mime.substring(0, slash) : mime;
        return switch (top) {
            case "image", "audio", "video" -> top;
            default -> "file";
        };
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
