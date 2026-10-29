package cn.kasuminova.astd.combat.automation.scenario.system.phaseai

import cn.kasuminova.astd.combat.automation.api.AutomationCombatContext
import cn.kasuminova.astd.combat.automation.base.AbstractPhaseAICombatScenario
import cn.kasuminova.astd.combat.automation.base.AutomationEvidence
import cn.kasuminova.astd.combat.automation.base.PhaseMotionEvidence
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.mission.FleetSide
import org.lwjgl.util.vector.Vector2f
import kotlin.math.cos
import kotlin.math.sin

abstract class AbstractPhaseAiSeedScenario : AbstractPhaseAICombatScenario() {

    abstract val seed: Long
    override val observeTimeoutSec: Float = 180f

    private var fleetBuilt: List<FleetEntry>? = null
    private var longestPhaseSec = 0f
    private var currentPhaseStartSec = -1f
    private var lastFireSec = 0f
    private var lastEffectiveHitSec = 0f
    private var lastObservedFireCount = 0
    private var lastObservedHitCount = 0
    private var stagnantPhaseSec = 0f
    private var longestFireGapSec = 0f
    private var longestEffectiveHitGapSec = 0f
    private val motion = PhaseMotionEvidence()

    private data class SeedConfig(
        val playerHullId: String,
        val playerVariantId: String,
        val enemyHullId: String,
        val enemyVariantId: String,
        val distanceSu: Float,
        val bearingDeg: Float,
        val enemyCount: Int,
    )

    private val config: SeedConfig by lazy { generateConfig(seed) }

    override fun buildFleet(): List<FleetEntry> {
        fleetBuilt?.let { return it }
        val cfg = config
        val bearingRad = Math.toRadians(cfg.bearingDeg.toDouble())
        val halfDist = cfg.distanceSu / 2f
        val playerAnchor = Vector2f(
            (-cos(bearingRad) * halfDist).toFloat(),
            (-sin(bearingRad) * halfDist).toFloat(),
        )
        val enemyFacing = (cfg.bearingDeg + 180f) % 360f
        val entries = mutableListOf(
            FleetEntry("player_${cfg.playerHullId}", FleetSide.PLAYER, cfg.playerVariantId, cfg.playerHullId, playerAnchor, cfg.bearingDeg.toFloat() % 360f, true),
        )
        for (i in 0 until cfg.enemyCount) {
            val spreadDeg = if (cfg.enemyCount == 1) 0.0 else (i - (cfg.enemyCount - 1) / 2.0) * 15.0
            val eBearingRad = Math.toRadians(cfg.bearingDeg + spreadDeg)
            val spread = cfg.distanceSu * 0.1f * i
            val eAnchor = Vector2f(
                (cos(eBearingRad) * halfDist).toFloat() + spread,
                (sin(eBearingRad) * halfDist).toFloat(),
            )
            entries.add(FleetEntry("enemy_${cfg.enemyHullId}_$i", FleetSide.ENEMY, cfg.enemyVariantId, cfg.enemyHullId, eAnchor, enemyFacing, false))
        }
        fleetBuilt = entries
        return entries
    }

    override fun verifyEvidence(
        ctx: AutomationCombatContext,
        combatSeconds: Float,
        player: ShipAPI?,
        enemies: List<ShipAPI>,
    ): Result {
        val phased = player?.isPhased == true
        if (player != null && motion.sample(combatSeconds, player.location.x, player.location.y)) {
            ctx.failureReason = "seed=$seed 15秒内路径超过600su但净位移不足150su，判定为绕圈"
            return Result.Failed
        }
        val timeOffset = evidence.sampleTimeSec - combatSeconds
        for (index in lastObservedFireCount until evidence.fireEvents.size) {
            val time = (evidence.fireEvents[index].timeSec - timeOffset).coerceAtLeast(0f)
            longestFireGapSec = maxOf(longestFireGapSec, time - lastFireSec)
            lastFireSec = maxOf(lastFireSec, time)
        }
        lastObservedFireCount = evidence.fireEvents.size
        for (index in lastObservedHitCount until evidence.hitEvents.size) {
            val hit = evidence.hitEvents[index]
            if (hit.effectiveDamage <= 0f) continue
            val time = (hit.timeSec - timeOffset).coerceAtLeast(0f)
            longestEffectiveHitGapSec = maxOf(longestEffectiveHitGapSec, time - lastEffectiveHitSec)
            lastEffectiveHitSec = maxOf(lastEffectiveHitSec, time)
        }
        lastObservedHitCount = evidence.hitEvents.size
        stagnantPhaseSec = combatSeconds - lastFireSec
        longestFireGapSec = maxOf(longestFireGapSec, stagnantPhaseSec)
        longestEffectiveHitGapSec = maxOf(longestEffectiveHitGapSec, combatSeconds - lastEffectiveHitSec)
        if (currentPhaseStartSec >= 0f) {
            longestPhaseSec = maxOf(longestPhaseSec, combatSeconds - currentPhaseStartSec)
        }
        if (phased) {
            if (currentPhaseStartSec < 0f) currentPhaseStartSec = combatSeconds
        } else currentPhaseStartSec = -1f
        if (longestPhaseSec > 60f || longestFireGapSec > 60f || longestEffectiveHitGapSec > 60f) {
            ctx.failureReason = "seed=$seed 持续超过60秒未产生新射击、有效命中或未完成上浮"
            return Result.Failed
        }
        val fire = evidence.completeSurfaceFire()
        val qualified = !phased && evidence.surfaceCount >= 1 && fire != null &&
            evidence.playerEffectiveDamage(fire.timeSec) > 500f
        val ended = player == null || player.isHulk || !ctx.engine.isEntityInPlay(player) ||
            enemies.all { it.isHulk || !ctx.engine.isEntityInPlay(it) }
        if (qualified && (ended || combatSeconds >= observeTimeoutSec)) return Result.Completed
        if (ended || combatSeconds >= observeTimeoutSec) {
            ctx.failureReason = "seed=$seed 未满足完整相位周期、上浮后本舰输出及持续有效交战"
            return Result.Failed
        }
        return Result.Observing
    }

    override fun appendScenarioEvidence(ctx: AutomationCombatContext, json: StringBuilder) {
        json.appendLine("  \"seedValue\": $seed,")
        json.appendLine("  \"seedPlayerHullId\": ${AutomationEvidence.jsonString(config.playerHullId)},")
        json.appendLine("  \"seedEnemyHullId\": ${AutomationEvidence.jsonString(config.enemyHullId)},")
        json.appendLine("  \"seedDistance\": ${config.distanceSu},")
        json.appendLine("  \"seedBearingDeg\": ${config.bearingDeg},")
        json.appendLine("  \"seedEnemyCount\": ${config.enemyCount},")
        json.appendLine("  \"seedLongestPhaseSec\": ${formatFloat(longestPhaseSec)},")
        json.appendLine("  \"seedStagnantPhaseSec\": ${formatFloat(stagnantPhaseSec)},")
        json.appendLine("  \"seedLongestFireGapSec\": ${formatFloat(longestFireGapSec)},")
        json.appendLine("  \"seedLongestEffectiveHitGapSec\": ${formatFloat(longestEffectiveHitGapSec)},")
        json.appendLine("  \"seedCircling\": ${motion.circling},")
        json.appendLine("  \"seedWindowPath\": ${formatFloat(motion.windowPath)},")
        json.appendLine("  \"seedWindowDisplacement\": ${formatFloat(motion.windowDisplacement)},")
    }

    companion object {
        private val PLAYER_HULLS = listOf(
            "astd_zw_101" to "astd_zw_101_Standard",
            "astd_zw_002" to "astd_zw_002_Standard",
            "astd_zw_103" to "astd_zw_103_Standard",
        )
        private val ENEMY_OPTIONS = listOf(
            "dominator" to "dominator_Assault",
            "conquest" to "conquest_Standard",
            "hammerhead" to "hammerhead_Balanced",
            "eagle" to "eagle_Assault",
        )
        private val DISTANCES = listOf(1500f, 2000f, 2500f, 3000f)
        private val BEARINGS = listOf(0f, 30f, 60f, 90f, 120f, 150f)
        private val COUNTS = listOf(1, 1, 1, 2, 2, 3)

        private fun generateConfig(seed: Long): SeedConfig {
            val r = java.util.Random(seed)
            val (pH, pV) = PLAYER_HULLS[r.nextInt(PLAYER_HULLS.size)]
            val (eH, eV) = ENEMY_OPTIONS[r.nextInt(ENEMY_OPTIONS.size)]
            val dist = DISTANCES[r.nextInt(DISTANCES.size)]
            val bearing = BEARINGS[r.nextInt(BEARINGS.size)]
            val count = COUNTS[r.nextInt(COUNTS.size)]
            return SeedConfig(pH, pV, eH, eV, dist, bearing, count)
        }
    }
}
