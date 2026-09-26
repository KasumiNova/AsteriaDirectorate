package cn.kasuminova.astd.combat.effect.lens.iceshard

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.lwjgl.util.vector.Vector2f

/**
 * [IceShardMirvMath] 纯计算面全量驱动：冰晶伤害分配的不变量（各枚落区间、
 * 合计恒定、同种子确定性）、伤害相关弹速映射、冻结周期伤害量、分裂门控与附着点换算。
 */
class IceShardMirvMathTest {

    @Test
    fun `伤害分配：多种子下数量恒定、各枚落区间、合计恒定`() {
        repeat(200) { seed ->
            val out = IceShardMirvMath.allocateShardDamages(Random(seed))
            assertEquals(IceShardMirvDifficulty.SHARD_COUNT, out.size, "seed=$seed 冰晶数量")
            out.forEachIndexed { i, dmg ->
                assertTrue(
                    dmg >= IceShardMirvDifficulty.SHARD_DAMAGE_MIN - 1e-3f &&
                            dmg <= IceShardMirvDifficulty.SHARD_DAMAGE_MAX + 1e-3f,
                    "seed=$seed 第 $i 枚伤害 $dmg 超出伤害区间",
                )
            }
            assertEquals(
                IceShardMirvDifficulty.TOTAL_DAMAGE, out.sum(), 0.05f,
                "seed=$seed 合计须恒等于总伤锚点",
            )
        }
    }

    @Test
    fun `伤害分配：同种子结果确定可复现`() {
        val a = IceShardMirvMath.allocateShardDamages(Random(42))
        val b = IceShardMirvMath.allocateShardDamages(Random(42))
        assertTrue(a.contentEquals(b), "同种子分配序列逐枚一致")
    }

    @Test
    fun `伤害相关弹速：随伤害单调递增，区间外 clamp 到端点`() {
        val min = IceShardMirvDifficulty.SHARD_DAMAGE_MIN
        val max = IceShardMirvDifficulty.SHARD_DAMAGE_MAX
        val mid = (min + max) / 2f
        assertTrue(
            IceShardMirvMath.shardSpeed(min) < IceShardMirvMath.shardSpeed(mid) &&
                    IceShardMirvMath.shardSpeed(mid) < IceShardMirvMath.shardSpeed(max),
            "弹速须随伤害单调递增",
        )
        assertEquals(
            IceShardMirvMath.shardSpeed(min), IceShardMirvMath.shardSpeed(0f), 1e-4f,
            "低于伤害区间 → clamp 到最低伤端点弹速",
        )
        assertEquals(
            IceShardMirvMath.shardSpeed(max), IceShardMirvMath.shardSpeed(9999f), 1e-4f,
            "高于伤害区间 → clamp 到最高伤端点弹速",
        )
    }

    @Test
    fun `冻结周期伤害：与子射弹伤害和难度比例双双正比，零比例为零`() {
        val tick = IceShardMirvMath.tickDamage(400f, 0.5f)
        assertEquals(tick * 2f, IceShardMirvMath.tickDamage(800f, 0.5f), 1e-3f, "伤害翻倍 → 周期伤害翻倍")
        assertEquals(tick * 2f, IceShardMirvMath.tickDamage(400f, 1.0f), 1e-3f, "难度比例翻倍 → 周期伤害翻倍")
        assertEquals(0f, IceShardMirvMath.tickDamage(400f, 0f), 1e-4f, "零难度比例 → 零周期伤害")
    }

    @Test
    fun `分裂门控：豁免期内不分裂，豁免期后进入分裂距离才分裂`() {
        val immunity = IceShardMirvDifficulty.SPLIT_IMMUNITY_SECONDS
        val range = IceShardMirvDifficulty.SPLIT_RANGE
        // 豁免期内：即使已贴脸（dist=0）也不分裂
        assertFalse(IceShardMirvMath.canSplit(0f, 0f))
        assertFalse(IceShardMirvMath.canSplit(immunity - 0.01f, 0f))
        // 豁免期满：分裂距离边界含本数
        assertTrue(IceShardMirvMath.canSplit(immunity, range))
        assertTrue(IceShardMirvMath.canSplit(immunity + 2f, range - 1f))
        // 豁免期满但超程：不分裂
        assertFalse(IceShardMirvMath.canSplit(immunity + 2f, range + 0.01f))
    }

    @Test
    fun `附着点换算：任意朝向正反算恒还原命中点`() {
        val shipLoc = Vector2f(1000f, -500f)
        val hitPoint = Vector2f(1080f, -460f)
        // 覆盖正/负/大角度朝向：局部偏移 → 同朝向回算世界坐标必须逐分量还原命中点
        listOf(0f, 45f, 90f, 180f, 270f, 359.9f).forEach { facing ->
            val local = IceShardMirvMath.attachLocalOffset(shipLoc, facing, hitPoint)
            val world = IceShardMirvMath.attachWorldPoint(shipLoc, facing, local)
            assertEquals(hitPoint.x, world.x, 1e-3f, "facing=$facing 回算 x 须还原命中点")
            assertEquals(hitPoint.y, world.y, 1e-3f, "facing=$facing 回算 y 须还原命中点")
        }
    }

    @Test
    fun `附着点换算：舰船转向后附着点随舰体系同步旋转`() {
        val shipLoc = Vector2f(0f, 0f)
        val hitPoint = Vector2f(100f, 0f)
        // 附着时刻舰艏 180°（实机镜像 bug 场景）：局部偏移快照于 180° 基准
        val local = IceShardMirvMath.attachLocalOffset(shipLoc, 180f, hitPoint)
        // 附着瞬间（当前朝向 = 基准朝向）：世界点必须就是命中点，不得镜像到舰体另一侧
        val atAttach = IceShardMirvMath.attachWorldPoint(shipLoc, 180f, local)
        assertEquals(100f, atAttach.x, 1e-3f)
        assertEquals(0f, atAttach.y, 1e-3f)
        // 舰船转向 180°→270°（CCW +90°）：附着点（舰尾方向）随舰体系旋到 (0, +100)
        val afterTurn = IceShardMirvMath.attachWorldPoint(shipLoc, 270f, local)
        assertEquals(0f, afterTurn.x, 1e-3f)
        assertEquals(100f, afterTurn.y, 1e-3f)
    }
}
