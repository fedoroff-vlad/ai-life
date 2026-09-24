package dev.fedorov.ailife.media.config;

import io.minio.MinioClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the singleton S3 client from {@link MediaServiceProperties}. The client is thread-safe and
 * connection-pooled (okhttp under the hood), so one bean serves the whole app.
 *
 * <p>The client library is still MinIO's ({@code io.minio}) while the <b>server</b> is SeaweedFS —
 * that is deliberate and costs nothing: the MinIO Java SDK is a plain S3 client, stayed freely
 * available on Maven Central when MinIO's server images did not, and swapping it for the AWS SDK
 * would rewrite working code to no benefit. See media-service/README §Object store.
 */
@Configuration
public class S3ClientConfig {

    @Bean
    public MinioClient s3Client(MediaServiceProperties props) {
        MediaServiceProperties.S3 s3 = props.getS3();
        return MinioClient.builder()
                .endpoint(s3.getEndpoint())
                .credentials(s3.getAccessKey(), s3.getSecretKey())
                .build();
    }
}
