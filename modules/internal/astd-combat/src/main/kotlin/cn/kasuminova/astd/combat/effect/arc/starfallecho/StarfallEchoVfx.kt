package cn.kasuminova.astd.combat.effect.arc.starfallecho

import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import cn.kasuminova.astd.impl.render.TriShardComponent
import cn.kasuminova.astd.impl.render.TriShardSpec
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/**
 * 坠星残响的共享配色与爆炸特效（第 5 发命中带谐振层目标时的规模化爆发）。
 *
 * 爆炸构成（规格 10-signature 特效节）：
 * - 星云：基础 10 片，每 100% 规模（每层）+5 片；单片大小 = 爆炸直径；
 * - 三角碎片：基础 100 片，每 100% 规模 +100 片且尺寸 +25%（走统一粒子池，事件级大批量）；
 * - 电弧：方向恒爆心向边缘，出现位置 25%~75% 半径处，长度 25%~75% 半径，
 *   `setFadedOutAtStart(true)`（先隐后现的爆发帧感）；数量按规模 4+2/层（裁定值，设计案未给）。
 */
object StarfallEchoVfx {

    /** 普通弹配色（蓝白）。 */
    val NORMAL_CORE = Color(240, 248, 255)
    val NORMAL_FRINGE = Color(120, 190, 255)

    /** 第 5 发配色（共振红）。 */
    val FINAL_CORE = Color(255, 235, 225)
    val FINAL_FRINGE = Color(255, 90, 60)

    /**
     * 第 5 发爆炸特效：[stacks] = 消耗的谐振层数（1~4 = 100%~400% 规模），[radius] = 爆炸半径。
     * 纯视觉，不含伤害结算（结算在 StarfallEchoOnHitEffect）。
     */
    fun explosion(engine: CombatEngineAPI, center: Vector2f, stacks: Int, radius: Float) {
        val scale = stacks.coerceAtLeast(1)
        spawnNebula(engine, center, scale, radius)
        spawnShards(engine, center, scale, radius)
        spawnArcs(engine, center, scale, radius)
    }

    /** 星云：10 + 5×(规模−1) 片，单片大小 = 爆炸直径；整片大尺码下压透明度、拉长淡出保可读性。 */
    private fun spawnNebula(engine: CombatEngineAPI, center: Vector2f, scale: Int, radius: Float) {
        // BoxUtil 星云控制器闲置缺陷旁路（详见 resetNebulaControllerIfIdle 文档）：
        // 每次爆炸事件调一次、在 repeat 喷池之前；严禁逐颗粒调。
        BoxUtilCombatVfx.resetNebulaControllerIfIdle(engine)
        val count = 10 + 5 * (scale - 1)
        repeat(count) {
            val pos = MathUtils.getRandomPointInCircle(center, radius * 0.4f)
            val dir = MathUtils.getRandomNumberInRange(0f, 360f)
            val speed = MathUtils.getRandomNumberInRange(20f, 60f) * scale
            val vel = MathUtils.getPointOnCircumference(Vector2f(), speed, dir)
            val brighten = MathUtils.getRandomNumberInRange(0f, 1f) < 0.3f
            val base = if (brighten) FINAL_CORE else FINAL_FRINGE
            BoxUtilCombatVfx.addNebulaParticle(
                engine, pos, vel,
                radius * 2f,
                1.5f, 0.1f, 0.25f,
                MathUtils.getRandomNumberInRange(1.0f, 1.8f),
                Color(base.red, base.green, base.blue, 90),
            )
        }
    }

    /** 三角碎片：100×规模 片，尺寸 ×(1 + 0.25×(规模−1))；自爆心向四周飞散。 */
    private fun spawnShards(engine: CombatEngineAPI, center: Vector2f, scale: Int, radius: Float) {
        val shards = TriShardComponent(
            "astd_starfall_echo_explosion",
            radius * 2f,
            FINAL_CORE,
            FINAL_FRINGE,
            TriShardSpec(batchCount = 1, layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER),
        )
        val count = 100 * scale
        val sizeScale = 1f + 0.25f * (scale - 1)
        repeat(count) {
            val pos = MathUtils.getRandomPointInCircle(center, radius * 0.3f)
            val dir = MathUtils.getRandomNumberInRange(0f, 360f)
            val speed = MathUtils.getRandomNumberInRange(120f, 360f) * (0.75f + 0.25f * scale)
            shards.addShard(0, pos, MathUtils.getPointOnCircumference(Vector2f(), speed, dir), sizeScale)
        }
        shards.activatePendingBatches(engine)
    }

    /** 电弧：方向恒爆心向边缘（径向向外），起点 25%~75% 半径，长度 25%~75% 半径，先隐后现。 */
    private fun spawnArcs(engine: CombatEngineAPI, center: Vector2f, scale: Int, radius: Float) {
        val count = 4 + 2 * (scale - 1)
        repeat(count) {
            val dir = MathUtils.getRandomNumberInRange(0f, 360f)
            val from = MathUtils.getPointOnCircumference(
                center, radius * MathUtils.getRandomNumberInRange(0.25f, 0.75f), dir,
            )
            val to = MathUtils.getPointOnCircumference(
                from, radius * MathUtils.getRandomNumberInRange(0.25f, 0.75f), dir,
            )
            engine.spawnEmpArcVisual(
                from, null, to, null,
                6f + 2f * scale,
                FINAL_FRINGE, FINAL_CORE,
            ).setFadedOutAtStart(true)
        }
    }
}
