package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.RenderContext
import cn.kasuminova.astd.renderer.boxutil.pool.PooledCombatVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

data class TriShardSpec(
    val batchCount: Int = 3,
    val spritePath: String = "graphics/fx/astd_shard_tri.png",
    val sizeMul: Float = 0.03f,
    val sizeMin: Float = 6f,
    val sizeMax: Float = 16f,
    val sizeJitterLo: Float = 0.7f,
    val sizeJitterHi: Float = 1.3f,
    val skewLo: Float = 0.7f,
    val skewHi: Float = 1.3f,
    val spinMin: Float = 180f,
    val spinMax: Float = 540f,
    val coreRatio: Float = 0.25f,
    val alphaLo: Int = 140,
    val alphaHi: Int = 200,
    // Sprite frag 合成 diffuse + emissive×emissive.w 且 fragEmissive 全强度进 bloom，全 alpha 会过曝糊形，故降权
    val emissiveAlphaMul: Float = 0.4f,
    val timerFadeIn: Float = 0.02f,
    val timerFadeOut: Float = 0.32f,
    val timerFullLo: Float = 0.18f,
    val timerFullHi: Float = 0.32f,
    val layer: CombatEngineLayers = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
)

class TriShardComponent(
    id: String,
    private val length: Float,
    private val coreColor: Color,
    private val fringeColor: Color,
    private val spec: TriShardSpec = TriShardSpec(),
) : RenderEntityImpl(id) {

    private val log = Global.getLogger(TriShardComponent::class.java)

    private val poolKey = PooledCombatVfx.SpritePoolKey(spec.spritePath, spec.layer)

    internal val batches = List(spec.batchCount) { Batch() }

    override fun advanceSelf(ctx: RenderContext, amount: Float) {
        val engine = ctx.engine ?: return
        activatePendingBatches(engine)
    }

    fun activatePendingBatches(engine: CombatEngineAPI) {
        for (batch in batches) {
            if (batch.instances.isNotEmpty()) activateBatch(engine, batch)
        }
    }

    fun addShard(batchIndex: Int, pos: Vector2f, vel: Vector2f, sizeScale: Float = 1f) {
        val side = (length * spec.sizeMul).coerceIn(spec.sizeMin, spec.sizeMax) *
                MathUtils.getRandomNumberInRange(spec.sizeJitterLo, spec.sizeJitterHi) * sizeScale
        val sideRatio = MathUtils.getRandomNumberInRange(spec.skewLo, spec.skewHi)
        val brighten = MathUtils.getRandomNumberInRange(0f, 1f) < spec.coreRatio
        val base = if (brighten) coreColor else fringeColor
        val spinMag = MathUtils.getRandomNumberInRange(spec.spinMin, spec.spinMax)
        val spin = if (MathUtils.getRandomNumberInRange(0f, 1f) < 0.5f) -spinMag else spinMag
        val alpha = MathUtils.getRandomNumberInRange(spec.alphaLo, spec.alphaHi).coerceIn(0, 255)
        batches[batchIndex].instances += ShardInstance(
            pos = Vector2f(pos),
            vel = Vector2f(vel),
            facingDeg = MathUtils.getRandomNumberInRange(0f, 360f),
            turnRateDegPerSec = spin,
            // Instance2Data 的 scale 是半尺寸（边长的一半）。
            scaleX = side * 0.5f,
            scaleY = side * 0.5f * sideRatio,
            color = Color(base.red, base.green, base.blue, alpha),
            emissiveAlpha = (alpha * spec.emissiveAlphaMul).toInt().coerceIn(0, 255),
            timerFull = MathUtils.getRandomNumberInRange(spec.timerFullLo, spec.timerFullHi),
        )
    }

    private fun activateBatch(engine: CombatEngineAPI, batch: Batch) {
        val pending = ArrayList(batch.instances)
        batch.instances.clear()
        var dropped = 0
        for (inst in pending) {
            val accepted = PooledCombatVfx.spawnSprite(
                engine = engine,
                key = poolKey,
                x = inst.pos.x,
                y = inst.pos.y,
                velX = inst.vel.x,
                velY = inst.vel.y,
                facingDeg = inst.facingDeg,
                turnRateDeg = inst.turnRateDegPerSec,
                scaleX = inst.scaleX,
                scaleY = inst.scaleY,
                color = inst.color,
                emissiveColor = Color(inst.color.red, inst.color.green, inst.color.blue, inst.emissiveAlpha),
                fadeIn = spec.timerFadeIn,
                full = inst.timerFull,
                fadeOut = spec.timerFadeOut,
            )
            if (!accepted) dropped++
        }
        if (dropped > 0) {
            log.warn("三角碎片批灌入粒子池失败（$dropped/${pending.size} 颗缺席，id=$id），本批视觉缺席")
        }
    }

    internal class Batch {
        val instances = ArrayList<ShardInstance>()
    }

    internal class ShardInstance(
        val pos: Vector2f,
        val vel: Vector2f,
        val facingDeg: Float,
        val turnRateDegPerSec: Float,
        val scaleX: Float,
        val scaleY: Float,
        val color: Color,
        val emissiveAlpha: Int,
        val timerFull: Float,
    )
}
