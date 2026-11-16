# 任务18 验证记录：通知重试与缓存故障恢复

- 日期：2026-11-16
- 分支：`feat/ticket-18-outbox-retry-cache`；基线 HEAD `199e0f1`（ticket 13 合入后的集成分支 merge 提交，本票在既有 worktree 上接续实现）。
- 环境：Windows 11 Pro for Workstations 10.0.26200；JDK 21.0.9（Maven enforcer `[21,22)`）；Maven Wrapper 3.9.12；Spring Boot 4.1.2；Testcontainers `mysql:8.4`、`redis:8.4`、`testcontainers/ryuk:0.14.0`（Ryuk 启用）；业务时区 Asia/Shanghai；磁盘 C: 可用约 29 GB。
- 串行方式：所有 Maven 命令均通过 `C:\Users\YuanS\AppData\Local\Temp\vms-spec-notes\with-test-lock.ps1`（互斥量 `Local\vms-verification-lock`）串行执行，首参为本 worktree 的 `mvnw.cmd` 绝对路径；未并行运行 Maven。
- 证据存放：原始日志位于 `.scratch/vehicle-monitoring/evidence/logs/18-*.log`；按仓库归档约定，可提交副本另存为 `.scratch/vehicle-monitoring/evidence/18-*.log`。

## 1. TDD 红→绿记录

公共命令前缀（锁内）：`mvnw.cmd -B -pl server -am -Dtest=OutboxRetentionTest,OutboxRetryAndCacheRecoveryTest -Dsurefire.failIfNoSpecifiedTests=false test`

| 阶段 | 实际结果 | 说明 | 证据 |
| --- | --- | --- | --- |
| 无效尝试 | 未执行测试 | 后台命令工作目录落在主 worktree，构建产物不是本票工作区；日志改名保留，不计入红阶段 | `18-red-0-invalid-cwd.log` |
| 预跑（环境缺陷） | `Tests run: 12, Failures: 1, Errors: 5` | 用 `docker restart` 注入 Redis 故障：Testcontainers 随机映射端口在重启后重新分配（实测 46425→45205），Lettuce 无法重连并污染同上下文 Sa-Token 会话，5 个 ERROR 均为后续用例登录失败，不是实现缺失 | `18-red-1.log` |
| 真红 | `Tests run: 12, Failures: 4, Errors: 0`，BUILD FAILURE | 2 个失败为 `cleanupCompleted()` 未实现（`done-old`、`done-at-boundary` 未被删除）；2 个失败为 `POST /api/v1/outbox/{eventId}/redeliver` 不存在（期望 200/409，实际 404）。其余 8 个用例已由既有实现覆盖并通过 | `18-red-2.log` |
| 绿 | `Tests run: 12, Failures: 0, Errors: 0`，BUILD SUCCESS | `OutboxRetentionTest` 2/2、`OutboxRetryAndCacheRecoveryTest` 10/10 | `18-green-1.log` |

红阶段的 4 个失败均为功能缺失（清理未实现、重投端点不存在），无编译错误、无预填通过。实现切片：`PositionOutboxWorker.cleanupCompleted()` 的 7 天条件删除与 `@Scheduled`；`AuditService.CATEGORY_OUTBOX`；`Permission.OUTBOX_REDELIVER`（仅 ADMIN）；新增 `OutboxRedeliveryService` 与 `OutboxController`。

测试侧在真红前修正了两处测试设计问题，均不改变产品语义：其一，业务时钟冻结时首包与后续包需回溯 5 秒生成，否则第二条报文 `ageMillis` 为负、不进入实时窗口（仅测试数据构造）；其二，Redis 故障注入改为「`FLUSHDB` 清空派生数据 + `LettuceConnectionFactory.resetConnection()` 丢弃现有连接」，等价模拟重启后的空缓存与重连，原因见预跑记录与第 5 节。

## 2. 逐条 AC

### AC1 event_id 唯一，重复 enqueue 不重复业务事件 — 通过

- 命令：锁内 `-Dtest=OutboxRetryAndCacheRecoveryTest`。
- 期望：同一终端同秒相同内容重复上报只保留 1 条 `track_point`、1 条 `position:<vehicleId>` outbox；更高版本位置继续合并到同一 `event_id` 并推进 `version`。
- 实际：重复 datagram 两次 ACK 成功，`track_point=1`、outbox 1 条且 `version=1`；上报下一秒位置后 `track_point=2`、outbox 仍 1 条且 `version=2`、`status=PENDING`。
- 证据：`OutboxRetryAndCacheRecoveryTest.duplicatePositionDatagramDoesNotDuplicateOutboxEventAndNewerVersionMerges`（`18-green-1.log`）。

### AC2 位置通知每车合并且 worker 只清已读版本，不误清高版本 — 通过

- 命令：同上。
- 期望：worker 发送期间若同车事件被更新到更高版本，成功的条件更新 `WHERE event_id=? AND version=?` 不命中，事件保持 `PENDING`。
- 实际：伪造更高版本（version=2）后 `dispatch()`，outbox 仍为 `version=2`、`status=PENDING`、`attempts=0`。
- 证据：`workerOnlyClearsTheVersionItReadEvenWhenHigherVersionArrivesDuringPublish`（`18-green-1.log`）。

### AC3 报警与处置事件独立，不合并且不丢失 — 通过

- 命令：同上。
- 期望：`ALARM_EVENT` 按 `event_id` 逐条投递，不与位置事件合并；全部投递后均为 `DONE`。
- 实际：`alarm:created:77`、`alarm:handle:77:9` 各自投递一次，位置事件独立；3 条 outbox 全部 `DONE`，条数仍为 3。
- 证据：`alarmAndHandleEventsStayIndependentAndAreNotLostOrCoalesced`（`18-green-1.log`）；报警产生与处置写入的回归见 `OverspeedAlarmFlowTest`、`AlarmHandlingFlowTest`。

### AC4 最多 10 次后 DEAD，告警可见且可审计重投 — 通过

- 命令：同上。
- 期望：第 10 次失败转 `DEAD` 且不再自动重试；DEAD 在运行记录中可见；管理员重投后回到 `PENDING`、`attempts=0`，业务字段不变并写审计；非 DEAD 409 `OUTBOX_NOT_DEAD`、未知事件 404 `OUTBOX_EVENT_NOT_FOUND`、非管理员 403。
- 实际：`attempts=9` 起投递失败后 `status=DEAD`、`attempts=10`，推进时钟 5 分钟后再次 `dispatch()` 仍为 `DEAD`；`GET /api/v1/audit/operation-records` 返回 `outboxCounts.DEAD=1` 与 `deadOutbox[0]={eventId=position:4242, attempts=10}`；`POST /api/v1/outbox/position:4242/redeliver` 返回 200 且 `status=PENDING`、`attempts=0`，`event_type`/`aggregate_id`/`version` 保持 `VEHICLE_POSITION`/4242/5，随后 worker 投递成功转 `DONE`；`audit_log` 有 1 条 `OUTBOX/REDELIVER/outbox:position:4242`、`result=SUCCESS`，detail 含 `eventType=VEHICLE_POSITION, fromStatus=DEAD, toStatus=PENDING` 且不含敏感内容；非 DEAD 409、未知 404（并记 `FAILURE` 审计）、MONITOR 403 且事件保持 `DEAD`。
- 证据：`permanentFailureStopsAtTenAttemptsAndStaysDead`、`deadEventIsVisibleAndRedeliveryIsAuditedWithoutChangingBusinessFacts`、`redeliveryRejectsNonDeadUnknownEventsAndNonAdminActors`（`18-green-1.log`）。管理页面沿用既有审计页 `web/src/features/audit/views/AuditListView.vue` 的 `outboxCounts`/`deadOutbox` 展示，本票未改前端。

### AC5 DONE 7 天清理，DEAD 不自动删除 — 通过

- 命令：锁内 `-Dtest=OutboxRetentionTest,OutboxRetryAndCacheRecoveryTest`。
- 期望：`cleanupCompleted()` 只删除完成满 7 天的 `DONE`；恰好 7 天删除、不足 7 天保留；`DEAD` 与 `PENDING` 永不自动删除。
- 实际：8 天前 DONE 删除，6 天前 DONE、30 天前 DEAD、30 天前 PENDING 保留；边界用例中 `updated_at = now-7天` 删除、`now-7天+1分钟` 保留。
- 证据：`OutboxRetentionTest.doneOlderThanSevenDaysIsDeletedWhileRecentDoneDeadAndPendingAreKept`、`sevenDayBoundaryUsesActualCompletionTime`（`18-green-1.log`）。

### AC6 worker 崩溃恢复后不重复已提交业务事件 — 通过

- 命令：同上。
- 期望：事件残留 `PENDING` 时 worker 重投只清 outbox 标记，不新增/改写 `track_point` 与 `vehicle_last_position`。
- 实际：注入已提交轨迹、快照与 PENDING 事件，连续两次 `dispatch()` 后事件 `DONE`，`track_point` 仍 1 条、`vehicle_last_position.version=1` 且 `gps_time` 不变。
- 证据：`workerRecoveryDoesNotDuplicateAlreadyCommittedBusinessFacts`（`18-green-1.log`）。

### AC7 Redis 不可用或重启后快照可重建，旧缓存不伪造连接或在线 — 通过

- 命令：同上。
- 期望：派生缓存清空后由 `refreshCache()` 从 `vehicle_last_position` 重建；Redis 中只有 `vms:position:*` 派生键；伪造的旧缓存不能覆盖快照位置，也不能把终端显示为在线。
- 实际：清空 Redis 并丢弃现有连接后，`refreshCache()` 用 DB 事实重建 `vms:position:<id>`（含 `116.210000`、`version:1`），`keys("vms:*")` 全为 `vms:position:*`，无 online/connection 键；预先写入 `version=99` 的伪缓存后，`GET /api/v1/monitor/snapshot` 仍返回 DB 的 `version=1`、`lng=116.11`，`communicationStatus=OFFLINE`，伪缓存未被读取。
- 证据：`redisRestartRebuildsOnlyDerivedPositionCacheFromCommittedFacts`、`staleRedisCacheCannotFabricateConnectionOrOverrideCommittedPosition`（`18-green-1.log`）。

### AC8 故障注入下成功 ACK 不依赖缓存 — 通过

- 命令：同上。
- 期望：Redis 暂停期间位置仍提交并成功 ACK，ACK 耗时小于 2 秒；恢复后缓存可重建。
- 实际：暂停 Redis 容器后上报位置，0x8001 成功、耗时小于 2 秒，`vehicle_last_position.version=1`，outbox 事件为 `PENDING/RETRY/DONE`；解除暂停后 `refreshCache()` 重建出该车 `116.510000` 快照。
- 证据：`positionAckDoesNotDependOnRedisCacheAndRecoveryRebuildsIt`（`18-green-1.log`）。

## 3. 回归

命令（锁内）：`mvnw.cmd -B -pl server -am -Dtest=TelemetryFlowTest,PositionQualityFlowTest,SchemaUpgradeTest,OverspeedAlarmFlowTest,AlarmHandlingFlowTest,GeofenceJudgementFlowTest,AuditViewerTest -Dsurefire.failIfNoSpecifiedTests=false test`

实际：`Tests run: 51, Failures: 0, Errors: 0`，BUILD SUCCESS（`18-regression-1.log`）。

| 测试类 | 用例数 | 结果 |
| --- | --- | --- |
| `TelemetryFlowTest` | 10 | 通过 |
| `PositionQualityFlowTest` | 8 | 通过 |
| `SchemaUpgradeTest` | 2 | 通过 |
| `OverspeedAlarmFlowTest` | 10 | 通过 |
| `AlarmHandlingFlowTest` | 6 | 通过 |
| `GeofenceJudgementFlowTest` | 6 | 通过 |
| `AuditViewerTest` | 9 | 通过 |

## 4. 迁移与 SchemaUpgrade

本票未新增迁移：`cleanupCompleted()` 与重投端点在既有 `notification_outbox` 上完成，无 DDL 变更；保留号 V10 未占用。合并前 `SchemaUpgradeTest` 未修改并通过，实测维持 12 个迁移（V1–V5 SQL、V6 Java、V7、V8、V9、V11、V13、V14）、30 张表、37 个日分区，第二次增量迁移 6 个（`18-regression-1.log`）。合并任务14（新增 V12）后按集成分支迁移集合通过：13 个迁移、30 张表、37 个分区，`target=6` 执行 6 个、随后增量 7 个（`18-postmerge-1.log`）。

## 6. 合并集成分支后复验

- 合并：`git merge integration/tickets-08-27`，集成分支 tip 为 `6cf3998`（任务14 已合入），合并提交 `a7f9fae`，无冲突。
- 复跑命令（锁内）：`mvnw.cmd -B -pl server -am -Dtest=OutboxRetentionTest,OutboxRetryAndCacheRecoveryTest,SchemaUpgradeTest,TelemetryFlowTest,PositionQualityFlowTest,OverspeedAlarmFlowTest,AlarmHandlingFlowTest,GeofenceJudgementFlowTest,AuditViewerTest,OfflineAndPositionAlarmFlowTest -Dsurefire.failIfNoSpecifiedTests=false test`
- 实际：`Tests run: 71, Failures: 0, Errors: 0`，BUILD SUCCESS；本票 12 个用例全通过，合并引入的任务14 `OfflineAndPositionAlarmFlowTest` 8 个用例同批通过（`18-postmerge-1.log`）。
- 调度池：任务14 合并后 `spring.task.scheduling.pool.size` 为 4，本票清理任务与既有任务共用，语义不变。
- 最终提交状态：证据与注释修正提交后，锁内 `mvnw.cmd -B -pl server -am -DskipTests compile` 为 BUILD SUCCESS（`18-final-compile.log`）；该提交未改业务代码。

## 5. 未执行与说明

- 浏览器套件未执行：本票不改前端与页面行为，AC4 的 DEAD 列表沿用既有审计页展示；未产生 `18-playwright.log`。
- 跨实例/集群语义未执行：属 Scope boundaries 明确排除。
- Redis 故障注入以「`FLUSHDB` + `resetConnection()`」等价模拟：共享容器的随机映射端口在 `docker restart` 后会重新分配（实测 46425→45205），使应用连接配置失效并连带清空 Sa-Token 会话（预跑 `18-red-1.log` 中 5 个 ERROR 即此原因），不能作为应用层缓存故障的等价注入；重建数据仍来自真实 Testcontainers Redis 8.4 与 MySQL 8.4。Redis 容器真实暂停/解除的 ACK 时序另由 AC8 用例覆盖。
- 本轮 Testcontainers 的 MySQL、Redis 与 Ryuk 容器已由 Ryuk 回收；同时段其他 agent 的 `mysql:8.4`（`reverent_brahmagupta`，已运行约 3 小时）不属于本轮，未清理。