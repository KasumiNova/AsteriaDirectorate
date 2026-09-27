package cn.kasuminova.astd.combat.hullmods.lens

import cn.kasuminova.astd.combat.hullmods.base.ASTDHullModTooltipRenderer
import cn.kasuminova.astd.combat.hullmods.lens.GravEmFieldHullMod.Companion.isZw002Ship
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.ui.dsl.buildWith
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.input.InputEventAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.util.Misc
import java.awt.Color

/**
 * 引力电磁力场（Gravity Electromagnetic Field，hullmod id：astd_grav_em_field）——密蒙级（ZW-002）内置插件。
 *
 * 仅对密蒙级生效（[isApplicableToShip] / advanceInCombat 入口 [isZw002Ship] guard）。
 * advanceInCombat 每帧驱动：
 *
 * 1. **力场压制**：以本舰为心、[GravEmFieldTuning.RANGE]（难度三锚点）为半径内的所有敌对舰船
 *    （不含战机/残骸）受到最终乘区（modifyMult）压制：EMP 抗性绝对位移降低（`empDamageTakenMult`
 *    乘区反向换算，同 HeavyIonPulseEmpResistStacks 口径）、最大航速/机动性/三类武器射程削减、
 *    三类武器开火辐能增加。效果随距离衰减：[GravEmFieldTuning.effectScale]
 *    （≤半射程满效，边缘线性衰减到 [GravEmFieldTuning.EDGE_SCALE]）。
 *    受影响目标集合逐帧对账：新离场/失效目标立即 unmodify 全套修饰，不留 stat 残留。
 * 2. **力场视觉**（[GravEmFieldVfx]）：力场生效期间每隔 [GravEmFieldTuning.WAVE_INTERVAL]s
 *    从舰体碰撞箱边缘向随机外方向发射一波波形光斑（FlareEntity 组合：SMOOTH 圆斑 +
 *    SMOOTH_DISC 柔和光柱，外飘淡出，池化常驻实体）；舰船中心常驻一个极大 SMOOTH 圆斑
 *    （碰撞半径数倍）。相位与激活状态走线性过渡（[FieldState] 的 phaseBlend/fieldBlend）：
 *    相位切换时红/紫色系渐变；冷却时机制立即收口（压制修饰即时 unmodify）而视觉淡出——
 *    波形停喷（存量粒子按包络自然消散）、中心光斑 alpha/尺寸渐隐后移除，重新激活淡入。
 *    残骸化中心光斑立即移除（hulk 路径不过渡）；
 *    舰船撤离战场（非残骸化）由 [DepartedCleanupWatcher] 探活收口（含被压制目标的 stat 修饰）。
 *
 * 失效条件：舰船系统处于冷却（[ShipSystemAPI.SystemState.COOLDOWN]；IN/ACTIVE/OUT 激活过程保持），
 * 或舰船残骸化（残骸化瞬间对全部受影响目标执行 unmodify 收口）。
 *
 * 玩家状态行：BaseHullMod 无 getStatusData 接口，状态行走官方
 * [CombatEngineAPI.maintainStatusForPlayerShip]（每帧调用刷新，仅玩家船渲染），与
 * [GravSpaceFoldHullMod] 同款口径。
 *
 * 状态隔离：HullModEffect 实例按 hullmod 规格全局共享，逐舰状态挂 [ShipAPI.getCustomData]
 * （战斗内随实体生命周期）。注意原版实体级 customData 写入契约：字段惰性为 null 时
 * getCustomData() 返回一次性空表，首写必须走 [ShipAPI.setCustomData]
 * （2026-09-20 断言C排查实证，对照引力相位甲板同款口径）。
 */
class GravEmFieldHullMod : BaseHullMod() {

    /** 单舰力场状态：当前被压制的目标集合 + 波形光斑节拍计时器 + 中心光斑句柄 + 相位/激活过渡系数。 */
    private class FieldState {
        val affected = HashSet<ShipAPI>()
        var waveTimer = 0f
        var centerFlare: GravEmFieldVfx.CenterFlare? = null
        var departedWatcherInstalled = false

        /** 相位色过渡系数（0=力场紫，1=相位红），激活/冷却期都持续跟随相位状态。 */
        var phaseBlend = 0f

        /** 力场激活过渡系数（0=冷却不可见，1=完全激活），驱动中心光斑淡入淡出。 */
        var fieldBlend = 0f
    }

    /**
     * 离场清场哨兵：舰船撤离战场（retreat 等非残骸化移除）后 advanceInCombat 不再被调用，
     * 力场视觉（中心光斑/波形池）与被压制目标的 stat 修饰都失去收口路径——本插件每帧探活
     * （[CombatEngineAPI.isEntityInPlay]），确认离场即全量收口并自移除。
     * 残骸化不触发本路径（hulk 仍在 engine 视图中），hulk/冷却收口走 advanceInCombat 既有路径。
     */
    private inner class DepartedCleanupWatcher(
        private val engine: CombatEngineAPI,
        private val ship: ShipAPI,
    ) : BaseEveryFrameCombatPlugin() {
        override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
            if (engine.isEntityInPlay(ship)) return
            val state = ship.customData[STATE_KEY] as? FieldState
            if (state != null) {
                for (target in state.affected) unmodifyTarget(target, modIdOf(ship))
                state.affected.clear()
                state.centerFlare?.dispose()
                state.centerFlare = null
                ship.removeCustomData(STATE_KEY)
            }
            GravEmFieldVfx.disposeWavePools(engine)
            log.info("[ASTD] 引力电磁力场：舰船离场（ship=${ship.id}），中心光斑/波形池/压制修饰已收口")
            engine.removePlugin(this)
        }
    }

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused || amount <= 0f) return
        if (!ship.isZw002Ship()) return

        val state = ship.customData[STATE_KEY] as? FieldState

        // 残骸化：对仍在场目标逐一 unmodify、移除中心光斑后移除本舰状态（hulk 的引擎视图中目标引用仍有效）
        if (ship.isHulk) {
            if (state != null) {
                for (target in state.affected) unmodifyTarget(target, modIdOf(ship))
                state.centerFlare?.dispose()
                ship.removeCustomData(STATE_KEY)
            }
            return
        }

        val systemState = ship.system?.state
        val fieldActive = systemState != ShipSystemAPI.SystemState.COOLDOWN
        if (!fieldActive) {
            // 系统冷却：机制立即收口（压制修饰即时 unmodify），视觉走淡出过渡——
            // 波形停喷（低成本口径：存量粒子按各自包络自然消散，不额外排空粒子池），
            // 中心光斑随 fieldBlend 渐隐，归零后移除。状态体保留（离场哨兵安装标记随状态存续）
            if (state != null) {
                if (state.affected.isNotEmpty()) {
                    for (target in state.affected) unmodifyTarget(target, modIdOf(ship))
                    state.affected.clear()
                }
                advanceBlends(ship, state, false, amount)
                if (state.fieldBlend > 0f) {
                    state.centerFlare = GravEmFieldVfx.maintainCenterFlare(
                        engine, ship, state.centerFlare, state.phaseBlend, state.fieldBlend,
                    )
                } else if (state.centerFlare != null) {
                    state.centerFlare?.dispose()
                    state.centerFlare = null
                }
            }
            return
        }

        // 首写走 setCustomData（见类注释 customData 写入契约）
        val activeState = state ?: FieldState().also { ship.setCustomData(STATE_KEY, it) }
        if (!activeState.departedWatcherInstalled) {
            activeState.departedWatcherInstalled = true
            engine.addPlugin(DepartedCleanupWatcher(engine, ship))
        }
        val values = GravEmFieldTuning.resolve(DifficultyTuningImpl, ship.owner == 0)
        val modId = modIdOf(ship)

        advanceBlends(ship, activeState, true, amount)

        val seenThisFrame = HashSet<ShipAPI>()
        for (target in engine.ships) {
            if (target == null || target === ship) continue
            if (target.owner == ship.owner || target.isFighter || target.isHulk || !target.isAlive) continue
            val dist = Misc.getDistance(ship.location, target.location)
            val scale = GravEmFieldTuning.effectScale(dist, values.range)
            if (scale <= 0f) continue
            applyDebuff(target, modId, scale, values)
            seenThisFrame += target
        }

        // 对账：本帧未命中的既有目标立即 unmodify（离场/超程/敌我变化）
        activeState.affected.removeAll { target ->
            val stale = target !in seenThisFrame
            if (stale) unmodifyTarget(target, modId)
            stale
        }
        activeState.affected += seenThisFrame

        activeState.centerFlare = GravEmFieldVfx.maintainCenterFlare(
            engine, ship, activeState.centerFlare, activeState.phaseBlend, activeState.fieldBlend,
        )
        driveWaveVisuals(engine, ship, activeState, amount)

        if (ship === engine.playerShip) {
            maintainStatusBar(engine, values)
        }
    }

    /** 力场压制写入（全部最终乘区）：EMP 抗性位移 + 机动/射程削减 + 开火辐能增加。 */
    private fun applyDebuff(target: ShipAPI, modId: String, scale: Float, values: GravEmFieldTuning.Values) {
        val stats = target.mutableStats

        // EMP 抗性绝对位移：先 unmodify 读他源终值，再按削减量反推乘区系数写回；
        // 他源终值 ≤ 0（完全 EMP 免疫，0 乘区无法被乘算突破）时不写入
        val empStat = stats.empDamageTakenMult
        empStat.unmodifyMult(modId)
        val currentEmp = empStat.modifiedValue
        if (currentEmp > 0f) {
            val shifted = currentEmp + values.empResistReduction * scale
            empStat.modifyMult(modId, shifted / currentEmp)
        }

        val reductionMult = 1f - values.statPenalty * scale
        stats.maxSpeed.modifyMult(modId, reductionMult)
        stats.acceleration.modifyMult(modId, reductionMult)
        stats.deceleration.modifyMult(modId, reductionMult)
        stats.maxTurnRate.modifyMult(modId, reductionMult)
        stats.turnAcceleration.modifyMult(modId, reductionMult)
        stats.ballisticWeaponRangeBonus.modifyMult(modId, reductionMult)
        stats.energyWeaponRangeBonus.modifyMult(modId, reductionMult)
        stats.missileWeaponRangeBonus.modifyMult(modId, reductionMult)

        val fluxCostMult = 1f + values.statPenalty * scale
        stats.ballisticWeaponFluxCostMod.modifyMult(modId, fluxCostMult)
        stats.energyWeaponFluxCostMod.modifyMult(modId, fluxCostMult)
        stats.missileWeaponFluxCostMod.modifyMult(modId, fluxCostMult)
    }

    /** 力场收口：解除本力场在目标上的全部修饰（与 [applyDebuff] 写入面严格一一对应）。 */
    private fun unmodifyTarget(target: ShipAPI, modId: String) {
        val stats = target.mutableStats
        stats.empDamageTakenMult.unmodifyMult(modId)
        stats.maxSpeed.unmodifyMult(modId)
        stats.acceleration.unmodifyMult(modId)
        stats.deceleration.unmodifyMult(modId)
        stats.maxTurnRate.unmodifyMult(modId)
        stats.turnAcceleration.unmodifyMult(modId)
        stats.ballisticWeaponRangeBonus.unmodifyMult(modId)
        stats.energyWeaponRangeBonus.unmodifyMult(modId)
        stats.missileWeaponRangeBonus.unmodifyMult(modId)
        stats.ballisticWeaponFluxCostMod.unmodifyMult(modId)
        stats.energyWeaponFluxCostMod.unmodifyMult(modId)
        stats.missileWeaponFluxCostMod.unmodifyMult(modId)
    }

    /** 波形光斑节拍：每隔 [GravEmFieldTuning.WAVE_INTERVAL]s 一波（数量区间见 tuning），相位过渡系数决定红/紫渐变。 */
    private fun driveWaveVisuals(engine: CombatEngineAPI, ship: ShipAPI, state: FieldState, amount: Float) {
        state.waveTimer += amount
        while (state.waveTimer >= GravEmFieldTuning.WAVE_INTERVAL) {
            state.waveTimer -= GravEmFieldTuning.WAVE_INTERVAL
            GravEmFieldVfx.spawnWave(engine, ship, state.phaseBlend)
        }
    }

    /** 相位/激活过渡系数推进：朝目标（相位态/力场激活态）按各自过渡时长线性步进。 */
    private fun advanceBlends(ship: ShipAPI, state: FieldState, fieldActive: Boolean, amount: Float) {
        state.phaseBlend = stepBlend(state.phaseBlend, ship.isPhased, amount / GravEmFieldVfx.PHASE_BLEND_SECONDS)
        state.fieldBlend = stepBlend(state.fieldBlend, fieldActive, amount / GravEmFieldVfx.FIELD_BLEND_SECONDS)
    }

    private fun stepBlend(current: Float, on: Boolean, step: Float): Float {
        val target = if (on) 1f else 0f
        return if (current < target) (current + step).coerceAtMost(target)
        else (current - step).coerceAtLeast(target)
    }

    /** 玩家船左侧状态行（每帧调用刷新）：台词短句 + 当前难度档位的力场数值。 */
    private fun maintainStatusBar(engine: CombatEngineAPI, values: GravEmFieldTuning.Values) {
        engine.maintainStatusForPlayerShip(
            STATUS_KEY,
            spec?.spriteName ?: "",
            I18n[I18n.Categories.MOD, "ui.hullmod.grav_em_field.status.title"],
            I18n.t(
                I18n.Categories.MOD, "ui.hullmod.grav_em_field.status.data",
                "range" to values.range.toInt(),
                "empPct" to percent(values.empResistReduction),
                "statPct" to percent(values.statPenalty),
            ),
            false,
        )
    }

    override fun addPostDescriptionSection(
        tooltip: TooltipMakerAPI,
        hullSize: ShipAPI.HullSize,
        ship: ShipAPI?,
        width: Float,
        isForModSpec: Boolean,
    ) {
        // tooltip 展示口径：无舰上下文（装配面板）按玩家档 v2 展示
        val values = GravEmFieldTuning.resolve(DifficultyTuningImpl, ship == null || ship.owner == 0)
        tooltip.buildWith {
            spacer(6f)
            withLatticePulseBackground(accentColor = THEME.accentColor, width = width) {
                heading(spec?.displayName ?: "", THEME.nameColor, THEME.headerBackground, 6f)
                spacer(2f)
                para(I18n.Categories.MOD, "ui.hullmod.grav_em_field.summary", Misc.getTextColor(), 4f)
                para(
                    I18n.Categories.MOD, "ui.hullmod.grav_em_field.line.field", LINE_COLOR, 2f,
                    "range" to values.range.toInt(),
                    "empPct" to percent(values.empResistReduction),
                    "statPct" to percent(values.statPenalty),
                    "fluxPct" to percent(values.statPenalty),
                )
                para(
                    I18n.Categories.MOD, "ui.hullmod.grav_em_field.line.falloff", LINE_COLOR, 2f,
                    "fullRange" to (values.range * GravEmFieldTuning.FULL_EFFECT_FRACTION).toInt(),
                    "edgePct" to percent(GravEmFieldTuning.EDGE_SCALE),
                )
                para(I18n.Categories.MOD, "ui.hullmod.grav_em_field.line.cooldown", LINE_COLOR, 2f)
            }
        }
    }

    override fun isApplicableToShip(ship: ShipAPI): Boolean = ship.isZw002Ship()

    override fun showInRefitScreenModPickerFor(ship: ShipAPI): Boolean = false

    override fun getBorderColor(): Color = THEME.borderColor

    override fun getNameColor(): Color = THEME.nameColor

    companion object {
        /** Hullmod 注册 id（csv/注册接线由装配侧完成）。 */
        const val HULLMOD_ID = "astd_grav_em_field"

        private const val HULL_ID = "astd_zw_002"

        private val log = Global.getLogger(GravEmFieldHullMod::class.java)

        /** 逐舰力场状态挂载键（ShipAPI.customData，战斗内随实体生命周期）。 */
        private const val STATE_KEY = "astd_grav_em_field_state"

        /** 力场压制修饰句柄前缀：按源舰 id 区分（多力场源各自收口，互不误清）。 */
        private const val MOD_ID_PREFIX = "astd_grav_em_field:"

        /** 玩家船状态行键。 */
        private const val STATUS_KEY = "astd_grav_em_field_status"

        private val LINE_COLOR = Color(200, 200, 210)

        /** 紫主题（与 [ASTDGravPhaseDeckHullMod] 一致，透镜协议视觉统一）。 */
        private val THEME = ASTDHullModTooltipRenderer.Theme(
            nameColor = Color(200, 160, 255),
            borderColor = Color(160, 110, 255),
            headerBackground = Color(40, 18, 70, 185),
            sectionBackground = Color(28, 12, 52, 120),
            accentColor = Color(150, 90, 230),
        )

        private fun percent(value: Float): String = "${(value * 100f).toInt()}%"

        private fun modIdOf(ship: ShipAPI): String = MOD_ID_PREFIX + ship.id

        private fun ShipAPI?.isZw002Ship(): Boolean {
            val s = this ?: return false
            return s.hullSpec?.hullId == HULL_ID || s.hullSpec?.baseHullId == HULL_ID
        }
    }
}
