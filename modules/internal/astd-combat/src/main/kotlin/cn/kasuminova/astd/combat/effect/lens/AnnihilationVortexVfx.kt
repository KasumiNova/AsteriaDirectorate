package cn.kasuminova.astd.combat.effect.lens

import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import org.boxutil.define.BoxEnum
import org.boxutil.units.standard.entity.DistortionEntity
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/**
 * 湮灭涡旋的一次性视觉（规格 04 §2.1/§3）：坍缩爆炸与吸收反馈的纯视觉出口，无状态 object。
 *
 * 坍缩形态：深红 `spawnExplosion` + `addNebulaParticle` 烟云 + 内收 `DistortionEntity`
 * （对照 GCP `spawnSustainedHitCollapseDistortion` 形态，方向为外→内快速坍缩——
 * sizeIn 取坍缩全半径、sizeOut 收到近心点，power 外弱内强）。
 */
object AnnihilationVortexVfx {

    /** LENS 深红主色族（对齐「湮灭涡旋用深红涡旋 + 深红坍缩爆炸」美术口径）。 */
    val CORE_COLOR = Color(255, 60, 70, 255)
    val GLOW_COLOR = Color(180, 20, 40, 220)
    val NEBULA_COLOR = Color(200, 30, 50)

    /**
     * 坍缩一次性视觉：深红爆炸 + 烟云 + 内收引力扭曲。
     *
     * @param radius 坍缩半径（= 涡旋半径 × 150%，与伤害结算半径同径，观感与范围匹配）
     */
    fun collapse(engine: CombatEngineAPI, center: Vector2f, radius: Float) {
        val r = radius.coerceAtLeast(40f)
        engine.spawnExplosion(center, Vector2f(0f, 0f), CORE_COLOR, r * 1.05f, 0.45f)
        engine.spawnExplosion(center, Vector2f(0f, 0f), GLOW_COLOR, r * 0.62f, 0.30f)

        // 烟云：环布 nebula 外扩（爆炸余波）。
        repeat(10) { i ->
            val a = (i.toFloat() / 10f) * (2f * Math.PI.toFloat())
            val loc = Vector2f(center.x + Math.cos(a.toDouble()).toFloat() * r * 0.35f, center.y + Math.sin(a.toDouble()).toFloat() * r * 0.35f)
            val vel = Vector2f(Math.cos(a.toDouble()).toFloat() * 45f, Math.sin(a.toDouble()).toFloat() * 45f)
            engine.addNebulaParticle(loc, vel, r * 0.30f, 2.1f, 0.10f, 0.45f, 0.90f, Color(NEBULA_COLOR.red, NEBULA_COLOR.green, NEBULA_COLOR.blue, 120))
        }
        // 中心底尘。
        engine.addNebulaParticle(center, Vector2f(0f, 0f), r * 0.55f, 2.4f, 0.08f, 0.35f, 0.80f, Color(NEBULA_COLOR.red, NEBULA_COLOR.green, NEBULA_COLOR.blue, 100))

        spawnCollapseDistortion(engine, center, r)
    }

    /** 内收引力扭曲：从坍缩全半径快速收到近心点（外→内），强度外弱内强，末段快速消散。 */
    private fun spawnCollapseDistortion(engine: CombatEngineAPI, center: Vector2f, radius: Float) {
        BoxUtilCombatVfx.ensureReady(engine)

        val e = DistortionEntity()
        e.setGlobalTimer(0.03f, 0.22f, 0.30f)

        // 固定内圈比例（同 GCP 稳定形态口径）。
        e.setInnerIn(0.35f, 0.35f)
        e.setInnerFull(0.35f, 0.35f)
        e.setInnerOut(0.35f, 0.35f)
        e.setInnerHardness(0.90f)
        e.setRingHardness(0.70f)

        // 外 → 内：从坍缩全半径开始，快速坍缩到近心点。
        e.setSizeIn(radius, radius)
        e.setSizeFull(radius * 0.55f, radius * 0.55f)
        e.setSizeOut(radius * 0.18f, radius * 0.18f)

        // 强度外弱内强，最后快速消散。
        e.setPowerIn(0.35f)
        e.setPowerFull(0.75f)
        e.setPowerOut(0.95f)

        e.setLocation(center)
        val result = BoxUtilCombatVfx.addEntity(engine, BoxEnum.ENTITY_DISTORTION, e)
        if (result != 0) {
            log.warn("[ASTD] 湮灭涡旋坍缩 DistortionEntity 注册失败（addEntity 返回 $result），扭曲视觉缺失但爆炸/烟云照常")
        }
    }

    private val log = Global.getLogger(AnnihilationVortexVfx::class.java)
}
