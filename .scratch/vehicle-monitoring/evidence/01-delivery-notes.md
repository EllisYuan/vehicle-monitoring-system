# 任务 01 交付说明（草稿）

## 结构

```text
pom.xml                     # 聚合 POM，当前仅 server
server/
  pom.xml                   # Boot 4.1.2 / JDK 21 / Sa-Token / MyBatis-Plus / Flyway
  src/main/java/com/vms/
    VehicleMonitoringApplication.java
    audit/AuditService.java
    identity/account/       # sys_user 存取
    identity/auth/          # 登录、退出、当前用户
    identity/bootstrap/     # 部署配置一次性建立管理员
    identity/security/      # BCrypt
    infrastructure/         # 时钟（Asia/Shanghai）、Sa-Token 拦截器
  src/main/resources/
    application.yml
    db/migration/V1__identity_and_audit.sql
web/                        # Vue 3 登录页、路由守卫、退出、Playwright e2e
```

## 启动前提

1. JDK 21（本机 PATH 的 `java` 为 17，需显式指定）。
2. 空库 `vms`（MySQL 8.4）与 Redis；所有表由 Flyway 建立。
3. 首次启动必须提供 `VMS_BOOTSTRAP_ADMIN_USERNAME` 与 `VMS_BOOTSTRAP_ADMIN_PASSWORD`（≥8 字符）；之后重启可不再提供，已有账号不会被改写。

```bash
mvn -DskipTests package
VMS_DB_URL='jdbc:mysql://127.0.0.1:3306/vms?connectionTimeZone=Asia/Shanghai&forceConnectionTimeZoneToSession=true&allowPublicKeyRetrieval=true&useSSL=false' \
VMS_DB_USERNAME=... VMS_DB_PASSWORD=... \
java -jar server/target/server-0.1.0-SNAPSHOT.jar
```

`web`：`pnpm install && pnpm dev`，开发代理 `/api` → `http://127.0.0.1:8080`。
