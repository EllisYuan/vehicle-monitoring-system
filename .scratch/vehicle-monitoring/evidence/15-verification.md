# 任务15 验证证据：围栏绘制与进出事件

- 分支：`feat/ticket-15-geofence`（基线 `4e3cd32`，合入 `feat/ticket-12-overspeed-alarm` 后为 `4b6d582`）
- 工作区：`C:\Users\YuanS\Documents\paper\ja\vehicle-monitoring-system\.claude\worktrees\agent-af81fd0be99d2893e`
- 验证日期：2026-11-16
- 环境：Windows 11 Pro for Workstations；Java 21（`C:\Java\jdk-21.0.9`）；Maven wrapper 3.9.12；Node v24.11.1；pnpm 10.16.1；Testcontainers MySQL 8.4 + Redis 8.4（Ryuk 自动回收）；OpenLayers 10.11；Playwright 1.58.1
- 全局锁：本轮所有 Maven 命令均通过 `C:\Users\YuanS\AppData\Local\Temp\vms-spec-notes\with-test-lock.ps1` 串行执行，未并行 Maven，未绕过锁。

## 结论总览

| # | 验收点 | 状态 | 主要证据 |
| --- | --- | --- | --- |
| AC1 | 页面绘制圆与多边形，服务端同规则校验半径、顶点数、自交、退化与越界 | 通过 | `GeofenceApiFlowTest`、`web/e2e/geofences.spec.ts` 用例1/2 |
| AC2 | WGS84 存储与米制距离计算，边界点算内 | 通过 | `GeofenceApiFlowTest`、`GeofenceJudgementFlowTest#firstValidRealtimePoint...` |
| AC3 | 绑定车辆并可选择进入/离开事件，编辑与停用留审计 | 通过 | `GeofenceApiFlowTest#editDisable...`、e2e 用例1 |
| AC4 | 首次有效实时点只建基线，不产生事件 | 通过 | `GeofenceJudgementFlowTest#firstValidRealtimePoint...` |
| AC5 | 连续 3 点确认变化生成一次事件，抖动不重复 | 通过 | `GeofenceJudgementFlowTest#threeConsecutivePoints...`、`#jitterLongGap...` |
| AC6 | 相邻有效点 >30 秒重置候选 | 通过 | `GeofenceJudgementFlowTest#jitterLongGapAndHistoryPoints...` |
| AC7 | 形状或绑定变更重建基线并审计，不清其他 ACTIVE | 通过 | `GeofenceJudgementFlowTest#shapeAndBindingChanges...` |
| AC8 | 补传历史点不改变当前围栏状态 | 通过 | `GeofenceJudgementFlowTest#jitterLongGapAndHistoryPoints...` |
| AC9 | 围栏 state 与事件 outbox 在位置事务内 | 通过 | `GeofenceJudgementFlowTest#eventStateAndOutboxSharePositionTransaction...` |
| AC10 | 车队长只见授权车辆相关围栏与绑定 | 通过 | `GeofenceApiFlowTest#monitorMaintains...`、e2e 用例3 |

## 一、后端集成测试（真实 MySQL / 真实 TCP / 真实 WebSocket）

命令（锁内，单次 Maven 串行执行 6 个测试类）：

```powershell
& 'C:\Users\YuanS\AppData\Local\Temp\vms-spec-notes\with-test-lock.ps1' `
  'C:\Users\YuanS\Documents\paper\ja\vehicle-monitoring-system\.claude\worktrees\agent-af81fd0be99d2893e\mvnw.cmd' `
  -B -pl server -am '-Dtest=SchemaUpgradeTest,OverspeedAlarmFlowTest,GeofenceApiFlowTest,GeofenceJudgementFlowTest,PositionQualityFlowTest,TelemetryFlowTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

结果：BUILD SUCCESS，6 类 39 项全部通过（`Tests run: 2 / 10 / 3 / 6 / 8 / 10，Failures: 0, Errors: 0`）。
surefire 摘要见 `15-backend-regression-surefire.log`；围栏两类用例清单见 `15-geofence-surefire.log`；控制台见 `15-backend-regression-console.log`。

### AC1 页面与服务端同一规则校验

- `GeofenceApiFlowTest#serverRejectsInvalidCircleAndPolygonGeometryWithSameRulesAsPage`
  - 非法用例 11 个全部返回 400 且 `code=INVALID_GEOFENCE_GEOMETRY`：半径 9.99、100000.01、缺失；经度 180.5；纬度 -90.5；2 个顶点；蝴蝶结自交；三点共线退化；重复相邻点；顶点纬度 91；21 个顶点。
  - 合法边界 4 个全部 201：半径 10、半径 100000、三角形 3 顶点、正二十边形 20 顶点。
  - 真实 MySQL 核对：只有 4 条合法记录落库，非法请求无写入。
- 页面侧 `web/src/features/geofences/geometry.ts` 与 `GeofenceGeometryValidator` 使用同一组阈值（10/100000 米、3/20 顶点）和同一组提示文案；浏览器用例实际触发：半径 5 被页面拒绝并显示“10 到 100000 米”，蝴蝶结四点被拒绝并显示“自交”。见 e2e 用例1、用例2 与截图。

### AC2 WGS84 存储与米制距离

- `GeofenceApiFlowTest#editDisableAndBindingAreVersionedAuditedAndGeometryStoredAsWgs84`：`ST_SRID(center_point)=4326`，`ST_Longitude/ST_Latitude` 为 116.4/39.9；`ST_SRID(boundary)=4326`，`ST_AsText(...,'axis-order=long-lat')` 以 `POLYGON((` 开头且包含 `116.41 39.9`；`vertices` JSON 保留 7 位小数。
- `GeofenceJudgementFlowTest#firstValidRealtimePointBuildsBaselineAndBoundaryCountsInsideWithMetricDistances`：纬度 39.89 位于 116.39–116.41 多边形边界上，判定 `INSIDE`；同点对 500 m 圆判定 `OUTSIDE`（边界算内）。纬度 +0.01 度约 1113 m > 500 m，仍判 `OUTSIDE`，说明按米而不是经纬度差计算。
- 页面侧：绘制圆后半径由圆心与圆边两点的 haversine 距离换算（米），显示时按大圆方向采样为球面多边形；不使用屏幕像素或经纬度差。属静态检查 + 浏览器实测（`data-radius-m` 来自真实鼠标绘制）。

### AC3 绑定、事件开关、编辑与停用审计

- `GeofenceApiFlowTest#editDisableAndBindingAreVersionedAuditedAndGeometryStoredAsWgs84`：创建 v1 → 形状编辑 v2 → 停用 v3 → 解绑 v4；`audit_log` 中 `category='GEOFENCE' AND target='geofence:<id>'` 的动作序列为 `CREATE, UPDATE, STATUS, VEHICLES`；重名创建返回 409 且不新增成功审计。
- `GeofenceApiFlowTest#monitorMaintainsGeofencesWhileFleetLeaderOnlySeesAuthorizedVehicleBindings`：MONITOR 角色可创建围栏并停用他人创建的围栏（v2）。
- e2e 用例1：页面创建（勾选绑定车辆）→ 通知含“围栏已创建”与 `v1`；编辑半径 2000→3000 并取消“离开事件” → `v2`；停用 → `已停用`、`v3`；API 复核字段一致。

### AC4 首次有效实时点只建基线

- `GeofenceJudgementFlowTest#firstValidRealtimePoint...`：首个有效实时点在多边形/圆上均只写入 `geofence_vehicle_state`（`inside_state=INSIDE/OUTSIDE`），报警数为 0；随后 `locate_state=1` 的无效定位点不推进 `last_point_time`（不参与围栏计算）。

### AC5 连续 3 点确认，抖动不重复

- `GeofenceJudgementFlowTest#threeConsecutivePointsConfirmCrossingAndProduceOneEventPushedOverWebSocket`：圆内基线后向圆外移动，第 1、2 点不产生事件，第 3 点产生唯一 `GEOFENCE_LEAVE`（`condition_state=EVENT`、`recovered_at` 为空、`rule_snapshot.geofenceId/fenceVersion=1`），`consecutive_count` 归零；`notification_outbox` 事件 `geofence:leave:<alarmId>` 类型 `ALARM_EVENT`，WebSocket 收到 `alarm_event` 帧；继续在外不重复；回到圆内连续 3 点产生一条 `GEOFENCE_ENTER`，总数为 2。
- `GeofenceJudgementFlowTest#jitterLongGap...`：圆内外交替 6 点（10 秒间隔）报警数为 0。

### AC6 相邻有效点 >30 秒重置候选

- `GeofenceJudgementFlowTest#jitterLongGap...`：候选第 2 点后间隔 45 秒（>30 秒）再上报，不产生事件；随后连续 3 个 10 秒间隔点才产生唯一 `GEOFENCE_LEAVE`，且 `last_point_time` 为最后一点时间。

### AC7 形状/绑定变更重建基线并审计，不清其他 ACTIVE

- `GeofenceJudgementFlowTest#shapeAndBindingChanges...`：修改圆心后版本 v2，下一实时点仅重建基线（`inside_state=OUTSIDE`、`consecutive_count=0`），不产生事件；预先插入的 `OVERSPEED` `ACTIVE` 报警仍为 `ACTIVE`；解绑再绑定后版本 v4，下一实时点重建基线不产生事件；`audit_log` 同 target 含 `UPDATE, VEHICLES, VEHICLES` 与 `BASELINE_REBUILD`。

### AC8 补传历史点不改变当前围栏状态

- `GeofenceJudgementFlowTest#jitterLongGap...`：在状态为 `OUTSIDE` 时补传一条 `gps_time` 早于接收时间 60 秒以上的点，`track_point.report_type=HISTORY`；`inside_state`、`last_point_time` 均不变，围栏报警数仍为 1。

### AC9 围栏 state 与事件 outbox 在同一位置事务

- `GeofenceJudgementFlowTest#eventStateAndOutboxSharePositionTransaction...`：对 `alarm` 注入只允许 `OVERSPEED` 的 CHECK 约束后，第 3 个跨界点写入失败：TCP 不返回成功 ACK（连接关闭），`track_point` 仍为 3 条，无围栏报警，`geofence_vehicle_state` 回滚为 `INSIDE/consecutive_count=2/last_point_time=前一点`，位置 outbox `version=3`；移除约束后重发同一点成功并产生 `GEOFENCE_LEAVE`，`track_point` 为 4 条。

### AC10 车队长只看到授权车辆相关围栏与绑定

- `GeofenceApiFlowTest#monitorMaintains...`：车队长（仅授权甲队）列表只含绑定甲队车辆的围栏，且每个围栏的 `vehicles` 全部 `fleetId=甲队`；直接读取乙队围栏、`?fleetId=乙队` 过滤、创建、编辑、绑定、停用均返回 403，乙队围栏状态未被改动。
- e2e 用例3：车队长登录后 `nav-geofences` 数量为 0，访问 `/monitor/geofences` 被重定向到 `/`，无 `geofence-page`；API 跨车队查询与读取均 403。

## 二、迁移与存量结构

- `SchemaUpgradeTest`：空库迁移 `migrationsExecuted=11`，重复迁移为 0；表清单包含 `geofence`、`geofence_vehicle`、`geofence_vehicle_state`；`flyway_schema_history` 记录 applied=11、versions=11、成功；从 V6 增量升级 5 个迁移，`track_point` 分区与历史数据保留。
- `V13__geofence.sql` 为本票唯一新增迁移；V1–V9、V11、V12、V14 未改动（`git status` 可见）。

## 三、前端静态检查

| 检查 | 命令 | 结果 | 日志 |
| --- | --- | --- | --- |
| typecheck | `pnpm typecheck` | 通过（退出码 0，无输出） | `15-web-typecheck.log` |
| lint | `pnpm lint` | 通过（退出码 0；0 errors，131 warnings 均为存量样式警告） | `15-web-lint.log` |
| build | `pnpm build` | 通过；`GeofenceView-CHTfNkjJ.js 46.13 kB` | `15-web-build.log` |

前端命令在锁外执行（不占用 Maven 锁），与后端验证不并发。

## 四、浏览器验收（BrowserFlowTest）

前置：锁内 `mvnw.cmd -B -pl simulator -am -DskipTests package`（BUILD SUCCESS）。
命令（锁内，与本轮其他 Maven 串行）：

```powershell
& 'C:\Users\YuanS\AppData\Local\Temp\vms-spec-notes\with-test-lock.ps1' `
  '<worktree>\mvnw.cmd' -B -pl 'server,simulator' -am '-Dvms.browser-tests=true' `
  '-Dtest=BrowserFlowTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

结果：BUILD SUCCESS（Total time 04:21 min，Finished at 2026-11-16T16:12:42）；`BrowserFlowTest` 1 项通过；Playwright 套件 42 项（原有基线 39 + 本票新增 3）全部通过，耗时 3.4 min。日志：`15-browser.log`、`15-playwright.log`。

- 第一次运行（16:04 结束）Playwright 42 项中 41 通过、1 失败：本票新增圆形用例将圆心断言为 116.4/39.9，而真实鼠标点击位置换算的圆心为 116.3764481；属该新增用例断言过严，不是产品缺陷。失败证据保留为 `15-browser-run1-failed.log`、`15-playwright-run1-failed.log`。
- 修正该新增用例为“圆心是视野中心附近的有效 WGS84 经纬度（±0.2 度）”，未删除或放宽任何既有断言；重跑后 42/42 通过。`archives.spec.ts` 两次均通过，未出现已知登录抖动。
- 本票三个浏览器用例：① 绘制圆形（半径 5 被页面拒绝、2000 合法，创建/编辑/停用得到 v1→v2→v3 并经 API 复核）；② 绘制多边形（自交被拒，三角形闭合后 3 顶点）；③ 车队长权限（导航隐藏、路由重定向到 `/`、跨车队 API 403）。
- 截图：`15-geofence-create-edit.png`、`15-geofence-polygon.png`、`15-geofence-permission.png`。

## 五、偏差与说明

1. 分支基线：按要求 `git checkout -B feat/ticket-15-geofence 4e3cd32` 后，以 `git merge --no-ff feat/ticket-12-overspeed-alarm` 合入报警基础（合并提交 `4b6d582`），未直接 rebase 或改写历史。
2. 多边形绘制增加“完成绘制”按钮（`data-testid="finish-draw"`），用于确定性闭合与 e2e 操作；OpenLayers 默认的顶点回点/双击闭合仍可用（绘制期间显式关闭 `DoubleClickZoom` 以免误缩放）。
3. 按范围边界未实现：围栏限速、停留、启用时存量检查、终端围栏下发；底图真实 key 仍属任务26，本票只复用 `basemap.ts` 的 `resolveBasemap/wmtsResolutions/WMTS_MATRIX_IDS`，未改写该文件。
4. 容器核对：浏览器运行结束后（16:13 本地时间）`docker ps -a` 中无本轮 Testcontainers 创建的容器；当时在运行的 mysql:8.4 与 Ryuk 创建时间为 15:45:46 与 16:13:18（本地），分属并发其他 ticket 的会话（Testcontainers sessionId 不同），未清理他人容器。本轮 Ryuk 自动回收正常。

## 六、合并 integration/tickets-08-27

- 合并提交 `c2f9e9c`（Merge branch 'integration/tickets-08-27' into feat/ticket-15-geofence），无冲突。
- 合并仅带入 `09-verification.md` 与 `09-batch-backfill-and-history-ownership.md` 两处文档修正；`git diff --stat 0705d3e..c2f9e9c` 显示无源码、测试、迁移或前端文件变化，因此前述验证结果对最终 HEAD 仍成立，无需复跑 Maven 与前端检查。