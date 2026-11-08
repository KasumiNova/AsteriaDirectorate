package cn.kasuminova.astd.campaign.integration.ungp

import cn.kasuminova.astd.impl.combat.DcrDamageAttribution
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import cn.kasuminova.astd.renderer.boxutil.pool.FlareParticlePool
import cn.kasuminova.astd.renderer.boxutil.pool.PooledCombatVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CollisionClass
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.ShipAPI
import org.boxutil.units.standard.entity.DistortionEntity
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.VectorUtils
import org.lwjgl.util.vector.Vector2f
import ungp.api.rules.tags.UNGP_CombatTag
import ungp.scripts.campaign.specialist.UNGP_SpecialistSettings
import java.awt.Color

/**
 * UNGP 规则「相位震荡」：我方相位舰结束相位状态（上浮）时，以自身为中心释放引力脉冲，
 * 对周围敌方舰船/战机造成能量 + 等额 EMP 伤害（护盾可挡）并按自舰尺寸施加外推力；
 * 敌方射弹不被摧毁，改为径向外推并随机偏转飞行角度。
 *
 * 设计文档：docs/design/integrations/ungp.md。脉冲半径与冲击力度按自舰尺寸放大（文档未给半径数值，
 * 裁定为护卫舰 250 / 驱逐舰 350 / 巡洋舰 450 / 主力舰 600）。
 */
class UngpPhaseShockwaveRule : UngpRuleEffect(), UNGP_CombatTag {

    private var damage: Float = 300f
    private var cooldown: Float = 16f

    override fun updateDifficultyCache(difficulty: UNGP_SpecialistSettings.Difficulty) {
        damage = getValueByDifficulty(0, difficulty)
        cooldown = getValueByDifficulty(1, difficulty)
    }

    override fun getValueByDifficulty(index: Int, difficulty: UNGP_SpecialistSettings.Difficulty): Float = when (index) {
        0 -> difficultyValue(difficulty, 300f, 400f, 500f, 600f)
        1 -> difficultyValue(difficulty, 16f, 14f, 12f, 10f)
        else -> 0f
    }

    override fun getDescriptionParams(index: Int, difficulty: UNGP_SpecialistSettings.Difficulty): String? = when (index) {
        0 -> formatPlain(getValueByDifficulty(0, difficulty))
        1 -> formatPlain(getValueByDifficulty(1, difficulty))
        else -> null
    }

    override fun advanceInCombat(engine: CombatEngineAPI, amount: Float) = Unit

    override fun applyEnemyShipInCombat(amount: Float, ship: ShipAPI) = Unit

    override fun applyPlayerShipInCombat(amount: Float, engine: CombatEngineAPI, ship: ShipAPI) {
        if (amount <= 0f) return
        if (!ship.isAlive || ship.isHulk) return
        if (!ship.hullSpec.isPhase) return

        val tracks = tracks(engine)
        val track = tracks.getOrPut(ship.id) { Track() }
        val phased = ship.isPhased
        if (track.wasPhased && !phased) {
            val now = engine.getTotalElapsedTime(false)
            if (now >= track.cooldownUntil) {
                track.cooldownUntil = now + cooldown
                triggerPulse(engine, ship)
            }
        }
        track.wasPhased = phased
    }

    private fun triggerPulse(engine: CombatEngineAPI, ship: ShipAPI) {
        val center = Vector2f(ship.location)
        val size = ship.hullSize
        val radius = radiusOf(size)
        val push = pushOf(size)

        for (target in engine.ships) {
            if (target === ship || !target.isAlive || target.isHulk) continue
            if (target.owner == ship.owner) continue
            if (target.collisionClass == CollisionClass.NONE) continue
            val dist = MathUtils.getDistance(center, target.location)
            if (dist > radius + target.collisionRadius) continue
            applyHit(engine, ship, target, center, dist, push, targetPushFactor(target))
        }
        for (missile in engine.missiles) {
            if (missile.owner == ship.owner || missile.isFizzling || missile.didDamage()) continue
            val dist = MathUtils.getDistance(center, missile.location)
            if (dist > radius) continue
            // 不摧毁射弹：径向外推并随机偏转飞行角度，朝向对齐新速度方向
            applyPush(missile.location, missile.velocity, center, push * MISSILE_PUSH_FACTOR)
            val turn = MathUtils.getRandomNumberInRange(-MISSILE_DEFLECT_MAX_DEG, MISSILE_DEFLECT_MAX_DEG)
            VectorUtils.rotate(missile.velocity, turn, missile.velocity)
            missile.facing = VectorUtils.getFacing(missile.velocity)
        }

        spawnVfx(engine, center, size, radius)
    }

    private fun applyHit(
        engine: CombatEngineAPI,
        ship: ShipAPI,
        target: ShipAPI,
        center: Vector2f,
        dist: Float,
        push: Float,
        targetFactor: Float,
    ) {
        // 命中点取目标朝向脉冲源的船体边缘，确保护盾朝向正确参与结算
        val point = if (dist > 0.01f) {
            val nx = (target.location.x - center.x) / dist
            val ny = (target.location.y - center.y) / dist
            Vector2f(target.location.x - nx * target.collisionRadius * 0.8f, target.location.y - ny * target.collisionRadius * 0.8f)
        } else {
            Vector2f(target.location)
        }
        engine.applyDamage(target, point, damage, DamageType.ENERGY, damage, false, true, ship, false)
        DcrDamageAttribution.clarify(ship, target, DamageType.ENERGY, damage, damage, rule?.name)
        applyPush(target.location, target.velocity, center, push * targetFactor)
    }

    private fun applyPush(targetPos: Vector2f, targetVel: Vector2f, center: Vector2f, force: Float) {
        val dx = targetPos.x - center.x
        val dy = targetPos.y - center.y
        val dist = MathUtils.getDistance(center, targetPos)
        if (dist <= 0.01f) return
        targetVel.x += dx / dist * force
        targetVel.y += dy / dist * force
    }

    private fun spawnVfx(engine: CombatEngineAPI, center: Vector2f, size: ShipAPI.HullSize, radius: Float) {
        val life = SHOCK_FADE_IN + SHOCK_FULL + SHOCK_FADE_OUT
        PooledCombatVfx.spawnSprite(
            engine, SHOCKWAVE_POOL,
            center.x, center.y,
            scaleX = radius * SHOCK_START_FRACTION, scaleY = radius * SHOCK_START_FRACTION,
            scaleRateX = radius * (1f - SHOCK_START_FRACTION) / life,
            scaleRateY = radius * (1f - SHOCK_START_FRACTION) / life,
            color = SHOCK_COLOR, emissiveColor = SHOCK_EMISSIVE,
            fadeIn = SHOCK_FADE_IN, full = SHOCK_FULL, fadeOut = SHOCK_FADE_OUT,
        )

        // 同生命周期的空间扭曲实体：与冲击波同步扩散并淡出（BoxUtil DistortionEntity 环形扭曲）
        BoxUtilCombatVfx.ensureReady(engine)
        val distortion = DistortionEntity()
        distortion.setGlobalTimer(SHOCK_FADE_IN, SHOCK_FULL, SHOCK_FADE_OUT)
        distortion.setSizeIn(radius * SHOCK_START_FRACTION, radius * SHOCK_START_FRACTION)
        distortion.setSizeFull(radius * DISTORTION_FULL_FRACTION, radius * DISTORTION_FULL_FRACTION)
        distortion.setSizeOut(radius, radius)
        distortion.setInnerIn(DISTORTION_INNER_RATIO, DISTORTION_INNER_RATIO)
        distortion.setInnerFull(DISTORTION_INNER_RATIO, DISTORTION_INNER_RATIO)
        distortion.setInnerOut(DISTORTION_INNER_RATIO, DISTORTION_INNER_RATIO)
        distortion.innerHardness = DISTORTION_INNER_HARDNESS
        distortion.ringHardness = DISTORTION_RING_HARDNESS
        distortion.powerIn = DISTORTION_POWER_IN
        distortion.powerFull = DISTORTION_POWER_FULL
        distortion.powerOut = 0f
        distortion.setLocation(center)
        val distortionState = BoxUtilCombatVfx.addEntity(engine, distortion)
        if (distortionState != 0) {
            log.warn("[ASTD] 相位震荡冲击扭曲注册失败（addEntity 返回 $distortionState），本次扭曲视觉缺席")
            distortion.delete()
        }

        // SMOOTH + SMOOTH_DISC 混合外散粒子（与引力电子干扰力场同一表现体系）
        val glowPool = FlareParticlePool.obtain(
            engine, GLOW_POOL_KEY, CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
            smooth = true, sizeX = GLOW_SIZE, sizeY = GLOW_SIZE,
            capacity = POOL_CAPACITY, maxDuration = POOL_MAX_DURATION,
        )
        val pillarPool = FlareParticlePool.obtain(
            engine, PILLAR_POOL_KEY, CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
            smooth = false, sizeX = PILLAR_LENGTH, sizeY = PILLAR_WIDTH,
            capacity = POOL_CAPACITY, maxDuration = POOL_MAX_DURATION,
        )
        if (glowPool != null && pillarPool != null) {
            repeat(particleCountOf(size)) {
                val angle = MathUtils.getRandomNumberInRange(0f, 360f)
                val speed = MathUtils.getRandomNumberInRange(PARTICLE_SPEED_MIN, PARTICLE_SPEED_MAX)
                val vel = MathUtils.getPointOnCircumference(ZERO, speed, angle)
                val scale = MathUtils.getRandomNumberInRange(PARTICLE_SCALE_MIN, PARTICLE_SCALE_MAX)
                glowPool.spawn(center, 0f, vel, scale, scale, PARTICLE_CORE, PARTICLE_FRINGE, PARTICLE_FADE_IN, PARTICLE_FULL, PARTICLE_FADE_OUT)
                pillarPool.spawn(center, angle, vel, scale, scale, PARTICLE_CORE, PARTICLE_FRINGE, PARTICLE_FADE_IN, PARTICLE_FULL, PARTICLE_FADE_OUT)
            }
        }
    }

    private class Track {
        var wasPhased: Boolean = false
        var cooldownUntil: Float = 0f
    }

    companion object {
        private val log = Global.getLogger(UngpPhaseShockwaveRule::class.java)

        private const val TRACKS_KEY = "astd_ungp_phase_shockwave_tracks"

        private const val MISSILE_PUSH_FACTOR = 1.2f
        private const val MISSILE_DEFLECT_MAX_DEG = 30f

        private const val SHOCK_START_FRACTION = 0.2f
        private const val SHOCK_FADE_IN = 0.10f
        private const val SHOCK_FULL = 0.20f
        private const val SHOCK_FADE_OUT = 0.90f

        private const val DISTORTION_FULL_FRACTION = 0.7f
        private const val DISTORTION_INNER_RATIO = 0.55f
        private const val DISTORTION_INNER_HARDNESS = 0.85f
        private const val DISTORTION_RING_HARDNESS = 0.65f
        private const val DISTORTION_POWER_IN = 0.4f
        private const val DISTORTION_POWER_FULL = 0.8f

        private const val PARTICLE_SPEED_MIN = 250f
        private const val PARTICLE_SPEED_MAX = 450f
        private const val PARTICLE_SCALE_MIN = 0.75f
        private const val PARTICLE_SCALE_MAX = 1.25f
        private const val PARTICLE_FADE_IN = 0.10f
        private const val PARTICLE_FULL = 0.30f
        private const val PARTICLE_FADE_OUT = 1.00f

        private const val GLOW_SIZE = 30f
        private const val PILLAR_LENGTH = 120f
        private const val PILLAR_WIDTH = 14f
        private const val POOL_CAPACITY = 128
        private const val POOL_MAX_DURATION = 3f

        private const val GLOW_POOL_KEY = "astd_ungp_phase_shockwave_glow_pool"
        private const val PILLAR_POOL_KEY = "astd_ungp_phase_shockwave_pillar_pool"

        private val SHOCK_COLOR = Color(186, 120, 255, 60)
        private val SHOCK_EMISSIVE = Color(200, 150, 255, 70)
        private val PARTICLE_CORE = Color(232, 205, 255, 80)
        private val PARTICLE_FRINGE = Color(186, 120, 255, 60)

        private val SHOCKWAVE_POOL = PooledCombatVfx.SpritePoolKey(
            spritePath = "graphics/fx/astd_shockwave.png",
            layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
        )

        private val ZERO = Vector2f(0f, 0f)

        @Suppress("UNCHECKED_CAST")
        private fun tracks(engine: CombatEngineAPI): MutableMap<String, Track> =
            engine.customData.getOrPut(TRACKS_KEY) { HashMap<String, Track>() } as MutableMap<String, Track>

        private fun radiusOf(size: ShipAPI.HullSize): Float = when (size) {
            ShipAPI.HullSize.FRIGATE -> 250f
            ShipAPI.HullSize.DESTROYER -> 350f
            ShipAPI.HullSize.CRUISER -> 450f
            else -> 600f
        }

        private fun pushOf(size: ShipAPI.HullSize): Float = when (size) {
            ShipAPI.HullSize.FRIGATE -> 120f
            ShipAPI.HullSize.DESTROYER -> 180f
            ShipAPI.HullSize.CRUISER -> 250f
            else -> 340f
        }

        private fun particleCountOf(size: ShipAPI.HullSize): Int = when (size) {
            ShipAPI.HullSize.FRIGATE -> 10
            ShipAPI.HullSize.DESTROYER -> 20
            ShipAPI.HullSize.CRUISER -> 30
            else -> 40
        }

        private fun targetPushFactor(target: ShipAPI): Float = when (target.hullSize) {
            ShipAPI.HullSize.FIGHTER -> 1.2f
            ShipAPI.HullSize.FRIGATE -> 1.0f
            ShipAPI.HullSize.DESTROYER -> 0.7f
            ShipAPI.HullSize.CRUISER -> 0.45f
            else -> 0.25f
        }
    }
}
