package cn.kasuminova.astd.combat.effect.joint.stardust

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StardustLauncherAutofireAITest {

    @Test
    fun `光尘未满且有余弹时开火，空弹不开火`() {
        assertTrue(StardustLauncherAutofireAI.shouldFire(ammo = 1, maxAmmo = 6, aliveMotes = 0))
        assertTrue(StardustLauncherAutofireAI.shouldFire(ammo = 6, maxAmmo = 6, aliveMotes = 3))
        assertFalse(StardustLauncherAutofireAI.shouldFire(ammo = 0, maxAmmo = 6, aliveMotes = 0))
    }

    @Test
    fun `光尘达到上限时低备弹停火`() {
        assertFalse(StardustLauncherAutofireAI.shouldFire(ammo = 3, maxAmmo = 6, aliveMotes = 4))
        assertFalse(StardustLauncherAutofireAI.shouldFire(ammo = 1, maxAmmo = 6, aliveMotes = 10))
    }

    @Test
    fun `备弹达到满值减二时无视光尘上限开火`() {
        assertTrue(StardustLauncherAutofireAI.shouldFire(ammo = 4, maxAmmo = 6, aliveMotes = 4))
        assertTrue(StardustLauncherAutofireAI.shouldFire(ammo = 5, maxAmmo = 6, aliveMotes = 8))
        assertTrue(StardustLauncherAutofireAI.shouldFire(ammo = 6, maxAmmo = 6, aliveMotes = 4))
        assertFalse(StardustLauncherAutofireAI.shouldFire(ammo = 3, maxAmmo = 6, aliveMotes = 4))
    }

    @Test
    fun `低满值武器的阈值下限为一发`() {
        assertTrue(StardustLauncherAutofireAI.shouldFire(ammo = 1, maxAmmo = 2, aliveMotes = 4))
        assertFalse(StardustLauncherAutofireAI.shouldFire(ammo = 0, maxAmmo = 2, aliveMotes = 4))
    }
}
