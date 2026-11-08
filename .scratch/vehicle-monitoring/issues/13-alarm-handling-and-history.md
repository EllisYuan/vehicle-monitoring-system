# 13: 报警处置闭环

Status: open
Labels: ready-for-agent
Type: task
Blocked by: 12

## What to build

报警列表与详情展示报警时间、位置、当时关联司机、规则快照与处置历史。处置状态 PENDING→PROCESSING→HANDLED/IGNORED，登记操作者、时间与备注；condition_state（ACTIVE/RECOVERED/EVENT）与 handle_status 独立，异常恢复不自动处置，人工处置不自动恢复，终态不重新打开，新异常周期建新事件。处置历史同时保留异常条件变化与人工处置变化，二者分开记录；详情展示当时规则快照以便核查判定依据。同一报警的并发处置按当前状态条件更新，避免覆盖他人结果。关联关系采用报警当时的司机与车队，换关联不回写历史；车队长只读授权范围报警与处置历史，司机电话中间四位掩码。处置写 alarm_handle_log 与 notification_outbox 同事务，提交后即时推送。列表分别展示条件状态与处置状态两列，避免混同。本票自带页面、API、审计与测试。

## Spec coverage

主：AC19（US 57、61、62、63），见 [spec](../spec.md#可检查验收场景)。
复验：AC06 历史关系（报警当时司机不被换关联改写）；AC02 车队长只读与字段脱敏。事件来源为 [12](12-overspeed-rules-and-alarm-lifecycle.md)；报警列表推送的撤权与重连改正见 [17](17-websocket-revocation-and-resync.md)。

## Acceptance criteria

- [ ] PENDING→PROCESSING→HANDLED/IGNORED 迁移可用，非法迁移被拒绝
- [ ] 每次处置记录操作者、时间、备注与状态变化
- [ ] condition_state 与 handle_status 独立，恢复不改处置，处置不改恢复
- [ ] 终态不可重开，新周期生成新事件
- [ ] 车队长只读授权范围报警与处置历史
- [ ] 详情显示当时关联司机，换关联后历史不回写
- [ ] 车队长司机电话中间四位掩码
- [ ] 处置日志与 outbox 同事务，提交后即时推送
- [ ] 真实 HTTP 权限矩阵测试与数据库核对记录

## Scope boundaries

不实现报警规则配置与超速判定（12）、离线与围栏报警（14、15）、Excel 导出（20）、WS 撤权与重连（17）；不做人工归档、终态重开或自动处置。后票不代本票测试。

## Validation

以真实 HTTP 请求验证三角色权限矩阵与并发处置，核对 alarm 与 alarm_handle_log 的实际状态；用浏览器操作车队长账号验证只读与掩码显示；用 WS 订阅观察处置提交后推送。记录命令、环境、版本、时段与期望实际结果，逐条标注状态。

## Comments

（暂无）

## Answer

尚未实施，尚未执行验证。
