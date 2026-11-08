package com.vms.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class IntegrationContainers {

    @Bean
    @ServiceConnection
    MySQLContainer<?> mysqlContainer() {
        return new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
                .withDatabaseName("vms")
                .withUsername("vms")
                .withPassword("vmsdevpass")
                .withUrlParam("connectionTimeZone", "Asia/Shanghai")
                .withUrlParam("forceConnectionTimeZoneToSession", "true");
    }

    @Bean
    @ServiceConnection(name = "redis")
    @SuppressWarnings("resource")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:8.4")).withExposedPorts(6379);
    }
}
