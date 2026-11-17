# 22 验收证据：可重复的多车综合演示场景

Status: 验收完成（仅 1 轮端到端运行，限制见 §9）
日期：2026-11-16
分支：`feat/ticket-22-multivehicle-scenarios`
Worktree：`C:\Users\YuanS\Documents\paper\ja\vehicle-monitoring-system\.claude\worktrees\agent-ac2bfb44954865d72`

## 1. 实现边界

- 新增 `simulator` 场景编排（JDK-only，无 Spring）：`ScenarioSimulator`（CLI）、`ScenarioConfig`（白名单键解析）、`PlanGenerator`（seed 派生规范化计划）、`ScenarioRunner`（TCP 执行与记录）、`ScenarioPlan`/`PlanEntry`/`PlanItem`/`TerminalResult`/`RunSummary`/`ScenarioType`。
- 只做编排：决定何时连接/断开、发送什么报文、何时等待与重发；超速、围栏、离线、定位无效、补传幂等与冲突的判定全部由服务端在本轮真实运行中产生，simulator 内没有复制任何平台判定规则。
- 不新增协议消息：复用 `protocol` 模块的 0x0100/0x0102/0x0001/0x0002/0x0003/0x0200/0x0704/0x8300/0x8202 编解码。
- 不替代 09/14/15/16 单场景测试；本票只提供组合编排与可重复性证据。
- 不做 100 终端性能（24）与 30 天容量（25）；不自动注册账户、不申请外部服务。
- 场景失败不静默跳过：终端级 `FAILED`/`ABORTED` 写 summary，CLI 退出码非 0。

## 2. 配置格式（`ScenarioConfig` 白名单）

必填：`seed`、`vehicles`（20 位终端号，逗号分隔，去重）。未知键直接报错，重复键报错。

| 键 | 默认值 | 含义 |
| --- | --- | --- |
| `host` / `port` | `127.0.0.1` / `9000` | 平台 TCP listener |
| `interval-ms` | `1000` | 场景内相邻计划点间隔 |
| `vehicle-start-stagger-ms` | `0` | 按终端序号错开时间线起点，降低多车同刻调度抖动 |
| `ack-timeout-ms` | `5000` | 单次 ACK 等待上限 |
| `ack-max-retries` | `3` | 重试次数上限（0—3） |
| `ack-loss-drop-count` | `1` | ACK 丢失场景模拟丢弃次数 |
| `command-window-ms` | `15000` | 指令场景保持监听窗口 |
| `command-base-interval-ms` | `interval-ms` | 指令场景位置点基线间隔 |
| `offline-gap-ms` | `3000` | 离线场景断开时长 |
| `backfill-seconds` | `120` | 补传批次最早点的回溯秒数 |
| `route.crs` | `WGS84` | 仅支持 WGS84 |
| `route.start-lng` / `route.start-lat` / `route.step-lng` | `116.397` / `39.908` / `0.0002` | 路线起点与步长 |
| `geofence.step-lng` | `0.0015` | 围栏穿越步长 |
| `scene.<名称>` | 无 | `normal`、`parking`、`overspeed`、`gnss`、`geofence`、`offline`、`backfill`、`duplicate`、`ack-loss`、`commands`，值为 `true`/`false`；至少启用一个 |

子场景可分别或组合启用：单场景配置（如仅 `scene.overspeed=true` 或仅 `scene.commands=true`）由 `PlanGeneratorTest`、`ScenarioConfigTest` 的用例覆盖；本轮端到端验收只执行了全量组合配置（run1），未单独运行指令场景或其他单场景运行。全量配置见 `evidence/22-config-full.conf`。

## 3. seed 语义与可重复性

- `PlanGenerator` 对每个 `(terminal, scene)` 组合用 FNV-1a（seed 与终端号）再乘 `0x9E3779B97F4A7C15` 加 `scene.ordinal()` 派生 `java.util.Random`，路线抖动、方向、速度全部由该随机源产生，没有 `Math.random()` 兜底。
- 每终端时间线起点为 `terminalIndex * vehicle-start-stagger-ms`，其余偏移由场景常量与 `interval-ms` 推导，与墙钟无关。
- 发送记录中 `planned_offset_ms` 是相对计划偏移，`sent_at` 是实际发送时刻；跨运行比对使用 `terminal|scene|kind|message_id|serial|attempt|planned_offset_ms|lng|lat|speed|direction|fix_valid|drop_acks|items` 规范化后逐行比较，实际调度与 ACK 时序差异单独记录在 `received_at`/`latency_ms`。

## 4. 记录文件格式

每次运行输出 4 个文件，文件名 `22-yyyyMMdd-HHmmss-SSS-seed<seed>-{send,ack,summary}.tsv|manifest.txt`：

- send TSV：`send_id run_id terminal scene kind message_id serial attempt planned_offset_ms sent_at lng lat speed direction fix_valid drop_acks items`。每次发送尝试一行（重试同 serial、递增 attempt）。
- ack TSV：`ack_id run_id send_id terminal message_id serial attempt ack_message_id result status latency_ms received_at reason`。`status` 取 `ACK_SUCCESS`/`ACK_DROPPED`/`ACK_REJECTED`/`NO_ACK`/`DISCONNECTED`/`COMMAND_RESPONSE`；`send_id` 与发送记录对账，0x8300/0x8202 的终端应答以 `COMMAND_RESPONSE` 记录。
- summary TSV：每终端一行 `terminal status failure_reason sent_count ack_count remaining_entries`，末尾 `RUN SUCCESS|FAILURE` 行。
- manifest：runId、启动时间、seed、终端列表、host/port、全部时序参数、路线参数、启用场景、Java 版本与操作系统。

## 5. ACK 语义

- 每次发送等待 `ack-timeout-ms`（默认 5000）；超时后同 serial 重发，最多 `1 + ack-max-retries` 次尝试。
- 收到明确失败应答（`result != 0` 或注册应答缺鉴权码）立即停止重试，记 `ACK_REJECTED` 并写原因。
- 连接异常先记 `DISCONNECTED`，终止该终端并写 `UNEXPECTED_DISCONNECT`，不做无限重连；终端断开时有界退出。
- 模拟 ACK 丢失记 `ACK_DROPPED`，同一次发送流程内继续等待后续应答或重试。

## 6. GNSS 异常的诚实边界

- 已执行：`gnss` 场景发送 status 定位无效位的 0x0200 点，验证平台把无效点落为 `locate_state='INVALID'`、`lng/lat` 为 NULL，并参与 14 的 POSITION_INVALID 持续事件判定。
- 未执行：仓库内没有 alarm bit 语义映射（协议层只保留 `alarmFlag` 原始值，未定义具体报警位），因此依赖 alarm bit 的 GNSS 异常子能力无法表达，本票不勾选该子能力；证据不声称验证了 alarm bit。

## 7. 服务端死锁发现与修复

- 现象：修复前的验收运行中出现终端 `UNEXPECTED_DISCONNECT: 服务端关闭连接`，服务端日志报 `Deadlock found when trying to get lock; try restarting transaction`。该修复前服务端日志没有保存在本仓库，现有证据无法复核当时的原始堆栈。
- 根因（代码分析）：`alarm_candidate_state` 上的两个事务并发对**不同车辆行**执行 `INSERT ... ON DUPLICATE KEY UPDATE` 时，都会分配自增 id 并在 PRIMARY 索引 supremum 处取插入意向锁，互相等待，一方被回滚，位置事务失败导致连接关闭。`vehicle-start-stagger-ms` 只能降低碰撞概率，不能消除。
- 修复（同因同修，两侧一起改，代码见 `server/src/main/java/com/vms/alarm/`）：
  - `PositionInvalidAlarmParticipant`、`OverspeedAlarmParticipant` 在 `candidate == null` 时执行纯 INSERT，捕获 `DuplicateKeyException` 回退为 `updateCandidate`；已有候选行时只做 `UPDATE ... WHERE vehicle_id/…`，不再分配自增 id、不再取插入意向锁。
  - 两处 `candidate()` 查询均带 `FOR UPDATE`；保留原语义：同车首次写入并发时另一事务已插入唯一键，回退 UPDATE；候选方向、连续计数、规则版本与最后点时间照旧更新。
- 修复后于 2026-11-16 21:40（run1 结束后）捕获的 `SHOW ENGINE INNODB STATUS`（`logs/22-innodb-status.log`）中没有 `LATEST DETECTED DEADLOCK` 段；该文件只能说明捕获时无死锁记录，不能反证修复前死锁，修复前证据以上述现象描述为限。
- 服务端测试：`SchemaUpgradeTest`、`OverspeedAlarmFlowTest`、`OfflineAndPositionAlarmFlowTest` 共 20 项通过（`logs/22-maven-server-tests.log`，2026-11-16 20:47）。该并发死锁没有专门单测复现；修复后的端到端运行只有 run1 一轮（见 §9），不能声称已通过两轮运行验证。

## 8. 运行环境与命令

- 主机与运行时：Windows 11 10.0.26200（run1 manifest 的 `os_name`）、Java 21.0.9（manifest 与 server 启动日志一致）、Spring Boot 4.1.2、MySQL 8.4、Redis 8.4。
- 分支与 worktree：`feat/ticket-22-multivehicle-scenarios`；`C:\Users\YuanS\Documents\paper\ja\vehicle-monitoring-system\.claude\worktrees\agent-ac2bfb44954865d72`。
- 构建：
  - 全模块（含 simulator 新类）：`mvnw.cmd -B -DskipTests package`，BUILD SUCCESS（`logs/22-build.log`，2026-11-16 19:47）。
  - 验收前重打 server：`mvnw.cmd -B -pl server -DskipTests package`，BUILD SUCCESS（`logs/22-maven-server-package.log`，21:36）。
- 测试：
  - simulator：protocol 64 项 + simulator 24 项通过（`logs/22-green-simulator-tests.log`，21:03；命令按日志 reactor 为 `-pl simulator -am test`）。
  - server：`OverspeedAlarmFlowTest`、`OfflineAndPositionAlarmFlowTest`、`SchemaUpgradeTest` 共 20 项通过（`logs/22-maven-server-tests.log`，20:47）。
- 验收环境（`logs/22-acceptance-run.log`）：
  - 专用容器 `t22-mysql`（MySQL 8.4，映射本机 13312）与 `t22-redis`（Redis 8.4）；验收结束后两容器已删除。
  - `java -jar server/target/server-0.1.0-SNAPSHOT.jar`；HTTP 18095，JT/T 808 TCP listener 19015；Flyway 应用 13 个 migration 至 v14；管理员账号 `t22-admin`（`logs/22-server-run.log`）。
  - 通过管理端 API 创建 1 个车队、3 辆车、3 个终端；创建覆盖围栏场景穿越点的多边形电子围栏（ENABLED，绑定 3 车）；等待 125 s 使终端绑定历史早于补传批次的 `gps_time`（-120 s）。
- 场景运行：
  - 以 `simulator` 与 `protocol` 的 `target/classes` 为 classpath 调用 `java com.vms.simulator.ScenarioSimulator --config .scratch/vehicle-monitoring/evidence/22-config-full.conf --output-dir .scratch/vehicle-monitoring/evidence/logs/22-runs/run1`；CLI 输出见 `logs/22-run1-cli.log`，stderr 为空（`logs/22-run1-cli.err.log`）。
  - 关键参数：`seed=20261116`，3 个终端，`interval-ms=1000`，`vehicle-start-stagger-ms=800`，`ack-timeout-ms=5000`，`ack-max-retries=3`，`command-window-ms=12000`，`offline-gap-ms=3000`，`backfill-seconds=120`，全部 10 个场景启用；完整参数见 `22-config-full.conf` 与 `22-run1-manifest.txt`。
- 说明：同 seed 的计划一致性另有 `PlanGeneratorTest.sameSeedProducesIdenticalNormalizedPlan` 等进程内单元证据；端到端复跑未执行（见 §9）。

## 9. 验收运行结果

结论：仅完成 1 轮完整端到端验收（run1），未执行第二轮复跑。

### run1（2026-11-16 21:39:24—21:40:21）

- run id `22-20261116-213924-514-seed20261116`，seed `20261116`，终端 `01380022000000000021`、`01380022000000000022`、`01380022000000000023`。
- 终端结果：3/3 `OK`，每终端 `sent_count=44`、`ack_count=43`、`remaining_entries=0`；summary 末行 `RUN SUCCESS`（`22-run1-summary.tsv`）。
- 发送与应答记录：send 132 行（含 3 次 ACK 丢失重试），ack 129 行；`ACK_SUCCESS` 126、`ACK_DROPPED` 3、`ACK_REJECTED`/`NO_ACK`/`DISCONNECTED` 均为 0。3 个 `ACK_DROPPED` 分别是三个终端 ack-loss 场景 serial=37 的 attempt=1，attempt=2 均收到 `ACK_SUCCESS`（`22-run1-send.tsv`、`22-run1-ack.tsv`）。
- 场景覆盖（send 行按 scene 计数）：normal 24、geofence 27、offline 21、overspeed 15、commands 12、ack-loss 9、gnss 9、parking 9、backfill 3、duplicate 3。
- 验收脚本断言（`logs/22-acceptance-run.log`）：MySQL 容器就绪；server 启动并取得 admin session；围栏 ENABLED 且绑定 3 车；run1 CLI exit 0；summary 无 `FAILED`/`ABORTED`；终端起点符合 `vehicle-start-stagger-ms=800`；捕获的服务端日志无终端连接失败或 deadlock。
- 时序：run1 约 57 s；三个终端首帧发送时间为 21:39:24.58、21:39:25.37、21:39:26.18，起点间隔约 800 ms。

### 失败或环境尝试（不计入验收轮次）

- `logs/22-acceptance-run-docker-outage.log`（21:28）：Docker 不可用，server 未启动，脚本清理后退出，没有场景运行记录。
- `logs/22-deadlock-diag-run.log`（20:32）：死锁诊断运行在 run1 阶段提前停止，未产出运行记录。

### 限制与未执行项

- **可重复性**：只有 1 轮端到端运行，没有跨运行的发送序列逐行比对；可重复性目前仅有 `PlanGeneratorTest` 的进程内同 seed 规范化计划一致作为单元级证据。ticket Answer 明确标注该缺口。
- **MySQL 数据核对**：验收日志只有 `== SQL verification after run1` 标题，没有查询语句、查询结果或 PASS/FAIL 行；run1 结束后容器已删除，未补查。因此本票不声称已完成位置、异常、报警与指令结果的真实 MySQL 核对。
- **服务端日志窗口**：仓库中保留的 `logs/22-server-run.log` 只覆盖 21:37:06—21:37:17 的启动阶段，不含 21:39—21:40 的 run1 流量；因此验收脚本的“服务端日志无连接失败或 deadlock”无法从现有日志复核到 run1 窗口，run1 无断开主要由终端状态与 ACK 记录支撑。
- **GNSS alarm bit**：见 §6，未实现、未验证。
- 未执行：第二轮复跑、单场景端到端运行、浏览器全量、100 终端性能（24）、30 天容量（25）。

### 合并后最小验证（2026-11-17，integration/tickets-08-27 @ 4a38b98）

- 合并：`git merge --no-ff integration/tickets-08-27` 无冲突，合并提交 `6afd4ab`。
- 构建：`mvnw.cmd -B -f <worktree>\pom.xml -DskipTests package`，BUILD SUCCESS（exit 0），server 105 个源文件与 simulator 模块均编译通过。
- 测试：`-pl server -am -Dtest=OverspeedAlarmFlowTest,OfflineAndPositionAlarmFlowTest,SchemaUpgradeTest -Dsurefire.failIfNoSpecifiedTests=false test`，共 20 项通过：`OverspeedAlarmFlowTest` 10、`OfflineAndPositionAlarmFlowTest` 8、`SchemaUpgradeTest` 2；BUILD SUCCESS（exit 0，完整输出 `22-postmerge-tests.log`）。
- 迁移事实：本票提交未改 `server/src/main/resources/db` 与 `SchemaUpgradeTest`；合并后 Flyway 仍为 13 个 migration、版本 v14，`SchemaUpgradeTest` 断言通过。