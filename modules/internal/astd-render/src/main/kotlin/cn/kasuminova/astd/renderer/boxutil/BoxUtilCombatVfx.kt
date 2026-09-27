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
 * BoxUtil combat 侧 VFX 小工具：确保 initLater/CombatRenderingManager 就绪，并提供常用 TrailEntity 构造方法。
 *
 * 战斗域实体登记簿（[entityRegistry]）：`CombatRenderingManager.addEntity` 是静态全局注册、
 * 无 engine 作用域，BoxUtil 对 renderEntityMap 的跨战斗清理在生涯追击/多轮接战路径上证据不足
 * （实机堆转储实锤 99 万实例 / 1.3 GB 滞留）。addEntity 注册成功的实体全部入簿，
 * 由两条路径兜底收口：
 * 1. 全局 [TemporaryCleanupPlugin]（仅登记一次；BoxUtil cleanupAllQueue 回调后清空钩子集，
 *    钩子内部会自我重新登记）；
 * 2. engine 实例切换探测（ensureReady/addEntity 入口比较 engine 身份）——覆盖 cleanupCombatOnce
 *    未触发的路径。
 * 簿体自身防滞留：实体 delete 后经 [removeEntity] 摘除或注册越阈时顺手清理失效条目。
 */
object BoxUtilCombatVfx {

    private const val KEY_LATER_INIT = "astd_boxutil_later_init"
    private const val KEY_INVITED_CRM = "astd_boxutil_invited_combat_rendering_manager"
    private const val KEY_LOG_ADD_ENTITY_FAIL_ONCE = "astd_boxutil_add_entity_fail_once"
    private const val KEY_LOG_NEBULA_FAIL_ONCE = "astd_boxutil_nebula_fail_once"

    private val log = Global.getLogger(BoxUtilCombatVfx::class.java)

    private val entityRegistry = CombatEntityRegistry<RenderDataAPI>({ it.hasDelete() }, { it.delete() })

    /** 当前战斗的 engine 身份标记（战斗边界判据：无公开战斗 id，engine 实例身份是最可靠口径）。 */
    private var registryEngine: CombatEngineAPI? = null

    private val cleanupHook = CombatCleanupHook()

    /** 清理钩子全局只需登记一次；登记动作必须排在 BoxUtil initLater 成功之后（无 GL 环境触碰会抛类初始化异常）。 */
    @Volatile
    private var cleanupHookInstalled = false

    /**
     * 归一化朝向到 [0, 360)：BoxUtil `TrigUtil.sinFormCosF` 从 cos(半角) 反推 sin(半角) 时只做
     * `angle > 180` 的符号修正，负角度（如 atan2 直出的 -90°）会拿到错误符号的 sin——
     * 等价于绕 x 轴镜像，实体朝向/侧向偏移整体反转（朝下开火时锥形/弧凸向翻转的实锤根因）。
     * 所有进入 BoxUtil 实体变换（setStateVanilla / createModelMatrixVanilla）的朝向必须先过本函数。
     */
    fun normalizeFacingDeg(deg: Float): Float = ((deg % 360f) + 360f) % 360f

    fun ensureReady(engine: CombatEngineAPI) {
        purgeOnEngineSwitch(engine)
        if (engine.customData[KEY_LATER_INIT] == true) return
        // isGlobalInitialized 首次触碰会触发 BoxConfigGUI 类初始化：无 GL 环境（单测/无头）
        // 直接抛 ExceptionInInitializerError，必须一并收进 try（否则一次失败后续全是 NoClassDefFoundError）
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

    /** engine 实例切换即视为战斗边界：上一场战斗的登记实体全部 delete 清簿（cleanupCombatOnce 未触发路径的兜底）。 */
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

    /**
     * 跨战斗清理钩子：BoxUtil `cleanupAllQueue` 在战斗↔标题/回生涯时回调本钩子，
     * 但回调后随即清空钩子集（一次性语义），因此每次回调末尾自我重新登记以保持后续战斗仍受保护。
     */
    private class CombatCleanupHook : TemporaryCleanupPlugin {
        override fun cleanupCombatOnce() {
            purgeRegistry("cleanupCombatOnce")
            CombatRenderingManager.addCleanupPlugin(this)
        }
    }

    private fun inviteCombatRenderingManagerIfNeeded(engine: CombatEngineAPI) {
        if (engine.customData[KEY_INVITED_CRM] == true) return
        // BoxUtil 1.5+ 通过静态方法自动挂载渲染插件，不再需要手动 addPlugin。
        ensureReady(engine)
        engine.customData[KEY_INVITED_CRM] = true
    }

    /**
     * @return 0 表示成功；非 0 表示失败（BoxUtil 内部状态码）。
     */
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

    /**
     * 常驻实体的显式收尸入口：先 delete 后摘簿（簿体防滞留的主路径；
     * 直接 `entity.delete()` 的调用点由注册越阈顺手清理与战斗切换 purge 兜底）。
     * delete 抛异常时条目留簿（WARN 一次），等待战斗切换 purge 重试，避免摘簿后失联。
     */
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

    /**
     * 星云粒子（战斗域）：替代 `engine.addNebulaParticle` 的标准入口。
     *
     * 直接复用 Box 内置的星云粒子控制器（`RenderingUtil.VanillaFX.addNebulaParticle`）——
     * 底层是单个常驻 SpriteEntity + SimpleParticleControlData 实例池（8192 槽，
     * 原版 nebula_particles 4x4 图集随机 tile，加色混合，不受光照），不存在 renderEntityMap
     * 滞留问题，无需再走 PooledCombatVfx 自池化。
     *
     * 参数语义与原版 `CombatEngineAPI.addNebulaParticle` 完全对齐（尺寸/末端尺寸倍率/淡入比例/
     * 全亮比例/总时长/颜色）；Box 侧固定加色混合（等价原版 additive=true），渲染层固定
     * ABOVE_SHIPS_AND_MISSILES_LAYER（原版星云粒子在 "top particles" 阶段渲染，同属顶层区间）。
     *
     * @return false 表示未渲染（shader 未启用或实例池满）；视野外剔除属正常路径，返回 true。
     */
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
     * 星云控制器闲置重置（BoxUtil 上游缺陷旁路，勿删）。
     *
     * 缺陷机理（SimpleParticleControlData 源码实锤）：`clearParticles()` 清空实例池时只复位
     * `state[0]/[1]/[2]`，**漏复位提交游标 `state[4]`**；闲置超 maxDur（3.2s）后控制器清池并把
     * renderingCount 置 0，而 `controlAdvance` 提交块的判据是 `state[0] != state[4]`——下一批
     * 粒子数若恰好等于残留 state[4]（同层数爆炸数量恒等：10+5×(scale−1)），提交块整体跳过，
     * renderingCount 永远停在 0 → 该批及之后同数量批次永久不可见。
     *
     * 旁路：星云闲置（实体 renderingCount == 0）时 delete 旧控制器实体，Box `getController`
     * 的 `isEntityExpired()` 探测随即重建全新控制器（state 全零，提交游标归零）。
     *
     * 调用纪律：**每次爆炸事件在喷第一批粒子前调一次**——严禁逐颗粒调用（renderingCount 在
     * controlAdvance 提交前恒 0，会把本爆发刚喷入的池一并判死删掉）。
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

        // TrailEntity：node[0] 是 trail 的“末端”(end point)。
        // RenderingUtil.createBeamVisual() 默认先 addNode(length,0) 再 addNode(0,0)，所以 node[0] 位于 +length 方向。
        // shader 的 START_* / startWidth 作用于 factor=0（node[0]），END_* / endWidth 作用于 factor=1（最后一个节点）。
        entity.startWidth = tailWidth
        entity.endWidth = headWidth
        entity.mixFactor = mixPower

        // 颜色不变：这里只用 alpha multiplier 做“更亮 + 更高对比度”的渐变。
        entity.setStartColor(1f, 1f, 1f, tailAlphaMul)
        entity.setEndColor(1f, 1f, 1f, headAlphaMul)
        entity.setStartEmissive(1f, 1f, 1f, tailEmissiveAlphaMul)
        entity.setEndEmissive(1f, 1f, 1f, headEmissiveAlphaMul)

        val mat = entity.materialData
        // 提升发光：避免 emissive alpha 被 diffuse alpha 再乘一次。
        mat.alphaToEmissive = 0f
        mat.isColorToEmissive = 0f
        mat.glowPower = 1f
        mat.setColor(coreColor)
        mat.setEmissiveColor(fringeColor)

        return entity
    }

    /**
     * 创建“从中心向外”的 taper beam：
     * - node[0] 放在 (0,0)（中心/基部），node[1] 放在 (+length,0)（尖端）
     * - 这样每根光刺的节点方向一致（中心→尖端），可避免某些 beam 纹理/UV 方向性导致的“单臂看起来逆向旋转”的错觉。
     *
     * 注意：TrailEntity 的 START_* / startWidth 作用于 factor=0（node[0]），即“中心/基部”；
     * END_* / endWidth 作用于 factor=1（node[1]），即“尖端”。
     */
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

        // 不能用 globalTimerOnce：BoxUtil 会在渲染后把 once 实体从队列移除（即“只渲染一帧”）。
        // 这里用一个很长的 full 来实现常驻；淡出由调用方自行控制（或最终 delete）。
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

    /**
     * createTaperedBeamTrailFromCenter() 的“U 方向镜像版本”：
     * - 节点顺序为 (tip->center)：node[0]=(length,0) / node[1]=(0,0)
     * - 因为 node[0] 是 START_，所以宽度/渐变参数也要跟随反转（start=tip，end=base）。
     *
     * 用途：在某些方向下 beam 贴图沿长度(U)方向的方向性会造成“单臂看起来反向旋转/反向流动”的错觉；
     * 通过叠加一条 U 镜像 trail 可以让观感与方向无关。
     */
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
        // node[0]=tip, node[1]=center
        entity.addNode(Vector2f(length, 0f))
        entity.addNode(Vector2f(0f, 0f))
        entity.submitNodes()

        entity.setLayer(layer)
        entity.setAdditiveBlend()
        entity.setGlobalTimer(0f, full.coerceAtLeast(0.01f), 0f)

        // start=node0=tip, end=node1=center
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
            // 可能是 BoxUtil/渲染管理器尚未就绪，或依赖缺失导致的类加载异常。
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

    /**
     * 便捷方法：以弹体当前位置/朝向创建“尾随曳光”。
     *
     * 注意：返回的实体需要由调用方负责每帧 setStateVanilla() 跟随，以及在弹体死亡时 delete()。
     */
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
