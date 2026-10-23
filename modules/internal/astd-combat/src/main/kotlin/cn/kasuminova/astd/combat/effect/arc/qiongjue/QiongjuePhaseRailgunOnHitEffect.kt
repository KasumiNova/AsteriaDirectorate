package cn.kasuminova.astd.combat.effect.arc.qiongjue

import cn.kasuminova.astd.api.buff.buffHost
import cn.kasuminova.astd.api.combat.CombatFeedback
import cn.kasuminova.astd.combat.effect.base.SicWeaponUpgradeFlags
import cn.kasuminova.astd.impl.combat.CombatFeedbackImpl
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.impl.render.ConeImpactVfx
import cn.kasuminova.astd.impl.render.ConeImpactVfxSpec
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.OnHitEffectPlugin
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI
import com.fs.starfarer.api.util.Misc
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

class QiongjuePhaseRailgunOnHitEffect : OnHitEffectPlugin {

    override fun onHit(
        projectile: DamagingProjectileAPI,
        target: CombatEntityAPI,
        point: Vector2f?,
        shieldHit: Boolean,
        damageResult: ApplyDamageResultAPI,
        engine: CombatEngineAPI,
    ) {
        if (engine.isPaused) return

        val targetShip = target as? ShipAPI ?: return
        if (targetShip.isFighter || targetShip.isHulk || targetShip.isPhased) return

        val ship = projectile.source ?: projectile.weapon?.ship
        if (ship == null) {
            log.info("[ASTD] 穷距命中放弃结算：弹体无来源（spec=${projectile.projectileSpecId}）")
            return
        }

        // 命中回调的 point 可能为 null，回退到弹体当前位置。
        val hitPoint = point ?: projectile.location ?: return

        QiongjueDamageDealtModifier.ensure(ship)
        val host = ship.buffHost()
        val buff = host.find(QiongjueCalcStacks.BUFF_ID) as? QiongjueCalcStacks
            ?: QiongjueCalcStacks(ship, engine, host).also { host.register(it) }
        val retainPct = QiongjueStackMath.resolve(DifficultyTuningImpl, QiongjuePhaseRailgunDifficulty.SWITCH_RETAIN, ship.owner)
        val oldTargetValid = buff.isTargetAlive()
        val sameTarget = oldTargetValid && buff.target === targetShip
        val stacksAfter = QiongjueStackMath.stacksAfterHit(
            buff.stacks, oldTargetValid, sameTarget, retainPct,
            maxStacks = QiongjueStackMath.maxStacks(SicWeaponUpgradeFlags.isArmamentUpgradeActive(ship)),
        )
        buff.target = targetShip
        buff.addStacks(stacksAfter - buff.stacks)
        buff.lastHitTime = engine.getTotalElapsedTime(false)

        val isPlayerSource = ship === engine.playerShip
        if (oldTargetValid && !sameTarget) {
            if (isPlayerSource) {
                feedback.floatingText(
                    engine, hitPoint,
                    I18n[I18n.Categories.MOD, "ui.qiongjue.float.transfer"],
                    FLOAT_SIZE, FLOAT_COLOR, targetShip, 0f, FLOAT_FLASH_DURATION,
                )
            }
            bumpTelemetry(engine, if (isPlayerSource) TELEMETRY_TRANSFER_PLAYER else TELEMETRY_TRANSFER_OTHER)
        }

        ConeImpactVfx.spawn(
            engine,
            ConeImpactVfxSpec(
                origin = Vector2f(hitPoint),
                facingDeg = Misc.getAngleInDegrees(ZERO, projectile.velocity),
                halfAngleDeg = CONE_HALF_ANGLE,
                length = CONE_LENGTH,
                coreColor = CONE_CORE_COLOR,
                fringeColor = CONE_FRINGE_COLOR,
            ),
        )
        bumpTelemetry(engine, TELEMETRY_CONE_VFX)
        bumpTelemetry(engine, if (isPlayerSource) TELEMETRY_HIT_PLAYER else TELEMETRY_HIT_OTHER)
    }

    private fun bumpTelemetry(engine: CombatEngineAPI, key: String) {
        engine.customData[key] = (engine.customData[key] as? Int ?: 0) + 1
    }

    companion object {
        const val TELEMETRY_HIT_PLAYER = "astd_qiongjue_hit_player"

        const val TELEMETRY_HIT_OTHER = "astd_qiongjue_hit_other"

        const val TELEMETRY_TRANSFER_PLAYER = "astd_qiongjue_transfer_player"

        const val TELEMETRY_TRANSFER_OTHER = "astd_qiongjue_transfer_other"

        const val TELEMETRY_CONE_VFX = "astd_qiongjue_cone_vfx"

        fun telemetryCount(engine: CombatEngineAPI, key: String): Int = engine.customData[key] as? Int ?: 0

        private const val CONE_HALF_ANGLE = 12f
        private const val CONE_LENGTH = 90f

        private val CONE_CORE_COLOR = Color(235, 242, 252)
        private val CONE_FRINGE_COLOR = Color(160, 200, 255)

        private const val FLOAT_SIZE = 16f
        private const val FLOAT_FLASH_DURATION = 0.6f
        private val FLOAT_COLOR = Color(240, 245, 252)

        private val ZERO = Vector2f(0f, 0f)

        private val feedback: CombatFeedback = CombatFeedbackImpl

        private val log = Global.getLogger(QiongjuePhaseRailgunOnHitEffect::class.java)
    }
}
