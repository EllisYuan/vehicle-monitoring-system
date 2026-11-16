# 10 单车轨迹查询与回放 验证记录

- 日期：2026-11-16
- 分支：`feat/ticket-10-track-playback`（worktree `.claude/worktrees/agent-ab6f62035e6d17edd`）
- 基线：`integration/tickets-08-27` @ `b221243`；提交前合并该分支结果为 `Already up to date`
- 结论：8 条 Acceptance criteria 全部通过。其中“重要事件点保留”以受控样例验证，真实报警事件点按 ticket 约定待 12/27 复验；“必要点超限”的页面提示仅静态检查；真实天地图底图接入属 26，不在本票。
- 证据文件：`10-track-query-flow.log`、`10-simulator-build.log`、`10-browserflow.log`（首次浏览器运行的失败日志，既有偶发）、`10-browserflow-rerun.log`、`10-browserflow-final.log`、`10-playwright.log`、`10-track-playback.png`、`10-leader-track-scope.png`

## 环境

- Windows 11 Pro for Workstations；Java 21.0.9；Maven Wrapper（Surefire 3.5.6）；Spring Boot 4.1.2
- Testcontainers 2.0.5；Docker 29.1.3；MySQL 8.4；Redis 8.4；Flyway 9 个迁移（V1–V5 SQL、V6 Java、V7、V11、V14），启动日志确认 `Successfully applied 9 migrations ... now at version v14`
- Node v24.11.1；pnpm 10.16.1；Vite 8.3.6；Playwright 1.58.1（workers 1、fullyParallel false、retries 0、Chromium）
- 所有 Maven/浏览器验证经 `C:\Users\YuanS\AppData\Local\Temp\vms-spec-notes\with-test-lock.ps1` 串行执行；Ryuk 保持启用，未并行启动 Maven

## 实现要点

- API：`GET /api/v1/tracks?vehicleId=&from=&to=`，`@RequiresPermission(Permission.FLEET_VIEW)`；时间接受 `OffsetDateTime` 或 `LocalDateTime`（无偏移时按 `Asia/Shanghai`）
- 参数与窗口错误：`TRACK_VEHICLE_REQUIRED`、`TRACK_TIME_INVALID`、`TRACK_WINDOW_INVALID`、`TRACK_WINDOW_EXCEEDS_24H`、`TRACK_OUTSIDE_RETENTION`（按 `track.retention.days`）、`VEHICLE_NOT_FOUND`；反向、恰好 24 小时、跨日均按实现校验
- 数据范围：`FleetScopeResolver` + `FleetScopeSql.where("fleet_id_at_event", scope, args)`；越界 `ForbiddenException`（403）；不绕过既有会话与撤权机制
- 分区感知：`TrackPartitionCatalog` 读取 `information_schema.PARTITIONS` 的 `pYYYYMMDD` 分区，`load` 仅拼接查询日窗口内存在的分区名，生成 `track_point PARTITION (p...)` 查询
- 分段：`locate_state=VALID` 且 `lng/lat` 非空为有效点；无效点形成 `INVALID_FIX`；相邻有效点间隔 >30 秒形成 `TIME_GAP`
- 抽稀：段内 anchored Douglas-Peucker，初始 10 米，容差翻倍直至总量 ≤2000；段首尾与 `alarm_flag` 非零的事件关联点强制保留；必要点 >2000 返回 `TRACK_TOO_DENSE`（400，含“缩小”提示），不截断
- 响应：`vehicleId`、`plateNo`、`fleetId`、`from`、`to`、`rawPointCount`、`invalidPointCount`、`displayPointCount`、`segments[]`、`gaps[]`
- 审计：category `TRACK`、action `QUERY`、target `vehicle:{id}`；成功记录 raw/invalid/segments/display 点数，失败记录原因（含越权与超限）
- 前端：`/monitor/tracks` 查询、统计、断点与分段列表、`TrackMap`（每段独立 LineString，断点端点单独标记）、回放（播放/暂停/拖动/1×2×4×8×、200ms tick、断点内无 marker 且 `data-in-gap=true`）；空轨迹时钟显示“—”
- 未新增迁移；复用 26 的 `basemap.ts`、WMTS 矩阵与 attribution，未修改 16/17/21/11 的路由与菜单项

## 已执行

### 后端集成测试（真实 MySQL 8.4 + Redis 8.4）

命令（worktree 根）：

```
& with-test-lock.ps1 -Command '<worktree>\mvnw.cmd','-B','-pl','server','-am','-Dtest=TrackQueryFlowTest','-Dsurefire.failIfNoSpecifiedTests=false','test'
```

结果：`Tests run: 8, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS（58.578s）。日志：`10-track-query-flow.log`。

覆盖（TDD 红→绿：先落测试并确认失败，再实现 controller/service；本记录保留绿色复跑证据）：

| 测试 | 验证内容 |
| --- | --- |
| `trackQueryReturnsOrderedDisplayPointsAndDoesNotModifyRawRows` | 有序展示点、`+08:00` 时间、共线 3 点抽稀为 2、成功审计、查询前后 `track_point` 行完全一致 |
| `trackQueryAllowsCrossDayWithin24Hours` | 23:59:50 → 次日 00:00:10 单段可查 |
| `trackQueryRejectsOversizedReversedMalformedAndExpiredWindows` | >24h / 反向 / 非法时间 / 超保留期 / 缺 vehicleId 分别返回明确 400 错误；恰好 24h 放行 |
| `invalidFixAndLongGapSplitSegmentsAndReportGaps` | 1 个无效点 + >30s 间隔 → 3 段、2 个 gap，gap 起止与相邻段端点一致 |
| `simplificationKeepsEventRelatedPointOnStraightLine` | 共线 4 点含 1 个事件点 → 展示 3 点，事件点 `eventRelated=true` 且坐标保留 |
| `highDensityTraceIsSimplifiedUnderDisplayLimit` | 2500 点 → 展示点 ≤2000，首点时间与末点保留 |
| `queryRejectsWhenMandatoryPointsExceedDisplayLimit` | 2001 个事件点 → 400 `TRACK_TOO_DENSE` 且提示“缩小”，FAILURE 审计，原始 2001 行未动 |
| `leaderCanOnlyQueryVehiclesInGrantedFleetsAndDenialIsAudited` | 车队长授权车 200；跨队车 403，FAILURE 审计，数据未动 |

### simulator 构建

```
& with-test-lock.ps1 -Command '<worktree>\mvnw.cmd','-B','-pl','simulator','-am','-DskipTests','package'
```

结果：BUILD SUCCESS（3.127s），生成 protocol 与 simulator 产物。日志：`10-simulator-build.log`。

### 浏览器验收（真实后端 + Vite + Playwright）

最终命令：

```
& with-test-lock.ps1 -Command '<worktree>\mvnw.cmd','-B','-pl','server','-am','-Dtest=BrowserFlowTest','-Dvms.browser-tests=true','-Dsurefire.failIfNoSpecifiedTests=false','test'
```

结果：`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS；Playwright `38 passed (2.7m)`，其中本票 2 个用例通过（27.3s / 4.3s）。日志：`10-browserflow-final.log`、`10-playwright.log`。

运行历史（如实记录）：

1. 首次运行：Playwright 1 failed / 37 passed，唯一失败为既有 `archives.spec.ts:24` 首例 `signIn` 的 `current-user` 5 秒断言超时（失败快照显示用户名、随机密码已填入且按钮为“登录中…”，登录请求尚未返回）；该点已在 `evidence/21-verification.md` 记录为既有偶发，与本票改动无关。日志：`10-browserflow.log`
2. 第二次运行：`38 passed`。日志：`10-browserflow-rerun.log`
3. 修正空轨迹时钟显示后（`clockText` 在无分段时显示“—”），最终运行再次 `38 passed`。日志：`10-browserflow-final.log`、`10-playwright.log`。结论以最终运行为准。

本票用例（真实 TCP + 真实浏览器操作；截图仅辅助）：

- `tracks.spec.ts` 用例 1：经 API 建立车队/车辆/终端并绑定；三次真实 TCP 模拟（有效 3 点 → 1.1s 间隔 → `--fix-valid false` 2 点 → 1.1s 间隔 → 有效 3 点，经度分别为 121.4737/121.4737/121.474）；页面查询断言 raw=8、invalid=2、display=4、segments=2、gaps=1（“定位无效”），地图 `data-segment-count=2`、`data-gap-count=1`；再用 `/api/v1/tracks` 返回真值交叉校验点数、分段、缺口原因与首末点坐标
- 回放一致性：`data-state=ready` 时 `data-clock-ms` 等于 API `segments[0].startTime`，`data-marker-lng` 等于首点 lng；用真实鼠标拖动 `track-seek` 从 40% 到 20%（时钟前进、marker 非空且不小于首点）；`setSeek(0)` 与 `setSeek(end-start)` 分别断言时钟与 marker 对上首末点/坐标
- 断点：播放穿越缺口时 `track-in-gap` 可见、`data-in-gap=true`、marker 为 null；进入第二段后提示消失、marker 恢复，证明不跨段插值
- 暂停：`track-pause` 后 600ms 内 `data-clock-ms` 不变
- 倍速：逐个点击 1/2/4/8，断言 `data-speed` 与 `aria-pressed`；同一 1.2s 墙钟窗口内 8× 的轨迹时间推进量 > 1× 的 3 倍
- 导出：页面无“导出/下载”文本、无 `a[download]`
- `tracks.spec.ts` 用例 2：管理员 API 建 A/B 两车队车辆，创建仅授权 A 的 FLEET_LEADER；车队长登录后下拉仅出现授权车辆、无越界车辆 option；查询授权车得到 0 点并显示“该范围内没有有效轨迹点”；以车队长 token 直接请求越界车辆 `/api/v1/tracks` 得到 403；页面无导出入口
- 底图：Playwright 将 `**/__wmts-fixture/**` 置为失败，页面显示“底图加载失败”，轨迹与列表仍可用；真实天地图接入不属本票

### 前端静态检查与构建

```
pnpm typecheck
npx eslint e2e/tracks.spec.ts src/features/tracks src/app/router.ts
pnpm build
```

结果：typecheck exit 0；eslint 0 errors（61 个既有 vue 风格 warning，与仓库现有代码一致）；生产构建成功（319 modules，生成 `TrackPlaybackView` chunk，544ms）。

### 资源回收

- 本轮各次 Maven 运行由 Ryuk（0.14.0）回收其 MySQL 8.4 / Redis 8.4 容器；最后一次运行结束后核对：`docker ps -a --filter label=org.testcontainers=true` 为空，本轮 JVM 创建的 MySQL/Redis/Ryuk 容器均已移除
- 未停止或删除其他项目容器（如 `plp-*`、`vms-redis`、`vms-mysql84`）；其他 agent 并发测试期间出现的容器未做清理

## 逐条 Acceptance criteria

| # | AC | 状态 | 证据 |
| --- | --- | --- | --- |
| 1 | 单车辆与 ≤24 小时范围校验，跨日允许，超范围返回明确错误 | 通过 | `TrackQueryFlowTest` 窗口/跨日用例；错误码见上表 |
| 2 | 真实 MySQL 数据查询展示点 ≤2000，原始轨迹行未被修改 | 通过 | 2500 点抽稀 ≤2000、首末保留；查询前后行快照一致；真实 MySQL 8.4 Testcontainers |
| 3 | 无效定位与 >30 秒缺口形成分段，回放不跨段连线 | 通过 | 后端 3 段/2 gap；浏览器 `data-in-gap=true` 且 gap 内 marker 为 null；`TrackMap` 每段独立 LineString |
| 4 | 抽稀保留首尾与分段边界；重要事件点输入不会被丢弃，受控样例验证，真实报警数据在 12/27 复验 | 通过（受控样例） | 共线事件点展示 3 点且 `eventRelated=true`；首末点保留；真实报警事件点按 ticket 约定待 12/27 |
| 5 | 必要点超限返回缩小范围提示，不截断 | 通过（后端）；页面提示仅静态检查 | 2001 事件点 → 400 `TRACK_TOO_DENSE` 含“缩小”，审计 FAILURE，原始行数不变；前端 `track-too-dense` 提示经构建与静态检查，未做浏览器 2001 点场景 |
| 6 | 浏览器回放验证播放/暂停/拖动/1/2/4/8 倍速，时间轴与车辆位置一致 | 通过 | Playwright 真实浏览器操作：时钟/marker 与 API 真值一致、拖动与端点、暂停冻结、1× vs 8× 墙钟推进比；见 `10-playwright.log` |
| 7 | 车队长跨队查询被服务端拒绝，页面无原始轨迹导出入口 | 通过 | 跨队 API 403；车队长下拉仅授权车辆；页面无导出/下载入口与 `a[download]` |
| 8 | 查询与越权尝试产生审计记录 | 通过 | 成功 `TRACK/QUERY` SUCCESS；超限 FAILURE；跨队 FAILURE；均含 target `vehicle:{id}` |

## 未执行与边界

- 真实报警事件点保留：依赖 12 与 27 的事件数据落地，按 ticket 约定在 12/27 复验；本票仅以受控 `alarm_flag` 样例验证
- 真实天地图底图/网络与 CRS 对齐：属 26；本票浏览器用例使用失败的 WMTS fixture，只验证底图失败时轨迹仍可查/可回放
- 30 天容量、执行计划与查询耗时：不在本票范围（11/AC33）；分区裁剪为静态检查（查询只列查询日窗口内的 `pYYYYMMDD` 分区），未执行 EXPLAIN 或容量压测
- “必要点超限”页面提示未做浏览器端 2001 点场景，仅后端错误码测试 + 前端静态/构建检查
- 复验项：AC06 数据范围（跨队 403）通过；AC14 无效点断点通过；AC13 补传点只进历史不回退实时未在本票新增执行（本票查询为只读，不写实时状态；由 09 的测试覆盖）
- 全量 `pnpm lint` 存在既有文件历史 error（本票未触碰该文件，未纳入本票验收）；本票范围 eslint 为 0 error