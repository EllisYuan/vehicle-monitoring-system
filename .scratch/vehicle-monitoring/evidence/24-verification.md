# 任务 24 验证记录：100 终端性能验收（降配单轮，内存不足中止）

> **降配声明（必读）**：本轮按用户明确授权在**降低内存配置**下执行：MySQL 容器 1536→**768 MiB**、`innodb-buffer-pool-size` 768M→**384M**、Redis 256→**128 MiB**、Surefire JVM `-Xmx` 512m→**384m**。该轮于 2026-11-19 14:36:15 启动，14:38:42 被 harness 的运行期 0.6 GB 内存守卫**主动中止**（非 OS 看门狗 kill）。**未产生 30 分钟完整测量、p95、两段延迟、对账或缺失帧数据**。降配下的任何数字都**不代表默认部署配置的产品性能**，不得写入论文作为性能达标证据。且仅发起单轮，**未满足 AC 的重复测量要求**。

状态：**受阻（blocked）**。本轮完成了降配配置、构建与真实启动，但宿主可用内存在启动后 1.5 分钟内跌至 0.6 GB 以下，harness 主动中止；未产生可判定的验收结果。

## 结论摘要

| 项 | 结果 |
|---|---|
| 单轮状态 | **已启动，14:38:42 主动中止**（harness 运行期守卫；非 OS kill） |
| 中止读数 | harness `checkRuntimeGuards` 读到可用内存 **514,621,440 B（0.514 GB，十进制；守卫阈值 600,000,000 B）** |
| 30 分钟完整运行 | **未产生**（scenario_start 14:37:12.195 → 中止 14:38:42，约 90 s） |
| p95 / 两段延迟 | **未产生**（无 `frames.json`，浏览器在写帧前被停止） |
| 发送/ACK 对账 | **未执行**（未到达 `analyseDatabase`；MySQL 容器已由 Ryuk 回收）；18,000 仅为理论对照 |
| 部分原始数据（非验收） | send 1,148 行 = POSITION 848 + REGISTER/AUTH/HEARTBEAT 各 100；ack 1,148 行全部 `ACK_SUCCESS`、`result=0`；全部 `attempt=1`，位置重传 0 |
| 失败/重传 | 部分运行无协议失败/重传；唯一失败为内存守卫中止，已如实记录、不计入成功 |
| 构建 | `mvnw.cmd -B -f <worktree>\pom.xml -DskipTests package` 两次 BUILD SUCCESS（27.5 s、19.5 s） |
| 默认 Playwright | `46 tests in 15 files`（未变） |
| migration / 产品代码 | 本轮**无** migration、**无**产品代码改动；gap lock 修复为既有成果，保持不动 |
| 容器/进程回收 | `docker ps -a --filter name=testcontainers` 为空；本轮 MySQL/Redis/Ryuk 已回收；无遗留 java/node/vite；他人容器未动 |

## 降配项清单（本轮实际值）

| 项 | 默认值 | 本轮值 | 实际证据 |
|---|---|---|---|
| MySQL `withMemory` | 1536 MiB | **768 MiB** | `env.mysql.container_limits=memory=768MiB nanoCpus=2.0，…用户授权降配（默认 1536MiB）` |
| MySQL `--innodb-buffer-pool-size` | 768M | **384M** | `env.mysql.container_command=…--innodb-buffer-pool-size=384M…`；`env.mysql.innodb_buffer_pool_size=402653184` |
| Redis `withMemory` | 256 MiB | **128 MiB** | `env.redis.container_limits=memory=128MiB nanoCpus=0.5，…用户授权降配（默认 256MiB）` |
| Surefire JVM `-Xmx` | 512m | **384m** | `env.jvm.args=[-Xmx384m]`；实际配置位置为 `server/pom.xml` 的 `<argLine>`（不在 `PerfContainers.java`） |

其余 MySQL 参数保持不变：`--skip-log-bin --innodb-flush-log-at-trx-commit=2 --innodb-log-buffer-size=64M --innodb-doublewrite=OFF --max-connections=100 --performance-schema=OFF`；`vms.monitor.publish-interval-ms=2000` 为默认值，未覆盖。

harness 中同步写入了 `env.perf.memory_downscale`，明确记录本轮降配与“不代表默认部署配置”的限制；运行期主动中止线由 0.8 GB 放宽到用户本轮授权的 **0.6 GB**（`LOW_MEMORY_ABORT_BYTES=600_000_000L`），该守卫在本轮实际触发。

## 基线与范围

| 项目 | 值 |
|---|---|
| 分支 | `feat/ticket-24-hundred-terminal-perf` |
| worktree | `C:\Users\YuanS\Documents\paper\ja\vehicle-monitoring-system\.claude\worktrees\agent-ab1377cbd6ba02a0f` |
| 起点 HEAD | `d4106c3`（`docs: 完成任务24启动前内存观察证据`） |
| 集成分支 | `integration/tickets-08-27` tip `3ed52f2`，已核验为 HEAD 祖先 |
| 范围依据 | ticket 24、spec AC32/US89（次：US90）、`docs/design/系统设计方案.md` 性能目标节 |

## 启动门槛与内存曲线

用户本轮门槛：启动前 `FreePhysicalMemory` ≥ 2.0 GB 即可启动；运行中 < 0.6 GB 主动中止。

- 14:31:30 读到 **2.03 GB** 的瞬时峰值；26 秒后（14:31:56）回落至 **1.72 GB**，按门槛**未在该时刻启动**。
- 14:32:41 起由门槛启动器每 15 s 采样，持续在 0.6–1.2 GB；14:36:15 读到 **2.05 GB**，达到门槛后经全局锁启动。
- 14:36:22 外部采样 **2.01 GB**；随后容器、Spring Boot、Vite、Chromium 与 simulator 相继启动，可用内存快速下降：
  - 14:37:24 外部采样 **0.33 GB**（本轮运行期最低点）；
  - 14:37:42 harness 守卫 **829,775,872 B（0.83 GB，十进制）**，记录 `PERF_MEMORY_WARNING`；
  - 14:38:25 外部采样 **0.45 GB**；
  - 14:38:42 harness 守卫 **514,621,440 B（0.514 GB，十进制）< 0.6 GB**，抛出 `IllegalStateException` 并主动停止本轮；
  - 14:39:27（中止后，本轮容器已回收）外部采样回升至 **1.15 GB**。

外部 60 s 内存采样（`evidence/logs/24-round1-memory-watch.log`，单位按用户门槛口径 `FreePhysicalMemory/1MB`）：

| 时间 | free_gb | available_gb | 说明 |
|---|---|---|---|
| 14:20:54 | 0.43 | 0.58 | 观察开始 |
| 14:21:56 | 1.19 | 1.08 | |
| 14:22:57 | 1.04 | 0.89 | |
| 14:23:59 | 0.43 | 0.47 | |
| 14:25:06 | **0.17** | 0.29 | 观察窗最低点（其他项目 `pms_redis` 刚启动） |
| 14:26:08 | 1.13 | 1.11 | |
| 14:27:09 | 0.49 | 0.51 | |
| 14:28:11 | 0.50 | 0.53 | |
| 14:29:12 | 0.55 | 0.52 | |
| 14:30:13 | 0.61 | 0.58 | |
| 14:31:15 | 1.81 | 1.77 | |
| 14:32:16 | 1.53 | 1.50 | |
| 14:33:18 | 0.74 | 0.78 | |
| 14:34:19 | 0.55 | 0.53 | |
| 14:35:21 | 0.58 | 0.56 | |
| 14:36:22 | **2.01** | 1.91 | 启动后首次采样（门槛启动器 14:36:15 读 2.05） |
| 14:37:24 | **0.33** | 0.33 | 运行期外部最低点 |
| 14:38:25 | 0.45 | 0.42 | 守卫下一分钟读 0.514 GB 并中止 |
| 14:39:27 | 1.15 | 1.16 | 中止后回升（本轮容器已回收） |

完整 15 s 门槛采样见 `24-round1-gate.log`；harness 运行期每分钟采样见 `24-round1-metrics.log` 的 `runtime.memory.free_bytes_at_*`。

## 单轮执行与中止

启动命令（经 `with-test-lock.ps1` 全局锁，后台执行，锁上限 120 分钟）：

```text
mvnw.cmd -B -f <worktree>\pom.xml -pl server -am \
  -Dtest=HundredTerminalPerfHarnessTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dvms.perf=true -Dvms.perf.round=1 test
```

时间线：

| 时间 | 事件 |
|---|---|
| 14:36:15 | 门槛通过（2.05 GB），启动 Maven |
| 14:37:02.402 | harness `run.started_at` |
| 14:37:10–14:37:12 | Spring Boot 就绪；真实登录后 `/ws/monitor` 订阅建立（`WS 订阅建立`，user=perf-admin） |
| 14:37:12.195 | simulator `scenario_start`，steady 场景开始真实 TCP 注册/鉴权/位置上报 |
| 14:37:42 | harness 内存警告（0.83 GB） |
| 14:38:42 | **内存守卫读到 0.514 GB < 0.6 GB，主动中止** |
| 14:38:46 | Maven `BUILD FAILURE`（server 模块 2:27 min；总 2:28 min） |

Surefire 记录：`HundredTerminalPerfHarnessTest.hundredTerminalSteadyLoadIsMeasuredEndToEnd`，Time elapsed 101.4 s，`IllegalStateException: 宿主可用内存低于 0.6 GB（514621440 B），主动停止本轮并保留证据`，位置 `checkRuntimeGuards:382`（由 `HundredTerminalPerfHarnessTest.java:249` 调用）。

中止过程由 harness `finally` 停止 simulator/browser/frontend 子进程；Testcontainers 由 Ryuk 回收。**不是 OS 看门狗 kill**；运行日志中 `Deadlock=0`、`OutOfMemory=0`、`Killed=0`。

## 部分原始数据（非验收，不得作为 AC 结果）

本轮 simulator 运行约 90 s，原始文件为 `evidence/logs/24-1-simulator/22-20261119-143712-205-seed20261124-{send,ack}.tsv` 与 `-manifest.txt`：

| 项 | 实际 |
|---|---|
| send 行数 | **1,148** |
| ack 行数 | **1,148** |
| POSITION（`0200`） | 848 |
| REGISTER / AUTH / HEARTBEAT | 100 / 100 / 100 |
| `attempt` 分布 | 1 → 1,148（位置重传 **0**） |
| ACK 状态 | `ACK_SUCCESS` 1,148（非成功 0，`result=0` 1,148） |
| summary TSV | **未生成**（simulator 被 harness 停止，未写 RUN SUCCESS） |
| 理论点数 | 18,000（100 × 180），**仅作理论对照，不是实测数** |

这些数字只说明降配环境下的真实 TCP 链路在 90 s 内可完成注册/鉴权/上报并收到成功 ACK；**不是 30 分钟验收数据，不参与 AC2/AC3/AC5 判定**。

## 两段延迟（未产生）

要求的接入阶段（`scenario_start` 所在秒 + 60 s）与稳态 30 分钟分段统计，本轮**均未产生**：浏览器在 `frames.json` 落盘前被停止（`frames_exists=False`），故无任何延迟样本。

| 段 | 样本数 | p50 | p90 | p95 | p99 | max |
|---|---|---|---|---|---|---|
| 接入阶段 | 未产生 | — | — | — | — | — |
| 稳态 30 分钟 | 未产生 | — | — | — | — | — |

AC4 **未判定**。既不能判定达标，也不能判定未达标；不得用本轮或前序 smoke/中止轮的数字替代。

## 对账（未执行）

harness 在 `analyseDatabase` 之前中止，未执行 `SELECT COUNT(*) FROM track_point` 与 `SELECT COUNT(DISTINCT terminal_id, gps_time) FROM track_point`；MySQL 容器已由 Ryuk 回收，事后无法补查。因此本轮**没有**唯一持久行与发送/ACK 对账结果。

- 18,000 = 100 终端 × 180 点，仍是**理论对照**，不是实测；
- 部分 send/ack 1,148/1,148 只覆盖约 90 s，且没有对应的数据库行数，不能用于对账结论。

## 浏览器缺失帧（未产生）

无 `frames.json`，无法按接入/稳态分别统计缺失帧，也无法复核“outbox 按车合并推送”。该合并推送仍是设计行为（前序 smoke 中缺失集中在接入期，稳态缺失 0），但**本轮没有可引用的缺失帧数据**。

## 失败/重传

- 部分运行：1,148 条发送全部 `attempt=1`，`ACK_SUCCESS` 1,148，非成功 ACK 0；无协议失败、无重传。
- 本轮唯一失败是**内存守卫主动中止**；已如实记录，**未计入成功**，也未计为性能失败。
- 运行日志 `Deadlock found` 0、`OutOfMemory` 0；无 OS kill。

## 逐条 AC

| AC | 要求 | 状态 | 说明/证据定位 |
|---|---|---|---|
| 1 | 记录硬件、网络、版本、配置与时钟测量方法 | **未勾选** | 本轮 metrics 已记录（见下节环境表），但运行被中止、未与一次完成的验收运行绑定；按“未执行项不得勾选”不勾 |
| 2 | 100 终端、10 s、30 min 完整运行并留原始数据 | **未勾选** | 仅运行约 90 s（14:37:12.195 → 14:38:42），无 30 分钟完整数据；部分 send/ack 见上，不构成 AC2 原始数据 |
| 3 | 唯一持久行与发送/ACK 对账，18000 仅作理论对照 | **未勾选** | 未到达 `analyseDatabase`，无 `track_point` COUNT / `COUNT(DISTINCT terminal_id, gps_time)`；18,000 仅理论 |
| 4 | 接收至浏览器应用更新 p95 ≤ 3 s，测量可复现 | **未勾选** | 无 `frames.json`、无延迟样本；未判定；且仅单轮，不满足重复测量 |
| 5 | 失败/重传逐条解释，不计入成功 | **未勾选** | 部分运行无协议失败/重传；唯一失败为内存中止，已记录且不计入成功；但缺少完整 30 分钟失败/重传统计 |
| 6 | 瓶颈修复后重跑，或明确记录未达标 | **未勾选** | gap lock 修复为既有成果，本轮为修复后的降配重跑尝试，但在 1.5 分钟被内存中止，不构成 30 分钟“修复后重跑”；p95 是否达标无法判定 |
| 7 | 不把本次结果外推为容量结论 | **未勾选** | 本轮无可用于外推的验收结果；本记录明确不作任何容量结论 |
| 8 | 原始数据、汇总与证据路径留存 | **未勾选** | 部分原始 send/ack 与 metrics 已留存并给出路径，但不是一次完整 100 终端 30 分钟运行的原始数据与汇总；按 AC2 未达成，AC8 不勾 |

## gap lock 修复与旁证

既有修复（本轮未改）：`OverspeedAlarmParticipant.candidate()` 与 `PositionInvalidAlarmParticipant.candidate()` 先无锁探测候选行，仅当行存在时 `SELECT ... FOR UPDATE`，避免对不存在的唯一键在 REPEATABLE-READ 下取 gap lock 与并发首次 INSERT 的插入意向锁互相等待；`catch (DuplicateKeyException)` 兜底保留。

旁证（**非本轮验收结果**）：2026-11-19 09:55–10:17 前序中止轮在满规模真实负载下运行 22 分 06 秒（/30 分钟，被 OS 内存看门狗 kill）：send 13,506（POSITION 13,206 + REGISTER/AUTH/HEARTBEAT 各 100），ACK 13,506 全部 `ACK_SUCCESS`，位置重传 0，`Deadlock found` 0。本轮 90 s 部分运行同样无死锁，但时长不足以构成“修复后 30 分钟重跑”的证据。

## 环境（来自本轮 `24-round1-metrics.log` 与宿主查询）

| 项 | 值 |
|---|---|
| OS | Windows 11 Pro for Workstations 10.0（构建 26300，`env.os` 记录 10.0 amd64） |
| CPU | 18 逻辑处理器（Core Ultra 5 125H 14C/18T） |
| RAM | 15.65 GiB（`env.memory.total_bytes=16799621120`）；启动时 `env.memory.free_bytes=527519744` |
| JDK | Oracle JDK 21.0.9；Surefire 测试 JVM `-Xmx384m` |
| Spring Boot | 4.1.2 |
| MySQL | `mysql:8.4`，实例 8.4.13；容器 **768 MiB / 2 vCPU**；`--innodb-buffer-pool-size=384M`（`@@GLOBAL.innodb_buffer_pool_size=402653184`）；`--skip-log-bin --innodb-flush-log-at-trx-commit=2 --innodb-log-buffer-size=64M --innodb-doublewrite=OFF --max-connections=100 --performance-schema=OFF`；REPEATABLE-READ；utf8mb4 / utf8mb4_0900_ai_ci；`max_allowed_packet=1048576`；`time_zone=SYSTEM` |
| Redis | `redis:8.4`，8.4.0；容器 **128 MiB / 0.5 vCPU** |
| Docker | Desktop 29.1.3；VM `NCPU=4 MemTotal=8329089024` |
| 前端 | Node 24.11.1、Playwright 1.58.1、Chromium 145.0.7632.6、Vite 8.3.6、Vue 3.5.45、element-plus 2.13.1、ol 10.11.0 |
| 服务端参数 | `vms.monitor.publish-interval-ms=2000`（默认，未覆盖）；WS 重鉴权 1000 ms（默认，未覆盖）；Hikari max=10 minIdle=10 |
| 网络 | simulator、浏览器与 server 均在本机；TCP/HTTP/WS 走 127.0.0.1 回环；底图 fixture 请求被 Playwright 中止，不参与延迟口径 |
| 时钟测量方法 | 同宿主共享操作系统时钟；每帧以 `offset = clientArrival − server_time` 校正，`latency = (render − offset) − payload.receivedAt`；服务端→浏览器单向网络与帧构建计入延迟，客户端→服务端单向折进 offset（保守估计）。`server_time`、`receivedAt`、`gpsTime` 均为带 +08:00 偏移的 OffsetDateTime |
| 并发现场 | 其他项目容器 `plp-dev-mysql-1948`、`pms-milvus-standalone`、`pms_redis`、`pms_mongo`、`pms-etcd`、`pms_minio` 与 ljf/pms dev server 在本轮期间运行；本 agent 未关闭、未重启、未清理这些资源，本机不是独占性能窗口 |

## 清理与边界

- `docker ps -a --filter name=testcontainers` 为空；本轮 `quizzical_ramanujan`（MySQL）、`funny_moore`（Redis）、`testcontainers-ryuk-4a14bb13…` 均已回收。
- 运行结束后无本 worktree 的 java/node/vite/ScenarioSimulator 残留进程；他人进程与容器未动。
- 本轮未新增或修改 migration（`git diff 3ed52f2..HEAD -- server/src/main/resources/db` 为空）；未改产品代码；未加测试后门。
- 默认 Playwright 清单仍为 `46 tests in 15 files`（`24-round1-playwright-list.log`）。

## 恢复条件与待办

再次尝试需同时满足：

1. 宿主可用内存达到门槛并**留出启动余量**：本轮 2.05 GB 的峰值在约 70 s 内跌至 0.33–0.45 GB，说明在当前的宿主基线下，单次 2.0 GB 读数不足以支撑 MySQL/Redis/Spring/Vite/Chromium/simulator 同时启动；需要用户释放内存或等待其他项目任务结束，使可用内存持续高于门槛。
2. 本机无其他项目 Maven/Testcontainers 并发；当前 `plp-dev-mysql-1948`、`pms_*` 容器与 ljf/pms dev server 由用户控制，本 agent 不关闭。
3. 恢复后按同一降配配置（或用户另行授权的配置）完成至少一轮 30 分钟测量；按 AC 还需重复测量，并重新记录环境、核对 Testcontainers/Ryuk 回收、Playwright 清单与 migration 零改动。
4. 若继续使用本降配，必须在证据中保持“降配数据不代表默认部署配置”的声明；不得把本轮或后续降配数字写入论文作为性能达标证据。

## 证据路径

| 证据 | 路径 |
|---|---|
| 本记录 | `.scratch/vehicle-monitoring/evidence/24-verification.md` |
| 构建（两次） | `evidence/24-round1-package.log`、`evidence/24-round1-package-rerun.log` |
| Playwright 清单 | `evidence/24-round1-playwright-list.log` |
| 启动前环境与进程快照 | `evidence/24-round1-preflight-environment.log`、`evidence/logs/24-round1-preflight-snapshot-143156.log` |
| 门槛采样（15 s） | `evidence/24-round1-gate.log`、`evidence/logs/24-round1-gate.log` |
| 内存曲线（60 s） | `evidence/24-round1-memory-watch.log` |
| Maven/运行日志与异常 | `evidence/24-round1-run.log`、`evidence/24-round1-surefire.txt` |
| 本轮 metrics（含降配实际值与运行期内存） | `evidence/24-round1-metrics.log` |
| 部分原始 send/ack（非验收） | `evidence/logs/24-1-simulator/22-20261119-143712-205-seed20261124-{send,ack}.tsv` |
| 本轮 manifest | `evidence/24-round1-manifest.txt` |
| 子进程日志 | `evidence/24-round1-browser.log`、`evidence/24-round1-vite.log`、`evidence/24-round1-simulator.log` |
| 前序 09:55 中止轮（22 分钟旁证） | `evidence/logs/24-aborted-20261119-0955/24-1-simulator/22-20261119-095547-868-seed20261124-{send,ack}.tsv` |

## 限制

- 本轮未完成验收：不对 p95、吞吐、容量或稳定性作任何结论；不把部分 send/ack 或前序 smoke/22 分钟中止轮的数字当作 AC 结果。
- 降配配置下的环境与数据均不代表默认部署配置，不得写入论文作为性能达标证据。
- 仅发起单轮且该轮中止，未满足 AC 的重复测量要求。
