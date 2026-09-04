package cn.kasuminova.astd.combat.effect.arc

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.impl.buff.stubShip
import cn.kasuminova.astd.impl.buff.stubWeapon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.mockito.Mockito.`when`

/**
 * 规格 03 §4.1 用例 1~7：电驱加速炮机制纯逻辑全量验证（全部调用真实逻辑，禁止源码 contain）。
 *
 * 覆盖：装药上限与射程锚点的难度取值（玩家固定 v2）、辐能衰减分段、最终加成合成、
 * 装药额外伤害换算与触发阈值、seed 派生稳定性、随机序列单调性与换装回收。
 */
class ElectricDriveAcceleratorLogicTest {

    /** 测试桩：固定 k_s 的 DifficultyTuning 接口实现（非反射），value 走 entry.map 真算。 */
    private class FakeTuning(override val fixedScale: Float) : DifficultyTuning {
        override fun value(entry: ScalingEntry): Float = entry.map.value(fixedScale, entry.v1, entry.v2, entry.v5)
    }

    @Test
    fun `用例1 装药上限玩家固定 v2 与敌版三锚点`() {
        assertEquals(56.25f, ElectricDriveAcceleratorDifficulty.chargeMaxPct(FakeTuning(5f), owner = 0), 1e-6f)
        assertEquals(25f, ElectricDriveAcceleratorDifficulty.chargeMaxPct(FakeTuning(1f), owner = 1), 1e-6f)
        assertEquals(56.25f, ElectricDriveAcceleratorDifficulty.chargeMaxPct(FakeTuning(2f), owner = 1), 1e-6f)
        assertEquals(87.5f, ElectricDriveAcceleratorDifficulty.chargeMaxPct(FakeTuning(3f), owner = 1), 1e-6f)
        assertEquals(150f, ElectricDriveAcceleratorDifficulty.chargeMaxPct(FakeTuning(5f), owner = 1), 1e-6f)
    }

    @Test
    fun `用例2 射程锚点玩家固定 v2 与敌版四档（远征 300 对表）`() {
        assertEquals(200f, ElectricDriveAcceleratorDifficulty.rangeBonusBase(FakeTuning(5f), owner = 0), 1e-6f)
        assertEquals(100f, ElectricDriveAcceleratorDifficulty.rangeBonusBase(FakeTuning(1f), owner = 1), 1e-6f)
        assertEquals(200f, ElectricDriveAcceleratorDifficulty.rangeBonusBase(FakeTuning(2f), owner = 1), 1e-6f)
        assertEquals(300f, ElectricDriveAcceleratorDifficulty.rangeBonusBase(FakeTuning(3f), owner = 1), 1e-6f)
        assertEquals(400f, ElectricDriveAcceleratorDifficulty.rangeBonusBase(FakeTuning(5f), owner = 1), 1e-6f)
    }

    @Test
    fun `用例3 辐能衰减系数分段与越界 NaN 防线`() {
        listOf(0f, 0.1f, 0.2f).forEach { level ->
            assertEquals(1f, ElectricDriveAcceleratorDifficulty.fluxDecayFactor(level), 1e-6f, "level=$level 应满额")
        }
        assertEquals(0.75f, ElectricDriveAcceleratorDifficulty.fluxDecayFactor(0.25f), 1e-6f)
        assertEquals(0.5f, ElectricDriveAcceleratorDifficulty.fluxDecayFactor(0.3f), 1e-6f)
        listOf(0.4f, 0.6f, 1.0f).forEach { level ->
            assertEquals(0f, ElectricDriveAcceleratorDifficulty.fluxDecayFactor(level), 1e-6f, "level=$level 应归零")
        }
        // 越界 clamp：-0.5 → 满额；1.5 → 归零。
        assertEquals(1f, ElectricDriveAcceleratorDifficulty.fluxDecayFactor(-0.5f), 1e-6f)
        assertEquals(0f, ElectricDriveAcceleratorDifficulty.fluxDecayFactor(1.5f), 1e-6f)
        // NaN → 0 加成（调用侧 WARN 防线在 WeaponEffect，纯函数只定返回值语义）。
        assertEquals(0f, ElectricDriveAcceleratorDifficulty.fluxDecayFactor(Float.NaN), 1e-6f)
    }

    @Test
    fun `用例4 最终射程加成合成（玩家档 200 基础）`() {
        // 合成路径存在 float 减法误差（0.3f-0.2f≈0.099999994），容差放宽至 1e-4（对齐基建测试浮点容差先例）。
        assertEquals(100f, ElectricDriveAcceleratorDifficulty.rangeBonus(FakeTuning(2f), owner = 0, level = 0.3f), 1e-4f)
        assertEquals(200f, ElectricDriveAcceleratorDifficulty.rangeBonus(FakeTuning(2f), owner = 0, level = 0.1f), 1e-4f)
        assertEquals(0f, ElectricDriveAcceleratorDifficulty.rangeBonus(FakeTuning(2f), owner = 0, level = 0.4f), 1e-4f)
    }

    @Test
    fun `用例5 装药额外伤害换算与触发阈值`() {
        assertEquals(45f, ElectricDriveAcceleratorDifficulty.extraDamage(80f, 56.25f), 1e-6f)
        assertEquals(0f, ElectricDriveAcceleratorDifficulty.extraDamage(80f, 0f), 1e-6f)
        assertFalse(ElectricDriveAcceleratorDifficulty.shouldApplyExtra(0.5f))
        assertTrue(ElectricDriveAcceleratorDifficulty.shouldApplyExtra(1f))
    }

    @Test
    fun `用例6 seed 派生稳定性与区分度`() {
        val seedA = ElectricDriveAcceleratorDifficulty.seedOf("ship_1", "WS 001")
        assertEquals(seedA, ElectricDriveAcceleratorDifficulty.seedOf("ship_1", "WS 001"), "同 shipId+slotId 两次调用须同值")
        assertNotEquals(seedA, ElectricDriveAcceleratorDifficulty.seedOf("ship_1", "WS 002"), "不同 slotId 须不同值")
        assertNotEquals(seedA, ElectricDriveAcceleratorDifficulty.seedOf("ship_2", "WS 001"), "不同 shipId 须不同值")
    }

    @Test
    fun `用例7 随机序列单调递增与换装回收`() {
        val ship = stubShip()
        `when`(ship.isAlive).thenReturn(true)
        val weapon = stubWeapon("WS 001", ElectricDriveChargeState.WEAPON_ID)
        val state = ElectricDriveChargeState(ship, weapon, ElectricDriveAcceleratorDifficulty.seedOf("ship_1", "WS 001"))

        // nextCallIndex 连续调用返回 0,1,2… 不复位（双管同帧两发取值不同的前置保证）。
        assertEquals(0, state.nextCallIndex())
        assertEquals(1, state.nextCallIndex())
        assertEquals(2, state.nextCallIndex())

        // 换装回收路径：weaponId 不匹配时 isHostValid 返回 false。
        assertTrue(state.isHostValid())
        val swapped = ElectricDriveChargeState(ship, stubWeapon("WS 001", "astd_other_weapon"), 1L)
        assertFalse(swapped.isHostValid())
        // hulk 后宿主失效。
        val hulkShip = stubShip(hulk = true)
        `when`(hulkShip.isAlive).thenReturn(false)
        assertFalse(ElectricDriveChargeState(hulkShip, weapon, 1L).isHostValid())
    }
}
