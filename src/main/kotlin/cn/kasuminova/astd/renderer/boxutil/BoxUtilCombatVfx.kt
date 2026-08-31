package cn.kasuminova.astd.renderer.boxutil

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.graphics.SpriteAPI
import org.boxutil.BoxUtilModPlugin
import org.boxutil.base.api.RenderDataAPI
import org.boxutil.define.BoxEnum
import org.boxutil.manager.CombatRenderingManager
import org.boxutil.units.standard.entity.TrailEntity
import org.boxutil.util.RenderingUtil
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/** BoxUtil combat 侧 VFX 小工具：确保 initLater/CombatRenderingManager 就绪，并提供常用 TrailEntity 构造方法。 */
internal object BoxUtilCombatVfx {

    private const val KEY_LATER_INIT = "astd_boxutil_later_init"
    private const val KEY_INVITED_CRM = "astd_boxutil_invited_combat_rendering_manager"
    private const val KEY_LOG_ADD_ENTITY_FAIL_ONCE = "astd_boxutil_add_entity_fail_once"

    private val log = Global.getLogger(BoxUtilCombatVfx::class.java)

    /**
     * 归一化朝向到 [0, 360)：BoxUtil `TrigUtil.sinFormCosF` 从 cos(半角) 反推 sin(半角) 时只做
     * `angle > 180` 的符号修正，负角度（如 atan2 直出的 -90°）会拿到错误符号的 sin——
     * 等价于绕 x 轴镜像，实体朝向/侧向偏移整体反转（朝下开火时锥形/弧凸向翻转的实锤根因）。
     * 所有进入 BoxUtil 实体变换（setStateVanilla / createModelMatrixVanilla）的朝向必须先过本函数。
     */
    fun normalizeFacingDeg(deg: Float): Float = ((deg % 360f) + 360f) % 360f

    fun ensureReady(engine: CombatEngineAPI) {
        if (engine.customData[KEY_LATER_INIT] != true) {
            if (!BoxUtilModPlugin.isGlobalInitialized()) {
                try {
                    BoxUtilModPlugin.initLater()
                } catch (t: Throwable) {
                    log.warn("BoxUtil initLater() 失败，BoxUtil VFX 将暂时不可用", t)
                    return
                }
            }
            if (BoxUtilModPlugin.isGlobalInitialized()) {
                engine.customData[KEY_LATER_INIT] = true
            }
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
    fun addEntity(engine: CombatEngineAPI, target: Byte, entity: RenderDataAPI): Int {
        var state = CombatRenderingManager.addEntity(entity).toInt()
        if (state != 0) {
            inviteCombatRenderingManagerIfNeeded(engine)
            state = CombatRenderingManager.addEntity(entity).toInt()
        }
        return state
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
        entity.setStartWidth(tailWidth)
        entity.setEndWidth(headWidth)
        entity.setMixFactor(mixPower)

        // 颜色不变：这里只用 alpha multiplier 做“更亮 + 更高对比度”的渐变。
        entity.setStartColor(1f, 1f, 1f, tailAlphaMul)
        entity.setEndColor(1f, 1f, 1f, headAlphaMul)
        entity.setStartEmissive(1f, 1f, 1f, tailEmissiveAlphaMul)
        entity.setEndEmissive(1f, 1f, 1f, headEmissiveAlphaMul)

        val mat = entity.materialData
        // 提升发光：避免 emissive alpha 被 diffuse alpha 再乘一次。
        mat.setAlphaToEmissive(0f)
        mat.setColorToEmissive(0f)
        mat.setGlowPower(1f)
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

        entity.setStartWidth(baseWidth)
        entity.setEndWidth(tipWidth)
        entity.setMixFactor(mixPower)

        entity.setStartColor(1f, 1f, 1f, baseAlphaMul)
        entity.setEndColor(1f, 1f, 1f, tipAlphaMul)
        entity.setStartEmissive(1f, 1f, 1f, baseEmissiveAlphaMul)
        entity.setEndEmissive(1f, 1f, 1f, tipEmissiveAlphaMul)

        val mat = entity.materialData
        mat.setAlphaToEmissive(0f)
        mat.setColorToEmissive(0f)
        mat.setGlowPower(1f)
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
        entity.setStartWidth(tipWidth)
        entity.setEndWidth(baseWidth)
        entity.setMixFactor(mixPower)

        entity.setStartColor(1f, 1f, 1f, tipAlphaMul)
        entity.setEndColor(1f, 1f, 1f, baseAlphaMul)
        entity.setStartEmissive(1f, 1f, 1f, tipEmissiveAlphaMul)
        entity.setEndEmissive(1f, 1f, 1f, baseEmissiveAlphaMul)

        val mat = entity.materialData
        mat.setAlphaToEmissive(0f)
        mat.setColorToEmissive(0f)
        mat.setGlowPower(1f)
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
            addEntity(engine, BoxEnum.ENTITY_TRAIL, entity)
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
            addEntity(engine, BoxEnum.ENTITY_TRAIL, entity)
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
            addEntity(engine, BoxEnum.ENTITY_TRAIL, entity)
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
