package cn.kasuminova.astd.combat.effect.arc.qiongjue

import cn.kasuminova.astd.combat.effect.arc.qiongjue.QiongjueDamageDealtModifier.Companion.ensure
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.combat.listeners.DamageDealtModifier
import org.lwjgl.util.vector.Vector2f

// 同舰同 spec 武器共享 WeaponAPI.damage.modifier，逐发伤害必须写入回调提供的独立 DamageAPI。
class QiongjueDamageDealtModifier : DamageDealtModifier {

    override fun modifyDamageDealt(
        param: Any?,
        target: CombatEntityAPI?,
        damage: DamageAPI?,
        point: Vector2f?,
        shieldHit: Boolean,
    ): String? {
        val projectile = param as? DamagingProjectileAPI ?: return null
        val weapon = projectile.weapon ?: return null
        if (weapon.spec?.weaponId != QiongjuePhaseRailgunDifficulty.WEAPON_ID) return null
        val ship = projectile.source ?: return null
        val buff = ship.qiongjueCalcStacks() ?: return null
        if (buff.stacks <= 0) return null

        val perStack = QiongjueStackMath.resolve(DifficultyTuningRef, QiongjuePhaseRailgunDifficulty.PER_STACK_BONUS, ship.owner)
        val mult = QiongjueStackMath.mult(buff.stacks, perStack)
        damage?.modifier?.modifyMult(MOD_ID, mult)

        val engine = Global.getCombatEngine()
        if (engine != null) {
            engine.customData[telemetryKey(ship, weapon)] = mult
        }
        return MOD_ID
    }

    companion object {
        const val MOD_ID = "astd_qiongjue_dealt"

        const val TELEMETRY_DEALT_MULT_PREFIX = "astd_qiongjue_dealt_mult:"

        private val DifficultyTuningRef = cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl

        fun ensure(ship: ShipAPI) {
            if (!ship.hasListenerOfClass(QiongjueDamageDealtModifier::class.java)) {
                ship.addListener(QiongjueDamageDealtModifier())
            }
        }

        fun telemetryKey(ship: ShipAPI, weapon: WeaponAPI): String =
            "$TELEMETRY_DEALT_MULT_PREFIX${ship.id}:${weapon.slot?.id ?: "unknown"}"

        fun dealtMult(engine: CombatEngineAPI, ship: ShipAPI, weapon: WeaponAPI): Float =
            engine.customData[telemetryKey(ship, weapon)] as? Float ?: -1f
    }
}
