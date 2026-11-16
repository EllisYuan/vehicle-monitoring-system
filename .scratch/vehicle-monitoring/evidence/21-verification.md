# 任务 21 验证记录

状态：2026-11-16 验证完成（合并前基线 907c234；第一次合并后复验于 e3a28fa；第二次合并含任务16后复验于 e5f7936，并叠加分类筛选补齐提交）。合并前：后端审计查询测试 9/9、统一浏览器入口 Playwright 20/20（含本票 3 项）、真实 HTTP 三角色 + 独立 MySQL 8.4 容器直查、前端 typecheck/lint/build 均通过。合并后：`AuditViewerTest` 再次 9/9；`BrowserFlowTest` 第三次运行 Playwright 30/30（含本票 3 项），前两次为首个既有用例 5s 登录断言超时，定位见“合并后复验”。第二次合并含任务16：`AuditViewerTest` 9/9、`BrowserFlowTest` 首跑 Playwright 31/31（含本票 3 项与任务16指令 1 项）；任务16的 V14 使 `SchemaUpgradeTest` 既有断言失配，该缺口已由集成分支 `899daf1` 修复，第三次合并（`38bd3a1`）后复验：`AuditViewerTest` 9/9、`SchemaUpgradeTest` 1/1、`BrowserFlowTest` Playwright 31/31。本票无迁移，未改动 `SchemaUpgradeTest`。

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
- 合并后的受影响验证见“合并后复验”：`AuditViewerTest` 9/9、`BrowserFlowTest` 第三次 30/30 通过；前两次失败为首个既有用例的 5s 登录断言超时，与本票功能无关。
- 集成基线缺口已由集成分支修复：任务16的 `V14__command_log.sql` 曾使 `SchemaUpgradeTest` 迁移数与表清单断言失配；集成分支 `899daf1` 已对齐断言，本票第三次合并（`38bd3a1`）后 `SchemaUpgradeTest` 1/1 通过（见文末复验）。

## 合并后复验（e3a28fa）

- 合并提交 `e3a28fa`（父 `064383b` 与 `integration/tickets-08-27` 的 `2e9fbe2`），无冲突；合并后 Flyway 仍为 6 个迁移，无新增 schema 变更。
- `AuditViewerTest`：`Tests run: 9, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS，lock exit 0（`evidence/21-audit-tests-post-merge.log`）。
- `BrowserFlowTest` 前两次运行失败于 `web/e2e/archives.spec.ts:24` 首个用例 `signIn` 的 `current-user` 断言 5s 超时（Maven `Tests run: 1, Failures: 1`，Playwright 1 failed / 29 passed；两次的 `audit.spec.ts` 均 3/3 通过）：`evidence/21-browser-post-merge.log`、`evidence/21-browser-post-merge-rerun.log`。
- 失败定位（Playwright trace）：POST `/api/v1/auth/login` 实际返回 200，但耗时 3389ms；随后 `/api/v1/auth/me` 67ms、首页模块加载完成，失败快照已显示 `browser-admin（管理员）` 登录态。服务端登录使用 BCrypt strength=12，首个页面登录叠加 Vite 首次页面模块加载，超过 Playwright 默认 5s 的 expect 超时；审计页与审计接口不在该用例路径上。该失败与本票功能无关，属于既有浏览器用例的时序敏感问题，未修改共享测试配置。
- 第三次运行通过：`Tests run: 1, Failures: 0`，Playwright `30 passed (1.4m)`（含 `audit.spec.ts` 3/3），BUILD SUCCESS，lock exit 0（`evidence/21-browser-post-merge-final.log`）；截图重新生成 `web/test-results/admin-audit-page.png`。
- 复验结束后本轮 Testcontainers 容器已由 Ryuk 回收；检查时仍在运行的 `testcontainers-ryuk-*`（创建于 11:09:01）属于其他 ticket 的新一轮测试，未清理。未删除其他项目或开发容器。

## 合并后复验（e5f7936，含任务16指令票）

- 第二次合并提交 `e5f7936`（父 `5b3de0c` 与 `integration/tickets-08-27` 的 `6987d1b`，后者含任务16）。唯一冲突 `server/src/main/java/com/vms/identity/security/Permission.java`：同时保留 `AUDIT_VIEW(RoleCode.ADMIN)` 与 `COMMAND_DISPATCH(RoleCode.ADMIN, RoleCode.MONITOR)`；`web/src/app/router.ts` 自动合并后 `/monitor/commands` 与 `/admin/audit` 并存。
- 任务16在 `AuditService` 新增 `CATEGORY_COMMAND` 并由指令流程实际写入；本票审计页分类筛选补齐 `COMMAND`，`audit.spec.ts` 增加“分类筛选可选 COMMAND”断言（`web/src/features/audit/views/AuditListView.vue`、`web/e2e/audit.spec.ts`）。后端查询、脱敏与只读行为未改变。
- `pnpm typecheck` / `pnpm lint` 均 exit 0（`evidence/21-web-checks-post-merge-16.log`）。
- `AuditViewerTest`：`Tests run: 9, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS，lock exit 0（`evidence/21-audit-tests-post-merge-16.log`）。
- 浏览器验证前先执行 `mvnw.cmd -B -pl simulator -am -DskipTests package`（任务16修改了 simulator）：BUILD SUCCESS，lock exit 0（`evidence/21-simulator-build-post-merge-16.log`）。
- `BrowserFlowTest` 本次首跑通过：`Tests run: 1, Failures: 0`，Playwright `31 passed (1.9m)`，其中 `audit.spec.ts` 3/3、`commands.spec.ts` 1/1；BUILD SUCCESS，lock exit 0（`evidence/21-browser-post-merge-16.log`、`evidence/logs/02-playwright.log`）；截图 `web/test-results/admin-audit-page.png` 重新生成（63499 bytes）。
- 复验结束后本轮 Testcontainers 容器已由 Ryuk 回收；检查时 11:38:45 起新建的 `mysql:8.4` 与 `testcontainers-ryuk-*` 属于其他 ticket 的新一轮测试，未清理，也未删除其他项目或开发容器。
- 集成基线缺口（观察记录，现已修复）：任务16合入的 `V14__command_log.sql` 使 Flyway 迁移由 6 个增至 7 个，并新增 `command_log`、`terminal_command_sequence` 两张表，当时 `SchemaUpgradeTest` 断言未同步；集成分支 `899daf1` 已修正，本票第三次合并（`38bd3a1`）后该测试 1/1 通过。

## 合并后复验（38bd3a1，含集成迁移断言修复）

- 集成分支 `899daf1`（fix: 对齐集成分支迁移数与命令表断言）仅修改 `SchemaUpgradeTest`：迁移数 6→7、表清单加入 `command_log` 与 `terminal_command_sequence`、history 计数 6→7。第三次合并提交 `38bd3a1`（父 `88af4ec` 与 `899daf1`），无冲突。
- `AuditViewerTest` 与 `SchemaUpgradeTest`：`Tests run: 10, Failures: 0, Errors: 0, Skipped: 0`（前者 9、后者 1），BUILD SUCCESS，lock exit 0（`evidence/21-backend-post-merge-899daf1.log`）。
- `BrowserFlowTest`：`Tests run: 1, Failures: 0`，Playwright `31 passed (2.3m)`（`audit.spec.ts` 3/3、`commands.spec.ts` 1/1），BUILD SUCCESS，lock exit 0（`evidence/21-browser-post-merge-899daf1.log`、`evidence/logs/02-playwright.log`）；截图 `web/test-results/admin-audit-page.png` 重新生成（63783 bytes）。
- 本轮 Testcontainers 容器由 Ryuk 回收；未清理其他 ticket 或开发容器。