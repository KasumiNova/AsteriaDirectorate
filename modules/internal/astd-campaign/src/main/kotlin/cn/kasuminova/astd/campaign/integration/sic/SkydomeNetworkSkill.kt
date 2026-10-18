package cn.kasuminova.astd.campaign.integration.sic

import cn.kasuminova.astd.campaign.bounty.StandardCores
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipVariantAPI
import com.fs.starfarer.api.impl.campaign.ids.Personalities
import com.fs.starfarer.api.ui.TooltipMakerAPI
import second_in_command.SCData
import second_in_command.specs.SCBaseSkillPlugin

/**
 * 天幕网络（astd_sc_skydome_network）——「菀星智能」天赋的基石技能，随天赋自动解锁
 * （docs/design/skills/10-sic-skills.md 阶段一；数值声明见 [SicTuning]）。
 *
 * 机制落点：
 * - +无人舰点数：[advance]/[onActivation] 经 [SicShared.applyAutoPoints] 写玩家 Person 的
 *   `sc_auto_dp` dynamic mod（sourceId 带 `_external` 后缀，SIC 界面显示为外部来源；
 *   逐帧重写以跟随难度设置变更），[onDeactivation] 同 sourceId unmodify。NPC 舰队跳过。
 * - 菀星制式核心点数倍率降低：[applyEffectsBeforeShipCreation]——舰长取自
 *   `stats.fleetMember?.captain`，[StandardCores.byCommodity] 命中分档才生效；
 *   新倍率 = max(1, 档位倍率 - 难度解析降低量)，以 `新倍率 / 档位倍率` 写入舰船
 *   `sc_auto_points_mult` dynamic stat（乘区因子纯函数 [SicTuning.coreMultFactor]）。
 * - AI 核心性格跟随舰队学说：[advance] 逐帧（仅玩家舰队）——学说侵略度经
 *   [SicTuning.doctrinePersonality] 映射为目标性格；仅处理 isAICore 且
 *   （当前性格为无畏 或 已被本技能打标 `$astd_sc_doctrine_personality`）的核心，
 *   setPersonality(映射值) 并打标；仅在实际需要变更时写 memory（advance 每帧执行的节流口径，
 *   无中间状态驻留）。其他模组改过性格（非无畏且无标）的核心不受影响。
 *   [onDeactivation]：带标核心恢复无畏并清标（当前性格已被第三方改动的核心保留对方修改）。
 */
class SkydomeNetworkSkill : SCBaseSkillPlugin() {

    override fun getAffectsString(): String =
        I18n[I18n.Categories.MOD, "ui.sic.skydome_network.affects"]

    override fun addTooltip(data: SCData, tooltip: TooltipMakerAPI) {
        val points = SicShared.autoPointsFor(SicTuning.SKYDOME_NETWORK_AUTO_POINTS, isPlayer = true)
        val reduction = DifficultyTuningImpl.valueFor(SicTuning.SKYDOME_NETWORK_CORE_MULT_REDUCTION, true)

        SicShared.highlightLine(
            tooltip,
            I18n.t(I18n.Categories.MOD, "ui.sic.skydome_network.line.points", "points" to points),
        )
        SicShared.highlightLine(
            tooltip,
            I18n.t(
                I18n.Categories.MOD, "ui.sic.skydome_network.line.core_mult",
                "mult" to SicShared.formatNumber(reduction),
            ),
        )
        SicShared.subLine(
            tooltip,
            I18n[I18n.Categories.MOD, "ui.sic.skydome_network.sub.core_mult"],
        )
        SicShared.highlightLine(
            tooltip,
            I18n[I18n.Categories.MOD, "ui.sic.skydome_network.line.personality"],
        )
        SicShared.subLine(
            tooltip,
            I18n[I18n.Categories.MOD, "ui.sic.skydome_network.sub.personality"],
        )
    }

    override fun applyEffectsBeforeShipCreation(
        data: SCData,
        stats: MutableShipStatsAPI,
        variant: ShipVariantAPI,
        hullSize: ShipAPI.HullSize,
        id: String,
    ) {
        val captain = stats.fleetMember?.captain ?: return
        val tier = StandardCores.byCommodity(captain.aiCoreId) ?: return
        val reduction = DifficultyTuningImpl.valueFor(SicTuning.SKYDOME_NETWORK_CORE_MULT_REDUCTION, data.isPlayer)
        stats.dynamic.getStat(SicTuning.SC_AUTO_POINTS_MULT_STAT)
            .modifyMult(id, SicTuning.coreMultFactor(tier.autoPointsMult, reduction))
    }

    override fun advance(data: SCData, amunt: Float) {
        if (data.isNPC) return
        SicShared.applyAutoPoints(spec.id, spec.name, SicTuning.SKYDOME_NETWORK_AUTO_POINTS, data.isPlayer)
        applyDoctrinePersonalities()
    }

    override fun onActivation(data: SCData) {
        if (data.isNPC) return
        SicShared.applyAutoPoints(spec.id, spec.name, SicTuning.SKYDOME_NETWORK_AUTO_POINTS, data.isPlayer)
        applyDoctrinePersonalities()
    }

    override fun onDeactivation(data: SCData) {
        if (data.isNPC) return
        SicShared.removeAutoPoints(spec.id)
        restoreDoctrinePersonalities()
    }

    /**
     * 性格跟随：逐帧遍历玩家舰队成员舰长，仅在需要变更时 setPersonality + 打标。
     * 无畏是 ASTD 制式核心与原版核心的出厂性格（StandardCores.createOfficerPerson /
     * 原版 AICoreOfficerPluginImpl），故「当前性格为无畏」视为未被其他模组染指。
     */
    private fun applyDoctrinePersonalities() {
        val target = SicTuning.doctrinePersonality(Global.getSector().playerFaction.doctrine.aggression)
        for (member in Global.getSector().playerFleet.fleetData.membersListCopy) {
            val captain = member.captain ?: continue
            if (!captain.isAICore) continue
            val current = captain.personalityAPI.id
            if (current == target) continue
            val marked = captain.memoryWithoutUpdate.contains(SicTuning.DOCTRINE_PERSONALITY_MEM_KEY)
            if (current != Personalities.RECKLESS && !marked) continue
            captain.setPersonality(target)
            captain.memoryWithoutUpdate.set(SicTuning.DOCTRINE_PERSONALITY_MEM_KEY, target)
        }
    }

    /**
     * 停用回收：本技能打标过的核心恢复无畏并清标。标记记录的是本技能实际写入的性格，
     * 仅当当前性格仍等于写入值时才恢复——打标期间被其他模组改过性格的核心保留对方修改。
     */
    private fun restoreDoctrinePersonalities() {
        for (member in Global.getSector().playerFleet.fleetData.membersListCopy) {
            val captain = member.captain ?: continue
            val memory = captain.memoryWithoutUpdate
            if (!memory.contains(SicTuning.DOCTRINE_PERSONALITY_MEM_KEY)) continue
            val applied = memory.getString(SicTuning.DOCTRINE_PERSONALITY_MEM_KEY)
            memory.unset(SicTuning.DOCTRINE_PERSONALITY_MEM_KEY)
            if (captain.personalityAPI.id == applied) {
                captain.setPersonality(Personalities.RECKLESS)
            }
        }
    }
}
