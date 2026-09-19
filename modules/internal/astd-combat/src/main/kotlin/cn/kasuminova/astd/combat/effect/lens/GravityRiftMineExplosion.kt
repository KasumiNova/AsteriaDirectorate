package cn.kasuminova.astd.combat.effect.lens

import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.ProximityExplosionEffect
import com.fs.starfarer.api.impl.combat.RiftCascadeMineExplosion

/**
 * 引力裂隙发生器：裂隙（PHASE_MINE）的近炸爆炸特效（purple/20-production.md §2）。
 *
 * 复刻原版 [RiftCascadeMineExplosion] 的结算，唯一差异是配色取本模组布雷器
 * （astd_grav_rift_minelayer）弹体 glowColor 的红色——[RiftCascadeMineExplosion.createStandardRiftParams]
 * 的颜色来源即该 glowColor，因此裂隙视觉（NegativeExplosionVisual）自动呈现红色正色。
 * 尺寸/伤害梯度由系统脚本生成地雷时写入的 sizeMult（customData）驱动，与原版同一约定。
 */
class GravityRiftMineExplosion : ProximityExplosionEffect {

    override fun onExplosion(explosion: DamagingProjectileAPI, originalProjectile: DamagingProjectileAPI) {
        val sizeMult = originalProjectile.customData
            ?.get(RiftCascadeMineExplosion.SIZE_MULT_KEY) as? Float ?: 1f
        val params = RiftCascadeMineExplosion.createStandardRiftParams(MINELAYER_ID, BASE_RADIUS * sizeMult)
        params.fadeOut = FADE_OUT_SECONDS
        RiftCascadeMineExplosion.spawnStandardRift(explosion, params)
    }

    companion object {
        private const val MINELAYER_ID = "astd_grav_rift_minelayer"

        /** 裂隙视觉基准半径（对齐原版 25 × sizeMult）。 */
        private const val BASE_RADIUS = 25f

        /** 裂隙淡出时长（秒，对齐原版 1.0）。 */
        private const val FADE_OUT_SECONDS = 1.0f
    }
}
