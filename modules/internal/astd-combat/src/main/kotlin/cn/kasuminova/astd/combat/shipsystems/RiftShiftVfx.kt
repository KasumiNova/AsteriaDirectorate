package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.impl.render.TriShardComponent
import cn.kasuminova.astd.impl.render.TriShardSpec
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.sqrt

/**
 * 裂隙折跃的紫色虚空星云裂隙与闭合爆炸特效（规格 blue/10-unique.md XC-002 节特效段，
 * 参考 tools/game-vfx-preview#Void Cutter Beam）。
 *
 * - [riftFrame]：每帧沿已成形裂隙段释放同色星云并向两侧扩散，偶发垂直裂隙的电弧；
 * - [closureBlast]：闭合爆点的小爆发（星云 + 三角碎片 + 径向电弧），构成参考
 *   StarfallEchoVfx.explosion 的缩小版。
 *
 * 纯视觉，不含伤害结算（结算在 RiftShiftSystemStats）。
 */
object RiftShiftVfx {

    /** 裂隙配色（虚空紫）。 */
    val RIFT_CORE = Color(240, 225, 255)
    val RIFT_FRINGE = Color(170, 110, 255)

    /** 星云释放密度：基础 24 片/秒 + 每 20su 裂隙长度追加 1 片/秒（800su 全程 ≈ 64 片/秒）。 */
    private const val NEBULA_BASE_PER_SECOND = 24f
    private const val NEBULA_PER_LENGTH_PER_SECOND = 1f / 20f

    /** 裂隙电弧每帧出现概率（≈5 次/秒 @60fps）。 */
    private const val RIFT_ARC_CHANCE_PER_FRAME = 0.08f

    /**
     * 裂隙逐帧喷放：沿 from→to（已成形段）均匀取采样点，横向抖动后生成星云片，
     * 速度恒垂直裂隙向两侧扩散（「裂隙周围不断释放并扩散同色星云」）。
     */
    fun riftFrame(engine: CombatEngineAPI, from: Vector2f, to: Vector2f, amount: Float) {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val len = sqrt(dx * dx + dy * dy)
        if (len < 1f) return
        val ux = dx / len
        val uy = dy / len

        // 期望片数按帧时长折算，零头概率化取整
        val expected = (NEBULA_BASE_PER_SECOND + len * NEBULA_PER_LENGTH_PER_SECOND) * amount
        var count = expected.toInt()
        if (MathUtils.getRandomNumberInRange(0f, 1f) < expected - count) count++
        // BoxUtil 星云控制器闲置缺陷旁路（详见 resetNebulaControllerIfIdle 文档）：
        // 每个喷发批次（帧）调一次、在喷池之前；严禁逐颗粒调。
        if (count > 0) BoxUtilCombatVfx.resetNebulaControllerIfIdle(engine)
        repeat(count) {
            val t = MathUtils.getRandomNumberInRange(0f, 1f)
            val lateral = MathUtils.getRandomNumberInRange(-1f, 1f) * RiftShiftTuning.RIFT_HALF_WIDTH * 0.6f
            val pos = Vector2f(from.x + dx * t - uy * lateral, from.y + dy * t + ux * lateral)
            val side = if (MathUtils.getRandomNumberInRange(0f, 1f) < 0.5f) 1f else -1f
            val speed = MathUtils.getRandomNumberInRange(12f, 36f)
            val vel = Vector2f(-uy * speed * side, ux * speed * side)
            val brighten = MathUtils.getRandomNumberInRange(0f, 1f) < 0.25f
            val base = if (brighten) RIFT_CORE else RIFT_FRINGE
            BoxUtilCombatVfx.addNebulaParticle(
                engine, pos, vel,
                MathUtils.getRandomNumberInRange(36f, 80f),
                1.6f, 0.12f, 0.3f,
                MathUtils.getRandomNumberInRange(1.0f, 1.8f),
                Color(base.red, base.green, base.blue, MathUtils.getRandomNumberInRange(60, 110)),
            )
        }

        if (MathUtils.getRandomNumberInRange(0f, 1f) < RIFT_ARC_CHANCE_PER_FRAME) {
            val t = MathUtils.getRandomNumberInRange(0f, 1f)
            val fromP = Vector2f(from.x + dx * t, from.y + dy * t)
            val side = if (MathUtils.getRandomNumberInRange(0f, 1f) < 0.5f) 1f else -1f
            val arcLen = MathUtils.getRandomNumberInRange(30f, 80f)
            val toP = Vector2f(fromP.x - uy * arcLen * side, fromP.y + ux * arcLen * side)
            engine.spawnEmpArcVisual(
                fromP, null, toP, null,
                MathUtils.getRandomNumberInRange(3f, 6f),
                RIFT_FRINGE, RIFT_CORE,
            ).setFadedOutAtStart(true)
        }
    }

    /** 闭合爆点小爆发：8 片星云 + 30 片三角碎片 + 4 条径向电弧，半径取 [RiftShiftTuning.BLAST_RADIUS]。 */
    fun closureBlast(engine: CombatEngineAPI, point: Vector2f) {
        val radius = RiftShiftTuning.BLAST_RADIUS
        spawnNebula(engine, point, radius)
        spawnShards(engine, point, radius)
        spawnArcs(engine, point, radius)
    }

    /** 星云：8 片，单片大小 = 爆炸直径；大尺码下压透明度、拉长淡出保可读性。 */
    private fun spawnNebula(engine: CombatEngineAPI, point: Vector2f, radius: Float) {
        // BoxUtil 星云控制器闲置缺陷旁路：每次爆炸事件调一次、在 repeat 喷池之前；严禁逐颗粒调。
        BoxUtilCombatVfx.resetNebulaControllerIfIdle(engine)
        repeat(8) {
            val pos = MathUtils.getRandomPointInCircle(point, radius * 0.4f)
            val dir = MathUtils.getRandomNumberInRange(0f, 360f)
            val speed = MathUtils.getRandomNumberInRange(20f, 60f)
            val vel = MathUtils.getPointOnCircumference(Vector2f(), speed, dir)
            val brighten = MathUtils.getRandomNumberInRange(0f, 1f) < 0.3f
            val base = if (brighten) RIFT_CORE else RIFT_FRINGE
            BoxUtilCombatVfx.addNebulaParticle(
                engine, pos, vel,
                radius * 2f,
                1.5f, 0.1f, 0.25f,
                MathUtils.getRandomNumberInRange(0.8f, 1.4f),
                Color(base.red, base.green, base.blue, 90),
            )
        }
    }

    /** 三角碎片：30 片自爆心向四周飞散（统一粒子池，事件级批量）。 */
    private fun spawnShards(engine: CombatEngineAPI, point: Vector2f, radius: Float) {
        val shards = TriShardComponent(
            "astd_rift_shift_closure",
            radius * 2f,
            RIFT_CORE,
            RIFT_FRINGE,
            TriShardSpec(batchCount = 1, layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER),
        )
        repeat(30) {
            val pos = MathUtils.getRandomPointInCircle(point, radius * 0.3f)
            val dir = MathUtils.getRandomNumberInRange(0f, 360f)
            val speed = MathUtils.getRandomNumberInRange(120f, 300f)
            shards.addShard(0, pos, MathUtils.getPointOnCircumference(Vector2f(), speed, dir), 1f)
        }
        shards.activatePendingBatches(engine)
    }

    /** 电弧：方向恒爆心向边缘（径向向外），起点/长度 25%~75% 半径，先隐后现。 */
    private fun spawnArcs(engine: CombatEngineAPI, point: Vector2f, radius: Float) {
        repeat(4) {
            val dir = MathUtils.getRandomNumberInRange(0f, 360f)
            val from = MathUtils.getPointOnCircumference(
                point, radius * MathUtils.getRandomNumberInRange(0.25f, 0.75f), dir,
            )
            val to = MathUtils.getPointOnCircumference(
                from, radius * MathUtils.getRandomNumberInRange(0.25f, 0.75f), dir,
            )
            engine.spawnEmpArcVisual(from, null, to, null, 6f, RIFT_FRINGE, RIFT_CORE)
                .setFadedOutAtStart(true)
        }
    }
}
