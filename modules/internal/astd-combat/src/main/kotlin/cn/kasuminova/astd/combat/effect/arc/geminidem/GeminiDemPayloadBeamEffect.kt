package cn.kasuminova.astd.combat.effect.arc.geminidem

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BeamAPI
import com.fs.starfarer.api.combat.BeamEffectPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.util.IntervalUtil
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import java.util.IdentityHashMap

/**
 * 双子星 DEM payload 光束回调（规格 10 §2.2，两件 payload .wpn 的 `beamEffect`；靠 weapon spec id 区分弹头种类）。
 *
 * 首伤帧一次性（防照射期重复登记）：
 * - 向 [GeminiDemSyncHandler] 登记命中（同步窗口判定入口）。
 *
 * 照射期持续（仅动能光束，2026-09 机制重做：取代旧首伤帧一次性 4×500 EMP）：
 * - 每 [GeminiDemDifficulty.EMP_ARC_INTERVAL] 秒产生一次电弧打击（末次命中点 → 目标，
 *   `spawnEmpArc` 原版行为自动索敌武器/引擎模块——规格 §0.1 事实 #15），
 *   单道 EMP = 面板 × [GeminiDemDifficulty.EMP_ARC_EMP_FRACTION]（合计与单弹面板等额）。
 *
 * EMP 节律与伤害 tick 解耦（2026-09-26 实机诊断）：原版 payload 光束伤害按 ≈4Hz tick 落账
 * （didDamageThisFrame 每 ~15 帧才一帧为 true），按伤害帧累积节律会全程归零；
 * 改为照射期内每帧累积，自末次伤害帧起 [EMP_GRACE_SECONDS] 秒宽限内视为仍在命中。
 *
 * 目标过滤（规格 §2.4）：hulk 不登记不触发（记 DEBUG）；战机 EMP 电弧与同步登记均跳过
 * （光束本身伤害照常走原版结算）；drone 按舰船计。照射中途目标变 hulk/离场即断电弧。
 *
 * perBeam 状态：同一 [BeamAPI] 实例只登记一次首伤帧；weapon 停火且无伤害帧即移除，
 * 新一轮打击（新 beam 实例或复用实例再次开火）可重新登记。
 */
class GeminiDemPayloadBeamEffect : BeamEffectPlugin {

    /** 单 beam 实例状态：首伤帧标记、EMP 节律与末次命中锚点。 */
    private class BeamState {
        var firstHitDone = false

        /** 目标是否通过 hulk/战机过滤（EMP/同步资格）；首个有效伤害帧定格。 */
        var eligible = false
        var lastTarget: ShipAPI? = null
        val lastPoint = Vector2f(0f, 0f)
        var sinceLastDamage = Float.MAX_VALUE
        val empInterval = IntervalUtil(GeminiDemDifficulty.EMP_ARC_INTERVAL, GeminiDemDifficulty.EMP_ARC_INTERVAL)
    }

    private val beamStates = IdentityHashMap<BeamAPI, BeamState>()

    override fun advance(amount: Float, engine: CombatEngineAPI, beam: BeamAPI) {
        // 隐藏原版束体渲染（保留伤害/命中结算），视觉由 GeminiDemPayloadBeamVfx 的 BoxUtil 光束实体接管
        beam.coreColor = COLOR_TRANSPARENT
        beam.fringeColor = COLOR_TRANSPARENT
        beam.width = 0.01f

        val kind = when (beam.weapon?.spec?.weaponId) {
            GeminiDemDifficulty.KINETIC_PAYLOAD_ID -> GeminiDemSyncHandler.WarheadKind.KINETIC
            GeminiDemDifficulty.HE_PAYLOAD_ID -> GeminiDemSyncHandler.WarheadKind.HE
            else -> return
        }

        // 停火清理：weapon 不再开火且本帧无伤害时移除状态，下一轮打击可重新触发（规格 §4.1 用例 10）。
        // 门控必须容忍「isFiring=false 但光束仍在结算伤害」：payload 是 fires_one_burst 爆发光束，
        // DEMScript 的 demDrone 只点一次 FIRE，isFiring 在爆发首帧后可能翻转，而 1s 照射期伤害 tick 仍在继续。
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
                onFirstHit(engine, beam, kind, target)
                state.eligible = !target.isHulk && !target.isFighter
            }
        } else {
            state.sinceLastDamage += amount
        }

        // EMP 节律：照射期内每帧累积（原版伤害按 ≈4Hz tick 落账，不能按伤害帧累积），
        // 末次伤害帧起 EMP_GRACE_SECONDS 宽限内视为仍在命中；目标变 hulk/离场即断
        if (kind != GeminiDemSyncHandler.WarheadKind.KINETIC || !state.eligible) return
        val empTarget = state.lastTarget ?: return
        if (state.sinceLastDamage > EMP_GRACE_SECONDS) return
        state.empInterval.advance(amount)
        if (!state.empInterval.intervalElapsed()) return
        if (empTarget.isHulk || !engine.isEntityInPlay(empTarget)) {
            state.lastTarget = null
            return
        }
        engine.spawnEmpArc(
            beam.source,
            Vector2f(state.lastPoint),
            empTarget,
            empTarget,
            DamageType.ENERGY,
            0f,
            GeminiDemDifficulty.WARHEAD_PANEL_DAMAGE * GeminiDemDifficulty.EMP_ARC_EMP_FRACTION,
            EMP_ARC_MAX_RANGE,
            EMP_ARC_SOUND_ID,
            EMP_ARC_THICKNESS,
            ARC_FRINGE,
            ARC_CORE,
        )
        engine.customData[TELEMETRY_EMP_ARCS] = empArcCount(engine) + 1
    }

    /** 首伤帧一次性：目标过滤（hulk/战机记 DEBUG 跳过）+ 遥测 + 同步登记。 */
    private fun onFirstHit(engine: CombatEngineAPI, beam: BeamAPI, kind: GeminiDemSyncHandler.WarheadKind, target: ShipAPI) {
        if (target.isHulk) {
            log.debug("双子星 DEM payload：目标为残骸（${target.id}），不登记不触发同步（光束伤害照常）")
            return
        }
        if (target.isFighter) {
            log.debug("双子星 DEM payload：目标为战机（${target.id}），跳过 EMP 电弧与同步登记（光束伤害照常）")
            return
        }

        // R2 读数校准面（规格 §4.2 检查点 4）：首伤帧打印 payload 光束结算面板，与「dps × burstSize」口径核对
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

        GeminiDemSyncHandler.recordHit(engine, target, kind, beam.to, beam.source)
    }

    companion object {
        private val log = Global.getLogger(GeminiDemPayloadBeamEffect::class.java)

        /** EMP 电弧配色（动能冷蓝白，与 payload 光束三色同族）。 */
        private val ARC_FRINGE = Color(140, 200, 255)
        private val ARC_CORE = Color(225, 242, 255)

        /** 原版束体隐藏色（全透明；视觉由 GeminiDemPayloadBeamVfx 接管）。 */
        private val COLOR_TRANSPARENT = Color(0, 0, 0, 0)

        /** 电弧音效（settings.json 音效表已核实存在）。 */
        private const val EMP_ARC_SOUND_ID = "tachyon_lance_emp_impact"
        private const val EMP_ARC_THICKNESS = 20f

        /** 电弧索敌半径（spawnEmpArc 在目标舰上自选武器/引擎模块，给足全舰覆盖）。 */
        private const val EMP_ARC_MAX_RANGE = 10_000f

        /** 命中宽限（秒）：原版伤害 tick ≈4Hz（间隔 ~0.25s），宽限须大于 tick 间隔。 */
        private const val EMP_GRACE_SECONDS = 0.3f

        /** 遥测键：动能光束 EMP 电弧累计道数（≈ 动能命中次数 × 照射秒数 ÷ 0.1）。 */
        const val TELEMETRY_EMP_ARCS = "astd_gemini_dem_emp_arc_count"

        /** 遥测键：动能/高爆 payload 光束首伤帧命中次数（同步配对观测面）。 */
        const val TELEMETRY_KINETIC_HIT = "astd_gemini_dem_kinetic_hit_count"
        const val TELEMETRY_HE_HIT = "astd_gemini_dem_he_hit_count"

        fun empArcCount(engine: CombatEngineAPI): Int = engine.customData[TELEMETRY_EMP_ARCS] as? Int ?: 0
        fun kineticHitCount(engine: CombatEngineAPI): Int = engine.customData[TELEMETRY_KINETIC_HIT] as? Int ?: 0
        fun heHitCount(engine: CombatEngineAPI): Int = engine.customData[TELEMETRY_HE_HIT] as? Int ?: 0
    }
}
