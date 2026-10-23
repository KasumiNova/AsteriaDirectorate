package cn.kasuminova.astd.combat.effect.arc.geminidem

import cn.kasuminova.astd.combat.effect.base.SicWeaponUpgradeFlags
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BeamAPI
import com.fs.starfarer.api.combat.BeamEffectPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.util.Misc
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import java.util.IdentityHashMap

// 原版 payload 光束伤害按约 4Hz tick 落账（didDamageThisFrame 多数帧为 false），
// EMP 节律按照射期每帧累积，末次伤害帧起 EMP_GRACE_SECONDS 宽限内视为仍在命中。
class GeminiDemPayloadBeamEffect : BeamEffectPlugin {

    private class BeamState {
        var firstHitDone = false

        var eligible = false
        var lastTarget: ShipAPI? = null
        val lastPoint = Vector2f(0f, 0f)
        var sinceLastDamage = Float.MAX_VALUE

        var empElapsed = 0f

        var arcsFired = 0

        var empPerArc = Float.NaN

        var syncResonated = false
    }

    private val beamStates = IdentityHashMap<BeamAPI, BeamState>()

    override fun advance(amount: Float, engine: CombatEngineAPI, beam: BeamAPI) {
        // 仅隐藏原版束体渲染，伤害与命中结算不受影响。
        beam.coreColor = COLOR_TRANSPARENT
        beam.fringeColor = COLOR_TRANSPARENT
        beam.width = 0.01f

        val weaponId = beam.weapon?.spec?.weaponId
        val kind = when (weaponId) {
            GeminiDemDifficulty.KINETIC_PAYLOAD_ID, GeminiDemDifficulty.KINETIC_PAYLOAD_FIGHTER_ID ->
                GeminiDemSyncHandler.WarheadKind.KINETIC

            GeminiDemDifficulty.HE_PAYLOAD_ID, GeminiDemDifficulty.HE_PAYLOAD_FIGHTER_ID ->
                GeminiDemSyncHandler.WarheadKind.HE

            else -> return
        }
        val fighterPayload = weaponId == GeminiDemDifficulty.KINETIC_PAYLOAD_FIGHTER_ID ||
                weaponId == GeminiDemDifficulty.HE_PAYLOAD_FIGHTER_ID

        // fires_one_burst 爆发光束在 isFiring 翻转后伤害 tick 仍在继续，停火清理必须容忍该窗口。
        val damaging = beam.didDamageThisFrame()
        if (beam.weapon?.isFiring != true && !damaging) {
            beamStates.remove(beam)
            return
        }

        val state = beamStates.getOrPut(beam) { BeamState() }
        val target = beam.damageTarget as? ShipAPI
        if (damaging && target != null) {
            state.lastTarget = target
            state.lastPoint.set(beam.to)
            state.sinceLastDamage = 0f
            if (!state.firstHitDone) {
                state.firstHitDone = true
                onFirstHit(engine, beam, state, kind, target)
                state.eligible = !target.isHulk && !target.isFighter
            }
        } else {
            state.sinceLastDamage += amount
        }

        val arcOwner = beam.source?.owner ?: beam.weapon?.ship?.owner
        val upgradeActive = arcOwner != null && SicWeaponUpgradeFlags.isArmamentUpgradeActiveForOwner(engine, arcOwner)

        val heArcs = kind == GeminiDemSyncHandler.WarheadKind.HE && upgradeActive && state.syncResonated
        if ((kind != GeminiDemSyncHandler.WarheadKind.KINETIC && !heArcs) || !state.eligible) return
        val empTarget = state.lastTarget ?: return
        if (state.sinceLastDamage > EMP_GRACE_SECONDS) return
        if (state.arcsFired >= GeminiDemDifficulty.EMP_ARC_COUNT) return
        if (empTarget.isHulk || !engine.isEntityInPlay(empTarget)) {
            state.lastTarget = null
            return
        }
        state.empElapsed += amount
        if (state.empElapsed < state.arcsFired * GeminiDemDifficulty.EMP_ARC_INTERVAL) return
        if (state.empPerArc.isNaN()) {
            state.empPerArc = resolveEmpPerArc(beam, fighterPayload)
        }
        val from = Vector2f(state.lastPoint)
        if (rollShieldPierce(empTarget, upgradeActive)) {
            engine.spawnEmpArcPierceShields(
                beam.source, from, empTarget, empTarget,
                DamageType.ENERGY, 0f, state.empPerArc, EMP_ARC_MAX_RANGE,
                EMP_ARC_SOUND_ID, EMP_ARC_THICKNESS, ARC_FRINGE, ARC_CORE,
            )
            engine.customData[TELEMETRY_PIERCE_ARCS] = pierceArcCount(engine) + 1
        } else {
            engine.spawnEmpArc(
                beam.source, from, empTarget, empTarget,
                DamageType.ENERGY, 0f, state.empPerArc, EMP_ARC_MAX_RANGE,
                EMP_ARC_SOUND_ID, EMP_ARC_THICKNESS, ARC_FRINGE, ARC_CORE,
            )
        }
        state.arcsFired++
        engine.customData[TELEMETRY_EMP_ARCS] = empArcCount(engine) + 1
    }

    private fun rollShieldPierce(empTarget: ShipAPI, upgradeActive: Boolean): Boolean {
        if (!upgradeActive) return false
        val chance = GeminiDemDifficulty.arcShieldPierceChance(
            GeminiDemDifficulty.hardFluxFraction(empTarget.fluxTracker.hardFlux, empTarget.fluxTracker.maxFlux),
        )
        return chance > 0f && Misc.random.nextFloat() < chance
    }

    private fun resolveEmpPerArc(beam: BeamAPI, fighterPayload: Boolean): Float {
        val source = beam.source
        val fraction = if (source == null) {
            log.warn("双子星 DEM payload：beam.source 缺失，EMP 总量倍率保守取 v2")
            GeminiDemDifficulty.EMP_TOTAL_FRACTION.v2
        } else {
            GeminiDemDifficulty.resolve(GeminiDemDifficulty.EMP_TOTAL_FRACTION, source.owner)
        }
        val fighterMult = if (fighterPayload) GeminiDemDifficulty.FIGHTER_DAMAGE_MULT else 1f
        return GeminiDemDifficulty.WARHEAD_PANEL_DAMAGE * fraction * GeminiDemDifficulty.EMP_ARC_SHARE_OF_TOTAL * fighterMult
    }

    private fun onFirstHit(
        engine: CombatEngineAPI,
        beam: BeamAPI,
        state: BeamState,
        kind: GeminiDemSyncHandler.WarheadKind,
        target: ShipAPI,
    ) {
        if (target.isHulk) {
            log.debug("双子星 DEM payload：目标为残骸（${target.id}），不登记不触发同步（光束伤害照常）")
            return
        }
        if (target.isFighter) {
            log.debug("双子星 DEM payload：目标为战机（${target.id}），跳过 EMP 电弧与同步登记（光束伤害照常）")
            return
        }

        log.info(
            "双子星 DEM payload 首伤帧：kind=$kind target=${target.id} " +
                    "beamDamage=${beam.damage?.damage} source=${(beam.source as? ShipAPI)?.id ?: beam.source} owner=${beam.source?.owner}",
        )
        when (kind) {
            GeminiDemSyncHandler.WarheadKind.KINETIC ->
                engine.customData[TELEMETRY_KINETIC_HIT] = kineticHitCount(engine) + 1

            GeminiDemSyncHandler.WarheadKind.HE ->
                engine.customData[TELEMETRY_HE_HIT] = heHitCount(engine) + 1
        }

        state.syncResonated =
            GeminiDemSyncHandler.recordHit(engine, target, kind, beam.to, beam.source) ||
                    GeminiDemSyncHandler.wasResonated(engine, target)
    }

    companion object {
        private val log = Global.getLogger(GeminiDemPayloadBeamEffect::class.java)

        private val ARC_FRINGE = Color(140, 200, 255)
        private val ARC_CORE = Color(225, 242, 255)

        private val COLOR_TRANSPARENT = Color(0, 0, 0, 0)

        private const val EMP_ARC_SOUND_ID = "tachyon_lance_emp_impact"
        private const val EMP_ARC_THICKNESS = 20f

        // spawnEmpArc 不提供命中点参数，命中位置由原版在目标舰上自行选取。
        private const val EMP_ARC_MAX_RANGE = 10_000f

        // 原版伤害 tick 约 4Hz（间隔约 0.25s），命中宽限须大于 tick 间隔。
        private const val EMP_GRACE_SECONDS = 0.3f

        const val TELEMETRY_EMP_ARCS = "astd_gemini_dem_emp_arc_count"

        const val TELEMETRY_PIERCE_ARCS = "astd_gemini_dem_emp_arc_pierce_count"

        const val TELEMETRY_KINETIC_HIT = "astd_gemini_dem_kinetic_hit_count"
        const val TELEMETRY_HE_HIT = "astd_gemini_dem_he_hit_count"

        fun empArcCount(engine: CombatEngineAPI): Int = engine.customData[TELEMETRY_EMP_ARCS] as? Int ?: 0
        fun pierceArcCount(engine: CombatEngineAPI): Int = engine.customData[TELEMETRY_PIERCE_ARCS] as? Int ?: 0
        fun kineticHitCount(engine: CombatEngineAPI): Int = engine.customData[TELEMETRY_KINETIC_HIT] as? Int ?: 0
        fun heHitCount(engine: CombatEngineAPI): Int = engine.customData[TELEMETRY_HE_HIT] as? Int ?: 0
    }
}
