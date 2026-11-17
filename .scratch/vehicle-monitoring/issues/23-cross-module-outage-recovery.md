# 23: 监控中断与恢复综合验收

Status: resolved
Labels: ready-for-agent
Type: task
Blocked by: 11, 14, 15, 17, 18

## What to build

编写脚本化验收，分别中断并恢复 MySQL、Redis 进程、TCP listener 与浏览器 WebSocket。中断脚本记录开始与结束时间，恢复后检查连接、待通知队列与页面状态；MySQL 与 Redis 使用进程级中断，WS 用浏览器断网或关闭连接模拟。验证 ACTIVE 报警与处置记录在应用重启后保留；候选计数与围栏基线重建；启动后 180 秒宽限生效；停机期间车辆运动不被补算为进出或报警，补传只补轨迹；旧位置在页面显示陈旧，不伪装实时。验证 Redis 重启后已提交业务数据不丢，会话可能要求重新登录。重连后快照与报警列表补齐；18 负责的通知重试不重复生成业务事件，允许重复推送且客户端按事件/版本去重；14 的离线判定不产生冗余事件。页面显示断线与数据陈旧状态。每项记录中断方式、持续时长、恢复结果与证据。只做验收编排，不修改判定规则。某项依赖能力尚未实现时记录未执行，不调整规则使其通过。脚本可重复执行，并对同一环境连续跑两轮以排除偶发。

## Spec coverage

主：US88、AC28。次（复验）：AC15 Redis/WS 故障恢复、AC20 离线判定与重启宽限。详见 [spec](../spec.md)。

## Acceptance criteria

- [x] 分别执行 MySQL、Redis、TCP、WS 中断与恢复，均有记录
- [x] ACTIVE 报警与处置不被重启改写，候选与围栏基线重建
- [x] 180 秒启动宽限生效，停机运动不被补算
- [x] 补传只补轨迹，不回退图标、不改变围栏状态
- [x] Redis 重启后业务数据不丢，会话要求重新登录
- [x] 重连补齐快照/报警，业务事件不重复创建；重复推送按事件标识或版本去重
- [x] 页面可见断线与数据陈旧
- [x] 每项记录命令、环境、期望/实际与证据

## Scope boundaries

不改判定阈值与业务规则；不引入集群高可用或多实例 TCP 调度；不做性能（24）与容量（25）；不重新实现 17/18 的能力，只驱动并验证。

## Validation

以脚本对真实 MySQL、Redis、TCP listener 与浏览器执行中断/恢复；每个中断点前后各取一次数据库结果与页面状态，便于对照；记录环境、版本、脚本参数、时间点与证据。未执行项不得勾选。

## Comments

17 号 WS 与 18 号 outbox 仍为主责；本票的 AC15/AC20 复验不转移主责，也不替代各自单票测试。

## Answer

已完成本票验收编排（分支 `feat/ticket-23-outage-recovery`，基线 `4ed9752`），同环境连续两轮全部通过（`round=1/2 result=PASS`、`ALL CHECKS PASSED (2 consecutive rounds)`，锁进程 exit 0）。未修改判定阈值与业务规则，未新增迁移（保留号 V10 未占用），未改动 17/18 的实现。

- 中断与恢复：MySQL `docker stop/start t23-mysql`（25 s / 25 s）、Redis `docker stop/start t23-redis`（7 s / 6 s）、TCP 与应用进程停止后以同一 jar 重启（17 s / 9 s）、浏览器 WebSocket（Playwright 用例 4 关闭页面后重开；用例 5 在浏览器层关闭 `/ws/monitor` 后恢复）。每项的中断方式、开始/结束时间、时长与恢复结果见验证记录第 2 节。
- 重启后事实：报警保持 `ACTIVE|PROCESSING`、处置日志与最后位置保留；`alarm_candidate_state` 与围栏基线重建可用（`OVER|3`、`INSIDE|0`），再送实时超速点不重复创建报警、不产生虚假进出；启动 180 秒宽限内无 OFFLINE，宽限后按 `last_seen_at + 180s` 创建 1 条 `PLATFORM|ACTIVE|PENDING`；停机期间运动未被补算。
- 停机后补传：仅新增 `track_point` 的 `HISTORY` 行；`vehicle_last_position`、候选计数与围栏状态均未回退或改写，无进出事件与新增报警。
- Redis 重启：已提交位置与最后位置不丢（MySQL 为事实源），派生缓存键 `vms:position:*` 重建；本次 `stop/start` 保留了 Redis 内会话数据，旧会话仍返回 200，**未强制重新登录**，按实际行为记录，未虚构。
- 重连：重开后快照与报警列表补齐，REST 查询同一报警 id 不变；outbox 中 `alarm:created`、`alarm:handle`、`position` 各 1 行且 `event_id` 唯一；既有 `monitoring-stream.spec.ts` 继续覆盖按版本去重与断线补齐。
- 页面：断线显示 `实时连接已断开`/`正在重连 x/5`/`实时连接已停止 · 请手动重试`，超过 60 秒显示 `数据陈旧` 与逐车 `定位陈旧`；恢复后 `实时连接已建立`、陈旧标记清除、快照重新取到。
- 未执行/限制：项目无运行期单独关闭 Netty listener 的入口，TCP 中断以停止应用进程实现（HTTP 与 TCP 同时中断），限制已记录；集群高可用、多实例调度与性能（24）、容量（25）不在本票范围。
- 前端 `pnpm typecheck/lint/build` 均通过（lint 0 error、137 warnings 与既有基线一致）。

证据：`.scratch/vehicle-monitoring/evidence/23-verification.md`（逐条 AC 的命令、环境、期望/实际与证据定位），同目录 `23-*` 日志与截图。合并最新集成分支后的复验记录见验证记录第 7 节。

