package cn.kasuminova.astd.renderer.effect.system

import java.awt.Color
import kotlin.math.roundToInt

/**
 * XC-001「坠星残响」冷色视觉调色板：落叶飞花为蓝白冷色系系统，
 * 全船 halo / bloom / 引擎 flare 统一取这里的冷色基底与插值工具。
 */
internal object Xc001ColdPalette {

    val coldCore: Color = Color(122, 232, 255)
    val coldFringe: Color = Color(160, 242, 255)

    fun lerpColor(from: Color, to: Color, level: Float, alpha: Int = 255): Color {
        val t = level.coerceIn(0f, 1f)
        fun lerp(a: Int, b: Int): Int = (a + (b - a) * t).roundToInt().coerceIn(0, 255)
        return Color(lerp(from.red, to.red), lerp(from.green, to.green), lerp(from.blue, to.blue), alpha.coerceIn(0, 255))
    }

    fun withAlpha(color: Color, alpha: Int): Color =
        Color(color.red, color.green, color.blue, alpha.coerceIn(0, 255))
}
