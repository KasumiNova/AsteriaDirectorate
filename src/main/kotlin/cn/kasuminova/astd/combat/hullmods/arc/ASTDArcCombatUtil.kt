package cn.kasuminova.astd.combat.hullmods.arc

import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import java.util.WeakHashMap

internal object ASTDArcCombatUtil {
    private val baseRefireDelayByWeapon: WeakHashMap<WeaponAPI, Float> = WeakHashMap()

    fun effectiveSystemRange(ship: ShipAPI, baseRange: Float): Float =
        try { ship.mutableStats.systemRangeBonus.computeEffective(baseRange) } catch (_: Throwable) { baseRange }

    fun isNonPdNonMissileWeapon(weapon: WeaponAPI): Boolean {
        if (safeIsDecorative(weapon)) return false
        return when (safeType(weapon)) {
            WeaponAPI.WeaponType.MISSILE,
            WeaponAPI.WeaponType.LAUNCH_BAY,
            WeaponAPI.WeaponType.DECORATIVE,
            WeaponAPI.WeaponType.SYSTEM,
            WeaponAPI.WeaponType.STATION_MODULE -> false
            else -> !isPointDefenseWeapon(weapon)
        }
    }

    fun isPointDefenseWeapon(weapon: WeaponAPI): Boolean =
        safeHasHint(weapon, WeaponAPI.AIHints.PD) ||
            safeHasHint(weapon, WeaponAPI.AIHints.PD_ONLY) ||
            safeHasHint(weapon, WeaponAPI.AIHints.PD_ALSO) ||
            safeHasHint(weapon, WeaponAPI.AIHints.ANTI_FTR)

    fun applyRefireDelayMult(weapon: WeaponAPI, mult: Float): Boolean {
        if (mult >= 0.999f) {
            restoreRefireDelay(weapon)
            return false
        }
        val cooldown = try { weapon.cooldownRemaining } catch (_: Throwable) { 0f }
        if (cooldown > 0.02f) return false
        val base = baseRefireDelayByWeapon.getOrPut(weapon) {
            try { weapon.refireDelay } catch (_: Throwable) { 0f }
        }
        if (base <= 0f) return false
        return try {
            weapon.setRefireDelay(base / mult.coerceAtLeast(0.05f))
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun restoreRefireDelay(weapon: WeaponAPI) {
        val base = baseRefireDelayByWeapon.remove(weapon) ?: return
        if (base <= 0f) return
        try { weapon.setRefireDelay(base) } catch (_: Throwable) {}
    }

    fun restoreRefireDelays(ship: ShipAPI) {
        for (weapon in try { ship.allWeapons } catch (_: Throwable) { return }) {
            restoreRefireDelay(weapon)
        }
    }

    private fun safeIsDecorative(weapon: WeaponAPI): Boolean = try { weapon.isDecorative } catch (_: Throwable) { false }
    private fun safeType(weapon: WeaponAPI): WeaponAPI.WeaponType? = try { weapon.type } catch (_: Throwable) { null }
    private fun safeHasHint(weapon: WeaponAPI, hint: WeaponAPI.AIHints): Boolean = try { weapon.hasAIHint(hint) } catch (_: Throwable) { false }
}
