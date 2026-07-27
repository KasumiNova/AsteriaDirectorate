package cn.kasuminova.astd.combat.hullmods

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.combat.hullmods.affix.BountyScalingHullMod
import cn.kasuminova.astd.combat.hullmods.arc.ASTDVirtualParticleLatticeWebHullMod
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 存量接轨锚点验证：k_s=1.0/2.0/5.0 时赏金缩放倍率与断熵虚粒子上限精确命中三锚点。
 * 通过 [DifficultyTuningImpl] 注入系数走完整映射链路。
 */
class BountyScalingHullModTest {

    @AfterTest
    fun clearOverride() {
        DifficultyTuningImpl.installScaleForTests(null)
    }

    private fun bonusesAt(scale: Float): BountyScalingHullMod.Bonuses {
        DifficultyTuningImpl.installScaleForTests(scale)
        return BountyScalingHullMod.bonuses(DifficultyTuningImpl)
    }

    @Test
    fun `迟暮 1_0 无加成`() {
        val b = bonusesAt(1.0f)
        assertEquals(1.00f, b.hull, 1e-6f)
        assertEquals(1.00f, b.armor, 1e-6f)
        assertEquals(1.00f, b.fluxCapacity, 1e-6f)
        assertEquals(1.00f, b.fluxDissipation, 1e-6f)
        assertEquals(1.00f, b.maxSpeed, 1e-6f)
    }

    @Test
    fun `砺刃 2_0 命中设计基准`() {
        val b = bonusesAt(2.0f)
        assertEquals(1.05f, b.hull, 1e-6f)
        assertEquals(1.05f, b.armor, 1e-6f)
        assertEquals(1.04f, b.fluxCapacity, 1e-6f)
        assertEquals(1.06f, b.fluxDissipation, 1e-6f)
        assertEquals(1.02f, b.maxSpeed, 1e-6f)
    }

    @Test
    fun `破晓 5_0 命中放开上限`() {
        val b = bonusesAt(5.0f)
        assertEquals(1.20f, b.hull, 1e-6f)
        assertEquals(1.20f, b.armor, 1e-6f)
        assertEquals(1.16f, b.fluxCapacity, 1e-6f)
        assertEquals(1.24f, b.fluxDissipation, 1e-6f)
        assertEquals(1.08f, b.maxSpeed, 1e-6f)
    }

    @Test
    fun `断熵虚粒子上限三锚点`() {
        val entry = ASTDVirtualParticleLatticeWebHullMod.DEFENSIVE_CAP
        DifficultyTuningImpl.installScaleForTests(1.0f)
        assertEquals(6f, DifficultyTuningImpl.value(entry), 1e-6f)
        DifficultyTuningImpl.installScaleForTests(2.0f)
        assertEquals(13f, DifficultyTuningImpl.value(entry), 1e-6f)
        DifficultyTuningImpl.installScaleForTests(5.0f)
        assertEquals(18f, DifficultyTuningImpl.value(entry), 1e-6f)
    }

    @Test
    fun `默认档下 bonuses 与注入 2_0 一致`() {
        DifficultyTuningImpl.installScaleForTests(null)
        val byDefault = BountyScalingHullMod.bonuses(DifficultyTuningImpl)
        val byInjected = bonusesAt(2.0f)
        assertEquals(byInjected, byDefault)
    }

    /** 防御：tuning 形参必须被尊重，实现内部不得写死单例。 */
    @Test
    fun `bonuses 尊重传入的读取面`() {
        val fixed = object : DifficultyTuning {
            override val fixedScale: Float get() = 5f
            override fun value(entry: cn.kasuminova.astd.api.difficulty.ScalingEntry): Float =
                entry.map.value(fixedScale, entry.v1, entry.v2, entry.v5)
        }
        val b = BountyScalingHullMod.bonuses(fixed)
        assertEquals(1.20f, b.hull, 1e-6f)
    }
}
