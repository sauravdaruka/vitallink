package com.vitallink.readings;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer(@Value("${test.postgres.image}") String image) {
        return new PostgreSQLContainer(DockerImageName.parse(image).asCompatibleSubstituteFor("postgres"));
    }

    @Bean
    @ServiceConnection
    RabbitMQContainer rabbitContainer(@Value("${test.rabbitmq.image}") String image) {
        return new RabbitMQContainer(DockerImageName.parse(image).asCompatibleSubstituteFor("rabbitmq"));
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer(@Value("${test.redis.image}") String image) {
        return new GenericContainer<>(DockerImageName.parse(image)).withExposedPorts(6379);
    }
}
