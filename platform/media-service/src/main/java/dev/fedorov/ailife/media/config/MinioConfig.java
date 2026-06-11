package dev.fedorov.ailife.media.config;

import io.minio.MinioClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the singleton {@link MinioClient} from {@link MediaServiceProperties}. The client is
 * thread-safe and connection-pooled (okhttp under the hood), so one bean serves the whole app.
 */
@Configuration
public class MinioConfig {

    @Bean
    public MinioClient minioClient(MediaServiceProperties props) {
        MediaServiceProperties.Minio m = props.getMinio();
        return MinioClient.builder()
                .endpoint(m.getEndpoint())
                .credentials(m.getAccessKey(), m.getSecretKey())
                .build();
    }
}
