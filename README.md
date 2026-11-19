# 车辆监控系统

面向单家道路货运企业、多车队的本科毕业设计。业务工程自任务 01 起建立，已完成登录与权限、车队/车辆/终端/司机档案与历史关联、终端接入与可靠位置、轨迹与分区保留、超速/离线/定位无效/围栏报警与处置、指令、订阅撤权、通知重试、监控统计、Excel 导出与审计查询等实现；01—13、15—21、23、25 已验收，14（GNSS/终端来源报警位）、22（可重复性仅单轮）、24（性能验收未完成）代码已合入但状态 `blocked`，26（真实天地图 key）与 27（真实 Linux 单机部署）未完成。

## 阅读入口

- [开发任务索引](.scratch/vehicle-monitoring/README.md)：用户已批准的 27 个独立 tickets、直接依赖和验收分工；01—13、15—21、23、25 `resolved`，14、22、24、26 `blocked`，27 未完成。
- [车辆监控系统 Spec](.scratch/vehicle-monitoring/spec.md)：用户故事、实现决策与验收场景。
- [任务 01 验证记录](.scratch/vehicle-monitoring/evidence/01-verification.md)、[任务 02 验证记录](.scratch/vehicle-monitoring/evidence/02-verification.md)、[任务 03 验证记录](.scratch/vehicle-monitoring/evidence/03-verification.md)：已执行、未执行与已知偏差。

- [系统设计方案](docs/design/系统设计方案.md)：技术栈、模块化单体、项目结构、业务规则、数据模型、实施与验收。
- [方案对齐记录](docs/design/方案对齐.md)：逐轮决策追溯。
- [GLOSSARY](GLOSSARY.md)：业务术语。
- [ADR](docs/adr/)：边界、协议、地图、架构与可靠性取舍。
- [Issue tracker 规则](docs/agents/issue-tracker.md)：使用 `.scratch/<feature>/` 管理 spec 与 ticket。

## 当前工程

```text
pom.xml                 聚合 POM（protocol、server、simulator）
protocol/               独立 JT/T 808-2019 codec 与 fixture 单测
simulator/              最小真实 TCP JT/T 808 模拟终端 CLI
server/                 Spring Boot 4.1.2 / JDK 21 / Sa-Token 1.46.0 / MyBatis-Plus 3.5.18 / Flyway
web/                    Vue 3.5 + TypeScript + Vite 8，登录页、两种布局、车队与账号页面、路由守卫
```

实测版本：JDK 21.0.9、Maven 3.9.12、Node 24.11.1、pnpm 10.16.1、MySQL 8.4、Redis 8.4.0。后端启动前提与命令见 `.env.example` 与 `server/src/main/resources/application.yml`；首次在空库启动必须提供 `VMS_BOOTSTRAP_ADMIN_USERNAME`、`VMS_BOOTSTRAP_ADMIN_PASSWORD`，仓库不含默认密码。

验证：后端 `mvn test`（真实 MySQL 8.4 + Redis，单 fork、测试类顺序运行退出，Ryuk 自动清理；MySQL 512 MiB、Redis 128 MiB 上限）。前端 `pnpm --dir web typecheck`、`pnpm --dir web lint`、`pnpm --dir web build`。

真实浏览器与后端的统一入口为 `./mvnw -Dvms.browser-tests=true test`：自动启动独立测试库、随机端口后端和 Vite，使用随机临时管理员运行 Playwright，结束停止子进程与容器。前提为已安装 web 依赖和对应 Chromium；本机已有浏览器可通过 `VMS_E2E_CHROMIUM` 指定，不能把本机绝对路径写进配置。只跑该入口可使用 `-Dtest=BrowserFlowTest`。手动运行 `pnpm --dir web test:e2e` 时需要设置真实运行的后端与测试管理员，缺少变量会 skip；统一入口会提供变量，防止把 skip 当通过。

`../explore/` 保留旧规划与调研出处，已标记历史状态；旧 schema.sql 和预填通过的测试表不能作为新项目建库或测试证据。
