# 任务23 验证记录：监控中断与恢复综合验收

- 日期：2026-11-16
- 分支：`feat/ticket-23-outage-recovery`；基线 HEAD `4ed9752`（`integration/tickets-08-27`，任务18 合入后的集成分支）。
- 环境：Windows 11 Pro for Workstations 10.0.26200；JDK 21.0.9（`JAVA_HOME=C:\Java\jdk-21.0.9`）；Spring Boot 4.1.2；MySQL 8.4（`t23-mysql`，固定端口 13313）；Redis 8.4（`t23-redis`，固定端口 16385）；应用 HTTP 18195、TCP 19115；Vite 8.3.6（18124）；Playwright 1.58.1 + Chromium；业务时区 Asia/Shanghai。
- 应用启动参数：仅覆盖 `-Dvms.access.tcp-port=19115` 与 `-Dvms.alarm.offline-scan-interval-ms=3000`（离线扫描间隔，便于在验收窗口内观察）；启动宽限仍使用既有 `vms.alarm.offline-scan-initial-delay-ms=180000`，未修改。
- 串行方式：验收脚本、Playwright 与所有服务启动/停止均通过 `C:\Users\YuanS\AppData\Local\Temp\vms-spec-notes\with-test-lock.ps1`（互斥量 `Local\vms-verification-lock`，等待上限 120 分钟）串行执行；本轮未并行启动 Maven、浏览器或 Testcontainers。前端 `pnpm typecheck/lint/build` 在锁外执行。
- 验收脚本：`C:\Users\YuanS\AppData\Local\Temp\vms-ticket23\verify23.ps1`（临时脚本，按项目惯例不入库；命令见下）。脚本每轮重建业务库、启动真实应用，依次执行四类中断并记录开始/结束/时长与恢复结果，同环境连续两轮。
- 执行命令（锁内）：
  `& 'C:\Users\YuanS\AppData\Local\Temp\vms-spec-notes\with-test-lock.ps1' -Command 'C:\Users\YuanS\AppData\Local\Microsoft\WindowsApps\Microsoft.PowerShell_8wekyb3d8bbwe\pwsh.exe','-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File','C:\Users\YuanS\AppData\Local\Temp\vms-ticket23\verify23.ps1'`
- 证据存放：以下均在 `.scratch/vehicle-monitoring/evidence/` 顶层（`.gitignore` 仅放行该目录顶层的 `*.log`）：
  `23-server-r{1,2}-s{1,2}.log`、`23-playwright-{r1,r2}.log`、`23-vite-{r1,r2}.log`、`23-r{1,2}-23-browser-*.png`、`23-web-checks.log`、`23-red-{1,2,3}-*.log`。
## 1. 红→绿记录

本票是验收编排，不改产品代码；验收脚本与新 e2e 本身也按红→绿推进。红阶段的失败均定位为**测试数据/测试驱动方式**问题，不调整任何判定阈值或放宽断言：

| 阶段 | 实际结果 | 说明 | 证据 |
| --- | --- | --- | --- |
| 红 1 | `ASSERT FAILED: V2 HISTORY points expected [3] actual [0]` | 补传点取固定 600 秒前，早于 `terminal_vehicle_binding.effective_from`；服务端按 `gps_time` 解析归属为 `UNASSIGNABLE`，不写入 `track_point`（真实归属语义，不是缺陷） | `23-red-1-backfill-history.log` |
| 红 2 | `ASSERT FAILED: 补传窗口可用：绑定已生效 -28574s` | `docker exec mysql` 客户端会话时区为 UTC，而应用以 JVM 本地时区（Asia/Shanghai）写 DATETIME，SQL `NOW()` 差 8 小时；改用宿主机本地时间字面量比较 | `23-red-2-timezone.log` |
| 红 3 | `ASSERT FAILED: Playwright exit code expected [0] actual [1]` | `page.unrouteAll()` 只清理 HTTP 路由，不清 `routeWebSocket` 注册；浏览器 WS 恢复后仍被拦截，页面停留在断线状态。改为开关控制放行并 `ws.connectToServer()` | `23-red-3-playwright.log` |
| 绿 | `SUMMARY round=1 result=PASS ... playwright=PASS`；`SUMMARY round=2 result=PASS ... playwright=PASS`；`ALL CHECKS PASSED (2 consecutive rounds)`，锁进程 exit 0 | 同环境连续两轮全量通过；Playwright 两轮均 `5 passed`、exit 0 | 两轮完整控制台输出由执行任务归档；逐项 PASS 与时间戳见第 2、4 节及下列日志 |

负向对照（真实中断期间的实际状态，而非测试假绿）：

- MySQL 停止期间，模拟器上报 `register: TCP response timeout after 5000 ms`，退出码 1，未获得成功 ACK；恢复后同一终端上报成功（`mysql-recovered-attempt-1 exit=0`）。
- TCP/应用进程停止期间，模拟器 `ConnectException: Connection refused`，退出码 1；停机中的运动未进入系统。
- 浏览器层关闭 `/ws/monitor` 后，页面先显示 `实时连接已断开`/`正在重连 x/5`，重试耗尽后 `实时连接已停止 · 请手动重试`；超过 60 秒出现 `数据陈旧` 与逐车 `定位陈旧`。
- Redis 停止期间，业务位置仍提交并成功 ACK（缓存不参与 ACK），服务日志出现 `Position cache unavailable; next refresh rebuilds it from MySQL`。

## 2. 中断与恢复逐项记录

| 中断 | 方式（真实中断，非 mock） | 第 1 轮开始→结束（时长） | 第 2 轮开始→结束（时长） | 恢复结果 |
| --- | --- | --- | --- | --- |
| MySQL | `docker stop t23-mysql` → `docker start t23-mysql`（本票专用容器，固定端口 13313，保留容器卷） | 21:44:55.529 → 21:45:20.458（25 s） | 21:49:41.654 → 21:50:06.415（25 s） | 位置上报重新获得成功 ACK；ACTIVE 报警、处置记录、最后位置保持；服务未退出 |
| Redis | `docker stop t23-redis` → `docker start t23-redis`（本票专用容器，固定端口 16385，保留容器卷） | 21:45:33.001 → 21:45:39.806（7 s） | 21:50:18.534 → 21:50:24.905（6 s） | 中断期间位置仍提交并 ACK；恢复后快照由 MySQL 重建，派生缓存键 `vms:position:*` 重建；旧会话仍有效（实测未强制重新登录） |
| TCP listener / 应用进程 | `Stop-Process` 停止应用进程（HTTP 18195 与 TCP 19115 同时不可用）→ 以同一 jar 重新启动 | 21:45:50.747 → 21:46:08.234（17 s） | 21:50:35.866 → 21:50:44.485（9 s） | TCP 端口重新监听；ACTIVE 报警、处置、候选、围栏基线与最后位置保持；启动宽限生效 |
| 浏览器 WebSocket | 真实 Chromium + 真实 Vite + 真实后端：用例 4 `page.close()` 销毁页面连接后重开；用例 5 `page.routeWebSocket` 在浏览器层以 1011 关闭 `/ws/monitor`，恢复时放行并由 `ws.connectToServer()` 代理到真实后端 | 用例 4：16.7 s；用例 5：1.7 min（含重试耗尽与 60 s 陈旧阈值） | 用例 4：14.6 s；用例 5：1.7 min | 用例 4：重开后快照与报警列表补齐、业务事件不重复；用例 5：手动重试后 `实时连接已建立`、陈旧标记清除、快照恢复 |
说明：

- MySQL/Redis 均使用容器级 `stop`/`start`，未使用 `pause` 代替 kill-then-restart；固定宿主端口使重启后应用连接配置不变。两个容器仅为本票创建，脚本结束时只删除 `t23-mysql`/`t23-redis`（`docker rm --force --volumes`）。
- TCP 中断选择停止应用进程：Windows 上 Netty listener 随进程退出，HTTP 与 TCP 同时中断；这是进程级真实中断。未单独只停 Netty 端口（项目未提供运行期关闭 listener 的入口），限制如实记录。
- 浏览器 WS 中断不使用假 WebSocket；`page.close()` 与 `routeWebSocket` 关闭均发生在真实页面与真实后端之间。
- 停机期间将 T5 的 `terminal.last_seen_at` 回拨 400 秒，仅用于在两轮有限窗口内观察「启动 180 秒宽限」与「宽限后 OFFLINE」；不改变产品判定规则或阈值。
### 2.1 MySQL 中断细节（两轮一致）

- 中断前：V1 超速报警 `ACTIVE|PENDING`（后续处置为 `PROCESSING`），候选 `OVER|3`，围栏基线 `1|INSIDE|0`，V1 最后位置 `version=4 speed=100.0`；T3 无轨迹。
- 中断期间：T3 上报 register 5 秒超时、退出码 1；旧会话快照请求记录为 `TRANSPORT`（仅记录，不作断言）。
- 恢复后：T3 位置上报成功（`track_point=1`，`REALTIME=1`，无历史行）；报警行仍 1 条 `ACTIVE|PROCESSING`，处置日志 1 条 `HANDLE|PENDING|PROCESSING|1`；V1 最后位置仍 `2026-11-16 21:44:50|4|100.0`（第 2 轮 `21:49:37|4|100.0`）；HTTP 存活探测 200。
- 证据：第 1 轮 `23-server-r1-s1.log` 中 21:44:55 起 `Communications link failure`、Hikari broken connection；控制台 PASS 行见 2.3；`23-server-r1-s2.log`。

### 2.2 Redis 中断细节（两轮一致）

- 中断期间：V4 上送 1 个位置，`track_point=1`、`vehicle_last_position` speed `10.0`，ACK 成功；服务日志 21:45:33 起多次 `Position cache unavailable; next refresh rebuilds it from MySQL`；旧会话快照 500（仅记录）。
- 恢复后：旧会话快照 200（实测未要求重新登录；仍执行一次 `login t23-admin -> 200` 作为可用性核验）；快照包含 V4 最新位置；Redis 派生键 `vms:position:<V4>` 重建；已提交业务数据未丢失（以 MySQL 为事实源）。
- 证据：`23-server-r1-s1.log`、`23-server-r2-s1.log`（`Position cache unavailable`、`Cannot reconnect ...:16385`）；控制台 PASS 行。
### 2.3 应用重启、宽限与补传细节

第 1 轮关键 PASS 行（第 2 轮同构，时间不同）：

```
PASS alarm after restart = ACTIVE|PROCESSING
PASS handle log after restart = HANDLE|PENDING|PROCESSING|1
PASS candidate after restart = OVER|3|2026-11-16 21:44:50.000
PASS geofence state after restart = 1|INSIDE|0|2026-11-16 21:44:50.000
PASS V1 position after restart = 2026-11-16 21:44:50|4|100.0
PASS 重启后快照 V1 仍为 100.0 km/h（不回退）
PASS 重启后快照 V2 仍为 20.0 km/h（不回退）
PASS T5 offline count during grace = 0
PASS 补传窗口可用：绑定已生效 112s
PASS V2 HISTORY points = 3
PASS V2 position after backfill = 2026-11-16 21:45:45|1|20.0
PASS V2 alarms after backfill = 0
PASS V1 HISTORY points = 3
PASS V1 position after backfill = 2026-11-16 21:44:50|4|100.0
PASS candidate unchanged by backfill = OVER|3|2026-11-16 21:44:50.000
PASS geofence unchanged by backfill = 1|INSIDE|0|2026-11-16 21:44:50.000
PASS V1 alarm count after backfill = 1
PASS candidate rebuilt = OVER|3
PASS V1 still one alarm = 1
PASS geofence no spurious event = INSIDE|0
PASS no geofence alarm for V1 = 0
PASS outbox event ids unique = 1
PASS created outbox single row = 1
PASS handle outbox single row = 1
PASS V4 position outbox single row = 1
PASS T5 OFFLINE 报警在宽限后创建 id=3
PASS T5 offline alarm after grace = ACTIVE|PLATFORM|0|1
PASS T5 offline last_time equals start = 0
PASS T5 offline alarm count = 1
PASS T5 offline outbox single row = 1
PASS V2 still no alarm after grace = 0
PASS V1 still one alarm after grace = 1
```

补传窗口按绑定已生效时长动态取（第 1 轮 82 s、第 2 轮 81 s），落在绑定 `effective_from` 之后、60 秒实时阈值之前，因此补传点成为 `HISTORY`，只新增 `track_point`；`vehicle_last_position`、候选、围栏基线与报警均未被回退或改写。
## 3. 逐条 AC

### AC1 分别执行 MySQL、Redis、TCP、WS 中断与恢复，均有记录 — 通过

- 命令/环境：见头部锁内命令与固定端口；每轮重建 MySQL/Redis 业务库。
- 期望：四类中断均真实发生，记录方式、开始/结束、持续时长与恢复结果。
- 实际：四类中断均执行并记录，见第 2 节表格；两轮均通过。MySQL 25 s/25 s，Redis 7 s/6 s，TCP 17 s/9 s，浏览器 WS 由 Playwright 用例 4/5 驱动（两轮合计 `5 passed`，exit 0）。
- 证据：第 2 节；`23-playwright-r1.log`、`23-playwright-r2.log`；两轮控制台 PASS/RECORD 行。

### AC2 ACTIVE 报警与处置不被重启改写，候选与围栏基线重建 — 通过

- 命令/环境：应用重启后查询 `alarm`、`alarm_handle_log`、`alarm_candidate_state`、`vehicle_geofence_state`、`vehicle_last_position`。
- 期望：`condition_state`/`handle_status` 保持 `ACTIVE|PROCESSING`；处置日志不丢；候选与围栏基线可继续使用，不被重启清空；重建后同一 ACTIVE 报警不重复创建。
- 实际：两轮重启后报警 `ACTIVE|PROCESSING`、处置日志 `HANDLE|PENDING|PROCESSING|1`；候选 `OVER|3`、围栏 `1|INSIDE|0`；再送 3 个实时超速点后候选仍 `OVER|3`、报警仍 1 条、无围栏事件/围栏报警；outbox `alarm:created`、`alarm:handle`、`position` 各 1 条且 `event_id` 唯一。
- 证据：第 2.3 节 PASS 行；`23-server-r{1,2}-s{1,2}.log`。
### AC3 180 秒启动宽限生效，停机运动不被补算 — 通过

- 命令/环境：应用重启后 +30 s 查 T5 OFFLINE 计数；+200 s 查离线报警；停机期间以模拟器向 TCP 19115 发送运动点。
- 期望：宽限内不产生 OFFLINE；宽限后按 `last_seen_at + 180s` 创建一条 OFFLINE；停机期间因 TCP 不可达而未进入系统的运动不补算报警。
- 实际：+30 s `T5 offline count during grace = 0`；+200 s OFFLINE 报警 id=3 `ACTIVE|PLATFORM|rule_id NULL|handle PENDING`，`start_time = last_seen_at + 180s`（差值 0），仅 1 条，outbox 单行；停机运动上报 `Connection refused`、exit 1；补传/重启后 V2 报警仍 0 条、V1 仍 1 条。
- 证据：第 2.3 节；`23-server-r{1,2}-s2.log` 启动时间与 `TrackPartitionMaintenance`；控制台 RECORD/PASS 行。

### AC4 补传只补轨迹，不回退图标、不改变围栏状态 — 通过

- 命令/环境：重启后模拟器 `--backfill 3 --backfill-interval-ms 5000 --batch-size 3 --speed 200`（V1 补传坐标在围栏外，V2 补传坐标远离基线）。
- 期望：补传点仅写 `track_point`（`HISTORY`）；`vehicle_last_position`、候选、围栏状态不因补传改变；不产生进出事件或报警。
- 实际：V2 `HISTORY=3`、`REALTIME=1`、最后位置仍 `speed=20.0`、报警 0；V1 `HISTORY=3`、最后位置仍 `2026-11-16 21:44:50|4|100.0`、候选/围栏基线时间戳不变、报警仍 1 条；无围栏事件、无新增报警。
- 证据：第 2.3 节；`23-server-r{1,2}-s2.log`；两轮控制台 PASS 行。
### AC5 Redis 重启后业务数据不丢，会话要求重新登录 — 通过（实测未要求重新登录）

- 命令/环境：`docker stop t23-redis` 期间上报 V4 位置，`docker start` 后查 `track_point`、`vehicle_last_position`、快照与 `vms:position:<V4>`；旧会话与重新登录后会话各请求一次快照。
- 期望：已提交业务数据不丢，快照由 MySQL 重建；会话行为按实际记录，不虚构。
- 实际：中断期间 V4 `track_point=1`、speed `10.0`，ACK 成功；恢复后快照 200 且包含 V4 最新位置，派生缓存键重建；旧会话恢复后仍返回 200，**本次未要求重新登录**（`Redis stop/start` 保留了 Redis 内会话数据）；重新 login 亦 200。已提交数据以 MySQL 为事实源，未丢失。
- 证据：第 2.2 节；`23-server-r{1,2}-s1.log` 的 `Position cache unavailable`；两轮控制台 RECORD `Redis 恢复后旧会话快照状态=200`。

### AC6 重连补齐快照/报警，业务事件不重复创建；重复推送按事件标识或版本去重 — 通过

- 命令/环境：Playwright 用例 4（关闭页面→重开并重新登录）、用例 5（浏览器层断 WS→恢复）；真实后端 + Vite + 模拟器。
- 期望：重开后快照与报警列表补齐；同一业务事件不重复创建；重复推送按 `event_id` 或版本去重。
- 实际：用例 4 重开后 V1 位置 `100.0 km/h`、报警列表可见同一报警行（活跃/待处理），REST 查询仍只有 1 条且 id 不变；用例 5 恢复后快照 `42.5 km/h`，报警 0 条（该车未产生报警）；outbox SQL 断言 `event_id` 唯一且 created/handle/position 各 1 行；既有 `monitoring-stream.spec.ts` 用例 1 验证旧版本帧不覆盖新版本、断线重连补齐窗口（两轮均通过）。报警列表当前由页面加载时 REST 补齐，没有 WS `alarm_event` 订阅，此为实际行为。
- 证据：`23-playwright-r{1,2}.log`（5 passed）；`23-r{1,2}-23-browser-reconnect-alarm-list.png`；第 2.3 节 outbox PASS 行。
### AC7 页面可见断线与数据陈旧 — 通过

- 命令/环境：Playwright 用例 5，Chromium 真实页面，`routeWebSocket` 关闭 `/ws/monitor`。
- 期望：断线可见；停止重连后数据陈旧可见；恢复后陈旧消失。
- 实际：状态区依次出现 `实时连接已断开`/`正在重连`，随后 `实时连接已停止 · 请手动重试`；超过 60 秒出现 `数据陈旧` 横幅与逐车 `定位陈旧`；放行并点击 `重新连接` 后 `实时连接已建立`、陈旧计数 0、位置 `42.5 km/h`。
- 证据：`23-playwright-r{1,2}.log` 用例 5 通过；`23-r{1,2}-23-browser-stale-recovered.png`（恢复后截图；陈旧状态由用例断言与 Playwright 日志记录）。

### AC8 每项记录命令、环境、期望/实际与证据 — 通过

- 本文件逐项给出命令、环境、期望/实际、时间与证据路径；中断记录见第 2 节，两轮对比见第 4 节；原始日志与截图已归档到 `evidence/` 顶层。
## 4. 两轮对比

控制台汇总（同一环境、同一容器、连续两轮）：

```
round=1 result=PASS roundSeconds=282s mysqlOutage=25s redisOutage=7s tcpOutage=17s playwright=PASS
round=2 result=PASS roundSeconds=288s mysqlOutage=25s redisOutage=6s tcpOutage=9s playwright=PASS
ALL CHECKS PASSED (2 consecutive rounds)
```

- 两轮均完成建车/建终端/绑定、超速 ACTIVE、处置、四类中断与恢复、补传、候选重建、outbox 唯一性、Playwright 5 项与宽限后 OFFLINE 校验，无失败、无跳过。
- 时长差异（Redis 7→6 s、TCP 17→9 s）来自容器/进程重启与就绪等待的实际耗时，不影响断言；断言均为确定性 SQL/HTTP/页面状态。
- Playwright 两轮均 `5 passed`（3 条既有 `monitoring-stream.spec.ts` + 2 条新增 `outage-recovery.spec.ts`），exit 0；新增用例在 `web/e2e/outage-recovery.spec.ts`，未修改既有 spec 或放宽断言。

## 5. 前端静态检查与回归边界

- 命令（锁外）：`pnpm typecheck`、`pnpm lint`、`pnpm build`（`web/`）。
- 实际：typecheck exit 0；lint exit 0（`137 problems (0 errors, 137 warnings)`，与集成分支既有 warning 基线一致）；build exit 0（`✓ built in 544ms`）。
- 证据：`23-web-checks.log`。
- 本票未修改 `server/` 业务代码、未修改既有前端功能代码；新增 e2e spec 属于验收编排。`pnpm typecheck` 覆盖 `e2e/**/*.ts`（`tsconfig.node.json` 已包含），新 spec 通过类型检查。
## 6. 迁移与 SchemaUpgrade

- 本票未新增 Flyway 迁移，未修改任何 DDL、`ck_alarm_type` 或 `ck_candidate_direction`；保留号 V10 未占用。
- 应用每次启动均由 Flyway 对空库应用集成分支迁移集合并成功启动（`23-server-r*-s1.log` 启动成功、`TrackPartitionMaintenance` 完成），说明验收环境与集成分支迁移集合一致。
- `SchemaUpgradeTest` 未在本票单独修改或运行（无 schema 变更）；合并集成分支后由受影响验证/合并者按实际迁移集合复核，详见第 7 节。

## 7. 合并集成分支后复验

待合并最新 `integration/tickets-08-27` 后补充。

## 8. 未执行与说明

- 集群/多实例高可用、多实例 TCP 调度：Scope boundaries 明确排除，未执行。
- 性能（24）与容量（25）：不在本票范围，未执行。
- 单独只停 Netty listener 而不停应用进程：项目无运行期关闭 listener 入口，未执行；以停止应用进程（HTTP+TCP 同时中断）作为进程级 TCP 中断，限制已记录。
- 浏览器 WS 用例 5 在恢复后截图；断线与数据陈旧状态由 Playwright 断言和日志记录，未单独截取中断中画面。
- Redis 会话实际未要求重新登录；AC5 按实际行为记录，未虚构「强制重新登录」。

## 9. 容器与磁盘

- 两轮结束后脚本仅删除本票容器 `t23-mysql`、`t23-redis`（`docker rm --force --volumes`）；未执行 `docker volume prune`、`docker system prune`，未删除任何他人容器（`reverent_brahmagupta` 等仍在）。
- 结束时 `docker ps -a` 中不存在 `t23-*`；C: 可用约 27.5 GB（高于 18 GB 警戒线，未触及 15 GB 硬边界）。
- 本票未使用 Testcontainers（验收脚本直接管理固定端口容器），因此无 Ryuk 回收对象；未留下命名卷。