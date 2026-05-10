package cn.kasuminova.astd.campaign.companions

import cn.kasuminova.astd.campaign.ui.HudMessages
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.SpecialItemData
import com.fs.starfarer.api.characters.FullName
import com.fs.starfarer.api.impl.campaign.ids.Factions
import com.fs.starfarer.api.impl.campaign.ids.Personalities
import com.fs.starfarer.api.impl.campaign.ids.Ranks
import com.fs.starfarer.api.plugins.OfficerLevelupPlugin
import java.awt.Color
import java.util.Random

object CompanionService {

    fun ensureIntelAdded(state: CompanionState) {
        if (state.intelAdded) return
        val sector = Global.getSector() ?: return
        sector.intelManager.addIntel(CompanionsIntel())
        state.intelAdded = true
    }

    fun recruitWatcher(state: CompanionState) {
        val sector = Global.getSector() ?: return
        val playerFleet = sector.playerFleet ?: return

        // 已存在则不重复添加
        if (playerFleet.fleetData.officersCopy.any { it.person?.id == CompanionIds.WATCHER_ID }) {
            state.watcherRecruited = true
            return
        }

        val faction = sector.getFaction(Factions.PLAYER)
        val person = faction.createRandomPerson()
        person.setId(CompanionIds.WATCHER_ID)
        person.setName(FullName("守望者", "", FullName.Gender.MALE))
        person.setRankId(Ranks.SPACE_CAPTAIN)
        person.setPostId(Ranks.POST_OFFICER)
        person.setPersonality(Personalities.STEADY)
        person.setPortraitSprite("graphics/portraits/portrait_independent07.png")

        val lvlPlugin = Global.getSettings().getPlugin("officerLevelUp") as OfficerLevelupPlugin
        val maxLevel = lvlPlugin.getMaxLevel(person)
        person.stats.setLevel(maxLevel)
        person.stats.setSkipRefresh(true)

        // 2 个独特主技能（Elite）
        person.stats.setSkillLevel(CompanionIds.SK_WATCHER_1, 2f)
        person.stats.setSkillLevel(CompanionIds.SK_WATCHER_2, 2f)

        // 其余技能：选择通用但不抢戏的组合（Elite）。
        person.stats.setSkillLevel("helmsmanship", 2f)
        person.stats.setSkillLevel("field_modulation", 2f)
        person.stats.setSkillLevel("target_analysis", 2f)
        person.stats.setSkillLevel("systems_expertise", 2f)
        person.stats.setSkillLevel("combat_endurance", 2f)

        person.stats.setSkipRefresh(false)
        person.stats.refreshCharacterStatsEffects(false)

        playerFleet.fleetData.addOfficer(person)
        state.watcherRecruited = true
        ensureIntelAdded(state)

        HudMessages.campaign(I18n[I18n.Categories.MOD, "hud.companion.watcher_joined"], Color(120, 200, 255))
    }

    fun obtainEchoCore(state: CompanionState) {
        val sector = Global.getSector() ?: return
        val cargo = sector.playerFleet?.cargo ?: return

        // 已有则不重复
        val hasCore = cargo.stacksCopy.any { stack ->
            stack.isSpecialStack && stack.specialItemSpecIfSpecial?.id == CompanionIds.ECHO_CORE_SPECIAL_ITEM_ID
        }
        if (!hasCore) {
            cargo.addSpecial(SpecialItemData(CompanionIds.ECHO_CORE_SPECIAL_ITEM_ID, null), 1f)
        }

        // 同时作为“等级 8 的 AI 军官”加入（让技能与坐舰加成可实际生效）。
        val playerFleet = sector.playerFleet
        if (playerFleet != null && playerFleet.fleetData.officersCopy.none { it.person?.id == CompanionIds.ECHO_ID }) {
            val remnant = sector.getFaction(Factions.REMNANTS)
            val person = remnant.createRandomPerson()
            person.setId(CompanionIds.ECHO_ID)
            person.setName(FullName("回声", "", FullName.Gender.ANY))
            person.setRankId(Ranks.SPACE_CAPTAIN)
            person.setPostId(Ranks.POST_OFFICER)
            person.setPersonality(Personalities.STEADY)
            person.setAICoreId("alpha_core")
            person.setPortraitSprite("graphics/portraits/portrait_ai1.png")

            person.stats.setLevel(8)
            person.stats.setSkipRefresh(true)
            person.stats.setSkillLevel(CompanionIds.SK_ECHO_1, 2f)
            person.stats.setSkillLevel(CompanionIds.SK_ECHO_2, 2f)
            person.stats.setSkillLevel("electronic_warfare", 2f)
            person.stats.setSkillLevel("target_analysis", 2f)
            person.stats.setSkillLevel("field_modulation", 2f)
            person.stats.setSkillLevel("systems_expertise", 2f)
            person.stats.setSkillLevel("damage_control", 2f)
            person.stats.setSkillLevel("helmsmanship", 2f)
            person.stats.setSkipRefresh(false)
            person.stats.refreshCharacterStatsEffects(false)

            playerFleet.fleetData.addOfficer(person)
        }

        state.echoObtained = true
        ensureIntelAdded(state)
        HudMessages.campaign(I18n[I18n.Categories.MOD, "hud.companion.echo_obtained"], Color(170, 120, 255))
    }
}
