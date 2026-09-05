package cn.kasuminova.astd.campaign.bounty

import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.campaign.FleetAssignment
import com.fs.starfarer.api.impl.campaign.events.OfficerManagerEvent
import org.magiclib.bounty.MagicBountyLoader
import org.magiclib.bounty.MagicBountySpec

/**
 * 以“代码生成”的方式向 MagicBounty 注册主线赏金：不依赖 magicBounty_data.json。
 *
 * - 全部玩家可见文本从 [BountyKeys.I18N_CATEGORY] 字符串表读取（键名约定见 [BountyDef]）；
 * - 出现门槛走 trigger_memKeys_all（前序单结清 memKey / 序章文书 memKey）；
 * - 第一章及以后的工单只在分局空间站市场挂出（[BountyKeys.STATION_TRIGGER_MARKET_IDS]）；
 * - 报酬不在 spec 里配置（credit_reward = 0），由 [BountyRewards] 按难度系数动态结算。
 */
object MagicBountyBridge {

    /** 注册全部主线赏金（overwrite=true 时覆盖同 key 旧数据）。 */
    @JvmStatic
    fun registerMainBounties(overwrite: Boolean) {
        for (def in MainBounties.defs) {
            MagicBountyLoader.addBountyData(def.key, buildSpecFor(def), overwrite)
        }
    }

    /** 重注册单个主线赏金（失败单重置后兜底，保证加载器重载后定义仍在）。 */
    @JvmStatic
    fun registerMainBounty(def: BountyDef, overwrite: Boolean) {
        MagicBountyLoader.addBountyData(def.key, buildSpecFor(def), overwrite)
    }

    /** 主线定义是否仍在加载器中（缺失时由管理脚本补注册）。 */
    @JvmStatic
    fun mainBountiesRegistered(): Boolean =
        MainBounties.defs.all { MagicBountyLoader.getBountyData(it.key) != null }

    private fun i18nName(def: BountyDef): String = I18n[BountyKeys.I18N_CATEGORY, "main.${def.key}.name"]
    private fun i18nDesc(def: BountyDef): String = I18n[BountyKeys.I18N_CATEGORY, "main.${def.key}.desc"]
    private fun i18nFleetName(def: BountyDef): String = I18n[BountyKeys.I18N_CATEGORY, "main.${def.key}.fleet_name"]

    private fun i18nDanger(def: BountyDef): String =
        if (def.dangerAbsent) {
            I18n[BountyKeys.I18N_CATEGORY, "danger.level.absent"]
        } else {
            I18n[BountyKeys.I18N_CATEGORY, "danger.level.${def.dangerLevel}"]
        }

    private fun buildSpecFor(def: BountyDef): MagicBountySpec {
        val memAll = HashMap<String, Boolean>()
        for (mk in def.requiredMemKeys) {
            memAll[mk] = true
        }

        // 序章工单由代办文书引出、目标坐标在玩家当前星域附近，不限定挂出市场；
        // 第一章起工单终端固定在分局空间站（受理/核销点）。
        val marketIds: List<String> =
            if (def.chapter >= 1) BountyKeys.STATION_TRIGGER_MARKET_IDS else emptyList()

        val isAutomated = def.fleetFactionId == "remnant"

        // MagicBounty 胜利不等于交付。独立战斗结果键，不能提前满足后续工单的核销门槛。
        val jobMemKey = "\$astd_battle_${def.key}"

        return MagicBountySpec(
            // trigger
            marketIds,
            emptyList(),
            false,
            emptyList(),
            false,
            -1,
            -1,
            -1,
            0,
            1.0f,
            memAll,
            emptyMap(),
            emptyMap(),
            emptyMap(),
            emptyMap(),
            -99.0f,
            99.0f,
            // job
            i18nName(def),
            i18nDesc(def),
            null,
            // 核销回执不在赏金板展示；战后由舰队接触对话框按 MEM_SUCCESS_TEXT 输出（见 BountyFidConfigGen）。
            "",
            I18n[BountyKeys.I18N_CATEGORY, "generic.fail"],
            I18n[BountyKeys.I18N_CATEGORY, "generic.expired"],
            null,
            i18nDanger(def),
            -1,
            0,
            0.0f,
            0.0f,
            emptyMap(),
            "assassination",
            true,
            !isAutomated,
            "FlagshipText",
            "Vague",
            true,
            I18n[BountyKeys.I18N_CATEGORY, "generic.option.accept"],
            null,
            jobMemKey,
            null,
            // target
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            -1,
            -1,
            OfficerManagerEvent.SkillPickPreference.GENERIC,
            emptyMap(),
            // fleet
            i18nFleetName(def),
            def.fleetFactionId,
            def.flagshipVariantId,
            i18nName(def),
            false,
            false,
            emptyMap(),
            false,
            0.0f,
            def.baselineFP,
            def.fleetFactionId,
            1.0f,
            false,
            true,
            FleetAssignment.DEFEND_LOCATION,
            "AGGRESSIVE",
            null,
            // location
            emptyList(),
            emptyList(),
            "VAGUE",
            if (isAutomated) listOf("theme_remnant") else emptyList(),
            emptyList(),
            emptyList(),
            true,
            true,
        )
    }
}
