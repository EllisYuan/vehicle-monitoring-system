# 任务12 验证记录：可配置超速报警与持续事件

- 日期：2026-11-16
- 分支：feat/ticket-12-overspeed-alarm；基线 integration/tickets-08-27 HEAD `b221243`
- 结论：**Status: resolved**（待提交前合并集成分支并按合并后结果复验）。9 条 AC 中 8 条有真实 TCP / 真实 MySQL / WS 帧证据，1 条（越界 fleetId 的 403 分支）为静态检查。

## 环境

- Windows 11 Pro for Workstations 10.0.26200；JDK 21.0.9；Maven Wrapper（`mvnw.cmd`）。
- Spring Boot 4.1.2；Testcontainers 2.0.5：MySQL 8.4（真实生成列与唯一约束）、Redis 8.4、Ryuk 0.14.0（保持启用，结束自动回收）。
- 所有 Maven 命令经全局串行锁 `C:\Users\YuanS\AppData\Local\Temp\vms-spec-notes\with-test-lock.ps1` 执行；前端 `pnpm typecheck/lint/build` 在锁外执行。
- 可控时钟：`MutableClock`（`@Primary` bean），测试以 `advance(Duration)` 推进 `received_at` 与 `gps_time` 的时差窗口。
- 真实 TCP：`TerminalClient` 直连 `vms.access.tcp-port=0` 随机端口，发送 0x0200 定位帧并解析 0x8001 应答。

## 实现要点（与 AC 对应）

- 迁移 `server/src/main/resources/db/migration/V8__overspeed_alarm.sql`：`alarm_rule`（`global_alarm_type` 生成列唯一约束 + `uk_alarm_rule_fleet`；seed GLOBAL OVERSPEED 80 km/h、version 1）、`alarm`（`active_dimension` 仅在 `condition_state='ACTIVE'` 时非空，`uk_alarm_active_dimension` 唯一；`ck_alarm_recovered` 约束恢复时间）、`alarm_candidate_state`（`uk_candidate_dimension` 按车辆/类型/来源/维度唯一）。
- 规则解析 `AlarmRuleService.resolve(fleetId)`：`enabled=1`，按 `ORDER BY CASE WHEN scope_type='FLEET' THEN 0 ELSE 1 END LIMIT 1` 实现车队覆盖优先于全局默认，返回规则 id、scope、fleetId、阈值与 version。
- 判定 `OverspeedAlarmParticipant implements PositionStateParticipant`：`speed/10.0 > thresholdKph` 为 OVER；候选连续条件为同 `rule_id`/`rule_version`、点序前进、相邻点间隔 ≤30s（恰 30s 仍连续）；`consecutive_count>=3` 时 OVER 走 `activate`、UNDER 走 `recover`；计数不足且 OVER 时 `touchActive` 推进已有 ACTIVE 的 `last_time`。`activate` 先 `FOR UPDATE` 查 ACTIVE，无则插入报警（`driver_id`、`fleet_id_at_event`、`rule_snapshot` JSON），捕获 `DuplicateKeyException` 转为延续既有 ACTIVE；成功后 enqueue `alarm:created:<id>`。`recover` 按 `id=? AND condition_state='ACTIVE'` 条件更新，仅 `updated>0` 才 enqueue `alarm:recovered:<id>`，不改 `last_time`。
- 事务：`OverspeedAlarmParticipant.apply` 仅在 `PositionIngestService.applyRealtimeState`（realtime && fixValid）内、与 `track_point`/`vehicle_last_position`/`vehicle_monitor_state`/`position:<vehicleId>` outbox 同一 `@Transactional` 事务执行；异常整体回滚、不返回成功 ACK。
- Outbox 与 WS：`notification_outbox.event_type='ALARM_EVENT'` 的 `alarm:created`/`alarm:recovered` 按事件 id 独立投递（version 恒 1、不按车合并）；worker 按 `event_type` 路由；`MonitorWebSocketHandler.publishAlarm` 对每个订阅者重新 `requireToken` + `requirePermission(FLEET_VIEW)`，并按 `FleetScopeSql.where("a.fleet_id_at_event", ...)` 过滤，越界订阅者不产出帧。WS 帧 `type='alarm_event'`，含 `event_id/aggregate_id/version/server_time/payload`。
- API：`/api/v1/admin/alarm-rules`（GET overview、PUT `/global`、PUT/DELETE `/fleets/{fleetId}`），类级 `@RequiresPermission(ALARM_RULE_MANAGE)`（仅 ADMIN）；`ThresholdRequest` 为 `@Min(1) @Max(300)`；`requireFleetInScope` 对越界 fleetId 抛 `ForbiddenException`，`requireFleetExists` 抛 404；修改递增 `alarm_rule.version` 并写 `CATEGORY_ALARM` 审计（`UPDATE_GLOBAL`/`UPSERT_FLEET_OVERRIDE`/`DELETE_FLEET_OVERRIDE`）。
- 前端：`web/src/features/alarm-rules/api.ts` + `views/AlarmRuleView.vue`；`/admin/alarm-rules` 路由由占位替换为真实页面，保留 `name:'admin-alarm-rules'` 与 meta `area:'admin'`、`roles:['ADMIN']`、`title:'报警规则'`；页面维护全局阈值与车队覆盖（新增/更新/删除、版本与更新时间展示）。
- 设计同步：`docs/design/系统设计方案.md` 已追加任务12的 V8 表结构、事务挂接、outbox/WS 事件与 API 说明。

## 已执行

### 1. TDD 红→绿

命令模板（均走锁）：

```
mvnw.cmd -B -pl server -am -Dtest=OverspeedAlarmFlowTest#<方法> -Dsurefire.failIfNoSpecifiedTests=false test
```

| 切片 | 红 | 绿 |
| --- | --- | --- |
| V8 迁移与 SchemaUpgradeTest 对齐 | `12-red-schema.log`：Tests run 2，Failures 2，BUILD FAILURE | `12-schema-green.log`：Tests run 2，全通过，BUILD SUCCESS |
| 规则 API 维护、覆盖、版本、审计、角色 | `12-red-rule-api.log`：整类 6 项中 2 项失败，BUILD FAILURE | `12-rule-api-progress.log`：规则两项 2 通过；整类见下方 10 项 |
| 3 点成立、逐点不新增、司机/规则快照/fleet_id_at_event | `12-red-alarm-active.log`：Tests run 1，Errors 1，BUILD FAILURE | `12-alarm-active-green.log`：Tests run 1，全通过，BUILD SUCCESS |
| 3 点恢复、按 alarm_id、下一周期新事件 | `12-red-recovery.log`：Tests run 2，Failures 1，Errors 1，BUILD FAILURE | `12-alarm-lifecycle-green.log`：Tests run 2，全通过，BUILD SUCCESS |
| 间隔重计、无效点不推进 | `12-red-recovery.log` 覆盖首轮；补齐后 | `12-alarm-gap-invalid-green.log`：累计 Tests run 4，全通过 |
| 新报警即时推送、越界订阅者不推送 | `12-red-alarm-push.log`：Tests run 1，Failures 1，BUILD FAILURE | `12-alarm-push-green.log`：Tests run 1，全通过，BUILD SUCCESS |
| 同事务回滚/提交后 ACK、并发唯一、重启保留 | 红阶段由 `12-red-alarm-active.log` 等缺失实现路径覆盖 | `12-alarm-transaction-concurrency-restart.log`：Tests run 3，全通过 |

- 红阶段失败均来自缺失实现（无 `alarm` 表、规则接口 404、无报警帧、无事务内写入等），未预填通过。
- 结果：通过。

### 2. 整类回归

```
mvnw.cmd -B -pl server -am -Dtest=OverspeedAlarmFlowTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- `12-alarm-flow-full-green.log`：`Tests run: 10, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS（52.74 s）。
- 覆盖：3 点成立与快照、3 点恢复与新周期、间隔重计、无效点、推送、同事务与 ACK、并发唯一与唯一索引、重启保留、规则 API、覆盖优先与规则变更不追溯。
- 结果：通过。

### 3. SchemaUpgradeTest

```
mvnw.cmd -B -pl server -am -Dtest=SchemaUpgradeTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- `12-schema-green.log`：`Tests run: 2, Failures: 0, Errors: 0`，BUILD SUCCESS。
- 空库：`migrationsExecuted=10`；表清单含 `alarm`、`alarm_candidate_state`、`alarm_rule`；`flyway_schema_history` applied=10、versions=10、all_ok=1。
- 从 V6 升级：应用 V7、V8、V11、V14 共 4 个迁移；`track_point` 37 个日分区与既有数据不变；`system_config` 保留 30 天；`track_partition_maintenance` 为空。
- 结果：通过。

### 4. 前端静态检查与构建（锁外）

- `pnpm --dir web typecheck`：exit 0（`12-frontend-typecheck.log`）。
- `pnpm --dir web lint`：exit 0；0 errors、70 warnings（`12-frontend-lint.log`）；新增 `AlarmRuleView.vue` 的 warning 与既有 `TrackRetentionView.vue` 同为 `vue/max-attributes-per-line`、`vue/singleline-html-element-content-newline` 风格项。
- `pnpm --dir web build`：BUILD 成功，产出 `AlarmRuleView-*.js` 与 CSS（`12-frontend-build.log`）。
- 依赖安装：worktree 首次运行前执行 `pnpm --dir web install --frozen-lockfile`（`12-frontend-install.log`）。
- 结果：通过。

### 5. 静态检查与日志扫描

- `AlarmRuleAdminController` 类级 `@RequiresPermission(Permission.ALARM_RULE_MANAGE)`；`Permission.ALARM_RULE_MANAGE` 仅授予 `RoleCode.ADMIN`，既有权限项未改动。
- `AlarmRuleAdminService` 的 `requireFleetInScope` 对越界 fleetId 抛 `ForbiddenException`；由于当前仅 ADMIN 拥有规则管理权限且 ADMIN 为全企业范围，该分支在现有角色矩阵下不可由 HTTP 触发，属静态检查项。
- 日志凭证扫描（`12-log-scan.log`）：`12-*.log` 未发现真实外部凭证或 API key；出现的 `Alarm-Pass-2026` 为测试内 bootstrap-admin 固定密码，`satoken` 为 Testcontainers 临时会话 token，容器销毁即失效。

### 6. 全量 server 回归

```
with-test-lock.ps1 mvnw.cmd -B -pl server -am test
```

- `12-server-full-suite.log`：26 个测试类，`Tests run: 150, Failures: 0, Errors: 0, Skipped: 1`，BUILD SUCCESS（reaction 15:48 min）。
- 其中 `OverspeedAlarmFlowTest` 10 项、`SchemaUpgradeTest` 2 项、`TerminalTcpIntegrationTest` 13 项、`CommandFlowTest` 11 项、`AuditViewerTest` 9 项、`TelemetryFlowTest` 等既有类全部通过。
- 唯一 skipped 为 `BrowserFlowTest`：需 `-Dvms.browser-tests=true` 与 Chromium 环境，本票未启用浏览器全量。
- 结果：通过。

### 7. 测试资源回收

- 全量运行结束后 `docker ps -a` 中无本轮 Testcontainers 的 MySQL/Redis/Ryuk 容器（Ryuk 保持启用并自动回收）。
- 其他项目的停止容器（`plp-*`、`pms_*`、`vms-mysql84` 等）保持原状，未清理。

## 逐项 AC 状态

| Acceptance criterion | 状态 | 依据 |
| --- | --- | --- |
| 全局默认与车队覆盖规则可维护，覆盖优先，版本保存 | 通过 | `adminMaintainsGlobalAndFleetRulesWithVersionsValidationAuditAndRoleCheck`：GET 初始 GLOBAL 80/v1；PUT global 95 → v2；PUT fleet 60 → v1、再 PUT 65 → v2；DELETE 204 且 overview 为空；审计含 `UPDATE_GLOBAL`/`UPSERT_FLEET_OVERRIDE` 与 `thresholdKph=95`；MONITOR 读写均 403；阈值 0 → 400。`fleetOverrideWinsAndRuleChangesOnlyAffectLaterPositions`：车队 A 覆盖 90 时 70 km/h 不成立（全局 50），车队 B 用全局 50 成立且 `rule_snapshot.scopeType=GLOBAL`；A 覆盖改 60 后新事件 `rule_version=2`、`scopeType=FLEET`；全局改为 55 后既有事件 `rule_version` 与快照不变 |
| 连续 3 个有效实时点超限成立 ACTIVE，逐点上报不新增 | 通过 | `threeConsecutiveOverspeedPointsCreateSingleActiveAlarmWithSnapshots`：2 点 0 报警、候选 count=2/OVER；第 3 点 1 条 ACTIVE/PENDING；第 4 点仍 1 条、`last_time` 推进、候选 count=4 |
| 连续 3 个有效点 ≤阈值恢复，按 alarm_id 条件更新 | 通过 | `threeConsecutiveQuietPointsRecoverActiveAndNextCycleCreatesNewEvent`：2 低点仍 ACTIVE，第 3 低点 RECOVERED、`recovered_at` 正确、`last_time` 停在最后超速点、`alarm:recovered` 单次；下一周期新 id 并单次 `alarm:created`。`invalidFixPointsDoNotAdvanceConfirmationOrRecovery`：无效点不推进成立/恢复 |
| 间隔 >30 秒候选重计，ACTIVE 被保留 | 通过 | `candidateRecountsAfterGapBeyondThirtySecondsButActiveAlarmStays`：恰 30s 仍 count=4；31s 后 count=1/OVER 且 ACTIVE 保留、`recovered_at` NULL、`last_time` 推进；重计后再次 3 点确认不新建事件、仍 1 条 created |
| 并发判定下同维度最多一个 ACTIVE，有数据库唯一约束证据 | 通过 | `concurrentConfirmationKeepsOneActiveAlarmAndDatabaseConstraintRejectsDuplicate`：两线程同事务契约并发调用后 ACTIVE 恰 1 条、`ALARM_EVENT` created 恰 1 条；`information_schema.statistics` 中 `uk_alarm_active_dimension` 唯一索引数量 1（non_unique=0）；直接插入第二个 ACTIVE 抛 `DataIntegrityViolationException` 且消息含 `uk_alarm_active_dimension` |
| 报警保存当时司机、规则快照与 fleet_id_at_event | 通过 | `threeConsecutiveOverspeedPointsCreateSingleActiveAlarmWithSnapshots`：`driver_id` 为触发时绑定司机、`fleet_id_at_event` 为车辆当时车队、`rule_id` 为全局规则、`rule_version=1`、`rule_snapshot` 含 `thresholdKph=80`/`scopeType=GLOBAL`/`ruleVersion=1` |
| 报警/state/outbox 同事务，提交后 ACK；重启后 ACTIVE 不自动恢复 | 通过 | `alarmWriteJoinsPositionTransactionSoFailuresRollBackWithoutAckAndCommitPrecedesAck`：注入 CHECK 使报警插入失败 → TCP 读取 -1 无 ACK；`track_point`=2、`vehicle_last_position.version`=2、候选保持 2 且 `last_point_time` 不变、位置 outbox=2、无 `ALARM_EVENT`；blocker 锁 terminal 行时 350ms 无 ACK 且报警不可见；rollback 后 ACK 且报警 1 条。`activeAlarmSurvivesCandidateRebuildAndOnlyRecoversAfterThreeQuietPoints`：删除 `alarm_candidate_state` 模拟重启后 ACTIVE 保留、不补算、后续从 1 重计，3 个低点后恢复 |
| 新报警即时推送到订阅列表 | 通过 | `newAlarmIsPushedToAuthorizedSubscribersOnly`：admin 订阅收到 `alarm_event` 帧，`event_id=alarm:created:<id>`、version=1、`server_time` 非空、payload 含 alarmId/vehicleId/fleetId/alarmType=OVERSPEED/source=PLATFORM/conditionState=ACTIVE/ruleVersion=1；位置帧不回归；其他车队 leader 3.5s 内无报警帧 |
| 真实 MySQL 并发与回滚测试记录 | 通过 | 上述并发与回滚用例均运行在 Testcontainers `mysql:8.4`；并发用例读取真实 `information_schema.statistics` 与真实唯一索引冲突异常，回滚用例使用真实约束注入与真实表行锁 |

## 失败与修正

- 并发用例首轮断言 `consecutive_count == 4` 失败（`expected: 4 but was: 1`）。诊断：两线程各自先读候选再写，后点先提交时前点触发重计，最终计数取决于线程交错（1 或 3），原断言依赖调度顺序。修正：删除顺序相关计数断言，保留方向断言与真正的不变量（唯一 ACTIVE、单次创建事件、唯一索引存在、重复 ACTIVE 被拒）。`12-concurrency-diagnostic.log` 为单跑通过的诊断记录，修正后 `12-alarm-transaction-concurrency-restart.log` 3 项通过。
- 整类首轮运行失败：回滚用例注入的表级 CHECK `alarm_type <> 'OVERSPEED'` 与同一 Spring 上下文中前置测试遗留的 OVERSPEED 行冲突，`ALTER TABLE` 校验失败。修正：约束改为仅作用于本测试车辆（`vehicle_id <> <本测试车辆>`），只调整测试夹具，不改实现；修正后整类 10 项通过（`12-alarm-flow-full-green.log`）。

## 未执行 / 边界

- 报警列表、详情与人工处置属任务13；本票只交付 ACTIVE/RECOVERED 事件、规则快照与 outbox/WS 推送，`/monitor/alarms` 仍为占位页。
- 离线、GNSS、终端来源报警属任务14；无效定位当前只作为“不推进超速判定”的输入，不生成报警。
- 越界 fleetId 的 403 分支为静态检查项：规则管理权限仅 ADMIN，ADMIN 为全企业范围，现有角色下 HTTP 不可触发该分支。
- 前端未执行 Playwright e2e（本票为可选）；页面行为由 typecheck/lint/build 与后端规则 API 测试覆盖，浏览器全量验证留给综合验收票。
- 报警事件推送的撤权重连校正由任务17 深化；本票只验证基础推送与越界过滤。
- 论文映射与论文正文未在本票同步；如需写入论文，按项目映射另行确认。

## 合并与复验

（提交前合并 integration/tickets-08-27 后回填）
