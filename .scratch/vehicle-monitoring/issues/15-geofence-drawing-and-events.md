# 15: 围栏绘制与进出事件

Status: resolved
Labels: ready-for-agent
Type: task
Blocked by: 12

## What to build

OpenLayers 绘制圆形与多边形围栏，页面与服务端执行同一合法校验：圆半径 10–100000m，多边形 3–20 顶点，拒绝自交、退化与越界；几何以 WGS84 持久化，地图按实际投影显示，距离使用米制。API 支持创建、编辑、停用围栏，绑定车辆并选择进入、离开事件，变更留审计。首次有效实时点只建立内外基线；边界算内；连续 3 点确认新的内外状态后生成一次事件；相邻有效点 >30 秒重置候选。形状或绑定变更重建基线并审计，补传历史点不改变当前围栏状态，不清除其他 ACTIVE 报警。围栏 state 与事件 outbox 在 07 的位置事务内。车队长只见授权车辆相关的围栏与绑定。地图交互不替代服务端几何校验；距离计算不直接使用经纬度差或屏幕像素。本票自带 migration、页面、API、审计与测试。

## Spec coverage

主：AC21（US 66–71），见 [spec](../spec.md#可检查验收场景)。
复验：AC02 围栏列表与绑定不泄露其他车队车辆；AC15 事务部分（围栏 state、事件与位置同事务）。事件写入结构沿用 [12](12-overspeed-rules-and-alarm-lifecycle.md)。

## Acceptance criteria

- [x] 页面绘制圆与多边形，服务端同规则校验半径、顶点数、自交、退化与越界
- [x] WGS84 存储与米制距离计算，边界点算内
- [x] 绑定车辆并可选择进入/离开事件，编辑与停用留审计
- [x] 首次有效实时点只建基线，不产生事件
- [x] 连续 3 点确认变化生成一次事件，抖动不重复
- [x] 相邻有效点 >30 秒重置候选
- [x] 形状或绑定变更重建基线并审计，不清其他 ACTIVE
- [x] 补传历史点不改变当前围栏状态
- [x] 围栏 state 与事件 outbox 在位置事务内
- [x] 车队长只见授权车辆相关围栏与绑定

## Scope boundaries

不做围栏限速、停留、启用时存量检查、下发终端围栏；不实现底图真实接入（26）；不改 07 位置事务结构，只挂接围栏状态与事件。后票不代本票测试。

## Validation

浏览器绘制、编辑与权限验证；HTTP API 校验用例；经真实 TCP 位置序列验证基线、边界、3 点跨界、抖动与 >30 秒间隔；真实 MySQL 核对几何存储、state 与 outbox。记录命令、环境、版本、时段、期望与实际，逐条标注状态。

## Comments

（暂无）

## Answer

已实现并完成验证，逐条 AC 的证据见 `.scratch/vehicle-monitoring/evidence/15-verification.md`。

实现：

- `V13__geofence.sql`：新增 `geofence`（`fence_name` 唯一、`fence_type`、圆心/半径或 `vertices`、`center_point POINT SRID 4326`、`boundary POLYGON SRID 4326`、`enter_enabled`/`leave_enabled`、`status`、`version`，CHECK 约束半径 10–100000 米、圆/多边形字段互斥与经纬度范围）、`geofence_vehicle`（`(geofence_id, vehicle_id)` 唯一）、`geofence_vehicle_state`（`(geofence_id, vehicle_id)` 唯一，`fence_version`、`inside_state`、`candidate_state`、`consecutive_count`、`last_point_time`）；`alarm` 的 `ck_alarm_type` 扩展 `GEOFENCE_ENTER`/`GEOFENCE_LEAVE`。未改 V1–V9、V11、V12、V14。
- `com.vms.geofence`：`GeofenceGeometryValidator` 统一半径、顶点数、自交、退化与越界规则（`INVALID_GEOFENCE_GEOMETRY`）；`GeofenceAdminService` 创建、编辑、状态、绑定均递增 `geofence.version` 并写 `GEOFENCE` 审计（CREATE/UPDATE/STATUS/VEHICLES 与 BASELINE_REBUILD），重名 409，读取/写入按 `FleetScopeSql` 过滤，越权 fleetId 返回 403；`GeofenceStateParticipant` 挂在 07 位置事务内：首个有效实时点只建基线、边界算内、圆形用 haversine 米制距离、多边形用线段距离与射线法，连续 3 个有效实时点确认后生成一次 `condition_state='EVENT'` 事件，相邻点 >30 秒候选重计，`fence_version` 不一致时重建基线并写审计，历史点与无效点不参与。
- 事件沿用 `alarm` 与 `notification_outbox` 的 `ALARM_EVENT`（`geofence:enter|leave:<alarmId>`，version 1），WS 帧 `alarm_event`。
- 前端 `web/src/features/geofences/`：`geometry.ts` 与服务端同规则同文案；`api.ts`；`GeofenceMap.vue` 复用 `basemap.ts` 的 `resolveBasemap`/`wmtsResolutions`/`WMTS_MATRIX_IDS`（未改写该文件），OpenLayers 绘制圆/多边形，半径按大圆距离换算为米，卸载时 dispose map、vector/tile source 与 ResizeObserver；`GeofenceView.vue` 提供创建、编辑、停用与绑定；`/monitor/geofences` 占位替换为真实页面，保留 `name`/`meta` 与导航 `data-testid`。
- 设计同步：`docs/design/系统设计方案.md` 追加任务15的 V13 落地说明。

验证（均经 `with-test-lock.ps1` 串行；Testcontainers MySQL 8.4 + Redis 8.4，Ryuk 已回收）：

- TDD 红→绿：几何校验、API、判定各切片先红后绿。
- `GeofenceApiFlowTest` 3 项：非法几何 11 例全部 400 且不落库、合法边界 10/100000 米与 3/20 顶点、WGS84 `ST_SRID=4326`、版本与审计序列、重名 409、车队长可见范围与 403。
- `GeofenceJudgementFlowTest` 6 项：首个实时点建基线且边界算内、米制距离、无效点不推进、3 点确认与 WS 推送、抖动与 >30 秒重计、历史点不改状态、形状/绑定重建基线与审计且不清其他 ACTIVE、注入约束证明围栏 state 与事件 outbox 同事务回滚且不 ACK。
- `SchemaUpgradeTest` 2 项：空库 11 个迁移、表清单含三张围栏表；从 V6 增量 5 个迁移，分区与数据保留。
- 前端 `pnpm typecheck`、`pnpm lint`（0 error，131 条存量 warning）、`pnpm build` 全部退出码 0。
- 浏览器 `BrowserFlowTest` + 新 spec `web/e2e/geofences.spec.ts`：Playwright 42 项全部通过（基线 39 + 新增 3）。第一次运行因新增圆形用例的圆心断言过严 1 项失败（圆心实际为点击位置 116.3764481），修正该新增断言后重跑 42/42 通过；两次运行日志均保留在 evidence。截图三张（创建/编辑、多边形、权限）。

偏差：分支基线 `4e3cd32` 后以 `git merge --no-ff feat/ticket-12-overspeed-alarm` 合入报警基础（`4b6d582`）；多边形增加“完成绘制”按钮用于确定性闭合（默认顶点回点/双击闭合仍可用，绘制期间关闭双击缩放以免误缩放）。

已合并 `integration/tickets-08-27`（`c2f9e9c`），合并仅涉及任务09文档修正，无代码变化，验证结论不变。

范围边界：未实现围栏限速、停留、启用时存量检查、终端围栏下发；真实天地图 key 属任务26，本票未改写 `basemap.ts` 与 fixture。
