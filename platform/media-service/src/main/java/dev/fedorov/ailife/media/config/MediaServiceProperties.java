package dev.fedorov.ailife.media.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Maps {@code media.*} env-driven config. MinIO connection settings live under
 * {@code media.minio.*}; {@code max-bytes} caps a single upload (receipts/voice
 * notes are small — a hard cap keeps a rogue caller from OOMing the service,
 * which reads the whole blob into memory before streaming it to MinIO).
 */
@ConfigurationProperties(prefix = "media")
public class MediaServiceProperties {

    private final Minio minio = new Minio();

    /** Max size of a single uploaded object, in bytes. Default 10 MiB. */
    private long maxBytes = 10L * 1024 * 1024;

    public Minio getMinio() { return minio; }

    public long getMaxBytes() { return maxBytes; }
    public void setMaxBytes(long maxBytes) { this.maxBytes = maxBytes; }

    public static class Minio {
        /** S3 endpoint, e.g. {@code http://localhost:9000}. */
        private String endpoint = "http://localhost:9000";
        private String accessKey = "ailife";
        private String secretKey = "ailife-secret";
        /** Bucket all objects land in. Created on startup if absent. */
        private String bucket = "ai-life-media";

        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        public String getAccessKey() { return accessKey; }
        public void setAccessKey(String accessKey) { this.accessKey = accessKey; }
        public String getSecretKey() { return secretKey; }
        public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
        public String getBucket() { return bucket; }
        public void setBucket(String bucket) { this.bucket = bucket; }
    }
}
