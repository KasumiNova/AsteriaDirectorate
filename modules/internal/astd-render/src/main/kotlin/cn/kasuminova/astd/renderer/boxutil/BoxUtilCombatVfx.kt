package cn.kasuminova.astd.renderer.boxutil

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.graphics.SpriteAPI
import org.boxutil.BoxUtilModPlugin
import org.boxutil.base.api.RenderDataAPI
import org.boxutil.base.api.resource.TemporaryCleanupPlugin
import org.boxutil.define.BoxEnum
import org.boxutil.manager.CombatRenderingManager
import org.boxutil.units.standard.entity.TrailEntity
import org.boxutil.util.RenderingUtil
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/**
 * BoxUtil 的 `CombatRenderingManager.addEntity` 是静态全局注册、无 engine 作用域，BoxUtil 对
 * renderEntityMap 的跨战斗清理不可靠，addEntity 成功的实体必须全部入 [entityRegistry] 登记，
 * 由战斗切换时的 purge 统一删除。
 */
object BoxUtilCombatVfx {

    private const val KEY_LATER_INIT = "astd_boxutil_later_init"
    private const val KEY_INVITED_CRM = "astd_boxutil_invited_combat_rendering_manager"
    private const val KEY_LOG_ADD_ENTITY_FAIL_ONCE = "astd_boxutil_add_entity_fail_once"
    private const val KEY_LOG_NEBULA_FAIL_ONCE = "astd_boxutil_nebula_fail_once"

    private val log = Global.getLogger(BoxUtilCombatVfx::class.java)

    private val entityRegistry = CombatEntityRegistry<RenderDataAPI>({ it.hasDelete() }, { it.delete() })

    private var registryEngine: CombatEngineAPI? = null

    private val cleanupHook = CombatCleanupHook()

    @Volatile
    private var cleanupHookInstalled = false

    /**
     * 归一化朝向到 [0, 360)：BoxUtil `TrigUtil.sinFormCosF` 反推半角 sin 时只按 `angle > 180`
     * 修正符号，负角度会得到镜像朝向，所有进入 BoxUtil 实体变换的朝向必须先过本函数。
     */
    fun normalizeFacingDeg(deg: Float): Float = ((deg % 360f) + 360f) % 360f

    fun ensureReady(engine: CombatEngineAPI) {
        purgeOnEngineSwitch(engine)
        if (engine.customData[KEY_LATER_INIT] == true) return
        // isGlobalInitialized 首次触碰会触发 BoxConfigGUI 类初始化，无 GL 环境直接抛 ExceptionInInitializerError
        val initialized = try {
            if (!BoxUtilModPlugin.isGlobalInitialized()) {
                BoxUtilModPlugin.initLater()
            }
            BoxUtilModPlugin.isGlobalInitialized()
        } catch (t: Throwable) {
            log.warn("BoxUtil 初始化不可用（${t.javaClass.simpleName}），BoxUtil VFX 将暂时不可用", t)
            return
        }
        if (initialized) {
            engine.customData[KEY_LATER_INIT] = true
            installCleanupHookOnce()
        }
    }

    private fun purgeOnEngineSwitch(engine: CombatEngineAPI) {
        val previous = registryEngine
        if (previous === engine) return
        registryEngine = engine
        if (previous != null) purgeRegistry("engine 实例切换兜底")
    }

    private fun purgeRegistry(reason: String) {
        if (entityRegistry.size == 0) return
        val result = entityRegistry.purge()
        val message = "[ASTD] BoxUtil 战斗实体登记簿 purge（$reason）：登记 ${result.registered}，" +
            "delete ${result.deleted}，已失效 ${result.alreadyDeleted}，失败 ${result.failures}"
        if (result.failures > 0) {
            val first = result.firstFailure
            log.warn("$message；首个异常 ${first?.javaClass?.simpleName}: ${first?.message}（失败条目留簿待下轮重试）", first)
        } else {
            log.info(message)
        }
    }

    private fun installCleanupHookOnce() {
        if (cleanupHookInstalled) return
        cleanupHookInstalled = true
        CombatRenderingManager.addCleanupPlugin(cleanupHook)
    }

    /** BoxUtil `cleanupAllQueue` 回调后会清空钩子集（一次性语义），每次回调末尾必须重新登记。 */
    private class CombatCleanupHook : TemporaryCleanupPlugin {
        override fun cleanupCombatOnce() {
            purgeRegistry("cleanupCombatOnce")
            CombatRenderingManager.addCleanupPlugin(this)
        }
    }

    private fun inviteCombatRenderingManagerIfNeeded(engine: CombatEngineAPI) {
        if (engine.customData[KEY_INVITED_CRM] == true) return
        ensureReady(engine)
        engine.customData[KEY_INVITED_CRM] = true
    }

    fun addEntity(engine: CombatEngineAPI, entity: RenderDataAPI): Int {
        purgeOnEngineSwitch(engine)
        var state = CombatRenderingManager.addEntity(entity).toInt()
        if (state != 0) {
            inviteCombatRenderingManagerIfNeeded(engine)
            state = CombatRenderingManager.addEntity(entity).toInt()
        }
        if (state == 0) entityRegistry.register(entity)
        return state
    }

    fun removeEntity(entity: RenderDataAPI) {
        if (entity.hasDelete()) {
            entityRegistry.unregister(entity)
            return
        }
        try {
            entity.delete()
        } catch (t: Throwable) {
            log.warn("BoxUtil 实体 delete 失败（${t.javaClass.simpleName}: ${t.message}），条目留簿等待 purge 重试", t)
            return
        }
        entityRegistry.unregister(entity)
    }

    fun addNebulaParticle(
        engine: CombatEngineAPI,
        location: Vector2f,
        velocity: Vector2f,
        size: Float,
        endSizeMult: Float,
        rampUpFraction: Float,
        fullBrightnessFraction: Float,
        totalDuration: Float,
        color: Color,
    ): Boolean {
        ensureReady(engine)
        val ok = RenderingUtil.VanillaFX.addNebulaParticle(
            false, location, velocity, size, endSizeMult, rampUpFraction, fullBrightnessFraction, totalDuration, color,
        )
        if (!ok && engine.customData[KEY_LOG_NEBULA_FAIL_ONCE] != true) {
            engine.customData[KEY_LOG_NEBULA_FAIL_ONCE] = true
            log.warn("BoxUtil 星云粒子渲染失败（shader 未启用或实例池满），本场战斗后续同类失败不再重复告警")
        }
        return ok
    }

    /**
     * 星云控制器闲置重置（BoxUtil 上游缺陷旁路，勿删）。`SimpleParticleControlData.clearParticles()`
     * 只复位 `state[0]/[1]/[2]`，漏复位提交游标 `state[4]`；闲置清池后若下一批粒子数恰好等于残留
     * `state[4]`，提交块被整体跳过，该批及之后同数量批次永久不可见。闲置（renderingCount == 0）时
     * delete 旧控制器实体即可由 Box 的 `isEntityExpired()` 探测重建归零。必须每次爆炸喷第一批粒子前
     * 调一次，严禁逐颗粒调用（renderingCount 在提交前恒 0，会把本批刚喷入的池误删）。
     */
    fun resetNebulaControllerIfIdle(engine: CombatEngineAPI) {
        ensureReady(engine)
        val controller = try {
            RenderingUtil.VanillaFX.Controllers.getNebulaParticle(false)
        } catch (t: Throwable) {
            log.warn("BoxUtil 星云控制器获取失败（${t.javaClass.simpleName}），本次闲置重置跳过", t)
            return
        }
        val entity = controller.entity ?: return
        if (entity.renderingCount > 0) return
        val renderEntity = entity as? RenderDataAPI
        if (renderEntity == null) {
            log.warn("BoxUtil 星云控制器实体未实现 RenderDataAPI（${entity.javaClass.simpleName}），本次闲置重置跳过")
            return
        }
        if (!renderEntity.hasDelete()) renderEntity.delete()
    }

    fun createTaperedBeamTrail(
        location: Vector2f,
        facing: Float,
        length: Float,
        tailWidth: Float,
        headWidth: Float,
        coreColor: Color,
        fringeColor: Color,
        coreSprite: SpriteAPI,
        fringeSprite: SpriteAPI,
        layer: CombatEngineLayers,
        full: Float,
        tailAlphaMul: Float,
        headAlphaMul: Float,
        tailEmissiveAlphaMul: Float,
        headEmissiveAlphaMul: Float,
        mixPower: Float,
    ): TrailEntity {
        val entity = RenderingUtil.createBeamVisual(
            location,
            normalizeFacingDeg(facing),
            length,
            headWidth,
            coreColor,
            fringeColor,
            coreSprite,
            fringeSprite,
            0f,
            full,
            0f,
            true,
        )

        entity.setLayer(layer)

        // TrailEntity 的 START_* / startWidth 作用于 node[0]（createBeamVisual 中位于 +length 端），END_* / endWidth 作用于末节点
        entity.startWidth = tailWidth
        entity.endWidth = headWidth
        entity.mixFactor = mixPower

        entity.setStartColor(1f, 1f, 1f, tailAlphaMul)
        entity.setEndColor(1f, 1f, 1f, headAlphaMul)
        entity.setStartEmissive(1f, 1f, 1f, tailEmissiveAlphaMul)
        entity.setEndEmissive(1f, 1f, 1f, headEmissiveAlphaMul)

        val mat = entity.materialData
        mat.alphaToEmissive = 0f
        mat.isColorToEmissive = 0f
        mat.glowPower = 1f
        mat.setColor(coreColor)
        mat.setEmissiveColor(fringeColor)

        return entity
    }

    fun createTaperedBeamTrailFromCenter(
        location: Vector2f,
        facing: Float,
        length: Float,
        baseWidth: Float,
        tipWidth: Float,
        coreColor: Color,
        fringeColor: Color,
        coreSprite: SpriteAPI,
        fringeSprite: SpriteAPI,
        layer: CombatEngineLayers,
        full: Float,
        baseAlphaMul: Float,
        tipAlphaMul: Float,
        baseEmissiveAlphaMul: Float,
        tipEmissiveAlphaMul: Float,
        mixPower: Float,
    ): TrailEntity {
        val entity = TrailEntity()
        entity.addNode(Vector2f(0f, 0f))
        entity.addNode(Vector2f(length, 0f))
        entity.submitNodes()

        entity.setLayer(layer)
        entity.setAdditiveBlend()

        // BoxUtil 会把 once 定时器实体渲染一帧后移除，常驻必须用长 full
        entity.setGlobalTimer(0f, full.coerceAtLeast(0.01f), 0f)

        entity.startWidth = baseWidth
        entity.endWidth = tipWidth
        entity.mixFactor = mixPower

        entity.setStartColor(1f, 1f, 1f, baseAlphaMul)
        entity.setEndColor(1f, 1f, 1f, tipAlphaMul)
        entity.setStartEmissive(1f, 1f, 1f, baseEmissiveAlphaMul)
        entity.setEndEmissive(1f, 1f, 1f, tipEmissiveAlphaMul)

        val mat = entity.materialData
        mat.alphaToEmissive = 0f
        mat.isColorToEmissive = 0f
        mat.glowPower = 1f
        mat.setColor(coreColor)
        mat.setEmissiveColor(fringeColor)
        mat.setDiffuse(coreSprite)
        mat.setEmissive(fringeSprite)

        entity.setStateVanilla(location, normalizeFacingDeg(facing))
        return entity
    }

    fun createTaperedBeamTrailFromCenterReversedU(
        location: Vector2f,
        facing: Float,
        length: Float,
        baseWidth: Float,
        tipWidth: Float,
        coreColor: Color,
        fringeColor: Color,
        coreSprite: SpriteAPI,
        fringeSprite: SpriteAPI,
        layer: CombatEngineLayers,
        full: Float,
        baseAlphaMul: Float,
        tipAlphaMul: Float,
        baseEmissiveAlphaMul: Float,
        tipEmissiveAlphaMul: Float,
        mixPower: Float,
    ): TrailEntity {
        val entity = TrailEntity()
        entity.addNode(Vector2f(length, 0f))
        entity.addNode(Vector2f(0f, 0f))
        entity.submitNodes()

        entity.setLayer(layer)
        entity.setAdditiveBlend()
        entity.setGlobalTimer(0f, full.coerceAtLeast(0.01f), 0f)

        entity.startWidth = tipWidth
        entity.endWidth = baseWidth
        entity.mixFactor = mixPower

        entity.setStartColor(1f, 1f, 1f, tipAlphaMul)
        entity.setEndColor(1f, 1f, 1f, baseAlphaMul)
        entity.setStartEmissive(1f, 1f, 1f, tipEmissiveAlphaMul)
        entity.setEndEmissive(1f, 1f, 1f, baseEmissiveAlphaMul)

        val mat = entity.materialData
        mat.alphaToEmissive = 0f
        mat.isColorToEmissive = 0f
        mat.glowPower = 1f
        mat.setColor(coreColor)
        mat.setEmissiveColor(fringeColor)
        mat.setDiffuse(coreSprite)
        mat.setEmissive(fringeSprite)

        entity.setStateVanilla(location, normalizeFacingDeg(facing))
        return entity
    }

    fun createAndAddTaperedBeamTrail(
        engine: CombatEngineAPI,
        location: Vector2f,
        facing: Float,
        length: Float,
        tailWidth: Float,
        headWidth: Float,
        coreColor: Color,
        fringeColor: Color,
        coreSprite: SpriteAPI,
        fringeSprite: SpriteAPI,
        layer: CombatEngineLayers,
        full: Float,
        tailAlphaMul: Float,
        headAlphaMul: Float,
        tailEmissiveAlphaMul: Float,
        headEmissiveAlphaMul: Float,
        mixPower: Float,
    ): TrailEntity? {
        ensureReady(engine)

        val entity = createTaperedBeamTrail(
            location,
            facing,
            length,
            tailWidth,
            headWidth,
            coreColor,
            fringeColor,
            coreSprite,
            fringeSprite,
            layer,
            full,
            tailAlphaMul,
            headAlphaMul,
            tailEmissiveAlphaMul,
            headEmissiveAlphaMul,
            mixPower,
        )

        val state = try {
            addEntity(engine, entity)
        } catch (t: Throwable) {
            if (engine.customData[KEY_LOG_ADD_ENTITY_FAIL_ONCE] != true) {
                engine.customData[KEY_LOG_ADD_ENTITY_FAIL_ONCE] = true
                log.warn("BoxUtil addEntity threw exception (target=${BoxEnum.ENTITY_TRAIL})", t)
            }
            -1
        }

        if (state != 0) {
            if (engine.customData[KEY_LOG_ADD_ENTITY_FAIL_ONCE] != true) {
                engine.customData[KEY_LOG_ADD_ENTITY_FAIL_ONCE] = true
                log.warn("BoxUtil addEntity failed (state=$state, target=${BoxEnum.ENTITY_TRAIL}). BoxUtil VFX entity was deleted.")
            }
            entity.delete()
            return null
        }

        return entity
    }

    fun createAndAddTaperedBeamTrailFromCenter(
        engine: CombatEngineAPI,
        location: Vector2f,
        facing: Float,
        length: Float,
        baseWidth: Float,
        tipWidth: Float,
        coreColor: Color,
        fringeColor: Color,
        coreSprite: SpriteAPI,
        fringeSprite: SpriteAPI,
        layer: CombatEngineLayers,
        full: Float,
        baseAlphaMul: Float,
        tipAlphaMul: Float,
        baseEmissiveAlphaMul: Float,
        tipEmissiveAlphaMul: Float,
        mixPower: Float,
    ): TrailEntity? {
        ensureReady(engine)

        val entity = createTaperedBeamTrailFromCenter(
            location = location,
            facing = facing,
            length = length,
            baseWidth = baseWidth,
            tipWidth = tipWidth,
            coreColor = coreColor,
            fringeColor = fringeColor,
            coreSprite = coreSprite,
            fringeSprite = fringeSprite,
            layer = layer,
            full = full,
            baseAlphaMul = baseAlphaMul,
            tipAlphaMul = tipAlphaMul,
            baseEmissiveAlphaMul = baseEmissiveAlphaMul,
            tipEmissiveAlphaMul = tipEmissiveAlphaMul,
            mixPower = mixPower,
        )

        val state = try {
            addEntity(engine, entity)
        } catch (t: Throwable) {
            if (engine.customData[KEY_LOG_ADD_ENTITY_FAIL_ONCE] != true) {
                engine.customData[KEY_LOG_ADD_ENTITY_FAIL_ONCE] = true
                log.warn("BoxUtil addEntity threw exception (target=${BoxEnum.ENTITY_TRAIL})", t)
            }
            -1
        }

        if (state != 0) {
            if (engine.customData[KEY_LOG_ADD_ENTITY_FAIL_ONCE] != true) {
                engine.customData[KEY_LOG_ADD_ENTITY_FAIL_ONCE] = true
                log.warn("BoxUtil addEntity failed (state=$state, target=${BoxEnum.ENTITY_TRAIL}). BoxUtil VFX entity was deleted.")
            }
            entity.delete()
            return null
        }

        return entity
    }

    fun createAndAddTaperedBeamTrailFromCenterReversedU(
        engine: CombatEngineAPI,
        location: Vector2f,
        facing: Float,
        length: Float,
        baseWidth: Float,
        tipWidth: Float,
        coreColor: Color,
        fringeColor: Color,
        coreSprite: SpriteAPI,
        fringeSprite: SpriteAPI,
        layer: CombatEngineLayers,
        full: Float,
        baseAlphaMul: Float,
        tipAlphaMul: Float,
        baseEmissiveAlphaMul: Float,
        tipEmissiveAlphaMul: Float,
        mixPower: Float,
    ): TrailEntity? {
        ensureReady(engine)

        val entity = createTaperedBeamTrailFromCenterReversedU(
            location = location,
            facing = facing,
            length = length,
            baseWidth = baseWidth,
            tipWidth = tipWidth,
            coreColor = coreColor,
            fringeColor = fringeColor,
            coreSprite = coreSprite,
            fringeSprite = fringeSprite,
            layer = layer,
            full = full,
            baseAlphaMul = baseAlphaMul,
            tipAlphaMul = tipAlphaMul,
            baseEmissiveAlphaMul = baseEmissiveAlphaMul,
            tipEmissiveAlphaMul = tipEmissiveAlphaMul,
            mixPower = mixPower,
        )

        val state = try {
            addEntity(engine, entity)
        } catch (t: Throwable) {
            if (engine.customData[KEY_LOG_ADD_ENTITY_FAIL_ONCE] != true) {
                engine.customData[KEY_LOG_ADD_ENTITY_FAIL_ONCE] = true
                log.warn("BoxUtil addEntity threw exception (target=${BoxEnum.ENTITY_TRAIL})", t)
            }
            -1
        }

        if (state != 0) {
            if (engine.customData[KEY_LOG_ADD_ENTITY_FAIL_ONCE] != true) {
                engine.customData[KEY_LOG_ADD_ENTITY_FAIL_ONCE] = true
                log.warn("BoxUtil addEntity failed (state=$state, target=${BoxEnum.ENTITY_TRAIL}). BoxUtil VFX entity was deleted.")
            }
            entity.delete()
            return null
        }

        return entity
    }

    fun createAndAddTaperedProjectileTracer(
        engine: CombatEngineAPI,
        projectile: DamagingProjectileAPI,
        length: Float,
        tailWidth: Float,
        headWidth: Float,
        coreColor: Color,
        fringeColor: Color,
        coreSprite: SpriteAPI,
        fringeSprite: SpriteAPI,
        layer: CombatEngineLayers,
        full: Float,
        tailAlphaMul: Float,
        headAlphaMul: Float,
        tailEmissiveAlphaMul: Float,
        headEmissiveAlphaMul: Float,
        mixPower: Float,
    ): TrailEntity? {
        return createAndAddTaperedBeamTrail(
            engine = engine,
            location = projectile.location,
            facing = projectile.facing + 180f,
            length = length,
            tailWidth = tailWidth,
            headWidth = headWidth,
            coreColor = coreColor,
            fringeColor = fringeColor,
            coreSprite = coreSprite,
            fringeSprite = fringeSprite,
            layer = layer,
            full = full,
            tailAlphaMul = tailAlphaMul,
            headAlphaMul = headAlphaMul,
            tailEmissiveAlphaMul = tailEmissiveAlphaMul,
            headEmissiveAlphaMul = headEmissiveAlphaMul,
            mixPower = mixPower,
        )
    }
}
