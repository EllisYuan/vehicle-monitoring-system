# 12: 可配置超速报警与持续事件

Status: resolved
Labels: ready-for-agent
Type: task
Blocked by: 04, 08

## What to build

全局默认与车队覆盖两级超速规则，管理员界面维护阈值（默认 80 km/h），车队覆盖优先，规则参数按版本保存，事件保留当时规则依据。连续 3 个有效实时点超限成立 ACTIVE；连续 3 个有效点 ≤阈值恢复，无额外滞回；候选点间隔 >30 秒重计，但不清除 ACTIVE。数据库以车辆、类型、来源与相关规则维度唯一约束保障同维度最多一个 ACTIVE，恢复按 alarm_id 与当前状态条件更新，避免误关新周期。位置处理在同一事务内写报警、车辆判定 state 与 notification_outbox，提交后才回 ACK；报警快照保存当时关联司机、规则快照与 fleet_id_at_event。新报警提交后即时推送报警列表。候选计数在应用重启后重建，不据停机期间数据补算；规则修改只影响之后的位置判定，不追溯既有事件。只做超速，不做处置、围栏、离线与无效定位报警。本票自带 migration、界面、API、审计与测试。

## Spec coverage

主：AC18（US 55、56、57、64、65），见 [spec](../spec.md#可检查验收场景)。
复验：AC06 关联司机快照（报警固定当时 driver_id）；AC15 事务部分（报警与快照/outbox 原子提交，07 基线）。后续 [13](13-alarm-handling-and-history.md) 的处置基于本票事件。

## Acceptance criteria

- [x] 全局默认与车队覆盖规则可维护，覆盖优先，版本保存
- [x] 连续 3 个有效实时点超限成立 ACTIVE，逐点上报不新增
- [x] 连续 3 个有效点 ≤阈值恢复，按 alarm_id 条件更新
- [x] 间隔 >30 秒候选重计，ACTIVE 被保留
- [x] 并发判定下同维度最多一个 ACTIVE，有数据库唯一约束证据
- [x] 报警保存当时司机、规则快照与 fleet_id_at_event
- [x] 报警/state/outbox 同事务，提交后 ACK；重启后 ACTIVE 不自动恢复
- [x] 新报警即时推送到订阅列表
- [x] 真实 MySQL 并发与回滚测试记录

## Scope boundaries

不含处置闭环（13）、离线/GNSS/终端来源报警（14）、围栏（15）；不实现报警列表处置按钮与导出；不改 07 位置事务结构，只挂接报警写入。后票不代本票测试。

## Validation

向真实 TCP listener 发送受控超速位置序列或经模拟终端上报，核对 ACK 时序、DB 中 alarm、state、outbox、WS 推送与重启行为；并发判断用真实 MySQL 记录实际结果。记录命令、环境、版本、时段、期望与实际，逐条标注状态。

## Comments

（暂无）

## Answer

已实现并完成验证，详细证据与逐条 AC 记录见 `.scratch/vehicle-monitoring/evidence/12-verification.md`。

实现：

- `V8__overspeed_alarm.sql` 新增 `alarm_rule`（seed GLOBAL OVERSPEED 80 km/h、version 1；`global_alarm_type` 生成列与 `uk_alarm_rule_global`、`uk_alarm_rule_fleet`）、`alarm`（`rule_id`/`rule_version`/`rule_snapshot`、`fleet_id_at_event`、`start_time`/`last_time`/`recovered_at`；`active_dimension` 仅在 `condition_state='ACTIVE'` 时非空并受 `uk_alarm_active_dimension` 唯一约束）、`alarm_candidate_state`（`uk_candidate_dimension` 按车辆/类型/来源/维度唯一）。
- `com.vms.alarm`：`AlarmRuleService.resolve` 车队覆盖优先；`OverspeedAlarmParticipant implements PositionStateParticipant` 在 07 位置事务内判定连续 3 点成立/恢复、相邻候选点间隔 >30 秒重计且保留 ACTIVE、规则版本变化重计、无效点不推进；报警保存当时 `driver_id`、规则快照与 `fleet_id_at_event`；恢复按 `id` 与 `condition_state='ACTIVE'` 条件更新，仅实际更新才产生恢复事件；并发确认以唯一约束兜底并转为延续既有 ACTIVE。
- outbox 新增 `ALARM_EVENT`（`alarm:created:<id>`、`alarm:recovered:<id>`，version 恒 1、不合并）；`MonitorWebSocketHandler.publishAlarm` 按订阅者重新鉴权与车队范围读取，越界订阅者不产生帧；`PositionOutboxWorker` 按 `event_type` 路由。
- 管理 API `/api/v1/admin/alarm-rules`（GET overview、PUT `/global`、PUT/DELETE `/fleets/{fleetId}`），`@RequiresPermission(ALARM_RULE_MANAGE)`（仅 ADMIN），阈值 `@Min(1) @Max(300)`，修改递增 `version` 并写 `CATEGORY_ALARM` 审计（`UPDATE_GLOBAL`/`UPSERT_FLEET_OVERRIDE`/`DELETE_FLEET_OVERRIDE`），越界 fleetId 抛 `ForbiddenException`。
- 前端新增 `web/src/features/alarm-rules/`（API + `AlarmRuleView.vue`），`/admin/alarm-rules` 占位替换为真实页面，保留原 `name`/`area`/`roles`/`title` 与导航项。
- 设计同步：`docs/design/系统设计方案.md` 追加任务12表结构、事务挂接、outbox/WS 事件、API 权限与审计说明。

验证（均经 `with-test-lock.ps1` 全局串行锁；Testcontainers `mysql:8.4`/`redis:8.4`，Ryuk 启用并已回收）：

- TDD 红→绿：迁移、规则 API、成立、恢复、间隔/无效点、推送、同事务/并发/重启各切片均有红阶段失败记录与绿阶段通过记录。
- `OverspeedAlarmFlowTest` 10 项：3 点成立与快照、3 点恢复与新周期、30 秒边界与重计、无效点、推送与越界过滤、同事务回滚/提交后 ACK、并发唯一与唯一索引、重启保留、规则 API 与覆盖优先级。
- `SchemaUpgradeTest` 2 项：空库 10 个迁移且表清单含 `alarm`/`alarm_candidate_state`/`alarm_rule`；从 V6 升级应用 4 个迁移，既有分区与数据不变。
- 全量 `mvnw.cmd -B -pl server -am test`：26 个测试类，`Tests run: 150, Failures: 0, Errors: 0, Skipped: 1`，BUILD SUCCESS；唯一 skipped 为需浏览器开关的 `BrowserFlowTest`。
- 合并 `integration/tickets-08-27`（`4e3cd32`，含任务09、10）后复跑 `OverspeedAlarmFlowTest,SchemaUpgradeTest` 12 项通过，`router.ts` 同时保留轨迹回放与报警规则页面。
- 前端 `pnpm typecheck/lint/build` 全部退出码 0（lint 0 error、70 条既有风格 warning），构建产物含 `AlarmRuleView-*.js`。

遗留边界：报警列表、详情与人工处置属任务13；离线/GNSS/终端来源报警属任务14；围栏属任务15。越界 fleetId 的 403 分支为静态检查（规则管理仅 ADMIN，ADMIN 为全企业范围，现有角色下 HTTP 不可触发）。本票未执行 Playwright 浏览器 e2e，页面行为由 typecheck/lint/build 与后端规则 API 测试覆盖。
