# 任务 01 验证记录

状态：进行中。每条结果在命令实际执行后填入，未执行的保持“未执行”。

## 环境

| 项 | 值 |
| --- | --- |
| 操作系统 | Windows 11 Pro for Workstations 10.0.26200 |
| JDK | 21.0.9 (Oracle)；PATH 中 `java` 为 17.0.20.1，启动时显式使用 JDK 21 |
| Maven | 3.9.12 |
| Node / pnpm | 24.11.1 / 10.16.1 |
| MySQL | 8.4 容器 `vms-server-db`（127.0.0.1:3308），空库 `vms` |
| Redis | 8.4.0 容器 `vms-redis`（127.0.0.1:6380） |
| Docker | 29.1.3，Desktop Linux engine |

网络前提：`repo.maven.apache.org` 与本机网络 TLS 握手失败，验证使用仓库外的临时 settings 关闭该问题、指向阿里云公共镜像；项目 POM 未写死镜像。

## 依赖解析

版本在 `server/pom.xml` 与 `web/package.json` 锁定：

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| Spring Boot | 4.1.2 | 父 POM；Boot 4 中 Flyway 自动配置由 `spring-boot-starter-flyway` 提供 |
| MyBatis-Plus | 3.5.18 | `mybatis-plus-spring-boot4-starter` |
| Sa-Token | 1.46.0 | boot4 starter + alone-redis；alone-redis 的存储实现与 `commons-pool2` 为 optional，需显式引入 |
| Flyway | BOM 管理（12.4.0） | `flyway-mysql` 同版本 |
| MySQL Connector/J | BOM 管理（9.7.0） | |
| Spring Security Crypto | BOM 管理 | BCrypt，不带 Spring Security filter chain |
| Testcontainers | BOM 管理（2.0.5） | Boot 4 BOM 中模块名为 `testcontainers-mysql`、`testcontainers-junit-jupiter` |
| Vue / Vite / TS | 3.5.45 / 8.3.6 / 5.9.3 | |
| Vue Router / Pinia / Element Plus / Axios | 5.4.0 / 4.1.0 / 2.13.1 / 1.13.2 | |

Boot 4 与 Jackson 3 差异：测试使用 `tools.jackson.databind`；`AutoConfigureMockMvc` 位于 `org.springframework.boot.webmvc.test.autoconfigure`。

## 验证结果

| # | 验收项 | 状态 | 证据 |
| --- | --- | --- | --- |
| 1 | 空 MySQL 8.4 上 Flyway 建库，二次启动无重复变更 | 通过（测试 + 真实启动） | `SchemaUpgradeTest`；`01-log-scan.log` 首次 `Migrating schema vms to version "1 - identity and audit"`，二次启动无 Migrating 行；`flyway_schema_history` 仅 1 行 success=1 |
| 2 | Maven Wrapper 构建成功，依赖解析与锁定版本 | 通过 | `mvnw -v` 显示 Maven 3.9.12 + JDK 21.0.9；`mvnw -DskipTests package` BUILD SUCCESS，产出 `server-0.1.0-SNAPSHOT.jar` |
| 3 | 部署配置提供非默认管理员并一次性建立，无默认密码 | 通过（测试 + 真实启动） | `AdminBootstrapper`：首次 `初始管理员已建立：ops-admin`，二次 `账号已存在，跳过初始管理员建立`；`sys_user` 仅 1 行 |
| 4 | 空库启动后无自动生成的模拟业务数据 | 通过 | `01-db-verification.log`：库中仅 `sys_user`、`audit_log`、`flyway_schema_history`，`sys_user` 计 1，无车队/车辆/终端表 |
| 5 | 浏览器登录并进入会话 | 通过（Playwright） | `3 passed`：未登录重定向、错误凭证提示、正确凭证进入会话 |
| 6 | 错误凭证不泄露账号存在性 | 通过（测试 + 真实 HTTP） | `01-http-verification.log`：已知账号与不存在账号返回完全相同的响应体与 401 |
| 7 | 退出后原 token 不能访问业务接口 | 通过（测试 + 真实 HTTP + 浏览器） | `01-http-verification.log`：logout 204 后同 token 访问 `/auth/me` 返回 401；Playwright 页面内 fetch 同样 401 |
| 8 | 登录成功、失败与退出写 audit_log 且不含密码、token | 通过（测试 + 真实库） | `01-db-verification.log` 见 BOOTSTRAP/LOGIN SUCCESS/LOGIN FAILURE×2/LOGOUT 五行 |
| 9 | 应用日志不出现凭证 | 通过（测试 + 日志扫描） | `01-log-scan.log`：三份日志对凭证模式匹配 0 行；`CredentialLogHygieneTest` |
| 10 | 前端类型检查与 lint | 通过 | `pnpm typecheck`、`pnpm lint` 均无输出/无告警 |

### 真实启动与 HTTP 验证（真实环境）

- 空库容器 `vms-server-db`（MySQL 8.4，127.0.0.1:3308），Redis 8.4 容器 `vms-redis`（127.0.0.1:6380）。
- 首次启动：`Started VehicleMonitoringApplication in 5.839 seconds`，Flyway 应用 V1。
- 二次启动：`账号已存在，跳过初始管理员建立`，无重复 migration。
- 命令与输出：`01-http-verification.log`、`01-db-verification.log`、`01-log-scan.log`、`server-first-start.log`、`server-second-start.log`、`web-dev.log`。

### 未执行

- 02 的范围（角色授权、数据范围、失败锁定、单会话替换、8 小时到期验证、WS）未在本票验证，状态为“未执行”，不由本票宣称通过。
- 真实部署环境（Linux + Docker Compose）未验证，属任务 27。
- `mvn` 默认连接 Maven Central 在本机网络失败（TLS 握手中断），验证使用临时 settings 指向阿里云镜像；项目本身未固化镜像配置。

### 已知偏差与后续

1. **Sa-Token 1.46.0 仅基于 Spring Boot 4.0.3 构建**：实测在 4.1.2 上可启动、登录、退出，但官方未声明 4.1 兼容性，属需持续观察的兼容风险。
2. `sys_user` / `audit_log` 相对设计基线增加了 `actor_name`、`source_ip`、`created_at` 索引等实现字段；`sys_user.created_at/updated_at` 为 NOT NULL 无默认值，由应用写入。
3. 会话 token 存于浏览器 `sessionStorage`，经 `satoken` 请求头传递，不写 Cookie/URL/日志；这是设计“凭证不进 URL/日志”的具体实现选择，未在访谈中逐项确认。
4. 前端尚未引入 Element Plus/OpenLayers 的实际使用（依赖已锁定），登录页使用原生 CSS；布局与地图属 02 及后续任务。

### Code review 处理

| 审查意见 | 处理 |
| --- | --- |
| 缺 Maven Wrapper | 已生成 `mvnw`/`mvnw.cmd`/`.mvn/wrapper`，锁定 Maven 3.9.12，`mvnw -v` 实测通过 |
| 登录页把 500/网络错误显示为“用户名或密码错误” | 改为仅 401 显示凭证错误，其他错误提示“登录服务暂时不可用” |
| `shared/http.ts` 注释称 token 只在内存，实际存 sessionStorage | 注释改为“由 session store 提供”，与实现一致 |
| `AuthExceptionHandler` 未使用的 logger | 已删除 |
| 会话超时值在 Controller 与 yml 双处维护 | 改为注入 `SaTokenConfig`，读取配置中的 `timeout` |
| `audit_log` 新增 `actor_name`/`source_ip`、`sys_user` 新增 `created_at`/`updated_at` 未回写设计 | 已同步设计 §6 表字段并说明理由 |
| root POM 缺 protocol/simulator 模块 | 保留为分期：两模块分别在 05/06 引入，设计 §4 的目录结构属于目标态 |
| 单会话（`is-concurrent:false`） | 属 02 范围（会话替换），本票未宣称；已在 yml 注释中标注 |
| 测试内重复的建库/GRANT 与登录辅助代码 | 保留：两处用途不同（迁移可重复性 vs 空库 bootstrap），抽公共夹具会把测试耦合到同一辅助实现；如需继续收敛放在 02 一并处理 |
| `roleCode`/`status` 用 String 而非枚举 | 已由数据库 CHECK 约束兜底；类型化枚举在 02 引入角色授权时统一处理 |

