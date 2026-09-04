package cn.kasuminova.astd.renderer.effect.system

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import java.awt.Color
import kotlin.math.roundToInt

internal object ArcFlareOverdriveVisualState {

    private const val VISUAL_LEVEL_KEY_PREFIX = "astd_tactical_overdrive_visual_level:"

    val coldCore: Color = Color(122, 232, 255)
    val coldFringe: Color = Color(160, 242, 255)
    val hotCore: Color = Color(255, 160, 55)
    val hotFringe: Color = Color(255, 120, 38)

    fun setLevel(engine: CombatEngineAPI, ship: ShipAPI, level: Float) {
        engine.customData[key(ship)] = level.coerceIn(0f, 1f)
    }

    fun clear(engine: CombatEngineAPI, ship: ShipAPI) {
        engine.customData.remove(key(ship))
    }

    fun getLevel(ship: ShipAPI, engine: CombatEngineAPI? = Global.getCombatEngine()): Float {
        val current = engine?.customData?.get(key(ship)) as? Float ?: 0f
        return current.coerceIn(0f, 1f)
    }

    fun lerpColor(from: Color, to: Color, level: Float, alpha: Int = 255): Color {
        val t = level.coerceIn(0f, 1f)
        fun lerp(a: Int, b: Int): Int = (a + (b - a) * t).roundToInt().coerceIn(0, 255)
        return Color(lerp(from.red, to.red), lerp(from.green, to.green), lerp(from.blue, to.blue), alpha.coerceIn(0, 255))
    }

    private fun key(ship: ShipAPI): String = "$VISUAL_LEVEL_KEY_PREFIX${System.identityHashCode(ship)}"
}