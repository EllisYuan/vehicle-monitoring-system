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

## Scope boundaries

不替代 25 号 30 天容量验收；不优化到生产级高可用；不改业务规则与协议；不引入压测专用业务接口或测试后门；不把理论 18000 写成实测；不声称满足全部运营容量。

## Validation

以专用环境实际 TCP 负载、浏览器端测量与真实 MySQL 结果核对；延迟以浏览器实际渲染更新为准，不以服务端发送时间代替；保存原始发送、ACK、持久行与延迟数据；记录环境与命令。未执行项不得勾选。

## Comments

依赖 22 的可重复多车场景作为负载来源；与 25 号容量验收分开执行，结论不互相外推。

## Answer

**受阻（blocked）：两轮 30 分钟满规模测量未执行，未产生任何验收数字。**

已就绪（前序检查点 + 本次补强）：
- simulator 稳态负载（`ScenarioType.STEADY`、`ScenarioConfig.durationMs`、`PlanGenerator.steady`，100 终端 × 10 s × 30 min）。
- perf harness（`HundredTerminalPerfHarnessTest`、`PerfContainers`、`web/perf/hundred-terminal-latency.mjs`）：真实 TCP 注册/鉴权/上报、真实 MySQL/Redis Testcontainers、真实浏览器登录与 `/ws/monitor` 订阅，延迟以浏览器渲染更新为准。
- 本次补强：接入阶段（`scenario_start` 所在秒 + 60 s）与稳态段分开统计 p50/p90/p95/p99/max，AC4 只按稳态 p95 判定并同时报告接入段；缺失帧按接入/稳态分别计数；harness 每分钟记录宿主可用内存（<1.2 GB 警告、<0.8 GB 主动中止本轮），外部另有 `24-<round>-memory-watch.log`（每 120 s，含容器名）。
- `mvnw.cmd -B -f <worktree>\pom.xml -DskipTests package` 四模块 BUILD SUCCESS（25.7 s）；默认 Playwright 清单仍为 `46 tests in 15 files`；无 migration、本次无产品代码改动。

未执行原因：启动前内存采样 **0.43–2.78 GB FreePhysical（Available 0.46–2.78 GB），全部低于用户规定的 3.5 GB 门槛；最低点 0.43 GB（10:57:01）远低于 harness 的 0.8 GB 主动中止线**；同时其他项目 ljf/park-leasing-platform 的 Maven/Testcontainers 在 10:39–11:04 持续并发运行（两个 worktree，竞争 Maven 最多 5 个；容器 `thirsty_panini`、`eager_goldberg`、`romantic_lalande`、`blissful_diffie`、`optimistic_solomon`、`suspicious_jemison`、`plp-dev-mysql-1948`、Ryuk），本机不是独占性能窗口。按用户指令停止，未启动任何 30 分钟测量。

两轮实测、p95 与接入/稳态分段：**未执行**。失败/重传：**未产生**。前序旁证（非验收，不作为 AC 结果）：2026-11-19 09:55–10:17 中止轮 send 13,506（POSITION 13,206 + REGISTER/AUTH/HEARTBEAT 各 100），ACK 13,506 全部 `ACK_SUCCESS`，位置重传 0，`Deadlock found` 0；稳态进行 22 分 06 秒 / 30 分钟时被内存看门狗 kill。smoke 稳态缺失 0 帧。

gap lock 修复：两个 `*AlarmParticipant.candidate()` 先无锁探测、仅当候选行存在时 `SELECT ... FOR UPDATE`，`catch (DuplicateKeyException)` 兜底保留。上述 22 分钟真实负载无死锁是修复有效性的旁证，但“修复后 30 分钟重跑”未完成。

限制：不对 p95、吞吐、容量或稳定性作任何结论，不由本次或前序 smoke 外推容量；恢复后需重新记录环境并完成两轮测量。

证据：`24-verification.md`、`evidence/24-package-rerun.log`、`evidence/24-playwright-list.log`、`evidence/24-preflight-environment.log`、`evidence/24-preflight-memory-watch.log`；前序原始数据在 `evidence/logs/24-1-simulator/`。
