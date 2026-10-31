package cn.kasuminova.astd.campaign.integration.ungp

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType
import ungp.api.rules.tags.UNGP_CombatTag
import ungp.scripts.campaign.specialist.UNGP_SpecialistSettings

/**
 * UNGP 规则「平衡作战法则」（负面）：敌方舰船基础射程低于阈值的武器获得固定基础射程提升，
 * 提升后不超过该阈值。无特效。
 *
 * 通过克隆武器 spec 并改写 maxRange 实现，每艘舰船仅处理一次。
 * 设计文档：docs/design/integrations/ungp.md。
 */
class UngpBalancedDoctrineRule : UngpRuleEffect(), UNGP_CombatTag {

    private var threshold: Float = 500f
    private var bonus: Float = 100f

    override fun updateDifficultyCache(difficulty: UNGP_SpecialistSettings.Difficulty) {
        threshold = getValueByDifficulty(0, difficulty)
        bonus = getValueByDifficulty(1, difficulty)
    }

    override fun getValueByDifficulty(index: Int, difficulty: UNGP_SpecialistSettings.Difficulty): Float = when (index) {
        0 -> difficultyValue(difficulty, 500f, 600f, 700f, 800f)
        1 -> difficultyValue(difficulty, 100f, 150f, 200f, 250f)
        else -> 0f
    }

    override fun getDescriptionParams(index: Int, difficulty: UNGP_SpecialistSettings.Difficulty): String? = when (index) {
        0 -> formatPlain(getValueByDifficulty(0, difficulty))
        1 -> formatPlain(getValueByDifficulty(1, difficulty))
        else -> null
    }

    override fun advanceInCombat(engine: CombatEngineAPI, amount: Float) = Unit

    override fun applyPlayerShipInCombat(amount: Float, engine: CombatEngineAPI, ship: ShipAPI) = Unit

    override fun applyEnemyShipInCombat(amount: Float, ship: ShipAPI) {
        if (ship.customData.containsKey(APPLIED_KEY)) return
        if (ship.isFighter || ship.isDrone) return
        ship.customData[APPLIED_KEY] = true

        for (weapon in ship.allWeapons) {
            when (weapon.type) {
                WeaponType.MISSILE, WeaponType.DECORATIVE, WeaponType.SYSTEM,
                WeaponType.STATION_MODULE, WeaponType.LAUNCH_BAY,
                    -> continue
                else -> Unit
            }
            val base = weapon.spec?.maxRange ?: continue
            if (base <= 0f || base >= threshold) continue
            weapon.ensureClonedSpec()
            weapon.spec.maxRange = (base + bonus).coerceAtMost(threshold)
        }
    }

    companion object {
        private const val APPLIED_KEY = "astd_ungp_balanced_doctrine_applied"
    }
}
