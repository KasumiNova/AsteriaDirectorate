package cn.kasuminova.astd.renderer.projectile

import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxTrailRenderer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ASTDProjectileVfxTrailRendererTest {
    @Test
    fun `trail renderer builds local two node head locked beam`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val spec = preset.trailEntities.single()
        val nodes = ASTDProjectileVfxTrailRenderer.localNodes(spec)

        assertEquals(2, nodes.size)
        assertEquals(-420f, nodes[0].x)
        assertEquals(0f, nodes[0].y)
        assertEquals(0f, nodes[1].x)
        assertEquals(0f, nodes[1].y)
    }

    @Test
    fun `trail renderer exposes width and orientation mapping`() {
        val spec = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!.trailEntities.single()
        val params = ASTDProjectileVfxTrailRenderer.parametersForTests(spec, testContext())

        assertEquals(3.5f, params.startWidth)
        assertEquals(0.3f, params.endWidth)
        assertEquals(ASTDProjectileVfxAnchorMode.HeadLocked, params.anchorMode)
        assertEquals(ASTDProjectileVfxOrientationMode.ProjectileVelocity, params.orientationMode)
        assertEquals(5f, params.boxUtilFacing)
    }

    @Test
    fun `trail renderer fails fast when BoxUtil entity cannot be added`() {
        val source = Files.readString(Path.of("src/main/kotlin/cn/kasuminova/astd/renderer/projectile/runtime/ASTDProjectileVfxTrailRenderer.kt"))
        val createEntityBody = source.substringAfter("internal fun createEntity").substringBefore("internal fun applyLayer")

        assertTrue(createEntityBody.contains("throw IllegalStateException"))
        assertFalse(createEntityBody.contains("return null"))
    }

    @Test
    fun `projectile BoxUtil runtime layers do not warn and continue after addEntity failure`() {
        listOf(
            Path.of("src/main/kotlin/cn/kasuminova/astd/renderer/projectile/runtime/ASTDProjectileVfxTrailRenderer.kt"),
            Path.of("src/main/kotlin/cn/kasuminova/astd/renderer/projectile/runtime/ASTDProjectileVfxSideWispRenderer.kt"),
            Path.of("src/main/kotlin/cn/kasuminova/astd/renderer/projectile/runtime/ASTDProjectileVfxMistRenderer.kt"),
        ).forEach { sourcePath ->
            val source = Files.readString(sourcePath)
            assertFalse(source.contains("addEntity failed state="), "${sourcePath.fileName} must fail fast instead of logging addEntity failure")
            assertTrue(source.contains("throw IllegalStateException"), "${sourcePath.fileName} must throw on BoxUtil addEntity failure")
            assertTrue(source.contains("delete()"), "${sourcePath.fileName} must clean up partial BoxUtil state on addEntity failure")
        }
    }
}
