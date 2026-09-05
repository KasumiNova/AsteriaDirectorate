package cn.kasuminova.astd.campaign.world

import cn.kasuminova.astd.api.difficulty.ScalingEntry
import com.fs.starfarer.api.campaign.CampaignFleetAPI
import com.fs.starfarer.api.impl.campaign.ids.Stats
import com.fs.starfarer.api.impl.campaign.ids.Tags
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.util.Misc

/**
 * 视界动力（轨道生活空间站「拾光」，docs/story/07 紫菀遗址星系）。
 *
 * 效果随难度系数缩放（k=1 下限 / k=5 上限）：
 * 最大工业设施数量 +2~+4、所有建筑维护费 -15%~-75%、危险度 -10%~-50%。
 *
 * 另提供生涯层光环：本站周边 [AURA_RADIUS_SU]（1500su）范围内的全部舰队
 * 免疫黑洞环境的事件视界效果（CR 流失与引力风推离），不影响战斗内引力节点的战场效果
 * （两套机制相互独立）。
 *
 * 实现口径：原版地形 `StarCoronaTerrainPlugin.applyEffect` 对携带
 * [Tags.FLEET_IGNORES_CORONA] 标签的舰队整体跳过效果，光环经 [advance] 为范围内舰队
 * 挂上该标签、离开范围即摘除，并记录舰队在本站接管前是否已有该标签，
 * 只有本站实际新增的标签才会移除，避免覆盖已有来源的同名牌。紫菀遗址星系主星仅为黑洞，
 * 故该标签同时覆盖普通日冕的语义在此星系内与文档口径一致。
 */
class StoryConditionEventHorizonPower : StoryConditionBase() {

    override fun apply(id: String) {
        market.stats.dynamic.getMod(Stats.MAX_INDUSTRIES)
            .modifyFlat(id, scaledInt(MAX_INDUSTRIES).toFloat(), conditionName)
        market.upkeepMult.modifyMult(id, 1f - scaled(UPKEEP_REDUCTION), conditionName)
        market.hazard.modifyMult(id, 1f - scaled(HAZARD_REDUCTION), conditionName)
    }

    override fun unapply(id: String) {
        market.stats.dynamic.getMod(Stats.MAX_INDUSTRIES).unmodifyFlat(id)
        market.upkeepMult.unmodifyMult(id)
        market.hazard.unmodifyMult(id)
        releaseAllProtectedFleets()
    }

    override fun advance(amount: Float) {
        super.advance(amount)
        val origin = market?.primaryEntity ?: return
        val location = market?.containingLocation ?: return
        for (fleet in location.fleets) {
            val inRange = Misc.getDistance(fleet, origin) <= AURA_RADIUS_SU
            val protectedByUs = fleet.memoryWithoutUpdate.getBoolean(MEM_PROTECTED)
            if (inRange && !protectedByUs) {
                fleet.memoryWithoutUpdate.set(MEM_HAD_TAG, fleet.hasTag(Tags.FLEET_IGNORES_CORONA))
                fleet.addTag(Tags.FLEET_IGNORES_CORONA)
                fleet.memoryWithoutUpdate.set(MEM_PROTECTED, true)
            } else if (!inRange && protectedByUs) {
                releaseFleet(fleet)
            }
        }
    }

    override fun createTooltipAfterDescription(tooltip: TooltipMakerAPI, expanded: Boolean) {
        addEffectLine(
            tooltip,
            "condition.common.max_industries",
            "val" to formatSignedInt(scaledInt(MAX_INDUSTRIES)),
        )
        addEffectLine(
            tooltip,
            "condition.event_horizon_power.upkeep",
            "val" to formatPercent(-scaled(UPKEEP_REDUCTION)),
        )
        addEffectLine(
            tooltip,
            "condition.event_horizon_power.hazard",
            "val" to formatPercent(-scaled(HAZARD_REDUCTION)),
        )
        addEffectLine(
            tooltip,
            "condition.event_horizon_power.aura",
            "radius" to AURA_RADIUS_SU.toInt(),
        )
    }

    private fun releaseFleet(fleet: CampaignFleetAPI) {
        if (!fleet.memoryWithoutUpdate.getBoolean(MEM_HAD_TAG)) {
            fleet.removeTag(Tags.FLEET_IGNORES_CORONA)
        }
        fleet.memoryWithoutUpdate.unset(MEM_HAD_TAG)
        fleet.memoryWithoutUpdate.unset(MEM_PROTECTED)
    }

    /** 状况被移除时摘除本站挂出的全部免疫标签（防止市场失效后残留永久免疫）。 */
    private fun releaseAllProtectedFleets() {
        val location = market?.containingLocation ?: return
        for (fleet in location.fleets) {
            if (fleet.memoryWithoutUpdate.getBoolean(MEM_PROTECTED)) {
                releaseFleet(fleet)
            }
        }
    }

    companion object {
        /** 光环半径（su），文档口径"约 1500su"。 */
        const val AURA_RADIUS_SU = 1500f

        /**
         * 舰队持久 memory 键：标记免疫标签由本站挂出。
         * 不带 `$` 前缀以便随存档持久化（读档后光环状态可正确恢复/摘除）。
         */
        const val MEM_PROTECTED = "astd_event_horizon_power_protected"

        /** Boolean：本站首次接管前舰队是否已有同名标签，避免释放时误删外部来源。 */
        const val MEM_HAD_TAG = "astd_event_horizon_power_had_tag"

        /** 最大工业设施数量平值（整数语义）。 */
        val MAX_INDUSTRIES = ScalingEntry(2f, 3f, 4f)

        /** 建筑维护费乘区减免（0.15 = -15%）。 */
        val UPKEEP_REDUCTION = ScalingEntry(0.15f, 0.30f, 0.75f)

        /** 危险度乘区减免（0.10 = -10%）。 */
        val HAZARD_REDUCTION = ScalingEntry(0.10f, 0.20f, 0.50f)
    }
}
