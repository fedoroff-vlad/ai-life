package dev.fedorov.ailife.memory;

import dev.fedorov.ailife.bus.EventBusConfig;
import dev.fedorov.ailife.memory.config.MemoryServiceProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@ConfigurationPropertiesScan(basePackageClasses = MemoryServiceProperties.class)
@Import(EventBusConfig.class)
public class MemoryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MemoryServiceApplication.class, args);
    }
}
