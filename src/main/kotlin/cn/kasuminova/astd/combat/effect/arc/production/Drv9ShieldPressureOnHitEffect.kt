package cn.kasuminova.astd.combat.effect.arc.production

import cn.kasuminova.astd.internal.debug.CombatCaps
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.OnHitEffectPlugin
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/**
 * DRV-9 高速磁轨驱动炮：连续命中护盾时施加少量盾压（每秒封顶）。
 */
class Drv9ShieldPressureOnHitEffect : OnHitEffectPlugin {

    companion object {
        /** 每秒最大盾压（封顶）*/
        private const val CAP_PER_SECOND = 180f

        /** 每次命中施加的额外盾压比例（相对于伤害）*/
        private const val PRESSURE_MULT = 0.12f

        /** 命中特效颜色 */
        private val FX_COLOR = Color(140, 210, 255, 180)
    }

    override fun onHit(
        projectile: DamagingProjectileAPI,
        target: CombatEntityAPI,
        point: Vector2f,
        shieldHit: Boolean,
        damageResult: ApplyDamageResultAPI,
        engine: CombatEngineAPI,
    ) {
        if (!shieldHit) return
        val ship = target as? ShipAPI ?: return
        if (ship.isHulk || ship.isPhased) return

        val dmg = projectile.damageAmount
        if (dmg <= 0f) return

        val desiredExtra = dmg * PRESSURE_MULT
        if (desiredExtra <= 0f) return

        // 每秒封顶：按"武器 id + 目标"做桶
        val weaponId = projectile.weapon?.spec?.weaponId ?: "drv9"
        val bucketKey = "drv9_pressure:$weaponId:${System.identityHashCode(ship)}"
        val applied = CombatCaps.applyPerSecondCap(engine, bucketKey, CAP_PER_SECOND, desiredExtra)
        if (applied <= 0f) return

        ship.fluxTracker.increaseFlux(applied, true)

        // 轻量命中特效
        engine.addHitParticle(point, Vector2f(), 28f, 0.9f, 0.14f, FX_COLOR)
    }
}
