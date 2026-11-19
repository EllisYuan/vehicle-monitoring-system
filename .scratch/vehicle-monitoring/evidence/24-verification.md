# 任务 24 验证记录：100 终端性能验收

状态：**受阻（blocked），两轮满规模测量未执行**。2026-11-19 启动前按内存纪律读取宿主可用内存，观察窗内采样全部低于 3.5 GB 门槛（最低 0.43 GB，远低于 harness 0.8 GB 中止线），且其他项目（ljf/park-leasing-platform）的 Maven/Testcontainers 在本机并发运行，专用窗口不成立。按用户指令停止，**未启动任何 30 分钟测量，未产生任何 p95、对账或两轮验收数字**。

## 结论摘要

| 项 | 结果 |
|---|---|
| 第 1 轮（100 终端 × 10 s × 30 min） | **未执行** |
| 第 2 轮（重复测量） | **未执行** |
| 启动前宿主可用内存门槛 | 3.5 GB |
| 本次实测可用内存范围（FreePhysical / Available） | **0.43–2.78 GB**，全部低于门槛；最低 0.43 GB（10:57:01，远低于 harness 0.8 GB 中止线） |
| 并发负载 | 10:39:03–11:04:12 其他项目 Maven + Testcontainers 持续运行（两个 worktree，竞争 Maven 最多 5 个；PID 27392/27224/34520/31196 等；容器 `thirsty_panini`、`eager_goldberg`、`romantic_lalande`、`blissful_diffie`、`optimistic_solomon`、`suspicious_jemison`、`plp-dev-mysql-1948`、Ryuk），非独占窗口 |
| 本票代码就绪度 | 分段统计与内存守卫已加入 harness；四模块 `-DskipTests package` BUILD SUCCESS；默认 Playwright 清单 46 tests / 15 files 不变；无 migration、无产品代码改动 |
| 开发库/迁移 | 未新增或修改 migration（`git diff 3ed52f2..HEAD -- server/src/main/resources/db` 为空） |

## 基线与范围

| 项目 | 值 |
|---|---|
| 分支 | `feat/ticket-24-hundred-terminal-perf` |
| worktree | `C:\Users\YuanS\Documents\paper\ja\vehicle-monitoring-system\.claude\worktrees\agent-ab1377cbd6ba02a0f` |
| 起点 HEAD | `2ad3621`（`wip: 任务24性能harness与候选行gap lock死锁修复检查点`），工作区干净 |
| 集成分支 | `integration/tickets-08-27` tip `3ed52f2`，已核验为 HEAD 祖先 |
| 范围依据 | ticket `24-hundred-terminal-performance.md`、spec AC32/US89（次：US90）、`docs/design/系统设计方案.md` 性能目标节 |

## 本次完成（属于就绪工作，不是验收结果）

1. **延迟分段口径实现**（`server/src/test/java/com/vms/perf/HundredTerminalPerfHarnessTest.java`）：
   - 从 simulator manifest 读取 `scenario_start`，以 `scenario_start` 所在秒 + 60 秒为界，按位置点的 `gps_time` 将帧划分为**接入阶段**与**稳态段**；
   - 两段分别统计 p50/p90/p95/p99/max、样本数，以及 outbox 等待、transport、render 分段时间；AC4 判定改用**稳态段 p95**，接入阶段实际值同时输出；
   - 缺失帧按接入/稳态分别计数（`browser.frames_missing_ramp` / `browser.frames_missing_steady`），保留“outbox 按车合并推送”的解释；
   - 全量计划的分段期望值由计划推导：按该口径典型为接入期约 550 点、稳态约 17,450 点（随 `scenario_start` 的秒内小数不同上下浮动数点），是理论 18,000 的对照，不是实测数。
2. **运行期内存守卫**：harness 每分钟记录宿主可用内存（`runtime.memory.free_bytes_at_<HHmmss>`），低于 1.2 GB 记警告，低于 0.8 GB 主动抛出并停止本轮，保留已写出的 metrics；外部另有每 120 秒采样的 `24-<round>-memory-watch.log`。
3. **构建验证**：`mvnw.cmd -B -f <worktree>\pom.xml -DskipTests package` 经全局锁执行，四模块 BUILD SUCCESS（第二次 25.7 s；日志 `evidence/24-package-rerun.log`）。
4. **清单核对**：`pnpm --dir web exec playwright test --list` 输出 `Total: 46 tests in 15 files`（日志 `evidence/24-playwright-list.log`）；`web/` 无改动。
5. **产品代码**：本次未进一步修改；前序检查点的两个 `*AlarmParticipant.candidate()` 先无锁探测、存在才 `FOR UPDATE` 的修复保持不变。

## 未执行项与原因

计划执行的两条命令（每轮一条，经锁）均**未启动**：

```text
mvnw.cmd -B -f <worktree>\pom.xml -pl server -am \
  -Dtest=HundredTerminalPerfHarnessTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dvms.perf=true -Dvms.perf.round=1 test
# 第 2 轮：-Dvms.perf.round=2
```

原因（全部有现场记录，见 `evidence/24-preflight-environment.log` 与 `evidence/24-preflight-memory-watch.log`）：

- 启动前采样 0.43–2.78 GB，**全部低于 3.5 GB 门槛**（最低点 0.43 GB 远低于 harness 0.8 GB 中止线）；上次满规模第 1 轮正是因宿主内存不足被看门狗在稳态 22/30 分钟 kill，用户明确要求低于 3.5 GB 不启动；
- 其他项目 `ljf/park-leasing-platform` 的 Maven/Testcontainers 于 10:39:03、10:43:10、10:49:05 连续运行（`Ticket23SchemaTest`、`EnterpriseMembersProfileChangesHttpTest` 与全量 `test`，两个 worktree；容器 `thirsty_panini`、`eager_goldberg`、`romantic_lalande`、`plp-dev-mysql-1948`、Ryuk），本机不是 24 号要求的独占性能窗口；并发 CPU/磁盘/容器会污染 p95 与对账口径；
- 用户要求不关闭、不影响其浏览器与其他应用，不清理他人容器，因此无法在本轮内人为恢复门槛。

## 逐条 AC

| AC | 要求 | 状态 | 说明/证据定位 |
|---|---|---|---|
| 1 | 记录硬件、网络、版本、配置与时钟测量方法 | **未勾选（未执行）** | 环境项已由前序中止轮 `evidence/logs/24-1-metrics.log` 记录，但未与一次完成的验收运行绑定，不作为达成；版本与时钟方法见下节参考 |
| 2 | 100 终端、10 s、30 min 完整运行并留原始数据 | **未勾选（未执行）** | 两轮均未启动；无本轮 send/ack TSV、无 frames.json |
| 3 | 唯一持久行与发送/ACK 对账，18000 仅作理论对照 | **未勾选（未执行）** | 无本轮数据库结果；18,000 仅作为计划理论值（按分段口径典型为接入约 550 + 稳态约 17,450，随启动秒内小数略变） |
| 4 | 接收至浏览器应用更新 p95 ≤ 3 s，测量可复现 | **未勾选（未执行）** | 无本轮延迟样本；harness 已实现接入/稳态分段与稳态 p95 判定，但未运行 |
| 5 | 失败/重传逐条解释，不计入成功 | **未勾选（未执行）** | 无本轮失败/重传记录 |
| 6 | 瓶颈修复后重跑，或明确记录未达标 | **未勾选（未执行）** | gap lock 修复与前序 22 分钟无死锁旁证见下节，但“修复后重跑”的 30 分钟验收未执行 |
| 7 | 不把本次结果外推为容量结论 | **未勾选** | 本次无结果可外推；不据此作任何容量结论 |
| 8 | 原始数据、汇总与证据路径留存 | **未勾选（未执行）** | 现有仅为就绪与受阻证据，非验收原始数据 |

## 环境参考（来自 2026-11-19 09:55 中止轮 `24-1-metrics.log`，非本轮验收）

| 项 | 值 |
|---|---|
| OS / CPU / RAM | Windows 11 Pro for Workstations 10.0.26200（`os.version=10.0`）、18 逻辑处理器（Core Ultra 5 125H 14C/18T）、15.65 GiB |
| JDK | Oracle JDK 21.0.9；Surefire `-Xmx512m` |
| Spring Boot | 4.1.2 |
| MySQL | `mysql:8.4`，实例 8.4.13；容器 1536 MiB / 2 vCPU；`--skip-log-bin --innodb-buffer-pool-size=768M --innodb-flush-log-at-trx-commit=2 --innodb-log-buffer-size=64M --innodb-doublewrite=OFF --max-connections=100 --performance-schema=OFF`；REPEATABLE-READ；`max_allowed_packet=1 MiB`；session `time_zone=SYSTEM`（JVM/浏览器同为 UTC+8） |
| Redis | `redis:8.4`，8.4.0，256 MiB / 0.5 vCPU |
| 前端 | Node 24.11.1、Playwright 1.58.1、Chromium 145.0.7632.6、Vite 8.3.6、Vue 3.5.45、element-plus 2.13.1、ol 10.11.0 |
| 服务端参数 | `vms.monitor.publish-interval-ms=2000`（默认，未覆盖）；WS 重鉴权 1000 ms（默认，未覆盖） |
| 网络 | simulator、浏览器与 server 均在本机，TCP/HTTP/WS 走 127.0.0.1 回环；底图 fixture 请求被 Playwright 中止，不参与延迟口径 |
| 时钟测量方法 | 同宿主共享操作系统时钟；每帧以 `offset = clientArrival − server_time` 校正，`latency = (render − offset) − payload.receivedAt`；服务端→浏览器单向网络与帧构建计入延迟，客户端→服务端单向折进 offset（保守估计）。`server_time`、`receivedAt`、`gpsTime` 均为带 +08:00 偏移的 OffsetDateTime |

## 前序旁证（非验收结果，不得作为 AC 达成依据）

- **2026-11-19 09:55–10:17 中止轮**（满规模第 1 轮，稳态进行 22 分 06 秒 / 30 分钟时被系统看门狗以内存不足 kill）：send 13,506 = POSITION 13,206 + REGISTER 100 + AUTH 100 + HEARTBEAT 100；ACK 13,506 全部 `ACK_SUCCESS`；位置重传 0；`Deadlock found` 0 次。原始 send/ack TSV 见 `evidence/logs/24-1-simulator/22-20261119-095547-868-seed20261124-{send,ack}.tsv`。**没有延迟汇总，不能作为 AC4 证据**。
- **smoke（2026-11-18，120 s，非验收规模）**：1,500/1,500 持久化与 ACK；浏览器少 87 帧，全部发生在接入错峰阶段（59 车缺首点、28 车缺第二点），稳态缺失 0；稳态 outbox 等待 p95 约 2.0–2.4 s、transport p95 约 22 ms、render p95 约 23 ms；整体 p95 6.3 s 受接入阶段首波排队拉高。见 `evidence/logs/24-smoke-*` 与协作笔记。
- **gap lock 修复**：两个 `*AlarmParticipant.candidate()` 先无锁探测、仅当候选行存在时 `SELECT ... FOR UPDATE`，`catch (DuplicateKeyException)` 兜底保留。上述 22 分钟真实负载是“无死锁”的旁证，但不是本 AC 要求的 30 分钟完整验收。

## 内存曲线摘要（启动前）

| 时间 | FreePhysical (GB) | Available (GB) | 备注 |
|---|---|---|---|
| 10:36:19 | 2.62 | 2.60 | 其他项目 Maven 尚未启动 |
| 10:36:51 | 2.40 | 2.39 | |
| 10:37:22 | 1.82 | 1.81 | |
| 10:37:54 | 2.77 | 2.75 | |
| 10:38:25 | 1.87 | 1.81 | |
| 10:38:56 | **0.85** | 0.86 | 早期低点 |
| 10:39:28 | 1.98 | 1.88 | 10:39:03 其他项目 Maven 启动 |
| 10:39:59 | 1.78 | 1.76 | |
| 10:40:31 | 1.48 | 1.43 | |
| 10:41:02 | 2.47 | 2.61 | 竞争 Maven 结束 |
| 10:41:34 | 2.78 | 2.78 | 采样窗口最高 |
| 10:42:21 | 2.09 | 2.08 | |
| 10:43:23 | 1.83 | 1.82 | 10:43:10 其他项目再次启动（MySQL + Ryuk） |
| 10:44:24 | 2.03 | 2.04 | |
| 10:45:26 | 2.05 | 2.14 | |
| 10:46:27 | 1.16 | 1.13 | vmmemWSL 1.36 GB |
| 10:47:28 | 1.72 | 1.68 | |
| 10:48:30 | 0.94 | 1.25 | 其他项目再次启动 Maven（两个 java） |
| 10:49:51 | 0.98 | **0.88** | 两个 worktree 并发测试（`EnterpriseMembersProfileChangesHttpTest` 与全量 `test`），容器 `eager_goldberg` + Ryuk |
| 10:50:52 | 1.36 | 1.37 | 3 个竞争 Maven；容器 `romantic_lalande` + Ryuk + `plp-dev-mysql-1948` |
| 10:51:53 | 0.91 | 0.78 | 竞争负载持续 |
| 10:52:55 | 0.55 | 0.54 | **低于 harness 的 0.8 GB 主动中止线** |
| 10:53:56 | 2.10 | 2.08 | 竞争 Maven 降至 1 个；`plp-dev-mysql-1948` 持续 |
| 10:54:58 | 2.09 | 2.09 | |
| 10:55:59 | 2.20 | 2.16 | |
| 10:57:01 | **0.43** | **0.46** | **观察窗最低点**，仍在 0.8 GB 中止线以下 |

判定：启动前观察窗内 **0.43–2.78 GB FreePhysical（Available 0.46–2.78 GB），全部低于 3.5 GB 门槛**；最低点 0.43 GB 远低于 harness 的 0.8 GB 主动中止线，说明此时启动会直接触发中止或重现看门狗 kill。按用户指令停止。完整逐样本记录见 `evidence/24-preflight-memory-watch.log`。

## 恢复条件与待执行命令

恢复本票需同时满足：

1. 启动前 `FreePhysicalMemory ≥ 3.5 GB`（含完整的 10 分钟观察，不只看单点；需由用户释放宿主内存，本 agent 不关闭用户浏览器与其他应用）；
2. 本机无其他项目 Maven/Testcontainers 并发（独占窗口，其他项目测试结束且不会在测量期间返回）；
3. 保留 `publish-interval-ms=2000` 默认值；如改动任何 `vms.*` 参数，逐项记录理由。

随后每轮一条命令（经 `with-test-lock.ps1`，必须带 `-f`）：

```text
mvnw.cmd -B -f <worktree>\pom.xml -pl server -am \
  -Dtest=HundredTerminalPerfHarnessTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dvms.perf=true -Dvms.perf.round=1 test
# 轮 2 把 -Dvms.perf.round=1 改为 =2
```

运行中按 `24-<round>-memory-watch.log` 每 2–3 分钟核对；可用内存 < 1.2 GB 警告、< 0.8 GB 由 harness 主动中止。两轮完成后按接入/稳态分段填写延迟表、对账表与 AC。

## 证据路径

| 证据 | 路径 |
|---|---|
| 本记录 | `.scratch/vehicle-monitoring/evidence/24-verification.md` |
| 构建日志 | `.scratch/vehicle-monitoring/evidence/24-package-rerun.log`（顶层副本） |
| Playwright 清单 | `.scratch/vehicle-monitoring/evidence/24-playwright-list.log` |
| 启动前环境与进程 | `.scratch/vehicle-monitoring/evidence/24-preflight-environment.log` |
| 启动前内存采样 | `.scratch/vehicle-monitoring/evidence/24-preflight-memory-watch.log` |
| 前序中止轮 metrics | `.scratch/vehicle-monitoring/evidence/logs/24-1-metrics.log` |
| 前序中止轮原始 send/ack | `.scratch/vehicle-monitoring/evidence/logs/24-1-simulator/22-20261119-095547-868-seed20261124-*.tsv` |
| 前序 smoke | `.scratch/vehicle-monitoring/evidence/logs/24-smoke-*` |

## 限制

- 本次未完成验收：不对 p95、吞吐、容量或稳定性作任何结论，也不把 smoke/22 分钟中止轮的数字当作 AC 结果。
- 环境记录来自 09:55 中止轮，与恢复后的验收轮需重新记录并核对（尤其内存与容器版本）。
- 恢复后仍需检查 Testcontainers/Ryuk 是否回收、默认 Playwright 清单是否仍为 46/15、migration 是否保持零改动。
