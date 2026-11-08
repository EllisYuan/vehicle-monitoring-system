# 01: 空环境启动与管理员登录

Status: resolved
Labels: ready-for-agent
Type: task
Blocked by: None

## What to build

在空 MySQL 8.4 上建立可重复构建与升级的最小骨架：Maven Wrapper 锁定经实际验证的 JDK 21、Spring Boot 4.1.x 及依赖 patch 版本；Flyway versioned migration 只建本票所需的最小表（账号身份、审计、迁移元数据），不预建 22 张设计表。初始管理员凭证来自部署配置，首次启动一次性建立，仓库不含固定默认密码或自助注册。浏览器提供登录页与退出，登录使用账号密码换得随机 token 会话，错误响应不区分账号是否存在、不泄露凭证。登录成功、失败、退出与账号建立写入 audit_log，记录操作者、时间、来源与结果，不含密码、token。空库启动后确认没有自动生成的模拟业务数据，凭证不出现在应用日志。构建组合若不可解析，提交明确阻塞与版本证据，不静默换栈。

## Spec coverage

- 主：[AC01](../spec.md)（空库升级、非默认管理员、无模拟数据、凭证不入日志）；US1、US2。
- 次：US3 登录/退出基础、US12 登录审计；AC03 仅覆盖登录后会话与退出，停用/改密/替换/到期由 02 验证。
- 复验：无。监控与管理两种布局导航属 02，不在本票。

## Acceptance criteria

- [x] 空 MySQL 8.4 上 Flyway 成功建库，二次启动无重复变更，升级可重复
- [x] Maven Wrapper 构建成功，记录依赖解析结果与锁定版本
- [x] 部署配置提供非默认管理员并在首次启动一次性建立；仓库无默认密码
- [x] 空库启动后无自动生成的模拟业务数据
- [x] 浏览器可完成登录并进入会话；错误凭证响应不泄露账号存在性或 token
- [x] 退出后原 token 不能再访问业务接口
- [x] 登录成功、失败与退出写 audit_log，不含密码、token
- [x] 应用日志不出现凭证

## Scope boundaries

- 不建全表，仅最小骨架表；不建角色、车队、车辆、终端等业务档案。
- 不做角色授权与数据范围（02）、会话锁定与单会话替换（02）、WS、TCP 接入、地图与任何业务页面。
- 后续 ticket 的登录相关复验不能替代本票的 AC01 验证；本票也不必等待 02 才可关闭。

## Validation

- 使用真实 MySQL 8.4（本机或 Docker）从空库执行 migration 并二次启动；保存命令、版本、时间与输出。
- 用真实 HTTP 请求（浏览器与命令行）验证登录、退出与拒绝行为；用 SQL 查询 audit_log 核对内容。
- 记录状态为通过、失败、未执行或仅静态检查之一，附命令、环境与证据位置；不得预填通过。

## Comments

（暂无讨论）

## Answer

已实现并验证，状态 `resolved`（2026-11-08）。

### 交付内容

- 根 `pom.xml` + `server` 模块；`mvnw`/`mvnw.cmd`/`.mvn/wrapper` 锁定 Maven 3.9.12。
- 依赖锁定（见 `server/pom.xml`）：Spring Boot 4.1.2、MyBatis-Plus 3.5.18（boot4 starter）、Sa-Token 1.46.0（boot4 starter + alone-redis + redis-template + commons-pool2）、Flyway（`spring-boot-starter-flyway` + `flyway-mysql`）、BCrypt（spring-security-crypto）。
- `V1__identity_and_audit.sql`：仅 `sys_user`、`audit_log` 两表；`flyway_schema_history` 由 Flyway 建立。
- `identity` 包：bootstrap（部署配置一次性建立管理员，`count>0` 跳过，空库缺配置抛错）、登录/退出/当前用户、BCrypt。
- `audit` 包：登录成功/失败/退出/初始管理员建立写入 `audit_log`，记录操作者、时间、来源 IP、结果，不含密码/token/哈希。
- `web/`：Vue 3 登录页、路由守卫、退出、内存 + sessionStorage token（仅 `satoken` 请求头传递）。
- 测试：`SchemaUpgradeTest`、`BootstrapAdminTest`、`LoginFlowTest`、`CredentialLogHygieneTest`（真实 MySQL 8.4 + Redis 容器）；`web/e2e/login.spec.ts`（Playwright 真实浏览器）。

### 验证结果

全部 8 条 AC 实测通过。详细命令、环境、输出与证据位置见 [01-verification](../evidence/01-verification.md) 与同目录下的 `.log`。

- 真实空库首次启动：`Started VehicleMonitoringApplication in 5.839 seconds`，Flyway 应用 V1，建立 `ops-admin`。
- 二次启动：`账号已存在，跳过初始管理员建立`，无重复 migration。
- 真实 HTTP：错误密码与不存在账号返回完全相同 401 响应体；退出后同 token 返回 401。
- 真实库 `audit_log`：BOOTSTRAP、LOGIN SUCCESS、LOGIN FAILURE×2、LOGOUT 共 5 行；凭证扫描 0 命中。
- 浏览器：`3 passed`（未登录重定向、错误凭证提示、登录后退出且旧 token 失效）。
- `mvnw -DskipTests package` BUILD SUCCESS；`mvn test` 16 项通过；`pnpm typecheck`/`lint` 无告警。

### 已知偏差

1. Sa-Token 1.46.0 仅基于 Spring Boot 4.0.3 构建；在 4.1.2 实测可启动与登录，但无官方兼容声明，属持续观察的兼容风险。
2. 本机 Maven Central 不可达（TLS 中断），验证经仓库外临时 settings 走阿里云；项目未固化镜像。
3. 单会话替换、失败锁定、8 小时到期等属 02，本票未验证也未宣称。
4. `sys_user`/`audit_log` 的支撑字段偏差已回写设计 §6。

### 未执行

Linux + Docker Compose 部署（任务 27）、WS/TCP/地图（后续任务）。
