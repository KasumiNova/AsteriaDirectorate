---
name: "ss-csv-guidelines"
description: "ss-csv 使用规范与编辑规范：生成流程、目录约定、entry 规则、schema header 真相来源；以及 .system/.proj/.wpn 等副产物的指定格式编辑规范（不手改生成物，改 entry/outputs 后生成）。"
---

# Skill：ss-csv 使用规范与 CSV 编辑规范

> 模块迁移说明：`ss-csv` 已迁移至 `modules/internal/astd-csv`（Gradle 子项目名 `:astd-csv`），职责与产物路径不变。

## 适用范围

本规范适用于所有通过 ss-csv 生成的 Starsector 数据文件，尤其是：

- `data/weapons/weapon_data.csv`
- `data/hulls/ship_data.csv`
- `data/shipsystems/ship_systems.csv`
- `data/hullmods/hull_mods.csv`
- `data/strings/descriptions.csv`

以及与这些 CSV 同步生成的 `.system` / `.proj` / `.wpn` / 其他 JSON 文件。

> 强化约定：当你要编辑 `.system` / `.proj` / `.wpn` 等 **非 CSV** 文件时，也应把它们视为 ss-csv 体系的一部分来处理：
> - 先判断它是否由 ss-csv 生成/管理
> - 若是副产物：不要手改文件本体，应改 entry/outputs 并重新生成

## 核心原则

- **默认只生成到 build**：优先运行 `:astd-csv:generateSsCsv`，产物在 `build/generated/ss-csv/`。
- **避免手改生成物**：不要手改 `build/**` 与 `contents/**` 下由 ss-csv 管理的 CSV；应修改 `ss-csv` 的 entries。
- **schema header 是真相来源**：`tools/_schema_headers/*.header.csv` 的首行是列定义真相；生成器只读取首行。
- **按 key 排序 + 去重**：生成器按 `SsCsvEntry.key` 排序，重复 key 会直接失败。
- **遵守仓库全局约束**：ss-csv 的生成/写回流程以 `.agents/AGENTS.md` 为准（默认只生成到 build）。

## 生成入口与目录约定

- 入口任务（见 `modules/internal/astd-csv/build.gradle.kts`）：
  - `:astd-csv:generateSsCsv` → 输出到 `build/generated/ss-csv/`（安全）
  - `:astd-csv:writeSsCsvToContents -PssCsvForce=true` → 覆盖写入 `contents/`（危险）
- i18n 语言：`-Psscsv.locale=zh-cn|en-us`（默认 `zh-cn`）
- 扫描包固定为：`cn.kasuminova.astd.sscsv.entries.catalog`（不在该包/子包的 entry 不会被扫描）

## Entry 编写规范（ss-csv 使用规范）

### 1) 必须是 Kotlin `object`

生成器只会实例化 **final 类**，并通过 `INSTANCE` 获取 Kotlin `object` 实例。

- ✅ 推荐：`object Wpn_xxx : WeaponDataEntry()`
- ❌ 不推荐：普通 `class` / 非 final 类型（不会被扫描/实例化）

### 2) 选择合适的基类

常用基类（已封装字段与列名映射）：

- `WeaponDataEntry` → `weapon_data.csv`
- `ShipDataEntry` → `ship_data.csv`
- `ShipSystemEntry` → `ship_systems.csv`
- `ShipSystemWithSystemFileEntry` → `ship_systems.csv` + `data/shipsystems/<id>.system`
- `HullModEntry` → `hull_mods.csv`
- `DescriptionEntry` → `descriptions.csv`

迁移期/无损行迁移：

- `MigratedCsvLineEntry`（实现 `SsCsvCellsEntry`）：保留原始 CSV 行字符串，按 header 输出 cells

### 3) `key` 与 `id` 的一致性

- `SsCsvEntry.key` 用于排序与去重，**通常必须等于 `id`**。
- key 重复会导致生成直接失败。

### 4) `toRow()` 与列对齐

- `toRow()` 返回 `Map<列名, 值>`；生成器按 header 列名顺序输出。
- 未提供的列会输出空值。
- 多余列不会被写入（只认 header 列）。

若需要完全保留原 CSV 的列结构/格式，使用 `SsCsvCellsEntry.toCells()`。

### 5) 注释与说明

- 使用 `@SsCsvComment("...")` 添加人类可读注释。
- 当前任务使用 `--comment inline`：注释会以 `# key: comment` 形式写入 CSV（同列数补空）。
- 若需要更安全的注释方式，可改为 `sidecar` 或 `both`（会写入 `_comments/*.md`）。

## CSV 编辑规范（内容安全与一致性）

### 1) 不在 `contents/` 手改 ss-csv 管理的 CSV

- `contents/data/**` 下的 CSV 一旦被 ss-csv 生成覆盖，手改内容将丢失。
- 如需变更内容，**修改 entry + i18n** 并重新生成。

### 2) 修改列结构必须同步 schema header

- 新增/删除列时：先改 `tools/_schema_headers/*.header.csv` 的首行，再同步 Entry 中的 `toRow()`。
- 生成器只读首行，因此不要在 header 文件里追加“说明行”。

### 3) i18n 与文本来源

- 文本建议从 `modules/internal/astd-csv/src/main/resources/i18n/<locale>.properties` 获取（`SsI18n.t()`/`SsI18n.f()`）。
- 约定：
  - `weapon.<id>.name`
  - `system.<id>.name`
  - `desc.<id>.<text1|text2|text3|text4|notes>`
- properties 中的 `\n` 会被解析为真实换行。

> 需要 tooltip 多行、占位与百分号安全写法时，请参考 `weapon-tooltip-descriptions` skill。
> - 通用文本：`.agents/skills/csv-text-guidelines/SKILL.md`
> - weapon_data.csv：`.agents/skills/weapon-csv-guidelines/SKILL.md`

### 4) 数值与格式

- 数值会按生成器规则格式化（去尾零、`-0` → `0`）。
- 避免产生 `NaN` / `Infinity` 值（可能导致异常或异常输出）。

## 额外文件输出（.system / JSON / 其他）

- 实现 `SsExtraOutputs`：可生成额外文件（相对路径在 `GeneratedFile.relativePath`）。
- 生成器会阻止越界路径（不能写出 `outDir`），且会检测重复文件名。
- `SsJsonOutputs` 可生成 JSON 内容（内部使用稳定格式化器 `JsonWriter`）。

示例：

- `ShipSystemWithSystemFileEntry` 自动生成 `data/shipsystems/<id>.system`。

## 非 CSV 副产物编辑规范（.system / .proj / .wpn / 其他 JSON）

### 0) 先判断：这个文件是否属于 ss-csv 的“生成物”？

满足以下任一条件，就按“生成物”处理：

- 该文件在 `build/generated/ss-csv/**` 中出现（或写回后会覆盖 `contents/**`）
- 你能在 `ss-csv` 的 entry/输出接口里找到它的 `relativePath`（例如 `.system`）
- 它的内容明显由某个 Kotlin entry 的字段/模型驱动（例如 `.proj` 的 specClass/默认 onFireEffect）

**规则**：

- ✅ 生成物：改 `ss-csv` 的 entry/outputs → 重新生成 →（必要时）写回
- ❌ 不要直接手改 `contents/` 下的生成物（会被覆盖，且会让“真相来源”分裂）

### 1) `.system`（舰船系统 JSON）

- 推荐基类：`ShipSystemWithSystemFileEntry`
- 行为：生成 `ship_systems.csv` 的同时，额外输出 `data/shipsystems/<id>.system`

当你要改 `.system` 字段（如 `type/aiType/aiScript/statsScript/useSound`）时：

- 改对应的 entry 字段/覆盖点
- 不要直接改 `contents/data/shipsystems/<id>.system`

### 2) `.proj`（弹体/导弹 spec JSON）

本仓库已内置 `.proj` 的 JSON 模型与输出接口：

- 模型：
  - `modules/internal/astd-csv/.../outputs/proj/ProjProjectileSpec.kt`
  - `modules/internal/astd-csv/.../outputs/proj/ProjMissileSpec.kt`
- 输出接口（由 entry mix-in 实现）：
  - `SsProjProjectileOutputs`
  - `SsProjMissileOutputs`

当你要改 `.proj`（例如 `onFireEffect`、导弹引擎槽、碰撞/贴图参数）时：

- 改 entry 中的 `projSpec`（`ProjectileProjSpec` / `MissileProjSpec`）或相关模型字段
- 重新生成产物，检查 `build/generated/ss-csv/data/weapons/proj/<id>.proj`

约定提示：

- `.proj` 的 `onFireEffect` 推荐统一使用 `ProjectileSpecOnFireDispatcher`（有默认值），以接入本项目 VFX 分发管线。

### 3) `.wpn`（武器 spec JSON）

`SsExtraOutputs` 的设计目标包含“weapon_data.csv + <id>.wpn (+ projectiles)”这一类输出，但当前仓库中 `.wpn` 是否由 ss-csv 生成，取决于是否存在对应的 outputs 实现。

因此：

- 若你确认某 `.wpn` 是由 ss-csv 管理/生成的：遵循本节规则，改 entry/outputs，不手改文件。
- 若当前没有 ss-csv 输出实现、且该 `.wpn` 是手写资源：可以修改，但建议同步规划迁移路径，避免未来引入 `.wpn` 生成后出现覆盖冲突。

## 推荐流程

1. 在 `modules/internal/astd-csv/src/main/kotlin/.../entries/catalog/**` 新增 `object` 条目。
2. 补充 i18n 文本到 `modules/internal/astd-csv/src/main/resources/i18n/<locale>.properties`。
3. 运行 `:astd-csv:generateSsCsv`，检查 `build/generated/ss-csv/**` 输出。
4. **仅在明确需要**写回 `contents/` 时，执行 `:astd-csv:writeSsCsvToContents -PssCsvForce=true`。

## 常见坑

- Entry 不在 `entries.catalog` 包内 → 不会被扫描。
- 不是 `object` / 不是 final → 不会被实例化。
- `key` 重复 → 生成直接失败。
- `toCells()` 返回列数不匹配 header → 生成失败。
- 变更 header 未同步 Entry → 列错位/空列。
- 手改 `contents/` CSV → 被生成覆盖。

## 参考文件

- `modules/internal/astd-csv/build.gradle.kts`
- `modules/internal/astd-csv/src/main/kotlin/.../gen/SsCsvGenerator.kt`
- `modules/internal/astd-csv/src/main/kotlin/.../CsvTarget.kt`
- `modules/internal/astd-csv/src/main/kotlin/.../entries/*Entry.kt`
- `tools/_schema_headers/*.header.csv`
- `modules/internal/astd-csv/src/main/resources/i18n/*.properties`

## 相关引用（建议一起阅读）

- `.agents/AGENTS.md`：ss-csv 默认只生成到 build、写回需要明确要求。
- `.agents/skills/weapon-tooltip-descriptions/SKILL.md`：tooltip 多行/高亮/百分号等 CSV 文本安全规则。
- `docs/engineering/05-testing.md`：构建/部署/ss-csv 生成的完整测试流程。
- `docs/engineering/03-debug-placeholders.md`：调试占位数据与生成链路的说明。
