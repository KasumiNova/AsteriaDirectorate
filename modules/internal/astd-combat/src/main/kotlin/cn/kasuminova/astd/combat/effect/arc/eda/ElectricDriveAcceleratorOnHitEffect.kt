package cn.kasuminova.astd.combat.effect.arc.eda

import cn.kasuminova.astd.api.buff.getOrCreateBuffByWeapon
import cn.kasuminova.astd.combat.effect.base.SicWeaponUpgradeFlags
import cn.kasuminova.astd.impl.combat.CombatRandom
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.OnHitEffectPlugin
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI
import com.fs.starfarer.api.util.Misc
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

class ElectricDriveAcceleratorOnHitEffect : OnHitEffectPlugin {

    private var warnedZeroMaxPct = false
    private var warnedMissingSlot = false

    override fun onHit(
        projectile: DamagingProjectileAPI,
        target: CombatEntityAPI,
        point: Vector2f?,
        shieldHit: Boolean,
        damageResult: ApplyDamageResultAPI,
        engine: CombatEngineAPI,
    ) {
        val ship = target as? ShipAPI
        if (ship != null && (ship.isHulk || ship.isPhased)) return
        // 命中回调的 point 可能为 null，回退到弹体当前位置。
        val hitPoint = point ?: projectile.location ?: return

        val weapon = projectile.weapon
        val source = projectile.source ?: weapon?.ship
        if (source == null) {
            log.info("[ASTD] EDA 装药放弃结算：弹体无来源（spec=${projectile.projectileSpecId}）")
            return
        }
        if (weapon == null) {
            log.info("[ASTD] EDA 装药放弃结算：弹体无武器引用（spec=${projectile.projectileSpecId}）")
            return
        }
        // BuffHostImpl 对空槽武器抛 IllegalArgumentException，Weapon 级 Buff 无法退化登记。
        if (weapon.slot == null) {
            if (!warnedMissingSlot) {
                warnedMissingSlot = true
                log.warn("[ASTD] EDA 武器无槽位引用，无法登记 Weapon 级装药状态，放弃结算: weapon=${weapon.id}")
            }
            return
        }

        val maxPct = ElectricDriveAcceleratorDifficulty.chargeMaxPct(DifficultyTuningImpl, source.owner)
        if (maxPct <= 0f) {
            if (!warnedZeroMaxPct) {
                warnedZeroMaxPct = true
                log.warn("[ASTD] EDA 装药上限为 0（k_s 数据异常？），跳过结算: maxPct=$maxPct, ship=${source.id}")
            }
            return
        }

        val rollPct = ElectricDriveAcceleratorDifficulty.chargeRollPct(
            maxPct,
            SicWeaponUpgradeFlags.isArmamentUpgradeActive(source),
        ) {
            val state = source.getOrCreateBuffByWeapon(ElectricDriveChargeState.BUFF_ID, weapon) {
                ElectricDriveChargeState(
                    source,
                    weapon,
                    ElectricDriveAcceleratorDifficulty.seedOf(source.id, weapon.slot.id),
                )
            } as ElectricDriveChargeState
            CombatRandom.nextFloatIn(state.seed, state.nextCallIndex(), 0f..maxPct)
        }

        val extra = ElectricDriveAcceleratorDifficulty.extraDamage(projectile.damageAmount, rollPct)
        if (!ElectricDriveAcceleratorDifficulty.shouldApplyExtra(extra)) return
        // applyDamage 不会触发二次 onHit 回调，无连锁结算。
        engine.applyDamage(target, hitPoint, extra, DamageType.KINETIC, 0f, false, true, source)

        spawnChargeFlash(engine, hitPoint)
        recordTelemetry(engine, source, extra)
    }

    private fun spawnChargeFlash(engine: CombatEngineAPI, point: Vector2f) {
        engine.addHitParticle(point, ZERO_VEL, CENTER_FLASH_SIZE, 1f, CENTER_FLASH_DURATION, FLASH_COLOR)
        repeat(SCATTER_PARTICLES) {
            val dir = Misc.random.nextFloat() * 360f
            val speed = MathUtils.getRandomNumberInRange(SCATTER_SPEED_MIN, SCATTER_SPEED_MAX)
            val vel = MathUtils.getPointOnCircumference(null, speed, dir)
            engine.addHitParticle(
                point,
                vel,
                MathUtils.getRandomNumberInRange(SCATTER_SIZE_MIN, SCATTER_SIZE_MAX),
                0.9f,
                MathUtils.getRandomNumberInRange(SCATTER_DURATION_MIN, SCATTER_DURATION_MAX),
                FLASH_COLOR,
            )
        }
    }

    private fun recordTelemetry(engine: CombatEngineAPI, source: ShipAPI, extra: Float) {
        val playerCaused = source.owner == 0
        val countKey = if (playerCaused) TELEMETRY_EXTRA_COUNT_PLAYER else TELEMETRY_EXTRA_COUNT_OTHER
        val maxKey = if (playerCaused) TELEMETRY_EXTRA_MAX_PLAYER else TELEMETRY_EXTRA_MAX_OTHER
        engine.customData[countKey] = (engine.customData[countKey] as? Int ?: 0) + 1
        val max = engine.customData[maxKey] as? Float ?: 0f
        if (extra > max) engine.customData[maxKey] = extra
    }

    companion object {
        const val TELEMETRY_EXTRA_COUNT_PLAYER = "astd_eda_extra_damage_count_player"

        const val TELEMETRY_EXTRA_MAX_PLAYER = "astd_eda_extra_damage_max_player"

        const val TELEMETRY_EXTRA_COUNT_OTHER = "astd_eda_extra_damage_count_other"

        const val TELEMETRY_EXTRA_MAX_OTHER = "astd_eda_extra_damage_max_other"

        fun extraDamageCountPlayer(engine: CombatEngineAPI): Int = engine.customData[TELEMETRY_EXTRA_COUNT_PLAYER] as? Int ?: 0

        fun extraDamageCountOther(engine: CombatEngineAPI): Int = engine.customData[TELEMETRY_EXTRA_COUNT_OTHER] as? Int ?: 0

        private val FLASH_COLOR = Color(240, 245, 252)

        private val ZERO_VEL = Vector2f(0f, 0f)
        private const val CENTER_FLASH_SIZE = 30f
        private const val CENTER_FLASH_DURATION = 0.15f
        private const val SCATTER_PARTICLES = 5
        private const val SCATTER_SPEED_MIN = 60f
        private const val SCATTER_SPEED_MAX = 120f
        private const val SCATTER_SIZE_MIN = 14f
        private const val SCATTER_SIZE_MAX = 22f
        private const val SCATTER_DURATION_MIN = 0.25f
        private const val SCATTER_DURATION_MAX = 0.4f

        private val log = Global.getLogger(ElectricDriveAcceleratorOnHitEffect::class.java)
    }
}
