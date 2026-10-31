package cn.kasuminova.astd.campaign.integration.ungp

import cn.kasuminova.astd.combat.hullmods.arc.ASTDArmorDamageReduction
import com.fs.starfarer.api.combat.BeamAPI
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.listeners.AdvanceableListener
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier
import org.lwjgl.util.vector.Vector2f
import ungp.api.rules.tags.UNGP_CombatTag
import ungp.scripts.campaign.specialist.UNGP_SpecialistSettings

/**
 * UNGP 规则「装甲护盾」（负面）：敌方护盾按固定装甲值参与伤害计算，
 * 单次超过阈值的护盾伤害，超出部分按难度比例额外减免。
 *
 * 减伤算法复用 [ASTDArmorDamageReduction]（与船插「等离子装甲护盾」同一公式）。无特效。
 * 设计文档：docs/design/integrations/ungp.md。
 */
class UngpArmoredShieldRule : UngpRuleEffect(), UNGP_CombatTag {

    private var armorValue: Float = 20f
    private var threshold: Float = 1000f
    private var reduction: Float = 0.10f

    override fun updateDifficultyCache(difficulty: UNGP_SpecialistSettings.Difficulty) {
        armorValue = getValueByDifficulty(0, difficulty)
        threshold = getValueByDifficulty(1, difficulty)
        reduction = getValueByDifficulty(2, difficulty)
    }

    override fun getValueByDifficulty(index: Int, difficulty: UNGP_SpecialistSettings.Difficulty): Float = when (index) {
        0 -> difficultyValue(difficulty, 20f, 40f, 60f, 80f)
        1 -> difficultyValue(difficulty, 1000f, 900f, 800f, 700f)
        2 -> difficultyValue(difficulty, 0.10f, 0.15f, 0.20f, 0.25f)
        else -> 0f
    }

    override fun getDescriptionParams(index: Int, difficulty: UNGP_SpecialistSettings.Difficulty): String? = when (index) {
        0 -> formatPlain(getValueByDifficulty(0, difficulty))
        1 -> formatPlain(getValueByDifficulty(1, difficulty))
        2 -> formatFraction(getValueByDifficulty(2, difficulty))
        else -> null
    }

    override fun advanceInCombat(engine: CombatEngineAPI, amount: Float) = Unit

    override fun applyPlayerShipInCombat(amount: Float, engine: CombatEngineAPI, ship: ShipAPI) = Unit

    override fun applyEnemyShipInCombat(amount: Float, ship: ShipAPI) {
        if (ship.isFighter || ship.isDrone) return
        if (ship.shield == null) return
        if (!ship.hasListenerOfClass(ArmoredShieldListener::class.java)) {
            ship.addListener(ArmoredShieldListener(ship, armorValue, threshold, reduction))
        }
    }

    class ArmoredShieldListener(
        private val ship: ShipAPI,
        private val armorValue: Float,
        private val threshold: Float,
        private val reduction: Float,
    ) : DamageTakenModifier, AdvanceableListener {

        override fun advance(amount: Float) {
            if (!ship.isAlive || ship.isHulk) {
                ship.removeListener(this)
            }
        }

        override fun modifyDamageTaken(param: Any?, target: CombatEntityAPI?, damage: DamageAPI?, point: Vector2f?, shieldHit: Boolean): String? {
            if (target !== ship || damage == null) return null
            val modifier = damage.modifier
            if (!shieldHit || !ship.isAlive || ship.isHulk) {
                modifier.unmodify(STAT_ID_CAP)
                modifier.unmodify(STAT_ID_ARMOR)
                return null
            }

            val original = damage.damage.coerceAtLeast(0f) *
                (if (damage.isDps) damage.dpsDuration.coerceAtLeast(0f) else 1f)
            if (original <= 0f) {
                modifier.unmodify(STAT_ID_CAP)
                modifier.unmodify(STAT_ID_ARMOR)
                return null
            }

            var incoming = original
            if (incoming > threshold) {
                val capped = threshold + (incoming - threshold) * (1f - reduction)
                modifier.modifyMult(STAT_ID_CAP, capped / incoming)
                incoming = capped
            } else {
                modifier.unmodify(STAT_ID_CAP)
            }

            val result = ASTDArmorDamageReduction.compute(
                damageAmount = incoming,
                hitStrength = ASTDArmorDamageReduction.hitStrength(
                    damage.type, damage.baseDamage, param is BeamAPI || damage.isDps,
                ),
                armorValue = armorValue,
                minArmorValue = armorValue,
                effectiveArmorMult = ship.mutableStats.effectiveArmorBonus.mult,
                maxArmorDamageReduction = ship.mutableStats.maxArmorDamageReduction.modifiedValue,
            )
            if (result.damageMultiplier < 0.999f) {
                modifier.modifyMult(STAT_ID_ARMOR, result.damageMultiplier)
            } else {
                modifier.unmodify(STAT_ID_ARMOR)
            }
            return null
        }

        companion object {
            private const val STAT_ID_CAP = "astd_ungp_armored_shield_cap"
            private const val STAT_ID_ARMOR = "astd_ungp_armored_shield_armor"
        }
    }
}
