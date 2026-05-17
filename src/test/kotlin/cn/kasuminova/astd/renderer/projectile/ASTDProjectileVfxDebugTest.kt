package cn.kasuminova.astd.renderer.projectile

import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxDebug
import kotlin.test.Test
import kotlin.test.assertEquals

class ASTDProjectileVfxDebugTest {
    @Test
    fun `debug layer visibility filters render graph groups`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val visibility = ASTDProjectileVfxDebug.Visibility(
            trail = true,
            head = false,
            glow = true,
            mist = false,
            sideWisps = false,
            ribbon = true,
            logLayoutOnce = false,
        )

        assertEquals(listOf("trail", "glow", "ribbon"), ASTDProjectileVfxDebug.layersEnabledForTests(preset, visibility))
    }
}
