package com.vms.perf;

import java.time.Duration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 24：性能验收专用容器。
 *
 * <p>不复用 {@code com.vms.support.IntegrationContainers} 的 512 MiB / 1 vCPU /
 * innodb_flush_log_at_trx_commit=1 配置：该配置面向功能测试，在本机同时运行其他项目
 * MySQL 容器时，提交 fsync 会出现数秒级抖动（实测 ACK p95 3739 ms、max 4991 ms），
 * 与产品行为无关。这里采用与 ticket 25 容量 harness 相同的独立容器思路，参数全部
 * 记录到证据中；仍是真实 MySQL 8.4 与正式 migration。
 */
@TestConfiguration(proxyBeanMethods = false)
public class PerfContainers {

    @Bean
    @ServiceConnection
    MySQLContainer<?> mysqlContainer() {
        return new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
                .withDatabaseName("vms")
                .withUsername("vms")
                .withPassword("vmsdevpass")
                .withCommand("--skip-log-bin", "--innodb-buffer-pool-size=768M",
                        "--innodb-flush-log-at-trx-commit=2", "--innodb-log-buffer-size=64M",
                        "--innodb-doublewrite=OFF", "--max-connections=100",
                        "--performance-schema=OFF")
                .withCreateContainerCmdModifier(command -> command.getHostConfig()
                        .withMemory(1536L * 1024 * 1024).withNanoCPUs(2_000_000_000L))
                .withStartupTimeout(Duration.ofMinutes(5))
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
                        .withMemory(256L * 1024 * 1024).withNanoCPUs(500_000_000L))
                .withStartupTimeout(Duration.ofMinutes(2));
    }

    /**
     * Sa-Token 自建 Lettuce 连接只读 sa-token.alone-redis.*，不消费 @ServiceConnection，
     * 与 IntegrationContainers 相同的显式映射。
     */
    @Bean
    DynamicPropertyRegistrar saTokenRedisProperties(GenericContainer<?> redisContainer) {
        return registry -> {
            registry.add("sa-token.alone-redis.host", redisContainer::getHost);
            registry.add("sa-token.alone-redis.port", () -> redisContainer.getMappedPort(6379));
        };
    }
}
