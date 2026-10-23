package cn.kasuminova.astd.combat.effect.arc.starfallwing

import cn.kasuminova.astd.api.AstdLog
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import org.boxutil.units.standard.entity.FlareEntity
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

object StarfallWingVfx {

    private const val MOTE_FLARE_SIZE_START = 14f
    private const val MOTE_FLARE_SIZE_END = 30f
    private const val MOTE_FLARE_LIFETIME = 0.22f
    private const val MOTE_FLARE_FADE_IN = 0.02f
    private const val MOTE_FLARE_FULL = 0.06f
    private const val MOTE_FLARE_FADE_OUT = 0.14f

    private val CORE = Color(240, 225, 255, 130)
    private val FRINGE = Color(170, 110, 255, 170)

    private val log = AstdLog.logger

    private class ActiveFlare(
        val entity: FlareEntity,
        val startSize: Float,
        val endSize: Float,
        val lifetime: Float,
        var age: Float = 0f,
    )

    private const val KEY_FLARES = "astd_starfall_wing_split_flares"

    fun spawnMoteSplitFlare(engine: CombatEngineAPI, loc: Vector2f) {
        spawnFlare(
            engine, loc,
            startSize = MOTE_FLARE_SIZE_START, endSize = MOTE_FLARE_SIZE_END,
            lifetime = MOTE_FLARE_LIFETIME,
            fadeIn = MOTE_FLARE_FADE_IN, full = MOTE_FLARE_FULL, fadeOut = MOTE_FLARE_FADE_OUT,
        )
    }

    // FlareEntity 的 globalTimer 只驱动 alpha 包络，尺寸插值必须自行逐帧推进。
    fun advance(engine: CombatEngineAPI, amount: Float) {
        val flares = engine.customData[KEY_FLARES] as? MutableList<ActiveFlare> ?: return
        val it = flares.iterator()
        while (it.hasNext()) {
            val flare = it.next()
            flare.age += amount
            val t = (flare.age / flare.lifetime).coerceIn(0f, 1f)
            if (flare.age >= flare.lifetime || flare.entity.hasDelete()) {
                if (!flare.entity.hasDelete()) flare.entity.delete()
                it.remove()
                continue
            }
            val size = flare.startSize + (flare.endSize - flare.startSize) * t
            flare.entity.setSize(size, size)
        }
        if (flares.isEmpty()) engine.customData.remove(KEY_FLARES)
    }

    private fun spawnFlare(
        engine: CombatEngineAPI,
        loc: Vector2f,
        startSize: Float,
        endSize: Float,
        lifetime: Float,
        fadeIn: Float,
        full: Float,
        fadeOut: Float,
    ) {
        BoxUtilCombatVfx.ensureReady(engine)
        val entity = try {
            FlareEntity()
        } catch (t: Throwable) {
            log.warn("[ASTD] 坠星残翼光斑建实体失败（${t.javaClass.simpleName}），本次光斑缺席", t)
            return
        }
        entity.setLayer(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER)
        entity.setAdditiveBlend()
        entity.setSmooth()
        entity.isFlick = false
        // 不喂 bloom：additive 光斑 glow 叠 bloom 会过曝成盖住整舰的白团。
        entity.glowPower = 0f
        // BoxUtil 的 setCoreColor(float,float,float,float) 有误写 fringe 槽位的 bug，必须用 Color 重载。
        entity.setCoreColor(CORE)
        entity.setFringeColor(FRINGE)
        entity.setSize(startSize, startSize)
        entity.setGlobalTimer(fadeIn, full, fadeOut)
        entity.setStateVanilla(Vector2f(loc), 0f)
        val state = BoxUtilCombatVfx.addEntity(engine, entity)
        if (state != 0) {
            log.warn("[ASTD] 坠星残翼光斑注册失败（addEntity 返回 $state），本次光斑缺席")
            entity.delete()
            return
        }
        val flares = engine.customData.getOrPut(KEY_FLARES) { ArrayList<ActiveFlare>() } as MutableList<ActiveFlare>
        flares += ActiveFlare(entity, startSize, endSize, lifetime)
    }
}
