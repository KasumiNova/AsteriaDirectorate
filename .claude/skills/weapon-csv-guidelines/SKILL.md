---
name: "weapon-csv-guidelines"
description: "weapon_data.csv 编辑规范：关键字段、列对齐、引号安全与 ss-csv 工作流。"
---

# Skill：weapon_data.csv 编辑规范

## 适用范围

适用于本项目的 `contents/data/weapons/weapon_data.csv` 及其 `ss-csv` 生成逻辑。

目标：

- 保持列对齐与可读性
- 避免字段错位导致的静默异常
- 与 `ss-csv` 生成流程一致，避免手改被覆盖

## 关键字段与文本分工

`weapon_data.csv` 中与文本相关的关键字段：

- `customPrimary` / `customPrimaryHL`
- `customAncillary` / `customAncillaryHL`

建议：

- 主体文本放在 `customPrimary/customAncillary`
- 变量/高亮替换放在 `customPrimaryHL/customAncillaryHL`

> 文本换行与变量替换规范详见 `.agents/skills/csv-text-guidelines/SKILL.md`。

## 列对齐与缺省值

`weapon_data.csv` 一旦列错位，会导致：

- `tags/groupTag/tech/...` 串列
- `noDPSInTooltip/number` 被错读
- 游戏侧读取异常（可能静默，也可能表现诡异）

规则：

- 数值字段无意义时填 `0`，不要留空（尤其是中间段数值列）
- 每次改动后检查：
  - 行列数与 header 一致
  - `id`、`tags`、`tech/manufacturer`、`primaryRoleStr`、`noDPSInTooltip`、`number` 落位正确

## 引号与逗号的安全写法

- 字段内包含逗号/引号/换行时，必须使用双引号包裹
- 字段内双引号需双写为 `""`

## ss-csv 工作流建议（可选）

- 默认只生成到 `build/generated/ss-csv/**`
- 只有明确需要覆盖 `contents/` 才执行写回任务（需 `-PssCsvForce=true`）
- 写回后复查 `weapon_data.csv` 列对齐与引号完整性

## 常见坑位清单

- 列错位：中间段数值列留空导致整体右移
- 引号错误：字段内含逗号/换行但未加引号
- 替换失效：`customPrimary` 与 `customPrimaryHL` 占位/数量不匹配

## 本项目参考实现

- `contents/data/weapons/weapon_data.csv`：`astd_stellar_jet_emitter`
- `ss-csv/src/main/resources/i18n/zh-cn.properties`：`weapon.astd_stellar_jet_emitter.tooltip.*`
