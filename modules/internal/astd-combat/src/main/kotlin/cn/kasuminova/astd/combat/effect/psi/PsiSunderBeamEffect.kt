package cn.kasuminova.astd.combat.effect.psi

import cn.kasuminova.astd.impl.render.BeamHostImpl
import cn.kasuminova.astd.renderer.beam.driver.BeamFrame
import cn.kasuminova.astd.renderer.beam.driver.BeamVfxDriver
import cn.kasuminova.astd.renderer.beam.driver.BeamVfxDriverImpl
import cn.kasuminova.astd.renderer.beam.driver.BeamVfxSpecs
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BeamAPI
import com.fs.starfarer.api.combat.BeamEffectPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.VectorUtils
import org.lwjgl.util.vector.Vector2f
import java.util.WeakHashMap
import kotlin.math.max

/**
 * PSI-Ω「灵魂虹吸」：
 * - 基础伤害：weapon_data.csv 中定义（FRAG, 1000 DPS, 300 flux/s）。
 * - 额外效果：对目标持续照射会逐步增强“CR/PPT 抽取”。
 *   - 有人舰：每秒扣除当前 CR 的 0.2%~0.8%（随照射增强）。
 *   - 无人舰：每秒扣除最大 PPT 的 0.1%~0.4%（随照射增强）。
 *   - 命中护盾：上述抽取效率 ×0.2。
 *   - 回馈：抽取量的 50% 转化为自身 CR 恢复，并按舰体大小有折损（护卫 90% → 主力 30%）。
 *
 * 视觉：双螺旋束体 + 末端反向粒子流（随照射增强）。
 */
class PsiSunderBeamEffect : BeamEffectPlugin {

    companion object {
        private val log = Global.getLogger(PsiSunderBeamEffect::class.java)

        private const val RAMP_MIN = 5f
        private const val RAMP_MAX = 10f
        private const val DEFAULT_RAMP_DURATION = 7.5f
        private const val RAMP_DECAY_PER_SEC = 0.40f

        private const val BEAM_ACTIVE_BRIGHTNESS_MIN = 0.04f
        private const val DRAIN_BRIGHTNESS_MIN = 0.12f

        private const val SHIELD_MULT = 0.2f

        private const val CR_DRAIN_MIN = 0.002f
        private const val CR_DRAIN_MAX = 0.008f

        private const val PPT_DRAIN_MIN = 0.001f
        private const val PPT_DRAIN_MAX = 0.004f

        private const val RESTORE_FRACTION = 0.5f

        /** 光束 VFX 树的 spec id（登记于 [BeamVfxSpecs]）。 */
        private const val BEAM_SPEC_ID = "astd_psi_omega"

        private const val KEY_PPT_DRAIN = "astd_psi_omega_ppt_drain"
        private const val STAT_PPT_DRAIN = "astd_psi_omega_ppt_drain_stat"

        private const val KEY_PPT_RESTORE = "astd_psi_omega_ppt_restore"
        private const val STAT_PPT_RESTORE = "astd_psi_omega_ppt_restore_stat"

        // PPT 回馈上限：最多额外 +50% 基础峰值时长（避免无限堆叠）
        private const val PPT_RESTORE_CAP_FRACTION = 0.5f

        private val states: WeakHashMap<BeamAPI, BeamState> = WeakHashMap()

        private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

        private data class BeamState(
            var target: ShipAPI? = null,
            var contact: Float = 0f,
            var rampDuration: Float = DEFAULT_RAMP_DURATION,
            var baseWidth: Float = -1f,
            var driver: BeamVfxDriver? = null,

            // 反馈可视化/调试：累积后以“每秒速率”显示，避免 0.x% 变化被 UI 取整吞掉
            var statusT: Float = 0f,
            var statusDrain: Float = 0f,
            var statusRestoreApplied: Float = 0f,
            var statusPptAppliedSec: Float = 0f,
            var lastStatusLine: String = "",
            var logT: Float = 0f,
        )
    }

    override fun advance(amount: Float, engine: CombatEngineAPI, beam: BeamAPI) {
        if (engine.isPaused) return

        val source = beam.source ?: return
        val state = states.getOrPut(beam) { BeamState() }

        if (state.baseWidth <= 0f) {
            state.baseWidth = beam.width
        }

        val brightness = try { beam.brightness } catch (_: Throwable) { 0f }
        val beamActive = brightness > BEAM_ACTIVE_BRIGHTNESS_MIN && beam.length > 10f

        if (!beamActive) {
            // 停火/束体消失：缓慢衰减层数；驱动以 firing=false 推进，束体节点按心跳自淡（复火再拉回，无重建）。
            state.contact = max(0f, state.contact - amount * RAMP_DECAY_PER_SEC)
            state.target = null
            val ramp = (state.contact / state.rampDuration.coerceAtLeast(0.01f)).coerceIn(0f, 1f)
            driveVfx(engine, beam, state, amount, firing = false, strength = ramp)
            return
        }

        val rawTarget = beam.damageTarget
        val target = rawTarget as? ShipAPI
        val canDrain = target != null
            && engine.isEntityInPlay(target)
            && !target.isHulk
            && target.owner != source.owner
            && brightness > DRAIN_BRIGHTNESS_MIN

        if (canDrain) {
            // 新目标：重置 ramp，并重新 roll 持续时间（5~10s）
            if (state.target !== target) {
                state.target = target
                state.contact = 0f
                state.rampDuration = MathUtils.getRandomNumberInRange(RAMP_MIN, RAMP_MAX)
            }
            state.contact += amount
        } else {
            // 未命中舰船或命中非舰船：保留光束 VFX，但缓慢衰减汲取效率。
            state.contact = max(0f, state.contact - amount * RAMP_DECAY_PER_SEC)
            state.target = null
        }

        val ramp = (state.contact / state.rampDuration.coerceAtLeast(0.01f)).coerceIn(0f, 1f)

        var shieldHit = false
        var drained = 0f
        var appliedCr = 0f
        var appliedPptSec = 0f

        if (canDrain) {
            val drainTarget = target ?: return
            shieldHit = isShieldHit(drainTarget, beam.to)
            val effMult = if (shieldHit) SHIELD_MULT else 1f

            drained = if (isUnmanned(drainTarget)) {
                drainPpt(drainTarget, ramp, effMult, amount)
            } else {
                drainCr(drainTarget, ramp, effMult, amount)
            }

            // 回馈
            val restored = restoreSource(source, drainTarget, drained, ramp)
            appliedCr = restored.first
            appliedPptSec = restored.second
        }

        // 玩家可见状态（仅玩家船）：提示“回馈”正在工作或 CR 已满
        if (engine.playerShip === source) {
            if (canDrain) {
                state.statusT += amount
                state.statusDrain += drained
                state.statusRestoreApplied += appliedCr
                state.statusPptAppliedSec += appliedPptSec
                if (state.statusT >= 0.33f) {
                    val rateDrain = (state.statusDrain / state.statusT) * 100f
                    val rateRestore = (state.statusRestoreApplied / state.statusT) * 100f
                    val ratePpt = (state.statusPptAppliedSec / state.statusT)
                    val cur = try { source.currentCR } catch (_: Throwable) { 0f }
                    val maxCr = try { source.mutableStats.maxCombatReadiness.modifiedValue } catch (_: Throwable) { 1f }
                    val cap = max(cur, maxCr).coerceIn(0f, 1f)
                    val capped = cur >= cap - 1e-4f
                    state.lastStatusLine = if (capped) {
                        String.format("回馈 %.3f%%/s（CR受限） | PPT +%.2fs/s", rateRestore, ratePpt)
                    } else {
                        String.format("回馈 %.3f%%/s | PPT +%.2fs/s | 抽取 %.3f%%/s", rateRestore, ratePpt, rateDrain)
                    }
                    state.statusT = 0f
                    state.statusDrain = 0f
                    state.statusRestoreApplied = 0f
                    state.statusPptAppliedSec = 0f
                }
            } else {
                state.statusT = 0f
                state.statusDrain = 0f
                state.statusRestoreApplied = 0f
                state.statusPptAppliedSec = 0f
                state.lastStatusLine = "未锁定舰船目标（仅维持束体）"
            }
            engine.maintainStatusForPlayerShip(
                this,
                null,
                "PSI-Ω 灵魂虹吸",
                state.lastStatusLine,
                false,
            )

            // 可选日志：仅 devMode，每 1s 输出一次
            if (canDrain && Global.getSettings().isDevMode()) {
                state.logT += amount
                if (state.logT >= 1f) {
                    state.logT = 0f
                    try {
                        log.info(
                            "PSI-Ω feedback: appliedCR=%.6f, appliedPPT=%.3fs, drained=%.6f, ramp=%.3f, shield=%s".format(
                                appliedCr,
                                appliedPptSec,
                                drained,
                                ramp,
                                shieldHit,
                            )
                        )
                    } catch (_: Throwable) {
                    }
                }
            }
        }

        // 视觉：全 BoxUtil 光束（不依赖原版渲染），经 RenderEntity 新管线驱动。
        driveVfx(engine, beam, state, amount, firing = true, strength = ramp)
    }

    /**
     * 把本帧束几何/状态折成 [BeamFrame] 喂给该光束的 [BeamVfxDriver]（首帧惰性建树+驱动）。
     * strength=ramp 决定束体颜色/宽度与命中特效频次；firing 决定束体常驻还是淡出。
     */
    private fun driveVfx(engine: CombatEngineAPI, beam: BeamAPI, state: BeamState, amount: Float, firing: Boolean, strength: Float) {
        val driver = state.driver ?: run {
            val tree = BeamVfxSpecs.build(BEAM_SPEC_ID) ?: return
            BeamVfxDriverImpl(BeamHostImpl("beam@" + System.identityHashCode(beam), state.baseWidth), tree)
                .also { state.driver = it }
        }
        driver.advance(
            engine,
            BeamFrame(
                start = beam.from,
                facing = VectorUtils.getAngle(beam.from, beam.to),
                length = beam.length,
                endpoint = Vector2f(beam.to),
                firing = firing,
                strength = strength,
            ),
            amount,
        )
    }

    private fun isUnmanned(target: ShipAPI): Boolean {
        val drone = try { target.isDrone } catch (_: Throwable) { false }
        if (drone) return true
        val minCrew: Float = try { target.hullSpec.minCrew } catch (_: Throwable) { 1f }
        return minCrew <= 0f
    }

    private fun isShieldHit(target: ShipAPI, point: Vector2f): Boolean {
        val shield = try { target.shield } catch (_: Throwable) { null } ?: return false
        if (!shield.isOn) return false
        val dx = point.x - shield.location.x
        val dy = point.y - shield.location.y
        val rr = shield.radius + 20f
        if (dx * dx + dy * dy > rr * rr) return false
        return try { shield.isWithinArc(point) } catch (_: Throwable) { true }
    }

    /**
     * @return drained amount in CR fraction (0..1) for restore calculation
     */
    private fun drainCr(target: ShipAPI, ramp: Float, mult: Float, amount: Float): Float {
        val cur = try { target.currentCR } catch (_: Throwable) { return 0f }
        if (cur <= 0.001f) return 0f

        val rate = lerp(CR_DRAIN_MIN, CR_DRAIN_MAX, ramp) * mult
        val delta = cur * rate * amount

        try {
            target.currentCR = (cur - delta).coerceAtLeast(0f)
        } catch (_: Throwable) {
        }
        return delta
    }

    /**
     * @return drained amount as PPT fraction (0..1) for restore calculation
     */
    private fun drainPpt(target: ShipAPI, ramp: Float, mult: Float, amount: Float): Float {
        val specBase = try { target.hullSpec.noCRLossTime } catch (_: Throwable) { 0f }
        if (specBase <= 0.01f) {
            // fallback：没有 PPT 概念的目标，按 CR 处理
            return drainCr(target, ramp, mult, amount)
        }

        val drained0 = try {
            target.customData[KEY_PPT_DRAIN] as? Float
        } catch (_: Throwable) {
            null
        } ?: 0f

        // 当前 effective 已包含我们的负 flat；因此 + drained0 作为“未抽取前”的最大 PPT 近似。
        val maxNoDrain = try {
            target.mutableStats.peakCRDuration.computeEffective(specBase) + drained0
        } catch (_: Throwable) {
            specBase + drained0
        }.coerceAtLeast(1f)

        val rate = lerp(PPT_DRAIN_MIN, PPT_DRAIN_MAX, ramp) * mult
        val dppt = maxNoDrain * rate * amount

        val drained = (drained0 + dppt).coerceAtMost(maxNoDrain - 1f)

        try {
            target.customData[KEY_PPT_DRAIN] = drained
        } catch (_: Throwable) {
        }

        try {
            // 通过降低“峰值持续时间 stat”来逼迫 peak time 剪短。
            target.mutableStats.peakCRDuration.modifyFlat(STAT_PPT_DRAIN, -drained)
        } catch (_: Throwable) {
        }

        return (dppt / maxNoDrain).coerceIn(0f, 1f)
    }

    private fun restoreSource(source: ShipAPI, target: ShipAPI, drained: Float, ramp: Float): Pair<Float, Float> {
        if (drained <= 0f) return 0f to 0f

        val eff = when (try { target.hullSize } catch (_: Throwable) { ShipAPI.HullSize.DEFAULT }) {
            ShipAPI.HullSize.FRIGATE -> 0.9f
            ShipAPI.HullSize.DESTROYER -> 0.7f
            ShipAPI.HullSize.CRUISER -> 0.5f
            ShipAPI.HullSize.CAPITAL_SHIP -> 0.3f
            else -> 0.6f
        }

        // ramp 越高，回馈越“尖锐”；但上限仍为 50%
        val restore = drained * RESTORE_FRACTION * eff * (0.85f + 0.15f * ramp)

        // PPT 回馈：把“回馈份额”按 source 的有效峰值时长换算为秒并累积到 peakCRDuration。
        val appliedPptSec = restorePpt(source, restore)

        try {
            val cur = source.currentCR
            val maxCr = source.mutableStats.maxCombatReadiness.modifiedValue

            // 关键修复：某些情况下 maxCombatReadiness 的 modifiedValue 可能瞬间低于当前 CR。
            // 若直接 min(maxCr, ...) 会把当前 CR 硬夹到更低值，表现为 85% -> 15% 的“异常掉 CR”。
            // 这里保证“回馈”只会提高 CR（或保持不变），绝不降低。
            val cap = max(cur, maxCr).coerceIn(0f, 1f)
            val next = (cur + restore).coerceIn(0f, cap)
            source.currentCR = next
            return (next - cur).coerceAtLeast(0f) to appliedPptSec
        } catch (_: Throwable) {
        }

        return 0f to appliedPptSec
    }

    private fun restorePpt(source: ShipAPI, restoreCrFraction: Float): Float {
        if (restoreCrFraction <= 0f) return 0f

        val base = try { source.hullSpec.noCRLossTime } catch (_: Throwable) { 0f }
        if (base <= 0.01f) return 0f

        val curBonus = try {
            source.customData[KEY_PPT_RESTORE] as? Float
        } catch (_: Throwable) {
            null
        } ?: 0f

        val effectiveNow = try {
            source.mutableStats.peakCRDuration.computeEffective(base)
        } catch (_: Throwable) {
            base
        }.coerceAtLeast(1f)

        // computeEffective 包含我们自己的 flat；扣回后得到“无本机制时”的有效峰值
        val effectiveNoOur = (effectiveNow - curBonus).coerceAtLeast(1f)
        val capBonus = (effectiveNoOur * PPT_RESTORE_CAP_FRACTION).coerceAtLeast(0f)

        // 将“CR 分数回馈”映射为“PPT 秒回馈”
        val addSec = (restoreCrFraction * effectiveNoOur).coerceAtLeast(0f)

        val nextBonus = (curBonus + addSec).coerceIn(0f, capBonus)
        val applied = (nextBonus - curBonus).coerceAtLeast(0f)
        if (applied <= 0f) return 0f

        try { source.customData[KEY_PPT_RESTORE] = nextBonus } catch (_: Throwable) {}
        try { source.mutableStats.peakCRDuration.modifyFlat(STAT_PPT_RESTORE, nextBonus) } catch (_: Throwable) {}

        return applied
    }

}
