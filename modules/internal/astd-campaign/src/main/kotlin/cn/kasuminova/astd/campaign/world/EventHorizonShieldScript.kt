package cn.kasuminova.astd.campaign.world

import com.fs.starfarer.api.EveryFrameScript
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.CampaignFleetAPI
import com.fs.starfarer.api.impl.campaign.ids.Tags
import com.fs.starfarer.api.util.Misc

/**
 * 视界动力：拾光 1500su 范围内舰队的事件视界免疫标签动态挂摘（生涯层脚本，transient）。
 *
 * 视界动力状况的生涯层效果，与市场状况数值相互独立：
 * 动态为「拾光」[StoryWorldIds.EVENT_HORIZON_IMMUNITY_RADIUS] 范围内全部舰队挂/摘
 * [Tags.FLEET_IGNORES_CORONA]（StarCoronaTerrainPlugin.applyEffect 对该标签直接豁免，
 * 事件视界地形继承同一判定）。
 */
class EventHorizonShieldScript : EveryFrameScript {

    /** 本脚本挂过标签的舰队 id（出范围/实体消失时摘除并复原）。 */
    private val shieldedFleetIds = LinkedHashSet<String>()

    override fun advance(amount: Float) {
        val sector = Global.getSector() ?: return
        val station = sector.getEntityById(StoryWorldIds.ASTER_STATION_SHIGUANG)
        if (station != null) {
            for (fleet in station.containingLocation.fleets) {
                if (Misc.getDistance(station.location, fleet.location) > StoryWorldIds.EVENT_HORIZON_IMMUNITY_RADIUS) continue
                if (shieldedFleetIds.add(fleet.id)) {
                    fleet.addTag(Tags.FLEET_IGNORES_CORONA)
                    fleet.memoryWithoutUpdate.set(MEM_SHIELDED, true)
                }
            }
        }

        val iterator = shieldedFleetIds.iterator()
        while (iterator.hasNext()) {
            val fleetId = iterator.next()
            val fleet = sector.getEntityById(fleetId) as? CampaignFleetAPI
            val inRange = fleet != null && fleet.isAlive && station != null &&
                    fleet.containingLocation === station.containingLocation &&
                    Misc.getDistance(station.location, fleet.location) <= StoryWorldIds.EVENT_HORIZON_IMMUNITY_RADIUS
            if (!inRange) {
                if (fleet != null && fleet.memoryWithoutUpdate.getBoolean(MEM_SHIELDED)) {
                    fleet.removeTag(Tags.FLEET_IGNORES_CORONA)
                    fleet.memoryWithoutUpdate.unset(MEM_SHIELDED)
                }
                iterator.remove()
            }
        }
    }

    override fun isDone(): Boolean = false

    override fun runWhilePaused(): Boolean = false

    companion object {
        /** 舰队 memory：事件视界免疫标签由本模组挂载（摘除时只摘自己挂的）。 */
        const val MEM_SHIELDED: String = "\$astd_event_horizon_shielded"
    }
}
