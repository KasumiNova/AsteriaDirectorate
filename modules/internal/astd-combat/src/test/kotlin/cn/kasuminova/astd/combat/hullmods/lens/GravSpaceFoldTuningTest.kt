package cn.kasuminova.astd.combat.hullmods.lens

import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import org.lwjgl.util.vector.Vector2f
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 引力空间折跃器数值规格：折跃概率三锚点折算（v1 40%/65% / v2 50%/75% / v5 75%/95%，
 * 玩家固定 v2）、概率合成（伤害 ≤50 取基础值、≥500 触及上限、中间线性加计）、
 * 镜像折跃落点（newPos = 2 × 舰船中心 − 弹体位置）。
 * 经 [DifficultyTuningImpl.installScaleForTests] 走完整映射链路（对齐 GravPhaseDeckTuningTest 先例）。
 */
class GravSpaceFoldTuningTest {

    @AfterTest
    fun clearOverride() {
        DifficultyTuningImpl.installScaleForTests(null)
    }

    private fun resolveAt(scale: Float, isPlayer: Boolean = false): GravSpaceFoldTuning.Values {
        DifficultyTuningImpl.installScaleForTests(scale)
        return GravSpaceFoldTuning.resolve(DifficultyTuningImpl, isPlayer)
    }

    @Test
    fun `三锚点精确取档 k_s 1 2 5`() {
        val v1 = resolveAt(1f)
        assertEquals(0.40f, v1.foldChanceBase, 1e-6f)
        assertEquals(0.65f, v1.foldChanceCap, 1e-6f)
        assertEquals(0.85f, v1.beamDamageTakenMult, 1e-6f)

        val v2 = resolveAt(2f)
        assertEquals(0.50f, v2.foldChanceBase, 1e-6f)
        assertEquals(0.75f, v2.foldChanceCap, 1e-6f)
        assertEquals(0.75f, v2.beamDamageTakenMult, 1e-6f)

        val v5 = resolveAt(5f)
        assertEquals(0.75f, v5.foldChanceBase, 1e-6f)
        assertEquals(0.95f, v5.foldChanceCap, 1e-6f)
        assertEquals(0.50f, v5.beamDamageTakenMult, 1e-6f)
    }

    @Test
    fun `玩家固定 v2 与 k_s 无关`() {
        val v = resolveAt(5f, isPlayer = true)
        assertEquals(0.50f, v.foldChanceBase, 1e-6f)
        assertEquals(0.75f, v.foldChanceCap, 1e-6f)
        assertEquals(0.75f, v.beamDamageTakenMult, 1e-6f)
    }

    @Test
    fun `概率合成 伤害低于下限取基础值`() {
        assertEquals(0.5f, GravSpaceFoldTuning.foldChance(0.5f, 0.75f, 50f), 1e-6f)
        assertEquals(0.5f, GravSpaceFoldTuning.foldChance(0.5f, 0.75f, 0f), 1e-6f)
        assertEquals(0.5f, GravSpaceFoldTuning.foldChance(0.5f, 0.75f, -20f), 1e-6f)
    }

    @Test
    fun `概率合成 伤害高于上限触及封顶`() {
        assertEquals(0.75f, GravSpaceFoldTuning.foldChance(0.5f, 0.75f, 500f), 1e-6f)
        assertEquals(0.75f, GravSpaceFoldTuning.foldChance(0.5f, 0.75f, 2000f), 1e-6f)
    }

    @Test
    fun `概率合成 中间伤害线性加计`() {
        // 275 恰为 50~500 中点：概率为基础与上限的中点
        assertEquals(0.625f, GravSpaceFoldTuning.foldChance(0.5f, 0.75f, 275f), 1e-6f)
        // 140 = 50 + 450×0.2：加计 20% 区间
        assertEquals(0.55f, GravSpaceFoldTuning.foldChance(0.5f, 0.75f, 140f), 1e-6f)
    }

    @Test
    fun `折跃判定三态流转 未判定按 roll 与概率分流到两个终态`() {
        // roll 命中概率 → 已折跃终态 + 本次执行折跃
        val folded = GravSpaceFoldTuning.resolveFold(null, 0.4f, 0.5f)
        assertEquals(GravSpaceFoldTuning.MARK_FOLDED, folded.first)
        assertEquals(true, folded.second)
        // roll 未命中 → 判定不折跃终态 + 本次不折跃
        val noFold = GravSpaceFoldTuning.resolveFold(null, 0.5f, 0.5f)
        assertEquals(GravSpaceFoldTuning.MARK_NO_FOLD, noFold.first)
        assertEquals(false, noFold.second)
    }

    @Test
    fun `折跃判定三态流转 两个终态均不再触发折跃`() {
        // 已折跃弹体镜像后仍在范围内：保持标记且不再次折跃（roll 入参不被消费）
        val foldedAgain = GravSpaceFoldTuning.resolveFold(GravSpaceFoldTuning.MARK_FOLDED, 0f, 1f)
        assertEquals(GravSpaceFoldTuning.MARK_FOLDED, foldedAgain.first)
        assertEquals(false, foldedAgain.second)
        // 判定不折跃的弹体不重 roll
        val noFoldAgain = GravSpaceFoldTuning.resolveFold(GravSpaceFoldTuning.MARK_NO_FOLD, 0f, 1f)
        assertEquals(GravSpaceFoldTuning.MARK_NO_FOLD, noFoldAgain.first)
        assertEquals(false, noFoldAgain.second)
    }

    @Test
    fun `镜像折跃落点为中心对称点`() {
        val mirrored = GravSpaceFoldTuning.mirroredPosition(Vector2f(100f, 200f), Vector2f(160f, 230f))
        assertEquals(40f, mirrored.x, 1e-6f)
        assertEquals(170f, mirrored.y, 1e-6f)
    }

    @Test
    fun `镜像折跃落点两次镜像回到原位且保持等距`() {
        val center = Vector2f(-50f, 80f)
        val pos = Vector2f(10f, -30f)
        val twice = GravSpaceFoldTuning.mirroredPosition(
            center, GravSpaceFoldTuning.mirroredPosition(center, pos),
        )
        assertEquals(pos.x, twice.x, 1e-5f)
        assertEquals(pos.y, twice.y, 1e-5f)

        val mirrored = GravSpaceFoldTuning.mirroredPosition(center, pos)
        val distBefore = Vector2f.sub(center, pos, null).length()
        val distAfter = Vector2f.sub(center, mirrored, null).length()
        assertEquals(distBefore, distAfter, 1e-5f)
    }
}
