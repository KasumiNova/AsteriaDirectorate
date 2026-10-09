package cn.kasuminova.astd.combat.hullmods.lens

import cn.kasuminova.astd.combat.hullmods.affix.AffixShared
import cn.kasuminova.astd.combat.hullmods.base.DepartedShipCleanupWatcher
import cn.kasuminova.astd.combat.hullmods.base.aliveEnemies
import cn.kasuminova.astd.combat.hullmods.base.hasHullId
import cn.kasuminova.astd.combat.hullmods.base.reconcileTracked
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.ui.dsl.HullmodThemes
import cn.kasuminova.astd.ui.dsl.hullmodCard
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import java.awt.Color

/**
 * 先进电子对抗网络（Advanced ECM Network，hullmod id：astd_advanced_ecm_network）——决明级（ZW-001）内置插件。
 *
 * 仅对决明级生效（[isApplicableToShip] / advanceInCombat 入口 [hasHullId] guard）。
 * advanceInCombat 每帧驱动（接入原版 ElectronicWarfareScript 的每舰贡献 stat，见
 * [AdvancedEcmNetworkTuning]）：
 *
 * 1. **网络贡献**：自身强度 + Σ 存活友军按舰级分档贡献，写入本舰
 *    electronic_warfare_flat（同 id 覆盖，幂等；原版逐舰累加即得舰队总额）。
 *    友军枚举走 [AffixShared.aliveAllies]（存活、非战机/无人机/残骸）。
 * 2. **敌方反制**：每艘存活敌舰的 electronic_warfare_flat 被 clamp 到按舰级上限
 *    （4/8/12/16%）：先 unmodify 本网络修饰读他源终值，超出上限再写负向 flat 修正。
 *    被 clamp 目标集合逐帧对账：失效目标（离场/残骸化/不再超限）立即 unmodify，不留 stat 残留。
 *
 * 失效条件：舰船残骸化（hulk 分支对全部被 clamp 目标执行 unmodify 收口）；
 * 舰船撤离战场（非残骸化）由 [DepartedShipCleanupWatcher] 探活收口。
 *
 * 状态隔离：HullModEffect 实例按 hullmod 规格全局共享，逐舰状态挂 [ShipAPI.getCustomData]
 * （战斗内随实体生命周期）。customData 首写必须走 [ShipAPI.setCustomData]
 * （字段惰性为 null 时 getCustomData() 返回一次性空表，同 GravEmFieldHullMod 口径）。
 */
class AdvancedEcmNetworkHullMod : BaseHullMod() {

    /** 单舰网络状态：当前被 clamp 的敌舰集合 + 离场哨兵安装标记。 */
    private class NetworkState {
        val clamped = HashSet<ShipAPI>()
        var departedWatcherInstalled = false
    }

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused || amount <= 0f) return
        if (!ship.hasHullId(HULL_ID)) return

        val state = ship.customData[STATE_KEY] as? NetworkState

        // 残骸化：对全部被 clamp 目标 unmodify、解除本舰自身的 ECM 贡献写入后移除本舰状态。
        // 注：原版 CombatFleetManager.shipDestroyed 会把阵亡舰移出 deployed 集合，残骸的
        // electronic_warfare_flat 本就不再计入 EW 汇总；此处显式收口是「死亡即失效」的
        // 明确语义落点（同时覆盖 owner 变更等原版移除前提之外的边界）
        if (ship.isHulk) {
            ship.mutableStats.dynamic.getMod(AdvancedEcmNetworkTuning.ECM_FLAT_STAT).unmodifyFlat(HULLMOD_ID)
            if (state != null) {
                for (target in state.clamped) unclamp(target, modIdOf(ship))
                ship.removeCustomData(STATE_KEY)
            }
            return
        }

        // 首写走 setCustomData（见类注释 customData 写入契约）
        val activeState = state ?: NetworkState().also { ship.setCustomData(STATE_KEY, it) }
        if (!activeState.departedWatcherInstalled) {
            activeState.departedWatcherInstalled = true
            engine.addPlugin(DepartedShipCleanupWatcher(engine, ship, "先进电子对抗网络") {
                val s = ship.customData[STATE_KEY] as? NetworkState
                if (s != null) {
                    for (target in s.clamped) unclamp(target, modIdOf(ship))
                    ship.removeCustomData(STATE_KEY)
                }
            })
        }

        val values = AdvancedEcmNetworkTuning.resolve(DifficultyTuningImpl, ship.owner == 0)
        val modId = modIdOf(ship)

        // 网络贡献：自身 + 存活友军分档，写本舰 electronic_warfare_flat（同 id 覆盖，幂等）
        var frigates = 0
        var destroyers = 0
        var cruisers = 0
        var capitals = 0
        for (ally in AffixShared.aliveAllies(ship)) {
            when (ally.hullSize) {
                ShipAPI.HullSize.CAPITAL_SHIP -> capitals++
                ShipAPI.HullSize.CRUISER -> cruisers++
                ShipAPI.HullSize.DESTROYER -> destroyers++
                else -> frigates++
            }
        }
        ship.mutableStats.dynamic.getMod(AdvancedEcmNetworkTuning.ECM_FLAT_STAT).modifyFlat(
            HULLMOD_ID,
            AdvancedEcmNetworkTuning.totalOwnEcm(values, frigates, destroyers, cruisers, capitals),
        )

        // 敌方反制：每艘存活敌舰贡献 clamp 到舰级上限
        val seen = HashSet<ShipAPI>()
        for (target in aliveEnemies(engine, ship)) {
            val mod = target.mutableStats.dynamic.getMod(AdvancedEcmNetworkTuning.ECM_FLAT_STAT)
            mod.unmodifyFlat(modId)
            val delta = AdvancedEcmNetworkTuning.clampDelta(
                mod.computeEffective(0f),
                AdvancedEcmNetworkTuning.enemyCapFor(target.hullSize),
            )
            if (delta != 0f) {
                mod.modifyFlat(modId, delta)
                seen += target
            }
        }

        // 对账：本帧未命中的既有目标立即 unmodify（离场/残骸化/敌我变化/不再超限）
        reconcileTracked(activeState.clamped, seen) { unclamp(it, modId) }
    }

    /** 反制收口：解除本网络在目标上的 clamp 修正。 */
    private fun unclamp(target: ShipAPI, modId: String) {
        target.mutableStats.dynamic.getMod(AdvancedEcmNetworkTuning.ECM_FLAT_STAT).unmodifyFlat(modId)
    }

    override fun addPostDescriptionSection(
        tooltip: TooltipMakerAPI,
        hullSize: ShipAPI.HullSize,
        ship: ShipAPI?,
        width: Float,
        isForModSpec: Boolean,
    ) {
        // tooltip 展示口径：无舰上下文（装配面板）按我方档位展示（默认砺刃 v2）
        val values = AdvancedEcmNetworkTuning.resolve(DifficultyTuningImpl, ship == null || ship.owner == 0)
        tooltip.hullmodCard(width, THEME, spec?.displayName) {
            spacer(2f)
            para("ui.hullmod.advanced_ecm_network.desc", 4f)
            para("ui.hullmod.advanced_ecm_network.summary", 4f)
            para(
                "ui.hullmod.advanced_ecm_network.line.self", LINE_COLOR, 2f,
                v("selfPct", percent(values.selfEcm)),
            )
            para(
                "ui.hullmod.advanced_ecm_network.line.ally", LINE_COLOR, 2f,
                v("fPct", percent(values.allyEcmFrigate)),
                v("dPct", percent(values.allyEcmDestroyer)),
                v("cPct", percent(values.allyEcmCruiser)),
                v("capPct", percent(values.allyEcmCapital)),
            )
            para("ui.hullmod.advanced_ecm_network.line.cap", LINE_COLOR, 2f)
        }
    }

    override fun isApplicableToShip(ship: ShipAPI): Boolean = ship.hasHullId(HULL_ID)

    override fun showInRefitScreenModPickerFor(ship: ShipAPI): Boolean = false

    override fun getBorderColor(): Color = THEME.borderColor

    override fun getNameColor(): Color = THEME.nameColor

    companion object {
        /** Hullmod 注册 id（csv/注册接线由装配侧完成）。 */
        const val HULLMOD_ID = AdvancedEcmNetworkTuning.HULLMOD_ID

        private const val HULL_ID = "astd_zw_001"

        /** 逐舰网络状态挂载键（ShipAPI.customData，战斗内随实体生命周期）。 */
        private const val STATE_KEY = "astd_advanced_ecm_network_state"

        /** 敌方 clamp 修饰句柄前缀：按源舰 id 区分（多网络源各自收口，互不误清）。 */
        private const val MOD_ID_PREFIX = "astd_advanced_ecm_network:"

        private val LINE_COLOR = Color(200, 200, 210)

        /** 透镜线紫主题预设（与 [GravEmFieldHullMod] 一致，透镜协议视觉统一）。 */
        private val THEME = HullmodThemes.LENS

        /** 百分数单位（10 = 10%）格式化：整值去小数。 */
        private fun percent(value: Float): String =
            if (value == value.toInt().toFloat()) "${value.toInt()}%" else "$value%"

        private fun modIdOf(ship: ShipAPI): String = MOD_ID_PREFIX + ship.id
    }
}
