package cn.kasuminova.astd.combat.effect.arc

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BeamAPI
import com.fs.starfarer.api.combat.BeamEffectPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.ShipAPI
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import java.util.IdentityHashMap

/**
 * 双子星 DEM payload 光束回调（规格 10 §2.2，两件 payload .wpn 的 `beamEffect`；靠 weapon spec id 区分弹头种类）。
 *
 * 首伤帧一次性（防 1s 照射期多帧重复触发）：
 * 1. 动能光束：追加 [GeminiDemDifficulty.EMP_ARC_COUNT] 道 [GeminiDemDifficulty.EMP_ARC_EMP_DAMAGE] EMP 电弧
 *    （`spawnEmpArc` 原版行为自动索敌武器/引擎模块——规格 §0.1 事实 #15）；
 * 2. 两光束均：向 [GeminiDemSyncHandler] 登记命中（同步窗口判定入口）。
 *
 * 目标过滤（规格 §2.4）：hulk 不登记不触发（记 DEBUG）；战机 EMP 电弧与同步登记均跳过
 * （光束本身伤害照常走原版结算）；drone 按舰船计。
 *
 * perBeam 状态：同一 [BeamAPI] 实例只触发一次首伤帧；weapon 停火（isFiring == false）即移除，
 * 新一轮打击（新 beam 实例或复用实例再次开火）可重新触发。
 */
class GeminiDemPayloadBeamEffect : BeamEffectPlugin {

    /** 首伤帧已触发的 beam 实例表（IdentityHashMap：beam 实例身份即状态，无额外字段需求）。 */
    private val triggeredBeams = IdentityHashMap<BeamAPI, Boolean>()

    override fun advance(amount: Float, engine: CombatEngineAPI, beam: BeamAPI) {
        val kind = when (beam.weapon?.spec?.weaponId) {
            GeminiDemDifficulty.KINETIC_PAYLOAD_ID -> GeminiDemSyncHandler.WarheadKind.KINETIC
            GeminiDemDifficulty.HE_PAYLOAD_ID -> GeminiDemSyncHandler.WarheadKind.HE
            else -> return
        }

        // 停火清理：weapon 不再开火时移除状态，下一轮打击可重新触发（规格 §4.1 用例 10）
        if (beam.weapon?.isFiring != true) {
            triggeredBeams.remove(beam)
            return
        }

        if (!beam.didDamageThisFrame()) return
        val target = beam.damageTarget as? ShipAPI ?: return
        if (triggeredBeams.containsKey(beam)) return
        triggeredBeams[beam] = true

        val point = beam.to
        if (target.isHulk) {
            log.debug("双子星 DEM payload：目标为残骸（${target.id}），不登记不触发同步（光束伤害照常）")
            return
        }
        if (target.isFighter) {
            log.debug("双子星 DEM payload：目标为战机（${target.id}），跳过 EMP 电弧与同步登记（光束伤害照常）")
            return
        }

        if (kind == GeminiDemSyncHandler.WarheadKind.KINETIC) {
            repeat(GeminiDemDifficulty.EMP_ARC_COUNT) {
                engine.spawnEmpArc(
                    beam.source,
                    point,
                    target,
                    target,
                    DamageType.ENERGY,
                    0f,
                    GeminiDemDifficulty.EMP_ARC_EMP_DAMAGE,
                    EMP_ARC_MAX_RANGE,
                    EMP_ARC_SOUND_ID,
                    EMP_ARC_THICKNESS,
                    ARC_FRINGE,
                    ARC_CORE,
                )
            }
            engine.customData[TELEMETRY_EMP_ARCS] = empArcCount(engine) + GeminiDemDifficulty.EMP_ARC_COUNT
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

        GeminiDemSyncHandler.recordHit(engine, target, kind, point, beam.source)
    }

    companion object {
        private val log = Global.getLogger(GeminiDemPayloadBeamEffect::class.java)

        /** EMP 电弧配色（动能冷蓝白，与 payload 光束三色同族）。 */
        private val ARC_FRINGE = Color(140, 200, 255)
        private val ARC_CORE = Color(225, 242, 255)

        /** 电弧音效（settings.json 音效表已核实存在）。 */
        private const val EMP_ARC_SOUND_ID = "tachyon_lance_emp_impact"
        private const val EMP_ARC_THICKNESS = 20f

        /** 电弧索敌半径（spawnEmpArc 在目标舰上自选武器/引擎模块，给足全舰覆盖）。 */
        private const val EMP_ARC_MAX_RANGE = 10_000f

        /** 遥测键：动能光束追加的 EMP 电弧累计道数（应为动能命中次数 ×4）。 */
        const val TELEMETRY_EMP_ARCS = "astd_gemini_dem_emp_arc_count"

        /** 遥测键：动能/高爆 payload 光束首伤帧命中次数（同步配对观测面）。 */
        const val TELEMETRY_KINETIC_HIT = "astd_gemini_dem_kinetic_hit_count"
        const val TELEMETRY_HE_HIT = "astd_gemini_dem_he_hit_count"

        fun empArcCount(engine: CombatEngineAPI): Int = engine.customData[TELEMETRY_EMP_ARCS] as? Int ?: 0
        fun kineticHitCount(engine: CombatEngineAPI): Int = engine.customData[TELEMETRY_KINETIC_HIT] as? Int ?: 0
        fun heHitCount(engine: CombatEngineAPI): Int = engine.customData[TELEMETRY_HE_HIT] as? Int ?: 0
    }
}
