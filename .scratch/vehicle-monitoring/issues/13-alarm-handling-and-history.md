# 13: 报警处置闭环

Status: resolved
Labels: ready-for-agent
Type: task
Blocked by: 12

## What to build

报警列表与详情展示报警时间、位置、当时关联司机、规则快照与处置历史。处置状态 PENDING→PROCESSING→HANDLED/IGNORED，登记操作者、时间与备注；condition_state（ACTIVE/RECOVERED/EVENT）与 handle_status 独立，异常恢复不自动处置，人工处置不自动恢复，终态不重新打开，新异常周期建新事件。处置历史同时保留异常条件变化与人工处置变化，二者分开记录；详情展示当时规则快照以便核查判定依据。同一报警的并发处置按当前状态条件更新，避免覆盖他人结果。关联关系采用报警当时的司机与车队，换关联不回写历史；车队长只读授权范围报警与处置历史，司机电话中间四位掩码。处置写 alarm_handle_log 与 notification_outbox 同事务，提交后即时推送。列表分别展示条件状态与处置状态两列，避免混同。本票自带页面、API、审计与测试。

## Spec coverage

主：AC19（US 57、61、62、63），见 [spec](../spec.md#可检查验收场景)。
复验：AC06 历史关系（报警当时司机不被换关联改写）；AC02 车队长只读与字段脱敏。事件来源为 [12](12-overspeed-rules-and-alarm-lifecycle.md)；报警列表推送的撤权与重连改正见 [17](17-websocket-revocation-and-resync.md)。

## Acceptance criteria

- [x] PENDING→PROCESSING→HANDLED/IGNORED 迁移可用，非法迁移被拒绝
- [x] 每次处置记录操作者、时间、备注与状态变化
- [x] condition_state 与 handle_status 独立，恢复不改处置，处置不改恢复
- [x] 终态不可重开，新周期生成新事件
- [x] 车队长只读授权范围报警与处置历史
- [x] 详情显示当时关联司机，换关联后历史不回写
- [x] 车队长司机电话中间四位掩码
- [x] 处置日志与 outbox 同事务，提交后即时推送
- [x] 真实 HTTP 权限矩阵测试与数据库核对记录

## Scope boundaries

不实现报警规则配置与超速判定（12）、离线与围栏报警（14、15）、Excel 导出（20）、WS 撤权与重连（17）；不做人工归档、终态重开或自动处置。后票不代本票测试。

## Validation

以真实 HTTP 请求验证三角色权限矩阵与并发处置，核对 alarm 与 alarm_handle_log 的实际状态；用浏览器操作车队长账号验证只读与掩码显示；用 WS 订阅观察处置提交后推送。记录命令、环境、版本、时段与期望实际结果，逐条标注状态。

## Comments

（暂无）

## Answer

已实现并完成验证，逐条 AC 证据、命令与环境记录见 `.scratch/vehicle-monitoring/evidence/13-verification.md`。

实现：

- `V9__alarm_handle.sql` 新增 `alarm_handle_log`：`change_type`（`CONDITION`/`HANDLE`）、`from_status`、`to_status`、`operator_id`（条件变化为空、人工处置非空，CHECK 约束）、`remark`（≤500）与 `created_at`；CHECK 拒绝 `from_status = to_status`，按 `(alarm_id, created_at, id)` 建索引，FK 指向 `alarm` 与 `sys_user`。
- `com.vms.alarm.AlarmHandleService`：按 `PENDING→PROCESSING→HANDLED/IGNORED` 迁移，以 `UPDATE alarm SET handle_status=? WHERE id=? AND handle_status=<当前状态>` 条件更新实现并发安全；非法迁移或状态已变化返回 409 `ALARM_HANDLE_ILLEGAL`；同一事务写 `alarm_handle_log`、`notification_outbox`（`ALARM_EVENT`，`event_id=alarm:handle:<id>:<logId>`）与 `CATEGORY_ALARM/HANDLE` 审计，提交后由 outbox worker 推送 WS `alarm_event`。
- `OverspeedAlarmParticipant` 恢复成功时写 `CONDITION` 日志（`ACTIVE→RECOVERED`，operator/remark 为空），条件变化与人工处置分开记录；恢复不改 `handle_status`，处置不改 `condition_state`；终态不可重开，新异常周期沿用任务12语义新建 `alarm` 行与 `alarm:created:<id>` 事件。报警的 `driver_id` 与 `fleet_id_at_event` 为事件快照，换绑不回写历史。
- `AlarmQueryService` + `AlarmController`：`GET /api/v1/alarms`（可选 `fleetId`，`FleetScopeSql` 按事件车队限定范围）、`GET /api/v1/alarms/{alarmId}`（报警时间/位置、当时司机、`rule_snapshot`、`handleHistory` 与 `conditionHistory` 分离）、`POST /api/v1/alarms/{alarmId}/handle`（`targetStatus` 必填、`remark` ≤500）。读权限 `FLEET_VIEW`、处置权限 `ALARM_HANDLE`（ADMIN/MONITOR）；越界车队 403、未登录 401、不存在 404；`FLEET_LEADER` 电话中间四位掩码（`138****5678`）。
- 前端 `web/src/features/alarms/`（`api.ts` + `AlarmListView.vue`）：`/monitor/alarms` 由占位替换为真实页面，路由 `name`/`area`/`ticket` 保持；列表分列条件状态与处置状态，详情展示规则快照、当时司机与电话、处置/条件两段历史；处置表单仅 ADMIN/MONITOR 可见，终态显示不可重开提示；车队长显示只读提示且无表单。
- 设计同步：`docs/design/系统设计方案.md` 的 `alarm_handle_log` 字段增补 `change_type`，并新增任务13表结构、状态机、事务/推送、API 权限与前端说明。

验证（均经 `with-test-lock.ps1` 全局串行锁；Testcontainers `mysql:8.4`/`redis:8.4`，Ryuk 启用并已回收）：

- TDD 红→绿：迁移、列表/详情、状态机、恢复条件日志、全流程五个切片均有红阶段失败记录（迁移缺失、接口 404 等目标断言失败）与绿阶段通过记录，未预填通过。
- `AlarmHandlingFlowTest` 6 项（真实 MySQL/Redis + 真实 HTTP/WS/TCP）：双状态与司机快照/掩码/401/403/404；状态机逐步日志与非法迁移 409（DB 不变 + 审计）；恢复条件日志分离与新周期新事件；并发处置 200 vs 409 且仅一条日志/outbox；提交后 WS 推送与 outbox `DONE`；outbox 写失败同事务回滚、修复后重试成功。
- 回归锁内 `-Dtest=OverspeedAlarmFlowTest,TelemetryFlowTest,SchemaUpgradeTest,PositionQualityFlowTest`：`Tests run: 30, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS；`SchemaUpgradeTest` 期望 11 个迁移且表清单含 `alarm_handle_log`。
- 前端 `pnpm --dir web typecheck/lint/build` 均 exit 0（lint 0 error、137 warning，其中本票新增 6 条 `vue/singleline-html-element-content-newline` 风格提示），构建产物含 `AlarmListView-Du2wYoTt.js`。
- 浏览器（锁内 simulator package 后 `-Dvms.browser-tests=true -Dtest=BrowserFlowTest`）：共 7 次运行，逐次原因与结果见证据第 5 节；rerun6 `BUILD SUCCESS`，Playwright `40 passed (3.6m)`（39 项基线 + 新增 `alarms.spec.ts` 1 项），rerun4 亦全绿。`alarms.spec.ts` 用真实 TCP simulator 触发超速，管理员在页面完成 PENDING→PROCESSING→HANDLED 并核对双状态、当时司机、规则快照与 2 条处置历史；车队长只读且电话 `138****5678`。首轮/rerun1 为新增 spec 自身字段路径与语法问题（已修正 spec），rerun2 为 `archives.spec.ts` 既有 5s 登录偶发超时（按允许重跑一次），rerun3/rerun5 为 `[Playwright finishes within four minutes]` 墙钟预算超时（rerun5 被中断前 1–37 项含新增用例均通过）；既有 spec 未修改、断言未放宽。
- 容器：本票各轮运行的 mysql/redis/Ryuk 容器均已回收；现存 `086e2cc950ce` 属其他 session，按要求未清理。

偏差与边界：分支按指示从 `4e3cd32` 建立，先 `--no-ff` 合入任务12（`57b58af`），收尾合并 `integration/tickets-08-27`。规则配置与超速判定属任务12（已合入）；离线/GNSS/终端报警（14）、围栏（15）、导出（20）、WS 撤权深化（17）未在本票实现；未做人工归档、终态重开或自动处置。`BrowserFlowTest` 依赖 simulator 预编译产物的跨票问题（任务27 在干净环境处理），本轮以锁内 `-pl simulator -am -DskipTests package` 配合浏览器验证。

