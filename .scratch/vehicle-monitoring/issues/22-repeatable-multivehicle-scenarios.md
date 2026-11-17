# 22: 可重复的多车综合演示场景

Status: blocked
Labels: ready-for-agent
Type: task
Blocked by: 09, 14, 15, 16

## What to build

扩展独立模拟器 CLI，以配置驱动固定 seed、路线、车辆数与上报间隔，编排综合场景：正常行驶、停车、超速、GNSS 异常、围栏进出、离线、历史补传、重复与同秒冲突、ACK 丢失、文本与临时跟踪指令。等待 ACK 默认 5 秒、最多 3 次重试，永久拒绝时停止重试并记录原因。输出独立的发送记录与 ACK/应答记录，可用于对账与修复前后比较。相同配置与 seed 重复运行应产生可对照的发送序列。综合场景只做编排，不重新实现各单场景判定逻辑，也不替代 09/14/15/16 各自的单场景测试；后票不为前票补测。运行命令、seed、服务器地址、车数与运行环境写入记录。输出按运行时间与 seed 命名，便于前后对照；单场景失败可单独重跑，不要求整组重来。综合场景同时覆盖多车并发下同终端串行与应答顺序的可观察行为。

## Spec coverage

主：US85、AC31。次（复验）：US77 指令产生可验证应答、AC24 中模拟器行为部分。详见 [spec](../spec.md)。

## Acceptance criteria

- [ ] 相同 seed/路线/车数的计划输入序列一致；按相对时间与终端规范化后可对比，实际调度及 ACK 时序差异单独记录（仅 1 轮端到端运行，未做跨运行比对；同 seed 计划一致性只有 PlanGeneratorTest 单测证据）
- [x] 正常、停车、超速、GNSS 异常、围栏、离线、补传、重复/冲突、ACK 丢失可分别或组合启用（组合由 run1 覆盖；单场景启用由 PlanGeneratorTest、ScenarioConfigTest 覆盖）
- [x] 等待 ACK 5 秒、最多 3 次重试；永久拒绝停止重试并留记录（默认值见 ScenarioConfig；ScenarioRunnerTest 覆盖 ACK 丢失重试、永久拒绝、重试耗尽）
- [x] 发送记录与 ACK 记录独立输出并可对账（run1 send 132 行 / ack 129 行，send_id 对账；ScenarioRunnerTest 双向断言）
- [x] 场景由配置复现，无需修改代码（ScenarioSimulator 只接受 --config/--output-dir；run1 使用 22-config-full.conf）
- [x] 不替代 09/14/15/16 单场景测试，不复制其判定实现（simulator 只做编排；服务端判定未复制）
- [x] 终端断开时场景有界退出，不无限重试（ScenarioRunnerTest.unexpectedDisconnectAbortsTerminalWithoutInfiniteRetry）
- [x] 命令、seed、环境与结果写入证据（22-run1-manifest.txt、22-config-full.conf、22-verification.md §8/§9、run1 CLI/send/ack/summary）

## Scope boundaries

不实现平台侧判定、不新增协议消息；不做 100 终端性能（24）与 30 天容量（25）；不自动注册账户、不申请外部服务；场景失败不静默跳过，必须记录。

## Validation

对运行中的实际 TCP listener 执行综合场景，完整走通注册与鉴权；以真实 MySQL 核对位置、异常、报警与指令结果；重复运行比对发送记录；记录命令、seed、环境、期望/实际与证据路径。未执行项不得勾选。

## Comments

依赖各单场景能力，本票只负责组合编排与可重复性。场景参数取自 spec 的设计容量范围，不新增需求。与 23 中断恢复、24 性能验收分离，不互相替代。

## Answer

实现：`simulator` 增加 JDK-only 场景编排（ScenarioConfig/PlanGenerator/ScenarioRunner/ScenarioSimulator 等，复用 protocol 编解码），配置白名单驱动 seed、路线、终端数、时序与 10 类场景；发送/ACK 分别落 TSV，summary 与 manifest 记录 runId、seed、环境与参数；终端失败非 0 退出并有界终止。

同时修复了验收暴露的服务端并发死锁：`OverspeedAlarmParticipant`、`PositionInvalidAlarmParticipant` 对 `alarm_candidate_state` 的首次写入改为纯 INSERT + `DuplicateKeyException` 回退 UPDATE，已有行只 UPDATE，`candidate()` 带 `FOR UPDATE`。

实际执行：
- 构建：全模块 `mvnw.cmd -B -DskipTests package` BUILD SUCCESS；验收前 `-pl server -DskipTests package` BUILD SUCCESS。
- 测试：simulator 24 项、server 指定 20 项通过（protocol 64 项）。
- 端到端验收：仅 1 轮（run1），seed=20261116，3 终端，stagger=800，ack-timeout=5000/retries=3，全部 10 场景；RUN SUCCESS，3/3 终端 OK，每终端 44 sent/43 acks；3 次 ACK_DROPPED 后重试成功，无 REJECTED/NO_ACK/DISCONNECTED。

限制与未完成：
- **可重复性证据为单轮运行，未完成第二轮**；跨运行发送序列比对未做。
- 验收日志的 SQL 核对步骤没有留下查询结果；run1 的 MySQL 位置、异常、报警与指令数据未在证据中核对，容器已删除未补查。
- 仓库保留的 server 日志只覆盖启动阶段，未覆盖 run1 窗口。
- 未执行单场景端到端运行、浏览器全量、100 终端性能（24）、30 天容量（25）。
- GNSS alarm bit 子能力未实现、未验证（见证据 §6）。

证据：`.scratch/vehicle-monitoring/evidence/22-verification.md`、`22-config-full.conf`、`22-run1-{manifest.txt,summary.tsv,send.tsv,ack.tsv}`、`22-*.log`（顶层已入库）。
