package cn.kasuminova.astd.combat.hullmods.lens

import cn.kasuminova.astd.combat.hullmods.HullmodIncompatibility
import cn.kasuminova.astd.combat.hullmods.affix.AffixShared
import cn.kasuminova.astd.combat.hullmods.base.DepartedShipCleanupWatcher
import cn.kasuminova.astd.combat.hullmods.base.IncompatibleHullmodStripper
import cn.kasuminova.astd.combat.hullmods.base.aliveEnemies
import cn.kasuminova.astd.combat.hullmods.base.hasHullId
import cn.kasuminova.astd.combat.hullmods.base.reconcileTracked
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.ui.dsl.HullmodThemes
import cn.kasuminova.astd.ui.dsl.HullmodTone
import cn.kasuminova.astd.ui.dsl.hl
import cn.kasuminova.astd.ui.dsl.hullmodCard
import cn.kasuminova.astd.ui.dsl.v
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.characters.PersonAPI
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipVariantAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import java.awt.Color

class AdvancedEcmNetworkHullMod : BaseHullMod() {

    private class NetworkState {
        val clamped = HashSet<ShipAPI>()
        val debuffed = HashSet<ShipAPI>()
        val boostedCommanders = HashSet<PersonAPI>()
        val captureBoosted = HashSet<ShipAPI>()
        var departedWatcherInstalled = false
    }

    override fun applyEffectsBeforeShipCreation(
        hullSize: ShipAPI.HullSize,
        stats: MutableShipStatsAPI,
        id: String,
    ) {
        val variant = stats.variant ?: return
        if (!variant.isZw001Variant()) return
        // 只检测入队：本回调可能在原版 OP 结算的 hullMods 实时迭代内触发，结构性移除会抛 CME
        IncompatibleHullmodStripper.requestStrip(variant, FORBIDDEN_HULLMOD_IDS, HULLMOD_ID)
    }

    override fun applyEffectsAfterShipCreation(ship: ShipAPI, id: String) {
        // ShipFactory 迭代 getAllMods() 快照，此处入队并立即清理是安全的
        IncompatibleHullmodStripper.requestStrip(ship.variant, FORBIDDEN_HULLMOD_IDS, HULLMOD_ID)
        IncompatibleHullmodStripper.drainPending()
    }

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        IncompatibleHullmodStripper.drainPending()
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused || amount <= 0f) return
        if (!ship.hasHullId(HULL_ID)) return

        val state = ship.customData[STATE_KEY] as? NetworkState

        if (ship.isHulk) {
            ship.mutableStats.dynamic.getMod(AdvancedEcmNetworkTuning.ECM_FLAT_STAT).unmodifyFlat(HULLMOD_ID)
            if (state != null) {
                cleanup(state, modIdOf(ship))
                ship.removeCustomData(STATE_KEY)
            }
            return
        }

        // customData 首次写入必须调用 setCustomData；getCustomData 返回的空表不持久。
        val activeState = state ?: NetworkState().also { ship.setCustomData(STATE_KEY, it) }
        if (!activeState.departedWatcherInstalled) {
            activeState.departedWatcherInstalled = true
            engine.addPlugin(DepartedShipCleanupWatcher(engine, ship, "先进电子对抗网络") {
                ship.mutableStats.dynamic.getMod(AdvancedEcmNetworkTuning.ECM_FLAT_STAT).unmodifyFlat(HULLMOD_ID)
                val s = ship.customData[STATE_KEY] as? NetworkState
                if (s != null) {
                    cleanup(s, modIdOf(ship))
                    ship.removeCustomData(STATE_KEY)
                }
            })
        }

        val values = AdvancedEcmNetworkTuning.resolve(DifficultyTuningImpl, ship.owner == 0)
        val modId = modIdOf(ship)

        applyNetworkContribution(ship, values)
        applyEnemyClamp(engine, ship, activeState, modId)
        applyCommanderBoost(engine, ship, values, activeState, modId)
        applyCaptureRateBoost(engine, ship, values, activeState, modId)
        applyEwDebuff(engine, ship, activeState, modId)
    }

    private fun applyNetworkContribution(ship: ShipAPI, values: AdvancedEcmNetworkTuning.Values) {
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
    }

    private fun applyEnemyClamp(
        engine: CombatEngineAPI,
        ship: ShipAPI,
        state: NetworkState,
        modId: String,
    ) {
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
        reconcileTracked(state.clamped, seen) { unclamp(it, modId) }
    }

    private fun unclamp(target: ShipAPI, modId: String) {
        target.mutableStats.dynamic.getMod(AdvancedEcmNetworkTuning.ECM_FLAT_STAT).unmodifyFlat(modId)
    }

    private fun applyCommanderBoost(
        engine: CombatEngineAPI,
        ship: ShipAPI,
        values: AdvancedEcmNetworkTuning.Values,
        state: NetworkState,
        modId: String,
    ) {
        val capturedPoints = engine.objectives.count { it.owner == ship.owner }
        val boost = AdvancedEcmNetworkTuning.commanderBoost(values, capturedPoints)
        val commanders = engine.getFleetManager(ship.owner)?.allFleetCommanders.orEmpty()
        for (commander in commanders) {
            commander.stats.dynamic.getMod(AdvancedEcmNetworkTuning.EW_MAX_STAT)
                .modifyFlat(modId, boost)
        }
        reconcileTracked(state.boostedCommanders, commanders) {
            it.stats.dynamic.getMod(AdvancedEcmNetworkTuning.EW_MAX_STAT).unmodifyFlat(modId)
        }
    }

    private fun applyCaptureRateBoost(
        engine: CombatEngineAPI,
        ship: ShipAPI,
        values: AdvancedEcmNetworkTuning.Values,
        state: NetworkState,
        modId: String,
    ) {
        val allies = engine.ships.filter {
            it.owner == ship.owner && it.isAlive && !it.isHulk && !it.isFighter && !it.isDrone
        }
        for (ally in allies) {
            ally.mutableStats.dynamic.getMod(AdvancedEcmNetworkTuning.CAP_RATE_STAT)
                .modifyMult(modId, values.captureRateMult)
        }
        reconcileTracked(state.captureBoosted, allies) {
            it.mutableStats.dynamic.getMod(AdvancedEcmNetworkTuning.CAP_RATE_STAT).unmodifyMult(modId)
        }
    }

    private fun applyEwDebuff(
        engine: CombatEngineAPI,
        ship: ShipAPI,
        state: NetworkState,
        modId: String,
    ) {
        val enemyOwner = if (ship.owner == 0) 1 else 0
        val penalty = AdvancedEcmNetworkTuning.penaltyDealt(
            ownTotal = sideEcmTotal(engine, ship.owner),
            ownMax = sideEcmMax(engine, ship.owner),
            enemyTotal = sideEcmTotal(engine, enemyOwner),
        )
        val magnitude = AdvancedEcmNetworkTuning.debuffMagnitude(penalty)

        val seen = HashSet<ShipAPI>()
        if (magnitude > 0f) {
            for (target in aliveEnemies(engine, ship)) {
                val stats = target.mutableStats
                stats.empDamageTakenMult.modifyMult(modId, 1f + magnitude)
                stats.shieldDamageTakenMult.modifyMult(modId, 1f + magnitude)
                stats.systemCooldownBonus.modifyMult(modId, 1f + magnitude)
                stats.systemRegenBonus.modifyMult(modId, 1f - magnitude)
                seen += target
                if (target === engine.playerShip) {
                    maintainDebuffStatus(engine, magnitude)
                }
            }
        }
        reconcileTracked(state.debuffed, seen) { unmodifyDebuff(it, modId) }
    }

    private fun unmodifyDebuff(target: ShipAPI, modId: String) {
        val stats = target.mutableStats
        stats.empDamageTakenMult.unmodifyMult(modId)
        stats.shieldDamageTakenMult.unmodifyMult(modId)
        stats.systemCooldownBonus.unmodifyMult(modId)
        stats.systemRegenBonus.unmodifyMult(modId)
    }

    private fun sideEcmTotal(engine: CombatEngineAPI, owner: Int): Float {
        val manager = engine.getFleetManager(owner) ?: return 0f
        var total = 0f
        for (member in manager.deployedCopyDFM) {
            if (member.isFighterWing || member.isStationModule) continue
            val memberShip = member.ship ?: continue
            total += memberShip.mutableStats.dynamic.getValue(AdvancedEcmNetworkTuning.ECM_FLAT_STAT, 0f)
        }
        for (objective in engine.objectives) {
            if (objective.owner == owner && objective.type == AdvancedEcmNetworkTuning.SENSOR_ARRAY_TYPE) {
                total += AdvancedEcmNetworkTuning.SENSOR_ARRAY_ECM
            }
        }
        return total
    }

    private fun sideEcmMax(engine: CombatEngineAPI, owner: Int): Float {
        val manager = engine.getFleetManager(owner) ?: return 0f
        var max = 0f
        for (commander in manager.allFleetCommanders) {
            max = maxOf(
                max,
                AdvancedEcmNetworkTuning.VANILLA_MAX_EFFECT +
                    commander.stats.dynamic.getValue(AdvancedEcmNetworkTuning.EW_MAX_STAT, 0f),
            )
        }
        return max
    }

    private fun cleanup(state: NetworkState, modId: String) {
        for (target in state.clamped) unclamp(target, modId)
        state.clamped.clear()
        for (target in state.debuffed) unmodifyDebuff(target, modId)
        state.debuffed.clear()
        for (commander in state.boostedCommanders) {
            commander.stats.dynamic.getMod(AdvancedEcmNetworkTuning.EW_MAX_STAT).unmodifyFlat(modId)
        }
        state.boostedCommanders.clear()
        for (ally in state.captureBoosted) {
            ally.mutableStats.dynamic.getMod(AdvancedEcmNetworkTuning.CAP_RATE_STAT).unmodifyMult(modId)
        }
        state.captureBoosted.clear()
    }

    private fun maintainDebuffStatus(engine: CombatEngineAPI, magnitude: Float) {
        engine.maintainStatusForPlayerShip(
            STATUS_KEY,
            spec?.spriteName ?: "",
            I18n[I18n.Categories.MOD, "ui.hullmod.advanced_ecm_network.status.title"],
            I18n.t(
                I18n.Categories.MOD, "ui.hullmod.advanced_ecm_network.status.data",
                "pct" to percent(magnitude * 100f),
            ),
            true,
        )
    }

    override fun addPostDescriptionSection(
        tooltip: TooltipMakerAPI,
        hullSize: ShipAPI.HullSize,
        ship: ShipAPI?,
        width: Float,
        isForModSpec: Boolean,
    ) {
        val values = AdvancedEcmNetworkTuning.resolve(DifficultyTuningImpl, ship == null || ship.owner == 0)
        tooltip.hullmodCard(width, THEME, spec?.displayName) {
            para("ui.hullmod.advanced_ecm_network.desc", hl("奇点核心", HullmodThemes.LENS.nameColor))
            heading("ui.hullmod.advanced_ecm_network.section.core")
            para(
                "ui.hullmod.advanced_ecm_network.line.self",
                v("selfPct", percent(values.selfEcm)),
                hl(percent(values.selfEcm), HullmodTone.HIGHLIGHT),
            )
            para("ui.hullmod.advanced_ecm_network.line.ally")
            table {
                row(
                    "ui.hullmod.advanced_ecm_network.table.ally.row_0.label",
                    "ui.hullmod.advanced_ecm_network.table.ally.row_0.value",
                    HullmodTone.DEFAULT, HullmodTone.HIGHLIGHT,
                    v("pct", percent(values.allyEcmFrigate)),
                )
                row(
                    "ui.hullmod.advanced_ecm_network.table.ally.row_1.label",
                    "ui.hullmod.advanced_ecm_network.table.ally.row_1.value",
                    HullmodTone.DEFAULT, HullmodTone.HIGHLIGHT,
                    v("pct", percent(values.allyEcmDestroyer)),
                )
                row(
                    "ui.hullmod.advanced_ecm_network.table.ally.row_2.label",
                    "ui.hullmod.advanced_ecm_network.table.ally.row_2.value",
                    HullmodTone.DEFAULT, HullmodTone.HIGHLIGHT,
                    v("pct", percent(values.allyEcmCruiser)),
                )
                row(
                    "ui.hullmod.advanced_ecm_network.table.ally.row_3.label",
                    "ui.hullmod.advanced_ecm_network.table.ally.row_3.value",
                    HullmodTone.DEFAULT, HullmodTone.HIGHLIGHT,
                    v("pct", percent(values.allyEcmCapital)),
                )
            }
            para(
                "ui.hullmod.advanced_ecm_network.line.cap",
                hl("4%/8%/12%/16%", HullmodTone.HIGHLIGHT),
            )
            heading("ui.hullmod.advanced_ecm_network.section.counter")
            para(
                "ui.hullmod.advanced_ecm_network.line.max",
                v("boostPct", percent(values.maxEffectBoost * 100f)),
                v("maxPct", percent(AdvancedEcmNetworkTuning.VANILLA_MAX_EFFECT * (1f + values.maxEffectBoost))),
                hl(percent(values.maxEffectBoost * 100f), HullmodTone.HIGHLIGHT),
                hl(
                    percent(AdvancedEcmNetworkTuning.VANILLA_MAX_EFFECT * (1f + values.maxEffectBoost)),
                    HullmodTone.HIGHLIGHT,
                ),
            )
            para(
                "ui.hullmod.advanced_ecm_network.line.point",
                v("pointPct", percent(values.pointMaxBoost * 100f)),
                hl(percent(values.pointMaxBoost * 100f), HullmodTone.HIGHLIGHT),
            )
            para(
                "ui.hullmod.advanced_ecm_network.line.capture",
                v("capturePct", percent((values.captureRateMult - 1f) * 100f)),
                hl(percent((values.captureRateMult - 1f) * 100f), HullmodTone.HIGHLIGHT),
            )
            para("ui.hullmod.advanced_ecm_network.line.debuff")
            table {
                row(
                    "ui.hullmod.advanced_ecm_network.table.debuff.row_0.label",
                    "ui.hullmod.advanced_ecm_network.table.debuff.row_0.value",
                    HullmodTone.DEFAULT, HullmodTone.HIGHLIGHT,
                )
                row(
                    "ui.hullmod.advanced_ecm_network.table.debuff.row_1.label",
                    "ui.hullmod.advanced_ecm_network.table.debuff.row_1.value",
                    HullmodTone.DEFAULT, HullmodTone.HIGHLIGHT,
                )
                row(
                    "ui.hullmod.advanced_ecm_network.table.debuff.row_2.label",
                    "ui.hullmod.advanced_ecm_network.table.debuff.row_2.value",
                    HullmodTone.DEFAULT, HullmodTone.HIGHLIGHT,
                )
                row(
                    "ui.hullmod.advanced_ecm_network.table.debuff.row_3.label",
                    "ui.hullmod.advanced_ecm_network.table.debuff.row_3.value",
                    HullmodTone.DEFAULT, HullmodTone.HIGHLIGHT,
                )
            }
            para("ui.hullmod.advanced_ecm_network.line.final", hl("最终值减益", HullmodTone.HIGHLIGHT))
            heading("ui.hullmod.advanced_ecm_network.section.limits")
            para(
                "ui.hullmod.advanced_ecm_network.line.limit_ecm",
                hl("#", HullmodTone.RED), hl("电子对抗组件", HullmodTone.RED),
            )
            para(
                "ui.hullmod.advanced_ecm_network.line.limit_eccm",
                hl("#", HullmodTone.RED), hl("电子反对抗组件", HullmodTone.RED),
            )
            spacer(6f)
            para("ui.hullmod.export.difficulty_note", hl("难度系数", HullmodTone.DEFAULT))
        }
    }

    override fun isApplicableToShip(ship: ShipAPI): Boolean {
        if (ship.variant?.let(::hasForbiddenHullMod) == true) return false
        return ship.hasHullId(HULL_ID)
    }

    override fun getUnapplicableReason(ship: ShipAPI): String? {
        if (ship.variant?.let(::hasForbiddenHullMod) == true) {
            return I18n[I18n.Categories.MOD, "ui.hullmod.advanced_ecm_network.unapplicable.ecm_mods"]
        }
        return null
    }

    override fun showInRefitScreenModPickerFor(ship: ShipAPI): Boolean = false

    override fun getBorderColor(): Color = THEME.borderColor

    override fun getNameColor(): Color = THEME.nameColor

    private fun hasForbiddenHullMod(variant: ShipVariantAPI): Boolean =
        FORBIDDEN_HULLMOD_IDS.any { forbiddenId ->
            variant.hasHullMod(forbiddenId) ||
                    variant.permaMods.contains(forbiddenId) ||
                    variant.sMods.contains(forbiddenId) ||
                    variant.sModdedBuiltIns.contains(forbiddenId) ||
                    variant.suppressedMods.contains(forbiddenId)
        }

    companion object {
        const val HULLMOD_ID = AdvancedEcmNetworkTuning.HULLMOD_ID

        private val FORBIDDEN_HULLMOD_IDS: Set<String> =
            HullmodIncompatibility.forbiddenByController(HULLMOD_ID)

        private const val HULL_ID = "astd_zw_001"

        private const val STATE_KEY = "astd_advanced_ecm_network_state"

        private const val MOD_ID_PREFIX = AdvancedEcmNetworkTuning.MOD_ID_PREFIX

        private const val STATUS_KEY = "astd_advanced_ecm_network_status"

        private val THEME = HullmodThemes.LENS

        private fun percent(value: Float): String =
            if (value == value.toInt().toFloat()) "${value.toInt()}%" else "$value%"

        private fun modIdOf(ship: ShipAPI): String = MOD_ID_PREFIX + ship.id

        private fun ShipVariantAPI?.isZw001Variant(): Boolean {
            val v = this ?: return false
            return v.hullSpec?.hullId == HULL_ID || v.hullSpec?.baseHullId == HULL_ID
        }
    }
}

fun purgeEcmCommanderBoosts(engine: CombatEngineAPI) {
    var purged = 0
    for (owner in 0..1) {
        val manager = engine.getFleetManager(owner) ?: continue
        for (commander in manager.allFleetCommanders) {
            val mod = commander.stats.dynamic.getMod(AdvancedEcmNetworkTuning.EW_MAX_STAT)
            for (source in AdvancedEcmNetworkTuning.fieldModSources(mod.flatBonuses.keys.toList())) {
                mod.unmodifyFlat(source)
                purged++
            }
        }
    }
    if (purged > 0) {
        Global.getLogger(AdvancedEcmNetworkHullMod::class.java)
            .info("[ASTD] 先进电子对抗网络：清理跨战斗残留的指挥官增幅修饰 $purged 项")
    }
}
