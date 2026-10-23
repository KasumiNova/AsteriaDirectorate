package cn.kasuminova.astd.campaign.world

import com.fs.starfarer.api.EveryFrameScript
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.CampaignFleetAPI
import com.fs.starfarer.api.campaign.SectorEntityToken
import com.fs.starfarer.api.impl.campaign.ids.Tags
import com.fs.starfarer.api.util.IntervalUtil
import com.fs.starfarer.api.util.Misc

/** 原版 BaseLocation.getEntityById 每次调用都重建 id 索引，禁止逐帧按 id 反查，实体引用必须缓存持有。 */
class EventHorizonShieldScript : EveryFrameScript {

    private var station: SectorEntityToken? = null

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
        const val MEM_SHIELDED: String = "\$astd_event_horizon_shielded"

        private const val SCAN_INTERVAL_SECONDS = 0.2f
    }
}
