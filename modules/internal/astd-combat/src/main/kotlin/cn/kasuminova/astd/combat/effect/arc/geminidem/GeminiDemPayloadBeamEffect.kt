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

/**
 * 双子星 DEM payload 光束回调（规格 10 §2.2，四件 payload .wpn 的 `beamEffect`：舰装/战机版共用；靠 weapon spec id 区分弹头种类与战机削弱）。
 *
 * 首伤帧一次性（防照射期重复登记）：
 * - 向 [GeminiDemSyncHandler] 登记命中（同步窗口判定入口）。
 *
 * 照射期持续（仅动能光束；SIC「武备升级」激活且目标已触发共振时高爆光束同等参与——
 * 红色导弹电弧与蓝色一致，且全部电弧按目标硬辐能比例概率穿透护盾，见 advance 内分支）：
 * - 首道电弧随首伤帧立即打出，其后每 [GeminiDemDifficulty.EMP_ARC_INTERVAL] 秒一道
 *   （末次命中点 → 目标，`spawnEmpArc` 原版行为自动索敌武器/引擎模块——规格 §0.1 事实 #15），
 *   每轮打击（自 beam 首伤帧起）预算固定 [GeminiDemDifficulty.EMP_ARC_COUNT] 道，打满即止；
 *   单道 EMP = 面板 × EMP 总量难度倍率（[GeminiDemDifficulty.EMP_TOTAL_FRACTION]，玩家按我方档位系数/敌版轨一）
 *   × [GeminiDemDifficulty.EMP_ARC_SHARE_OF_TOTAL]，战机型弹头再乘 [GeminiDemDifficulty.FIGHTER_DAMAGE_MULT]。
 *
 * 战机型削弱（数据驱动链，见 [GeminiDemDifficulty] 战机链注释）：战机版弹头 spec 的
 * payloadWeaponId 指向战机版 payload 武器（dps 已按 ×[GeminiDemDifficulty.FIGHTER_DAMAGE_MULT] 折算），
 * 光束伤害无需脚本干预；本类按 payload 武器 spec id 识别战机版，仅 EMP 电弧单道乘算同一系数
 * （与难度缩放乘算叠加）。实体标记通道不成立：DEMScript 打击段新建 FX drone 承载光束，弹头 customData 不传递。
 *
 * EMP 节律与伤害 tick 解耦（2026-09-26 实机诊断）：原版 payload 光束伤害按 ≈4Hz tick 落账
 * （didDamageThisFrame 每 ~15 帧才一帧为 true），按伤害帧累积节律会全程归零；
 * 改为照射期内每帧累积，自末次伤害帧起 [EMP_GRACE_SECONDS] 秒宽限内视为仍在命中。
 * 首道立即打出的动机（2026-09-27 实机判例）：首伤帧 → 末次 tick + 宽限的有效命中窗口仅 ≈0.8s，
 * 若首道也等满一个间隔，5 道 × 0.2s 的第 5 道（阈值 1.0s）会落在窗口外。
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

        /** EMP 节律累积（秒，自首个有效伤害帧起）：第 k 道电弧阈值为 k × [GeminiDemDifficulty.EMP_ARC_INTERVAL]。 */
        var empElapsed = 0f

        /** 本轮打击已打出的 EMP 电弧道数（预算 [GeminiDemDifficulty.EMP_ARC_COUNT]，打满即止）。 */
        var arcsFired = 0

        /** 单道 EMP 伤害（首道电弧前按来源难度与战机标记结算一次，轮内不变）。 */
        var empPerArc = Float.NaN

        /** 目标已触发同步共振（SIC「武备升级」红色导弹电弧的发射前提；首伤帧登记时定格）。 */
        var syncResonated = false
    }

    private val beamStates = IdentityHashMap<BeamAPI, BeamState>()

    override fun advance(amount: Float, engine: CombatEngineAPI, beam: BeamAPI) {
        // 隐藏原版束体渲染（保留伤害/命中结算），视觉由 GeminiDemPayloadBeamVfx 的 BoxUtil 光束实体接管
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
        // 战机版 payload（数据驱动 ×0.75 削弱链）：光束伤害由战机版 spec dps 天然折算，脚本侧只剩 EMP 单道乘算
        val fighterPayload = weaponId == GeminiDemDifficulty.KINETIC_PAYLOAD_FIGHTER_ID ||
                weaponId == GeminiDemDifficulty.HE_PAYLOAD_FIGHTER_ID

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
                onFirstHit(engine, beam, state, kind, target)
                state.eligible = !target.isHulk && !target.isFighter
            }
        } else {
            state.sinceLastDamage += amount
        }

        // SIC「武备升级」（归属方粒度：payload 光束挂在 DEMScript 新建 FX drone 上，
        // 发射舰标记经 owner 等价判定，见 SicWeaponUpgradeFlags KDoc）：
        // - 红色（高爆）导弹在目标已触发共振后同样产生电弧（与蓝色一致）；
        // - 电弧按目标硬辐能比例概率穿透护盾（25% 起、90% 满载 50%）。
        val arcOwner = beam.source?.owner ?: beam.weapon?.ship?.owner
        val upgradeActive = arcOwner != null && SicWeaponUpgradeFlags.isArmamentUpgradeActiveForOwner(engine, arcOwner)

        // EMP 节律：照射期内每帧累积（原版伤害按 ≈4Hz tick 落账，不能按伤害帧累积），
        // 末次伤害帧起 EMP_GRACE_SECONDS 宽限内视为仍在命中；目标变 hulk/离场即断；
        // 首道随首伤帧立即打出，其后每 EMP_ARC_INTERVAL 一道，预算 EMP_ARC_COUNT 道打满即止
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

    /**
     * 电弧穿盾判定（SIC「武备升级」）：未激活恒 false（现状逐字保留）；激活按目标硬辐能占比
     * 经 [GeminiDemDifficulty.arcShieldPierceChance] 概率掷签（Misc.random 一次性 proc，
     * 不进确定性序列——电弧节律本身由照射窗口驱动，无重放一致性需求）。
     */
    private fun rollShieldPierce(empTarget: ShipAPI, upgradeActive: Boolean): Boolean {
        if (!upgradeActive) return false
        val chance = GeminiDemDifficulty.arcShieldPierceChance(
            GeminiDemDifficulty.hardFluxFraction(empTarget.fluxTracker.hardFlux, empTarget.fluxTracker.maxFlux),
        )
        return chance > 0f && Misc.random.nextFloat() < chance
    }

    /**
     * 单道 EMP 伤害结算：面板 × EMP 总量难度倍率（玩家按我方档位系数/敌版轨一）× 单道占比 × 战机削弱。
     * [fighterPayload] 按 payload 武器 spec id 判定（数据驱动削弱链的脚本侧落点：
     * 光束伤害已由战机版 spec dps 折算，EMP 电弧是脚本显式数值，须在此乘算）。
     */
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

    /** 首伤帧一次性：目标过滤（hulk/战机记 DEBUG 跳过）+ 遥测 + 同步登记（共振触发状态定格给红色电弧门控）。 */
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

        // 登记后即读共振表：本次命中触发（recordHit 返回 true）或此前已由异种弹头触发均视为已共振
        state.syncResonated =
            GeminiDemSyncHandler.recordHit(engine, target, kind, beam.to, beam.source) ||
                    GeminiDemSyncHandler.wasResonated(engine, target)
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

        /** 遥测键：动能光束 EMP 电弧累计道数（≈ 动能命中轮数 × [GeminiDemDifficulty.EMP_ARC_COUNT]）。 */
        const val TELEMETRY_EMP_ARCS = "astd_gemini_dem_emp_arc_count"

        /** 遥测键：SIC「武备升级」穿盾电弧累计道数（含红/蓝两色）。 */
        const val TELEMETRY_PIERCE_ARCS = "astd_gemini_dem_emp_arc_pierce_count"

        /** 遥测键：动能/高爆 payload 光束首伤帧命中次数（同步配对观测面）。 */
        const val TELEMETRY_KINETIC_HIT = "astd_gemini_dem_kinetic_hit_count"
        const val TELEMETRY_HE_HIT = "astd_gemini_dem_he_hit_count"

        fun empArcCount(engine: CombatEngineAPI): Int = engine.customData[TELEMETRY_EMP_ARCS] as? Int ?: 0
        fun pierceArcCount(engine: CombatEngineAPI): Int = engine.customData[TELEMETRY_PIERCE_ARCS] as? Int ?: 0
        fun kineticHitCount(engine: CombatEngineAPI): Int = engine.customData[TELEMETRY_KINETIC_HIT] as? Int ?: 0
        fun heHitCount(engine: CombatEngineAPI): Int = engine.customData[TELEMETRY_HE_HIT] as? Int ?: 0
    }
}
