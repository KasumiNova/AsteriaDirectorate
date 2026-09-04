package cn.kasuminova.astd.renderer.effect.explosion

import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [RiftExplosionVfx] 纯函数面单测：buildRiftParams 参数映射（对齐原版
 * RiftCascadeMineExplosion.createStandardRiftParams 口径 + 本组件厚度同比缩放）
 * 与尺寸锚点常量（-30% 定案）——真实调用断言输出，不触引擎。
 */
class RiftExplosionVfxTest {

    @Test
    fun `buildRiftParams 字段映射对齐原版口径`() {
        val palette = RiftExplosionPalette(
            border = Color(1, 2, 3, 255),
            underglow = Color(4, 5, 6, 100),
            windup = Color(7, 8, 9, 60),
        )
        val p = RiftExplosionVfx.buildRiftParams(
            palette = palette,
            radius = 40f,
            fadeOut = 1.25f,
            withHitGlow = true,
        )
        assertEquals(40f, p.radius, 1e-6f, "半径原样透传（抖动在生成循环侧）")
        assertEquals(40f, p.thickness, 1e-6f, "厚度 = 半径 × 1.0（原版 25/25 比）")
        assertEquals(0.1f, p.fadeIn, 1e-6f, "淡入口径 0.1s（原版定值）")
        assertEquals(1.25f, p.fadeOut, 1e-6f, "淡出时长透传")
        assertTrue(p.withHitGlow, "首裂隙带命中闪光")
        assertEquals(0.75f, p.hitGlowSizeMult, 1e-6f, "命中闪光尺寸倍率（原版定值）")
        assertEquals(0.0f, p.spawnHitGlowAt, 1e-6f, "命中闪光触发阈值（原版定值）")
        assertEquals(1.0f, p.noiseMag, 1e-6f, "噪声幅度（原版定值）")
        assertEquals(palette.border, p.color, "边缘色取调色板 border")
        assertEquals(palette.underglow, p.underglow, "底部星云色取调色板 underglow（覆盖原版暗红默认）")
    }

    @Test
    fun `buildRiftParams 次裂隙不带命中闪光`() {
        val p = RiftExplosionVfx.buildRiftParams(
            palette = RiftExplosionPalette.BLUE,
            radius = 17.5f,
            fadeOut = 1.0f,
            withHitGlow = false,
        )
        assertFalse(p.withHitGlow, "仅首裂隙 withHitGlow（原版循环口径）")
        assertEquals(RiftExplosionPalette.BLUE.border, p.color)
    }

    @Test
    fun `尺寸锚点常量守护`() {
        assertEquals(0.7f, RiftExplosionVfx.SIZE_SCALE, 1e-6f, "总尺寸相对原版 -30%（需求定案）")
        assertEquals(25f, RiftExplosionVfx.VANILLA_BASE_RADIUS, 1e-6f, "原版地雷基准半径 25")
        assertEquals(17.5f, RiftExplosionVfx.DEFAULT_RADIUS, 1e-6f, "默认半径 = 25 × 0.7")
        assertEquals(2, RiftExplosionVfx.NUM_RIFTS, "每次爆炸 2 枚裂隙（原版默认）")
        assertEquals(0.5f, RiftExplosionVfx.WINDUP_SECONDS, 1e-6f, "起爆征兆 0.5s（原版地雷 delay）")
        assertEquals(1.0f, RiftExplosionVfx.DEFAULT_FADE_OUT, 1e-6f, "淡出 1.0s（原版地雷口径）")
    }
}
