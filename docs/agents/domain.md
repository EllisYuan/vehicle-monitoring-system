# Domain docs

## 读取规则

探索业务代码前，读取根目录 `GLOSSARY.md`（若存在），并读取 `docs/adr/` 中与当前任务相关的 ADR。

文件尚未创建时继续任务；术语或设计决策明确后，再通过 `/domain-modeling` 按需记录。

## 布局

采用 single-context：

- `GLOSSARY.md`：项目共享的业务术语。
- `docs/adr/`：项目的 Architecture Decision Records。

## 使用规则

Issue、代码、测试和设计讨论中的业务概念使用 GLOSSARY 定义的术语。
发现术语缺口时，记录待澄清项。

建议与现有 ADR 冲突时，指出对应 ADR 及重新讨论的理由。
