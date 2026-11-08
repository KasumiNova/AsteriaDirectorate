package cn.kasuminova.astd.combat.hullmods.arc

import cn.kasuminova.astd.combat.effect.arc.starfallwing.StarfallWingMoteAi
import cn.kasuminova.astd.combat.effect.arc.starfallwing.StarfallWingMotes
import cn.kasuminova.astd.combat.effect.arc.starfallwing.StarfallWingTuning
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.renderer.effect.system.Xc002GhostWingsEffect
import cn.kasuminova.astd.ui.dsl.HullmodThemes
import cn.kasuminova.astd.ui.dsl.HullmodTone
import cn.kasuminova.astd.ui.dsl.hl
import cn.kasuminova.astd.ui.dsl.hullmodCard
import cn.kasuminova.astd.ui.dsl.v
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.BeamAPI
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.listeners.DamageDealtModifier
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier
import com.fs.starfarer.api.ui.TooltipMakerAPI
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/**
 * 虚数之翼（星翼-级内置船插）：虚数充能池、星羽翼装弹幕、虚数滑翔、超翼过载与透支折跃管理。
 * 充能 HUD 条由 [Xc002GhostWingsEffect] 渲染，本类不占用左下角状态栏。
 */
class ASTDImaginaryWingsHullMod : BaseHullMod(), DamageDealtModifier, DamageTakenModifier {

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        if (!ship.isAlive || ship.isHulk) return
        if (ship.hullSpec?.hullId != HULL_ID && ship.hullSpec?.baseHullId != HULL_ID) return
        val engine = Global.getCombatEngine() ?: return
        Xc002GhostWingsEffect.track(engine, ship)
        if (engine.isPaused) return

        if (ship.customData[KEY_LISTENER] == null) {
            ship.setCustomData(KEY_LISTENER, true)
            ship.addListener(this)
        }

        val pool = ImaginaryCharge.of(ship)
        pool.add(ImaginaryWingsTuning.CHARGE_REGEN_PER_SECOND * amount)
        if (pool.hitGainCooldown > 0f) pool.hitGainCooldown -= amount
        if (pool.overdriveRemaining > 0f) pool.overdriveRemaining -= amount

        applyGlideStats(ship, pool)
        manageOverdraft(ship, pool)
        driveBarrage(engine, ship, pool, amount)
    }

    // ---------- 虚数滑翔 ----------

    private fun applyGlideStats(ship: ShipAPI, pool: ImaginaryCharge) {
        val stats = ship.mutableStats
        val isPlayer = ship.owner == 0
        val baseMaxSpeed = stats.maxSpeed.baseValue
        val ratio = if (baseMaxSpeed > 1f) (ship.velocity.length() / baseMaxSpeed).coerceIn(0f, 1f) else 0f

        val rofBonus = DifficultyTuningImpl.valueFor(ImaginaryWingsTuning.GLIDE_ROF_PERCENT, isPlayer) * ratio
        val glideMult = 1f + rofBonus / 100f
        stats.ballisticRoFMult.modifyMult(MOD_ID_GLIDE, glideMult)
        stats.energyRoFMult.modifyMult(MOD_ID_GLIDE, glideMult)
        stats.ballisticAmmoRegenMult.modifyMult(MOD_ID_GLIDE, glideMult)
        stats.energyAmmoRegenMult.modifyMult(MOD_ID_GLIDE, glideMult)

        if (pool.inOverdrive) {
            val overdriveBonus = DifficultyTuningImpl.valueFor(ImaginaryWingsTuning.OVERDRIVE_ROF_PERCENT, isPlayer)
            val overdriveMult = 1f + overdriveBonus / 100f
            stats.ballisticRoFMult.modifyMult(MOD_ID_OVERDRIVE, overdriveMult)
            stats.energyRoFMult.modifyMult(MOD_ID_OVERDRIVE, overdriveMult)
        } else {
            stats.ballisticRoFMult.unmodifyMult(MOD_ID_OVERDRIVE)
            stats.energyRoFMult.unmodifyMult(MOD_ID_OVERDRIVE)
        }

        // 零辐能加速阈值放宽（原版统计口径为总幅能占比）
        val thresholdStat = stats.zeroFluxMinimumFluxLevel
        thresholdStat.modifyFlat(MOD_ID_FLUX, ImaginaryWingsTuning.ZERO_FLUX_THRESHOLD - thresholdStat.baseValue)
    }

    // ---------- 星羽翼装弹幕 ----------

    private fun driveBarrage(engine: CombatEngineAPI, ship: ShipAPI, pool: ImaginaryCharge, amount: Float) {
        if (pool.barrageTimer > 0f) pool.barrageTimer -= amount
        if (pool.charge < ImaginaryWingsTuning.BARRAGE_THRESHOLD) return
        if (pool.barrageTimer > 0f) return
        if (!enemyInRange(engine, ship, ImaginaryWingsTuning.BARRAGE_RANGE)) return

        pool.barrageTimer += ImaginaryWingsTuning.BARRAGE_INTERVAL
        pool.consume(ImaginaryWingsTuning.BARRAGE_COST)

        val target = resolveMoteTarget(engine, ship, pool)
        val index = pool.barrageAnchorIndex % ImaginaryWingsTuning.WING_ANCHORS.size
        pool.barrageAnchorIndex++
        for (side in intArrayOf(1, -1)) {
            val anchor = ImaginaryWingsTuning.WING_ANCHORS[index]
            StarfallWingMotes.spawn(
                engine, ship,
                ImaginaryWingsTuning.anchorWorld(ship, anchor, side),
                ImaginaryWingsTuning.launchAngleDeg(ship.facing, side),
                target,
            )
        }
    }

    private fun enemyInRange(engine: CombatEngineAPI, ship: ShipAPI, range: Float): Boolean {
        for (candidate in engine.ships) {
            val other = candidate as? ShipAPI ?: continue
            if (other === ship || other.owner == ship.owner) continue
            if (!other.isAlive || other.isHulk || other.isFighter || other.isDrone) continue
            if (MathUtils.getDistance(ship.location, other.location) <= range) return true
        }
        return false
    }

    private fun resolveMoteTarget(engine: CombatEngineAPI, ship: ShipAPI, pool: ImaginaryCharge): ShipAPI? {
        val locked = ship.shipTarget
        if (isValidMoteTarget(engine, locked, ship)) return locked
        if (isValidMoteTarget(engine, pool.lastMainGunHit, ship)) return pool.lastMainGunHit
        return StarfallWingMoteAi.nearestEnemyShip(engine, ship, ship.location, ImaginaryWingsTuning.BARRAGE_RANGE)
    }

    private fun isValidMoteTarget(engine: CombatEngineAPI, target: ShipAPI?, ship: ShipAPI): Boolean {
        if (target == null || target === ship) return false
        if (!engine.isEntityInPlay(target)) return false
        if (!target.isAlive || target.isHulk || target.isFighter || target.isDrone) return false
        return target.owner != ship.owner
    }

    // ---------- 透支折跃（0 充能时以 50% 虚数充能强行激活） ----------

    private fun manageOverdraft(ship: ShipAPI, pool: ImaginaryCharge) {
        val system = ship.system ?: return
        if (pool.overdraftArmed && system.state != ShipSystemAPI.SystemState.IDLE) {
            // 激活边沿：锁定本次为透支折跃，立即扣除 50% 虚数充能
            pool.overdraftArmed = false
            pool.overdraftActive = true
            pool.consume(ImaginaryWingsTuning.OVERDRAFT_COST)
            return
        }
        if (pool.overdraftArmed) {
            when {
                // 自然充能完成，回收虚拟充能（保留 1 发真实充能）
                system.ammo >= 2 -> {
                    system.setAmmo(1)
                    pool.overdraftArmed = false
                }
                // 充能池已跌破透支门槛，收回虚拟充能
                pool.charge < ImaginaryWingsTuning.OVERDRAFT_THRESHOLD -> {
                    system.setAmmo(0)
                    pool.overdraftArmed = false
                }
            }
            return
        }
        if (system.state == ShipSystemAPI.SystemState.IDLE && system.ammo == 0 &&
            pool.charge >= ImaginaryWingsTuning.OVERDRAFT_THRESHOLD &&
            !ship.fluxTracker.isOverloadedOrVenting
        ) {
            system.setAmmo(1)
            pool.overdraftArmed = true
        }
    }

    // ---------- 伤害监听（交火共鸣 / 滑翔减伤） ----------

    override fun modifyDamageDealt(
        param: Any?,
        target: CombatEntityAPI?,
        damage: DamageAPI?,
        point: Vector2f?,
        shieldHit: Boolean,
    ): String? {
        // 弹体与光束都算"非导弹武器命中"；BeamAPI 不继承 DamagingProjectileAPI，需单独取源舰
        val source: ShipAPI
        val isMainGun: Boolean
        when (param) {
            is DamagingProjectileAPI -> {
                if (param is MissileAPI) return null
                source = param.source ?: return null
                isMainGun = param.weapon?.id == StarfallWingTuning.WEAPON_ID
            }
            is BeamAPI -> {
                source = param.weapon?.ship ?: return null
                isMainGun = false
            }
            else -> return null
        }
        val victim = target as? ShipAPI ?: return null
        if (victim.owner == source.owner) return null
        if (source.hullSpec?.hullId != HULL_ID && source.hullSpec?.baseHullId != HULL_ID) return null

        val pool = ImaginaryCharge.of(source)
        pool.tryHitGain(ImaginaryWingsTuning.HIT_GAIN)
        if (isMainGun && !victim.isFighter && victim.isAlive) {
            pool.lastMainGunHit = victim
        }
        return null
    }

    override fun modifyDamageTaken(
        param: Any?,
        target: CombatEntityAPI?,
        damage: DamageAPI?,
        point: Vector2f?,
        shieldHit: Boolean,
    ): String? {
        if (param !is DamagingProjectileAPI) return null
        val ship = target as? ShipAPI ?: return null
        val dmg = damage ?: return null
        val baseMaxSpeed = ship.mutableStats.maxSpeed.baseValue
        if (baseMaxSpeed <= 1f) return null
        val ratio = (ship.velocity.length() / baseMaxSpeed).coerceIn(0f, 1f)
        if (ratio <= 0f) return null
        val reduction = DifficultyTuningImpl.valueFor(ImaginaryWingsTuning.GLIDE_DAMAGE_REDUCTION, ship.owner == 0)
        val mult = 1f - reduction * ratio
        if (mult >= 1f) return null
        dmg.modifier.modifyMult(MOD_ID_TAKEN, mult)
        return MOD_ID_TAKEN
    }

    // ---------- Tooltip ----------

    override fun addPostDescriptionSection(
        tooltip: TooltipMakerAPI,
        hullSize: ShipAPI.HullSize,
        ship: ShipAPI?,
        width: Float,
        isForModSpec: Boolean,
    ) {
        val isPlayer = ship == null || ship.owner == 0
        val moteDamage = DifficultyTuningImpl.valueFor(ImaginaryWingsTuning.MOTE_DAMAGE, isPlayer).toInt()
        val glideRof = DifficultyTuningImpl.valueFor(ImaginaryWingsTuning.GLIDE_ROF_PERCENT, isPlayer).toInt()
        val glideReduction = DifficultyTuningImpl.valueFor(ImaginaryWingsTuning.GLIDE_DAMAGE_REDUCTION, isPlayer)
        tooltip.hullmodCard(width, THEME, spec?.displayName) {
            para("ui.hullmod.imaginary_wings.desc")
            heading("ui.hullmod.imaginary_wings.section.charge")
            para(
                "ui.hullmod.imaginary_wings.line.charge",
                v("regen", ImaginaryWingsTuning.CHARGE_REGEN_PER_SECOND.toInt()),
                v("hit", ImaginaryWingsTuning.HIT_GAIN.toInt()),
                v("empowered", ImaginaryWingsTuning.EMPOWERED_HIT_GAIN.toInt()),
                hl("+${ImaginaryWingsTuning.HIT_GAIN.toInt()}%", HullmodTone.POSITIVE),
                hl("+${ImaginaryWingsTuning.EMPOWERED_HIT_GAIN.toInt()}%", HullmodTone.POSITIVE),
            )
            heading("ui.hullmod.imaginary_wings.section.barrage")
            para(
                "ui.hullmod.imaginary_wings.line.barrage",
                v("threshold", ImaginaryWingsTuning.BARRAGE_THRESHOLD.toInt()),
                v("range", ImaginaryWingsTuning.BARRAGE_RANGE.toInt()),
                v("interval", ImaginaryWingsTuning.BARRAGE_INTERVAL),
                v("cost", ImaginaryWingsTuning.BARRAGE_COST.toInt()),
                v("damage", moteDamage),
                v("emp", ImaginaryWingsTuning.MOTE_EMP.toInt()),
                hl("$moteDamage", HullmodTone.HIGHLIGHT),
            )
            para(
                "ui.hullmod.imaginary_wings.line.erosion",
                v("per", (StarfallWingTuning.EROSION_TAKEN_PER_STACK * 100f).toInt()),
                v("max", StarfallWingTuning.EROSION_MAX_STACKS),
                v("dur", StarfallWingTuning.EROSION_DURATION_SECONDS.toInt()),
                hl("${(StarfallWingTuning.EROSION_TAKEN_PER_STACK * 100f).toInt()}%", HullmodTone.HIGHLIGHT),
            )
            heading("ui.hullmod.imaginary_wings.section.glide")
            para(
                "ui.hullmod.imaginary_wings.line.glide",
                v("flux", (ImaginaryWingsTuning.ZERO_FLUX_THRESHOLD * 100f).toInt()),
                v("rof", glideRof),
                v("reduction", (glideReduction * 100f).toInt()),
                hl("+$glideRof%", HullmodTone.POSITIVE),
                hl("-${(glideReduction * 100f).toInt()}%", HullmodTone.POSITIVE),
            )
            spacer(6f)
            para("ui.hullmod.export.difficulty_note", hl("难度系数", HullmodTone.DEFAULT))
        }
    }

    override fun isApplicableToShip(ship: ShipAPI): Boolean {
        val hullId = ship.hullSpec?.hullId
        val baseHullId = ship.hullSpec?.baseHullId
        return hullId == HULL_ID || baseHullId == HULL_ID
    }

    override fun showInRefitScreenModPickerFor(ship: ShipAPI): Boolean = false

    override fun getBorderColor(): Color = THEME.borderColor

    override fun getNameColor(): Color = THEME.nameColor

    companion object {
        private const val HULL_ID = "astd_xc_002"

        private const val MOD_ID_GLIDE = "astd_imaginary_wings_glide"

        private const val MOD_ID_FLUX = "astd_imaginary_wings_flux"

        private const val MOD_ID_OVERDRIVE = "astd_imaginary_wings_overdrive"

        private const val MOD_ID_TAKEN = "astd_imaginary_wings_taken"

        private const val KEY_LISTENER = "astd_imaginary_wings_listener"

        private val THEME = HullmodThemes.IMAGINARY
    }
}
