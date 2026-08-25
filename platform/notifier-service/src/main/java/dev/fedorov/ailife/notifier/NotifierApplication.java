package dev.fedorov.ailife.notifier;

import dev.fedorov.ailife.bus.EventBusConfig;
import dev.fedorov.ailife.notifier.config.NotifierProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan(basePackageClasses = NotifierProperties.class)
@Import(EventBusConfig.class)
@EnableScheduling // the held-notification redrain tick (#487 PX-1b)
public class NotifierApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotifierApplication.class, args);
    }
}
