# 21: 管理员审计与运行记录查询

Status: resolved
Labels: ready-for-agent
Type: task
Blocked by: 03

## What to build

为 ADMIN 提供登录审计与关键写操作审计的查询页面。前提是前序票在交付时各自写入审计，本票不代写、不集中补录；若前票未写，应回到对应票修复，而不是在本票伪造记录。支持按时间、操作者、动作、结果筛选与分页；详情展示条件与结果，但对 password、鉴权码、token、WS ticket 等凭证脱敏，不显示明文。MONITOR 与 FLEET_LEADER 访问审计接口时服务端拒绝；车队长不可见系统审计。运行记录包含 outbox DEAD、分区维护失败等已实现告警，展示与源事实一致，不另行编造。审计只读，页面不提供删除或修改入口。筛选条件在服务端解析与校验，分页大小有上限，避免一次拉取全部审计；查询有界，不拖慢业务写入。

## Spec coverage

主：US12、AC02 审计权限项。次（复验）：US9 撤权即时生效、US54 维护失败可见记录、AC02 权限矩阵中的系统审计行。详见 [spec](../spec.md)。

## Acceptance criteria

- [x] 登录成功/失败与关键写操作审计可查询，数据由前序票实际写入
- [x] 按时间、操作者、动作、结果筛选与分页有效
- [x] 详情不含 password、鉴权码、token、WS ticket 明文
- [x] MONITOR 与 FLEET_LEADER 访问被服务端拒绝
- [x] 审计记录只读，页面无删除或修改入口
- [x] 已实现的运行记录与源事实一致；尚未提供的 outbox DEAD、分区维护等记录在 11/18/27 补充复验，不阻塞本票关闭
- [x] 审计撤权后旧会话访问及时失效
- [x] 查询有界，不影响业务写入

## Scope boundaries

不导出审计（三类导出不含审计）；本期不自动清理审计；不接入外部日志平台、不做日志检索集群；不重新定义前票的审计写入格式，只按实际落地字段查询；不为前票补写历史记录。

## Validation

通过 HTTP 以三角色验证查询与拒绝；与真实 MySQL 中审计行直查对照；构造含凭证的样例验证脱敏，并抽样确认登录失败记录不含提交的密码；检查页面无写入口；记录环境、版本、命令与结果。未执行项不得勾选。

## Comments

登录审计由账号与会话票负责写入，业务审计由各写操作票负责写入；本票只读并负责查询、筛选与展示。若前票尚未写审计，本票列出缺口并保持 blocked，不伪造记录。与 20 号导出的审计记录可核对但不耦合。

## Answer

已实现并通过验证（2026-11-16，分支 feat/ticket-21-audit-viewer）。

实现：新增 `Permission.AUDIT_VIEW`（仅 ADMIN）；`AuditController` 提供只读 `GET /api/v1/audit/logs` 与 `GET /api/v1/audit/operation-records`，无任何写方法；`AuditQueryService` 按 `audit_log` 实际字段查询，支持 actor/category/action/result 与时间窗、分页，默认窗口 7 天、最大 31 天，size 1–100（默认 20），offset 上限 10000，detail 对 password/鉴权码/token/WS ticket 脱敏；运行记录读取 `notification_outbox` 各状态计数与 DEAD 明细；前端 `/admin/audit` 替换占位页，提供筛选、分页、只读表格与运行记录区，无删除或修改入口。无数据库迁移，未改动 `SchemaUpgradeTest`，未重定义前票审计格式。

验证命令与结果：
- `mvnw.cmd -B -pl server -am '-Dtest=AuditViewerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`：Tests run: 9, Failures: 0, Errors: 0, Skipped: 0，BUILD SUCCESS（`evidence/21-audit-tests.log`）。
- 统一浏览器入口 `BrowserFlowTest`：Tests run: 1, Failures: 0；Playwright 20 passed，其中 `audit.spec.ts` 3/3；截图 `web/test-results/admin-audit-page.png`（`evidence/logs/02-playwright.log`）。
- 真实 HTTP（独立 MySQL 8.4 容器、端口 18081）：ADMIN 查询 200，MONITOR/FLEET_LEADER 403 FORBIDDEN，越界参数 400，写方法 405，撤权后旧 token 401 ACCOUNT_DISABLED，脱敏样例与 MySQL 直查一致（`evidence/21-http-verification.log`、`evidence/21-package.log`）。
- `pnpm typecheck` / `pnpm lint` / `pnpm build` 均 exit 0（`evidence/21-web-checks.log`）。
- 汇总见 `evidence/21-verification.md`。

合并后复验（合并提交 `e3a28fa`，父 `064383b` 与 `integration/tickets-08-27` 的 `2e9fbe2`，无冲突）：`AuditViewerTest` 再次 9/9；`BrowserFlowTest` 第三次运行 Playwright 30/30（`audit.spec.ts` 3/3，`evidence/21-browser-post-merge-final.log`）。前两次运行仅首个既有用例 `archives.spec.ts` 的 `current-user` 5s 登录断言超时（登录 POST 实际 200、耗时 3389ms，失败快照已登录），trace 定位与本票改动无关；详见 `evidence/21-verification.md` 的“合并后复验”。
第二次合并（合并提交 `e5f7936`，父 `5b3de0c` 与 `integration/tickets-08-27` 的 `6987d1b`，含任务16指令票）：唯一冲突 `Permission.java`，保留 `AUDIT_VIEW` 与 `COMMAND_DISPATCH`。任务16新增 `AuditService.CATEGORY_COMMAND` 并实际写入审计；本票审计页分类筛选补齐 `COMMAND` 并在 `audit.spec.ts` 增加断言。复验：`AuditViewerTest` 9/9；`BrowserFlowTest` 首跑 Playwright 31/31（`audit.spec.ts` 3/3、`commands.spec.ts` 1/1）；`pnpm typecheck` / `lint` exit 0。证据：`evidence/21-audit-tests-post-merge-16.log`、`evidence/21-browser-post-merge-16.log`、`evidence/21-web-checks-post-merge-16.log`，详见 `evidence/21-verification.md`。
第三次合并（合并提交 `38bd3a1`，父 `88af4ec` 与集成分支 `899daf1`）：集成分支修复 `SchemaUpgradeTest` 的 V14 迁移数与表断言，无冲突。复验：`AuditViewerTest` 9/9、`SchemaUpgradeTest` 1/1（合计 10/10）、`BrowserFlowTest` Playwright 31/31（`audit.spec.ts` 3/3、`commands.spec.ts` 1/1）；证据 `evidence/21-backend-post-merge-899daf1.log`、`evidence/21-browser-post-merge-899daf1.log`。
第四次合并（合并提交 `64644ed`，父 `7d28c17` 与集成分支 `eecda2c`，含任务17订阅撤权）：无冲突。复验：`AuditViewerTest` 9/9、`BrowserFlowTest` Playwright 34/34（`audit.spec.ts` 3/3、`commands.spec.ts` 1/1，任务17新增 3 项亦通过）；证据 `evidence/21-audit-tests-post-merge-64644ed.log`、`evidence/21-browser-post-merge-64644ed.log`。

缺口：分区维护失败告警依赖 11 号票的维护功能，尚未交付，本票如实提示且未编造占位数据，按约定在 11/18/27 补充复验；outbox DEAD 已实现并验证。审计导出与自动清理在 Scope 外。另，任务16合入的 `V14__command_log.sql` 使 `SchemaUpgradeTest` 的迁移数与表清单断言失配（迁移 6→7，新增 `command_log`、`terminal_command_sequence`），集成分支 `899daf1` 已对齐断言；本票第三次合并（`38bd3a1`）后 `SchemaUpgradeTest` 1/1 通过，本票未改动该测试。
