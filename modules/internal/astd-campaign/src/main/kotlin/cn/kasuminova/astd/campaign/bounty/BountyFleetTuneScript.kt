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

    // 玩家打开赏金情报面板时游戏处于暂停：必须在暂停期也完成后处理，
    // 否则 intel 的舰队编成显示（ShowFleet.Vanilla 读实际舰队成员）会露出修复前的原始舰队。
    override fun runWhilePaused(): Boolean = true

    override fun advance(amount: Float) {
        // 制式核心头像必须早于赏金舰队生成完成预加载，否则 AI 核心军官指派/渲染路径拿到黑壳贴图。
        if (!portraitsPreloaded) {
            portraitsPreloaded = true
            try {
                StandardCores.preloadPortraits()
            } catch (t: Throwable) {
                log.error("[ASTD] 制式核心头像预加载失败", t)
            }
        }

        // 暂停期 advance 的 amount 恒为 0，游戏时间不流动，扫描节流必须用墙钟，
        // 否则暂停中（赏金面板浏览期）创建的舰队永远不会被后处理，创建期充气损伤无法及时修复。
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
        /** 扫描节流的墙钟间隔（纳秒，1 秒）。 */
        const val SCAN_INTERVAL_NANOS: Long = 1_000_000_000L
    }
}
