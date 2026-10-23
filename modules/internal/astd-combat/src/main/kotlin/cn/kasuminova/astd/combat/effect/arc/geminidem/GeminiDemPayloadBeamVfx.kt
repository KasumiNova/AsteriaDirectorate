package cn.kasuminova.astd.combat.effect.arc.geminidem

import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import cn.kasuminova.astd.renderer.boxutil.pool.PooledCombatVfx
import cn.kasuminova.astd.renderer.boxutil.pool.TrailLease
import cn.kasuminova.astd.renderer.boxutil.pool.TrailLeaseKey
import cn.kasuminova.astd.renderer.boxutil.pool.TrailLeaseSpec
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BeamAPI
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.EmpArcEntityAPI
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.util.IntervalUtil
import com.fs.starfarer.api.util.Misc
import org.boxutil.units.standard.entity.FlareEntity
import org.boxutil.units.standard.entity.TrailEntity
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import java.util.IdentityHashMap

// EveryFrameWeaponEffectPlugin 实例按武器 spec 共享，单武器状态必须存于 engine.customData。
class GeminiDemPayloadBeamVfx : EveryFrameWeaponEffectPlugin {

    private var checkoutFailCount = 0

    private enum class Kind(val texturePath: String, val core: Color, val fringe: Color) {
        KINETIC(TEX_ZAPPY, Color(220, 240, 255), Color(140, 190, 255)),
        HE(TEX_FLOW, Color(255, 150, 150), Color(255, 40, 60)),
    }

    private class BeamVisualState {
        var beam: BeamAPI? = null

        var lease: TrailLease? = null

        var glowFlare: FlareEntity? = null
        var pillarFlare: FlareEntity? = null

        var activeElapsed = 0f
        var fading = false
        var fadeElapsed = 0f

        var syncBlend = 0f

        var lastFrom: Vector2f? = null
        var lastFacing = 0f
        var lastLength = 0f

        val ambientNebulaInterval = IntervalUtil(AMBIENT_NEBULA_INTERVAL, AMBIENT_NEBULA_INTERVAL)
        val decorArcInterval = IntervalUtil(DECOR_ARC_INTERVAL, DECOR_ARC_INTERVAL)
    }

    override fun advance(amount: Float, engine: CombatEngineAPI, weapon: WeaponAPI) {
        if (engine.isPaused) return
        val kind = when (weapon.spec?.weaponId) {
            GeminiDemDifficulty.KINETIC_PAYLOAD_ID, GeminiDemDifficulty.KINETIC_PAYLOAD_FIGHTER_ID -> Kind.KINETIC
            GeminiDemDifficulty.HE_PAYLOAD_ID, GeminiDemDifficulty.HE_PAYLOAD_FIGHTER_ID -> Kind.HE
            else -> return
        }

        val beam = weapon.beams?.firstOrNull()
        val firing = beam != null && weapon.isFiring && beam.brightness > 0.05f

        val states = statesOf(engine)
        var state = states[weapon]

        if (firing && beam != null) {
            var newBurst = false
            if (state == null) {
                state = BeamVisualState()
                states[weapon] = state
                newBurst = true
            } else if (state.beam !== beam) {
                state.lease?.release(FADE_OUT)
                state.lease = null
                retireFlares(state)
                state.beam = beam
                state.activeElapsed = 0f
                state.fading = false
                state.fadeElapsed = 0f
                newBurst = true
            }

            state.beam = beam
            state.activeElapsed += amount

            val from = Vector2f(beam.from)
            val facing = Misc.getAngleInDegrees(from, beam.to)
            val length = MathUtils.getDistance(from, beam.to).coerceAtLeast(1f)
            state.lastFrom = from
            state.lastFacing = facing
            state.lastLength = length

            val now = engine.getTotalElapsedTime(false)
            val syncUntil = weapon.ship?.customData?.get(GeminiDemDifficulty.SYNC_VISUAL_KEY) as? Float ?: 0f
            val syncActive = syncUntil > now
            state.syncBlend = (state.syncBlend +
                    (if (syncActive) amount else -amount) / SYNC_BLEND_SECONDS).coerceIn(0f, 1f)
            val core = lerpColor(kind.core, SYNC_CORE, state.syncBlend)
            val fringe = lerpColor(kind.fringe, SYNC_FRINGE, state.syncBlend)

            val ramp = (state.activeElapsed / RAMP_IN).coerceIn(0f, 1f)
            val held = state.lease
            if (held != null && (!held.active || held.entity.hasDelete() || !held.entity.isValid)) {
                log.warn("双子星 DEM payload 束体租约异常失效（active=${held.active}），本帧重新检出")
                state.lease = null
            }
            val lease = state.lease ?: checkoutLease(engine, kind, from, facing, length).also { state.lease = it }
            if (lease != null) {
                updateEntity(lease.entity, from, facing, length, core, fringe, alphaMul = ramp, widthMul = ramp)
                lease.touch()
            }
            if (newBurst) {
                spawnLaunchBurst(engine, from, fringe)
            }
            syncFlares(engine, state, from, facing, core, fringe, alphaMul = ramp)
            if (kind == Kind.HE) {
                advanceAmbientNebula(state, engine, amount, from, facing, length, fringe)
            }
            if (kind == Kind.KINETIC) {
                advanceDecorArcs(state, engine, amount, from, facing, length, core, fringe)
            }
            return
        }

        if (state == null || state.lease == null) {
            if (state != null) retireFlares(state)
            states.remove(weapon)
            return
        }
        if (!state.fading) {
            state.fading = true
            state.fadeElapsed = 0f
            retireFlares(state)
        }
        state.fadeElapsed += amount
        val t = (state.fadeElapsed / FADE_OUT).coerceIn(0f, 1f)
        val lease = state.lease
        if (lease == null || !lease.active || lease.entity.hasDelete() || !lease.entity.isValid) {
            states.remove(weapon)
            return
        }
        val from = state.lastFrom
        if (from != null) {
            updateEntity(
                lease.entity, from, state.lastFacing, state.lastLength,
                core = null, fringe = null,
                alphaMul = 1f - t, widthMul = lerp(1f, FADE_WIDTH_END_MUL, t),
            )
            lease.touch()
        }
        if (t >= 1f) {
            lease.release()
            states.remove(weapon)
        }
    }

    private fun checkoutLease(
        engine: CombatEngineAPI,
        kind: Kind,
        from: Vector2f,
        facing: Float,
        length: Float,
    ): TrailLease? {
        val lease = PooledCombatVfx.checkoutTrail(
            engine, poolKey(kind),
            TrailLeaseSpec(
                location = from,
                facingDeg = facing,
                nodes = listOf(Vector2f(0f, 0f), Vector2f(length, 0f)),
                startWidth = BASE_WIDTH,
                endWidth = TIP_WIDTH,
                coreColor = kind.core,
                fringeColor = kind.fringe,
                startAlpha = BASE_ALPHA,
                endAlpha = TIP_ALPHA,
                startEmissiveAlpha = BASE_EMISSIVE_ALPHA,
                endEmissiveAlpha = TIP_EMISSIVE_ALPHA,
                texturePixels = TEX_PIXELS,
                textureSpeed = TEX_SPEED,
                watchdogHeartbeat = KEEPALIVE,
                watchdogFadeOut = FADE_OUT,
            ),
        )
        if (lease == null) {
            checkoutFailCount++
            if (checkoutFailCount == 1 || checkoutFailCount % CHECKOUT_WARN_STRIDE == 0) {
                log.warn("双子星 DEM payload 束体池化检出失败（kind=$kind，池不可用或池满，累计 $checkoutFailCount 次），本层缺失其余特效照常")
            }
        }
        return lease
    }

    // BoxUtil submitNodes 只上传刷新索引圈定的区间，同尺寸再提交默认刷新计数为 0，每帧必须显式圈全区。
    private fun updateEntity(
        entity: TrailEntity,
        from: Vector2f,
        facing: Float,
        length: Float,
        core: Color?,
        fringe: Color?,
        alphaMul: Float,
        widthMul: Float,
    ) {
        val nodes = entity.nodes
        if (nodes != null && nodes.size >= 2) {
            nodes[1].x = length
            entity.setNodeRefreshIndex(0)
            entity.setNodeRefreshAllFromCurrentIndex()
            entity.submitNodes()
        }
        entity.setStateVanilla(from, BoxUtilCombatVfx.normalizeFacingDeg(facing))

        entity.startWidth = BASE_WIDTH * widthMul
        entity.endWidth = TIP_WIDTH * widthMul

        entity.startColorAlpha = BASE_ALPHA * alphaMul
        entity.endColorAlpha = TIP_ALPHA * alphaMul
        entity.startEmissiveAlpha = BASE_EMISSIVE_ALPHA * alphaMul
        entity.endEmissiveAlpha = TIP_EMISSIVE_ALPHA * alphaMul

        if (core != null && fringe != null) {
            val mat = entity.materialData
            mat.setColor(core)
            mat.setEmissiveColor(fringe)
        }
    }

    private fun syncFlares(
        engine: CombatEngineAPI,
        state: BeamVisualState,
        from: Vector2f,
        beamFacing: Float,
        core: Color,
        fringe: Color,
        alphaMul: Float,
    ) {
        val glow = state.glowFlare ?: createFlare(engine, smooth = true, core, fringe)?.also { state.glowFlare = it }
        val pillar = state.pillarFlare ?: createFlare(engine, smooth = false, core, fringe)?.also { state.pillarFlare = it }
        glow?.let {
            it.setStateVanilla(from, 0f)
            it.setCoreColor(core)
            it.setFringeColor(fringe)
            it.globalAlpha = alphaMul
            it.setGlobalTimer(0f, KEEPALIVE, FADE_OUT)
        }
        pillar?.let {
            it.setStateVanilla(from, BoxUtilCombatVfx.normalizeFacingDeg(beamFacing + 90f))
            it.setCoreColor(core)
            it.setFringeColor(fringe)
            it.globalAlpha = alphaMul
            it.setGlobalTimer(0f, KEEPALIVE, FADE_OUT)
        }
    }

    private fun createFlare(engine: CombatEngineAPI, smooth: Boolean, core: Color, fringe: Color): FlareEntity? {
        val entity = FlareEntity()
        entity.setLayer(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER)
        entity.setAdditiveBlend()
        if (smooth) {
            entity.setSmooth()
            entity.setSize(FLARE_GLOW_SIZE, FLARE_GLOW_SIZE)
        } else {
            entity.setSharpDisc()
            entity.setSize(FLARE_PILLAR_LENGTH, FLARE_PILLAR_WIDTH)
        }
        entity.autoAspect()
        // BoxUtil 的 setCoreColor(float,float,float,float) 有误写 fringe 槽位的 bug，必须用 Color 重载。
        entity.setCoreColor(core)
        entity.setFringeColor(fringe)
        entity.setGlobalTimer(0f, KEEPALIVE, FADE_OUT)
        BoxUtilCombatVfx.ensureReady(engine)
        val addState = BoxUtilCombatVfx.addEntity(engine, entity)
        if (addState != 0) {
            log.warn("双子星 DEM payload 发射点光斑注册失败（addEntity 返回 $addState，smooth=$smooth），本层缺失其余特效照常")
            entity.delete()
            return null
        }
        return entity
    }

    private fun retireFlares(state: BeamVisualState) {
        state.glowFlare?.setGlobalTimer(0f, 0.01f, FADE_OUT)
        state.pillarFlare?.setGlobalTimer(0f, 0.01f, FADE_OUT)
        state.glowFlare = null
        state.pillarFlare = null
    }

    private fun spawnLaunchBurst(engine: CombatEngineAPI, from: Vector2f, fringe: Color) {
        repeat(LAUNCH_BURST_COUNT) {
            val dir = Misc.getUnitVectorAtDegreeAngle(MathUtils.getRandomNumberInRange(0f, 360f))
            dir.scale(MathUtils.getRandomNumberInRange(LAUNCH_BURST_OFFSET_MIN, LAUNCH_BURST_OFFSET_MAX))
            val pos = Vector2f(from.x + dir.x, from.y + dir.y)
            BoxUtilCombatVfx.addNebulaParticle(
                engine, pos, ZERO, MathUtils.getRandomNumberInRange(LAUNCH_NEBULA_SIZE_MIN, LAUNCH_NEBULA_SIZE_MAX),
                1.4f, 0.2f, 0.3f, 1f,
                Color(fringe.red, fringe.green, fringe.blue, LAUNCH_NEBULA_ALPHA),
            )
        }
    }

    private fun advanceAmbientNebula(
        state: BeamVisualState,
        engine: CombatEngineAPI,
        amount: Float,
        from: Vector2f,
        facing: Float,
        length: Float,
        fringe: Color,
    ) {
        state.ambientNebulaInterval.advance(amount)
        if (!state.ambientNebulaInterval.intervalElapsed()) return
        val dir = Misc.getUnitVectorAtDegreeAngle(facing)
        repeat(AMBIENT_NEBULA_COUNT) {
            val along = MathUtils.getRandomNumberInRange(0f, length)
            val jitter = Misc.getUnitVectorAtDegreeAngle(MathUtils.getRandomNumberInRange(0f, 360f))
            jitter.scale(MathUtils.getRandomNumberInRange(0f, AMBIENT_NEBULA_JITTER))
            val pos = Vector2f(from.x + dir.x * along + jitter.x, from.y + dir.y * along + jitter.y)
            BoxUtilCombatVfx.addNebulaParticle(
                engine, pos, ZERO, MathUtils.getRandomNumberInRange(AMBIENT_NEBULA_SIZE_MIN, AMBIENT_NEBULA_SIZE_MAX),
                1.2f, 0.1f, 0.3f, 0.8f,
                Color(fringe.red, fringe.green, fringe.blue, AMBIENT_NEBULA_ALPHA),
            )
        }
    }

    private fun advanceDecorArcs(
        state: BeamVisualState,
        engine: CombatEngineAPI,
        amount: Float,
        from: Vector2f,
        facing: Float,
        length: Float,
        core: Color,
        fringe: Color,
    ) {
        state.decorArcInterval.advance(amount)
        if (!state.decorArcInterval.intervalElapsed()) return
        val dir = Misc.getUnitVectorAtDegreeAngle(facing)
        engine.spawnEmpArcVisual(from, null, offset(from, dir, length), null, DECOR_ARC_THICKNESS, fringe, core)
    }

    private fun offset(origin: Vector2f, dir: Vector2f, dist: Float): Vector2f =
        Vector2f(origin.x + dir.x * dist, origin.y + dir.y * dist)

    private fun poolKey(kind: Kind): TrailLeaseKey = when (kind) {
        Kind.KINETIC -> KINETIC_POOL_KEY
        Kind.HE -> HE_POOL_KEY
    }

    companion object {
        private val log = Global.getLogger(GeminiDemPayloadBeamVfx::class.java)

        private const val CHECKOUT_WARN_STRIDE = 64

        private const val TEX_ZAPPY = "graphics/fx/astd_trails_zappy.png"
        private const val TEX_FLOW = "graphics/fx/astd_trails_flow.png"

        private const val RAMP_IN = 0.1f

        private const val FADE_OUT = 0.45f

        private const val KEEPALIVE = 0.15f

        private const val FADE_WIDTH_END_MUL = 0.3f

        private const val BASE_WIDTH = 20f
        private const val TIP_WIDTH = 20f

        private const val BASE_ALPHA = 0.8f
        private const val TIP_ALPHA = 1.0f
        private const val BASE_EMISSIVE_ALPHA = 0.2f
        private const val TIP_EMISSIVE_ALPHA = 0.4f

        private const val TEX_PIXELS = 256f
        private const val TEX_SPEED = 360f

        private val SYNC_CORE = Color(235, 190, 255)
        private val SYNC_FRINGE = Color(180, 90, 255)

        private const val SYNC_BLEND_SECONDS = 0.25f

        private const val LAUNCH_BURST_COUNT = 10
        private const val LAUNCH_BURST_OFFSET_MIN = 20f
        private const val LAUNCH_BURST_OFFSET_MAX = 40f
        private const val LAUNCH_NEBULA_SIZE_MIN = 80f
        private const val LAUNCH_NEBULA_SIZE_MAX = 160f
        private const val LAUNCH_NEBULA_ALPHA = 200

        private const val AMBIENT_NEBULA_INTERVAL = 0.2f
        private const val AMBIENT_NEBULA_COUNT = 6
        private const val AMBIENT_NEBULA_SIZE_MIN = 60f
        private const val AMBIENT_NEBULA_SIZE_MAX = 120f
        private const val AMBIENT_NEBULA_JITTER = 20f
        private const val AMBIENT_NEBULA_ALPHA = 150

        private const val DECOR_ARC_INTERVAL = 0.1f
        private const val DECOR_ARC_THICKNESS = 15f

        private const val FLARE_GLOW_SIZE = 46f
        private const val FLARE_PILLAR_LENGTH = 160f
        private const val FLARE_PILLAR_WIDTH = 30f

        private const val STATES_KEY = "astd_gemini_dem_payload_vfx_states"

        private val ZERO = Vector2f(0f, 0f)

        private val KINETIC_POOL_KEY = TrailLeaseKey(
            layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
            coreSpritePath = TEX_ZAPPY,
            fringeSpritePath = TEX_ZAPPY,
            mixPower = 0.5f,
            capacity = 8,
        )
        private val HE_POOL_KEY = TrailLeaseKey(
            layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
            coreSpritePath = TEX_FLOW,
            fringeSpritePath = TEX_FLOW,
            mixPower = 0.5f,
            capacity = 8,
        )

        @Suppress("UNCHECKED_CAST")
        private fun statesOf(engine: CombatEngineAPI): IdentityHashMap<WeaponAPI, BeamVisualState> =
            engine.customData.getOrPut(STATES_KEY) { IdentityHashMap<WeaponAPI, BeamVisualState>() }
                    as IdentityHashMap<WeaponAPI, BeamVisualState>

        private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

        private fun lerpColor(a: Color, b: Color, t: Float): Color = Color(
            lerp(a.red.toFloat(), b.red.toFloat(), t).toInt().coerceIn(0, 255),
            lerp(a.green.toFloat(), b.green.toFloat(), t).toInt().coerceIn(0, 255),
            lerp(a.blue.toFloat(), b.blue.toFloat(), t).toInt().coerceIn(0, 255),
        )
    }
}
