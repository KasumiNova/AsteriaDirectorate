package cn.kasuminova.astd.combat.effect.arc.starfallecho

import cn.kasuminova.astd.combat.effect.arc.starfallecho.StarfallEchoOnFireEffect.Companion.SPEC_ID_FINAL
import cn.kasuminova.astd.combat.effect.arc.starfallecho.StarfallEchoOnFireEffect.Companion.SPEC_ID_NORMAL
import cn.kasuminova.astd.combat.effect.generic.CombatVfxBootstrap
import cn.kasuminova.astd.impl.render.TriShardComponent
import cn.kasuminova.astd.impl.render.TriShardSpec
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin
import com.fs.starfarer.api.combat.WeaponAPI
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import java.util.IdentityHashMap

/**
 * 坠星残响的武器级每帧效果（挂 `.wpn` 的 `everyFrameEffect`）：
 *
 * - 代行 VFX bootstrap（`.wpn` 只有一个 everyFrame 槽，本武器独占，必须自行
 *   [CombatVfxBootstrap.ensureInstalled]，否则弹体 VFX 管线不启动）；
 * - 弹匣禁射闸（隐藏机制）：弹药 < [StarfallEchoTuning.AMMO_GATE] 且不在连射中时逐帧
 *   `setForceNoFireOneFrame(true)`；连射进行中放行（否则会切断已起射的 5 发 burst）；
 * - 特殊 trail：追踪本武器存活弹体，每 0.05s 放 5 个同色三角碎片（统一向飞行方向慢飞，
 *   走 [TriShardComponent] 树外发射 + 统一粒子池），每 0.5s 放一个马赫环
 *   （BoxUtil SpriteEntity 拍扁椭圆，寿命 1s 缓慢扩大渐透明，素材 astd_generated_ring）。
 *
 * 三角碎片组件按配色各一（普通蓝白 / 第 5 发共振红），马赫环为事件级低频直接建实体。
 */
class StarfallEchoWeaponEffect : EveryFrameWeaponEffectPlugin {

    /** 本武器存活弹体 → 发射节拍状态。 */
    private val tracked = IdentityHashMap<DamagingProjectileAPI, Trail>()

    /** 存活马赫环。 */
    private val rings = ArrayList<Ring>()

    private var shardsNormal: TriShardComponent? = null
    private var shardsFinal: TriShardComponent? = null

    /** 马赫环贴图加载失败的一次性 WARN 闸（不重试风暴）。 */
    private var ringTextureUnavailable = false

    override fun advance(amount: Float, engine: CombatEngineAPI, weapon: WeaponAPI) {
        if (engine.isPaused) return
        CombatVfxBootstrap.ensureInstalled(engine)

        // 弹匣禁射闸（隐藏机制）
        if (!StarfallEchoTuning.canFire(weapon.ammo, weapon.isInBurst)) {
            weapon.setForceNoFireOneFrame(true)
        }

        scanProjectiles(engine, weapon)
        emitTrails(engine, amount)
        advanceRings(engine, amount)
    }

    /** 扫入本武器新弹体，剔除已离场的。 */
    private fun scanProjectiles(engine: CombatEngineAPI, weapon: WeaponAPI) {
        for (projectile in engine.projectiles) {
            if (projectile.weapon !== weapon) continue
            val specId = projectile.projectileSpecId
            val final = when (specId) {
                SPEC_ID_NORMAL -> false
                SPEC_ID_FINAL -> true
                else -> continue
            }
            tracked.getOrPut(projectile) { Trail(final) }
        }
        val it = tracked.entries.iterator()
        while (it.hasNext()) {
            if (!engine.isEntityInPlay(it.next().key)) it.remove()
        }
    }

    /** 逐弹体推进发射节拍：碎片 0.05s×5、马赫环 0.5s×1。 */
    private fun emitTrails(engine: CombatEngineAPI, amount: Float) {
        for ((projectile, trail) in tracked) {
            trail.shardAcc += amount
            if (trail.shardAcc >= SHARD_INTERVAL) {
                trail.shardAcc = 0f
                emitShards(engine, projectile, trail.final)
            }
            trail.ringAcc += amount
            if (trail.ringAcc >= RING_INTERVAL) {
                trail.ringAcc = 0f
                emitRing(engine, projectile, trail.final)
            }
        }
        // 灌批即消费，空批无操作；逐帧调一次保证节拍外不滞留
        shardsNormal?.activatePendingBatches(engine)
        shardsFinal?.activatePendingBatches(engine)
    }

    /** 同色三角碎片 ×5：弹体位置小散布，统一向飞行方向慢飞。 */
    private fun emitShards(engine: CombatEngineAPI, projectile: DamagingProjectileAPI, final: Boolean) {
        val shards = if (final) {
            shardsFinal ?: TriShardComponent(
                "astd_starfall_echo_trail_final", 68f, StarfallEchoVfx.FINAL_CORE, StarfallEchoVfx.FINAL_FRINGE, TRAIL_SHARD_SPEC,
            ).also { shardsFinal = it }
        } else {
            shardsNormal ?: TriShardComponent(
                "astd_starfall_echo_trail", 34f, StarfallEchoVfx.NORMAL_CORE, StarfallEchoVfx.NORMAL_FRINGE, TRAIL_SHARD_SPEC,
            ).also { shardsNormal = it }
        }
        val facing = projectile.facing
        repeat(SHARDS_PER_TICK) {
            val pos = MathUtils.getRandomPointInCircle(projectile.location, 8f)
            val speed = MathUtils.getRandomNumberInRange(100f, 150f)
            val vel = MathUtils.getPointOnCircumference(
                Vector2f(), speed, facing + MathUtils.getRandomNumberInRange(-8f, 8f),
            )
            shards.addShard(0, pos, vel)
        }
    }

    /** 马赫环：弹体当前位置留一枚拍扁椭圆环（定向沿飞行方向），寿命 1s 缓慢扩大渐透明。 */
    private fun emitRing(engine: CombatEngineAPI, projectile: DamagingProjectileAPI, final: Boolean) {
        if (ringTextureUnavailable) return
        val sprite = try {
            Global.getSettings().loadTexture(StarfallEchoVfx.RING_TEXTURE)
            Global.getSettings().getSprite(StarfallEchoVfx.RING_TEXTURE)
        } catch (t: Throwable) {
            ringTextureUnavailable = true
            log.warn("坠星残响马赫环贴图加载失败，本场战斗该武器不再放环: path=${StarfallEchoVfx.RING_TEXTURE}", t)
            return
        }

        val entity = org.boxutil.units.standard.entity.SpriteEntity(sprite)
        entity.setLayer(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER)
        entity.setAdditiveBlend()
        val halfSize = if (final) RING_HALF_SIZE_FINAL else RING_HALF_SIZE_NORMAL
        entity.setBaseSizePerTiles(halfSize, halfSize)
        val color = if (final) StarfallEchoVfx.FINAL_FRINGE else StarfallEchoVfx.NORMAL_FRINGE
        entity.materialData.setColor(
            color.red / 255f, color.green / 255f, color.blue / 255f, RING_ALPHA,
        )
        entity.materialData.emissive = entity.materialData.diffuse
        entity.materialData.setEmissiveColor(
            color.red / 255f, color.green / 255f, color.blue / 255f, RING_ALPHA * 0.5f,
        )
        entity.materialData.glowPower = 0.5f
        entity.setGlobalTimer(RING_FADE_IN, RING_FULL, RING_FADE_OUT)
        BoxUtilCombatVfx.ensureReady(engine)
        val state = BoxUtilCombatVfx.addEntity(engine, entity)
        if (state != 0) {
            log.warn("坠星残响马赫环注册失败（addEntity 返回 $state），本枚环缺席")
            entity.delete()
            return
        }
        val ring = Ring(entity, Vector2f(projectile.location), projectile.facing)
        syncRing(ring)
        rings += ring
    }

    /** 逐帧推进马赫环：缓慢扩大（0.7 → 1.6 倍），拍扁椭圆（横向 ×0.45）；计时器管渐透明。 */
    private fun advanceRings(engine: CombatEngineAPI, amount: Float) {
        val it = rings.iterator()
        while (it.hasNext()) {
            val ring = it.next()
            ring.elapsed += amount
            if (ring.entity.hasDelete() || ring.elapsed >= RING_LIFETIME + 0.05f) {
                it.remove()
                continue
            }
            syncRing(ring)
        }
    }

    private fun syncRing(ring: Ring) {
        val growth = RING_GROWTH_START + (RING_GROWTH_END - RING_GROWTH_START) *
                (ring.elapsed / RING_LIFETIME).coerceIn(0f, 1f)
        ring.entity.setStateVanilla(
            ring.pos,
            BoxUtilCombatVfx.normalizeFacingDeg(ring.facing),
            Vector2f(growth, growth * RING_FLATTEN),
        )
    }

    /** 一枚存活马赫环：实体、锚点（发射时弹体位置，环不跟弹）、定向与存活时间。 */
    private class Ring(val entity: org.boxutil.units.standard.entity.SpriteEntity, val pos: Vector2f, val facing: Float) {
        var elapsed: Float = 0f
    }

    /** 单弹体发射节拍累积。 */
    private class Trail(val final: Boolean) {
        var shardAcc: Float = 0f
        var ringAcc: Float = 0f
    }

    companion object {
        /** 三角碎片发射节拍（秒）与每节拍颗数。 */
        private const val SHARD_INTERVAL = 0.05f
        private const val SHARDS_PER_TICK = 5

        /** 马赫环发射节拍（秒）。 */
        private const val RING_INTERVAL = 0.5f

        /** 马赫环寿命包络（fadeIn + full + fadeOut = 1s）。 */
        private const val RING_FADE_IN = 0.06f
        private const val RING_FULL = 0.5f
        private const val RING_FADE_OUT = 0.44f
        private const val RING_LIFETIME = RING_FADE_IN + RING_FULL + RING_FADE_OUT

        /** 马赫环基准半径（普通 / 第 5 发，约为弹体宽度的 2 倍）。 */
        private const val RING_HALF_SIZE_NORMAL = 35f
        private const val RING_HALF_SIZE_FINAL = 70f

        /** 马赫环透明度与拍扁比（椭圆横向压扁）、扩大域。 */
        private const val RING_ALPHA = 0.6f
        private const val RING_FLATTEN = 0.45f
        private const val RING_GROWTH_START = 0.7f
        private const val RING_GROWTH_END = 1.6f

        /** 弹体拖尾碎片参数：小尺寸、短寿命、弹体同级渲染层。 */
        private val TRAIL_SHARD_SPEC = TriShardSpec(
            batchCount = 1,
            sizeMul = 0.2f,
            sizeMin = 4f,
            sizeMax = 9f,
            spinMin = 90f,
            spinMax = 360f,
            alphaLo = 120,
            alphaHi = 180,
            timerFullLo = 0.15f,
            timerFullHi = 0.3f,
            timerFadeOut = 0.3f,
        )

        private val log = Global.getLogger(StarfallEchoWeaponEffect::class.java)
    }
}
