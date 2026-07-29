package cn.kasuminova.astd.combat.effect.arc

import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxSpecs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 规格 02 §4.1 用例 10：弹体 VFX 登记——真实调用管线入口，build 执行 DSL 不抛异常。
 */
class HeavyIonPulseVfxRegistrationTest {

    @Test
    fun `用例10 弹体 VfxSpec 登记且可构建`() {
        assertTrue(ProjectileVfxSpecs.has("astd_heavy_ion_pulse_shot"), "重型离子脉冲弹体 VFX 未登记")
        assertNotNull(ProjectileVfxSpecs.build("astd_heavy_ion_pulse_shot"), "重型离子脉冲弹体 VFX 构建返回 null")
    }
}
