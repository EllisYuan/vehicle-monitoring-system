# 任务26 验证记录：真实天地图接入验证

- 日期：2026-11-16
- 分支：feat/ticket-26-tianditu-basemap（基于 integration/tickets-08-27 @ 907c234）
- 结论：**Status: blocked**。未提供真实天地图 key，真实底图、真实网络与真实坐标对齐均未执行；AC30 未通过。已完成不依赖 key 的配置核对、fixture 降级验证与比对方法准备。

## 环境

- Windows 11 Pro for Workstations 10.0.26200；Node/pnpm 按 web 工程；OpenLayers 10.11.0；Playwright chromium。
- 后端测试：JDK 21.0.9，Maven Wrapper；Testcontainers 2.0.5（MySQL 8.4、Redis 8.4、Ryuk 0.14.0），Ryuk 保持启用。
- 浏览器与后端测试均通过全局互斥脚本 `C:\Users\YuanS\AppData\Local\Temp\vms-spec-notes\with-test-lock.ps1` 串行执行。
- 未配置真实 key：环境变量 VMS_*/VITE_WMTS_* 未设置，仓库内无 web/.env.local、.env 等本地 key 配置。未索取、未猜测、未伪造 key；本记录及日志不含任何 key 值。

## 已执行

### 1. 配置解析与 WMTS 元数据核对

- 新增 `web/src/features/monitoring/basemap.ts`：集中解析 VITE_WMTS_*（URL、layer、matrixSet、style、format、attribution、key 占位符），不依赖 Vite 运行时。
- 修正 `VehicleMap.vue` 的矩阵编号：天地图 TileMatrix 从 1 开始，原实现使用 0..18 与服务层级错位；现使用 1..18，层级 N 分辨率 = maxResolution / 2^N。
- 新增 `web/e2e/fixtures/tianditu-wmts-metadata.json`：2026-11-16 从公开 GetCapabilities（`http://t0.tianditu.gov.cn/vec_w/wmts?request=GetCapabilities&service=wmts`，无需 key）提取的 vec 图层元数据；未抓取任何瓦片。
- 测试 `web/e2e/basemap-config.spec.ts` 共 8 项：配置解析、key 特殊字符编码、matrixSet 限制、缺 URL/缺 key 报错、layer/style/format/attribution 覆盖、矩阵编号与分辨率对照元数据、已知点投影与瓦片行列号基准。
- 结果：通过（27 项 Playwright 全通过，含本组 8 项）。

### 2. attribution 显示

- 组件新增 `data-testid="monitor-map-attribution"`；仅在底图实际配置时显示，默认文案 `天地图 · 国家地理信息公共服务平台`，并保留 `· OpenLayers`。
- 未配置或配置错误时不显示 attribution。
- 结果：fixture 环境验证通过；**未核对供应商条款要求的最终文案与展示位置**。

### 3. key 卫生

- key 仅通过 `{key}` 占位符替换后进入瓦片请求 URL，并进行 URI 编码；错误提示、页面正文与 console 不出现 key。
- e2e 用例断言：瓦片请求包含 `tk=<fixture key>`，页面正文与控制台不含该值。
- 结果：通过（fixture key，非真实 key）。

### 4. 无效 key / 网络失败降级

- e2e 用例将道路/注记请求分别返回 403 XML 与非图片 XML，页面显示 `底图加载失败…`，车辆列表、筛选与定位数据仍可用，提供 `重试底图`；另有 12s 超时提示路径。
- 结果：通过（Playwright route 模拟，非真实网络故障）。

### 5. 静态检查与构建

- `pnpm --dir web typecheck`：exit 0。
- `pnpm --dir web lint`：exit 0。
- `pnpm --dir web build`：exit 0（vite build 成功）。
- 结果：通过。

### 6. 测试环境回收

- 本轮 BrowserFlowTest 结束后 `docker ps -a --filter name=testcontainers` 无残留；未清理其他开发容器或数据库。
- 结果：通过。

## 未执行（缺真实 key / 真实网络）

- 真实天地图道路（vec_w）与中文注记（cva_w）底图在浏览器中可见；未产生真实瓦片请求，未记录真实响应状态、图层版本与浏览器版本。
- 真实底图上的已知点对齐比对（仅准备了方法与 fixture 基准）。
- 部署环境的网络可达性、域名白名单与 HTTPS 证书行为。
- 供应商条款正文与账户实际额度：条款页为 SPA，仅抓取到页面外壳；账户额度需登录控制台，未代用户操作。
- AC30 整体未通过，不以 fixture 截图或静态截图冒充实时底图验证。

## 逐项 AC 状态

| Acceptance criterion | 状态 | 依据/说明 |
| --- | --- | --- |
| 真实 key 配置后道路与中文注记底图可见 | 未执行 | 无真实 key，未发起真实瓦片请求 |
| CRS 与坐标对齐有已知点比对证据 | 未执行（仅方法与 fixture 基准） | 已核对 EPSG:3857/matrixSet=w，提供已知点行列号基准；未在真实底图上比对 |
| attribution 按供应商条款正确显示 | 未执行（显示行为已用 fixture 验证） | 条款正文未能读取，最终文案与位置未核对 |
| 额度与服务条款核对结论有记录 | 仅静态检查 | 官方文档确认需申请 Key、存在配额限制、Fees/AccessConstraints 为 none；账户额度未核对 |
| 开发环境与部署网络分别验证 | 未执行 | 仅本地 Vite + fixture |
| 网络失败时显示错误且车辆列表可用 | 通过 | Playwright 403/非图片响应用例，车辆列表可用 |
| 无 key 时仅本票 blocked，不伪造结果 | 通过 | 本票 Status: blocked，未勾选未执行项 |
| 不以静态截图冒充实时底图验证 | 通过 | 本轮截图均为 fixture 失败态，已标注非真实底图 |

## 真实 key 到位后的比对方法

已知点基准（WGS84 经纬度 → Web Mercator，14 级瓦片；分辨率由服务元数据 scaleDenominator × 25.4/96/1000 独立换算）：

| 地标 | lng | lat | EPSG:3857 x | EPSG:3857 y | z14 col | z14 row |
| --- | --- | --- | --- | --- | --- | --- |
| 北京天安门 | 116.3913 | 39.9075 | 12956620.248767 | 4852509.522163 | 13489 | 6208 |
| 上海外滩 | 121.4900 | 31.2400 | 13524204.936475 | 3663957.093090 | 13721 | 6694 |
| 广州塔 | 113.3245 | 23.1066 | 12615225.634402 | 2644915.204952 | 13349 | 7110 |

步骤：

1. 在 `web/.env.local` 配置用户许可的真实 key 与天地图 vec_w/cva_w 地址（HTTPS，`tk={key}`），不提交仓库；控制台按部署域名设置白名单。
2. 启动开发环境，打开实时监控页；在浏览器 Network 抓取 `GetTile` 请求，确认 `LAYER=vec/cva`、`TILEMATRIXSET=w`、`STYLE=default`、`FORMAT=tiles`，响应 200 且为图片；记录浏览器版本、时间与请求列表（截图脱敏，不含 key）。
3. 用模拟终端上报上表已知坐标，核对车辆标记中心与底图地标重合；或直接请求 `TILEMATRIX=14&TILEROW=<row>&TILECOL=<col>` 的 vec/cva 瓦片，核对注记叠合。偏差应只来自 WGS84→Web Mercator 转换，不应存在 GCJ-02 偏移。
4. 在 DevTools 断网或拦截 tianditu 域名，确认错误提示出现且车辆列表可用；记录响应状态。
5. 在部署环境重复步骤 2—4，分别记录开发与部署网络结论。
6. 登录控制台核对当前账户额度、服务条款与 attribution 要求，更新本文件与任务 Answer。

## 证据索引

- `26-browser-red.log`：TDD 红测（attribution testid 缺失，2 项失败，Maven exit 1）。
- `26-browser-green.log`：最终 BrowserFlowTest，BUILD SUCCESS，27 项 Playwright 全通过（02-playwright.log 为同名轮次明细，位于 evidence/logs/）。
- `26-typecheck.log`、`26-lint.log`、`26-build.log`：静态检查与构建。
- `26-wmts-fixture-failure.png`、`26-invalid-key-fixture.png`：失败态辅助截图（fixture，非真实底图）。
- 服务元数据 fixture：`web/e2e/fixtures/tianditu-wmts-metadata.json`。
- 数据库：本票无 migration、无 schema 变更（本轮 Testcontainers 仅执行 V1—V6 既有迁移）。
