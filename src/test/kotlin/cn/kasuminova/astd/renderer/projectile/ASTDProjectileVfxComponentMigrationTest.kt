package cn.kasuminova.astd.renderer.projectile

import cn.kasuminova.astd.renderer.projectile.component.ASTDProjectileVfxComponentSpec
import kotlin.test.Test
import kotlin.test.assertEquals

class ASTDProjectileVfxComponentMigrationTest {
    @Test
    fun `aod7 preset is represented by ordered render components`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!

        assertEquals(
            listOf("trail", "mist", "glow", "body", "sideWisp", "head", "ribbon"),
            preset.components.map { it.kind },
        )
    }

    @Test
    fun `aod7 migrated trail keeps authored dimensions and sprites`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val trail = preset.components.filterIsInstance<ASTDProjectileVfxComponentSpec.Trail>().single()

        assertEquals(420f, trail.layer.length)
        assertEquals(40f, trail.layer.startWidth)
        assertEquals(4f, trail.layer.endWidth)
        assertEquals("graphics/fx/beamcoreb.png", trail.layer.diffuseSpritePath)
        assertEquals("graphics/fx/beamfringeb.png", trail.layer.emissiveSpritePath)
    }
}
