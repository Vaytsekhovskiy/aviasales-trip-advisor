package avia;

import avia.common.config.AviaProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AviaProperties.class)
public class AviaApplication {
    public static void main(String[] args) {
        SpringApplication.run(AviaApplication.class, args);
    }
}
