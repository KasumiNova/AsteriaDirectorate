---
name: "package-structure-guidelines"
description: "ASTD 包结构规范：多模块布局、根包、internal、renderer、combat 及各领域 base 包约定。"
---

# Skill：包结构规范

## 模块布局（Gradle 多模块）

模块化拆分后源码按模块分布；**模块是物理边界，包名是逻辑边界——拆分不改 FQN**（`contents/` 与 ss-csv 的类字符串引用不受影响）。

- `modules/api/astd-api`：对外/模块内交互 API（buff、difficulty 等），包 `cn.kasuminova.astd.api.*`。
- `modules/api/astd-api-render`：渲染专用 API（api 超集）：RenderEntity 树接口、texTrail/flare/arc spec 蓝图、弹体/光束 VFX DSL，包 `cn.kasuminova.astd.api.render` / `cn.kasuminova.astd.impl.render`（spec 纯数据）/ `cn.kasuminova.astd.renderer.*.driver`（DSL 与策略）。
- `modules/internal/astd-impl`：通用功能实现（I18n、difficulty、buff 实现等），包 `cn.kasuminova.astd.impl.*` / `cn.kasuminova.astd.internal.*`。
- `modules/internal/astd-ui`：UI 特化（dialog DSL、HUD 消息、tooltip 背景），包 `cn.kasuminova.astd.campaign.dialog.core` / `cn.kasuminova.astd.campaign.dialog.story`（剧情对话脚本与工厂）/ `cn.kasuminova.astd.campaign.ui` / `cn.kasuminova.astd.ui.*`。
- `modules/internal/astd-render`：非特化渲染实现（RenderEntity 树实现、texTrail/flare/arc 组件、shader 运行时、弹体/光束驱动），包 `cn.kasuminova.astd.impl.render` / `cn.kasuminova.astd.renderer.*`。
- `modules/internal/astd-combat`：武器/船插/战术系统/军官技能及武器特效具体实现，包 `cn.kasuminova.astd.combat.*`（含武器专属 VFX spec 定义，如 `ProjectileVfxSpecs`/`BeamVfxSpecs` 在 `cn.kasuminova.astd.renderer.*.driver` 包但物理位于本模块）。
- `modules/internal/astd-campaign`：生涯模式功能（赏金、剧情线等），包 `cn.kasuminova.astd.campaign.*`。
- `modules/internal/astd-automation`：自动化测试功能与测试战役定义（`contents/` 资源也在本模块），**不随 release 打包**（`-Pastd.includeAutomation=false`）。
- `modules/internal/astd-csv`：CSV/数据文件生成器（原 `ss-csv`），包 `cn.kasuminova.astd.sscsv`，产物仍输出 `build/generated/ss-csv/`。
- 根工程：装配工程（SDG mod 插件、`contents/`、mod 入口 `src/main/java/cn/kasuminova/astd/AsteriaDirectoratePlugin.java`、字节码 agent），各模块向根 jar 贡献产物。

依赖方向：`api ← api-render ← impl ← ui/render ← combat ← campaign ← 根装配`；`automation` 独立于链路。禁止反向依赖（如 api 不得引用 impl 实现类，跨层桥用 holder 注入，例：`BuffBackends` + `BuffInstall.install()`）。

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
- `ss-csv` 已完成结构重构：迁移至 `modules/internal/astd-csv`（包名 `cn.kasuminova.astd.sscsv`）。
