# 车辆监控系统

## 项目入口

需求、架构、数据库或接口任务先读 `docs/design/系统设计方案.md`；决策追溯查 `docs/design/方案对齐.md` 与相关 `docs/adr/`。整合稿待整体确认时，仅整理文档与核查；用户确认整体共识后再进入业务实现。
`../explore/README.md` 导航的 2026-11-06 规划保留为历史参考，使用其中字段、协议或论文材料前核对当前设计与原始来源。
实现状态以本仓库源码和实际验证记录为准；规划中的功能与版本属于设计依据。

## Agent skills

### Issue tracker

使用 Local markdown，在 `.scratch/<feature>/` 管理 spec 和独立 ticket。
创建、读取或更新任务前，读取 `docs/agents/issue-tracker.md`。

### Domain docs

采用 single-context：根目录 `GLOSSARY.md` 和 `docs/adr/`。
探索业务术语或相关设计决策前，读取 `docs/agents/domain.md`。
