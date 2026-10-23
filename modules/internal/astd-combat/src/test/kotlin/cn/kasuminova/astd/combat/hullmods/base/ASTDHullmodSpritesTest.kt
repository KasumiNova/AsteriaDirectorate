package cn.kasuminova.astd.combat.hullmods.base

import com.fs.starfarer.api.combat.ShipVariantAPI
import com.fs.starfarer.api.loading.HullModSpecAPI
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import kotlin.test.Test
import kotlin.test.assertEquals

class ASTDHullmodSpritesTest {

    private fun variantWith(permaMods: Set<String>): ShipVariantAPI {
        val variant = mock(ShipVariantAPI::class.java)
        `when`(variant.permaMods).thenReturn(permaMods.toMutableSet())
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
}
