---
name: "00-skill-index"
description: "Skill 索引：按领域汇总本仓库可用的所有技能与路径。"
---

# Skill 索引（按领域）

> 本索引用于快速定位技能文件。按目录名 `00-` 前缀放置，确保在列表最前面。

## 文档与源码阅读

- **外部文档与源码阅读优先级**
  - 路径：`.github/skills/docs-and-source-reading/SKILL.md`
  - 适用：先读工作区 → 游戏目录 mods sources/jar → dev-resources →（可选）Gradle cache。

## 本地化（I18n）

- **本地化（I18n）规范**
  - 路径：`.github/skills/localization-guidelines/SKILL.md`
  - 适用：逻辑代码禁止硬编码文本；使用 `I18n/I18nUi` 做格式化与高亮。

## 命名与前缀

- **前缀规范（ASTD / astd）**
  - 路径：`.github/skills/prefix-guidelines/SKILL.md`
  - 适用：新增类名/数据 id 优先使用 ASTD/astd；限制引入新前缀（武器体系例外）。

## 包结构

- **包结构规范**
  - 路径：`.github/skills/package-structure-guidelines/SKILL.md`
  - 适用：根包、internal、renderer、combat、base 包与单文件组织约定。

## ss-csv 与 CSV 生成/编辑

- **ss-csv 使用规范与 CSV 编辑规范**
  - 路径：`.github/skills/ss-csv-guidelines/SKILL.md`
  - 适用：entries 规则、schema header 真相来源、生成/写回流程。

- **CSV 文本编辑规范（换行 / 变量替换）**
  - 路径：`.github/skills/csv-text-guidelines/SKILL.md`
  - 适用：所有 CSV 文本字段的换行、占位符与 `%` 安全写法。

- **weapon_data.csv 编辑规范**
  - 路径：`.github/skills/weapon-csv-guidelines/SKILL.md`
  - 适用：武器 CSV 的列对齐、关键字段分工、ss-csv 工作流。

## 对话系统（Dialog DSL）

- **Dialog DSL 使用指南**
  - 路径：`.github/skills/dialog-dsl-guidelines/SKILL.md`
  - 适用：对话图/节点/选项/动作/延迟文本队列与常见用法。

## BoxUtil 渲染/VFX

- **BoxUtil 使用指南（API / 调试 / 避坑点）**
  - 路径：`.github/skills/boxutil-guidelines/SKILL.md`
  - 适用：BoxUtil 初始化、实体与材质、实例化渲染、调试与性能建议。

- **渲染 / 特效规范（优先 BoxUtil）**
  - 路径：`.github/skills/rendering-vfx-guidelines/SKILL.md`
  - 适用：战斗 VFX 优先 BoxUtil；统一接入弹体 VFX 管线；必要时才降级原版渲染 API。

## 系统设计

- **系统状态文本规范（台词风格）**
  - 路径：`.github/skills/system-status-text-guidelines/SKILL.md`
  - 适用：`getStatusData()` 返回台词风格文本；多状态/多模式键名约定；禁止在状态行写属性数值。
