package cn.kasuminova.astd.combat.effect.arc.geminidem

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.input.InputEventAPI
import com.fs.starfarer.api.util.Misc

/**
 * 生涯环境诊断探针（-Dastd.gd.debug=true 启用）：逐秒记录场上 dem_drone 的锁定激光发射与光束实体状态。
 * 用于判别「激光未发射」与「光束已发射但未渲染」两种故障域；正常游玩不加载本探针。
 */
class GeminiDemDroneDebugProbe : BaseEveryFrameCombatPlugin() {

    private var engine: CombatEngineAPI? = null
    private var elapsedSinceLog = THROTTLE_SECONDS

    override fun init(engine: CombatEngineAPI) {
        this.engine = engine
        log.info("[ASTD] 双子星 DEM 诊断探针已挂载（-Dastd.gd.debug=true）")
    }

    override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
        val engine = engine ?: return
        if (engine.isPaused) return
        elapsedSinceLog += amount
        if (elapsedSinceLog < THROTTLE_SECONDS) return
        elapsedSinceLog = 0f

        for (ship in engine.ships) {
            if (ship.hullSpec?.hullId != DRONE_HULL_ID) continue
            val group0 = ship.weaponGroupsCopy.getOrNull(0)
            val laser = group0?.weaponsCopy?.getOrNull(0)
            val group1 = ship.weaponGroupsCopy.getOrNull(1)
            val payload = group1?.weaponsCopy?.getOrNull(0)
            val sb = StringBuilder(256)
            sb.append("[ASTD] DEM 无人机状态：alive=").append(ship.isAlive)
                .append(" owner=").append(ship.owner)
                .append(" layer=").append(ship.layer)
                .append(" loc=").append(formatVec(ship.location))
                .append(" facing=").append(ship.facing)
                .append(" group0active=").append(ship.weaponGroupsCopy.getOrNull(0)?.let { ship.selectedGroupAPI === it })
            if (laser == null) {
                sb.append(" laser=<缺失>")
            } else {
                sb.append(" laser{id=").append(laser.id)
                    .append(" firing=").append(laser.isFiring)
                    .append(" disabled=").append(laser.isDisabled)
                    .append(" charge=").append(laser.chargeLevel)
                    .append(" cooldown=").append(laser.cooldownRemaining)
                    .append(" range=").append(laser.range)
                    .append(" arc=").append(laser.arc)
                    .append(" currFacing=").append(laser.currAngle)
                    .append(" beams=").append(laser.beams.size)
                for (beam in laser.beams) {
                    sb.append(" [beam w=").append(beam.width)
                        .append(" len=").append(Misc.getDistance(beam.from, beam.to))
                        .append(" coreA=").append(beam.coreColor?.alpha)
                        .append(" fringeA=").append(beam.fringeColor?.alpha)
                        .append(" brightness=").append(beam.brightness)
                        .append("]")
                }
                sb.append("}")
            }
            if (payload == null) {
                sb.append(" payload=<缺失>")
            } else {
                sb.append(" payload{firing=").append(payload.isFiring)
                    .append(" beams=").append(payload.beams.size)
                    .append("}")
            }
            log.info(sb.toString())
        }
    }

    companion object {
        private val log = Global.getLogger(GeminiDemDroneDebugProbe::class.java)

        private const val DRONE_HULL_ID = "dem_drone"
        private const val THROTTLE_SECONDS = 1f

        const val INSTALLED_KEY = "astd_gemini_dem_debug_probe_installed"

        fun isEnabled(): Boolean = System.getProperty("astd.gd.debug") == "true"

        fun installIfEnabled(engine: CombatEngineAPI) {
            if (!isEnabled()) return
            if (engine.customData[INSTALLED_KEY] == true) return
            engine.customData[INSTALLED_KEY] = true
            engine.addPlugin(GeminiDemDroneDebugProbe())
        }
    }

    private fun formatVec(v: org.lwjgl.util.vector.Vector2f): String = "(${v.x.toInt()},${v.y.toInt()})"
}
