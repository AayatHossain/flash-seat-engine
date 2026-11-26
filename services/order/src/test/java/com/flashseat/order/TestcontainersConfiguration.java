package com.flashseat.order;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    // Same versions as docker-compose.yml, so tests run against the real engines.
    static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:18.7-alpine");
    static final DockerImageName KAFKA_IMAGE = DockerImageName.parse("apache/kafka:4.3.2");

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(POSTGRES_IMAGE);
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        return new KafkaContainer(KAFKA_IMAGE);
    }
}
