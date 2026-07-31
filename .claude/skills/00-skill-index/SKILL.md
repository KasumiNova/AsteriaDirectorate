---
name: "00-skill-index"
description: "Skill 索引：按领域汇总本仓库可用的所有技能与路径。"
---

# Skill 索引（按领域）

> 本索引用于快速定位技能文件。按目录名 `00-` 前缀放置，确保在列表最前面。

## 文档与源码阅读

- **外部文档与源码阅读优先级**
  - 路径：`.agents/skills/docs-and-source-reading/SKILL.md`
  - 适用：先读工作区 → 游戏目录 mods sources/jar → dev-resources →（可选）Gradle cache。

## 本地化（I18n）

- **本地化（I18n）规范**
  - 路径：`.agents/skills/localization-guidelines/SKILL.md`
  - 适用：逻辑代码禁止硬编码文本；使用 `I18n/I18nUi` 做格式化与高亮。

## 命名与前缀

- **前缀规范（ASTD / astd）**
  - 路径：`.agents/skills/prefix-guidelines/SKILL.md`
  - 适用：新增类名/数据 id 优先使用 ASTD/astd；限制引入新前缀（武器体系例外）。

## 包结构

- **包结构规范**
  - 路径：`.agents/skills/package-structure-guidelines/SKILL.md`
  - 适用：根包、internal、renderer、combat、base 包与单文件组织约定。

## ss-csv 与 CSV 生成/编辑

- **ss-csv 使用规范与 CSV 编辑规范**
  - 路径：`.agents/skills/ss-csv-guidelines/SKILL.md`
  - 适用：entries 规则、schema header 真相来源、生成/写回流程。

- **CSV 文本编辑规范（换行 / 变量替换）**
  - 路径：`.agents/skills/csv-text-guidelines/SKILL.md`
  - 适用：所有 CSV 文本字段的换行、占位符与 `%` 安全写法。

- **weapon_data.csv 编辑规范**
  - 路径：`.agents/skills/weapon-csv-guidelines/SKILL.md`
  - 适用：武器 CSV 的列对齐、关键字段分工、ss-csv 工作流。

## 对话系统（Dialog DSL）

- **Dialog DSL 使用指南**
  - 路径：`.agents/skills/dialog-dsl-guidelines/SKILL.md`
  - 适用：对话图/节点/选项/动作/延迟文本队列与常见用法。

## BoxUtil 渲染/VFX

- **BoxUtil 使用指南（API / 调试 / 避坑点）**
  - 路径：`.agents/skills/boxutil-guidelines/SKILL.md`
  - 适用：BoxUtil 初始化、实体与材质、实例化渲染、调试与性能建议。

- **渲染 / 特效规范（优先 BoxUtil）**
  - 路径：`.agents/skills/rendering-vfx-guidelines/SKILL.md`
  - 适用：战斗 VFX 优先 BoxUtil；统一接入弹体 VFX 管线；必要时才降级原版渲染 API。

- **弹体拖尾规范（texTrail DSL）**
  - 路径：`.agents/skills/projectile-trail-guidelines/SKILL.md`
  - 适用：弹体拖尾统一 texTrail DSL；素材清单（astd_trails_ 族）、调参指南（MagicTrail 四件套对照）、验证流程。

- **通用游戏特效预览工具规范**
  - 路径：`.agents/skills/game-vfx-preview-guidelines/SKILL.md`
  - 适用：`tools/game-vfx-preview/` 的 MD3 组件库、WebGL 渲染、effect preset、样式边界与验收规范。

## 系统设计

- **系统状态文本规范（台词风格）**
  - 路径：`.agents/skills/system-status-text-guidelines/SKILL.md`
  - 适用：`getStatusData()` 返回台词风格文本；多状态/多模式键名约定；禁止在状态行写属性数值。

- **舰船系统描述文本规范**
  - 路径：`.agents/skills/ship-system-description-guidelines/SKILL.md`
  - 适用：`descriptions.csv` 中 `SHIP_SYSTEM` 的 `text1/text2/text3/text4/text5` 字段分工；图鉴/装配界面/效果文本显示位置；原生换行。

- **舰船引擎布局规范（1 主 + 2 辅引擎簇）**
  - 路径：`.agents/skills/ship-engine-layout-guidelines/SKILL.md`
  - 适用：`*.ship` 的 `engineSlots` 统一为“1 主喷口 + 2 辅喷口”簇；主辅尺寸比例、法线偏移、样式归属与现有舰船改造流程。

- **船插互斥 / 禁装实现规范**
  - 路径：`.agents/skills/hullmod-incompatibility-guidelines/SKILL.md`
  - 适用：内置船插或特殊舰体需要禁止、清理或提示不兼容船插；区分硬清理、MagicLib warning 与 UI 灰掉方案。

## 文案风格

- **文案风格硬约束（通用规则 / 分类模板 / 审查清单）**
  - 路径：`.agents/skills/copy-style-guidelines/SKILL.md`
  - 适用：全部玩家可见文本的通用规则、分类模板、审查清单、双线词库与禁忌词表。新增/修改文案后强制调用 `@copy-review` 审查。
