# 26: 真实天地图接入验证

Status: blocked
Labels: ready-for-agent
Type: task
Blocked by: 07

## What to build

使用用户控制的真实天地图 key（放本地或部署配置，不进仓库、不进日志）配置道路底图与中文注记 WMTS。核对服务地址、图层名、tileMatrixSet 与样式参数，确认服务 CRS 与 WGS84 数据的投影转换，用已知坐标点比对对齐并留存证据。按供应商允许的浏览器 key 使用方式配置白名单或相应限制；浏览器请求中可见的 key 不当作服务端秘密，日志与截图应脱敏，不在界面正文或文档展示其值。按供应商条款显示 attribution，核对额度、服务条款与开发、部署网络的可用性。网络失败或 key 无效时页面显示错误并保留车辆列表。缺少 key 时仅本票 blocked，不影响其他票；27 号最终交付依赖本票完成。不代用户注册或购买服务，不在聊天中索取 key。

## Spec coverage

主：US45、AC30。次（复验）：US44 底图失败仍可用、AC30 中不以截图冒充实时验证。详见 [spec](../spec.md)。

## Acceptance criteria

- [ ] 真实 key 配置后道路与中文注记底图可见
- [ ] CRS 与坐标对齐有已知点比对证据
- [ ] attribution 按供应商条款正确显示
- [ ] 额度与服务条款核对结论有记录
- [ ] 开发环境与部署网络分别验证
- [x] 网络失败时显示错误且车辆列表可用
- [x] 无 key 时仅本票 blocked，不伪造结果
- [x] 不以静态截图冒充实时底图验证

## Scope boundaries

不申请账户、不购买服务、不做离线底图或未授权瓦片抓取、不做逆地理编码与地址搜索；不修改地图引擎选型（ADR 已定），只验证接入配置；不把真实 key 提交到仓库、文档或日志；不修改 07 的实现，只做配置与真实验证。

## Validation

在浏览器中加载真实底图并抓取网络请求，记录浏览器版本与时间；核对已知坐标点对齐；模拟断网与无效 key 验证错误提示；保存请求列表、响应状态与截图定位，截图仅作辅助证据，并记录服务返回的图层或版本信息以备复核；记录 key 来源方式（不记录 key 本身）、环境与证据。未执行项不得勾选。

## Comments

开发阶段可用占位图层，但本票必须以真实 key 与真实网络完成验证；key 额度不足时记录实际状态与影响范围，不以占位图层替代结论；26 未完成时 27 保持 blocked。

## Answer

真实天地图 key 未提供，本票 blocked；AC30 未通过，真实底图、真实网络与真实坐标对齐均未执行，未勾选未执行项。

已完成（不依赖真实 key）：

- 新增 `web/src/features/monitoring/basemap.ts`：集中解析 VITE_WMTS_*（URL、layer、matrixSet、style、format、attribution、key 占位符），key 仅编码后进入瓦片请求，错误信息不含 key。
- 修正 `VehicleMap.vue` 的 TileMatrix 编号：由 0..18 改为符合天地图服务的 1..18（层级 N 分辨率 = maxResolution / 2^N），并接入配置解析；新增 `data-testid="monitor-map-attribution"`，仅在底图配置时显示 attribution（默认 `天地图 · 国家地理信息公共服务平台` · OpenLayers）。
- 新增 `web/e2e/basemap-config.spec.ts`（8 项）与 `web/e2e/fixtures/tianditu-wmts-metadata.json`：用公开 GetCapabilities 元数据核对 layer=vec、TileMatrixSet=w、EPSG:900913、层级 1—18 与分辨率，并给出已知点（天安门/外滩/广州塔）WGS84→Web Mercator 与 z14 瓦片行列号比对基准。未抓取瓦片。
- `web/e2e/monitoring.spec.ts` 覆盖：attribution 显示、key 只进入瓦片请求（页面正文/错误/console 不含 key）、403 与非图片响应时显示 `底图加载失败…` 且车辆列表可用。`BrowserFlowTest` 使用本地 fixture URL，不访问第三方底图。
- `web/.env.example` 补充 TileMatrix 起始层级、key 浏览器可见需域名白名单、不得提交仓库的说明。

命令与结果：

- `pnpm --dir web typecheck`、`lint`、`build`：均 exit 0。
- BrowserFlowTest（全局锁内串行）：红测 exit 1（attribution testid 缺失 2 项失败）；最终 BUILD SUCCESS，27 项 Playwright 全通过。
- 本轮 Testcontainers（MySQL 8.4/Redis 8.4/Ryuk）已回收，无残留；无 DB migration 或 schema 变更。

证据：

- `.scratch/vehicle-monitoring/evidence/26-verification.md`（逐项 AC 状态与真实 key 到位后的比对步骤）
- `26-browser-red.log`、`26-browser-green.log`、`26-typecheck.log`、`26-lint.log`、`26-build.log`
- `26-wmts-fixture-failure.png`、`26-invalid-key-fixture.png`（fixture 失败态，非真实底图）

未执行及原因：

- 真实 vec_w/cva_w 底图可见与真实瓦片请求记录：无真实 key。
- 真实底图已知点对齐：已准备基准与方法，未在真实底图上执行。
- 开发与部署网络分别验证：仅本地 fixture，无部署网络。
- attribution 按供应商条款核对：条款页为 SPA，仅获取页面外壳，正文未读到。
- 账户额度核对：需登录控制台，不代用户操作；官方文档仅确认存在个人/企业配额限制、需申请 Key、GetCapabilities 标注 Fees none / AccessConstraints none。

需要用户提供/确认：许可的真实天地图 key（配置在本地或部署环境，不在聊天/仓库/日志中出现）、部署域名白名单与网络可达性、账户额度与条款确认。之后可按 `26-verification.md` 的步骤完成 AC30；本票保持 blocked 不影响其他票。

