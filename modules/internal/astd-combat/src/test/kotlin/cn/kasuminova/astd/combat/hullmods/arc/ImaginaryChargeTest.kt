package cn.kasuminova.astd.combat.hullmods.arc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImaginaryChargeTest {

    @Test
    fun `充能入账钳制在上下界之间`() {
        val pool = ImaginaryCharge()
        assertEquals(0f, pool.charge)

        pool.add(150f)
        assertEquals(ImaginaryWingsTuning.CHARGE_MAX, pool.charge)
        pool.add(-30f)
        assertEquals(70f, pool.charge)
        pool.add(-100f)
        assertEquals(0f, pool.charge)
    }

    @Test
    fun `按比例支出返回实际扣除量并逐发递减`() {
        val pool = ImaginaryCharge()
        pool.add(100f)

        assertEquals(10f, pool.consumeFraction(0.1f), 1e-4f)
        assertEquals(90f, pool.charge, 1e-4f)

        assertEquals(9f, pool.consumeFraction(0.1f), 1e-4f)
        assertEquals(81f, pool.charge, 1e-4f)
    }

    @Test
    fun `零充能时按比例支出不产生负数`() {
        val pool = ImaginaryCharge()
        assertEquals(0f, pool.consumeFraction(0.1f), 1e-4f)
        assertEquals(0f, pool.charge)
    }

    @Test
    fun `兑换要求次数为零且充能严格高于阈值`() {
        val pool = ImaginaryCharge()
        pool.charge = 59.9f
        assertFalse(pool.tryExchangeSystemCharge(systemAmmo = 0))
        assertEquals(59.9f, pool.charge, 1e-4f)

        pool.charge = 60f
        assertFalse(pool.tryExchangeSystemCharge(systemAmmo = 0))
        assertEquals(60f, pool.charge, 1e-4f)

        pool.charge = 60.1f
        assertTrue(pool.tryExchangeSystemCharge(systemAmmo = 0))
        assertEquals(0.1f, pool.charge, 1e-3f)
    }

    @Test
    fun `已有系统次数时不兑换且不扣充能`() {
        val pool = ImaginaryCharge()
        pool.charge = 100f
        assertFalse(pool.tryExchangeSystemCharge(systemAmmo = 1))
        assertEquals(100f, pool.charge)
    }

    @Test
    fun `连续兑换受余额限制只成功一次`() {
        val pool = ImaginaryCharge()
        pool.charge = 70f
        assertTrue(pool.tryExchangeSystemCharge(systemAmmo = 0))
        assertEquals(10f, pool.charge, 1e-4f)
        assertFalse(pool.tryExchangeSystemCharge(systemAmmo = 0))
        assertEquals(10f, pool.charge, 1e-4f)
    }
}
