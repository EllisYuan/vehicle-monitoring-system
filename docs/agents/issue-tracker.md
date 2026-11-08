# Issue tracker: Local markdown

本仓库的 spec 和 ticket 保存在 `.scratch/`，纳入 Git 版本管理。

## 文件约定

- 每个 feature 使用 `.scratch/<feature-slug>/`。
- Spec 保存在 `spec.md`。
- 每个 ticket 独立保存为 `issues/<NN>-<slug>.md`，从 `01` 编号。
- Ticket 顶部使用 `Status: open`、`Status: claimed`、`Status: blocked` 或 `Status: resolved` 记录状态。
- 依赖使用 `Blocked by: NN, NN` 记录。
- 讨论追加在 `## Comments` 下；完成结果与验证证据写入 `## Answer`。

## 操作约定

- “发布到 Issue tracker”：在对应 feature 下创建或更新 Markdown 文件。
- “读取 ticket”：读取用户指定路径；仅提供编号时，在对应 feature 下定位。
- 开始执行 ticket 时设置 `Status: claimed`。
- 完成验收后填写结果和验证证据，再设置 `Status: resolved`。

## Wayfinding

使用 `/wayfinder` 时，以 `.scratch/<effort>/map.md` 保存 Notes、Decisions-so-far 和 Fog，每个问题仍使用独立 ticket。
Ticket 使用 `Type: research|prototype|grilling|task` 记录类型。
按编号选取 open 且依赖均已 resolved 的 ticket；完成后将结论和 ticket 链接追加到 map 的 Decisions-so-far。
