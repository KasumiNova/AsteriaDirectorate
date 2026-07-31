---
name: "localization-guidelines"
description: "本地化(I18n)规范：逻辑代码禁止硬编码文本，统一使用本项目 I18n API 与 strings.json/额外字符串表。"
---

# Skill：本地化（I18n）规范

## 目标

- **禁止硬编码 UI 文本**：逻辑代码中不直接写中文/英文句子，避免未来本地化无法覆盖。
- 统一使用本项目的本地化 API：`cn.kasuminova.astd.util.I18n` / `I18nUi`。
- 动态文本使用**命名占位符**，避免 `String.format` / `%` 引发崩溃或不可控格式化。

## 适用范围

- Kotlin/Java 逻辑代码产生的所有可见文本：
  - `TextPanelAPI` / `TooltipMakerAPI` / `OptionPanelAPI` / HUD message
  - Dialog DSL（`DialogContext.sayI18n/enqueueI18n/hudMessageI18n`）

不包含（但建议配合）：

- `descriptions.csv`（更偏“数据驱动描述”）
- ss-csv 生成器自己的 `SsI18n`（构建期用，非运行时 UI）

## 文本存放位置（真相来源）

- 运行时主表：`contents/data/strings/strings.json`
  - 建议使用 `I18n.Categories.MOD`（即 modId `asteria_directorate`）作为 category。
- 额外字符串表（兜底/扩展）：`contents/data/strings/bounty_strings.json`
  - `I18n` 在 `settings.getString(category,key)` 找不到时，会尝试从该 JSON 按 category 读取。

## 必须遵守的规则

### 规则 1：逻辑代码不允许硬编码可见文本

- ❌ 不允许：
  - `text.addPara("你获得了奖励")`
  - `tooltip.addPara("伤害：" + dmg, ...)`
  - `options.addOption("离开", "leave")`

- ✅ 允许：
  - `ctx.sayI18n(I18n.Categories.MOD, "ui.reward", "amount" to amount)`
  - `DialogDsl.option(id = OPT_LEAVE, text = I18n[I18n.Categories.MOD, "ui.leave"], action = DialogDsl.close())`

> 例外：debug 日志、异常信息、内部 key/id（不面向玩家显示）可以硬编码。

### 规则 2：一律使用 I18n 的命名占位符，不使用 String.format

本项目 I18n 支持两种替换语法：

1) 命名占位符：`%key%`
- `%%` 会转义为字面 `%`

2) 可高亮参数标记：`<param:#RRGGBB:key>`
- 在 `I18n.tr(...)` / `I18nUi.addPara(...)` 这类“富渲染”路径会携带高亮色
- 在 `I18n.t(...)` 纯文本路径会被当作普通变量替换（忽略高亮语义）

### 规则 3：缺参要“可见且可定位”

- 未提供的 key：I18n 默认保留原样或退回到 key 本身（便于发现漏传参）。
- 因此请保证：
  - 变量名尽量短且有意义（例如 `targetName`、`amount`、`days`）
  - key 命名带领域前缀（例如 `dialog.watcher.*`、`ui.*`）

## 推荐用法（按场景）

### 1) 纯字符串（不需要高亮）

- `I18n.t(Categories.MOD, "ui.cost", "cost" to cost)`

适用：HUD message、日志式提示、无需多色高亮的文本。

### 2) Tooltip/Label 多色高亮

- 推荐：`I18nUi.addPara(tooltip, Categories.MOD, "ui.cost", pad, baseColor, "cost" to cost)`

在翻译文本中写：

- `"费用：<param:#F1C40F:cost>"`

### 3) TextPanel（对话文本）

- `ctx.sayI18n(CAT, "dialog.demo.intro.0", "targetName" to targetName)`
- `ctx.enqueueI18nFading(CAT, "dialog.demo.timed.1", delay = 0.6f, fadeIn = 0.25f)`

说明：`DialogContext` 已封装好 `sayI18n/enqueueI18n/hudMessageI18n`，优先使用它。

### 4) OptionPanel（选项文本）

- 选项的 `id` 是内部标识（可硬编码常量），但 `text` 必须本地化：
  - `text = I18n[CAT, "dialog.demo.option.leave"]`

## key 命名约定（建议）

- UI 通用：`ui.<feature>.<name>`
- Dialog：`dialog.<story>.<node>.<line>` 与 `dialog.<story>.option.<name>`
- HUD：`hud.<feature>.<name>`

保持与现有约定一致（见 `strings.json` 中 `dialog.demo.*`、`dialog.watcher.*`）。

## 常见坑位

- **直接写中文**：后续翻译补丁无法覆盖。
- **拼接字符串**：`"伤害：" + dmg` 使语序不可翻译；应把模板放到 strings，数值用占位符。
- **误用 `%`**：不要依赖 `String.format`；按本项目 I18n 的 `%key%` 规则写。
- **把可变状态写进单例节点**：与本地化无关但常一起出现（请把状态放 `sessionState` / Memory）。

## 参考文件

- `src/main/kotlin/cn/kasuminova/astd/util/I18n.kt`
- `src/main/kotlin/cn/kasuminova/astd/util/I18nUi.kt`
- `contents/data/strings/strings.json`
- `contents/data/strings/bounty_strings.json`
- Dialog 示例：`src/main/kotlin/.../campaign/dialog/demo/DemoDialog.kt`
