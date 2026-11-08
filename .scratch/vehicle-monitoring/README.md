# 车辆监控开发任务

用户已批准本索引中的 27 个任务及依赖关系。任务按依赖顺序编号，每个文件独立保存交付行为、验收条件、范围和验证记录。上级 [Spec](spec.md) 保持不变，业务设计与术语仍适用。

所有任务初始为 `Status: open`、`Labels: ready-for-agent`。标签表示任务定义可领取，不表示依赖已完成；领取前检查全部 `Blocked by` 指向任务均为 `resolved`。执行时按仓库规则改为 `claimed`，记录实际结果后再 `resolved`；外部条件未满足时标 `blocked` 并说明原因。

当前进度：01 已实现并验证，状态 `resolved`；02 与 05 的依赖已就绪，可领取。

## 任务索引

| 编号 | 任务 | 直接 Blocked by | 主要 spec 验收 |
|---|---|---|---|
| 01 | [空环境启动与管理员登录](issues/01-bootstrap-and-login.md) | None | AC01、AC03 登录基础 |
| 02 | [账号角色与车队授权](issues/02-accounts-and-fleet-access.md) | 01 | AC02/03 HTTP 部分、AC04 |
| 03 | [车辆、终端档案与历史绑定](issues/03-vehicle-terminal-bindings.md) | 02 | AC05 终端绑定与转队 |
| 04 | [司机档案与关联历史](issues/04-drivers-and-association-history.md) | 03 | AC05 司机关联 |
| 05 | [JT/T 808-2019 独立报文验证](issues/05-jt808-independent-fixtures.md) | 01 | AC07 |
| 06 | [模拟终端接入与通信状态](issues/06-terminal-registration-and-sessions.md) | 03、05 | AC08、AC17 接入 |
| 07 | [可靠位置上报到实时地图](issues/07-durable-position-to-live-map.md) | 06 | AC09、AC15 事务基础 |
| 08 | [重复、冲突与定位质量处理](issues/08-position-idempotency-and-quality.md) | 07 | AC10/11/13、AC14 NULL 部分 |
| 09 | [断线补传与历史归属](issues/09-batch-backfill-and-history-ownership.md) | 08 | AC06 车辆车队、AC12 |
| 10 | [单车轨迹查询与回放](issues/10-track-query-and-playback.md) | 08 | AC22/23 |
| 11 | [30 天保留与分区维护](issues/11-track-retention-and-partitions.md) | 07 | AC27 |
| 12 | [可配置超速报警与持续事件](issues/12-overspeed-rules-and-alarm-lifecycle.md) | 04、08 | AC18、AC06 报警司机 |
| 13 | [报警处置闭环](issues/13-alarm-handling-and-history.md) | 12 | AC19、AC06 历史详情复验 |
| 14 | [离线、定位与终端来源报警](issues/14-offline-gnss-and-terminal-alarms.md) | 12 | AC14 报警、AC20 |
| 15 | [围栏绘制与进出事件](issues/15-geofence-drawing-and-events.md) | 12 | AC21 |
| 16 | [文本与临时跟踪指令](issues/16-text-and-tracking-commands.md) | 07 | AC24、AC17 指令复验 |
| 17 | [实时订阅撤权与重连校正](issues/17-websocket-revocation-and-resync.md) | 07 | AC03 WS、AC29 |
| 18 | [通知重试与缓存故障恢复](issues/18-outbox-retries-and-cache-recovery.md) | 13 | AC16、AC15 恢复复验 |
| 19 | [有权限的监控统计](issues/19-authorized-monitoring-statistics.md) | 13 | AC25 |
| 20 | [三类 Excel 导出](issues/20-limited-redacted-excel-exports.md) | 13 | AC26、AC02 导出复验 |
| 21 | [管理员审计与运行记录查询](issues/21-audit-and-operation-log-viewer.md) | 03 | AC02 审计权限、US12 |
| 22 | [可重复的多车综合演示场景](issues/22-repeatable-multivehicle-scenarios.md) | 09、14、15、16 | AC31 |
| 23 | [监控中断与恢复综合验收](issues/23-cross-module-outage-recovery.md) | 11、14、15、17、18 | AC28、AC15/20 复验 |
| 24 | [100 终端性能验收](issues/24-hundred-terminal-performance.md) | 22 | AC32 |
| 25 | [30 天量级轨迹容量验收](issues/25-thirty-day-track-capacity.md) | 10、11 | AC33 |
| 26 | [真实天地图接入验证](issues/26-tianditu-live-basemap-validation.md) | 07 | AC30 |
| 27 | [空环境部署与最终交付验收](issues/27-clean-deployment-and-delivery.md) | 19、20、21、23、24、25、26 | AC34、全部 AC 综合回归 |

## 领取与交付边界

1. 01 已完成，02 和 05 可以分别推进。依赖就绪表示业务条件具备，多任务同时修改共享工程仍需协调，不代表可以无隔离地同时写相同文件。
2. 每项任务在自身范围内交付必要的 migration、接口、界面或 CLI、审计与测试。05 为独立协议验证，无需无意义地添加页面；后续综合任务不替代前序单功能测试。
3. 07 从首次发布 TCP/WS 能力起就需要提交后 ACK、事务内 outbox 和服务端权限，17/18 是深化与复验；不能先上线不安全、不可靠的临时版本。
4. 同一 AC 可跨能力分段验收：02 不因 WS/导出尚未实现而无法关闭；08 验证无效位置持久化，14 验证异常成立/恢复，10 验证回放断点，27 综合复验。
5. 09 先验证车辆/车队归属；12/13 加入司机报警关联快照后复验 AC06。10 在报警能力出现后需复验重要事件关联点，27 汇总执行。
6. 26 的账户、条款或网络条件可以独立阻塞；核心位置开发不等待 key，但 27 必须包含真实地图验证，不能以替身底图判 AC30 通过。
7. 审计写入和基础恢复随各功能交付，21 提供查询，23 验证跨模块故障。除 01 外，其余运行、性能与容量结果均未执行，保持验收 checkbox 未勾选。
8. 本次仅发布任务定义，不领取任务、不编写业务代码、不安装依赖、不修改原始 Word、不执行 Git commit/push。

## 完成条件

单项任务仅在本项范围验收实际执行、有可追溯结果、必需阻塞解除后才能标 `resolved`。最终 27 的依赖闭包包含其他全部 26 项；所有 spec AC 的最终结果必须在交付记录中明确列示，不能将 `ready-for-agent` 标签理解为测试通过。
