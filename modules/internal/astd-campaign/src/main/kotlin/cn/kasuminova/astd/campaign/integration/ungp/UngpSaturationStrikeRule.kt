package cn.kasuminova.astd.campaign.integration.ungp

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType
import org.lwjgl.util.vector.Vector2f
import ungp.api.rules.tags.UNGP_CombatTag
import ungp.scripts.campaign.specialist.UNGP_SpecialistSettings
import java.awt.Color

/**
 * UNGP 规则「饱和打击协议」（负面）：敌方舰船每 30 秒为所有导弹武器恢复 20%/30%/40%/50%
 * 最大备弹（至少 1 枚）并立即完成装填，装填位置弹出数量浮字。
 *
 * 设计文档：docs/design/integrations/ungp.md。
 */
class UngpSaturationStrikeRule : UngpRuleEffect(), UNGP_CombatTag {

    private var period: Float = 30f
    private var ammoFraction: Float = 0.2f

    override fun updateDifficultyCache(difficulty: UNGP_SpecialistSettings.Difficulty) {
        period = getValueByDifficulty(0, difficulty)
        ammoFraction = getValueByDifficulty(1, difficulty) / 100f
    }

    override fun getValueByDifficulty(index: Int, difficulty: UNGP_SpecialistSettings.Difficulty): Float = when (index) {
        0 -> PERIOD
        1 -> difficultyValue(difficulty, 20f, 30f, 40f, 50f)
        2 -> MIN_AMMO_RESTORED.toFloat()
        else -> 0f
    }

    override fun getDescriptionParams(index: Int, difficulty: UNGP_SpecialistSettings.Difficulty): String? = when (index) {
        0 -> formatPlain(getValueByDifficulty(0, difficulty))
        1 -> formatPlain(getValueByDifficulty(1, difficulty))
        2 -> formatPlain(getValueByDifficulty(2, difficulty))
        else -> null
    }

    override fun advanceInCombat(engine: CombatEngineAPI, amount: Float) = Unit

    override fun applyPlayerShipInCombat(amount: Float, engine: CombatEngineAPI, ship: ShipAPI) = Unit

    override fun applyEnemyShipInCombat(amount: Float, ship: ShipAPI) {
        if (amount <= 0f) return
        if (!ship.isAlive || ship.isHulk) return

        val engine = com.fs.starfarer.api.Global.getCombatEngine() ?: return
        val timers = timers(engine)
        val now = engine.getTotalElapsedTime(false)
        val next = timers.getOrPut(ship.id) { now + period }
        if (now < next) return
        timers[ship.id] = now + period

        for (weapon in ship.allWeapons) {
            if (weapon.type != WeaponType.MISSILE) continue
            if (weapon.isDecorative || weapon.isDisabled || weapon.isPermanentlyDisabled) continue
            val maxAmmo = weapon.maxAmmo
            if (maxAmmo <= 0) continue
            val restore = (maxAmmo * ammoFraction).toInt().coerceAtLeast(MIN_AMMO_RESTORED)
            val current = weapon.ammo
            val loaded = (current + restore).coerceAtMost(maxAmmo)
            if (loaded <= current) continue
            weapon.ammo = loaded
            engine.addFloatingText(
                Vector2f(weapon.location), "+${loaded - current}",
                FLOATY_SIZE, FLOATY_COLOR, ship, FLOATY_FLASH_FREQ, FLOATY_FLASH_DUR,
            )
        }
    }

    companion object {
        private const val TIMERS_KEY = "astd_ungp_saturation_strike_timers"
        private const val PERIOD = 30f
        private const val MIN_AMMO_RESTORED = 1
        private const val FLOATY_SIZE = 20f
        private const val FLOATY_FLASH_FREQ = 0.25f
        private const val FLOATY_FLASH_DUR = 0.25f

        private val FLOATY_COLOR = Color(200, 140, 255, 235)

        @Suppress("UNCHECKED_CAST")
        private fun timers(engine: CombatEngineAPI): MutableMap<String, Float> =
            engine.customData.getOrPut(TIMERS_KEY) { HashMap<String, Float>() } as MutableMap<String, Float>
    }
}
