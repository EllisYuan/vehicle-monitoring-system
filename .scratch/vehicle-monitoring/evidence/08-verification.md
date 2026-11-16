# 任务08 验证记录：重复、冲突与定位质量处理

- 日期：2026-11-16
- 分支：feat/ticket-08-idempotency-quality
- 基线：07 已合入；本分支实现提交 `0ba1297 feat: 完成任务08重复与定位质量处理`，合并 `integration/tickets-08-27`（含任务16、17、21、26）的合并提交在其后。
- 结论：**Status: resolved**。8 条 AC 均有真实 TCP / 真实 MySQL / 浏览器证据；未使用 INSERT IGNORE；成功 ACK 仅在事务提交后发送。

## 环境

- Windows 11 Pro for Workstations 10.0.26200；JDK 21，Maven Wrapper（`mvnw.cmd`）。
- Testcontainers 2.0.5：MySQL 8.4（真实分区表与唯一键）、Redis 8.4、Ryuk 0.14.0（保持启用，结束自动回收）。
- 后端、浏览器与所有占用端口/容器的命令均通过全局互斥脚本 `C:\Users\YuanS\AppData\Local\Temp\vms-spec-notes\with-test-lock.ps1` 串行执行；前端 `pnpm typecheck/lint/build` 在锁外执行。
- 浏览器：Playwright 1.58.1 chromium；Vite dev server 由 BrowserFlowTest 拉起，WMTS 使用显式失败 fixture（非真实天地图）。
- 可控时钟：`MutableClock`（`@Primary` bean）推进 `received_at - gps_time` 时差窗口。

## 实现要点（与 AC 对应）

- 唯一键 `uk_track_terminal_time (terminal_id, gps_time)` 为幂等权威；只捕获 `DuplicateKeyException` 进入重传/冲突比对，其余 SQL 错误照旧抛出回滚；`server/src`、`simulator/src`、`web/src` 中无 `INSERT IGNORE`。
- `payload_hash` = 规范化 `PositionReport.encode()` 的 SHA-256，只含 0x0200 基本体字段；报文流水号、`received_at`、报文到达顺序不参与。异常行没有规范化报告时 `payload_hash` 取原始 body 的 SHA-256。
- 冲突：`SELECT payload_hash ... FOR UPDATE` 比对首条；一致则幂等成功（仅更新 `last_seen_at`），不一致写 `telemetry_anomaly.reason='CONFLICTING_CONTENT'`，不覆盖首条、不推进 `vehicle_last_position`/`notification_outbox`。
- 时差 `ageMillis = received_at - gps_time`：`ageMillis >= 0 && <= 60000` 且新于已处理水位才是 `REALTIME`；容差内未来点（`ageMillis >= -30000`）与过旧点只写 `HISTORY`；`ageMillis < -30000` 写 `FUTURE_BEYOND_TOLERANCE`。
- 定位无效（`fixValid=false`）写 `track_point` 且 `lng`/`lat` 为 NULL、`locate_state='INVALID'`，`raw_body`/`payload_hash` 保留用于比对；不进地图、快照、outbox。
- 异常原因：`INVALID_TIME`、`COORDINATE_OUT_OF_RANGE`、`INVALID_POSITION_FIELDS`、`UNASSIGNABLE`、`RETENTION_OUTSIDE`、`FUTURE_BEYOND_TOLERANCE`、`CONFLICTING_CONTENT`；异常先在同一事务插入，事务提交后 `TerminalFrameHandler` 才回 0x8001 成功应答。
- 迁移：`server/src/main/resources/db/migration/V7__telemetry_anomaly.sql`，保持 V7；表 `telemetry_anomaly` 含 `terminal_id`、`vehicle_id`、`fleet_id_at_event`、`gps_time`、`received_at`、`reason`、`payload_hash`、`body_length`、`raw_body VARBINARY(255)`，索引 `received_at`、`(vehicle_id, gps_time)`、`(terminal_id, gps_time)`。
- 设计同步：`docs/design/系统设计方案.md` 已补 `telemetry_anomaly` 结构与位置分流规则；异常数据当前经受限 DB 投影追溯，管理查询页面留给后续审计/统计票。

## 已执行

### 1. TDD 红→绿（`PositionQualityFlowTest`，真实 TCP + Testcontainers MySQL）

命令模板（均走锁）：

```
mvnw.cmd -B -pl server -am -Dtest=PositionQualityFlowTest -Dsurefire.failIfNoSpecifiedTests=false test
```

| 切片 | 红 | 绿 |
| --- | --- | --- |
| ACK 丢失后改流水号重传 | `08-red-idempotency.log`：Tests run 1，Errors 1，BUILD FAILURE | `08-green-idempotency.log`：Tests run 1，全通过，BUILD SUCCESS |
| 时差窗口与旧点/未来点分流 | `08-red-window-quality.log`：Tests run 4，Failures 2，Errors 1，BUILD FAILURE | `08-green-window-quality.log`：Tests run 4，全通过，BUILD SUCCESS |
| 无效定位、冲突、异常分支 | `08-red-quality-branches.log`：Tests run 8，Failures 3，Errors 5，BUILD FAILURE | `08-green-quality-branches.log`：Tests run 8，全通过，BUILD SUCCESS |

- 红阶段 8 个用例的失败/错误均来自缺失实现（唯一键未处理、无异常表、无窗口分流等），未预填通过；绿阶段未再改实现逻辑，仅修正测试侧 `LocalDateTime` 取值方式（`queryForObject(..., LocalDateTime.class, ...)`）。
- 结果：通过。

### 2. 合并后受影响的编译与测试（走锁，含 `clean`）

```
mvnw.cmd -B -pl server -am clean -Dtest=PositionQualityFlowTest,TelemetryFlowTest,SchemaUpgradeTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- `08-postmerge-backend.log`：`SchemaUpgradeTest` 1 通过（26.47 s）、`PositionQualityFlowTest` 8 通过（34.14 s）、`TelemetryFlowTest` 10 通过（49.42 s）；合计 `Tests run: 19, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS。
- `TelemetryFlowTest` 的锁等待、outbox 失败回滚、数据库会话被杀等用例复验了“仅提交后成功 ACK”的语义未被本票破坏。
- `SchemaUpgradeTest` 已按本分支实际迁移集合同步：V1–V6 + V7 + V14 共 **8** 个迁移；断言 `migrationsExecuted == 8`、`flyway_schema_history` applied = **8**、distinct versions = **8**；表清单 18 项按 `table_name` 排序，含 `telemetry_anomaly`（位于 `sys_user_fleet` 与 `terminal` 之间）。
- 结果：通过。

### 3. 浏览器与 WS 表现（走锁）

先构建 simulator（真实 TCP CLI，含 `--fix-valid`）：

```
mvnw.cmd -B -pl simulator -am -DskipTests package
```

- `08-simulator-build.log`：BUILD SUCCESS。

再运行 BrowserFlowTest（拉起真实后端、真实 TCP、Vite 与 Playwright 全量 e2e）：

```
mvnw.cmd -B -pl server -am -Dtest=BrowserFlowTest -Dvms.browser-tests=true -Dsurefire.failIfNoSpecifiedTests=false test
```

- `08-browser.log`：BrowserFlowTest 1 通过，BUILD SUCCESS。
- `08-playwright.log`：Playwright **35 passed**，含 `monitoring.spec.ts:86 定位无效点不进入地图且列表仍显示暂无有效定位`（通过；真实 TCP 发送 `--fix-valid false` 的两帧，断言车辆列表仍显示“暂无有效定位”、地图 `data-feature-count` 与发送前一致）以及任务26 的底图 fixture 用例（不冲突）。
- 截图：`08-invalid-fix-not-on-map.png`（`web/test-results/08-invalid-fix-not-on-map.png` 复制）。
- 结果：通过（fixture 底图，非真实天地图；真实底图属任务26 未执行项）。

### 4. 前端静态检查与构建（锁外）

- `pnpm --dir web typecheck`：exit 0（`08-typecheck.log`）。
- `pnpm --dir web lint`：exit 0（`08-lint.log`）。
- `pnpm --dir web build`：exit 0（`08-build.log`）。

### 5. 静态检查

- `grep -rn "INSERT IGNORE" server/src simulator/src web/src`：无匹配。
- `TerminalFrameHandler.position()` 仅调用一次 `positions.accept(session, frame.body(), receivedAt)`，随后发送 0x8001；`accept` 经 Spring 事务代理返回即已提交；Redis/WS 由 outbox worker 异步执行，不阻塞 ACK。
- 冲突分支在 `applyRealtimeState` 之前返回，不改变快照、不推进水位、不写 outbox。

### 6. 测试资源回收

- 本轮 BrowserFlowTest/Testcontainers 结束后 `docker ps -a` 无 `testcontainers-*` 或 Ryuk 残留，无 8080/9000/5173 残留进程。
- 未清理其他项目的既有容器（`plp-*`、`pms_*`、`vms-mysql84`、`vms-redis` 等保持原状）。
- 结果：通过。

## 逐项 AC 状态

| Acceptance criterion | 状态 | 依据 |
| --- | --- | --- |
| ACK 丢失后重传同定位体但改流水号，仅一条正常位置且返回成功 | 通过 | `retransmissionWithChangedSerialCreatesOnePositionAndReturnsSuccess`：serial 3/4 两次均 result=0；`track_point` 1 条、`vehicle_last_position.version=1`、`notification_outbox.version=1`、无异常行 |
| 同秒不同内容保留首条，冲突写 telemetry_anomaly 且可查 | 通过 | `concurrentSameSecondDifferentContentKeepsFirstAndRecordsSingleConflict`：首帧之后、首帧 ACK 前发送第二帧；两帧均成功应答；`track_point` 仅 1 条且 `lng=116.50`；异常表 1 条 `CONFLICTING_CONTENT`，`gps_time` 与冲突秒一致、`body_length=28`。可查性经 DB 投影验证，管理页不在本票范围 |
| 冲突点不改变快照，也不进入后续实时判定输入 | 通过 | 同上：`vehicle_last_position.version=1`、`lng=116.50`、`notification_outbox.version=1`；冲突分支在 `applyRealtimeState` 前返回 |
| 时差窗口外的未来/过旧点按规则分流：仅历史或异常 | 通过 | `staleAndOldPointsOnlyBackfillHistoryAndNeverRollBackSnapshot`：`now-60s` REALTIME；`now-61s` HISTORY 且快照不变；`now-10s` HISTORY 且不回退；`futureTolerancePointStaysHistoryAndDoesNotFreezeRealtime`：`now+20s` 在容差内写 HISTORY，`now+40s` 超容差写 `FUTURE_BEYOND_TOLERANCE` 且无 `track_point` |
| 容差内未来点不冻结正常流，时间追上也不追认 | 通过 | 同上：`now+20s` 后快照计数为 0；时钟推进 25 秒后仍为 0；随后正常点使 version=1 且 `lng=116.41`，证明未来点未占用水位、未被追认 |
| 定位无效点 lng/lat 为 NULL 且页面地图不显示 | 通过 | `invalidFixIsStoredWithNullCoordinatesAndAbsentFromMonitor`：`lng`/`lat` 为 NULL、`locate_state='INVALID'`、`raw_body`/`payload_hash` 保留、快照无 position、无 outbox；后续 HISTORY 点不推进水位，时钟推进后正常点恢复实时。浏览器 `monitoring.spec.ts:86` 通过并截图 |
| 非法时间、越界坐标、无法归属记录异常原因 | 通过 | `invalidTimeAndOutOfRangeCoordinatesAreRecordedAsAnomaliesWithSuccessAck`：BCD 非法时间 → `INVALID_TIME`（`gps_time IS NULL`），纬度 91 → `COORDINATE_OUT_OF_RANGE`，两者均成功 ACK 且无 `track_point`；`pointForDisabledVehicleIsRecordedAsUnassignableAnomalyWithoutGuessing`：停用车辆 → `UNASSIGNABLE`，不猜测车辆、不静默丢弃 |
| 保留期外不重建分区，仅记受限异常摘要 | 通过 | `pointOutsideRetentionIsRecordedAsRestrictedAnomalyWithoutRebuildingPartitions`：30 天前点 → `RETENTION_OUTSIDE`；`information_schema.partitions` 数量前后一致；无 `track_point`；异常行 `payload_hash` 非空、`body_length=28`、`raw_body` 28 字节 |

## 未执行 / 边界

- 0704 批量幂等与补传归属：属任务09，本票未验证。
- 定位异常报警的连续 3 点成立/恢复：属任务14；本票只保证异常输入与存储，未实现报警。
- 异常数据管理查询页面：本票未实现，当前仅经受限 DB 投影追溯；留给后续审计/统计票。
- 真实天地图底图：本票浏览器验证使用显式失败 fixture，真实 key/网络属任务26 未执行项。
- 本次 `concurrentSameSecondDifferentContent` 的“并发”为同一连接内首帧 ACK 前连续发送两帧，服务端按终端行锁串行处理；未引入多线程 TCP 写入。

## 合并与复验

- 合并 `integration/tickets-08-27`（`afc91be`）到本分支，冲突文件：`server/src/test/java/com/vms/identity/SchemaUpgradeTest.java`、`web/src/features/monitoring/components/VehicleMap.vue`；均已按双方语义合并（任务08 的异常表与任务26 的 `resolveBasemap`/attribution/TileMatrix 1..18 并存）。
- `web/e2e/monitoring.spec.ts` 自动合并后语义冲突（任务26 已重构辅助函数），已按任务26 的 `signIn`/`createVehicle` 重写无效定位用例并移除旧残留块。
- 合并后复验：后端 19 项通过、Playwright 35 项通过、前端 typecheck/lint/build 均 exit 0。
