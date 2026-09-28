package cn.kasuminova.astd.renderer.effect.explosion

import cn.kasuminova.astd.api.render.BloomFlareVfx
import cn.kasuminova.astd.impl.render.ASTDColor
import cn.kasuminova.astd.impl.render.BloomFlareSpec
import cn.kasuminova.astd.impl.render.BoxFlareStyle
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.input.InputEventAPI
import org.boxutil.units.standard.entity.FlareEntity
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/**
 * [BloomFlareVfx] 的 BoxUtil 实现（模组内通用组件；首批接入点：坠星残响爆心十字辉星、
 * 摧锋鱼雷命中十字辉星）。
 *
 * 每枚光斑：钉住生命周期（全局计时器由调用方给定，默认超长 full）→ 逐帧插件线性推进
 * 尺寸扩散与透明度归零 → 到期删除实体并自注销。任一光斑建实体（无 GL 环境构造抛异常）
 * 或注册失败即整组放弃（已建实体删除）并记 WARN——调用方的其余特效构成不受影响。
 */
object BloomFlareVfxImpl : BloomFlareVfx {

    private val log = Global.getLogger(BloomFlareVfxImpl::class.java)

    override fun spawn(
        engine: CombatEngineAPI,
        center: Vector2f,
        durationSeconds: Float,
        flares: List<BloomFlareSpec>,
        timerFadeIn: Float,
        timerFull: Float,
        timerFadeOut: Float,
    ): Int {
        BoxUtilCombatVfx.ensureReady(engine)
        val created = ArrayList<FlareEntity>(flares.size)
        for (spec in flares) {
            val entity = buildFlare(engine, center, spec, timerFadeIn, timerFull, timerFadeOut)
            if (entity == null) {
                created.forEach { it.delete() }
                return 0
            }
            created.add(entity)
        }
        engine.addPlugin(
            BloomFlareAnimPlugin(
                engine,
                durationSeconds,
                flares.zip(created).map { (spec, entity) -> FlareTrack(entity, spec) },
            )
        )
        return created.size
    }

    /** 建一枚钉住生命周期的光斑；失败（无 GL 环境构造异常 / addEntity 非 0）记 WARN 返回 null。 */
    private fun buildFlare(
        engine: CombatEngineAPI,
        center: Vector2f,
        spec: BloomFlareSpec,
        timerFadeIn: Float,
        timerFull: Float,
        timerFadeOut: Float,
    ): FlareEntity? {
        // FlareEntity 构造在无 GL 环境（单测/无头）会抛异常，收住并降级为无辉星
        val entity = try {
            FlareEntity()
        } catch (t: Throwable) {
            log.warn("绽放辉星建实体失败（${t.javaClass.simpleName}），本次跳过辉星组", t)
            return null
        }
        entity.setLayer(spec.layer)
        entity.setAdditiveBlend()
        when (spec.style) {
            BoxFlareStyle.SMOOTH -> entity.setSmooth()
            BoxFlareStyle.SHARP -> entity.setSharp()
            BoxFlareStyle.SMOOTH_DISC -> entity.setSmoothDisc()
            BoxFlareStyle.SHARP_DISC -> entity.setSharpDisc()
        }
        entity.isFlick = false
        entity.isSyncFlick = false
        entity.glowPower = spec.glowPower
        entity.noisePower = spec.noisePower
        // 用 Color 重载：BoxUtil 的 setCoreColor(float×4) 有源码 bug（误写 fringe 槽位，BoxFlareComponent 注记）
        entity.setCoreColor(spec.coreColor.toAwt())
        entity.setFringeColor(spec.fringeColor.toAwt())
        entity.setSize(spec.sizeStart, spec.heightStart)
        entity.autoAspect()
        entity.setGlobalTimer(timerFadeIn, timerFull, timerFadeOut)
        entity.setStateVanilla(Vector2f(center), BoxUtilCombatVfx.normalizeFacingDeg(spec.facingDeg))
        val state = BoxUtilCombatVfx.addEntity(engine, entity)
        if (state != 0) {
            log.warn("绽放辉星注册失败（addEntity 返回 $state），本次跳过辉星组")
            entity.delete()
            return null
        }
        return entity
    }

    /** 单枚光斑的动画轨迹：尺寸从 ([sizeStart], [heightStart]) 线性扩散到 ([sizeEnd], [heightEnd])。 */
    private class FlareTrack(entity: FlareEntity, spec: BloomFlareSpec) {
        val entity: FlareEntity = entity
        val sizeStart: Float = spec.sizeStart
        val sizeEnd: Float = spec.sizeEnd
        val heightStart: Float = spec.heightStart
        val heightEnd: Float = spec.heightEnd
    }

    /** 绽放辉星推进插件：存续期内尺寸线性扩散、透明度线性归零，到期删实体自注销。 */
    private class BloomFlareAnimPlugin(
        private val engine: CombatEngineAPI,
        private val durationSeconds: Float,
        private val tracks: List<FlareTrack>,
    ) : BaseEveryFrameCombatPlugin() {
        private var elapsed = 0f

        override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
            if (engine.isPaused) return
            elapsed += amount
            val t = (elapsed / durationSeconds).coerceIn(0f, 1f)
            val alpha = 1f - t
            for (track in tracks) {
                val size = track.sizeStart + (track.sizeEnd - track.sizeStart) * t
                val height = track.heightStart + (track.heightEnd - track.heightStart) * t
                track.entity.setSize(size, height)
                track.entity.globalAlpha = alpha
            }
            if (t >= 1f) {
                tracks.forEach { it.entity.delete() }
                engine.removePlugin(this)
            }
        }
    }
}

/** ASTDColor（0..1 浮点）→ awt Color（0..255）。 */
private fun ASTDColor.toAwt(): Color = Color(
    (red.coerceIn(0f, 1f) * 255f).toInt(),
    (green.coerceIn(0f, 1f) * 255f).toInt(),
    (blue.coerceIn(0f, 1f) * 255f).toInt(),
    (alpha.coerceIn(0f, 1f) * 255f).toInt(),
)
