package cn.kasuminova.astd.renderer.projectile

import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class ASTDProjectileNativeVisualSuppressionTest {
    @Test
    fun `AOD7 projectile disables native Starsector visual overlay`() {
        val spec = JSONObject(Files.readString(Path.of("contents/data/weapons/proj/astd_aod7_shot.proj")))

        assertEquals("astd_aod7_shot", spec.getString("id"))
        assertEquals("projectile", spec.getString("specClass"))
        assertEquals("BALLISTIC", spec.getString("spawnType"))
        assertEquals(
            "cn.kasuminova.astd.combat.effect.generic.ProjectileSpecOnFireDispatcher",
            spec.getString("onFireEffect"),
        )
        assertEquals(
            "cn.kasuminova.astd.combat.effect.generic.HighFluxShieldPressureOnHitEffect",
            spec.getString("onHitEffect"),
        )
        assertEquals("PROJECTILE_FF", spec.getString("collisionClass"))
        assertEquals("PROJECTILE_FIGHTER", spec.getString("collisionClassByFighter"))
        assertEquals(2.0, spec.getDouble("length"))
        assertEquals(2.0, spec.getDouble("width"))
        assertEquals(0.2, spec.getDouble("fadeTime"))
        assertEquals(0.0, spec.getDouble("textureScrollSpeed"))
        assertEquals(1.0, spec.getDouble("pixelsPerTexel"))
        assertEquals(0, spec.getJSONArray("fringeColor").getInt(3))
        assertEquals(0, spec.getJSONArray("coreColor").getInt(3))
        assertEquals("graphics/textures/BUtil_NONE.png", spec.getString("bulletSprite"))
    }
}
