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
        follow: BloomFlareVfx.FlareFollow?,
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
                follow,
            )
        )
        return created.size
    }

    private fun buildFlare(
        engine: CombatEngineAPI,
        center: Vector2f,
        spec: BloomFlareSpec,
        timerFadeIn: Float,
        timerFull: Float,
        timerFadeOut: Float,
    ): FlareEntity? {
        // FlareEntity 在无 GL 环境构造时会抛异常，失败时必须记录日志并放弃该光斑组。
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
        entity.isFlick = spec.flicker
        entity.isSyncFlick = false
        entity.glowPower = spec.glowPower
        entity.noisePower = spec.noisePower
        // BoxUtil setCoreColor(float×4) 会误写 fringe 通道，必须走 Color 重载。
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

    private class FlareTrack(entity: FlareEntity, spec: BloomFlareSpec) {
        val entity: FlareEntity = entity
        val sizeStart: Float = spec.sizeStart
        val sizeEnd: Float = spec.sizeEnd
        val heightStart: Float = spec.heightStart
        val heightEnd: Float = spec.heightEnd
    }

    private class BloomFlareAnimPlugin(
        private val engine: CombatEngineAPI,
        private val durationSeconds: Float,
        private val tracks: List<FlareTrack>,
        private val follow: BloomFlareVfx.FlareFollow?,
    ) : BaseEveryFrameCombatPlugin() {
        private var elapsed = 0f
        private val followVec = Vector2f()

        override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
            if (engine.isPaused) return
            elapsed += amount
            val t = (elapsed / durationSeconds).coerceIn(0f, 1f)
            val alpha = 1f - t
            val followFacing = follow?.locate(followVec)
            for (track in tracks) {
                if (followFacing != null) {
                    track.entity.setStateVanilla(followVec, BoxUtilCombatVfx.normalizeFacingDeg(followFacing))
                }
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

private fun ASTDColor.toAwt(): Color = Color(
    (red.coerceIn(0f, 1f) * 255f).toInt(),
    (green.coerceIn(0f, 1f) * 255f).toInt(),
    (blue.coerceIn(0f, 1f) * 255f).toInt(),
    (alpha.coerceIn(0f, 1f) * 255f).toInt(),
)
