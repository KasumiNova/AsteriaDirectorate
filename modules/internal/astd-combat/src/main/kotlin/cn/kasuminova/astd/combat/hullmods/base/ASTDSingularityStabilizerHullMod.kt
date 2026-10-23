package cn.kasuminova.astd.combat.hullmods.base

import cn.kasuminova.astd.combat.hullmods.HullmodIncompatibility
import cn.kasuminova.astd.combat.hullmods.base.ASTDSingularityStabilizerHullMod.Companion.UNITY_EPSILON
import cn.kasuminova.astd.combat.hullmods.base.ASTDSingularityStabilizerHullMod.Companion.resolveTimeAnchor
import cn.kasuminova.astd.ui.dsl.HullmodTheme
import cn.kasuminova.astd.ui.dsl.HullmodThemes
import cn.kasuminova.astd.ui.dsl.hullmodCard
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI

class ASTDSingularityStabilizerHullMod : BaseHullMod() {

    companion object {
        const val ID_ARC: String = "astd_arc_singularity_stabilizer"
        const val ID_LENS: String = "astd_lens_singularity_stabilizer"

        internal const val MOD_ID = "astd_singularity_stabilizer"

        private val FORBIDDEN_HULLMOD_IDS: Set<String> = HullmodIncompatibility.forbiddenByController(ID_ARC)

        private const val UNITY_EPSILON = 0.001f

        internal data class TimeAnchor(val clampMult: Float?, val anchor: Float?)

        internal fun resolveTimeAnchor(raw: Float): TimeAnchor = when {
            raw <= 0f -> TimeAnchor(null, null)
            raw < 1f - UNITY_EPSILON -> TimeAnchor(1f / raw, null)
            raw <= 1f + UNITY_EPSILON -> TimeAnchor(null, null)
            else -> TimeAnchor(null, 1f / raw)
        }
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        // 只检测入队：本回调可能在原版 OP 结算的 hullMods 实时迭代内触发，结构性移除会抛 CME
        IncompatibleHullmodStripper.requestStrip(stats.variant, FORBIDDEN_HULLMOD_IDS, MOD_ID)
    }

    override fun applyEffectsAfterShipCreation(ship: ShipAPI, id: String) {
        // ShipFactory 迭代 getAllMods() 快照，此处入队并立即清理是安全的
        IncompatibleHullmodStripper.requestStrip(ship.variant, FORBIDDEN_HULLMOD_IDS, MOD_ID)
        IncompatibleHullmodStripper.drainPending()
    }

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        IncompatibleHullmodStripper.drainPending()
        if (amount <= 0f || ship.isHulk || !ship.isAlive) return
        val stats = ship.mutableStats

        stats.timeMult.unmodifyMult(MOD_ID)
        val decision = resolveTimeAnchor(stats.timeMult.modifiedValue)
        if (decision.clampMult != null) {
            stats.timeMult.modifyMult(MOD_ID, decision.clampMult)
        }

        val anchor = decision.anchor
        // 原版仅在接敌状态推进 timeDeployed 并扣 CR，脱战时回卷会返还峰值时间
        if (anchor == null || !ship.areSignificantEnemiesInRange()) {
            stats.crLossPerSecondPercent.unmodifyMult(MOD_ID)
            return
        }
        stats.crLossPerSecondPercent.modifyMult(MOD_ID, anchor)
        // ShipAPI 无 getTimeDeployed()，等值读取口为 timeDeployedForCRReduction；Kotlin 属性语法要求读写成对，写入用显式 setter
        ship.setTimeDeployed(ship.timeDeployedForCRReduction - amount * (1f - anchor))
    }

    override fun addPostDescriptionSection(
        tooltip: TooltipMakerAPI,
        hullSize: ShipAPI.HullSize,
        ship: ShipAPI?,
        width: Float,
        isForModSpec: Boolean,
    ) {
        tooltip.hullmodCard(width, theme(), spec?.displayName) {
            para("ui.hullmod.singularity_stabilizer.desc")
        }
    }

    private fun theme(): HullmodTheme =
        if (spec?.id == ID_LENS) HullmodThemes.LENS else HullmodThemes.ARC
}
