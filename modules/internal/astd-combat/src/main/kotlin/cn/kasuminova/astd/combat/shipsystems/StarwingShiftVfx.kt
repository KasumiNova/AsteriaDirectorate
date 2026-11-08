package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.api.AstdLog
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import org.boxutil.units.standard.entity.FlareEntity
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/**
 * 星翼折跃的一次性视觉：切入边沿沿滑行路径铺一条星云+电弧的"折跃轨迹"，
 * 现身边沿在落点打一枚紫红光斑与放射电弧。全程无常驻实体，不随帧驱动。
 */
object StarwingShiftVfx {

    val RIFT_CORE = Color(160, 150, 255)
    val RIFT_FRINGE = Color(100, 80, 255)

    private const val NEBULA_BASE = 24f
    private const val NEBULA_PER_LENGTH = 1f / 16f
    private const val NEBULA_LATERAL_SPREAD = 24f

    private const val PATH_ARC_COUNT = 6

    private const val ENTRY_FLARE_SIZE = 90f
    private const val EMERGE_FLARE_SIZE = 130f

    private const val EMERGE_ARC_COUNT = 5
    private const val EMERGE_NEBULA_COUNT = 18

    private val log = AstdLog.logger

    /** 切入边沿：沿 start→start+dir*dist 铺一次性轨迹特效。 */
    fun burstAlongPath(engine: CombatEngineAPI, start: Vector2f, dir: Vector2f, dist: Float) {
        if (dist < 1f) return
        val ux = dir.x
        val uy = dir.y

        val expected = NEBULA_BASE + dist * NEBULA_PER_LENGTH
        var count = expected.toInt()
        if (MathUtils.getRandomNumberInRange(0f, 1f) < expected - count) count++
        // BoxUtil 星云控制器闲置缺陷旁路：每个喷发批次调一次且在喷池之前，严禁逐颗粒调
        if (count > 0) BoxUtilCombatVfx.resetNebulaControllerIfIdle(engine)
        repeat(count) {
            val t = MathUtils.getRandomNumberInRange(0f, 1f)
            val lateral = MathUtils.getRandomNumberInRange(-1f, 1f) * NEBULA_LATERAL_SPREAD
            val pos = Vector2f(start.x + ux * dist * t - uy * lateral, start.y + uy * dist * t + ux * lateral)
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
                Color(base.red, base.green, base.blue, MathUtils.getRandomNumberInRange(100, 200)),
            )
        }

        repeat(PATH_ARC_COUNT) {
            val t = MathUtils.getRandomNumberInRange(0f, 1f)
            val fromP = Vector2f(start.x + ux * dist * t, start.y + uy * dist * t)
            val side = if (MathUtils.getRandomNumberInRange(0f, 1f) < 0.5f) 1f else -1f
            val arcLen = MathUtils.getRandomNumberInRange(30f, 80f)
            val toP = Vector2f(fromP.x - uy * arcLen * side, fromP.y + ux * arcLen * side)
            engine.spawnEmpArcVisual(
                fromP, null, toP, null,
                MathUtils.getRandomNumberInRange(3f, 6f),
                RIFT_FRINGE, RIFT_CORE,
            ).setFadedOutAtStart(true)
        }

        spawnPulseFlare(engine, start, ENTRY_FLARE_SIZE)
    }

    /** 现身边沿：落点光斑 + 放射电弧 + 小团星云。 */
    fun emergePulse(engine: CombatEngineAPI, at: Vector2f) {
        spawnPulseFlare(engine, at, EMERGE_FLARE_SIZE)

        repeat(EMERGE_ARC_COUNT) {
            val angle = Math.toRadians(MathUtils.getRandomNumberInRange(0f, 360f).toDouble())
            val arcLen = MathUtils.getRandomNumberInRange(50f, 110f)
            val toP = Vector2f(
                at.x + (kotlin.math.cos(angle) * arcLen).toFloat(),
                at.y + (kotlin.math.sin(angle) * arcLen).toFloat(),
            )
            engine.spawnEmpArcVisual(
                at, null, toP, null,
                MathUtils.getRandomNumberInRange(4f, 8f),
                RIFT_FRINGE, RIFT_CORE,
            ).setFadedOutAtStart(true)
        }

        BoxUtilCombatVfx.resetNebulaControllerIfIdle(engine)
        repeat(EMERGE_NEBULA_COUNT) {
            val angle = Math.toRadians(MathUtils.getRandomNumberInRange(0f, 360f).toDouble())
            val radius = MathUtils.getRandomNumberInRange(0f, 40f)
            val pos = Vector2f(
                at.x + (kotlin.math.cos(angle) * radius).toFloat(),
                at.y + (kotlin.math.sin(angle) * radius).toFloat(),
            )
            val speed = MathUtils.getRandomNumberInRange(20f, 60f)
            val vel = Vector2f((kotlin.math.cos(angle) * speed).toFloat(), (kotlin.math.sin(angle) * speed).toFloat())
            val base = if (MathUtils.getRandomNumberInRange(0f, 1f) < 0.3f) RIFT_CORE else RIFT_FRINGE
            BoxUtilCombatVfx.addNebulaParticle(
                engine, pos, vel,
                MathUtils.getRandomNumberInRange(30f, 64f),
                1.4f, 0.10f, 0.25f,
                MathUtils.getRandomNumberInRange(0.8f, 1.4f),
                Color(base.red, base.green, base.blue, MathUtils.getRandomNumberInRange(110, 200)),
            )
        }
    }

    private fun spawnPulseFlare(engine: CombatEngineAPI, at: Vector2f, size: Float) {
        BoxUtilCombatVfx.ensureReady(engine)
        val flare = try {
            FlareEntity()
        } catch (t: Throwable) {
            log.warn("[ASTD] 星翼折跃光斑建实体失败（${t.javaClass.simpleName}），本次光斑缺席", t)
            return
        }
        flare.setLayer(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER)
        flare.setAdditiveBlend()
        flare.setSmooth()
        flare.isFlick = false
        // 不喂 bloom：additive 光斑 glow 叠 bloom 会过曝
        flare.glowPower = 0f
        flare.noisePower = 0.25f
        // 用 Color 重载：BoxUtil 的 setCoreColor(float,float,float,float) 有源码 bug（误写 fringe 槽位）
        flare.setCoreColor(RIFT_CORE)
        flare.setFringeColor(RIFT_FRINGE)
        flare.setSize(size, size)
        flare.setGlobalTimer(0.03f, 0.10f, 0.35f)
        flare.setStateVanilla(Vector2f(at), 0f)
        val addState = BoxUtilCombatVfx.addEntity(engine, flare)
        if (addState != 0) {
            log.warn("[ASTD] 星翼折跃光斑注册失败（addEntity 返回 $addState），本次光斑缺席")
            flare.delete()
        }
    }
}
