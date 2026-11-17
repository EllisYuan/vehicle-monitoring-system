# 22 验收证据：可重复的多车综合演示场景

Status: 验收运行进行中（结果待回填）
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

子场景可分别或组合启用：`scene.commands=true` 且其余不写即只跑指令场景（run3 使用）；全量配置见 `evidence/22-config-full.conf`。

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

- 现象：stagger=800 的首次完整验收在 run1 出现终端 21 `UNEXPECTED_DISCONNECT: 服务端关闭连接`；服务端日志报 `Deadlock found when trying to get lock; try restarting transaction`。
- 根因（以 `SHOW ENGINE INNODB STATUS` 的 `LATEST DETECTED DEADLOCK` 为证，见 `logs/22-innodb-status.log`）：`alarm_candidate_state` 上两个事务并发执行 `INSERT ... ON DUPLICATE KEY UPDATE` 不同车辆行时，都会分配自增 id 并在 PRIMARY 索引 supremum 处取插入意向锁，互相等待，一方被回滚，位置事务失败导致连接关闭。`vehicle-start-stagger-ms` 只能降低碰撞概率，不能消除。
- 修复（同因同修，两侧一起改）：
  - `PositionInvalidAlarmParticipant`、`OverspeedAlarmParticipant` 在 `candidate == null` 时执行纯 INSERT，捕获 `DuplicateKeyException` 回退为 `updateCandidate`；已有候选行时只做 `UPDATE ... WHERE vehicle_id/…`，不再分配自增 id、不再取插入意向锁。
  - 保留原语义：同车首次写入并发时另一事务已插入唯一键，回退 UPDATE；候选方向、连续计数、规则版本与最后点时间照旧更新。
- 服务端测试：`SchemaUpgradeTest`、`OverspeedAlarmFlowTest`、`OfflineAndPositionAlarmFlowTest` 共 20 项通过（日志 `logs/22-maven-server-tests.log`）。该并发死锁无专门单测复现，以本轮两轮真实运行与服务端日志无死锁作为验收证据。

## 8. 运行环境与命令

（待回填）

## 9. 验收运行结果

（待回填）
