# 任务09 验证记录：断线补传与历史归属

- 日期：2026-11-16
- 分支：feat/ticket-09-batch-backfill
- 基线：`integration/tickets-08-27` HEAD `b221243`（含任务08 幂等/质量与任务11 保留/分区）；提交前合并至 `f3d2645`（含任务10 车辆轨迹查询与回放），无冲突，合并后复跑见第 6 节。
- 结论：**Status: resolved**。7 条 AC 均有真实 TCP / 真实 MySQL / MockMvc 三角色 / Playwright 证据；本票未新增迁移。

## 环境

- Windows 11 Pro for Workstations 10.0.26200；JDK 21（`JAVA_HOME=C:\Java\jdk-21.0.9`；PATH 上的 `java` 为 17，运行 jar 与 simulator 必须使用 `JAVA_HOME` 下的 21）。
- Maven Wrapper `mvnw.cmd`；Testcontainers 2.0.5：MySQL 8.4、Redis 8.4、Ryuk 0.14.0（保持启用，结束自动回收）。
- 所有 Maven / 真实 server / 浏览器命令均通过全局互斥脚本 `C:\Users\YuanS\AppData\Local\Temp\vms-spec-notes\with-test-lock.ps1` 串行执行；前端 `pnpm typecheck/lint/build` 在锁外执行。
- 真实 CLI 验证使用独立容器 `t09-mysql`（13310）/`t09-redis`（16382）、打包后的 `server/target/server-0.1.0-SNAPSHOT.jar`（HTTP 18092、TCP 19010），不触碰其他票容器；脚本结束删除本脚本容器。
- 浏览器：Playwright 1.58.1 chromium，Vite dev server 由 BrowserFlowTest 拉起；WMTS 使用显式失败 fixture（非真实天地图）。

## 实现要点（与 AC 对应）

- `PositionBatch.parse(body)` 在进入数据库前完成整包结构校验：count 为 WORD、dataType、逐项长度 WORD；项数 ≤20、body ≤1023、声明项数与实际项数一致、每项可完整解析。结构失败整包拒绝。
- `PositionIngestService.acceptBatch`：`@Transactional`；先整包解析，再锁定终端并逐项 `ingest`；任一项抛错则整批回滚，异常传播到 `TerminalFrameHandler.exceptionCaught` 关闭连接，不发送 0x8001。
- `PositionIngestService.resolveOwnershipAt(terminalId, gpsTime)`：按 `gps_time` 在 `terminal_vehicle_binding` 与 `vehicle_fleet_history` 的 `[effective_from, effective_to)` 区间内唯一匹配，且车辆状态为 `ENABLED`；不唯一返回 null，由 `ingest` 写 `telemetry_anomaly.reason='UNASSIGNABLE'`，不按接收时当前关系推测。
- `track_point` 写入 `vehicle_id` 与 `fleet_id_at_event`；补传点按事件时刻归属旧车辆旧车队。
- 实时与历史分流保持任务08语义：`ageMillis=received_at-gps_time` 在 `0..60000` 且新于 `vehicle_last_position.gps_time` 才 `REALTIME`；只有 `REALTIME` 且 `fixValid` 才推进 `vehicle_last_position`、`vehicle_monitor_state` 与 `notification_outbox`，补传 `HISTORY` 不回退快照。
- 幂等仍以 `uk_track_terminal_time (terminal_id, gps_time)` 为权威；重复且内容一致不新增，同秒不同内容写 `CONFLICTING_CONTENT`；只捕获 `DuplicateKeyException`，其余 SQL 错误回滚整批。
- `TerminalFrameHandler`：0x0704 走 `batchPosition`，仅在 `acceptBatch` 正常返回（事务已提交）后回 0x8001。
- simulator：新增 `--batch single|mixed`、`--batch-size`、`--backfill`、`--backfill-seconds`、`--backfill-interval-ms`、`--reconnect true|false`；按批次大小选择 0x0200/0x0704；重连时重新注册取 authCode。
- 迁移：本票无新增迁移；`SchemaUpgradeTest` 未改动，仍断言 9 个迁移、23 张表、37 个分区。

## 已执行

### 1. TDD 切片（`BatchBackfillFlowTest`，真实 TCP + Testcontainers MySQL）

命令模板（走锁）：

```
mvnw.cmd -B -pl server -am -Dtest=BatchBackfillFlowTest -Dsurefire.failIfNoSpecifiedTests=false test
```

7 个用例沿红→绿逐个实现，最终结果见 `09-regression.log`：`Tests run: 7, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS。覆盖：

1. 合法 0x0704：ACK responseId=0x0704/result=0，2 行 HISTORY，`fleet_id_at_event` 为绑定车队，无快照。
2. 混合新点/重复/冲突：3 项中 1 行落库、1 条 `CONFLICTING_CONTENT`，重复不新增、无 outbox。
3. 结构失败（count=21、声明数与实际不符）：连接关闭，三张位置相关表均 0 行。
4. 中途数据库失败（对 `track_point` 注入 CHECK 使第二项失败）：整批回滚、无成功 ACK、无残留行。
5. 换绑后补传：换绑前 60s 点归旧车旧队 HISTORY；换绑后实时点归新车新队 REALTIME；早于所有区间的点写 `UNASSIGNABLE` 且 `vehicle_id`/`fleet_id_at_event` 为 NULL。
6. 转队：`fleet_id_at_event` 按事件时刻保留旧/新；MONITOR 读两段历史，新车队 FLEET_LEADER 只读转入后一段，旧车队 FLEET_LEADER 对 `/fleet-history` 与车辆详情均 403；快照按当前车队授权。
7. 补传不回退：0x0200 实时点后 0x0704 补两个旧点，`vehicle_last_position.version=1` 且仍为实时点，`vehicle_monitor_state.version=1`，outbox 仅 1 行，`report_type` 依次 `HISTORY,HISTORY,REALTIME`。

### 2. 真实 CLI + 真实独立 MySQL + 三角色 HTTP（走锁）

脚本：`C:\Users\YuanS\AppData\Local\Temp\vms-ticket09\verify-09.ps1`。命令：

```
with-test-lock.ps1 -Command 'pwsh','-NoProfile','-ExecutionPolicy','Bypass','-File','C:\Users\YuanS\AppData\Local\Temp\vms-ticket09\verify-09.ps1'
```

- 输出：`09-cli-http-sql.log`；server 控制台：`09-server-console.log` / `09-server-console.err.log`（err 为空）；结尾 `ALL CHECKS PASSED`。
- 场景：建旧/新两个车队、旧/新车辆与终端，绑定旧车；SQL 将当前绑定与旧车车队区间回溯 1 小时；CLI 实时点（121.48/42.5）；平台解绑并改绑新车、旧车转队；CLI `--reconnect true --backfill 2 --backfill-seconds 120 --batch-size 2` 补传；CLI `--batch mixed` 发新点/重复/同秒冲突；CLI 补传 7200s 前不可归属点。
- SQL 断言：`track_point` 恰 4 行——2 行旧车/旧车队 HISTORY（121.48、12.0），1 行旧车实时点（42.5），1 行新车/新车队 REALTIME（121.49、30.0）；`telemetry_anomaly` 为 `CONFLICTING_CONTENT` 1 行（新车/新车队）与 `UNASSIGNABLE` 2 行（NULL/NULL）；快照旧车 121.48/42.5/version=1、新车 121.49/30.0/version=1；outbox 两车各 1 行 DONE；绑定与车队历史各保留两段区间。
- HTTP 三角色：MONITOR 读到两段 `fleet-history`；新车队 FLEET_LEADER 只读到转入后一段；旧车队 FLEET_LEADER 的 `fleet-history` 与车辆详情均 403；新车队快照可见旧车最新实时位置 121.48，旧车队快照不含该车。
- 结果：通过。

### 3. 浏览器（走锁）

```
mvnw.cmd -B -pl server,simulator -am -Dvms.browser-tests=true -Dtest=BrowserFlowTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- `09-browser.log`：BrowserFlowTest 1 通过，BUILD SUCCESS。
- `09-playwright.log`：Playwright **37 passed**，含新增 `monitoring.spec.ts:157 断线重连补传旧时刻位置后列表与地图不回退`（真实 CLI 先发实时点 42.5 km/h，再 `--reconnect true --backfill 2 --backfill-seconds 120 --batch-size 2 --speed 12` 补传；列表仍显示 42.5 km/h，地图 `data-feature-count` 不变）。
- 截图：`web/test-results/09-backfill-no-rollback.png`（已复制为 `09-backfill-no-rollback.png`）。
- 结果：通过（底图为 fixture，非真实天地图）。

### 4. 服务端回归（走锁）

```
mvnw.cmd -B -pl server -am -Dtest=PositionQualityFlowTest,TelemetryFlowTest,SchemaUpgradeTest,BatchBackfillFlowTest -Dsurefire.failIfNoSpecifiedTests=false test
```

`09-regression.log`：`Tests run: 27, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS。分项：`SchemaUpgradeTest` 2、`BatchBackfillFlowTest` 7、`PositionQualityFlowTest` 8、`TelemetryFlowTest` 10。

- `TelemetryFlowTest` 的 `boundTerminal` 在绑定后把当前绑定与车队区间起点回溯 1 小时：09 起按 `gps_time` 归属历史区间，测试中的绑定须先于待上报点存在；不改变生产语义。
- `PositionQualityFlowTest` 在“旧点只补历史”用例中先推进 `MutableClock` 120 秒，保持原有意图（该类时钟起点为真实时间截断到秒）。

### 5. 前端静态检查与构建（锁外）

- `pnpm --dir web typecheck`：exit 0。
- `pnpm --dir web lint`：exit 0，39 warnings（均为 `TrackRetentionView.vue` 既有格式告警，0 errors）。
- `pnpm --dir web build`：exit 0，312 modules transformed。
- 证据：`09-web-checks.log`。
- 合并任务10 前端代码后复跑：`typecheck` exit 0；`lint` exit 0，100 warnings（含任务10 新增 `TrackMap.vue`/`TrackPlaybackView.vue` 的格式告警，0 errors）；`build` exit 0（313 modules transformed）。证据：`09-postmerge-web-checks.log`。

### 6. 合并后回归（走锁）

提交前 `git merge integration/tickets-08-27`：集成分支已前进到 `f3d2645`（含任务10 单车轨迹查询与回放），无冲突。

```
mvnw.cmd -B -pl server -am -Dtest=PositionQualityFlowTest,TelemetryFlowTest,SchemaUpgradeTest,BatchBackfillFlowTest,TrackQueryFlowTest,TrackRetentionMaintenanceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- `09-postmerge-backend.log`：`Tests run: 45, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS。
- 合并后首跑发现 `TrackRetentionMaintenanceTest` 空指针：09 曾把保留期外拒绝摘要的 `vehicle_id` 置 NULL，而任务11 的运营摘要要求记录接收时绑定。修复为 retention 分支用 `currentOwnership(terminalId)` 解析接收时当前绑定（仅用于 `track_retention_rejection` 与 `RETENTION_OUTSIDE` 异常摘要，不写入 `track_point`，不参与历史归属与读取授权）。
- 合并后浏览器：`mvnw.cmd -B -pl server,simulator -am -Dvms.browser-tests=true -Dtest=BrowserFlowTest -Dsurefire.failIfNoSpecifiedTests=false test`，BrowserFlowTest 1/1 通过，BUILD SUCCESS（`09-postmerge-browser.log`）。
## AC 状态

| # | Acceptance criterion | 状态 | 证据 |
| --- | --- | --- | --- |
| 1 | 0x0704 先整包验证结构/项数/长度，再单事务处理 | 通过 | `BatchBackfillFlowTest` 结构失败用例（count=21、声明数不符）；`acceptBatch` 先 `PositionBatch.parse` 再逐项落库 |
| 2 | 混合新点、重复、冲突的成功包全部可解释，重复不新增 | 通过 | `BatchBackfillFlowTest` 混合包用例；真实 CLI `--batch mixed` + SQL（1 行 + 1 条 `CONFLICTING_CONTENT`） |
| 3 | 结构或中途数据库失败不部分提交、不回成功 | 通过 | `BatchBackfillFlowTest` 结构失败与 CHECK 注入中途失败用例（连接关闭、0 行、无 ACK） |
| 4 | CLI 断线重连后补传旧时刻位置，归属旧车辆与旧车队 | 通过 | 真实 CLI `--reconnect true --backfill 2 --backfill-seconds 120`；SQL 两行旧车/旧队 HISTORY |
| 5 | 换绑后新队不可读转入前历史；旧队不因补传获得新位置权限 | 通过 | 真实 HTTP 三角色（monitor 2 段、新车队 1 段、旧车队 403；旧车队快照无该车）+ `BatchBackfillFlowTest` 用例 6 |
| 6 | 无法唯一归属写异常，不按当前关系推测 | 通过 | 真实 CLI 补传 7200s 前点；SQL `UNASSIGNABLE` 2 行、`vehicle_id`/`fleet_id_at_event` 为 NULL |
| 7 | 补传不回退最新位置与实时状态 | 通过 | SQL 快照 version=1 且仍为实时点；`BatchBackfillFlowTest` 用例 7；Playwright 列表 42.5 km/h 与地图要素数不变 |

## 边界与后续复验

- 轨迹查询 API 与回放属任务10；司机快照与报警关联属 12/13；报警产生与处置属 14；真实天地图底图与 key 属任务26。本票均未实现、未冒充。
- 本票不新增迁移，未占用其他票预留编号；`SchemaUpgradeTest` 未改动。
- 本票不替代任务08 的单点幂等/冲突测试与任务06 的注册鉴权测试；相关回归已在第 4 节执行。
- 真实 CLI 验证覆盖混合包、断线重连补传、换绑/转队与无法归属；21 项结构拒绝与中途数据库失败通过真实 TCP 测试基座注入验证（CLI 本身按选项构造合法包）。
