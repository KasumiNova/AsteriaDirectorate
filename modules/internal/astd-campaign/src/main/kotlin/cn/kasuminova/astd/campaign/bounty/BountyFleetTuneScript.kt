package cn.kasuminova.astd.campaign.bounty

import cn.kasuminova.astd.campaign.bounty.core.BountyFleetTuner
import com.fs.starfarer.api.EveryFrameScript
import com.fs.starfarer.api.Global
import org.apache.log4j.Logger

class BountyFleetTuneScript(
    private val tuner: BountyFleetTuner = BountyFleetTunerImpl(),
) : EveryFrameScript {

    private val log: Logger = Global.getLogger(BountyFleetTuneScript::class.java)
    private var timer: Float = 0f

    override fun isDone(): Boolean = false

    override fun runWhilePaused(): Boolean = false

    override fun advance(amount: Float) {
        timer += amount
        if (timer < SCAN_INTERVAL) return
        timer = 0f

        val sector = Global.getSector() ?: return
        val locations = sector.starSystems + sector.hyperspace
        for (location in locations) {
            for (fleet in location.fleets) {
                if (!tuner.isAstdBountyFleet(fleet)) continue
                try {
                    tuner.tuneIfNeeded(fleet)
                } catch (t: Throwable) {
                    log.error("[ASTD] 赏金舰队 ${fleet.name} 后处理失败", t)
                }
            }
        }
    }

    companion object {
        const val SCAN_INTERVAL: Float = 1f
    }
}
