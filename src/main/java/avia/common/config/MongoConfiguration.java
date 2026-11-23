package avia.common.config;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration
public class MongoConfiguration {
    @Bean(destroyMethod = "close")
    @Lazy
    MongoClient mongoClient(AviaProperties properties) {
        return MongoClients.create(properties.mongo().uri());
    }

    @Bean
    @Lazy
    MongoDatabase mongoDatabase(MongoClient client, AviaProperties properties) {
        return client.getDatabase(properties.mongo().database());
    }
}
