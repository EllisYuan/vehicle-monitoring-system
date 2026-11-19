# 24: 100 终端性能验收

Status: blocked
Labels: ready-for-agent
Type: task
Blocked by: 22

## What to build

在专用环境运行 100 台模拟终端，10 秒间隔，持续 30 分钟，记录服务器硬件、网络、版本（JDK、Spring Boot、MySQL、Redis、前端 build）、JVM 与数据库配置以及时钟测量方法。按实际发送数与成功 ACK 数对账唯一持久行；理论约 18000 点只作对照，不作为实测数。测量接收至浏览器应用更新延迟，取 p95 ≤3 秒，并说明端到端测量法（报文接收时间戳与浏览器渲染时间戳的同源或校正关系）。失败、丢包、重传逐条解释，不混入成功统计。发现瓶颈时修复后重跑，无法修复则明确记录未达标。不等待 23 完成，不以本次结果外推容量上限。负载由 22 的综合场景驱动，车辆与终端一一绑定，数据经真实 TCP 与鉴权流程进入；浏览器使用实际页面而非接口轮询，数据库为真实 MySQL。输出原始数据与汇总，测量至少重复一次，若不达 p95 目标则分析接收、事务、推送与渲染各段并修复后重跑，修复与重跑均留记录。

## Spec coverage

主：US89、AC32。次（复验）：US90 目标、实测、失败分别记录。详见 [spec](../spec.md)。

## Acceptance criteria

- [ ] 记录硬件、网络、版本、配置与时钟测量方法
- [ ] 100 终端、10s、30min 完整运行并留原始数据
- [ ] 唯一持久行与发送/ACK 对账，18000 仅作理论对照
- [ ] 接收至浏览器应用更新 p95 ≤3 秒，测量可复现
- [ ] 失败/重传逐条解释，不计入成功
- [ ] 瓶颈修复后重跑，或明确记录未达标
- [ ] 不把本次结果外推为容量结论
- [ ] 原始数据、汇总与证据路径留存

> 本轮所有 AC 均未勾选：降配单轮在启动约 1.5 分钟后被内存守卫主动中止，数据不完整；未执行/未达标项不得勾选。

## Scope boundaries

不替代 25 号 30 天容量验收；不优化到生产级高可用；不改业务规则与协议；不引入压测专用业务接口或测试后门；不把理论 18000 写成实测；不声称满足全部运营容量。

## Validation

以专用环境实际 TCP 负载、浏览器端测量与真实 MySQL 结果核对；延迟以浏览器实际渲染更新为准，不以服务端发送时间代替；保存原始发送、ACK、持久行与延迟数据；记录环境与命令。未执行项不得勾选。

## Comments

依赖 22 的可重复多车场景作为负载来源；与 25 号容量验收分开执行，结论不互相外推。

## Answer

**受阻（blocked）：降配单轮已于 2026-11-19 14:36:15 启动，14:38:42 被 harness 运行期 0.6 GB 内存守卫主动中止（读到 514,621,440 B），未产生 30 分钟完整数据、p95、两段延迟、对账或缺失帧统计。**

### 本轮实现内容

- `PerfContainers.java`：MySQL 容器内存 1536→768 MiB，`--innodb-buffer-pool-size` 768M→384M，Redis 容器内存 256→128 MiB；其余 MySQL 参数（`--skip-log-bin --innodb-flush-log-at-trx-commit=2 --innodb-log-buffer-size=64M --innodb-doublewrite=OFF --max-connections=100 --performance-schema=OFF`）不变。
- `server/pom.xml`：Surefire `<argLine>-Xmx384m</argLine>`。`-Xmx` 实际不在 `PerfContainers.java`，本轮 JVM 降配只能落在该 pom；它作用于 server 测试 JVM。
- `HundredTerminalPerfHarnessTest.java`：更新 `env.mysql.container_limits`、`env.mysql.container_command`、`env.redis.container_limits` 为降配实际值并标注“用户授权降配”；新增 `env.perf.memory_downscale`；运行期主动中止线由 0.8 GB 放宽为授权的 **0.6 GB**（`LOW_MEMORY_ABORT_BYTES=600_000_000L`），该守卫在本轮实际触发。
- 本轮未改产品代码、未新增/修改 migration、未加测试后门；两个 `*AlarmParticipant.candidate()` 的 gap lock 修复为既有成果，保持不动。
- 构建两次 `mvnw.cmd -B -f <worktree>\pom.xml -DskipTests package` BUILD SUCCESS（27.5 s、19.5 s）；默认 Playwright 清单仍为 46 tests / 15 files。

### 本轮降配项清单

| 项 | 默认值 | 本轮实际值 |
|---|---|---|
| MySQL 容器内存 | 1536 MiB | **768 MiB** |
| MySQL `--innodb-buffer-pool-size` | 768M | **384M**（`@@GLOBAL.innodb_buffer_pool_size=402653184`） |
| Redis 容器内存 | 256 MiB | **128 MiB** |
| Surefire JVM `-Xmx` | 512m | **384m**（`env.jvm.args=[-Xmx384m]`） |

`vms.monitor.publish-interval-ms=2000` 保持默认，未覆盖。

### 单轮实测数字（含中止）

- **启动门槛**：14:31:30 读到 2.03 GB 瞬时峰值，26 秒后回落 1.72 GB，按 ≥2.0 GB 门槛未启动；14:32:41 起每 15 s 采样；14:36:15 读到 2.05 GB，经全局锁启动。
- **中止**：14:37:42 harness 记录 0.83 GB 警告；14:38:42 读到 **514,621,440 B（0.514 GB，十进制）< 0.6 GB**，抛出 `IllegalStateException` 主动停止本轮（**非 OS 看门狗 kill**）。外部 60 s 采样运行期最低 **0.33 GB（14:37:24）**，14:38:25 为 0.45 GB，中止后 14:39:27 回升至 1.15 GB。完整曲线见 `24-round1-memory-watch.log`。
- **部分 send/ack（非验收）**：1,148 行 send = POSITION 848 + REGISTER/AUTH/HEARTBEAT 各 100；ack 1,148 行全部 `ACK_SUCCESS`、`result=0`；全部 `attempt=1`，位置重传 0。无 summary TSV（simulator 被停止）、无 `frames.json`、无数据库对账（未到达 `analyseDatabase`，MySQL 容器已由 Ryuk 回收）。18,000 仅为理论对照。
- **两段延迟**：接入阶段与稳态 30 分钟**均未产生**；AC4 未判定。没有 p95、p50/p90/p99/max 或样本数可报告。
- **失败/重传**：部分运行无协议失败、无非成功 ACK、无重传；唯一失败是内存守卫中止，已如实记录且**不计入成功**。运行日志 `Deadlock found` 0、`OutOfMemory` 0。

### 必须明确的限制

- **仅单轮，未满足 AC 的重复测量要求**；且该单轮本身也未完成 30 分钟，未产生任何可判定的验收结果。
- **降配导致数据不代表默认配置**：本轮降配下的环境与部分数据不代表默认部署配置的产品性能，不得作为性能达标证据，不得写入论文。
- 不对 p95、吞吐、容量或稳定性作任何结论；不由本轮或前序 smoke/22 分钟中止轮外推容量。

### gap lock 修复与旁证

两个 `*AlarmParticipant.candidate()` 先无锁探测、仅当候选行存在时 `SELECT ... FOR UPDATE`，避免对不存在的唯一键取 gap lock 与并发首次 INSERT 的插入意向锁互相等待；`catch (DuplicateKeyException)` 兜底保留。前序 2026-11-19 09:55–10:17 中止轮在真实满规模负载下运行 22 分 06 秒：send 13,506、ACK 13,506 全部 `ACK_SUCCESS`、位置重传 0、`Deadlock found` 0，是该修复有效的**旁证**。本轮 90 s 部分运行同样无死锁，但时长不足以构成“修复后 30 分钟重跑”的验收证据。

### 证据路径

- `.scratch/vehicle-monitoring/evidence/24-verification.md`
- `evidence/24-round1-package.log`、`24-round1-package-rerun.log`、`24-round1-playwright-list.log`
- `evidence/24-round1-preflight-environment.log`、`24-round1-gate.log`、`24-round1-memory-watch.log`
- `evidence/24-round1-run.log`、`24-round1-surefire.txt`、`24-round1-metrics.log`
- 部分原始 send/ack：`evidence/logs/24-1-simulator/22-20261119-143712-205-seed20261124-{send,ack}.tsv`（非验收）
- 前序 22 分钟中止轮：`evidence/logs/24-aborted-20261119-0955/24-1-simulator/22-20261119-095547-868-seed20261124-{send,ack}.tsv`

### 恢复条件

需要宿主可用内存达到门槛并留出启动余量（本轮 2.05 GB 峰值在约 70 s 内跌至 0.33–0.45 GB），且无其他项目 Maven/Testcontainers 并发；本 agent 未关闭用户应用或他人容器（当前 `plp-dev-mysql-1948`、`pms_*` 与 ljf/pms dev server 仍在运行）。恢复后需完成至少一轮 30 分钟测量，并按 AC 重复测量；继续使用降配时必须保留“不代表默认部署配置”的声明。
