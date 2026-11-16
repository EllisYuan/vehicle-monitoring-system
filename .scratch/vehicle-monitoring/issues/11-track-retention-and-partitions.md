# 11: 30 天保留与分区维护

Status: resolved
Labels: ready-for-agent
Type: task
Blocked by: 07

## What to build

在 07 交付的按日分区基础上实现保留与维护：track_point 按 gps_time 的 Asia/Shanghai 日历日分区，默认保留今天及此前 29 天。复用 07 的 Flyway 初始分区迁移，补齐保留范围内所需的历史日分区；应用启动与每日巡检预建未来 7 天分区，并巡检与清理过期分区，记录分区范围和执行结果。缩短保留期前先预览将删除的分区范围及估算影响，经管理员确认再生效，审计记录操作者、旧值、新值与结果。分区维护失败写可见日志与告警记录。保留期外新上报只记受限异常摘要，不重建过期分区。不自动清理报警、处置、绑定/关联历史与审计，不删除仍被保留轨迹引用的历史关系。本票自带 migration 调整、管理界面或 API、审计与测试，不等待报警模块。

## Spec coverage

主：AC27（US 52–54），见 [spec](../spec.md#可检查验收场景)。复验：I6 分区与唯一键语义（07 初始日分区）、AC09 数据库失败不报成功的维护失败路径。范围依据为 spec I6 与 Further Notes。

## Acceptance criteria

- [x] 复用初始迁移后，保留范围内历史日分区和未来分区齐备，空库与已有数据升级均可验证
- [x] 启动与每日巡检预建未来 7 天，记录巡检结果
- [x] 过期分区清理限定 Shanghai 今天+29 之外，记录删除范围
- [x] 缩短保留期返回预览，未确认不删除任何分区
- [x] 确认后生效，审计记录操作者、旧值、新值与结果
- [x] 维护失败写可见日志与告警记录，不静默
- [x] 保留期外上报记受限异常摘要，不重建分区
- [x] 清理不删除报警、处置、绑定与关联历史、审计（报警/处置表未落地，按静态检查与绑定/历史/审计行数对照）
- [x] 真实 MySQL 验证分区边界、清理与重启行为

## Scope boundaries

不实现报警、轨迹查询页面或导出；不改写 07 的写入事务与 ACK 语义；不承诺 30 天满足真实运营全部监管期限；分区维护不改变既有业务写入路径。后续票不得替本票补测。

## Validation

在真实 MySQL 8.4 上执行 migration，造跨 30 天边界数据，触发启动巡检、每日巡检与清理，记录命令、版本、时区配置、分区清单、行数变化与审计输出；缩短保留期的预览与确认按浏览器界面验证。逐条记录通过、失败、未执行或仅静态检查。

## Comments

（暂无）

## Answer

已实现并完成验证。详细证据与逐条 AC 记录见 `.scratch/vehicle-monitoring/evidence/11-verification.md`。

实现：

- `V11__track_retention_and_partitions.sql` 新增 `system_config`（seed `track.retention.days='30'`，version 1）、`track_partition_maintenance`、`track_partition_alert`、`track_retention_change`、`track_retention_rejection`；不修改 V6 建立的 `track_point` 日分区结构。
- `com.vms.track` 提供保留策略（Asia/Shanghai，窗口 `[today-(days-1), today]`、预建至 `today+7`）、分区目录（`ADD PARTITION` 补末端、`REORGANIZE` 补内部缺口、`DROP PARTITION` 清过期）、启动 `STARTUP` 与每日 `DAILY` 维护（cron `vms.track.daily-cron`，默认 `0 5 0 * * *`），维护结果写 `track_partition_maintenance`；失败写 ERROR 日志、FAILED 记录与 `track_partition_alert`。
- `TrackRetentionAdminService/Controller` 提供 `GET /api/v1/admin/track-retention`、`POST .../preview`、`POST .../confirm`，权限 `TRACK_RETENTION_MANAGE`（仅 ADMIN）。预览只落 `PREVIEWED` 记录不删分区；确认校验 token、30 分钟 TTL 与配置版本，生效后写 `TRACK_RETENTION` 审计（操作者、旧值→新值、结果）。确认失败路径使用 `@Transactional(noRollbackFor = ConflictException.class)`，确保 FAILED/EXPIRED 状态、失败维护记录、告警与 FAILURE 审计随 409 一并提交，不静默丢失。
- `PositionIngestService` 对保留期外上报写 `track_retention_rejection` 受限摘要（reason、payload_hash、body_length，不含坐标与原始报文），更新 `last_seen_at` 后回成功 ACK，不写 `track_point`、不推进快照/outbox、不重建过期分区。
- 前端新增 `web/src/features/track-retention/`（API + `TrackRetentionView.vue`）、路由 `/admin/track-retention` 与导航 `nav-admin-track-retention`，保留 16 票的 `/monitor/commands` 与 26 票既有导航；e2e 覆盖预览与确认。

验证（均经 `with-test-lock.ps1` 全局串行锁；Testcontainers `mysql:8.4`/`redis:8.4`，Ryuk 启用并已回收）：

- `TrackRetentionMaintenanceTest` 10 项：启动分区窗口与记录、过期清理与删除范围、失败告警、预览不删分区、确认与审计、保留期外拒绝、每日巡检与重启幂等、历史关系与审计保留、内部缺口 `REORGANIZE`、确认失败保留失败证据。
- `SchemaUpgradeTest` 2 项：空库 8 个迁移、`applied=8`/`versions=8`；从 V6 升级应用 V11+V14 两个迁移，37 个日分区与既有 `track_point` 数据不变。
- 合并 `integration/tickets-08-27` 后：`SchemaUpgradeTest,TrackRetentionMaintenanceTest,TelemetryFlowTest,CommandFlowTest` 33 项通过；`pnpm typecheck/lint/build` 退出码 0；simulator 打包成功；`BrowserFlowTest,TrackRetentionMaintenanceTest` 11 项通过，Playwright 32 项全部通过。

遗留边界：报警与处置表由 12/13 票交付，本票以清理实现只执行 `ALTER TABLE track_point DROP PARTITION`（无 DELETE/TRUNCATE/DROP TABLE）及绑定/历史/审计行数对照证明不清理；30 天真实规模容量属 25 票/AC33，不在本票结论内。合入前浏览器首轮 corepack 环境失败与第二轮页面组件注册缺陷均已修复，最终结果以上述合并后运行为准，失败记录保留在 evidence 中。