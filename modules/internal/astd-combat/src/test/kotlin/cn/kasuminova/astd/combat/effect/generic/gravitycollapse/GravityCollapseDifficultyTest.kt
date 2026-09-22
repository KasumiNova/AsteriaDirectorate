package cn.kasuminova.astd.combat.effect.generic.gravitycollapse

import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 引力坍缩炮难度锚点回归：锁定四档武器的三锚点数值与线性映射口径
 * （k_s=1 取下限 / k_s=2 落在两点线性插值 / k_s=5 取上限）。
 *
 * 数值需求来源：weapon_data tooltip 文案（0.5s | 比例 | 无视 | 减速 | 时长 | 难度系数）。
 */
class GravityCollapseDifficultyTest {

    @AfterTest
    fun cleanup() {
        DifficultyTuningImpl.installScaleForTests(null)
        GravityCollapseDifficulty.resetWarnStateForTests()
    }

    private fun assertAnchor(
        entry: ScalingEntry,
        atKs1: Float,
        atKs2: Float,
        atKs3: Float,
        atKs5: Float,
    ) {
        DifficultyTuningImpl.installScaleForTests(1f)
        assertEquals(atKs1, DifficultyTuningImpl.value(entry), 1e-4f, "k_s=1")
        DifficultyTuningImpl.installScaleForTests(2f)
        assertEquals(atKs2, DifficultyTuningImpl.value(entry), 1e-4f, "k_s=2")
        DifficultyTuningImpl.installScaleForTests(3f)
        assertEquals(atKs3, DifficultyTuningImpl.value(entry), 1e-4f, "k_s=3")
        DifficultyTuningImpl.installScaleForTests(5f)
        assertEquals(atKs5, DifficultyTuningImpl.value(entry), 1e-4f, "k_s=5")
    }

    @Test
    fun `范围高爆伤害比例锚点（大中小PD）`() {
        assertAnchor(spec("astd_gcp12").aoeDamageRatio, 0.20f, 0.25f, 0.30f, 0.40f)
        assertAnchor(spec("astd_gcp8").aoeDamageRatio, 0.25f, 0.3125f, 0.375f, 0.50f)
        assertAnchor(spec("astd_gcp4").aoeDamageRatio, 0.33f, 0.4125f, 0.495f, 0.66f)
        assertAnchor(spec("astd_gcp2").aoeDamageRatio, 0.50f, 0.625f, 0.75f, 1.00f)
    }

    @Test
    fun `机动性降低锚点（大中小PD）`() {
        assertAnchor(spec("astd_gcp12").mobilityReduction, 0.50f, 0.5625f, 0.625f, 0.75f)
        assertAnchor(spec("astd_gcp8").mobilityReduction, 0.40f, 0.45f, 0.50f, 0.60f)
        assertAnchor(spec("astd_gcp4").mobilityReduction, 0.30f, 0.3375f, 0.375f, 0.45f)
        assertAnchor(spec("astd_gcp2").mobilityReduction, 0.20f, 0.225f, 0.25f, 0.30f)
    }

    @Test
    fun `机动抑制时长与装甲减伤无视锚点（全系列共用）`() {
        for (id in listOf("astd_gcp12", "astd_gcp8", "astd_gcp4", "astd_gcp2")) {
            assertAnchor(spec(id).mobilityDuration, 2f, 2.5f, 3f, 4f)
            assertAnchor(spec(id).armorReductionIgnore, 0.50f, 0.60f, 0.70f, 0.90f)
        }
    }

    @Test
    fun `取值分支——玩家来源固定 v2 设计基准（不随 k_s 变化）`() {
        val config = cfg("astd_gcp12")
        DifficultyTuningImpl.installScaleForTests(5f)
        val values = GravityCollapseDifficulty.resolve(DifficultyTuningImpl, 0, config, "astd_gcp12")
        assertEquals(0.25f, values.aoeDamageRatio, 1e-4f)
        assertEquals(0.5625f, values.mobilityReduction, 1e-4f)
        assertEquals(2.5f, values.mobilityDuration, 1e-4f)
        assertEquals(0.60f, values.armorReductionIgnore, 1e-4f)
    }

    @Test
    fun `取值分支——敌方来源按轨一 k_s 映射`() {
        val config = cfg("astd_gcp8")
        DifficultyTuningImpl.installScaleForTests(3f)
        val values = GravityCollapseDifficulty.resolve(DifficultyTuningImpl, 1, config, "astd_gcp8")
        assertEquals(0.375f, values.aoeDamageRatio, 1e-4f)
        assertEquals(0.50f, values.mobilityReduction, 1e-4f)
        assertEquals(3f, values.mobilityDuration, 1e-4f)
        assertEquals(0.70f, values.armorReductionIgnore, 1e-4f)
    }

    @Test
    fun `取值分支——无来源保守取 v2（WARN-once 不抛异常）`() {
        val config = cfg("astd_gcp4")
        DifficultyTuningImpl.installScaleForTests(5f)
        val first = GravityCollapseDifficulty.resolve(DifficultyTuningImpl, null, config, "astd_gcp4")
        val second = GravityCollapseDifficulty.resolve(DifficultyTuningImpl, null, config, "astd_gcp4")
        assertEquals(0.4125f, first.aoeDamageRatio, 1e-4f)
        assertEquals(first.aoeDamageRatio, second.aoeDamageRatio, 1e-4f)
    }

    private fun spec(weaponId: String) =
        GravityCollapseWeaponSpecs.forWeaponId(weaponId) ?: error("缺少坍缩炮配置：$weaponId")

    /** 难度解析只消费四条锚点，其余字段取默认。 */
    private fun cfg(weaponId: String): GravityCollapseOnHitConfig {
        val s = spec(weaponId)
        return GravityCollapseOnHitConfig(
            aoeDamageRatio = s.aoeDamageRatio,
            mobilityReduction = s.mobilityReduction,
            mobilityDuration = s.mobilityDuration,
            armorReductionIgnore = s.armorReductionIgnore,
        )
    }
}
