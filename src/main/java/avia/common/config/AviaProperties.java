package avia.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "avia")
public record AviaProperties(Mongo mongo, Api api) {
    public record Mongo(String uri, String database) { }
    public record Api(Aviasales aviasales, AeroDataBox aerodatabox) { }
    public record Aviasales(String token) { }
    public record AeroDataBox(String key) { }
}
