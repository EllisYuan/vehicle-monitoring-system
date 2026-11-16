# 任务 21 验证记录

状态：2026-11-16 验证完成（合并前基线 907c234）。后端审计查询测试 9/9、统一浏览器入口 Playwright 20/20（含本票 3 项）、真实 HTTP 三角色 + 独立 MySQL 8.4 容器直查、前端 typecheck/lint/build 均通过。本票无迁移，未改动 `SchemaUpgradeTest`。

## 环境与资源边界

| 项 | 实际配置 |
|---|---|
| 系统 | Windows 11 Pro for Workstations |
| JDK | 21.0.9；Maven Wrapper 3.9.12 |
| 后端 | Spring Boot 4.1.2；surefire forkCount=1、reuseForks=false |
| 集成测试 DB/缓存 | Testcontainers `mysql:8.4` + `redis:8.4`，Ryuk 启用 |
| 真实 HTTP 验证 | 独立容器 `t21-mysql`（`mysql:8.4`，映射 13307）+ `t21-redis`（`redis:8.4`，映射 16380）；server jar 端口 18081 |
| 浏览器 | BrowserFlowTest 随机端口后端 + Vite + Chromium；Playwright workers=1 |
| 并发约束 | 所有 Maven/浏览器/容器验证经 `with-test-lock.ps1` 串行执行，未并行运行测试 |

验证结束后 `docker ps` 无测试容器残留；`t21-mysql`/`t21-redis` 与 Testcontainers/Ryuk 容器均已回收，未清理其他项目容器。token 与密码未写入任何证据文件。

## 实现与改动

- `server/src/main/java/com/vms/identity/security/Permission.java`：新增 `AUDIT_VIEW(RoleCode.ADMIN)`。
- `server/src/main/java/com/vms/audit/AuditQueryService.java`：只读查询 `audit_log`；按 `actor_name`/`category`/`action`/`result` 等值过滤；默认窗口 7 天、最大 31 天；`size` 1–100、默认 20；偏移上限 10000；`detail` 凭证脱敏；`notification_outbox` 各状态计数与 DEAD 明细（最多 100 行）。
- `server/src/main/java/com/vms/audit/AuditController.java`：`GET /api/v1/audit/logs`、`GET /api/v1/audit/operation-records`，类级 `@RequiresPermission(Permission.AUDIT_VIEW)`，无写方法。
- `web/src/features/audit/api.ts`、`web/src/features/audit/views/AuditListView.vue`：`/admin/audit` 查询页；筛选、分页、只读表格、运行记录区；页面无删除/修改入口。
- `web/src/app/router.ts`：`/admin/audit` 由占位页替换为真实页面，`roles: ['ADMIN']`。
- 测试：`server/src/test/java/com/vms/audit/AuditViewerTest.java`（9 项）、`web/e2e/audit.spec.ts`（3 项）。
- 无数据库迁移；未重定义前序票审计写入格式，未补写历史记录。

## 命令与结果

| 命令 | 结果 | 证据 |
|---|---|---|
| `mvnw.cmd -B -pl server -am '-Dtest=AuditViewerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test` | `Tests run: 9, Failures: 0, Errors: 0, Skipped: 0`；BUILD SUCCESS；lock exit 0 | `evidence/21-audit-tests.log` |
| `mvnw.cmd -B -pl server -am '-Dvms.browser-tests=true' '-Dtest=BrowserFlowTest' '-Dsurefire.failIfNoSpecifiedTests=false' test` | BrowserFlowTest 1/1；Playwright `20 passed (2.1m)`，其中 `audit.spec.ts` 3/3；BUILD SUCCESS；lock exit 0 | `evidence/logs/02-playwright.log`、`evidence/logs/02-browser-vite.log`、`web/test-results/admin-audit-page.png` |
| 真实 HTTP 三角色 + MySQL 直查（`pwsh -File` 于锁内） | 全部断言通过，lock exit 0 | `evidence/21-http-verification.log`、`evidence/21-package.log` |
| `pnpm typecheck` / `pnpm lint` / `pnpm build` | 依次 exit 0 | `evidence/21-web-checks.log` |

说明：BrowserFlowTest 第一次运行仅有 `monitoring.spec.ts` 失败，原因是 worktree 的 `simulator/target/classes` 未编译（`ClassNotFoundException: com.vms.simulator.TerminalSimulator`），与本票代码无关；执行 `mvnw.cmd -B -pl simulator -am -DskipTests package` 后重跑，20 项全绿。

## 真实 HTTP 与 MySQL 直查（独立容器，端口 18081）

`evidence/21-http-verification.log` 记录的实际结果：

- ADMIN `POST /api/v1/auth/login` 200；MONITOR、FLEET_LEADER、第二 ADMIN 登录均 200。
- 不存在账号 `ghost-user` 登录 401 `INVALID_CREDENTIALS`，响应不含提交密码 `Submitted-Pass-999`。
- `GET /api/v1/audit/logs?actor=ghost-user&result=FAILURE`（ADMIN）200，命中 1 条：`LOGIN/LOGIN/ghost-user/auth/FAILURE/UNKNOWN_ACCOUNT`，该行由认证流程实际写入。
- `GET /api/v1/audit/logs?category=FLEET&action=CREATE`（ADMIN）200，命中 `fleet:1`，与创建车队请求的实际 target 一致。
- 直插样例 `mask-probe` 的 `detail` 经 API 返回为 `password=***, authCode=***, token=***, wsTicket=***`；响应不含 `RealSecret123`、`AUTH9876ZY`、`tok-abc-9`、`ws-ticket-77`。
- MONITOR 与 FLEET_LEADER 对 `/audit/logs`、`/audit/operation-records` 均为 403 `FORBIDDEN`。
- `size=101` → 400 `INVALID_PAGE_SIZE`；`page=501&size=20` → 400 `PAGE_OFFSET_TOO_LARGE`；跨 2 个月时间窗 → 400 `TIME_RANGE_TOO_WIDE`。
- `page=0&size=2` 返回 `total=11 hasMore=true items=2`；随后创建第二个车队 201（查询不影响业务写入）。
- `POST/PUT/DELETE /api/v1/audit/logs` 均 405。
- `notification_outbox` 空表时各状态计数为 0、`deadOutbox=0`，与直查一致；插入 1 条 `DEAD` 行后 API 返回 `DEAD=1`、`deadOutbox=1`（`http-dead-1/POSITION/42/attempts=10`），与 MySQL 直查 `DEAD 1` 一致。
- 撤权：第二管理员停用前查询 200；停用后同一 token 查询 401 `ACCOUNT_DISABLED`。
- MySQL 直查 `audit_log` 的 12 行与 API/操作序列逐项一致（BOOTSTRAP、LOGIN、ACCOUNT CREATE/UPDATE_STATUS、FLEET CREATE、ghost LOGIN FAILURE、mask 样例）。
- 验证用 server 进程已停止，`t21-mysql`/`t21-redis` 已删除；`docker ps` 无残留。

## 验收对照

| 验收项 | 结果 | 依据 |
|---|---|---|
| 登录成功/失败与关键写操作审计可查询，数据由前序票实际写入 | 通过 | `AuditViewerTest.adminQueriesLoginAndWriteAuditWrittenByPriorTickets`；真实 HTTP 的 ghost 失败行与 `fleet:1` 行 |
| 按时间、操作者、动作、结果筛选与分页有效 | 通过 | `timeWindowPagingAndOrderingAreBoundedAndMatchDirectRows`；HTTP `page/size/total/hasMore` 与窗口过滤 |
| 详情不含 password、鉴权码、token、WS ticket 明文 | 通过 | `detailMasksCredentialSamplesAndLoginPasswordNeverAppears`；HTTP `mask-probe` 与 ghost 登录行 |
| MONITOR 与 FLEET_LEADER 访问被服务端拒绝 | 通过 | `monitorAndFleetLeaderAreRejectedByServer`；HTTP 两角色两接口均 403 `FORBIDDEN` |
| 审计记录只读，页面无删除或修改入口 | 通过 | `auditApiExposesNoWriteOperations`（405）；`audit.spec.ts` 断言表格内 button 数 0、无 `audit-delete`/`audit-edit`；截图 `admin-audit-page.png` |
| 已实现的运行记录与源事实一致；未提供的在 11/18/27 补充复验 | 通过（本票范围） | outbox 各状态计数与 DEAD 明细已实现并与 MySQL 直查一致（测试 + HTTP）；分区维护失败告警尚未由 11 号票交付，页面如实提示“其他维护告警在对应功能交付后接入”，未编造占位数据，留待 11/18/27 复验 |
| 审计撤权后旧会话访问及时失效 | 通过 | `adminSessionLosesAuditAccessImmediatelyAfterRevocation`；HTTP 停用后同一 token 401 `ACCOUNT_DISABLED` |
| 查询有界，不影响业务写入 | 通过 | `boundedQueriesUseIndexAndDoNotBlockBusinessWrites`（EXPLAIN 命中 `idx_audit_log_created_at`；连续 20 次查询后建车队成功）；HTTP 越界参数 400、查询后建车队 201 |

## 前端与只读页面

- `audit.spec.ts` 3 项全部通过：管理员按 actor + FAILURE 筛选命中 1 条并确认无写入口与不泄露密码；运行记录区展示 outbox 计数与 DEAD 表；监控员接口 403、菜单无 `nav-admin-audit`、直接访问 `/admin/audit` 重定向 `/`。
- 页面截图：`web/test-results/admin-audit-page.png`。
- `pnpm typecheck`、`pnpm lint`、`pnpm build` 均 exit 0（`evidence/21-web-checks.log`）。

## 未执行与剩余缺口

- 分区维护失败告警：依赖 11 号票的维护功能，本票未接入、未伪造记录；按 ticket 约定在 11/18/27 补充复验。
- 审计导出与自动清理：明确在 Scope 外，未实现、未验证。
- 浏览器覆盖沿用项目基线 Chromium 单浏览器；未扩展其他浏览器。
- 合并 `integration/tickets-08-27` 后的受影响验证在合并完成后另行记录。
