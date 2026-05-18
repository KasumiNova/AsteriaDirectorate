---
name: "prefix-guidelines"
description: "前缀规范：新增类名前缀/数据 id 前缀优先使用 ASTD / astd，禁止随意引入其他前缀。"
---

# Skill：前缀规范（ASTD / astd）

## 目标

- 保证本模组的**类名**与**数据 id** 可一眼识别来源，避免与其他模组冲突。
- 降低重构/迁移成本：统一命名规则后，批量查找、脚本生成、翻译 key 维护更稳定。

## 总规则（强制）

- 新增任何**特定于本模组**的：
  - 类/对象/顶层工具（Kotlin/Java）
  - 数据 id（ship/weapon/system/hullmod/wing/faction/industry 等）
  - 资源 id（json/csv 内的 id 字段、脚本引用的 specId）

都必须优先使用：

- **类名前缀：`ASTD`**（大写）
- **id 前缀：`astd_`**（小写 + 下划线）

除非确实有必要（见“例外”）。

## 适用范围

### 1) 类名前缀（ASTD）

适用于：

- 新增的公共/可复用工具类、系统入口类、规则类、数据结构等
- 与具体内容线无关的“基础设施”代码（例如通用 VFX、通用 campaign 工具）

推荐：

- `ASTD*`：顶层 public class/object
- `ASTD_*`：常量（如选项 id、memory key）

> 说明：当前项目已有 `AsteriaDirectoratePlugin`、以及大量 `Smd*` 类名（历史原因/领域分组）。新增代码以本规范为准：**优先 ASTD**。

### 2) 数据/资源 id 前缀（astd_）

适用于所有会进入 Starsector 数据系统的 id：

- ship hullId（`data/hulls/ship_data.csv`）
- hullmod id（`data/hullmods/hull_mods.csv`）
- ship system id（`data/shipsystems/ship_systems.csv` 与 `.system` 文件名）
- weapon id（`data/weapons/weapon_data.csv` 与 `.wpn/.proj`）
- 任务/实体/自定义内容 id（rules/missions/custom_entities 等）

推荐格式：

- `astd_<domain>_<name>`
  - domain 示例：`ship`/`sys`/`hm`/`wpn`/`fx`/`ai`（按需要，但不要滥造）
  - name 用小写 snake_case

> 已有内容中存在 `astd_*` id（例如 hullmod/ship/system 等），应延续这一前缀以保持一致。

## 例外（允许，但必须“有理由 + 成体系”）

### 例外 1：武器/战斗特效体系的既有前缀

仓库中已存在 `smd_` 相关资产命名（例如构建阶段确保 `contents/graphics/fx/smd_generated_ring.png` 存在；以及历史脚本生成 `smd_trail_*`）。

因此：

- 若你在“武器/战斗特效”领域需要保持与既有资源、工具链、资产集一致，允许使用 `smd_`。
- 但必须满足：
  - 仅限该武器/特效体系内部
  - 同一体系内前缀保持一致（不要混用 `smd_`、`abc_`、`test_`）
  - 新引入的其它前缀需要在 PR/提交说明中写明原因

### 例外 2：上游/外部生态强制前缀

某些外部模组/框架/工具可能要求特定前缀才能被扫描或与已有数据兼容。

- 这种情况下允许使用外部要求的前缀
- 但应尽量把“外部前缀”限制在最小范围，并在注释/文档中说明原因

## 与本项目工具链的关系

- ss-csv：新增 entry 的 `id`/`key` 同样遵循 `astd_`（除非落入武器例外）。
- 本地化：本规范不强制 i18n key 前缀，但建议按领域组织（`ui.*`、`dialog.*`）。

## 自检清单

- [ ] 我新增的类/对象是否用 `ASTD*`（除非有明确领域例外）？
- [ ] 我新增的数据 id 是否以 `astd_` 开头（除非有明确领域例外）？
- [ ] 若使用了 `smd_` 或其他前缀，是否写明“为什么必须这样做”，且只在该体系内使用？

## 参考

- `gradle.properties`：`mod.id=asteria_directorate`
- `build/mod_production/mod_info.json`：最终 mod id 与依赖清单
- `build.gradle.kts`：含 `smd_generated_ring.png` 的历史兼容说明
- `tools/gen_trail_textures.py`：含 `smd_trail_*` 的历史命名说明
