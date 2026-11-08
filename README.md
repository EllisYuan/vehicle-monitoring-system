# 车辆监控系统

面向单家道路货运企业、多车队的本科毕业设计。业务工程自任务 01 起建立：当前仅有最小工程骨架、`sys_user`/`audit_log` migration、初始管理员建立、登录/退出与最小登录页；其余业务功能尚未实现。

## 阅读入口

- [开发任务索引](.scratch/vehicle-monitoring/README.md)：用户已批准的 27 个独立 tickets、直接依赖和验收分工；01 已实现，02 与 05 可领取。
- [车辆监控系统 Spec](.scratch/vehicle-monitoring/spec.md)：用户故事、实现决策与验收场景。
- [任务 01 验证记录](.scratch/vehicle-monitoring/evidence/01-verification.md)：已执行、未执行与已知偏差。

- [系统设计方案](docs/design/系统设计方案.md)：技术栈、模块化单体、项目结构、业务规则、数据模型、实施与验收。
- [方案对齐记录](docs/design/方案对齐.md)：逐轮决策追溯。
- [GLOSSARY](GLOSSARY.md)：业务术语。
- [ADR](docs/adr/)：边界、协议、地图、架构与可靠性取舍。
- [Issue tracker 规则](docs/agents/issue-tracker.md)：使用 `.scratch/<feature>/` 管理 spec 与 ticket。

## 当前工程

```text
pom.xml                 聚合 POM（当前仅 server）
server/                 Spring Boot 4.1.2 / JDK 21 / Sa-Token 1.46.0 / MyBatis-Plus 3.5.18 / Flyway
web/                    Vue 3.5 + TypeScript + Vite 8，登录页、路由守卫、退出
```

实测版本：JDK 21.0.9、Maven 3.9.12、Node 24.11.1、pnpm 10.16.1、MySQL 8.4、Redis 8.4.0。后端启动前提与命令见 `.env.example` 与 `server/src/main/resources/application.yml`；首次在空库启动必须提供 `VMS_BOOTSTRAP_ADMIN_USERNAME`、`VMS_BOOTSTRAP_ADMIN_PASSWORD`，仓库不含默认密码。

验证：后端 `mvn test`（Testcontainers 真实 MySQL 8.4 + Redis），前端 `pnpm typecheck`、`pnpm lint`、`pnpm test:e2e`（需真实运行的后端与管理员）。

`../explore/` 保留旧规划与调研出处，已标记历史状态；旧 schema.sql 和预填通过的测试表不能作为新项目建库或测试证据。
