package cn.kasuminova.astd.combat.hullmods.base

import com.fs.starfarer.api.combat.ShipVariantAPI
import com.fs.starfarer.api.loading.HullModSpecAPI
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 内置船插动态图标（[ASTDHullmodSprites]）验证：
 * 1. 换贴守卫——目标与当前 sprite 相同不写 spec，不同才写（stats 重算频繁，避免无意义赋值）。
 * 2. 切换器形态判定——variant 模式 permaMod 决定 crew/auto 变体；缺模式态按载人（状态机缺省）。
 * 3. 谱系判定——以 lens 奇点能源为 LENS 谱系标记，未携带按 ARC（含 lh_001 等 ARC 侧联制舰）。
 */
class ASTDHullmodSpritesTest {

    private fun variantWith(permaMods: Set<String>, hullMods: Set<String> = emptySet()): ShipVariantAPI {
        val variant = mock(ShipVariantAPI::class.java)
        `when`(variant.permaMods).thenReturn(permaMods.toMutableSet())
        `when`(variant.hasHullMod(org.mockito.ArgumentMatchers.anyString())).thenAnswer { it.arguments[0] in hullMods || it.arguments[0] in permaMods }
        return variant
    }

    @Test
    fun `swap writes only when target sprite differs`() {
        val spec = mock(HullModSpecAPI::class.java)
        `when`(spec.spriteName).thenReturn("graphics/hullmods/old.png")

        ASTDHullmodSprites.swap(spec, "graphics/hullmods/old.png")
        verify(spec, never()).spriteName = "graphics/hullmods/old.png"

        ASTDHullmodSprites.swap(spec, "graphics/hullmods/new.png")
        verify(spec).spriteName = "graphics/hullmods/new.png"
    }

    @Test
    fun `switcher sprite follows current mode permaMods`() {
        val config = GENERIC_DUAL_MODE_CONFIG

        assertEquals(
            ASTDHullmodSprites.SWITCHER_CREW,
            ASTDHullmodSprites.switcherSpriteFor(variantWith(setOf(config.crewedModeId)), config),
            "载人模式应显示乘员席位变体",
        )
        assertEquals(
            ASTDHullmodSprites.SWITCHER_AUTO,
            ASTDHullmodSprites.switcherSpriteFor(variantWith(setOf(config.automatedModeId)), config),
            "无人模式应显示 AI 核心变体",
        )
        assertEquals(
            ASTDHullmodSprites.SWITCHER_CREW,
            ASTDHullmodSprites.switcherSpriteFor(variantWith(emptySet()), config),
            "缺模式态应按载人缺省",
        )
    }

    @Test
    fun `lineage sprite follows lens singularity power marker`() {
        assertEquals(
            ASTDHullmodSprites.NANO_LENS,
            ASTDHullmodSprites.lineageSpriteFor(
                variantWith(emptySet(), setOf("astd_lens_singularity_power")),
                ASTDHullmodSprites.NANO_ARC,
                ASTDHullmodSprites.NANO_LENS,
            ),
            "LENS 谱系（内置 lens 奇点能源）应显示 lens 变体",
        )
        assertEquals(
            ASTDHullmodSprites.STABILIZER_ARC,
            ASTDHullmodSprites.lineageSpriteFor(
                variantWith(emptySet(), setOf("astd_arc_singularity_power")),
                ASTDHullmodSprites.STABILIZER_ARC,
                ASTDHullmodSprites.STABILIZER_LENS,
            ),
            "ARC 谱系应显示 arc 变体",
        )
    }
}
