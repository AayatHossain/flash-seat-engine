package com.flashseat.worker;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    // Same versions as docker-compose.yml.
    static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:18.7-alpine");
    static final DockerImageName KAFKA_IMAGE = DockerImageName.parse("apache/kafka:4.3.2");
    static final DockerImageName MAILPIT_IMAGE = DockerImageName.parse("axllent/mailpit:v1.34.0");

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

    @Bean
    MailpitContainer mailpitContainer() {
        return new MailpitContainer(MAILPIT_IMAGE);
    }

    @Bean
    DynamicPropertyRegistrar mailProperties(MailpitContainer mailpit) {
        return registry -> {
            registry.add("spring.mail.host", mailpit::getHost);
            registry.add("spring.mail.port", mailpit::smtpPort);
        };
    }

    /** Mailpit catches emails over SMTP and lets tests read them back through its HTTP API. */
    public static class MailpitContainer extends GenericContainer<MailpitContainer> {

        MailpitContainer(DockerImageName image) {
            super(image);
            withExposedPorts(1025, 8025);
        }

        int smtpPort() {
            return getMappedPort(1025);
        }

        public String apiUrl() {
            return "http://" + getHost() + ":" + getMappedPort(8025) + "/api/v1";
        }
    }
}
