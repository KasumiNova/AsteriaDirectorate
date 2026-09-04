package cn.kasuminova.astd.campaign.bounty

import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.FleetAssignment
import com.fs.starfarer.api.impl.campaign.events.OfficerManagerEvent
import org.magiclib.bounty.MagicBountyLoader
import org.magiclib.bounty.MagicBountySpec

/**
 * 以“代码生成”的方式向 MagicBounty 注册赏金：不依赖 magicBounty_data.json。
 */
object MagicBountyBridge {

    /**
     * 注册全部主线赏金（按 memKey 做顺序门槛）。
     */
    @JvmStatic
    fun registerMainBounties(overwrite: Boolean) {
        for (def in StoryBounties.mains) {
            MagicBountyLoader.addBountyData(def.key, buildSpecFor(def), overwrite)
        }
    }

    /**
     * 根据主线完成事件生成“唯一支线”赏金。
     */
    @JvmStatic
    fun registerSideBountiesAfterMain(mainKey: String, count: Int) {
        val state = BountyState.getOrCreate()
        val completed = state.mainCompleted
        val baseSeed = Global.getSector()?.clock?.timestamp ?: System.currentTimeMillis()

        repeat(count.coerceIn(1, 6)) {
            state.sideSerial += 1
            val sideKey = "astd_side_${mainKey.removePrefix("astd_")}_${state.sideSerial.toString().padStart(3, '0')}"

            val spec = buildSideSpec(
                key = sideKey,
                title = I18n.t("asteria_directorate_bounty", "side.title", "serial" to state.sideSerial),
                shortDesc = I18n["asteria_directorate_bounty", "side.short_desc"],
                threatTier = (2 + (completed / 6)).coerceIn(2, 5),
                baselineFP = (70 + completed * 10).coerceAtMost(420),
                // 侧重“可打捞特殊武器”：用本模组中型战斗单位当目标更合适
                flagshipVariantId = listOf(
                    "astd_arc_flash_Standard",
                    "astd_diffraction_Standard",
                    "astd_nebula_echo_Standard",
                    "astd_magnetosphere_disturbance_Standard",
                ).let { list ->
                    val rnd = java.util.Random(baseSeed + state.sideSerial)
                    list[rnd.nextInt(list.size)]
                },
                requiredMainKey = mainKey,
            )

            MagicBountyLoader.addBountyData(sideKey, spec, true)
        }
    }

    private fun buildSpecFor(def: BountyDef): MagicBountySpec {
        val memAll = HashMap<String, Boolean>()
        if (def.requiredPreviousMainKey != null) {
            // 要求前一主线完成（memKey 为 true）
            memAll["\$${def.requiredPreviousMainKey}"] = true
        }

        // job_memKey：用 $<bountyKey> 作为 gating 条件。
        val jobMemKey = "\$${def.key}"

        // 最小字段集：尽量让 bounty board 可展示且可被接受。
        return MagicBountySpec(
            // trigger
            emptyList(),
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
            def.title,
            I18n.t("asteria_directorate_bounty", "bounty.${def.key}.description"),
            null,
            // 完成文案改为在“接触目标舰队”的战斗结算对话框里显示（见 BountyFidConfigGen）。
            "",
            I18n["asteria_directorate_bounty", "side.fail"],
            I18n["asteria_directorate_bounty", "side.expired"],
            null,
            "T${def.threatTier}",
            -1,
            0,
            0.0f,
            0.0f,
            emptyMap(),
            "assassination",
            true,
            true,
            "FlagshipText",
            "Vague",
            true,
            I18n["asteria_directorate_bounty", "side.option.accept"],
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
            I18n["asteria_directorate_bounty", "fleet.name.echo"],
            "remnant",
            def.flagshipVariantId,
            def.title,
            true,
            false,
            emptyMap(),
            false,
            0.0f,
            def.baselineFP,
            "remnant",
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
            listOf("theme_remnant"),
            emptyList(),
            emptyList(),
            true,
            true,
        )
    }

    private fun buildSideSpec(
        key: String,
        title: String,
        shortDesc: String,
        threatTier: Int,
        baselineFP: Int,
        flagshipVariantId: String,
        requiredMainKey: String,
    ): MagicBountySpec {
        val memAll = hashMapOf("\$${requiredMainKey}" to true)
        val jobMemKey = "\$${key}"

        return MagicBountySpec(
            // trigger
            emptyList(),
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
            title,
            shortDesc,
            null,
            // 支线完成文案同样不在赏金板展示；战后会在舰队接触对话框里给出简短提示。
            "",
            I18n["asteria_directorate_bounty", "side.missed"],
            I18n["asteria_directorate_bounty", "side.expired"],
            null,
            I18n.t("asteria_directorate_bounty", "side.threat", "tier" to threatTier),
            -1,
            0,
            0.0f,
            0.0f,
            emptyMap(),
            "assassination",
            true,
            true,
            "FlagshipText",
            "Vague",
            true,
            I18n["asteria_directorate_bounty", "side.option.accept"],
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
            I18n["asteria_directorate_bounty", "fleet.name.shards"],
            "remnant",
            flagshipVariantId,
            title,
            true,
            false,
            emptyMap(),
            false,
            0.0f,
            baselineFP,
            "remnant",
            1.0f,
            false,
            true,
            FleetAssignment.PATROL_SYSTEM,
            "AGGRESSIVE",
            null,
            // location
            emptyList(),
            emptyList(),
            "VAGUE",
            listOf("theme_remnant"),
            emptyList(),
            emptyList(),
            true,
            true,
        )
    }
}
