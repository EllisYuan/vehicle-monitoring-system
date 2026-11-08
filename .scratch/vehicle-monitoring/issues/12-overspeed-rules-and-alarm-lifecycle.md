# 12: 可配置超速报警与持续事件

Status: open
Labels: ready-for-agent
Type: task
Blocked by: 04, 08

## What to build

全局默认与车队覆盖两级超速规则，管理员界面维护阈值（默认 80 km/h），车队覆盖优先，规则参数按版本保存，事件保留当时规则依据。连续 3 个有效实时点超限成立 ACTIVE；连续 3 个有效点 ≤阈值恢复，无额外滞回；候选点间隔 >30 秒重计，但不清除 ACTIVE。数据库以车辆、类型、来源与相关规则维度唯一约束保障同维度最多一个 ACTIVE，恢复按 alarm_id 与当前状态条件更新，避免误关新周期。位置处理在同一事务内写报警、车辆判定 state 与 notification_outbox，提交后才回 ACK；报警快照保存当时关联司机、规则快照与 fleet_id_at_event。新报警提交后即时推送报警列表。候选计数在应用重启后重建，不据停机期间数据补算；规则修改只影响之后的位置判定，不追溯既有事件。只做超速，不做处置、围栏、离线与无效定位报警。本票自带 migration、界面、API、审计与测试。

## Spec coverage

主：AC18（US 55、56、57、64、65），见 [spec](../spec.md#可检查验收场景)。
复验：AC06 关联司机快照（报警固定当时 driver_id）；AC15 事务部分（报警与快照/outbox 原子提交，07 基线）。后续 [13](13-alarm-handling-and-history.md) 的处置基于本票事件。

## Acceptance criteria

- [ ] 全局默认与车队覆盖规则可维护，覆盖优先，版本保存
- [ ] 连续 3 个有效实时点超限成立 ACTIVE，逐点上报不新增
- [ ] 连续 3 个有效点 ≤阈值恢复，按 alarm_id 条件更新
- [ ] 间隔 >30 秒候选重计，ACTIVE 被保留
- [ ] 并发判定下同维度最多一个 ACTIVE，有数据库唯一约束证据
- [ ] 报警保存当时司机、规则快照与 fleet_id_at_event
- [ ] 报警/state/outbox 同事务，提交后 ACK；重启后 ACTIVE 不自动恢复
- [ ] 新报警即时推送到订阅列表
- [ ] 真实 MySQL 并发与回滚测试记录

## Scope boundaries

不含处置闭环（13）、离线/GNSS/终端来源报警（14）、围栏（15）；不实现报警列表处置按钮与导出；不改 07 位置事务结构，只挂接报警写入。后票不代本票测试。

## Validation

向真实 TCP listener 发送受控超速位置序列或经模拟终端上报，核对 ACK 时序、DB 中 alarm、state、outbox、WS 推送与重启行为；并发判断用真实 MySQL 记录实际结果。记录命令、环境、版本、时段、期望与实际，逐条标注状态。

## Comments

（暂无）

## Answer

尚未实施，尚未执行验证。
