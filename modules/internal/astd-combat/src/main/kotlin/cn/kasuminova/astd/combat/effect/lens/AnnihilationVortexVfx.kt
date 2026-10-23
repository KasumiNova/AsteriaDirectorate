package cn.kasuminova.astd.combat.effect.lens

import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import org.boxutil.units.standard.entity.DistortionEntity
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

object AnnihilationVortexVfx {

    val CORE_COLOR = Color(255, 60, 70, 255)
    val GLOW_COLOR = Color(180, 20, 40, 220)
    val NEBULA_COLOR = Color(200, 30, 50)

    fun collapse(engine: CombatEngineAPI, center: Vector2f, radius: Float) {
        val r = radius.coerceAtLeast(40f)
        engine.spawnExplosion(center, Vector2f(0f, 0f), CORE_COLOR, r * 1.05f, 0.45f)
        engine.spawnExplosion(center, Vector2f(0f, 0f), GLOW_COLOR, r * 0.62f, 0.30f)

        repeat(10) { i ->
            val a = (i.toFloat() / 10f) * (2f * Math.PI.toFloat())
            val loc = Vector2f(center.x + Math.cos(a.toDouble()).toFloat() * r * 0.35f, center.y + Math.sin(a.toDouble()).toFloat() * r * 0.35f)
            val vel = Vector2f(Math.cos(a.toDouble()).toFloat() * 45f, Math.sin(a.toDouble()).toFloat() * 45f)
            engine.addNebulaParticle(
                loc,
                vel,
                r * 0.30f,
                2.1f,
                0.10f,
                0.45f,
                0.90f,
                Color(NEBULA_COLOR.red, NEBULA_COLOR.green, NEBULA_COLOR.blue, 120)
            )
        }
        engine.addNebulaParticle(
            center,
            Vector2f(0f, 0f),
            r * 0.55f,
            2.4f,
            0.08f,
            0.35f,
            0.80f,
            Color(NEBULA_COLOR.red, NEBULA_COLOR.green, NEBULA_COLOR.blue, 100)
        )

        spawnCollapseDistortion(engine, center, r)
    }

    // 形态对照原版 GCP spawnSustainedHitCollapseDistortion，方向外→内坍缩。
    private fun spawnCollapseDistortion(engine: CombatEngineAPI, center: Vector2f, radius: Float) {
        BoxUtilCombatVfx.ensureReady(engine)

        val e = DistortionEntity()
        e.setGlobalTimer(0.03f, 0.22f, 0.30f)

        e.setInnerIn(0.35f, 0.35f)
        e.setInnerFull(0.35f, 0.35f)
        e.setInnerOut(0.35f, 0.35f)
        e.innerHardness = 0.90f
        e.ringHardness = 0.70f

        e.setSizeIn(radius, radius)
        e.setSizeFull(radius * 0.55f, radius * 0.55f)
        e.setSizeOut(radius * 0.18f, radius * 0.18f)

        e.powerIn = 0.35f
        e.powerFull = 0.75f
        e.powerOut = 0.95f

        e.setLocation(center)
        val result = BoxUtilCombatVfx.addEntity(engine, e)
        if (result != 0) {
            log.warn("[ASTD] 湮灭涡旋坍缩 DistortionEntity 注册失败（addEntity 返回 $result），扭曲视觉缺失但爆炸/烟云照常")
        }
    }

    private val log = Global.getLogger(AnnihilationVortexVfx::class.java)
}
