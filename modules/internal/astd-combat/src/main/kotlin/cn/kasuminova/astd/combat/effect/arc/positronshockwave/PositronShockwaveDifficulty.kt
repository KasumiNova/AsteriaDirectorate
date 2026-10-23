package cn.kasuminova.astd.combat.effect.arc.positronshockwave

import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.combat.effect.arc.positronshockwave.PositronShockwaveDifficulty.DAMAGE_MULT
import cn.kasuminova.astd.combat.effect.arc.positronshockwave.PositronShockwaveDifficulty.resolve
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI

object PositronShockwaveDifficulty {
    private val log = Global.getLogger(PositronShockwaveDifficulty::class.java)

    const val PANEL_DAMAGE = 150f

    val CONE_ANGLE = ScalingEntry(45f, 56.25f, 90f)

    val CONE_RANGE = ScalingEntry(200f, 250f, 400f)

    val DAMAGE_MULT = ScalingEntry(0.75f, 1f, 1.75f)

    val DENSITY_BONUS = ScalingEntry(0.1f, 0.2f, 0.5f)

    const val FUSE_RANGE_RATIO = 0.4f

    const val SIC_FIGHTER_EXTRA_HE_MULT = 1f

    fun fighterExtraHeDamage(dealtDamage: Float, armamentUpgrade: Boolean): Float =
        if (armamentUpgrade) dealtDamage.coerceAtLeast(0f) * SIC_FIGHTER_EXTRA_HE_MULT else 0f

    @Volatile
    private var nullSourceWarned = false

    data class Resolved(val halfAngleDeg: Float, val range: Float, val damage: Float, val densityBonus: Float)

    fun resolve(source: ShipAPI?): Resolved {
        if (source == null && !nullSourceWarned) {
            nullSourceWarned = true
            log.warn("正电子冲击波弹体无来源舰船，难度取值按敌方口径（k_s 映射）结算")
        }
        val isPlayer = source?.owner == 0
        return Resolved(
            DifficultyTuningImpl.valueFor(CONE_ANGLE, isPlayer) / 2f,
            DifficultyTuningImpl.valueFor(CONE_RANGE, isPlayer),
            PANEL_DAMAGE * DifficultyTuningImpl.valueFor(DAMAGE_MULT, isPlayer),
            DifficultyTuningImpl.valueFor(DENSITY_BONUS, isPlayer),
        )
    }

    fun reachedMaxRange(elapsed: Float, speed: Float, range: Float): Boolean {
        if (speed <= 0f || speed.isNaN()) {
            log.error("正电子冲击波弹体 moveSpeed 非法（$speed），属配置错误，立即按当前位置引爆（不允许静默消散）")
            return true
        }
        return elapsed * speed >= range
    }

    fun isFuseTarget(entity: CombatEntityAPI, owner: Int): Boolean = when (entity) {
        is MissileAPI -> entity.owner != owner
        is ShipAPI -> (entity.isFighter || entity.isDrone) && !entity.isHulk && entity.owner != owner
        else -> false
    }
}
