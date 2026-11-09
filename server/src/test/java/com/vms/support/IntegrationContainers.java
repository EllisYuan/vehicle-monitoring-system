package com.vms.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
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
                .withCommand("--innodb-buffer-pool-size=128M", "--max-connections=40")
                .withCreateContainerCmdModifier(command -> command.getHostConfig()
                        .withMemory(512L * 1024 * 1024).withNanoCPUs(1_000_000_000L))
                .withUrlParam("connectionTimeZone", "Asia/Shanghai")
                .withUrlParam("forceConnectionTimeZoneToSession", "true");
    }

    @Bean
    @ServiceConnection(name = "redis")
    @SuppressWarnings("resource")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:8.4"))
                .withExposedPorts(6379)
                .withCreateContainerCmdModifier(command -> command.getHostConfig()
                        .withMemory(128L * 1024 * 1024).withNanoCPUs(500_000_000L));
    }

    /**
     * Sa-Token 自建 Lettuce 连接，只读 sa-token.alone-redis.*，不消费 @ServiceConnection。
     * 这里把其主机和映射端口指到本次测试的 Redis 容器，避免依赖开发者本机固定端口。
     */
    @Bean
    DynamicPropertyRegistrar saTokenRedisProperties(GenericContainer<?> redisContainer) {
        return registry -> {
            registry.add("sa-token.alone-redis.host", redisContainer::getHost);
            registry.add("sa-token.alone-redis.port", () -> redisContainer.getMappedPort(6379));
        };
    }
}
