# 引力透镜级 · 阶段二修订轮（切换器通用化 + 特效重做 + 引擎修复 bug）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复阶段二验收暴露的 9 项问题：双模式切换器通用化（让玩家真能切模式）、5 类特效重做/调参、ASTD 全系引擎熄火修复时间异常。

**Architecture:** 切换器从「每舰一套自造 ids + 缺拆即切逻辑」重构为「ASTD 通用双模式框架」（一个通用状态机 util + 一个通用切换器 hullmod + 各舰注册自己的 ModeConfig），arc 与 lens 共享，tooltip 动态适配。特效层：回声残影改为绑定认知撕裂 debuff 生命周期的每帧重绘（jitter/红随距离实时）、标记高光改周期脉冲扩散波、幽灵信号改导弹位置小特效 + 50% 熄火、潮汐场降亮 ≥50%。引擎修复 bug 独立排查（高嫌疑 `ASTDVectorThrustEngineManager` 每帧 setFlameLevel 干扰原版熄火修复计时）。

**Tech Stack:** Kotlin, Starsector combat/refit API（hullmod permaMod 状态机、`MagicRender.battlespace` 残影 jitter、`setFlameLevel`/engine API、`MissileAPI.flameOut`），shader host `CombatShaderRuntime`（lens shader effects），ss-csv 真相源（hullmod 注册）。

**权威源：** `docs/superpowers/specs/2026-06-20-gravitational-lens-redesign-design.md`、`docs/design/ships/purple/10-unique.md` §1。arc 切换器参考实现：`ASTDArcFlareHullModUtil.kt` + `ASTDArcFlareDualModeSwitcherHullMod.kt` + `ASTDArcFlare{Crewed,Automated}ModeHullMod.kt`。

**用户决策（本轮 AskUserQuestion 已定）：**
1. 切换器：**抽象通用切换器框架**（arc + lens 共享，各注册自己的 mode/系统，tooltip 动态）。
2. 回声残影：**绑定撕裂 debuff 生命周期常驻** + jitter/红色随「当前位置离过去坐标距离」实时计算（越近越强越红）。
3. 标记高光：**周期性脉冲扩散波** + 扭曲，颜色不变（误差紫 / 深水红）。

**配色基线：** LENS 紫主，辅色红/淡紫，无青色。

---

## 文件结构（修订轮）

**新建 — 通用切换器框架：**
- `src/main/kotlin/cn/kasuminova/astd/combat/hullmods/base/ASTDDualModeConfig.kt` — 通用双模式配置（数据类：switcher id / crewed mode id / automated mode id / next markers / crewed 系统 id / automated 系统 id）+ 通用状态机函数（ensureModeState/activateMode/migrateLegacy/clearIncompatibleCaptain 泛化自 arc）。
- `src/main/kotlin/cn/kasuminova/astd/combat/hullmods/base/ASTDDualModeSwitcherHullMod.kt` — 通用切换器 hullmod（`isASTDShip()`，tooltip 动态读当前 variant 模式状态显示当前/目标模式）。

**修改 — 切换器接入：**
- arc：`ASTDArcFlareHullModUtil.kt`（改为基于通用 util，保留 arc ids 为一个 ModeConfig）、`ASTDArcFlare{Crewed,Automated}ModeHullMod.kt`（拆即切段改调通用 util）、arc 切换器 hullmod 注册改指向通用类（或保留 arc 类作薄包装——见 Task 2 决策）。
- lens：`LensArrayCoreModeUtil.kt`（改为基于通用 util，lens ModeConfig）、`ASTDLens{Crewed,Automated}ModeHullMod.kt`（**补拆即切段**）、`ASTDLensDualModeSwitcherHullMod.kt`（废弃，改用通用切换器 或 薄包装）。
- ss-csv：`Catalog_HullMods_LENS.kt` / `Catalog_HullMods_ARC*.kt`（切换器注册指向通用类，若采用单一通用切换器 id）+ strings.json（通用切换器动态 tooltip i18n）。

**修改 — 特效：**
- `EchoFixationField.kt` — EchoTearState 增存过去坐标；残影改由每帧（撕裂存活期）驱动。
- `EchoFixationAfterimageRenderer.kt` — 改为「每帧重绘 + jitter + 红色随距离」的常驻残影渲染（不再一次性淡出）。
- `PermeatingTideFieldEffect.kt` — alphaMult 降亮 ≥50%。
- `MarkHighlightShaderSource.kt` + `DriftMarkVisualEffect.kt` + `DeepWaterMarkVisualEffect.kt` — GLSL 改周期脉冲扩散波 + 扭曲。
- `GhostSignalWaveEffect.kt` — 改小尺寸、定位到导弹位置（或废弃改用轻量粒子）。
- `ASTDLensArrayCoreHullMod.kt` — 幽灵信号触发改：导弹位置小特效 + 50% 熄火（`missile.flameOut()`）。

**修改 — 引擎修复 bug（独立）：**
- `ASTDVectorThrustEngineManager.kt`（高嫌疑）或定位到的真实根因文件。

---

## Task 1: ASTD 引擎熄火修复时间异常排查 + 修复

**Files:**
- 调研：`src/main/kotlin/cn/kasuminova/astd/renderer/effect/system/ASTDVectorThrustEngineManager.kt`、`grep` 全 src 的 engine stat 修改
- Modify: 定位到的根因文件

**问题：** 所有 ASTD 系列舰船引擎熄火（disabled）后修复时间极长，与原版舰船不一致。

- [ ] **Step 1: 复现 + 定位根因**

调研顺序（按嫌疑）：
1. `ASTDVectorThrustEngineManager.kt`：它每帧对所有 ASTD 船 `controller.setFlameLevel(slot, level)`。读它在引擎 `isDisabled`（熄火）时的分支——当前 `usable = !engine.isDisabled && !engine.isPermanentlyDisabled`，disabled 时 `target=0f`。**关键怀疑**：每帧强制 setFlameLevel 是否干扰原版的引擎修复计时（原版熄火后按 `engineRepairTimeMult` 计时恢复，每帧外部写 flameLevel 可能重置/冻结该计时）。验证方法：临时禁用 ASTDVectorThrustEngineManager（注释掉 ensureInstalled 调用或 setFlameLevel），实机观察 ASTD 船熄火修复是否恢复正常。
2. 若非 setFlameLevel：grep 全项目有无 hullmod 改 `engineRepairTimeMult` / 引擎相关 stat（`grep -rn "engineRepair\|EngineRepair\|engine.*RepairTime" src/`）。
3. 检查 ASTD 船的 ship_data.csv / .ship 有无异常引擎字段（对比原版高科技船）。

**实现者必须先确认根因再改，在报告写明根因证据（哪个改动导致、如何验证）。**

- [ ] **Step 2: 修复根因**

按定位结果修复。若是 ASTDVectorThrustEngineManager 的 setFlameLevel 干扰：
- 方案：引擎 disabled 时**跳过 setFlameLevel**（不写 flame level，让原版自己管熄火/修复表现），只在引擎正常（usable）时才接管火焰长度。即把 `if (!usable) target=0f` 改为 `if (!usable) { continue/skip setFlameLevel }`——不调 setFlameLevel，交还原版。验证修复后熄火修复时间正常 + 正常引擎的矢量推进表现不受影响。
- 若是别处 stat 误改，移除/修正该 stat 修改。

- [ ] **Step 3: 验证**

Run: `./gradlew compileKotlin`，实机观察（或现有 automation 场景若能体现）。报告修复前后引擎修复时间对比。

- [ ] **Step 4: Commit**

```bash
git add <定位到的根因文件>
git commit -m "fix(astd): engine repair time after flameout no longer stalled by vector thrust manager

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: ASTD 通用双模式框架（状态机 util + 配置）

**Files:**
- Create: `src/main/kotlin/cn/kasuminova/astd/combat/hullmods/base/ASTDDualModeConfig.kt`
- Test: `src/test/kotlin/cn/kasuminova/astd/combat/hullmods/base/ASTDDualModeConfigTest.kt`

把 arc 的双模式状态机（`ensureASTDArcFlareModeState`/`activateMode`/`migrateLegacyModeState`/`clearIncompatibleCaptain`）泛化为接受 ModeConfig 的通用函数。

- [ ] **Step 1: 读 arc 现有状态机**

读 `src/main/kotlin/cn/kasuminova/astd/combat/hullmods/arc/ASTDArcFlareHullModUtil.kt` 全文（已是工作实现）。识别其中硬编码的 arc ids（SWITCHER/MODE_CREWED/MODE_AUTOMATED/NEXT_CREWED/NEXT_AUTOMATED + "automated" 原版船插 + crewed/automated 系统 id "astd_arc_flare_overdrive_crewed/automated"）。

- [ ] **Step 2: 写 ModeConfig 数据类 + 通用状态机函数**

创建 `ASTDDualModeConfig.kt`：
```kotlin
package cn.kasuminova.astd.combat.hullmods.base

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipVariantAPI

/**
 * ASTD 通用双模式（载人/无人）配置：一艘双模式舰船的全部 hullmod / 系统 id 集合。
 * 动机：arc_flare 与 gravitational_lens 共用同一套「拆切换器即轮换模式」交互，
 * 不应每舰自造状态机。此配置 + [applyDualMode*] 通用函数让一套逻辑驱动任意双模式舰。
 */
data class ASTDDualModeConfig(
    /** 通用切换器 hullmod id（所有双模式舰共用同一个，见 [ASTDDualModeSwitcherHullMod]）。 */
    val switcherId: String,
    val crewedModeId: String,
    val automatedModeId: String,
    val nextCrewedMarker: String,
    val nextAutomatedMarker: String,
    /** 载人版舰船系统 id（mode hullmod 激活时 setShipSystemId）。 */
    val crewedSystemId: String,
    /** 无人版舰船系统 id。 */
    val automatedSystemId: String,
)

/** 通用：确保 variant 的双模式状态自洽（镜像 arc 的 ensureASTDArcFlareModeState，参数化 ids）。 */
fun ShipVariantAPI.ensureASTDDualModeState(config: ASTDDualModeConfig, stats: MutableShipStatsAPI? = null) {
    if (!isASTDShipVariant()) return
    migrateLegacyDualModeState(config)
    val hasCrewed = getPermaMods().contains(config.crewedModeId)
    val hasAutomated = getPermaMods().contains(config.automatedModeId)
    if (hasCrewed && hasAutomated) {
        removePermaMod(config.automatedModeId)
        removePermaMod("automated")
        setDualModeNextMarker(config, config.nextCrewedMarker)
        return
    }
    when {
        hasCrewed -> setDualModeNextMarker(config, config.nextCrewedMarker)
        hasAutomated -> setDualModeNextMarker(config, config.nextAutomatedMarker)
        getPermaMods().contains(config.nextAutomatedMarker) -> activateDualMode(config, config.automatedModeId, stats)
        getPermaMods().contains(config.nextCrewedMarker) -> activateDualMode(config, config.crewedModeId, stats)
        else -> activateDualMode(config, config.crewedModeId, stats)
    }
}

/** 通用：激活某模式（镜像 arc activateMode，参数化）。 */
fun ShipVariantAPI.activateDualMode(config: ASTDDualModeConfig, modeId: String, stats: MutableShipStatsAPI? = null) {
    removePermaMod(config.crewedModeId)
    removePermaMod(config.automatedModeId)
    addPermaMod(modeId)
    val nextMarker = if (modeId == config.automatedModeId) config.nextAutomatedMarker else config.nextCrewedMarker
    setDualModeNextMarker(config, nextMarker)
    if (modeId == config.automatedModeId) {
        if (!getPermaMods().contains("automated")) addPermaMod("automated")
    } else {
        removePermaMod("automated")
    }
    clearIncompatibleDualModeCaptain(stats)
}

fun ShipVariantAPI.hasASTDDualModeAutomated(config: ASTDDualModeConfig): Boolean =
    getPermaMods().contains(config.automatedModeId) || hasHullMod(config.automatedModeId)

private fun ShipVariantAPI.migrateLegacyDualModeState(config: ASTDDualModeConfig) {
    val stateIds = listOf(config.crewedModeId, config.automatedModeId, config.nextCrewedMarker, config.nextAutomatedMarker)
    for (id in stateIds) {
        if (hasHullMod(id) && !getPermaMods().contains(id)) {
            removeMod(id); addPermaMod(id)
        }
    }
}

private fun ShipVariantAPI.setDualModeNextMarker(config: ASTDDualModeConfig, markerId: String) {
    removePermaMod(config.nextCrewedMarker)
    removePermaMod(config.nextAutomatedMarker)
    addPermaMod(markerId)
}

private fun clearIncompatibleDualModeCaptain(stats: MutableShipStatsAPI?) {
    val member = try { stats?.fleetMember } catch (_: Throwable) { null } ?: return
    val captain = try { member.captain } catch (_: Throwable) { null }
    if (captain != null) {
        val aiCoreId = try { captain.aiCoreId } catch (_: Throwable) { null }
        if (aiCoreId != null) {
            try { Global.getSector()?.playerFleet?.cargo?.addCommodity(aiCoreId, 1f) } catch (_: Throwable) {}
        }
    }
    try { member.setCaptain(null) } catch (_: Throwable) {}
}
```
（`isASTDShipVariant()` 已在 arc util 定义为 internal——确认它可被 base 包访问；若不可，把 `isASTDShipVariant`/`isASTDShip` 也移到 base 包或改 public。实现者确认并处理 import/可见性。`clearIncompatibleDualModeCaptain` 的 try/catch 是战役上下文核心防崩例外，保留。）

- [ ] **Step 3: 写测试**（纯状态机逻辑，用 fake variant 或验证 ids 配置自洽）

由于 ShipVariantAPI 是引擎接口难以纯单测，可测部分是 ModeConfig 的构造自洽（ids 非空、crewed≠automated）。写一个最小测试验证两个预置 config（arc/lens）的 ids 无冲突、字段齐全：
```kotlin
package cn.kasuminova.astd.combat.hullmods.base

import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ASTDDualModeConfigTest {
    @Test fun `config ids are distinct and non-blank`() {
        val cfg = ASTDDualModeConfig(
            switcherId = "astd_dual_mode_switcher",
            crewedModeId = "astd_test_mode_crewed", automatedModeId = "astd_test_mode_automated",
            nextCrewedMarker = "astd_test_next_crewed", nextAutomatedMarker = "astd_test_next_automated",
            crewedSystemId = "sys_crewed", automatedSystemId = "sys_automated",
        )
        assertNotEquals(cfg.crewedModeId, cfg.automatedModeId)
        assertNotEquals(cfg.nextCrewedMarker, cfg.nextAutomatedMarker)
        assertTrue(cfg.switcherId.isNotBlank())
    }
}
```
> 若实现者认为此测试过于平凡（仅验证 data class 字段），可跳过并在报告说明——状态机真正逻辑由 Task 4 实机切换验证。**但 ModeConfig + 通用函数本身必须实现。**

- [ ] **Step 4: 编译 + 提交**

Run: `./gradlew compileKotlin` + （若写了测试）`./gradlew test --tests "...ASTDDualModeConfigTest"`
```bash
git add src/main/kotlin/cn/kasuminova/astd/combat/hullmods/base/ASTDDualModeConfig.kt \
        src/test/kotlin/cn/kasuminova/astd/combat/hullmods/base/ASTDDualModeConfigTest.kt
git commit -m "feat(hullmods): generic ASTD dual-mode state machine framework

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: 通用切换器 hullmod（动态 tooltip）

**Files:**
- Create: `src/main/kotlin/cn/kasuminova/astd/combat/hullmods/base/ASTDDualModeSwitcherHullMod.kt`
- Modify: `contents/data/strings/strings.json`（通用切换器 i18n + 动态模式名）

单一通用切换器类，`isASTDShip()` 通用，tooltip 根据当前 variant 的模式状态动态显示「当前模式 / 拆下后切换到的目标模式」。

- [ ] **Step 1: 读 arc 切换器 hullmod**

读 `src/main/kotlin/cn/kasuminova/astd/combat/hullmods/arc/ASTDArcFlareDualModeSwitcherHullMod.kt`（结构范式）+ `ASTDHullModTooltipRenderer`（tooltip 渲染器）。

- [ ] **Step 2: 实现通用切换器**

创建 `ASTDDualModeSwitcherHullMod.kt`：
- `class ASTDDualModeSwitcherHullMod : BaseHullMod()`，`isApplicableToShip = ship.isASTDShip()`，`showInRefitScreenModPickerFor = ship.isASTDShip()`。
- **不在 applyEffectsBeforeShipCreation 调具体舰的 ensureModeState**——通用切换器不知道具体舰的 ModeConfig。模式状态自洽由各舰自己的 mode hullmod（Task 4/5）的 applyEffectsBeforeShipCreation 调 `ensureASTDDualModeState(舰的config)` 保证。切换器只负责「存在/被拆」的信号 + tooltip。
- **动态 tooltip**：`addPostDescriptionSection` 读 `ship?.variant` 的 permaMods 判断当前模式（含哪个 mode id），显示「当前：载人/无人；在 refit 拆下本插件可切换到：无人/载人」。需要一个「从 variant 反查 ModeConfig」的机制——因为通用切换器要支持多舰，得有个注册表把「hull id → ModeConfig」映射起来（见下）。
- **ModeConfig 注册表**：在 `ASTDDualModeConfig.kt` 或新文件加一个 `ASTDDualModeRegistry`（object，`Map<hullIdOrPredicate, ASTDDualModeConfig>`，arc/lens 启动时注册各自 config）。切换器 tooltip 用 `ASTDDualModeRegistry.configFor(ship)` 拿到当前舰的 config 再读模式状态。**实现者设计这个注册表**（简单 object + register/configFor，arc/lens 的 ids util 类静态初始化时 register）。
- tooltip i18n key 用占位参数（`%currentMode%` / `%targetMode%`），文案动态填充（参照项目 I18n.t 带参数的用法）。

- [ ] **Step 3: strings.json 通用切换器文案**

加 `ui.hullmod.dual_mode_switcher.summary` / `.line.1`（说明拆下即切换）/ 动态模式名 `ui.dual_mode.crewed` = "载人" / `ui.dual_mode.automated` = "无人"。手写直改 strings.json。

- [ ] **Step 4: 编译 + 提交**

Run: `./gradlew compileKotlin`
```bash
git add src/main/kotlin/cn/kasuminova/astd/combat/hullmods/base/ASTDDualModeSwitcherHullMod.kt \
        src/main/kotlin/cn/kasuminova/astd/combat/hullmods/base/ASTDDualModeConfig.kt \
        contents/data/strings/strings.json
git commit -m "feat(hullmods): generic dual-mode switcher hullmod with dynamic tooltip

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: lens 接入通用切换器框架（补拆即切，修复切换失效）

**Files:**
- Modify: `src/main/kotlin/cn/kasuminova/astd/combat/hullmods/lens/LensArrayCoreModeUtil.kt`（lens ModeConfig + 注册）
- Modify: `src/main/kotlin/cn/kasuminova/astd/combat/hullmods/lens/ASTDLensCrewedModeHullMod.kt`、`ASTDLensAutomatedModeHullMod.kt`（补拆即切段）
- Modify: `ss-csv/.../Catalog_HullMods_LENS.kt`（切换器注册指向通用类）+ `.ship` builtInMods（切换器 id 改通用）+ strings.json

**这是修复「切换器无法使用」的核心任务。**

- [ ] **Step 1: 定义 lens ModeConfig + 注册**

在 `LensArrayCoreModeUtil.kt`（或 LensArrayCoreHullModIds 旁）定义：
```kotlin
val LENS_DUAL_MODE_CONFIG = ASTDDualModeConfig(
    switcherId = ASTDDualModeSwitcherIds.SWITCHER_ID, // 通用切换器 id（Task 3 定义）
    crewedModeId = LensArrayCoreHullModIds.MODE_CREWED,
    automatedModeId = LensArrayCoreHullModIds.MODE_AUTOMATED,
    nextCrewedMarker = LensArrayCoreHullModIds.NEXT_CREWED,
    nextAutomatedMarker = LensArrayCoreHullModIds.NEXT_AUTOMATED,
    crewedSystemId = LensArrayCoreHullModIds.SYSTEM_CREWED,
    automatedSystemId = LensArrayCoreHullModIds.SYSTEM_AUTOMATED,
)
```
启动时 `ASTDDualModeRegistry.register(isGravitationalLens predicate, LENS_DUAL_MODE_CONFIG)`（找一个全局初始化点，参照 arc 怎么注册的；若用 hull id 映射，key = LensArrayCoreHullModIds.HULL_ID）。`ensureLensArrayModeState` 改为转调 `ensureASTDDualModeState(LENS_DUAL_MODE_CONFIG, stats)`。

- [ ] **Step 2: 补 lens mode hullmod 的拆即切段**

`ASTDLensCrewedModeHullMod.applyEffectsBeforeShipCreation` 改为（镜像 arc crewed）：
```kotlin
override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
    val variant = stats.variant ?: return
    if (!variant.isGravitationalLensVariant()) return
    // 切换器被玩家移除 → 立即切到无人模式并恢复切换器（拆即切）
    if (!variant.hasHullMod(LENS_DUAL_MODE_CONFIG.switcherId)) {
        variant.activateDualMode(LENS_DUAL_MODE_CONFIG, LensArrayCoreHullModIds.MODE_AUTOMATED, stats)
        variant.addMod(LENS_DUAL_MODE_CONFIG.switcherId)
        return
    }
    variant.hullSpec?.setShipSystemId(LensArrayCoreHullModIds.SYSTEM_CREWED)
}
```
`ASTDLensAutomatedModeHullMod` 对称（拆即切到 MODE_CREWED + 加回切换器；切换器在则 setShipSystemId(SYSTEM_AUTOMATED) + 保留蜂群思维 stat）。**保留 automated 的蜂群思维 stat 修改不变。**

- [ ] **Step 3: ss-csv 切换器注册指向通用类 + .ship builtInMods**

- `Catalog_HullMods_LENS.kt`：lens 不再注册自己的 `astd_lens_mode_switcher`（废弃），改用通用 `astd_dual_mode_switcher`（Task 3 注册）。若通用切换器在 base catalog 注册，lens catalog 移除旧 switcher 条目。
- `.ship` builtInMods：把 `astd_lens_mode_switcher` 换成通用 `astd_dual_mode_switcher`。手写直改 `contents/data/hulls/astd_gravitational_lens.ship`。
- 旧 `ASTDLensDualModeSwitcherHullMod.kt` + 旧 switcher id 废弃（删除类 + 移除注册；确认无其它引用）。

- [ ] **Step 4: 写回 contents + 编译 + 实机切换验证**

```bash
./gradlew :ss-csv:writeSsCsvToContents -PssCsvForce=true
./gradlew compileKotlin
./gradlew deployMod
```
**实机验证切换**：进 refit，拆下切换器 → 确认舰船从载人切到无人（系统变 echo_fixation_automated、蜂群思维 stat 生效、切换器自动加回）；再拆一次 → 切回载人。**这是本任务验收点，实现者必须实机确认切换真的工作**（或用 automation 场景验证模式可切——若 phase2 automation 能扩展验证切换，更好）。报告实机切换结果。

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/cn/kasuminova/astd/combat/hullmods/lens/LensArrayCoreModeUtil.kt \
        src/main/kotlin/cn/kasuminova/astd/combat/hullmods/lens/ASTDLensCrewedModeHullMod.kt \
        src/main/kotlin/cn/kasuminova/astd/combat/hullmods/lens/ASTDLensAutomatedModeHullMod.kt \
        ss-csv/src/main/kotlin/cn/kasuminova/astd/sscsv/entries/catalog/hullmods/lens/Catalog_HullMods_LENS.kt \
        contents/data/hulls/astd_gravitational_lens.ship \
        contents/data/hullmods/hull_mods.csv \
        contents/data/strings/strings.json
# 若删了 ASTDLensDualModeSwitcherHullMod.kt：git rm 它
git commit -m "fix(lens): wire dual-mode switcher to generic framework; restore mode switching

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: arc 回归通用切换器框架（保持行为不变）

**Files:**
- Modify: `ASTDArcFlareHullModUtil.kt`（arc ModeConfig + 转调通用 util）、`ASTDArcFlare{Crewed,Automated}ModeHullMod.kt`（拆即切改调通用）、arc 切换器注册

把 arc 从自有状态机迁移到通用框架，**arc 行为必须完全不变**（arc automation 测试 + StandardVariantBuiltInHullmodDataTest 必须仍 PASS）。

- [ ] **Step 1: arc ModeConfig**

在 `ASTDArcFlareHullModUtil.kt` 定义 `ARC_FLARE_DUAL_MODE_CONFIG`（用 arc 现有 ids + 系统 id "astd_arc_flare_overdrive_crewed/automated"），注册到 ASTDDualModeRegistry（key = arc hull id）。`ensureASTDArcFlareModeState` 改为转调 `ensureASTDDualModeState(ARC_FLARE_DUAL_MODE_CONFIG, stats)`（保留旧函数名作薄包装，避免改所有调用点；或直接改调用点）。`activateMode`/`hasASTDArcFlareAutomatedMode` 同样转调通用。

- [ ] **Step 2: arc mode hullmod 拆即切改调通用**

`ASTDArcFlareCrewedModeHullMod` / `AutomatedModeHullMod` 的拆即切段：`variant.activateMode(...)` → `variant.activateDualMode(ARC_FLARE_DUAL_MODE_CONFIG, ...)`，`hasHullMod(SWITCHER)` 的 SWITCHER → 通用切换器 id。**arc 的 stat 修改 + advanceInCombat 视觉全部保留不变。**

- [ ] **Step 3: arc 切换器注册指向通用类**

arc 不再用自己的 `ASTDArcFlareDualModeSwitcherHullMod`（废弃或薄包装），改用通用 `astd_dual_mode_switcher`。`.ship`（arc_flare 的 .ship/.variant builtInMods）切换器 id 改通用。**注意 arc_flare 的 variant permaMod 约束**（StandardVariantBuiltInHullmodDataTest 对 arc_flare 有特殊豁免）——确认改切换器 id 后 arc_flare variant 仍通过该测试。

- [ ] **Step 4: 写回 + 编译 + arc 回归测试**

```bash
./gradlew :ss-csv:writeSsCsvToContents -PssCsvForce=true
./gradlew compileKotlin
./gradlew test
./gradlew deployMod
ASTD_AUTOMATION_SCENARIO=arc_production_ships_vfx_tooltip ./gradlew smokeTestGame
```
Expected: 全量测试绿（含 StandardVariantBuiltInHullmodDataTest）；arc automation PASS；实机 arc 切换仍工作。**arc 行为零回归是验收硬条件。**

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/cn/kasuminova/astd/combat/hullmods/arc/ASTDArcFlareHullModUtil.kt \
        src/main/kotlin/cn/kasuminova/astd/combat/hullmods/arc/ASTDArcFlareCrewedModeHullMod.kt \
        src/main/kotlin/cn/kasuminova/astd/combat/hullmods/arc/ASTDArcFlareAutomatedModeHullMod.kt \
        ss-csv/src/main/kotlin/cn/kasuminova/astd/sscsv/entries/catalog/hullmods/arc/ \
        contents/data/hulls/ contents/data/variants/ contents/data/hullmods/hull_mods.csv
git commit -m "refactor(arc): migrate arc_flare to generic dual-mode framework (behavior unchanged)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: 潮汐场降亮 ≥50%

**Files:**
- Modify: `src/main/kotlin/cn/kasuminova/astd/renderer/effect/lens/PermeatingTideFieldEffect.kt`
- Test: `src/test/kotlin/cn/kasuminova/astd/renderer/effect/lens/PermeatingTideFieldEffectTest.kt`（更新 alpha 断言）

常驻潮汐场太亮（图 2），降至少 50%。当前 `alphaMult = level * 0.55f`（PermeatingTideFieldEffect.kt:154）。

- [ ] **Step 1: 更新测试断言**

读现有测试里 alpha 相关断言。把涨满 alpha 上限断言从 0.55 改为 ≤0.275（降 50%）。若测试断言 `frame(tideLevel=1).alphaMult` 约等于某值，改为新值。

- [ ] **Step 2: 降亮实现**

`PermeatingTideFieldEffect.kt:154`：`val alphaMult = level * 0.55f` → `val alphaMult = level * 0.26f`（降 ~53%，明确 ≥50%）。同步更新该行注释（「涨满约 0.55」→「涨满约 0.26，降亮避免大战场抢眼」）。若 GLSL 内还有额外亮度乘子（如 fill glow 的固定系数），一并评估降低（看 `waterTexture`/fill 部分，整体观感降 ≥50%）。

- [ ] **Step 3: 测试 + 编译 + 提交**

```bash
./gradlew test --tests "cn.kasuminova.astd.renderer.effect.lens.PermeatingTideFieldEffectTest"
./gradlew compileKotlin
git add src/main/kotlin/cn/kasuminova/astd/renderer/effect/lens/PermeatingTideFieldEffect.kt \
        src/test/kotlin/cn/kasuminova/astd/renderer/effect/lens/PermeatingTideFieldEffectTest.kt
git commit -m "fix(lens): dim permeating tide field by ~50% to reduce battlefield clutter

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: 回声定影残影重做（绑定撕裂 debuff 常驻 + jitter/红随距离）

**Files:**
- Modify: `src/main/kotlin/cn/kasuminova/astd/combat/lens/system/EchoFixationField.kt`（EchoTearState 增存过去坐标；每帧驱动残影）
- Modify: `src/main/kotlin/cn/kasuminova/astd/renderer/effect/lens/EchoFixationAfterimageRenderer.kt`（改每帧重绘 + jitter + 红随距离）

**用户反馈**：当前残影只出现 ~1.25s 一次性淡出，太水。要：残影在认知撕裂期间**常驻**（直到 debuff 结束）；加 jitter；敌舰当前位置离「过去坐标」越近，jitter 越强、颜色越红。

- [ ] **Step 1: EchoTearState 增存过去坐标 + 残影常驻所需上下文**

读 `EchoFixationField.kt` 的 `EchoTearState`（当前 `data class EchoTearState(val takenMult: Float, val expiresAt: Float)`，:115）和 replay 施加撕裂处（:325-337）。改为：
```kotlin
private data class EchoTearState(
    val takenMult: Float,
    val expiresAt: Float,
    /** 「过去坐标」：残影每帧绘制位置 + 距离计算基准（越近 jitter 越强越红）。 */
    val pastX: Float,
    val pastY: Float,
    /** 站桩有效范围（缩放后），用于把「当前→过去」距离归一为 jitter/红强度。 */
    val standstillRange: Float,
    /** 残影绘制用：被撕裂敌舰的 hullSpriteName + 过去 facing（实体仍在场，引用有效但存值更稳）。 */
    val spriteName: String,
    val pastFacing: Float,
)
```
replay 施加撕裂时（:325-337 附近）把 past 坐标（该敌舰的 firstOrNull 快照 x/y）、standstillRange、spriteName、pastFacing 一并存入 EchoTearState。

- [ ] **Step 2: 每帧驱动常驻残影（替换一次性 replay 残影）**

当前 replay 瞬间调 `EchoFixationAfterimageRenderer.spawn`（一次性）。改为：
- replay 时**不再**调一次性 spawn。
- 在推进插件的每帧（`expireTears` 同一遍历，:472-485）：对每个仍存活的 EchoTearState，计算 `dist = 当前敌舰位置到 (pastX,pastY)` 的距离，调 `EchoFixationAfterimageRenderer.renderPersistent(engine, ship, state.pastX, state.pastY, state.pastFacing, state.spriteName, dist, state.standstillRange)` 每帧重绘残影（在过去坐标）。debuff 到期 unapply 时残影自然停止（不再重绘）。
- 保留 telemetry 计数（每帧重绘 +1，afterimageFrames 仍 >0 供 Task 12 验证）。

- [ ] **Step 3: 残影渲染器改每帧重绘 + jitter + 红随距离**

`EchoFixationAfterimageRenderer.kt` 加 `renderPersistent(engine, ship, pastX, pastY, pastFacing, spriteName, distToPast, standstillRange)`：
- 用 `MagicRender.battlespace` 每帧画一帧**短生命周期**残影（如 fadeIn 0 / full 0.05 / fadeOut 0.05，约 0.1s，靠每帧重绘形成常驻）——不是一次性长淡出。
- **jitter**：`closeness = 1 - (distToPast / standstillRange).coerceIn(0,1)`（越近 closeness→1）。残影位置每帧加随机抖动 `offset = jitterAmplitude * closeness`（closeness 越高抖动越大）。jitterAmplitude 基准如 8~15su。随机用 `MathUtils.getRandomPointInCircle` 或按帧索引变化（注意 Math.random 在战斗内可用，渲染层用无妨）。
- **红随距离**：颜色从紫罗兰 `Color(170,110,230)` 按 closeness 插值到红 `Color(230,60,60)`——closeness=0 紫、closeness=1 红。alpha 也可随 closeness 略升。
- 移除/调整旧的一次性 `spawn`（若 Task 12 telemetry 还引用 spawn，保留但 replay 不再调；或统一为 renderPersistent。实现者确认 spawn 调用点全改）。
- 上限：常驻残影按场内撕裂敌舰数（≤MAX_TARGETS）自然约束，每帧每敌一帧，无需额外上限（但保留防御性注释）。

- [ ] **Step 4: 编译 + 实机/automation 验证**

```bash
./gradlew compileKotlin
ASTD_AUTOMATION_SCENARIO=lens_phase2_mechanisms ./gradlew smokeTestGame
```
确认 echoFixationAfterimageFrames 仍 >0（现在是每帧重绘，应远大于之前）。实机观察残影常驻 + 越近越红越抖（人工复核）。

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/cn/kasuminova/astd/combat/lens/system/EchoFixationField.kt \
        src/main/kotlin/cn/kasuminova/astd/renderer/effect/lens/EchoFixationAfterimageRenderer.kt
git commit -m "feat(lens): persistent echo afterimage bound to cognitive-tear debuff (jitter+red by proximity)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: 标记高光改周期脉冲扩散波 + 扭曲

**Files:**
- Modify: `src/main/kotlin/cn/kasuminova/astd/renderer/effect/lens/MarkHighlightShaderSource.kt`（GLSL 改扩散波 + 扭曲）
- Modify: `DriftMarkVisualEffect.kt` / `DeepWaterMarkVisualEffect.kt`（若需加 progress uniform 驱动脉冲）
- Modify: `ASTDLensArrayCoreHullMod.kt`（驱动改：每带标记敌舰维护脉冲波节奏）
- Test: `MarkVisualEffectTest.kt`（更新断言）

**用户反馈**：标记高光环改成「轻度扩散波，带扭曲，颜色不变（误差紫/深水红）」，周期性脉冲。

- [ ] **Step 1: GLSL 改扩散波 + 扭曲**

`MarkHighlightShaderSource.kt` 的 fragment：当前是环形 SDF + Fresnel 常驻高光。改为：以敌舰为中心、按一个 `progress`（0→1 周期循环）向外扩散的轻度波环（参照 GhostSignalWaveEffect / ArcJet 的扩散环 `ring = exp(-((r-radius)/thickness)^2)`，radius = progress 外扩），叠轻微 FBM/sin 扭曲（domain warp：`p += distortionAmp * fbm(p)` 或 `sin` 偏移）。颜色仍用 `u_hue/u_saturation`（误差紫 / 深水红，不变）。波较轻（alpha 适中，不刷屏）。

- [ ] **Step 2: effect 加 progress uniform + 周期驱动**

两个 effect 的 schema 加 `progress`（若没有）。`frame()` 接受 progress。`ASTDLensArrayCoreHullMod.submitMarkHighlights` 改为：per-target 维护一个脉冲相位（customData 记每敌的波 elapsed，按周期 ~1~1.5s 循环 progress=elapsed/period % 1），每帧 upsert 时传当前 progress。层数越高周期略短/波略强（可选）。
> 周期循环 progress 让波「一道接一道」周期性扩散（用户选「周期性脉冲扩散波」）。

- [ ] **Step 3: 更新测试**

`MarkVisualEffectTest.kt`：schema 加 progress 断言；frame() 的 progress→半径/alpha 行为断言；保留 id/紫红 hue/无青/AboveShips/keyed upsert 断言。

- [ ] **Step 4: 测试 + 编译 + 提交**

```bash
./gradlew test --tests "cn.kasuminova.astd.renderer.effect.lens.MarkVisualEffectTest"
./gradlew compileKotlin
git add src/main/kotlin/cn/kasuminova/astd/renderer/effect/lens/MarkHighlightShaderSource.kt \
        src/main/kotlin/cn/kasuminova/astd/renderer/effect/lens/DriftMarkVisualEffect.kt \
        src/main/kotlin/cn/kasuminova/astd/renderer/effect/lens/DeepWaterMarkVisualEffect.kt \
        src/main/kotlin/cn/kasuminova/astd/combat/hullmods/lens/ASTDLensArrayCoreHullMod.kt \
        src/test/kotlin/cn/kasuminova/astd/renderer/effect/lens/MarkVisualEffectTest.kt
git commit -m "feat(lens): mark highlights as periodic spreading waves with distortion (colors unchanged)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 9: 幽灵信号改导弹位置小特效 + 50% 熄火

**Files:**
- Modify: `src/main/kotlin/cn/kasuminova/astd/combat/hullmods/lens/ASTDLensArrayCoreHullMod.kt`（defuse 改：导弹位置小特效 + 50% 熄火；移除本舰大波）
- Modify: `src/main/kotlin/cn/kasuminova/astd/renderer/effect/lens/GhostSignalWaveEffect.kt`（改小尺寸定位导弹 或 废弃改轻量特效）
- Modify: `GhostSignalWaveEffectTest.kt`（更新断言）

**用户反馈**：幽灵信号触发特效太重太大（图 2 大紫圈），密集导弹环境抢眼。改为：触发时在**导弹位置**放一次小干扰特效。**新增**：受影响导弹额外 50% 概率被熄火（`flameOut`）。

- [ ] **Step 1: defuse 改导弹位置小特效 + 50% 熄火**

读 `ASTDLensArrayCoreHullMod.kt` 的 `ghostSignal`/`defuse`/`spawnGhostWave`/`advanceGhostWaves`。改为：
- **移除本舰中心大波**（删 spawnGhostWave/advanceGhostWaves/GhostWave 的本舰大波逻辑，或改为导弹位置小特效）。
- `defuse(missile)`：剥离制导（保留现有 setMissileAI no-op）后——
  - 在**导弹位置**放一次小干扰特效（GhostSignalWaveEffect 改小 + 定位到 missile.location，one-shot 或短 keyed；尺寸如 80~150su 小环，而非 2000su）。
  - **新增 50% 熄火**：`if (Math.random() < 0.5f) missile.flameOut()`（先 javap 确认 `MissileAPI.flameOut()` 存在；存在则用，导弹引擎熄火坠落）。注释：剥离制导基础上额外 50% 熄火。
- 触发节奏：每枚被剥离的导弹各放一个小特效（在其位置），不再是每 tick 一道大波。密集导弹时多个小特效分散在各导弹位置，不抢眼。

- [ ] **Step 2: GhostSignalWaveEffect 改小 + 定位导弹**

改 `GhostSignalWaveEffect`：renderRadius 从 ~2120su 改小（如 200su），geometry quad 缩小，instanceId 改 per-missile（`ghost-${identityHashCode(missile)}`）。effectSpec 范围/参数相应缩小。保留紫色、扩散波形态但小尺寸。
> 若改动太大不如重写：可新建一个轻量 `GhostSignalPulseEffect`（小扩散波），废弃旧大波 GhostSignalWaveEffect。实现者判断：改小现有 vs 新建轻量。优先改小现有（复用范式）。

- [ ] **Step 3: 更新测试**

`GhostSignalWaveEffectTest.kt`：renderRadius 断言从 >=2000f 改为新的小值（如 <=300f）；保留 id/紫 hue/无青/progress uniform/keyed upsert 断言。

- [ ] **Step 4: javap 确认 flameOut + 测试 + 编译 + 实机**

```bash
javap -classpath /mnt/windows_data/Games/Starsector098-linux/starfarer.api.jar com.fs.starfarer.api.combat.MissileAPI | grep -i "flameOut\|flame"
./gradlew test --tests "cn.kasuminova.astd.renderer.effect.lens.GhostSignalWaveEffectTest"
./gradlew compileKotlin
ASTD_AUTOMATION_SCENARIO=lens_phase2_mechanisms ./gradlew smokeTestGame
```
确认 ghostSignalWaveFrames 仍 >0（现在 per-missile 小特效）；实机观察小特效 + 部分导弹熄火坠落。**若 flameOut 不存在**，用替代（如 `missile.setArmingTime` 或直接 `engine.removeEntity` 模拟熄火？——不,熄火应是引擎失效坠落，找最贴近 API；不可达则报告并用最接近替代）。

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/cn/kasuminova/astd/combat/hullmods/lens/ASTDLensArrayCoreHullMod.kt \
        src/main/kotlin/cn/kasuminova/astd/renderer/effect/lens/GhostSignalWaveEffect.kt \
        src/test/kotlin/cn/kasuminova/astd/renderer/effect/lens/GhostSignalWaveEffectTest.kt
git commit -m "feat(lens): ghost signal as per-missile small pulse + 50% flameout chance

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 10: 全量回归 + 实机三场景 + 切换实机确认

**Files:** 无新代码（验证收尾）

- [ ] **Step 1: 全量测试** `./gradlew test`（应全绿，含 arc 不回归）。
- [ ] **Step 2: 真相源对齐** `./gradlew :ss-csv:writeSsCsvToContents -PssCsvForce=true` 后 `git status --short` 无漂移。
- [ ] **Step 3: 部署 + 三场景回归**
```bash
./gradlew deployMod
ASTD_AUTOMATION_SCENARIO=lens_phase1_foundation ./gradlew smokeTestGame
ASTD_AUTOMATION_SCENARIO=lens_phase2_mechanisms ./gradlew smokeTestGame
ASTD_AUTOMATION_SCENARIO=arc_production_ships_vfx_tooltip ./gradlew smokeTestGame
```
全 PASS。
- [ ] **Step 4: 人工复核清单更新**：更新 `2026-06-21-gravitational-lens-phase2-manual-visual-checklist.md`，加本轮 9 项的复核项（切换器拆即切、潮汐降亮、残影常驻+jitter+红、标记扩散波、幽灵小特效+熄火、引擎修复正常）。
- [ ] **Step 5: Final commit**
```bash
git add docs/superpowers/plans/2026-06-21-gravitational-lens-phase2-manual-visual-checklist.md
git commit -m "docs(lens): phase-2 revision regression verified + checklist update"
```

---

## 自检

**覆盖：** 9 项用户反馈全有任务 —— 切换器通用化(T2/T3/T4/T5) + 引擎修复(T1) + 潮汐降亮(T6) + 残影重做(T7) + 标记扩散波(T8) + 幽灵小特效+熄火(T9) + 回归(T10)。
**类型一致：** ASTDDualModeConfig/ensureASTDDualModeState/activateDualMode/ASTDDualModeRegistry/renderPersistent 在各任务引用一致。
**风险点：** T5 arc 回归通用框架是最大风险（不得破坏 arc 行为 + StandardVariantBuiltInHullmodDataTest）——T5 Step4 硬验收。T1 引擎 bug 需先定位根因再改（不妄改）。T9 flameOut API 需 javap 确认。
