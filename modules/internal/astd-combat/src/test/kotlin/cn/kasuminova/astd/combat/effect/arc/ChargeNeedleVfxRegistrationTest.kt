package cn.kasuminova.astd.combat.effect.arc

import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxSpecs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 规格 01 §4.1 用例 14：弹体 VFX 登记——真实调用管线入口，build 执行 DSL 不抛异常。
 */
class ChargeNeedleVfxRegistrationTest {

    @Test
    fun `用例14 两件武器弹体 VfxSpec 登记且可构建`() {
        assertTrue(ProjectileVfxSpecs.has("astd_charge_needle_shot"), "电荷针刺弹体 VFX 未登记")
        assertTrue(ProjectileVfxSpecs.has("astd_heavy_charge_needle_shot"), "重型电荷针刺弹体 VFX 未登记")
        assertNotNull(ProjectileVfxSpecs.build("astd_charge_needle_shot"), "电荷针刺弹体 VFX 构建返回 null")
        assertNotNull(ProjectileVfxSpecs.build("astd_heavy_charge_needle_shot"), "重型电荷针刺弹体 VFX 构建返回 null")
    }
}
