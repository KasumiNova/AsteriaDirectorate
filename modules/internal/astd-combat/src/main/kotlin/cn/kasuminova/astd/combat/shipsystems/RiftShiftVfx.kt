package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.api.AstdLog
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import org.boxutil.units.standard.entity.FlareEntity
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.sqrt

object RiftShiftVfx {

    val RIFT_CORE = Color(160, 150, 255)
    val RIFT_FRINGE = Color(100, 80, 255)

    private const val NEBULA_BASE_PER_SECOND = 24f
    private const val NEBULA_PER_LENGTH_PER_SECOND = 1f / 20f

    private const val NEBULA_LATERAL_SPREAD = 24f

    private const val RIFT_ARC_CHANCE_PER_FRAME = 0.08f

    private const val BODY_FADE_IN_SECONDS = 0.2f

    private const val VOID_CORE_EXTRA_LENGTH = 60f
    private const val VOID_CORE_HEIGHT = 96f
    private const val VOID_CORE_ALPHA = 0.85f

    private const val EDGE_HOT_HEIGHT_MIN = 8f
    private const val EDGE_HOT_HEIGHT_MAX = 14f
    private const val EDGE_HOT_JITTER = 5f

    private const val EDGE_GLOW_HEIGHT = 44f
    private const val EDGE_GLOW_ALPHA = 0.55f

    private val VOID_COLOR = Color(8, 4, 18, 255)

    private val EDGE_GLOW_FRINGE = Color(120, 60, 220)

    private class RiftBody(
        val voidCore: FlareEntity,
        val edgeHot: FlareEntity,
        val edgeGlow: FlareEntity,
    )

    private const val KEY_BODIES = "astd_rift_shift_bodies"

    // addEntity 失败是系统性故障，置位后不再逐帧重试刷屏
    private const val KEY_BODIES_DISABLED = "astd_rift_shift_bodies_disabled"

    private val log = AstdLog.logger

    fun riftBodyFrame(engine: CombatEngineAPI, riftKey: String, from: Vector2f, to: Vector2f, intensity: Float) {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val len = sqrt(dx * dx + dy * dy)
        if (len < 1f) return
        val angle = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
        val facing = BoxUtilCombatVfx.normalizeFacingDeg(angle)
        val mid = Vector2f((from.x + to.x) * 0.5f, (from.y + to.y) * 0.5f)
        val ux = dx / len
        val uy = dy / len

        val bodies = engine.customData.getOrPut(KEY_BODIES) { HashMap<String, RiftBody>() } as MutableMap<String, RiftBody>
        var body = bodies[riftKey]
        if (body == null) {
            if (engine.customData[KEY_BODIES_DISABLED] == true) return
            body = createRiftBody(engine)
            if (body == null) {
                engine.customData[KEY_BODIES_DISABLED] = true
                return
            }
            bodies[riftKey] = body
        }

        body.voidCore.setStateVanilla(mid, facing)
        body.voidCore.setSize(len + VOID_CORE_EXTRA_LENGTH, VOID_CORE_HEIGHT)
        body.voidCore.globalAlpha = VOID_CORE_ALPHA * intensity

        val jitter = MathUtils.getRandomNumberInRange(-EDGE_HOT_JITTER, EDGE_HOT_JITTER)
        val hotPos = Vector2f(mid.x - uy * jitter, mid.y + ux * jitter)
        body.edgeHot.setStateVanilla(hotPos, facing)
        body.edgeHot.setSize(len, MathUtils.getRandomNumberInRange(EDGE_HOT_HEIGHT_MIN, EDGE_HOT_HEIGHT_MAX))
        body.edgeHot.autoAspect()
        body.edgeHot.globalAlpha = MathUtils.getRandomNumberInRange(0.7f, 1f) * intensity

        body.edgeGlow.setStateVanilla(mid, facing)
        body.edgeGlow.setSize(len, EDGE_GLOW_HEIGHT)
        body.edgeGlow.autoAspect()
        body.edgeGlow.globalAlpha = EDGE_GLOW_ALPHA * intensity
    }

    fun closeRiftBody(engine: CombatEngineAPI, riftKey: String) {
        val bodies = engine.customData[KEY_BODIES] as? MutableMap<String, RiftBody> ?: return
        val body = bodies.remove(riftKey) ?: return
        body.voidCore.delete()
        body.edgeHot.delete()
        body.edgeGlow.delete()
        if (bodies.isEmpty()) engine.customData.remove(KEY_BODIES)
    }

    fun bodyIntensity(riftAgeSeconds: Float): Float =
        (riftAgeSeconds / BODY_FADE_IN_SECONDS).coerceIn(0f, 1f)

    private fun createRiftBody(engine: CombatEngineAPI): RiftBody? {
        BoxUtilCombatVfx.ensureReady(engine)
        val voidCore = createBodyFlare("暗核") ?: return null
        val edgeHot = createBodyFlare("热缘")
        if (edgeHot == null) {
            voidCore.delete()
            return null
        }
        val edgeGlow = createBodyFlare("外鞘")
        if (edgeGlow == null) {
            voidCore.delete()
            edgeHot.delete()
            return null
        }

        voidCore.setLayer(CombatEngineLayers.BELOW_SHIPS_LAYER)
        voidCore.setNormalBlend()
        voidCore.setSmooth()
        voidCore.setCoreColor(VOID_COLOR)
        voidCore.setFringeColor(VOID_COLOR)
        voidCore.glowPower = 0f
        voidCore.noisePower = 0.15f

        edgeHot.setLayer(CombatEngineLayers.ABOVE_SHIPS_LAYER)
        edgeHot.setAdditiveBlend()
        edgeHot.setSharpDisc()
        edgeHot.setCoreColor(RIFT_CORE)
        edgeHot.setFringeColor(RIFT_FRINGE)
        edgeHot.glowPower = 0.6f
        edgeHot.noisePower = 0.35f

        edgeGlow.setLayer(CombatEngineLayers.ABOVE_SHIPS_LAYER)
        edgeGlow.setAdditiveBlend()
        edgeGlow.setSmoothDisc()
        edgeGlow.setCoreColor(RIFT_FRINGE)
        edgeGlow.setFringeColor(EDGE_GLOW_FRINGE)
        edgeGlow.glowPower = 0.3f
        edgeGlow.noisePower = 0.25f

        // addEntity 按 getLayer() 路由，注册必须在图层与样式配置之后
        if (!registerBodyFlare(engine, voidCore, "暗核")) {
            edgeHot.delete()
            edgeGlow.delete()
            return null
        }
        if (!registerBodyFlare(engine, edgeHot, "热缘")) {
            voidCore.delete()
            edgeGlow.delete()
            return null
        }
        if (!registerBodyFlare(engine, edgeGlow, "外鞘")) {
            voidCore.delete()
            edgeHot.delete()
            return null
        }
        return RiftBody(voidCore, edgeHot, edgeGlow)
    }

    // 常驻计时器钉超长 full，生命周期由裂隙状态显式 delete
    private fun createBodyFlare(tag: String): FlareEntity? {
        val entity = try {
            FlareEntity()
        } catch (t: Throwable) {
            log.warn("[ASTD] 裂隙折跃本体$tag 建实体失败（${t.javaClass.simpleName}），本次裂隙该层缺席", t)
            return null
        }
        entity.isFlick = false
        entity.setGlobalTimer(0f, 1e7f, 0f)
        entity.setSize(1f, 1f)
        entity.globalAlpha = 0f
        return entity
    }

    private fun registerBodyFlare(engine: CombatEngineAPI, entity: FlareEntity, tag: String): Boolean {
        val state = BoxUtilCombatVfx.addEntity(engine, entity)
        if (state != 0) {
            log.warn("[ASTD] 裂隙折跃本体$tag 注册失败（addEntity 返回 $state），本次裂隙该层缺席")
            entity.delete()
            return false
        }
        return true
    }

    fun riftFrame(engine: CombatEngineAPI, from: Vector2f, to: Vector2f, amount: Float) {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val len = sqrt(dx * dx + dy * dy)
        if (len < 1f) return
        val ux = dx / len
        val uy = dy / len

        val expected = (NEBULA_BASE_PER_SECOND + len * NEBULA_PER_LENGTH_PER_SECOND) * amount
        var count = expected.toInt()
        if (MathUtils.getRandomNumberInRange(0f, 1f) < expected - count) count++
        // BoxUtil 星云控制器闲置缺陷旁路：每个喷发批次调一次且在喷池之前，严禁逐颗粒调
        if (count > 0) BoxUtilCombatVfx.resetNebulaControllerIfIdle(engine)
        repeat(count) {
            val t = MathUtils.getRandomNumberInRange(0f, 1f)
            val lateral = MathUtils.getRandomNumberInRange(-1f, 1f) * NEBULA_LATERAL_SPREAD
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
                Color(base.red, base.green, base.blue, MathUtils.getRandomNumberInRange(100, 200)),
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
}
