package cn.kasuminova.astd.campaign.world

import com.fs.starfarer.api.EveryFrameScript
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.CampaignFleetAPI
import com.fs.starfarer.api.campaign.SectorEntityToken
import com.fs.starfarer.api.impl.campaign.ids.Tags
import com.fs.starfarer.api.util.IntervalUtil
import com.fs.starfarer.api.util.Misc

/**
 * 视界动力：拾光 1500su 范围内舰队的事件视界免疫标签动态挂摘（生涯层脚本，transient）。
 *
 * 视界动力状况的生涯层效果，与市场状况数值相互独立：
 * 动态为「拾光」[StoryWorldIds.EVENT_HORIZON_IMMUNITY_RADIUS] 范围内全部舰队挂/摘
 * [Tags.FLEET_IGNORES_CORONA]（StarCoronaTerrainPlugin.applyEffect 对该标签直接豁免，
 * 事件视界地形继承同一判定）。
 *
 * 性能约束：原版 `BaseLocation.getEntityById` 每次调用都会重建 id 索引并做大小写归一，
 * 逐帧按 id 反查会烧掉过半帧耗时，因此空间站与已挂标签舰队一律持有实体引用，
 * 距离扫描按 [scanInterval] 节流而非逐帧执行。
 */
class EventHorizonShieldScript : EveryFrameScript {

    /** 拾光空间站实体缓存（首次/失效后才按 id 反查）。 */
    private var station: SectorEntityToken? = null

    /** 本脚本挂过标签的舰队（id -> 实体引用，出范围/实体消失时摘除并复原）。 */
    private val shieldedFleets = LinkedHashMap<String, CampaignFleetAPI>()

    private val scanInterval = IntervalUtil(SCAN_INTERVAL_SECONDS, SCAN_INTERVAL_SECONDS)

    override fun advance(amount: Float) {
        scanInterval.advance(amount)
        if (!scanInterval.intervalElapsed()) return

        val sector = Global.getSector() ?: return
        val currentStation = resolveStation(sector)
        if (currentStation != null) {
            for (fleet in currentStation.containingLocation.fleets) {
                if (Misc.getDistance(currentStation.location, fleet.location) > StoryWorldIds.EVENT_HORIZON_IMMUNITY_RADIUS) continue
                if (shieldedFleets.putIfAbsent(fleet.id, fleet) == null) {
                    fleet.addTag(Tags.FLEET_IGNORES_CORONA)
                    fleet.memoryWithoutUpdate.set(MEM_SHIELDED, true)
                }
            }
        }

        val iterator = shieldedFleets.entries.iterator()
        while (iterator.hasNext()) {
            val fleet = iterator.next().value
            val inRange = fleet.isAlive && currentStation != null &&
                    fleet.containingLocation === currentStation.containingLocation &&
                    Misc.getDistance(currentStation.location, fleet.location) <= StoryWorldIds.EVENT_HORIZON_IMMUNITY_RADIUS
            if (!inRange) {
                if (fleet.memoryWithoutUpdate.getBoolean(MEM_SHIELDED)) {
                    fleet.removeTag(Tags.FLEET_IGNORES_CORONA)
                    fleet.memoryWithoutUpdate.unset(MEM_SHIELDED)
                }
                iterator.remove()
            }
        }
    }

    private fun resolveStation(sector: com.fs.starfarer.api.campaign.SectorAPI): SectorEntityToken? {
        val cached = station
        if (cached != null && cached.isAlive && cached.containingLocation != null) return cached
        val resolved = sector.getEntityById(StoryWorldIds.ASTER_STATION_SHIGUANG)
        station = resolved
        return resolved
    }

    override fun isDone(): Boolean = false

    override fun runWhilePaused(): Boolean = false

    companion object {
        /** 舰队 memory：事件视界免疫标签由本模组挂载（摘除时只摘自己挂的）。 */
        const val MEM_SHIELDED: String = "\$astd_event_horizon_shielded"

        /** 距离扫描节流间隔（秒，生涯时间）；免疫生效/失效延迟一个间隔即可，无感知差异。 */
        private const val SCAN_INTERVAL_SECONDS = 0.2f
    }
}
