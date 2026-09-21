package cn.kasuminova.astd.combat.hullmods.affix

import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.combat.affix.AffixRegistry
import cn.kasuminova.astd.combat.hullmods.affix.AffixSingularityDriveHullMod.SingularityBurstSubsystem.Companion.DECIDE_INTERVAL
import cn.kasuminova.astd.combat.hullmods.affix.AffixSingularityDriveHullMod.SingularityBurstSubsystem.Companion.ENGAGE_RANGE_MULT
import cn.kasuminova.astd.combat.hullmods.affix.AffixSingularityDriveHullMod.SingularityBurstSubsystem.Companion.FLUX_TRIGGER_LEVEL
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.util.IntervalUtil
import org.lazywizard.lazylib.MathUtils
import org.magiclib.subsystems.MagicSubsystem
import org.magiclib.subsystems.MagicSubsystemsManager

/**
 * 词缀 R-17：奇点驱动（[AffixRegistry.ID_SINGULARITY_DRIVE]）。
 * 按难度系数：舰船获得 300%~900% 峰值时间；降低 20%~40% 武器产生的辐能；
 * 免疫所有环境导致的峰值时间降低效果；降低 25%~75% 过载时间（最终乘区）。
 * 舰船获得子系统「奇点爆发」（[SingularityBurstSubsystem]，MagicSubsystem）。
 */
class AffixSingularityDriveHullMod : BaseHullMod() {

    companion object {
        /** 峰值时间倍率：+300%~+900% → ×4 ~ ×10。 */
        val PEAK_DURATION = ScalingEntry(v1 = 4.0f, v2 = 7.0f, v5 = 10.0f)
        val WEAPON_FLUX_COST = ScalingEntry(v1 = 0.80f, v2 = 0.70f, v5 = 0.60f)
        val OVERLOAD_TIME = ScalingEntry(v1 = 0.75f, v2 = 0.50f, v5 = 0.25f)

        private const val ENV_CANCEL_ID = "astd_affix_singularity_env_cancel"
        private const val PEAK_BONUS_ID = "astd_affix_singularity_peak"
        private const val SUBSYSTEM_INSTALLED_KEY = "astd_affix_singularity_subsystem"
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        val tuning = AffixShared.tuning
        val weaponFlux = tuning.value(WEAPON_FLUX_COST)
        stats.ballisticWeaponFluxCostMod.modifyMult(id, weaponFlux)
        stats.energyWeaponFluxCostMod.modifyMult(id, weaponFlux)
        stats.overloadTimeMod.modifyMult(id, tuning.value(OVERLOAD_TIME))
    }

    override fun applyEffectsAfterShipCreation(ship: ShipAPI, id: String) {
        if (ship.customData[SUBSYSTEM_INSTALLED_KEY] != true) {
            ship.setCustomData(SUBSYSTEM_INSTALLED_KEY, true)
            MagicSubsystemsManager.addSubsystemToShip(ship, SingularityBurstSubsystem(ship))
        }
    }

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        if (!ship.isAlive || ship.isHulk) return
        val stats = ship.mutableStats

        // 峰值时间：先抵消任何把峰值压到基准值以下的外部修正（环境惩罚免疫），再叠乘本词缀加成。
        stats.peakCRDuration.unmodifyMult(ENV_CANCEL_ID)
        stats.peakCRDuration.unmodifyMult(PEAK_BONUS_ID)
        val base = ship.hullSpec?.noCRLossTime ?: 0f
        if (base > 0f) {
            val effectiveWithoutOurs = stats.peakCRDuration.computeEffective(base)
            if (effectiveWithoutOurs < base) {
                stats.peakCRDuration.modifyMult(ENV_CANCEL_ID, base / effectiveWithoutOurs)
            }
            stats.peakCRDuration.modifyMult(PEAK_BONUS_ID, AffixShared.tuning.value(PEAK_DURATION))
        }
    }

    /**
     * 子系统「奇点爆发」：激活后逐渐提升舰船时间流速（10 秒持续、20 秒冷却、不可充能）。
     *
     * AI 口径（affixes.md R-17「AI 逻辑沿用原版时流之壳」）：
     * 原版时流之壳（temporalshell.system）的 AI 实现为混淆内核内建类型
     * （`aiType: TEMPORAL_SHELL`，dev-resources 未镜像对应类，规范禁反射故无法直接复用），
     * 此处按原版可观察行为等效移植（参照本模组 ASTDLimitTemporalThrusterSystemAI 的既有移植风格）：
     * - 进攻窗口：目标进入本舰最长非导弹武器射程的 [ENGAGE_RANGE_MULT] 倍内，借时间膨胀抢占交战段；
     * - 防御窗口：近距存在来袭弹体/导弹威胁，借时间膨胀脱离或机动；
     * - 辐能压力：辐能水平越过 [FLUX_TRIGGER_LEVEL] 时借时间膨胀抢排辐窗口；
     * - 过载/排辐中不启用（与原版一致：系统期间不产生这类收益）。
     * 判定按 [DECIDE_INTERVAL] 节流，避免逐帧全量扫描弹体。
     */
    class SingularityBurstSubsystem(ship: ShipAPI) : MagicSubsystem(ship) {

        /** 时间流速提升幅度（100%~200%，按难度系数）。 */
        private val timeFlowBonus: Float = AffixShared.tuning.value(TIME_FLOW_BONUS)

        private val decideInterval = IntervalUtil(DECIDE_INTERVAL, DECIDE_INTERVAL)

        override fun getBaseActiveDuration(): Float = ACTIVE_DURATION

        override fun getBaseCooldownDuration(): Float = COOLDOWN_DURATION

        override fun getDisplayText(): String = I18n[AffixRegistry.CATEGORY, "affix.astd_affix_singularity_drive.subsystem"]

        override fun shouldActivateAI(amount: Float): Boolean {
            decideInterval.advance(amount)
            if (!decideInterval.intervalElapsed()) return false
            if (ship.fluxTracker.isOverloadedOrVenting) return false

            // 进攻窗口：目标进入交战距离
            val target = ship.shipTarget
            if (target != null && target.isAlive && !target.isHulk) {
                val engageRange = longestWeaponRange() * ENGAGE_RANGE_MULT
                if (MathUtils.getDistance(ship, target) <= engageRange) return true
            }

            // 防御窗口：近距来袭威胁
            if (incomingThreat()) return true

            // 辐能压力：抢排辐窗口
            return ship.fluxTracker.fluxLevel >= FLUX_TRIGGER_LEVEL
        }

        override fun advance(amount: Float, isPaused: Boolean) {
            if (isPaused) return
            if (state == State.ACTIVE) {
                // 逐渐提升：随激活进度从 1 爬升到 1 + bonus。
                val progress = (stateInterval.elapsed / stateInterval.intervalDuration).coerceIn(0f, 1f)
                stats.timeMult.modifyMult(MOD_ID, 1f + timeFlowBonus * progress)
            } else {
                stats.timeMult.unmodifyMult(MOD_ID)
            }
        }

        /** 最长非导弹武器射程（无武器时按 700su 交战距离兜底）。 */
        private fun longestWeaponRange(): Float =
            ship.allWeapons
                .asSequence()
                .filter { !it.isDecorative && it.type != WeaponAPI.WeaponType.MISSILE }
                .map { it.range }
                .filter { it > 0f }
                .maxOrNull() ?: 700f

        /** 近距来袭弹体/导弹威胁判定（含未爆弹体，EMP 按 0.25 折算伤害量）。 */
        private fun incomingThreat(): Boolean {
            val engine = Global.getCombatEngine() ?: return false
            return engine.projectiles.any { projectile ->
                projectile is DamagingProjectileAPI &&
                        projectile.owner != ship.owner &&
                        !projectile.didDamage() &&
                        MathUtils.getDistance(ship.location, projectile.location) <= THREAT_RANGE &&
                        projectile.damageAmount + projectile.empAmount * 0.25f >= THREAT_MIN_DAMAGE
            }
        }

        companion object {
            /** 时间流速提升幅度：100%~200%。 */
            val TIME_FLOW_BONUS = ScalingEntry(v1 = 1.0f, v2 = 1.5f, v5 = 2.0f)

            const val ACTIVE_DURATION = 10f
            const val COOLDOWN_DURATION = 20f

            /** AI 判定节流间隔（秒）。 */
            const val DECIDE_INTERVAL = 0.25f

            /** AI 进攻窗口：最长武器射程的倍率。 */
            const val ENGAGE_RANGE_MULT = 1.1f

            /** AI 辐能压力触发线。 */
            const val FLUX_TRIGGER_LEVEL = 0.35f

            /** 防御窗口的威胁判定距离（su）与最小折算伤害量。 */
            const val THREAT_RANGE = 700f
            const val THREAT_MIN_DAMAGE = 250f

            private const val MOD_ID = "astd_affix_singularity_burst"
        }
    }
}
