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

        private const val BEAM_SPEC_ID = "astd_psi_omega"

        private const val KEY_PPT_DRAIN = "astd_psi_omega_ppt_drain"
        private const val STAT_PPT_DRAIN = "astd_psi_omega_ppt_drain_stat"

        private const val KEY_PPT_RESTORE = "astd_psi_omega_ppt_restore"
        private const val STAT_PPT_RESTORE = "astd_psi_omega_ppt_restore_stat"

        private const val PPT_RESTORE_CAP_FRACTION = 0.5f

        private val states: WeakHashMap<BeamAPI, BeamState> = WeakHashMap()

        private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

        private data class BeamState(
            var target: ShipAPI? = null,
            var contact: Float = 0f,
            var rampDuration: Float = DEFAULT_RAMP_DURATION,
            var baseWidth: Float = -1f,
            var driver: BeamVfxDriver? = null,

            // 状态行按每秒速率显示：0.x% 的逐帧变化会被 UI 取整舍去。
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

        val brightness = try {
            beam.brightness
        } catch (_: Throwable) {
            0f
        }
        val beamActive = brightness > BEAM_ACTIVE_BRIGHTNESS_MIN && beam.length > 10f

        if (!beamActive) {
            // 停火时驱动以 firing=false 推进，束体节点按心跳自淡，复火拉回无需重建。
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
            if (state.target !== target) {
                state.target = target
                state.contact = 0f
                state.rampDuration = MathUtils.getRandomNumberInRange(RAMP_MIN, RAMP_MAX)
            }
            state.contact += amount
        } else {
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

            val restored = restoreSource(source, drainTarget, drained, ramp)
            appliedCr = restored.first
            appliedPptSec = restored.second
        }

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
                    val cur = try {
                        source.currentCR
                    } catch (_: Throwable) {
                        0f
                    }
                    val maxCr = try {
                        source.mutableStats.maxCombatReadiness.modifiedValue
                    } catch (_: Throwable) {
                        1f
                    }
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

            if (canDrain && Global.getSettings().isDevMode) {
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

        driveVfx(engine, beam, state, amount, firing = true, strength = ramp)
    }

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
        val drone = try {
            target.isDrone
        } catch (_: Throwable) {
            false
        }
        if (drone) return true
        val minCrew: Float = try {
            target.hullSpec.minCrew
        } catch (_: Throwable) {
            1f
        }
        return minCrew <= 0f
    }

    private fun isShieldHit(target: ShipAPI, point: Vector2f): Boolean {
        val shield = try {
            target.shield
        } catch (_: Throwable) {
            null
        } ?: return false
        if (!shield.isOn) return false
        val dx = point.x - shield.location.x
        val dy = point.y - shield.location.y
        val rr = shield.radius + 20f
        if (dx * dx + dy * dy > rr * rr) return false
        return try {
            shield.isWithinArc(point)
        } catch (_: Throwable) {
            true
        }
    }

    private fun drainCr(target: ShipAPI, ramp: Float, mult: Float, amount: Float): Float {
        val cur = try {
            target.currentCR
        } catch (_: Throwable) {
            return 0f
        }
        if (cur <= 0.001f) return 0f

        val rate = lerp(CR_DRAIN_MIN, CR_DRAIN_MAX, ramp) * mult
        val delta = cur * rate * amount

        try {
            target.currentCR = (cur - delta).coerceAtLeast(0f)
        } catch (_: Throwable) {
        }
        return delta
    }

    private fun drainPpt(target: ShipAPI, ramp: Float, mult: Float, amount: Float): Float {
        val specBase = try {
            target.hullSpec.noCRLossTime
        } catch (_: Throwable) {
            0f
        }
        if (specBase <= 0.01f) {
            // 无 PPT 概念的目标改按 CR 抽取处理。
            return drainCr(target, ramp, mult, amount)
        }

        val drained0 = try {
            target.customData[KEY_PPT_DRAIN] as? Float
        } catch (_: Throwable) {
            null
        } ?: 0f

        // computeEffective 已包含本机制的负 flat，加回 drained0 才是抽取前的最大 PPT。
        val maxNoDrain = try {
            target.mutableStats.peakCRDuration.computeEffective(specBase) + drained0
        } catch (_: Throwable) {
            specBase + drained0
        }.coerceAtLeast(1f)

        val rate = lerp(PPT_DRAIN_MIN, PPT_DRAIN_MAX, ramp) * mult
        val dppt = maxNoDrain * rate * amount

        val drained = (drained0 + dppt).coerceAtMost(maxNoDrain - 1f)

        try {
            target.setCustomData(KEY_PPT_DRAIN, drained)
        } catch (_: Throwable) {
        }

        try {
            target.mutableStats.peakCRDuration.modifyFlat(STAT_PPT_DRAIN, -drained)
        } catch (_: Throwable) {
        }

        return (dppt / maxNoDrain).coerceIn(0f, 1f)
    }

    private fun restoreSource(source: ShipAPI, target: ShipAPI, drained: Float, ramp: Float): Pair<Float, Float> {
        if (drained <= 0f) return 0f to 0f

        val eff = when (try {
            target.hullSize
        } catch (_: Throwable) {
            ShipAPI.HullSize.DEFAULT
        }) {
            ShipAPI.HullSize.FRIGATE -> 0.9f
            ShipAPI.HullSize.DESTROYER -> 0.7f
            ShipAPI.HullSize.CRUISER -> 0.5f
            ShipAPI.HullSize.CAPITAL_SHIP -> 0.3f
            else -> 0.6f
        }

        val restore = drained * RESTORE_FRACTION * eff * (0.85f + 0.15f * ramp)

        val appliedPptSec = restorePpt(source, restore)

        try {
            val cur = source.currentCR
            val maxCr = source.mutableStats.maxCombatReadiness.modifiedValue

            // maxCombatReadiness 的 modifiedValue 可能瞬间低于当前 CR，直接 min 会把当前 CR 硬夹低；
            // 回馈只许提高 CR 或保持不变。
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

        val base = try {
            source.hullSpec.noCRLossTime
        } catch (_: Throwable) {
            0f
        }
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

        // computeEffective 含本机制 flat，扣回 curBonus 才是无本机制时的有效峰值。
        val effectiveNoOur = (effectiveNow - curBonus).coerceAtLeast(1f)
        val capBonus = (effectiveNoOur * PPT_RESTORE_CAP_FRACTION).coerceAtLeast(0f)

        val addSec = (restoreCrFraction * effectiveNoOur).coerceAtLeast(0f)

        val nextBonus = (curBonus + addSec).coerceIn(0f, capBonus)
        val applied = (nextBonus - curBonus).coerceAtLeast(0f)
        if (applied <= 0f) return 0f

        try {
            source.customData[KEY_PPT_RESTORE] = nextBonus
        } catch (_: Throwable) {
        }
        try {
            source.mutableStats.peakCRDuration.modifyFlat(STAT_PPT_RESTORE, nextBonus)
        } catch (_: Throwable) {
        }

        return applied
    }

}
