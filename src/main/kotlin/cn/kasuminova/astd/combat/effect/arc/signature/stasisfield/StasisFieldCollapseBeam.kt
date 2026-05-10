package cn.kasuminova.astd.combat.effect.arc.signature.stasisfield

import cn.kasuminova.astd.renderer.effect.projectile.beam.BeamLineUtil
import cn.kasuminova.astd.renderer.effect.projectile.beam.AttachedBeamEllipseRingRenderer
import cn.kasuminova.astd.renderer.effect.projectile.beam.AttachedBeamSpriteRingRenderer
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import cn.kasuminova.astd.combat.effect.generic.projectile.TaperedBeamTrailsVfx

import cn.kasuminova.astd.combat.effect.arc.signature.stasisfield.StasisFieldCollapseBeam.REQUEST_KEY


/**
 * 停滞场 -> 坍缩炮（终结光束）与“系统武器”之间的通信协议。
 *
 * - 系统脚本在结束时写入 [REQUEST_KEY]；
 * - 内置 beam 武器的 everyFrameEffect 读取并消费该请求，然后驱动充能/开火/VFX。
 */
internal object StasisFieldCollapseBeam {

    const val WEAPON_ID: String = "astd_stasis_collapse_emitter"

    /** Ship.customData key */
    const val REQUEST_KEY: String = "astd_stasis_field:collapse_request"

    data class Request(
        /** 坍缩炮发射朝向（角度制，0~360）。 */
        val aimFacing: Float,
        /** 强度 0~1（通常来自“捕获能量/阈值”的归一化）。 */
        val intensity: Float,
        /** 请求创建时间（engine.totalElapsedTime，用于去重/调试）。 */
        val createdAt: Float,
    )
}
