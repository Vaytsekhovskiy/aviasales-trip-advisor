package avia.common.config;

import avia.AviasalesClient;
import avia.client.AeroDataBoxClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ExternalClientConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "avia.api.aviasales", name = "token")
    AviasalesClient aviasalesClient(AviaProperties properties) {
        return new AviasalesClient(properties.api().aviasales().token());
    }

    @Bean
    @ConditionalOnProperty(prefix = "avia.api.aerodatabox", name = "key")
    AeroDataBoxClient aeroDataBoxClient(AviaProperties properties) {
        return new AeroDataBoxClient(properties.api().aerodatabox().key());
    }
}
