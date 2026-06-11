package dev.fedorov.ailife.media.storage;

import dev.fedorov.ailife.media.config.MediaServiceProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;

/**
 * Thin wrapper over the MinIO SDK scoped to a single bucket. Hides MinIO's broad checked-exception
 * surface behind one unchecked {@link ObjectStoreException} so callers stay readable. The bucket is
 * created on startup if it doesn't exist — idempotent, so re-deploys against a populated MinIO are
 * a no-op.
 */
@Component
public class ObjectStore {

    private static final Logger log = LoggerFactory.getLogger(ObjectStore.class);

    private final MinioClient client;
    private final String bucket;

    public ObjectStore(MinioClient client, MediaServiceProperties props) {
        this.client = client;
        this.bucket = props.getMinio().getBucket();
    }

    public String bucket() {
        return bucket;
    }

    @PostConstruct
    void ensureBucket() {
        try {
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("created MinIO bucket '{}'", bucket);
            }
        } catch (Exception e) {
            throw new ObjectStoreException("failed to ensure bucket '" + bucket + "'", e);
        }
    }

    public void put(String key, byte[] bytes, String contentType) {
        try (var in = new ByteArrayInputStream(bytes)) {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .stream(in, bytes.length, -1)
                    .contentType(contentType)
                    .build());
        } catch (Exception e) {
            throw new ObjectStoreException("failed to put object '" + key + "'", e);
        }
    }

    public byte[] get(String key) {
        try (GetObjectResponse resp = client.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(key)
                .build())) {
            return resp.readAllBytes();
        } catch (Exception e) {
            throw new ObjectStoreException("failed to get object '" + key + "'", e);
        }
    }

    public void remove(String key) {
        try {
            client.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .build());
        } catch (Exception e) {
            throw new ObjectStoreException("failed to remove object '" + key + "'", e);
        }
    }

    public static class ObjectStoreException extends RuntimeException {
        public ObjectStoreException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
