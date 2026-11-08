# 18: 通知重试与缓存故障恢复

Status: open
Labels: ready-for-agent
Type: task
Blocked by: 13

## What to build

在 07 的基础 worker 上深化 notification_outbox：event_id 唯一，状态 PENDING、RETRY、DONE、DEAD；最多 10 次后 DEAD 并产生可见告警，可审计重投；DONE 7 天清理，DEAD 不自动删除。位置通知为每车可合并的最新版本，worker 清理必须限定其已读版本，不误清并发更高版本；报警与处置为独立事件，不合并且不丢失。worker 崩溃后恢复不重复已提交业务事件。Redis 不可用或重启后最新位置快照可重建，不以旧缓存伪造终端连接或在线状态。故障注入验证成功 ACK 不依赖缓存。缓存恢复只重建派生数据，不恢复终端连接权威，不改变已提交事务结果。outbox 事件与业务提交同事务产生，worker 只消费已提交事件；重试使用固定上限与固定间隔，永久失败不无限重试，DONE 清理按实际完成时间计算，完成不足 7 天的通知不清理。DEAD 告警进入可见列表，重投以审计记录发起，不改变原业务事件事实。不重做 07 的连接与快照基础。本票自带测试。

## Spec coverage

主：AC16（US 40、42、88），见 [spec](../spec.md#可检查验收场景)。
复验：AC15 恢复部分（07 已验事务基础，本票复验 Redis/WS 故障后的重试与校正）；位置合并与订阅版本语义见 [17](17-websocket-revocation-and-resync.md)，事件来源见 [13](13-alarm-handling-and-history.md)。

## Acceptance criteria

- [ ] event_id 唯一，重复 enqueue 不重复业务事件
- [ ] 位置通知每车合并且 worker 只清已读版本，不误清高版本
- [ ] 报警与处置事件独立，不合并且不丢失
- [ ] 最多 10 次后 DEAD，告警可见且可审计重投
- [ ] DONE 7 天清理，DEAD 不自动删除
- [ ] worker 崩溃恢复后不重复已提交业务事件
- [ ] Redis 不可用或重启后快照可重建，旧缓存不伪造连接或在线
- [ ] 故障注入下成功 ACK 不依赖缓存

## Scope boundaries

不改位置事务结构、不做每浏览器恰好一次投递承诺、不自动清理 DEAD；不重做 07 的连接注册与快照基础；不做跨实例或集群语义。后票不代本票测试。

## Validation

以真实 MySQL 与 Redis 做故障注入：重复 enqueue、worker 崩溃、位置并发高版本、永久失败、Redis 停止与重启、WS 不可用；核对 outbox 状态、告警、ACK 时序与快照重建。记录命令、环境、版本、时段、期望与实际，逐条标注状态。

## Comments

（暂无）

## Answer

尚未实施，尚未执行验证。
