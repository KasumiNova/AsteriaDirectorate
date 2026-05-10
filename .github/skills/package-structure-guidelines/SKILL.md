---
name: "package-structure-guidelines"
description: "ASTD 包结构规范：根包、internal、renderer、combat 及各领域 base 包约定。"
---

# Skill：包结构规范

## 根包

新代码根包统一使用：

`cn.kasuminova.astd`

## 通用工具

`cn.kasuminova.astd.internal`

用于存放通用工具、基础设施、非业务特定代码。

## 渲染相关

`cn.kasuminova.astd.renderer`

展开后包含：

- `cn.kasuminova.astd.renderer.projectile`
- `cn.kasuminova.astd.renderer.system`
- `cn.kasuminova.astd.renderer.effect.projectile`
- `cn.kasuminova.astd.renderer.effect.skill`
- `cn.kasuminova.astd.renderer.effect.hullmods`
- `cn.kasuminova.astd.renderer.effect.affix`

## 战斗相关

`cn.kasuminova.astd.combat`

射弹/武器特效：

- `cn.kasuminova.astd.combat.effect.base`
- `cn.kasuminova.astd.combat.effect.generic`
- `cn.kasuminova.astd.combat.effect.arc`
- `cn.kasuminova.astd.combat.effect.lens`
- `cn.kasuminova.astd.combat.effect.psi`

舰船系统：

- `cn.kasuminova.astd.combat.shipsystems`
- `cn.kasuminova.astd.combat.shipsystems.base`

军官技能：

- `cn.kasuminova.astd.combat.skills`
- `cn.kasuminova.astd.combat.skills.base`

舰船插件：

- `cn.kasuminova.astd.combat.hullmods`
- `cn.kasuminova.astd.combat.hullmods.base`
- `cn.kasuminova.astd.combat.hullmods.affix`

ASTD Affix 体系：

- `cn.kasuminova.astd.combat.affix`
- `cn.kasuminova.astd.combat.affix.base`

## 文件组织

- 每个特效使用单一 Kt 文件，即便可能存在多个类。
- 每个系统、技能、Hullmod、Affix 使用单一 Kt 文件，即便可能存在多个类。
- 系统、技能、Hullmod、Affix 的具体实现文件中不放抽象类。
- 抽象类和通用接口放入各领域 `base` 包。
- 涉及渲染的额外实现可以放入 `cn.kasuminova.astd.renderer.effect.xxx`。
- `ss-csv` 项目需要安排结构重构；第一轮优先完成包名迁移。
