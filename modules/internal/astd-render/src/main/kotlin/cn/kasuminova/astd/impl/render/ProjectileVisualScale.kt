package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.ProjectileHost
import cn.kasuminova.astd.api.render.RenderHost
import com.fs.starfarer.api.combat.DamagingProjectileAPI

/**
 * 弹体逐实例视觉缩放的 customData 键。调用方在 `ProjectileVfxDriverPlugin.track` 之前写入，
 * 值为正有限 [Number]，弹头、光斑、碎片与拖尾按同一值缩放。
 */
const val PROJECTILE_VISUAL_SCALE_KEY: String = "astd_projectile_visual_scale"

/**
 * 弹体拖尾折算用的基准速度 customData 键。调用方在 `ProjectileVfxDriverPlugin.track` 之前写入
 * 主炮面板（含加成）速度，避免拖尾寿命按隐藏载体 spec 的基准速度折算。缺省时取弹体自身基准速度。
 */
const val PROJECTILE_BASE_SPEED_KEY: String = "astd_projectile_base_speed"

internal object ProjectileVisualScale {

    /**
     * 读取宿主弹体的视觉缩放；宿主不是弹体或未写入该键时返回 null，表示按默认尺寸渲染。
     * 写入非数值或非正有限值时抛出异常，属于调用方编码错误。
     */
    fun read(host: RenderHost): Float? {
        val projectile = (host as? ProjectileHost)?.projectile ?: return null
        val raw = projectile.customData[PROJECTILE_VISUAL_SCALE_KEY] ?: return null
        return positiveValueOf(raw, projectile.projectileSpecId, PROJECTILE_VISUAL_SCALE_KEY)
    }

    /**
     * 读取拖尾折算用的基准速度；未写入该键时取弹体自身基准速度，即 spec 速度而非实例实际速度。
     */
    fun baseSpeed(projectile: DamagingProjectileAPI): Float {
        val raw = projectile.customData[PROJECTILE_BASE_SPEED_KEY] ?: return projectile.moveSpeed
        return positiveValueOf(raw, projectile.projectileSpecId, PROJECTILE_BASE_SPEED_KEY)
    }

    private fun positiveValueOf(raw: Any, projectileSpecId: String?, key: String): Float {
        require(raw is Number) {
            "$key 必须是 Number：projectile=$projectileSpecId，实际类型=${raw::class.java.name}"
        }
        val value = raw.toFloat()
        require(value.isFinite() && value > 0f) {
            "$key 必须是正有限值：projectile=$projectileSpecId，实际值=$value"
        }
        return value
    }
}
