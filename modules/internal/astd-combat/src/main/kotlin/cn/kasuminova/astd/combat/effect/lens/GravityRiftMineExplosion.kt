package cn.kasuminova.astd.combat.effect.lens

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.ProximityExplosionEffect
import com.fs.starfarer.api.impl.combat.RiftCascadeMineExplosion
import com.fs.starfarer.api.util.Misc
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/**
 * 引力裂隙发生器：裂隙（PHASE_MINE）的近炸爆炸特效
 * （purple/20-production.md §2，2026-09-20 二轮重做：正色视觉）。
 *
 * 伤害结算仍走布雷器弹体 behaviorSpec 的 explosionSpec（半径 100 / 核心 50），
 * 本类只负责视觉——**不再使用原版 NegativeExplosionVisual 反色裂隙**（黑中心 + 反色星云），
 * 改为正色三段：
 * 1. 中心闪光：极淡红（非常淡）的 spawnExplosion 闪光（替代原版黑色裂心）；
 * 2. 红色→极淡红星云粒子：沿爆心周边散布的内吸 swirly nebula（替代反色星云）；
 * 3. 命中小粒子：少量 hit particle 提亮爆发瞬间。
 *
 * 尺寸梯度由系统光束脚本生成地雷时写入的 sizeMult（[RiftCascadeMineExplosion.SIZE_MULT_KEY]）
 * 驱动，与原版同一约定。
 */
class GravityRiftMineExplosion : ProximityExplosionEffect {

    override fun onExplosion(explosion: DamagingProjectileAPI, originalProjectile: DamagingProjectileAPI) {
        val engine = Global.getCombatEngine()
        val sizeMult = originalProjectile.customData
            ?.get(RiftCascadeMineExplosion.SIZE_MULT_KEY) as? Float ?: 1f
        explosion.addDamagedAlready(explosion.source)

        val center = Vector2f(explosion.location)
        val radius = BASE_RADIUS * sizeMult

        // 中心闪光：极淡红（非常淡），尺寸随 sizeMult 缩放
        engine.spawnExplosion(center, ZERO, CENTER_COLOR, radius * FLASH_RADIUS_MULT, FLASH_DURATION)

        // 星云粒子：红色至深红为主、间杂极淡红，向爆心内吸形成裂隙坍缩观感
        for (i in 0 until NEBULA_PARTICLES) {
            val loc = Misc.getPointAtRadius(center, radius * NEBULA_SPAWN_RADIUS_FRAC)
            val life = NEBULA_LIFE_MIN + (NEBULA_LIFE_MAX - NEBULA_LIFE_MIN) * Math.random().toFloat()
            val inward = Misc.getUnitVectorAtDegreeAngle(Misc.getAngleInDegrees(loc, center))
            inward.scale(radius * NEBULA_SPAWN_RADIUS_FRAC / life * 0.5f)
            val color = if (i % PALE_EVERY == 0) NEBULA_PALE_RED else NEBULA_RED
            engine.addSwirlyNebulaParticle(
                loc, inward, radius * NEBULA_SIZE_FRAC * (0.75f + 0.5f * Math.random().toFloat()),
                0.4f, 0.15f, 0.6f, life, color, false,
            )
        }

        // 提亮粒子：爆发瞬间的红色小碎光
        for (i in 0 until HIT_PARTICLES) {
            val loc = Misc.getPointAtRadius(center, radius * 0.5f)
            engine.addHitParticle(
                loc, ZERO, HIT_PARTICLE_SIZE * (0.6f + 0.8f * Math.random().toFloat()),
                1f, HIT_PARTICLE_DURATION * (0.7f + 0.6f * Math.random().toFloat()), HIT_COLOR,
            )
        }
    }

    companion object {
        /** 裂隙视觉基准半径（对齐原版 25 × sizeMult）。 */
        private const val BASE_RADIUS = 25f

        /** 中心闪光：尺寸倍率与时長（秒）。 */
        private const val FLASH_RADIUS_MULT = 2.4f
        private const val FLASH_DURATION = 0.35f

        /** 星云粒子：数量、散布半径比例、尺寸比例、寿命区间（秒）、淡色粒子间隔。 */
        private const val NEBULA_PARTICLES = 7
        private const val NEBULA_SPAWN_RADIUS_FRAC = 1.6f
        private const val NEBULA_SIZE_FRAC = 2.2f
        private const val NEBULA_LIFE_MIN = 0.7f
        private const val NEBULA_LIFE_MAX = 1.1f
        private const val PALE_EVERY = 3

        /** 提亮粒子：数量、基准尺寸（su）、基准时长（秒）。 */
        private const val HIT_PARTICLES = 5
        private const val HIT_PARTICLE_SIZE = 24f
        private const val HIT_PARTICLE_DURATION = 0.3f

        /** 中心闪光色：极淡红（非常淡）——替代原版反色裂心的黑色。 */
        private val CENTER_COLOR = Color(255, 214, 214, 110)

        /** 星云色：红色正色 → 极淡红（非常淡）。 */
        private val NEBULA_RED = Color(255, 70, 70, 150)
        private val NEBULA_PALE_RED = Color(255, 190, 190, 70)
        private val HIT_COLOR = Color(255, 120, 110, 200)

        private val ZERO = Vector2f(0f, 0f)
    }
}
