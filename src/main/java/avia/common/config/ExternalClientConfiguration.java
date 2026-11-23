package avia.common.config;

import avia.common.aviasales.AviasalesClient;
import avia.common.aerodatabox.AeroDataBoxClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ExternalClientConfiguration {
    @Bean
    @ConditionalOnExpression("!environment.getProperty('avia.api.aviasales.token', '').isBlank()")
    AviasalesClient aviasalesClient(AviaProperties properties) {
        return new AviasalesClient(properties.api().aviasales().token());
    }

    @Bean
    @ConditionalOnExpression("!environment.getProperty('avia.api.aerodatabox.key', '').isBlank()")
    AeroDataBoxClient aeroDataBoxClient(AviaProperties properties) {
        return new AeroDataBoxClient(properties.api().aerodatabox().key());
    }
}
