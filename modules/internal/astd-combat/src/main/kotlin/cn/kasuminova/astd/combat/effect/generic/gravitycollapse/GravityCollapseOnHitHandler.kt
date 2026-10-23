package cn.kasuminova.astd.combat.effect.generic.gravitycollapse

import cn.kasuminova.astd.combat.effect.base.SicWeaponUpgradeFlags
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BeamAPI
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShieldAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.util.IntervalUtil
import com.fs.starfarer.api.util.Misc
import org.boxutil.units.standard.entity.DistortionEntity
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.combat.CombatUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.random.Random.Default.nextDouble
import kotlin.random.Random.Default.nextFloat

internal class GravityCollapseOnHitHandler(
    private val config: GravityCollapseOnHitConfig,
) {

    companion object {
        private val log = Global.getLogger(GravityCollapseOnHitHandler::class.java)

        private const val SHIELD_SNAP_EPS = 12f

        private const val HE_VS_ARMOR_MULT = 2f

        private const val FIGHTER_DAMAGE_MULT = 2f
    }

    private val hitCollapseInterval = IntervalUtil(config.tickInterval, config.tickInterval)
    private val extraDamageInterval = IntervalUtil(config.tickInterval, config.tickInterval)
    private var wasHittingLastFrame = false

    private var resolved: GravityCollapseDifficulty.ResolvedValues? = null

    fun reset() {
        hitCollapseInterval.forceIntervalElapsed()
        extraDamageInterval.forceIntervalElapsed()
        wasHittingLastFrame = false
        resolved = null
    }

    fun advance(
        engine: CombatEngineAPI,
        amount: Float,
        weapon: WeaponAPI,
        beam: BeamAPI?,
        aoePoint: Vector2f,
        panelDamage: Float,
    ) {
        if (beam == null) {
            if (wasHittingLastFrame) {
                hitCollapseInterval.forceIntervalElapsed()
                extraDamageInterval.forceIntervalElapsed()
            }
            wasHittingLastFrame = false
            return
        }

        val target = beam.damageTarget

        val isHitting = target != null
        if (config.requireDamageTarget && !isHitting) {
            if (wasHittingLastFrame) {
                hitCollapseInterval.forceIntervalElapsed()
                extraDamageInterval.forceIntervalElapsed()
            }
            wasHittingLastFrame = false
            return
        }
        wasHittingLastFrame = true

        val point = Vector2f(aoePoint)

        hitCollapseInterval.advance(amount)
        if (hitCollapseInterval.intervalElapsed()) {
            spawnSustainedHitCollapseDistortion(engine, point)
        }

        extraDamageInterval.advance(amount)
        if (!extraDamageInterval.intervalElapsed()) return

        val values = resolved ?: resolveDifficulty(weapon).also { resolved = it }
        val tickDamageBase = (panelDamage.coerceAtLeast(0f) * config.tickInterval * values.aoeDamageRatio)
            .coerceAtLeast(0f)
        if (tickDamageBase <= 0f) return

        val source = weapon.ship
        val owner = source?.owner
        val radius = config.aoeRadius.coerceAtLeast(1f)

        if (config.affectNonShips) {
            val entities = CombatUtils.getEntitiesWithinRange(point, radius)
            for (other in entities) {
                if (other == null) continue
                if (source != null && other === source) continue

                // 对飞行中的非导弹射弹 applyDamage 会触发其 onHit 链路，直接跳过。
                if (other is DamagingProjectileAPI && other !is MissileAPI) continue

                if (!config.affectAlliesAndNeutral && owner != null) {
                    val otherOwner = other.owner
                    if (otherOwner == owner) continue
                }

                if (owner != null && other.owner == owner &&
                    (other is MissileAPI || (other as? ShipAPI)?.isFighter == true)
                ) continue

                val ship = other as? ShipAPI
                if (ship != null && ship.isHulk && !config.affectHulks) continue

                val loc = other.location
                val cr = other.collisionRadius

                val distToSurface = (MathUtils.getDistance(point, loc) - cr).coerceAtLeast(0f)
                if (distToSurface > radius) continue

                applyTickToEntity(engine, source, other, point, tickDamageBase, values)
            }
        } else {
            for (other in engine.ships) {
                if (source != null && other === source) continue
                if (!config.affectAlliesAndNeutral && owner != null && other.owner == owner) continue
                if (other.isHulk && !config.affectHulks) continue

                val distToHull = (MathUtils.getDistance(point, other.location) - other.collisionRadius).coerceAtLeast(0f)
                if (distToHull > radius) continue

                applyTickToEntity(engine, source, other, point, tickDamageBase, values)
            }
        }

        spawnExtraHitTickFx(engine, point)
    }

    private fun applyTickToEntity(
        engine: CombatEngineAPI,
        source: ShipAPI?,
        other: CombatEntityAPI,
        point: Vector2f,
        damage: Float,
        values: GravityCollapseDifficulty.ResolvedValues,
    ) {
        val ship = other as? ShipAPI
        val shieldCovered = ship?.let { shieldCovers(it, point) } ?: false

        val applyPoint = if (ship != null && shieldCovered) {
            resolveShieldedDamagePoint(ship, point)
        } else {
            point
        }

        val armamentUpgrade = SicWeaponUpgradeFlags.isArmamentUpgradeActive(source)
        var totalDamage = damage +
                GravityCollapseDifficulty.hullDamageBasis(other.hitpoints, other.maxHitpoints, armamentUpgrade) *
                values.hullDamageRatio
        if (ship != null && ship.isFighter) totalDamage *= FIGHTER_DAMAGE_MULT
        // 原版相位免疫仅为碰撞层行为，applyDamage 不检查相位状态，程序化结算正常生效。
        if (ship != null && ship.isPhased) totalDamage *= values.phasedDamageRatio

        val inputDamage = if (ship != null && !ship.isHulk && !shieldCovered) {
            resolvePiercedInputDamage(ship, point, totalDamage)
        } else {
            totalDamage
        }

        if (inputDamage > 0f) {
            engine.applyDamage(
                other,
                applyPoint,
                inputDamage,
                DamageType.HIGH_EXPLOSIVE,
                0f,
                false,
                false,
                source,
                true,
            )
        }

        if (ship == null || ship.isHulk || shieldCovered) return

        GravityCollapseMobilityDebuff.apply(
            engine = engine,
            source = source,
            target = ship,
            reduction = values.mobilityReduction,
            duration = values.mobilityDuration,
        )
    }

    private fun resolvePiercedInputDamage(target: ShipAPI, point: Vector2f, totalDamage: Float): Float {
        if (totalDamage <= 0f) return totalDamage

        val grid = target.armorGrid ?: return totalDamage
        val cell = grid.getCellAtLocation(point) ?: return totalDamage
        if (cell.size < 2) return totalDamage
        val armor = grid.getArmorValue(cell[0], cell[1]).coerceAtLeast(0f)

        val stats = target.mutableStats
        val effectiveArmor = GravityCollapseArmorPiercing.effectiveArmor(
            cellArmor = armor,
            minArmor = grid.armorRating * stats.minArmorFraction.modifiedValue,
            effectiveArmorMult = stats.effectiveArmorBonus.mult,
        )
        val maxReduction = stats.maxArmorDamageReduction.modifiedValue

        return GravityCollapseArmorPiercing.solvePiercedInputDamage(
            damage = totalDamage,
            pierceMult = config.armorPierceDamageMult,
            effectiveArmor = effectiveArmor,
            maxReduction = maxReduction,
            typeMultVsArmor = HE_VS_ARMOR_MULT,
        )
    }

    private fun resolveDifficulty(weapon: WeaponAPI): GravityCollapseDifficulty.ResolvedValues =
        GravityCollapseDifficulty.resolve(DifficultyTuningImpl, weapon.ship?.owner, config, weapon.id)

    private fun shieldCovers(ship: ShipAPI, point: Vector2f): Boolean {
        val shield = ship.shield ?: return false
        if (shield.type == ShieldAPI.ShieldType.NONE) return false
        if (!shield.isOn) return false
        if (!shield.isWithinArc(point)) return false
        val sl = shield.location ?: return false
        val r = shield.radius
        if (r <= 0f) return false
        return MathUtils.getDistance(point, sl) <= r + SHIELD_SNAP_EPS
    }

    private fun resolveShieldedDamagePoint(ship: ShipAPI, explosionPoint: Vector2f): Vector2f {
        val shield = ship.shield ?: return explosionPoint
        if (!shield.isOn) return explosionPoint

        if (!shield.isWithinArc(explosionPoint)) return explosionPoint

        val sl = shield.location ?: return explosionPoint

        val r = shield.radius
        if (r <= 0f) return explosionPoint

        val d = MathUtils.getDistance(explosionPoint, sl)
        if (d > r + SHIELD_SNAP_EPS) return explosionPoint

        val ang = Misc.getAngleInDegrees(sl, explosionPoint)
        return MathUtils.getPointOnCircumference(sl, r, ang)
    }

    private fun spawnSustainedHitCollapseDistortion(engine: CombatEngineAPI, point: Vector2f) {
        BoxUtilCombatVfx.ensureReady(engine)

        DistortionEntity().apply {
            setGlobalTimer(0.02f, 0.06f, 0.25f)

            setInnerIn(0.35f, 0.35f)
            setInnerFull(0.35f, 0.35f)
            setInnerOut(0.35f, 0.35f)

            innerHardness = 0.90f
            ringHardness = 0.70f

            val vfxS = config.vfxScale.coerceIn(0.35f, 2.25f)
            setSizeIn(config.aoeRadius * 0.35f * vfxS, config.aoeRadius * 0.35f * vfxS)
            setSizeFull(config.aoeRadius * 0.7f * vfxS, config.aoeRadius * 0.7f * vfxS)
            setSizeOut(config.aoeRadius * vfxS, config.aoeRadius * vfxS)

            powerIn = 0.4f
            powerFull = 0.8f
            powerOut = 0f

            setLocation(point)

            val state = BoxUtilCombatVfx.addEntity(engine, this)
            if (state != 0) {
                log.warn("[ASTD] 引力坍缩炮：命中坍缩扭曲 addEntity 失败（state=$state），本次视觉缺席")
                delete()
            }
        }
    }

    private fun spawnExtraHitTickFx(engine: CombatEngineAPI, point: Vector2f) {
        val vis = 1.5f
        val vfxS = config.vfxScale.coerceIn(0.35f, 2.25f)

        val color = Color(255, 35, 35, 55)
        repeat(5) {
            engine.addNebulaParticle(
                point,
                Vector2f(nextDouble(-10.0, 10.0).toFloat(), nextDouble(-10.0, 10.0).toFloat()),
                135f * vis * vfxS,
                3f * nextFloat(),
                0.1f,
                0.2f,
                0.8f,
                color,
                true
            )
        }
    }

}
