package cn.kasuminova.astd.combat.effect.arc.starfallecho

import cn.kasuminova.astd.impl.render.ASTDColor
import cn.kasuminova.astd.impl.render.BloomFlareSpec
import cn.kasuminova.astd.impl.render.BoxFlareStyle
import cn.kasuminova.astd.impl.render.TriShardComponent
import cn.kasuminova.astd.impl.render.TriShardSpec
import cn.kasuminova.astd.api.render.BloomFlareVfx
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import cn.kasuminova.astd.renderer.effect.explosion.BloomFlareVfxImpl
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.EmpArcEntityAPI
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/**
 * 坠星残响的共享配色与爆炸特效（第 5 发命中恒爆发的规模化爆炸，规模 = 层数+1）。
 *
 * 爆炸构成（规格 10-signature 特效节）：
 * - 爆心十字辉星：两枚 SMOOTH_DISC 光柱以 [facingDeg]/[facingDeg]+90° 交叉
 *   （跟随受击点方位：目标舰心 → 命中点的朝向）+ 一枚 SMOOTH 圆形光斑同位叠放，
 *   随爆炸规模扩散并逐渐变淡至消失（[BloomFlareVfx] 绽放辉星通用 API 推进，
 *   FlareEntity 无内建尺寸关键帧）；
 * - 星云：基础 5 片，每 100% 规模（每级）+5 片；单片大小 = 爆炸直径；
 * - 三角碎片：基础 100 片，每 100% 规模 +100 片且尺寸 +5%（走统一粒子池，事件级大批量）；
 * - 电弧：方向恒爆心向边缘，出现位置 10%~20% 半径处，长度 25%~75% 半径，
 *   `setFadedOutAtStart(true)`（先隐后现的爆发帧感）；数量按规模 8+4/级（裁定值，设计案未给）。
 */
object StarfallEchoVfx {

    /** 绽放辉星通用 API（接口持有实现，AGENTS.md 面向接口口径）。 */
    private val bloomFlare: BloomFlareVfx = BloomFlareVfxImpl

    /** 普通弹配色（蓝白）。 */
    val NORMAL_CORE = Color(240, 248, 255)
    val NORMAL_FRINGE = Color(120, 190, 255)

    /** 第 5 发配色（共振红）。 */
    val FINAL_CORE = Color(255, 120, 120)
    val FINAL_FRINGE = Color(255, 60, 60)

    /** 爆心辉星存续（秒）。 */
    private const val CENTER_FLARE_DURATION = 1f

    /** 十字光柱起止长度（爆炸半径倍数）；短轴恒为长轴 ×[CROSS_ASPECT]。 */
    private const val CROSS_LEN_START_RATIO = 0.75f
    private const val CROSS_LEN_END_RATIO = 2f
    private const val CROSS_ASPECT = 0.1f

    /** 中心 SMOOTH 光斑起止直径（爆炸半径倍数）。 */
    private const val CORE_SIZE_START_RATIO = 0.6f
    private const val CORE_SIZE_END_RATIO = 1.5f

    /** 爆心辉星配色：核心近白暖色，辉光共振红（与爆炸配色同族）。 */
    private val FLARE_CORE_COLOR = ASTDColor(0xFFFFECE4)
    private val FLARE_FRINGE_COLOR = ASTDColor(0xFFFF3C3C)

    /**
     * 第 5 发爆炸特效：[stacks] = 消耗的谐振层数（0~4），[radius] = 爆炸半径，
     * [facingDeg] = 受击点方位角（目标舰心 → 命中点，十字光柱以此为基准交叉）。
     * 纯视觉，不含伤害结算（结算在 StarfallEchoOnHitEffect）；0 层 = 基础规模纯视觉爆炸。
     */
    fun explosion(engine: CombatEngineAPI, center: Vector2f, stacks: Int, radius: Float, facingDeg: Float) {
        val scale = stacks.coerceAtLeast(0) + 1
        spawnCenterFlares(engine, center, radius, facingDeg)
        spawnNebula(engine, center, scale, radius)
        spawnShards(engine, center, scale, radius)
        spawnArcs(engine, center, scale, radius)
    }

    /**
     * 爆心十字辉星：两枚 SMOOTH_DISC 光柱以 [facingDeg] 为基准 90° 交叉 +
     * 一枚 SMOOTH 圆形光斑同位叠放，尺寸随爆炸半径取值，由绽放辉星通用 API 推进扩散/变淡/消失。
     */
    private fun spawnCenterFlares(engine: CombatEngineAPI, center: Vector2f, radius: Float, facingDeg: Float) {
        val crossStart = radius * CROSS_LEN_START_RATIO
        val crossEnd = radius * CROSS_LEN_END_RATIO
        val coreStart = radius * CORE_SIZE_START_RATIO
        val coreEnd = radius * CORE_SIZE_END_RATIO
        bloomFlare.spawn(
            engine, center, CENTER_FLARE_DURATION,
            listOf(
                // 十字光柱 ×2：跟随受击点方位交叉
                BloomFlareSpec(
                    BoxFlareStyle.SMOOTH_DISC, crossStart, crossEnd, CROSS_ASPECT, facingDeg,
                    FLARE_CORE_COLOR, FLARE_FRINGE_COLOR,
                ),
                BloomFlareSpec(
                    BoxFlareStyle.SMOOTH_DISC, crossStart, crossEnd, CROSS_ASPECT, facingDeg + 90f,
                    FLARE_CORE_COLOR, FLARE_FRINGE_COLOR,
                ),
                // 中心圆形光斑：低透明度垫底
                BloomFlareSpec(
                    BoxFlareStyle.SMOOTH, coreStart, coreEnd, 1f, 0f,
                    FLARE_CORE_COLOR.scaledAlpha(0.5f), FLARE_FRINGE_COLOR.scaledAlpha(0.1f),
                ),
            ),
            timerFadeIn = 0.1f, timerFull = 0.6f, timerFadeOut = 0.3f,
        )
    }

    /** 星云：5×规模 片，单片大小 = 爆炸直径；整片大尺码下压透明度、拉长淡出保可读性。 */
    private fun spawnNebula(engine: CombatEngineAPI, center: Vector2f, scale: Int, radius: Float) {
        // BoxUtil 星云控制器闲置缺陷旁路（详见 resetNebulaControllerIfIdle 文档）：
        // 每次爆炸事件调一次、在 repeat 喷池之前；严禁逐颗粒调。
        BoxUtilCombatVfx.resetNebulaControllerIfIdle(engine)
        val count = 5 * scale
        repeat(count) {
            val pos = MathUtils.getRandomPointInCircle(center, radius * 0.4f)
            val brighten = MathUtils.getRandomNumberInRange(0f, 1f) < 0.3f
            val base = if (brighten) FINAL_CORE else FINAL_FRINGE
            BoxUtilCombatVfx.addNebulaParticle(
                engine, pos, Vector2f(),
                radius * 1.5f,
                1f, 0.1f, 0.5f,
                MathUtils.getRandomNumberInRange(1.0f, 1.8f),
                Color(base.red, base.green, base.blue, 100),
            )
        }
    }

    /** 三角碎片：100×规模 片，尺寸 ×(1 + 0.05×(规模−1))；自爆心向四周飞散。 */
    private fun spawnShards(engine: CombatEngineAPI, center: Vector2f, scale: Int, radius: Float) {
        val shards = TriShardComponent(
            "astd_starfall_echo_explosion",
            radius * 2f,
            FINAL_CORE,
            FINAL_FRINGE,
            TriShardSpec(batchCount = 2, layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER, timerFadeIn = 0.2f, timerFadeOut = 0.5f)
        )
        val count = 100 * scale
        val sizeScale = 0.75f + 0.05f * (scale - 1)
        repeat(count) {
            val pos = MathUtils.getRandomPointInCircle(center, radius * 0.5f)
            val dir = MathUtils.getRandomNumberInRange(0f, 360f)
            val speed = MathUtils.getRandomNumberInRange(60f, 180f) * (0.75f + 0.25f * scale)
            shards.addShard(0, pos, MathUtils.getPointOnCircumference(Vector2f(), speed, dir), sizeScale)
        }
        shards.activatePendingBatches(engine)
    }

    /** 电弧：方向恒爆心向边缘（径向向外），起点 10%~20% 半径，长度 25%~75% 半径，先隐后现。 */
    private fun spawnArcs(engine: CombatEngineAPI, center: Vector2f, scale: Int, radius: Float) {
        val count = 8 + 4 * (scale - 1)
        repeat(count) {
            val dir = MathUtils.getRandomNumberInRange(0f, 360f)
            val from = MathUtils.getPointOnCircumference(
                center, radius * MathUtils.getRandomNumberInRange(0.1f, 0.2f), dir,
            )
            val to = MathUtils.getPointOnCircumference(
                from, radius * MathUtils.getRandomNumberInRange(0.25f, 0.75f), dir,
            )
            engine.spawnEmpArcVisual(
                from, null, to, null,
                10f + 4f * scale,
                FINAL_FRINGE, FINAL_CORE, EmpArcEntityAPI.EmpArcParams().apply {
                    flickerRateMult = 0.3f * MathUtils.getRandomNumberInRange(0.5f, 1f)
                }
            ).apply {
                setFadedOutAtStart(true)
                setSingleFlickerMode(true)
            }
        }
    }
}
