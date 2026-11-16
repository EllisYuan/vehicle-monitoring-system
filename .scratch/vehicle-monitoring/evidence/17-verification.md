# 任务17 验证记录：实时订阅撤权与重连校正

- 日期：2026-11-16（Asia/Shanghai）
- 分支：`feat/ticket-17-ws-resync`
- 合并基线：`integration/tickets-08-27` @ `6987d1b`（已合入任务 16 文本/跟踪指令、任务 26 天地图底图）
- 结论：8 项 AC 全部通过；无阻塞项。报警列表补齐按票面约定转 13/27 后续复验。

## 环境

- Windows 11 Pro for Workstations 10.0.26200。
- JDK 21.0.9（Oracle）、Maven Wrapper 3.9.12、Spring Boot 4.1.2、sa-token 1.46.0。
- Testcontainers：MySQL 8.4、Redis 8.4、Ryuk 0.14.0（保持启用）。
- Node v24.11.1、pnpm 10.16.1、@playwright/test 1.58.1、Playwright Chromium。
- Maven 与 Playwright/dev server 验证均通过 `C:\Users\YuanS\AppData\Local\Temp\vms-spec-notes\with-test-lock.ps1`（`Local\vms-verification-lock`）串行执行；typecheck/lint/build 在锁外执行。
- 本记录与日志不含任何真实 ticket/token/key 值。

## 实现改动

- 后端 `MonitorWebSocketHandler`：握手鉴权通过后才进入订阅列表；每秒 sweeper 重新 `auth.requireToken` + `requirePermission(FLEET_VIEW)`，撤销以 close code 1008、reason `SUBSCRIPTION_REVOKED` 关闭；慢客户端以 1011 `SESSION_NOT_RELIABLE` 关闭；每订阅有界 `ArrayBlockingQueue`（默认 512，测试配 8）+ 虚拟线程串行 drain，不无限堆积；`publish` 每帧重新鉴权并按当前车队范围过滤；审计 `WS_SUBSCRIBE` / `WS_SUBSCRIPTION_REVOKED`，detail 仅 `sessionId=...;reason=...`。
- 前端 `web/src/features/monitoring/useMonitorStream.ts`：新增 `disconnected` / `stopped` 状态与状态标签；1008 时停止自动重连、清空车辆列表、置“实时订阅已失效”；新增 `revoked` 守卫并 abort 在途快照，撤权后迟到快照不得回填；重连成功再次查询快照；`position.version` 旧帧丢弃。
- `HomeView.vue`：新增“数据陈旧”提示（`data-testid="monitor-data-stale"`）。
- 新增测试：`MonitorWebSocketSubscriptionTest`（6 项）、`MonitorWebSocketExpiryTest`（1 项）、`web/e2e/monitoring-stream.spec.ts`（3 项）、`web/playwright.mock.config.ts`。

## TDD 红→绿（前端）

1. 红：`17-web-stream-red.log`。新增用例“撤权时仍在途的快照返回后不能恢复旧数据”在未修复时失败（撤权后 2200ms 仍找不到“实时订阅已失效”，旧快照把已清空状态写回）。同轮第一条用例另因测试替身把 Vite HMR socket 计入 `openDelays` 索引而失败，属测试代码时序问题，已改为只统计 `/ws/monitor`，不是产品缺陷。
2. 绿：修复 `useMonitorStream` 后 `17-web-stream.log`，3 passed（23.1s）。

## 已执行验证

### 1. 后端订阅撤权、到期与日志（合并前）

命令（锁内）：

```
mvnw.cmd -B -pl server -am -Dtest=MonitorWebSocketSubscriptionTest,MonitorWebSocketExpiryTest -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：`Tests run: 7, Failures: 0, Errors: 0, Skipped: 0`；`BUILD SUCCESS`。证据：`17-subscription-tests.log`（合并前副本 `17-subscription-tests-previous.log`）。

关键日志：

```
WS 订阅撤销：... reason=SESSION_REVOKED      （车队撤权、改密）
WS 订阅撤销：... reason=SESSION_EXPIRED      （新登录替换、绝对到期）
WS 订阅撤销：... reason=ACCOUNT_DISABLED     （停用）
WS 慢客户端缓冲超限，断开订阅：... capacity=8
```

覆盖：停用、改密、新登录替换、车队撤权、绝对到期均断言 close code 1008 且距触发 <5000ms；撤权前收到授权车队位置帧、跨队车辆无帧；订阅/撤销审计存在且 detail 不含 ticket/token；ROOT 日志不含 ticket/token；慢客户端（真实 TCP、不读取帧、300 辆车写入 outbox）在缓冲超限后断开。

### 2. 前端 mock e2e（合并前）

命令（`web` 目录、锁内）：

```
.\node_modules\.bin\playwright.cmd test -c playwright.mock.config.ts --reporter=list
```

结果：3 passed（`17-web-stream.log`）。覆盖：旧 version 1 帧不覆盖已显示 version 2；1011 断开显示“实时连接已断开”，断线期间受控变化只经快照，重连显示“正在重连”后“实时连接已建立”，车辆行更新为 66.0 km/h，快照数与 ticket 数增长；1008 后“实时连接已停止”、提示失效、列表清空且 2.5s 内无新 socket/ticket；撤权时在途快照返回后仍保持停止与空列表。

### 3. 合并集成分支

`git merge integration/tickets-08-27`：fast-forward 至 `6987d1b`，无冲突。工作区实现保留；ticket 26 基线保留（`web/src/features/monitoring/basemap.ts`、`data-testid="monitor-map-attribution"`、TileMatrix 1..18、`web/e2e/fixtures/tianditu-wmts-metadata.json` 与 `BrowserFlowTest` 的 `VMS_E2E_*` 环境变量用法）。

### 4. 合并后后端复跑

命令（锁内）：

```
mvnw.cmd -B -pl server -am -Dtest=MonitorWebSocketSubscriptionTest,MonitorWebSocketExpiryTest,TelemetryFlowTest -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：`Tests run: 17, Failures: 0, Errors: 0, Skipped: 0`（Subscription 6、Expiry 1、Telemetry 10）；`BUILD SUCCESS`。证据：`17-postmerge-backend.log`。TelemetryFlowTest 覆盖一次性 ticket（缺失/重复使用/登录替换后旧 ticket 均握手拒绝）与位置合并（连续 3 点只推送最终 version 3、lng 116.45）。

### 5. 合并后前端 mock e2e

命令同第 2 节（`web` 目录、锁内）。结果：3 passed（16.2s），`17-postmerge-web-stream.log`。合并后的 `VehicleMap`/basemap 变更未破坏监控页与订阅状态机。

### 6. 静态检查（合并后）

`pnpm --dir web typecheck` exit 0（`17-typecheck.log`）；`pnpm --dir web lint` exit 0（`17-lint.log`）；`pnpm --dir web build` exit 0（`17-build.log`）。

### 7. 合并后真实浏览器 BrowserFlowTest

命令（锁内；先预编译 simulator 供 Playwright 启动 CLI）：

```
mvnw.cmd -B -pl simulator -am -DskipTests package
mvnw.cmd -B -pl server,simulator -am -Dvms.browser-tests=true -Dtest=BrowserFlowTest -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`；`BUILD SUCCESS`；内层 Playwright `31 passed (2.1m)`。证据：`17-simulator-build.log`、`17-postmerge-browser.log`、`17-postmerge-playwright.log`。

- 真实后端 + 真实 WS + 真实 Chromium：`monitoring.spec.ts` 全部通过，其中 `monitoring.spec.ts:43` “TCP 位置实时进入地图与车辆列表，底图失败仍能查看并过滤”通过，页面断言“实时连接已建立”。
- 本票新增的 3 条 `monitoring-stream.spec.ts` 在真实套件中同样通过（第 26–28 项）。
- 首次执行失败：该 worktree 尚未生成 `simulator/target/classes`，reactor 中 server 测试先于 simulator 编译，2 条既有用例无法启动 `com.vms.simulator.TerminalSimulator`（`ClassNotFoundException`；`commands.spec.ts` 终端未进入 ONLINE），Playwright exit 1。先单独预编译 simulator 后复跑通过，属执行顺序/环境准备问题，不是代码回归。失败记录保留于 `17-postmerge-browser-attempt1.log`、`17-postmerge-playwright-attempt1.log`、`17-postmerge-browser-attempt1-surefire.txt`。

### 8. 容器回收

复跑结束即执行 `docker ps`：无运行中的 MySQL/Redis/Ryuk Testcontainers 容器，本轮容器已随 JVM 退出由 Ryuk 0.14.0 回收。Ryuk 全程启用；未执行 `docker rm`，未清理其他 ticket/项目的容器。

## AC 逐条

| AC | 状态 | 证据与说明 |
| --- | --- | --- |
| WS 握手使用一次性 ticket，ticket/token 不出现在日志 | 通过 | 07 `TelemetryFlowTest.wsTicketsAreRequiredSingleUseAndBoundToTheIssuingSession`（缺失/复用/登录替换后旧 ticket 握手拒绝）；17 `revocationAuditAndLogsNeverContainTicketOrToken` 断言 ROOT 日志与 `audit_log.detail` 不含 ticket/token；`MonitorTicketService` 30s、一次性、容量 4096，`consume` 原子移除并 `auth.requireToken`；`MonitorWebSocketConfig` 失败握手返回 401，不建立会话、不进订阅列表。 |
| 停用、改密、新登录、撤权或到期后旧订阅 5 秒内停止推送 | 通过 | `17-subscription-tests.log` 5 项断言 close 1008 且 <5000ms；`17-postmerge-backend.log` 复跑通过；撤销 reason 覆盖 SESSION_REVOKED / SESSION_EXPIRED / ACCOUNT_DISABLED；到期用 `sa-token.timeout=4` 缩短同一绝对到期语义。 |
| 帧含事件标识、version、server_time，旧版本不覆盖新状态 | 通过 | 后端 `fleetRevocationStopsSubscriptionAndCrossFleetPositionsAreFiltered` 断言 `event_id=position:<id>:1`、`version`、`server_time` 非空；前端 mock e2e 先收 version 2 再收 version 1，界面保持 55.0 km/h。 |
| 重连后重新查询快照，窗口内变化补齐；报警列表未提供时用受控变更验证通用校正机制 | 通过（报警列表转 13/27） | mock e2e：1011 断开期间变化只发生在快照（66.0 km/h），重连后再次查询快照并显示新值，snapshot 次数增加、ticket 重发；1008 不自动重连。报警列表补齐未执行，见下节。合并后 BrowserFlowTest 31 passed（含真实 WS 的 `monitoring.spec.ts`）。 |
| 慢客户端有界缓冲，超限断开且连接状态可见 | 通过 | `slowClientIsDisconnectedAfterBoundedBufferOverflow`：真实 TCP 只握手不读帧 + 300 辆车 outbox，服务器 WARN `capacity=8` 并移除订阅，客户端 close code ∈ {1011, -1}；前端 1011 显示“实时连接已断开”并进入“正在重连”。 |
| 位置每车最多 2 秒合并推送 | 通过 | `PositionOutboxWorker` `fixedRateString=${vms.monitor.publish-interval-ms:2000}`；`TelemetryFlowTest.positionUpdatesAreCoalescedToLatestVehicleVersion` 连续 3 点只收到最终 version 3、lng 116.45（`17-postmerge-backend.log`）。 |
| 车队长跨队订阅被过滤，不泄露出授权范围数据 | 通过 | 17 `fleetRevocationStopsSubscriptionAndCrossFleetPositionsAreFiltered`：授权车队车辆收到帧，其他车队车辆 2.5s 内无帧，撤权后 1008；`publish` 对每个订阅重新 `requirePermission` 并调用 `snapshots.vehicle(user, vehicleId)` 过滤。 |
| 记录报警列表在 13/27 的后续复验项；未交付报警不阻塞本票位置通道验收 | 通过 | 见“未执行与后续复验”。 |

## 未执行与后续复验

- 报警列表补齐：13 与 27 交付报警列表后，复验首次快照与订阅之间、断线重连期间新增/处置报警能通过再次查询补齐；本票未交付报警列表，仅以位置快照受控变化验证通用校正机制。
- 真实 8 小时绝对到期：以 `sa-token.timeout=4` 缩短验证同一 `requireToken` 到期语义，未等待生产时长。
- 慢客户端为真实 TCP 不读帧客户端，不是真实网络拥塞；传输背压占满时客户端可能只看到连接终止，因此断言 close code ∈ {1011, -1} 并以服务器 WARN 与订阅移除作为断开证据。
- 前端 mock e2e 用 fake WebSocket 驱动真实 Chromium；真实浏览器 + 真实 WS 的“实时连接已建立”由 BrowserFlowTest 中的 `monitoring.spec.ts` 覆盖。
