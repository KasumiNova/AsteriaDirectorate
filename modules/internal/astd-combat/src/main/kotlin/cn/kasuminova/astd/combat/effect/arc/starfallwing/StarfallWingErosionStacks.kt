package cn.kasuminova.astd.combat.effect.arc.starfallwing

import cn.kasuminova.astd.api.buff.Buff
import cn.kasuminova.astd.api.buff.BuffHost
import cn.kasuminova.astd.api.buff.BuffLifetime
import cn.kasuminova.astd.api.buff.buffHost
import cn.kasuminova.astd.api.buff.getBuff
import cn.kasuminova.astd.api.combat.CombatFeedback
import cn.kasuminova.astd.impl.combat.CombatFeedbackImpl
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import kotlin.math.floor
import kotlin.math.round

/**
 * 振频蚀穿：星羽光矢命中护盾时叠加，每层独立 4s 计时，
 * 每层使目标最终护盾承伤效率劣化 2%（加法），最多 15 层，劣化后承伤效率不超过 1.0。
 */
class StarfallWingErosionStacks(
    private val ship: ShipAPI,
    private val engine: CombatEngineAPI,
    private val host: BuffHost,
) : Buff {

    var showOnPlayerHud: Boolean = false

    private val layers = ArrayList<Float>()

    val stacks: Int get() = layers.size

    override val id: String get() = BUFF_ID
    override val lifetime: BuffLifetime get() = BuffLifetime.HOST_BOUND

    fun addLayer() {
        // 满层时挤掉最早一层，保证易伤持续新鲜
        if (layers.size >= StarfallWingTuning.EROSION_MAX_STACKS) layers.removeAt(0)
        layers += StarfallWingTuning.EROSION_DURATION_SECONDS
        refreshShieldMult()
    }

    override fun advance(amount: Float) {
        if (layers.isEmpty()) return
        for (i in layers.indices.reversed()) {
            val remaining = layers[i] - amount
            if (remaining <= 0f) layers.removeAt(i) else layers[i] = remaining
        }
        if (layers.isEmpty()) {
            host.remove(this)
            return
        }
        refreshShieldMult()
        maintainHud()
    }

    override fun isHostValid(): Boolean = ship.isAlive && !ship.isHulk && engine.isEntityInPlay(ship)

    override fun onRemove() {
        ship.mutableStats.shieldDamageTakenMult.unmodifyMult(BUFF_ID)
    }

    private fun refreshShieldMult() {
        val base = ship.mutableStats.shieldDamageTakenMult.baseValue
        ship.mutableStats.shieldDamageTakenMult.modifyMult(
            BUFF_ID, StarfallWingTuning.erosionShieldMult(base, layers.size),
        )
    }

    private fun maintainHud() {
        val player = engine.playerShip
        val weakened = stacks * StarfallWingTuning.EROSION_TAKEN_PER_STACK
        val pctText = formatPercent(weakened * 100f)
        val stacksText = stacks.toString()
        if (showOnPlayerHud && player != null) {
            feedback.maintainPlayerStatus(
                engine, HUD_KEY, HUD_ICON,
                I18n[I18n.Categories.MOD, "ui.starfall_wing.status.title"],
                I18n.t(I18n.Categories.MOD, "ui.starfall_wing.status.desc", "stacks" to stacksText, "percent" to pctText),
                negative = false,
            )
        }
        if (ship == player) {
            feedback.maintainPlayerStatus(
                engine, HUD_VICTIM_KEY, HUD_ICON,
                I18n[I18n.Categories.MOD, "ui.starfall_wing.status.victim_title"],
                I18n.t(I18n.Categories.MOD, "ui.starfall_wing.status.victim_desc", "stacks" to stacksText, "percent" to pctText),
                negative = true,
            )
        }
    }

    companion object {
        const val BUFF_ID = "astd_starfall_wing_erosion"

        fun attachLayer(ship: ShipAPI, engine: CombatEngineAPI, attacker: ShipAPI?) {
            val host = ship.buffHost()
            val buff = host.find(BUFF_ID) as? StarfallWingErosionStacks
                ?: StarfallWingErosionStacks(ship, engine, host).also { host.register(it) }
            buff.addLayer()
            if (attacker != null && attacker == engine.playerShip) buff.showOnPlayerHud = true
        }

        private const val HUD_KEY = "astd_starfall_wing_erosion_status"

        private const val HUD_VICTIM_KEY = "astd_starfall_wing_erosion_victim_status"

        private const val HUD_ICON = "graphics/hullmods/astd_arc_singularity_power.png"

        private val feedback: CombatFeedback = CombatFeedbackImpl

        internal fun formatPercent(value: Float): String {
            val rounded = round(value * 10f) / 10f
            return if (rounded == floor(rounded)) rounded.toInt().toString() else rounded.toString()
        }
    }
}

fun ShipAPI.starfallWingErosionStacks(): StarfallWingErosionStacks? =
    getBuff(StarfallWingErosionStacks.BUFF_ID) as? StarfallWingErosionStacks
