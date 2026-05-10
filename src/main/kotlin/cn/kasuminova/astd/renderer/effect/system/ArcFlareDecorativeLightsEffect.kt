package cn.kasuminova.astd.renderer.effect.system

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin
import com.fs.starfarer.api.combat.WeaponAPI
import java.awt.Color

/**
 * Arc Flare 静态装饰灯层控制器。
 *
 * 设计目标：
 * - 装配界面保留 decorative emissive 的静态展示
 * - 战斗中直接驱动 decorative emissive 的亮度，保证整船发光与舰体严格对齐
 */
class ArcFlareDecorativeLightsEffect : EveryFrameWeaponEffectPlugin {

    companion object {
        private const val BLOOM_WEAPON_ID = "astd_arc_flare_lights_bloom"
    }

    private var baseColor: Color? = null

    override fun advance(amount: Float, engine: CombatEngineAPI, weapon: WeaponAPI) {
        if (engine.isPaused) return

        val ship = weapon.ship ?: return
        val sprite = try {
            weapon.sprite
        } catch (_: Throwable) {
            null
        } ?: return
        val animation = try {
            weapon.animation
        } catch (_: Throwable) {
            null
        }

        if (baseColor == null) {
            baseColor = try {
                Color(sprite.color.red, sprite.color.green, sprite.color.blue, 255)
            } catch (_: Throwable) {
                Color.WHITE
            }
        }

        val weaponId = try {
            weapon.spec?.weaponId
        } catch (_: Throwable) {
            null
        }
        val alpha = if (ship.isHulk || ship.isPiece) 0f else 1.0f

        try {
            animation?.setAlphaMult(alpha)
        } catch (_: Throwable) {
        }

        try {
            sprite.setAdditiveBlend()
            val c = baseColor ?: Color.WHITE
            val color = if (weaponId == BLOOM_WEAPON_ID) {
                // 始终使用冷态蓝色，不随战术系统过载状态变色。
                val from = ArcFlareOverdriveVisualState.lerpColor(
                    ArcFlareOverdriveVisualState.coldFringe,
                    Color(255, 236, 228, 255),
                    0.22f,
                    255,
                )
                Color(from.red, from.green, from.blue, 255)
            } else {
                val c = baseColor ?: Color.WHITE
                Color(c.red, c.green, c.blue, 255)
            }
            sprite.color = color
        } catch (_: Throwable) {
        }
    }
}