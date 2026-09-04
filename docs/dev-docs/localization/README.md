# 本地化/字符串迁移（Hardcoded Text Migration）

本仓库的目标是避免把“玩家可见文本”硬编码在代码里（Kotlin/Java），以免后续做多语言时需要大规模翻找与改动。

---

## 现有机制（已使用）

- `contents/data/strings/strings.json`
  - Starsector 默认会加载该文件进入 StringManager（可直接 `settings.getString(category, key)`）。
- `src/main/kotlin/.../util/I18n.kt`
  - 统一入口：`I18n[category, key]` / `I18n.t(category, key, "var" to value)`
  - 支持变量替换：`%var%`
  - 支持额外字符串表：当前已加载：
    - `contents/data/strings/bounty_strings.json`

---

## 新增：硬编码文本扫描

脚本：`tools/scan_hardcoded_text.py`

用途：扫描 `src/main` 下 Kotlin/Java 代码中包含中文（CJK）的字符串字面量，帮助逐步迁移到 `data/strings/*.json`。

使用：
- `python3 tools/scan_hardcoded_text.py`
- `python3 tools/scan_hardcoded_text.py --max 500`

> 这是启发式扫描，不是完整语法解析器：可能漏报/误报，但对“先把大头迁掉”很好用。

---

## 迁移规范

1. **新增 key**
   - 通用 UI/对话：放 `contents/data/strings/strings.json` 或拆分到额外表并在 `I18n` 中登记。
   - 赏金相关：优先放 `contents/data/strings/bounty_strings.json`（分类 `asteria_directorate_bounty`）。

2. **代码读取方式**
   - Kotlin：
     - `I18n[I18n.Categories.MOD, "some.key"]`
     - `I18n.t(I18n.Categories.MOD, "some.key", "var" to value)`
   - Java：
     - `I18n.j("asteria_directorate", "some.key")`
     - `I18n.j1("asteria_directorate", "some.key", "var", value)`

3. **不要在 KDoc 里写 `data/strings/*`**
   - Kotlin 支持嵌套块注释，`/*` 会导致文档注释解析混乱。

---

## 已迁移示例

- 赏金 HUD 文案：重组舰队提示、主线推进提示、支线打捞提示
  - `contents/data/strings/bounty_strings.json`
  - `campaign/bounty/*`
