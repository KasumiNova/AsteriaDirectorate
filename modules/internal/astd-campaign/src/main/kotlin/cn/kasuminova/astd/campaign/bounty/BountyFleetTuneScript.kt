package cn.kasuminova.astd.campaign.bounty

import cn.kasuminova.astd.campaign.bounty.core.BountyFleetTuner
import com.fs.starfarer.api.EveryFrameScript
import com.fs.starfarer.api.Global
import org.apache.log4j.Logger

class BountyFleetTuneScript(
    private val tuner: BountyFleetTuner = BountyFleetTunerImpl(),
) : EveryFrameScript {

    private val log: Logger = Global.getLogger(BountyFleetTuneScript::class.java)
    private var lastScanNanos: Long = 0L
    private var portraitsPreloaded: Boolean = false

    override fun isDone(): Boolean = false

    override fun runWhilePaused(): Boolean = true

    override fun advance(amount: Float) {
        if (!portraitsPreloaded) {
            portraitsPreloaded = true
            try {
                StandardCores.preloadPortraits()
            } catch (t: Throwable) {
                log.error("[ASTD] 制式核心头像预加载失败", t)
            }
        }

        val now = System.nanoTime()
        if (now - lastScanNanos < SCAN_INTERVAL_NANOS) return
        lastScanNanos = now

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
        const val SCAN_INTERVAL_NANOS: Long = 1_000_000_000L
    }
}
