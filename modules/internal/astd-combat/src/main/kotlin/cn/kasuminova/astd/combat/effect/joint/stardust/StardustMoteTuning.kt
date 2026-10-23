package cn.kasuminova.astd.combat.effect.joint.stardust

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import com.fs.starfarer.api.Global
import java.awt.Color

object StardustMoteTuning {

    val ANTI_MISSILE_BONUS = ScalingEntry(0.50f, 1.50f, 2.50f)

    val ANTI_FIGHTER_BONUS = ScalingEntry(1.0f, 3.0f, 5.0f)

    val SHIP_EMP_MULT = ScalingEntry(1.0f, 3.0f, 5.0f)

    const val DEFAULT_ENGAGE_RANGE = 800f

    const val MAX_MOTES_PER_TARGET = 4

    const val MAX_ALIVE_MOTES_PER_WEAPON = 4

    val ARC_COLOR = Color(100, 165, 255, 255)

    val LENS_COLOR = Color(186, 120, 255, 255)

    data class Values(
        val antiMissileBonus: Float,
        val antiFighterBonus: Float,
        val shipEmpMult: Float,
    )

    fun resolve(tuning: DifficultyTuning, isPlayer: Boolean): Values = Values(
        antiMissileBonus = tuning.valueFor(ANTI_MISSILE_BONUS, isPlayer),
        antiFighterBonus = tuning.valueFor(ANTI_FIGHTER_BONUS, isPlayer),
        shipEmpMult = tuning.valueFor(SHIP_EMP_MULT, isPlayer),
    )

    fun colorForProj(projectileSpecId: String): Color {
        if (!StardustMoteIds.isStardustProj(projectileSpecId)) {
            warnOnce("unknownProj:$projectileSpecId") { "星尘光尘配色收到未知弹体 spec：$projectileSpecId，按 ARC 蓝兜底" }
        }
        return if (projectileSpecId == StardustMoteIds.PROJ_LENS) LENS_COLOR else ARC_COLOR
    }

    private val log = Global.getLogger(StardustMoteTuning::class.java)

    private val warnedKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun warnOnce(key: String, message: () -> String) {
        if (warnedKeys.add(key)) {
            log.warn(message())
        }
    }
}

object StardustMoteIds {
    const val WEAPON_ARC = "astd_lh_stardust_launcher_arc"
    const val WEAPON_LENS = "astd_lh_stardust_launcher_lens"
    const val PROJ_ARC = "astd_lh_stardust_mote_arc"
    const val PROJ_LENS = "astd_lh_stardust_mote_lens"

    fun isStardustProj(projectileSpecId: String): Boolean =
        projectileSpecId == PROJ_ARC || projectileSpecId == PROJ_LENS
}
