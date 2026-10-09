package cn.kasuminova.astd.combat.hullmods.lens

import cn.kasuminova.astd.combat.hullmods.base.DepartedShipCleanupWatcher
import cn.kasuminova.astd.combat.hullmods.base.aliveEnemies
import cn.kasuminova.astd.combat.hullmods.base.hasHullId
import cn.kasuminova.astd.combat.hullmods.base.reconcileTracked
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.ui.dsl.HullmodThemes
import cn.kasuminova.astd.ui.dsl.hullmodCard
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.characters.PersonAPI
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import java.awt.Color

/**
 * 引力电子干扰力场（Gravity Electronic Warfare Field，hullmod id：astd_grav_ew_field）——决明级（ZW-001）内置插件。
 *
 * 仅对决明级生效（[isApplicableToShip] / advanceInCombat 入口 [hasHullId] guard）。
 * advanceInCombat 每帧驱动（接入原版 ElectronicWarfareScript 的结算链路，见 [GravEwFieldTuning]）：
 *
 * 1. **最大效果提升**：对本侧舰队全部指挥官的 dynamic stat electronic_warfare_max 写 flat 增量
 *    （难度三锚点，v2 口径最大效果 10% → 20%）。注意 PersonAPI 的 dynamic stat 跨战斗存活，
 *    必须逐帧对账（指挥官离场立即 unmodify）并在 hulk/离场/战斗结束路径全量收口。
 * 2. **附加五项减益**：复算原版公式（[GravEwFieldTuning.penaltyDealt]，含双方 ECM 对抗折扣与
 *    原版取整口径）得出本侧实际造成的 EW 效果，按 实际/最大 比例对全部存活敌舰施加最终乘区
 *    （modifyMult）：empDamageTakenMult / shieldDamageTakenMult 提升（EMP 抗性与护盾效率降低）、
 *    maxSpeed/acceleration/deceleration/maxTurnRate/turnAcceleration 削减（航速与机动性降低）、
 *    systemCooldownBonus 提升（冷却时间增加）、systemRegenBonus 削减（充能时间增加）。
 *    被减益目标集合逐帧对账：失效目标（离场/残骸化/EW 效果归零）立即 unmodify，不留 stat 残留。
 *
 * 阵营语义：按 ship.owner 判定本侧——zw_001 在敌方时增益与减益对玩家方对称生效。
 *
 * 失效条件：舰船残骸化（hulk 分支全量收口）；舰船撤离战场（非残骸化）或战斗结束由
 * [DepartedShipCleanupWatcher] 探活收口（含指挥官修饰——防止泄漏到后续战斗/存档）。
 *
 * 状态隔离：HullModEffect 实例按 hullmod 规格全局共享，逐舰状态挂 [ShipAPI.getCustomData]
 * （战斗内随实体生命周期）。customData 首写必须走 [ShipAPI.setCustomData]
 * （字段惰性为 null 时 getCustomData() 返回一次性空表，同 GravEmFieldHullMod 口径）。
 */
class GravEwFieldHullMod : BaseHullMod() {

    /** 单舰力场状态：当前被减益的敌舰集合 + 被增幅的指挥官集合 + 离场哨兵安装标记。 */
    private class FieldState {
        val debuffed = HashSet<ShipAPI>()
        val boostedCommanders = HashSet<PersonAPI>()
        var departedWatcherInstalled = false
    }

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused || amount <= 0f) return
        if (!ship.hasHullId(HULL_ID)) return

        val state = ship.customData[STATE_KEY] as? FieldState

        // 残骸化：对全部被减益目标与被增幅指挥官 unmodify 后移除本舰状态
        if (ship.isHulk) {
            if (state != null) {
                cleanup(state, modIdOf(ship))
                ship.removeCustomData(STATE_KEY)
            }
            return
        }

        // 首写走 setCustomData（见类注释 customData 写入契约）
        val activeState = state ?: FieldState().also { ship.setCustomData(STATE_KEY, it) }
        if (!activeState.departedWatcherInstalled) {
            activeState.departedWatcherInstalled = true
            engine.addPlugin(DepartedShipCleanupWatcher(engine, ship, "引力电子干扰力场") {
                val s = ship.customData[STATE_KEY] as? FieldState
                if (s != null) {
                    cleanup(s, modIdOf(ship))
                    ship.removeCustomData(STATE_KEY)
                }
            })
        }

        val values = GravEwFieldTuning.resolve(DifficultyTuningImpl, ship.owner == 0)
        val modId = modIdOf(ship)

        // 最大效果提升：本侧全部指挥官 electronic_warfare_max flat 增量（同 id 覆盖，幂等）
        val commanders = engine.getFleetManager(ship.owner)?.allFleetCommanders.orEmpty()
        for (commander in commanders) {
            commander.stats.dynamic.getMod(GravEwFieldTuning.EW_MAX_STAT)
                .modifyFlat(modId, values.maxEffectBonus)
        }
        // 对账：不再担任本侧指挥官的既有目标立即 unmodify（指挥官 stat 跨战斗存活，必须收口）
        reconcileTracked(activeState.boostedCommanders, commanders) {
            it.stats.dynamic.getMod(GravEwFieldTuning.EW_MAX_STAT).unmodifyFlat(modId)
        }

        // 附加五项减益：复算原版公式得本侧实际 EW 效果比例（上限按原版 int 截断口径）。
        // 前提：原版 ElectronicWarfareScript 只处理 0/1 两侧对抗（player/enemy），本方 owner 非 0 时对方即 0
        val enemyOwner = if (ship.owner == 0) 1 else 0
        val ownTotal = sideEcmTotal(engine, ship.owner)
        val enemyTotal = sideEcmTotal(engine, enemyOwner)
        val ownMax = sideEcmMax(engine, ship.owner)
        val scale = GravEwFieldTuning.debuffScale(
            GravEwFieldTuning.penaltyDealt(ownTotal, ownMax, enemyTotal),
            ownMax.toInt().toFloat(),
        )

        val seen = HashSet<ShipAPI>()
        if (scale > 0f) {
            for (target in aliveEnemies(engine, ship)) {
                applyDebuff(target, modId, scale)
                seen += target
            }
        }

        // 对账：本帧未命中的既有目标立即 unmodify（离场/残骸化/敌我变化/EW 效果归零）
        reconcileTracked(activeState.debuffed, seen) { unmodifyTarget(it, modId) }
    }

    /** 阵营 ECM 总额复算（与 ElectronicWarfareScript.getTotalAndMaximum 同口径）：非战机/非站模块部署舰累加 + 传感阵列目标。 */
    private fun sideEcmTotal(engine: CombatEngineAPI, owner: Int): Float {
        val manager = engine.getFleetManager(owner) ?: return 0f
        var total = 0f
        for (member in manager.deployedCopyDFM) {
            if (member.isFighterWing || member.isStationModule) continue
            val memberShip = member.ship ?: continue
            total += memberShip.mutableStats.dynamic.getValue(GravEwFieldTuning.ECM_FLAT_STAT, 0f)
        }
        for (objective in engine.objectives) {
            if (objective.owner == owner && objective.type == GravEwFieldTuning.SENSOR_ARRAY_TYPE) {
                total += GravEwFieldTuning.SENSOR_ARRAY_ECM
            }
        }
        return total
    }

    /** 阵营最大效果复算（与 ElectronicWarfareScript 同口径）：10 + 各指挥官 electronic_warfare_max 的最大值；无指挥官为 0。 */
    private fun sideEcmMax(engine: CombatEngineAPI, owner: Int): Float {
        val manager = engine.getFleetManager(owner) ?: return 0f
        var max = 0f
        for (commander in manager.allFleetCommanders) {
            max = maxOf(
                max,
                GravEwFieldTuning.VANILLA_MAX_EFFECT +
                    commander.stats.dynamic.getValue(GravEwFieldTuning.EW_MAX_STAT, 0f),
            )
        }
        return max
    }

    /** 五项减益写入（全部最终乘区，幅度 = DEBUFF_FULL × scale）。 */
    private fun applyDebuff(target: ShipAPI, modId: String, scale: Float) {
        val magnitude = GravEwFieldTuning.DEBUFF_FULL * scale
        val stats = target.mutableStats
        stats.empDamageTakenMult.modifyMult(modId, 1f + magnitude)
        stats.shieldDamageTakenMult.modifyMult(modId, 1f + magnitude)
        val mobilityMult = 1f - magnitude
        stats.maxSpeed.modifyMult(modId, mobilityMult)
        stats.acceleration.modifyMult(modId, mobilityMult)
        stats.deceleration.modifyMult(modId, mobilityMult)
        stats.maxTurnRate.modifyMult(modId, mobilityMult)
        stats.turnAcceleration.modifyMult(modId, mobilityMult)
        stats.systemCooldownBonus.modifyMult(modId, 1f + magnitude)
        stats.systemRegenBonus.modifyMult(modId, 1f - magnitude)
    }

    /** 减益收口：解除本力场在目标上的全部修饰（与 [applyDebuff] 写入面严格一一对应）。 */
    private fun unmodifyTarget(target: ShipAPI, modId: String) {
        val stats = target.mutableStats
        stats.empDamageTakenMult.unmodifyMult(modId)
        stats.shieldDamageTakenMult.unmodifyMult(modId)
        stats.maxSpeed.unmodifyMult(modId)
        stats.acceleration.unmodifyMult(modId)
        stats.deceleration.unmodifyMult(modId)
        stats.maxTurnRate.unmodifyMult(modId)
        stats.turnAcceleration.unmodifyMult(modId)
        stats.systemCooldownBonus.unmodifyMult(modId)
        stats.systemRegenBonus.unmodifyMult(modId)
    }

    /** 全量收口：敌舰减益 + 指挥官增幅（hulk/离场/战斗结束路径共用）。 */
    private fun cleanup(state: FieldState, modId: String) {
        for (target in state.debuffed) unmodifyTarget(target, modId)
        state.debuffed.clear()
        for (commander in state.boostedCommanders) {
            commander.stats.dynamic.getMod(GravEwFieldTuning.EW_MAX_STAT).unmodifyFlat(modId)
        }
        state.boostedCommanders.clear()
    }

    override fun addPostDescriptionSection(
        tooltip: TooltipMakerAPI,
        hullSize: ShipAPI.HullSize,
        ship: ShipAPI?,
        width: Float,
        isForModSpec: Boolean,
    ) {
        // tooltip 展示口径：无舰上下文（装配面板）按我方档位展示（默认砺刃 v2）
        val values = GravEwFieldTuning.resolve(DifficultyTuningImpl, ship == null || ship.owner == 0)
        tooltip.hullmodCard(width, THEME, spec?.displayName) {
            spacer(2f)
            para("ui.hullmod.grav_ew_field.desc", 4f)
            para("ui.hullmod.grav_ew_field.summary", 4f)
            para(
                "ui.hullmod.grav_ew_field.line.max", LINE_COLOR, 2f,
                v("boostPct", percent(values.maxEffectBonus)),
                v("maxPct", percent(GravEwFieldTuning.VANILLA_MAX_EFFECT + values.maxEffectBonus)),
            )
            para(
                "ui.hullmod.grav_ew_field.line.debuff", LINE_COLOR, 2f,
                v("debuffPct", percent(GravEwFieldTuning.DEBUFF_FULL * 100f)),
            )
            para("ui.hullmod.grav_ew_field.line.scale", LINE_COLOR, 2f)
        }
    }

    override fun isApplicableToShip(ship: ShipAPI): Boolean = ship.hasHullId(HULL_ID)

    override fun showInRefitScreenModPickerFor(ship: ShipAPI): Boolean = false

    override fun getBorderColor(): Color = THEME.borderColor

    override fun getNameColor(): Color = THEME.nameColor

    companion object {
        /** Hullmod 注册 id（csv/注册接线由装配侧完成）。 */
        const val HULLMOD_ID = GravEwFieldTuning.HULLMOD_ID

        private const val HULL_ID = "astd_zw_001"

        /** 逐舰力场状态挂载键（ShipAPI.customData，战斗内随实体生命周期）。 */
        private const val STATE_KEY = "astd_grav_ew_field_state"

        /** 修饰句柄前缀：按源舰 id 区分（多力场源各自收口，互不误清）。 */
        private const val MOD_ID_PREFIX = GravEwFieldTuning.MOD_ID_PREFIX

        private val LINE_COLOR = Color(200, 200, 210)

        /** 透镜线紫主题预设（与 [GravEmFieldHullMod] 一致，透镜协议视觉统一）。 */
        private val THEME = HullmodThemes.LENS

        /** 百分数单位（10 = 10%）格式化：整值去小数。 */
        private fun percent(value: Float): String =
            if (value == value.toInt().toFloat()) "${value.toInt()}%" else "$value%"

        private fun modIdOf(ship: ShipAPI): String = MOD_ID_PREFIX + ship.id
    }
}

/**
 * 战斗级安全网：清理双方舰队指挥官 electronic_warfare_max 上前一场战斗残留的力场增幅修饰。
 *
 * 指挥官 PersonAPI 的 dynamic stat 跨战斗/跨存档存活：正常路径（逐帧对账 + hulk 分支 +
 * DepartedShipCleanupWatcher）已覆盖战斗内全部收口，但直接结束战斗/读档等路径可能跳过收口，
 * 残留项会永久生效且每场叠加。本函数在每场战斗初始化时调用一次
 * （ASTDGlobalCombatPlugin.init），按 [GravEwFieldTuning.MOD_ID_PREFIX] 识别并清除残留。
 */
fun purgeGravEwFieldCommanderBoosts(engine: CombatEngineAPI) {
    var purged = 0
    for (owner in 0..1) {
        val manager = engine.getFleetManager(owner) ?: continue
        for (commander in manager.allFleetCommanders) {
            val mod = commander.stats.dynamic.getMod(GravEwFieldTuning.EW_MAX_STAT)
            for (source in GravEwFieldTuning.fieldModSources(mod.flatBonuses.keys.toList())) {
                mod.unmodifyFlat(source)
                purged++
            }
        }
    }
    if (purged > 0) {
        Global.getLogger(GravEwFieldHullMod::class.java)
            .info("[ASTD] 引力电子干扰力场：清理跨战斗残留的指挥官增幅修饰 $purged 项")
    }
}
