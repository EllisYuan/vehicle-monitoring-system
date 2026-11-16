# 14: 离线、定位与终端来源报警

Status: blocked
Labels: ready-for-agent
Type: task
Blocked by: 12

## What to build

三类报警。离线：已启用、已鉴权且曾接入的终端 180 秒无有效通信成立，有效重连恢复；未接入、主动注销、停用、解绑排除；应用启动 180 秒宽限，宽限期内不生成离线报警以避免重启误报。离线判定以有效通信时间为依据，合法已鉴权补传报文的接收可更新 last_seen_at 并恢复通信在线，包内历史定位不推进当前位置、超速或围栏状态；注销、停用等管理操作不新造通信故障报警。定位无效：连续 3 个实时无效点成立，连续 3 个有效点恢复，无效点 lng/lat 为 NULL，不进入地图、超速与围栏计算；保存受限原始内容用于比对，不伪造 0/0 或复用旧坐标。GNSS 异常与终端来源报警：仅按已核对的报警位映射，保留 PLATFORM/TERMINAL 来源，未核对的位不编造。报警、判定 state 与 outbox 沿用 07 位置的同一事务，不另起提交路径。本票自带最小独立 CLI 场景与测试，不重做 12 的超速规则。

## Spec coverage

主：AC14 报警成立/恢复、AC20（US 58、59、60），见 [spec](../spec.md#可检查验收场景)。
复验：AC08 未接入终端不伪报警、无效位置 NULL 持久化（08 已验持久化，本票验报警语义）；AC15 事务部分。规则与事件结构来自 [12](12-overspeed-rules-and-alarm-lifecycle.md)。

## Acceptance criteria

- [x] 已启用、已鉴权、曾接入终端失联 180 秒生成离线 ACTIVE
- [x] 未接入、主动注销、停用、解绑不生成离线报警
- [x] 应用启动 180 秒宽限
- [x] 有效重连恢复离线报警
- [x] 连续 3 个无效点成立、3 个有效点恢复，坐标 NULL 有证据
- [ ] 无效点不进地图、超速成立/恢复与围栏（地图与超速部分通过；围栏属 15，尚未实现，未执行围栏断言）
- [ ] GNSS 与终端来源仅映射已核对位，来源保留，未支持不编（blocked：无可核对的报警位语义）
- [x] 报警与 state、outbox 同事务，ACK 时序正确
- [x] 最小 CLI 场景记录失联、恢复、注销与重启宽限

## Scope boundaries

不重做超速规则（12）、不做围栏（15）、不做处置界面（13）；未核对的协议位与修改单不得声称通过；不实现断线补传车辆车队归属（由 09 负责；若该分支尚未完成，本票不等待它，27 汇总报警侧复验）。

## Validation

以真实 TCP 与模拟终端（含独立 CLI 场景）制造接入、失联、重连、注销与重启，核对数据库 alarm/state 与公开输出；定位无效与 GNSS 用例记录报文与结果。记录命令、环境、版本、时段、期望与实际，逐条标注状态，未核对协议项明确标注阻塞或未执行。

## Comments

（暂无）

## Answer

Status: **blocked**。9 条 AC 中 7 条通过并验证、1 条部分通过（无效点地图与超速部分已验，围栏属任务15）、1 条阻塞；GNSS/终端来源报警位因仓库内无可核对的位语义映射（`PositionReport` 仅有原始 `alarmFlag`，`protocol` 与 ticket 05 fixture 均无映射表），该条保持阻塞，未实现、未编造。核心实现已完成并通过真实 TCP、Testcontainers 与独立 CLI 验证，已合入最新集成分支（合并提交 `bf25ecd`）；离线与定位无效功能可用，下游 22/23 可依赖。GNSS 条待映射来源确认后另票补齐。

实现：
- `OfflineAlarmService`（`server/src/main/java/com/vms/alarm/OfflineAlarmService.java`）：定时扫描以 `terminal.last_seen_at` 为唯一依据，阈值 180 秒；条件为 `terminal.status='ENABLED'`、`vehicle.status='ENABLED'`、`ever_authenticated=TRUE`、当前绑定存在、`last_seen_at <= now-180s`、`last_logout_at IS NULL OR last_logout_at < last_seen_at`，并以 `NOT EXISTS (... ACTIVE)` 防止重复建事件。`start_time=last_time=last_seen_at+180s`，`source='PLATFORM'`、`rule_id/rule_version/rule_name` 为 NULL。启动宽限在 `now < startedAt+180s` 内直接跳过扫描。
- 恢复：`authenticate`、`touch`（心跳/终端应答）与位置接收 `touchTerminal` 在同一事务调用 `recover`，ACTIVE → RECOVERED 并写 `alarm:recovered:<id>` outbox，`last_time` 不变；补传报文只更新 `last_seen_at` 并恢复通信，包内 HISTORY 点不推进 `vehicle_last_position`/`vehicle_monitor_state`/超速候选。
- 合法注销：`TerminalFrameHandler` 对 0x0003 调用 `TerminalAccessService.lawfulLogout`，写 `terminal.last_logout_at`；其余断连不写，扫描据此排除注销终端。未接入、停用、解绑车辆/终端均由扫描条件排除。
- 管理操作：停用终端、停用车辆、解绑/换绑只写 `alarm.scope_changed_at`（`markScopeChangedByTerminal/ByVehicle/ByVehicleTerminal`），既有 ACTIVE 不自动恢复、`recovered_at` 保持 NULL，也不新造通信故障报警。
- 定位无效：`PositionQualityParticipant` 只在实时无效点回调；`PositionInvalidAlarmParticipant` 同时实现 `PositionStateParticipant`（有效实时点）与 `PositionQualityParticipant`（无效实时点），在 07 位置事务内维护 `alarm_candidate_state`（方向 INVALID/VALID，`rule_id`/`rule_version` 为 NULL），连续 3 点成立、3 点恢复；无效点 `track_point.lng/lat` 为 NULL，保留 `raw_body`/`payload_hash`/`locate_state='INVALID'`，不进入 `applyRealtimeState`（地图、超速与围栏共用路径），不写位置 outbox/快照。
- 同事务与 ACK：报警、候选 state 与 outbox 均在 `PositionIngestService.accept/acceptBatch` 的 `@Transactional` 内，无独立提交；提交成功后 `TerminalFrameHandler` 才回 0x8001（ADR 0005）；写入失败整体回滚、不返回成功 ACK。
- 迁移 `V12__offline_and_terminal_alarms.sql`：新增 `terminal.last_logout_at`、`alarm.scope_changed_at`，扩展 `alarm.alarm_type` 允许 OFFLINE/POSITION_INVALID，候选表 `rule_id`/`rule_version` 可空并允许 INVALID/VALID，新增 `idx_alarm_terminal_type_state`。V12 本身未改动既有 V1–V8（V6 为 Java 迁移）、V11、V13、V14；合并时为解决执行顺序冲突修改了 V13 的约束（见上）。
- `application.yml`：`spring.task.scheduling.pool.size` 2→4；新增 `vms.alarm.offline-scan-interval-ms`（默认 10000）、`vms.alarm.offline-scan-initial-delay-ms`（默认 180000）。
- simulator 新增 `--logout true|false`（默认 true），用于制造“失联但不主动注销”。

验证命令与结果（完整记录见 `.scratch/vehicle-monitoring/evidence/14-verification.md`，日志 `evidence/14-*.log`）：
- TDD 切片一（定位无效）：`mvnw.cmd -B -pl server -am -Dtest=OfflineAndPositionAlarmFlowTest -Dsurefire.failIfNoSpecifiedTests=false test`；红 2 项 1F1E（`14-red-position-invalid.log`），中途 Integer/Long 断言修正（`14-green-position-invalid.log`），最终 2/2 通过（`14-green-position-invalid-rerun.log`）。
- TDD 切片二（离线/管理操作/保留边界）：同一命令扩至 8 项；红 1F4E（`14-red-offline.log`），中途解绑同秒触发 `EFFECTIVE_TIME_NOT_ADVANCED` 修正（`14-green-offline.log`），最终 8/8 通过（`14-green-offline-rerun.log`）。
- 回归（全局锁内串行）：`OverspeedAlarmFlowTest,PositionQualityFlowTest,TelemetryFlowTest,BatchBackfillFlowTest,TrackRetentionMaintenanceTest,SchemaUpgradeTest` 共 47/47 通过（`14-regression.log`）；`SchemaUpgradeTest` 按本分支 11 个迁移更新并实测空库与升级路径（`14-compile.log`、`14-build.log`：`-DskipTests package` BUILD SUCCESS）。
- 真实 CLI + 独立 MySQL/Redis：`verify-14.ps1` 输出 `ALL CHECKS PASSED`（`14-cli-offline.log`）：B 合法注销后回拨 400 秒零报警；A 无注销失联 180 秒后成立 `PLATFORM/OFFLINE/ACTIVE` 且 `start_time=last_seen_at+180s`、outbox `alarm:created`；A 重连 RECOVERED、`last_time` 不变、`alarm:recovered` 存在且无重复事件；重启 server #2 后 26 秒 C 仍零报警，190 秒后成立 `alarm:created:2`；`t14-mysql`/`t14-redis` 已删除。
- 提交前合并 `integration/tickets-08-27` @ `7412c1f`（合并提交 `11b80c5`，仅任务09证据/issue 文档更正，无代码差异），合并后在锁内重跑 `OfflineAndPositionAlarmFlowTest`+`SchemaUpgradeTest`（`14-postmerge.log`）。
- 提交后再次合并最新集成分支 @ `d8ddd88`（已含任务13，合并提交 `bf25ecd`）。唯一文本冲突为 `SchemaUpgradeTest` 的迁移数、history applied/versions 与自 V6 增量，已按合并后实测改为 13/13/13 与增量 7（30 张表清单与 37 分区沿用集成分支既有断言，实测通过）。
- V12/V13 约束顺序问题：V13（任务15）在 V12 之后执行，其 `DROP/ADD ck_alarm_type` 原仅允许 `OVERSPEED/GEOFENCE_ENTER/GEOFENCE_LEAVE`，会把 V12 新增的 `OFFLINE/POSITION_INVALID` 排除，合并后离线报警写入将失败。处理：在合并提交中把 V13 的约束改为并集 `('OVERSPEED','OFFLINE','POSITION_INVALID','GEOFENCE_ENTER','GEOFENCE_LEAVE')`，保持 13 个迁移；代价是 V13 的 Flyway checksum 变更，已应用旧 V13 的本地开发库需重建或 `flyway repair`（与协作约定中的开发库重建说明一致）。
- 合并后在同一把锁内复跑同一命令（`OfflineAndPositionAlarmFlowTest,SchemaUpgradeTest,OverspeedAlarmFlowTest,PositionQualityFlowTest,TelemetryFlowTest,BatchBackfillFlowTest,TrackRetentionMaintenanceTest,AlarmHandlingFlowTest`），8 类 61/61、BUILD SUCCESS（`14-postmerge2.log`）：AlarmHandling 6、Offline 8、Overspeed 10、SchemaUpgrade 2、BatchBackfill 7、PositionQuality 8、Telemetry 10、TrackRetention 10；日志确认空库应用 13 个迁移、`now at version v14`，离线与定位无效用例在含 V13 的实际 schema 上通过，证明约束并集生效。
- 提交后预合并复核（锁内，显式 `-f <worktree>\pom.xml`）7 类 55/55、BUILD SUCCESS（`14-premerge-rerun.log`）。

偏差与边界：
- GNSS/终端来源报警位标 blocked：`PositionReport` 只有原始 `alarmFlag`，`protocol` 与 ticket 05 fixture 无任何报警位语义样例；按设计“仅映射已核对位”保持零映射，未生成 TERMINAL 来源事件。
- 围栏属任务15，未实现；本票只保证无效点不进入实时状态计算路径，未执行围栏断言。
- 不重做任务12 超速规则；不实现任务13 处置界面/接口；未修改 `/monitor/alarms`；未改变任务09 `resolveOwnershipAt` 语义；未实现断线补传归属。
- 证据日志原写入 `evidence/logs/`（被 `.gitignore` 忽略），已原样复制到顶层 `evidence/14-*.log` 随提交保留；`14-server-*.log` 为 CLI 两次启动控制台输出，`.err.log` 均为空。
- 本票未改动 `web/`（相对集成分支的净差异无前端文件），合并后未执行前端 typecheck/lint；后端测试日志中 `ck_fail_outbox_13_9` 与 `CommunicationsException` 分别是任务13、任务07 测试的故意注入，不是产品缺陷。