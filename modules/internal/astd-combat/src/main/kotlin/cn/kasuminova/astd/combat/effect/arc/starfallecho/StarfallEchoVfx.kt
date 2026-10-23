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

object StarfallEchoVfx {

    private val bloomFlare: BloomFlareVfx = BloomFlareVfxImpl

    val NORMAL_CORE = Color(240, 248, 255)
    val NORMAL_FRINGE = Color(120, 190, 255)

    val FINAL_CORE = Color(255, 120, 120)
    val FINAL_FRINGE = Color(255, 60, 60)

    private const val CENTER_FLARE_DURATION = 1f

    private const val CROSS_LEN_START_RATIO = 0.75f
    private const val CROSS_LEN_END_RATIO = 2f
    private const val CROSS_ASPECT = 0.1f

    private const val CORE_SIZE_START_RATIO = 0.6f
    private const val CORE_SIZE_END_RATIO = 1.5f

    private val FLARE_CORE_COLOR = ASTDColor(0xFFFFECE4)
    private val FLARE_FRINGE_COLOR = ASTDColor(0xFFFF3C3C)

    fun explosion(engine: CombatEngineAPI, center: Vector2f, stacks: Int, radius: Float, facingDeg: Float) {
        val scale = stacks.coerceAtLeast(0) + 1
        spawnCenterFlares(engine, center, radius, facingDeg)
        spawnNebula(engine, center, scale, radius)
        spawnShards(engine, center, scale, radius)
        spawnArcs(engine, center, scale, radius)
    }

    private fun spawnCenterFlares(engine: CombatEngineAPI, center: Vector2f, radius: Float, facingDeg: Float) {
        val crossStart = radius * CROSS_LEN_START_RATIO
        val crossEnd = radius * CROSS_LEN_END_RATIO
        val coreStart = radius * CORE_SIZE_START_RATIO
        val coreEnd = radius * CORE_SIZE_END_RATIO
        bloomFlare.spawn(
            engine, center, CENTER_FLARE_DURATION,
            listOf(
                BloomFlareSpec(
                    BoxFlareStyle.SMOOTH_DISC, crossStart, crossEnd, CROSS_ASPECT, facingDeg,
                    FLARE_CORE_COLOR, FLARE_FRINGE_COLOR,
                ),
                BloomFlareSpec(
                    BoxFlareStyle.SMOOTH_DISC, crossStart, crossEnd, CROSS_ASPECT, facingDeg + 90f,
                    FLARE_CORE_COLOR, FLARE_FRINGE_COLOR,
                ),
                BloomFlareSpec(
                    BoxFlareStyle.SMOOTH, coreStart, coreEnd, 1f, 0f,
                    FLARE_CORE_COLOR.scaledAlpha(0.5f), FLARE_FRINGE_COLOR.scaledAlpha(0.1f),
                ),
            ),
            timerFadeIn = 0.1f, timerFull = 0.6f, timerFadeOut = 0.3f,
        )
    }

    private fun spawnNebula(engine: CombatEngineAPI, center: Vector2f, scale: Int, radius: Float) {
        // BoxUtil 星云控制器闲置缺陷旁路：每次爆炸事件调一次，禁止逐颗粒调。
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
