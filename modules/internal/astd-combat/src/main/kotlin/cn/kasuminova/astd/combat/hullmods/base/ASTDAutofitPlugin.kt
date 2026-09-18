package cn.kasuminova.astd.combat.hullmods.base

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.PluginPick
import com.fs.starfarer.api.campaign.BaseCampaignPlugin
import com.fs.starfarer.api.campaign.CampaignPlugin.PickPriority
import com.fs.starfarer.api.characters.PersonAPI
import com.fs.starfarer.api.combat.ShipVariantAPI
import com.fs.starfarer.api.fleet.FleetMemberAPI
import com.fs.starfarer.api.plugins.AutofitPlugin
import com.fs.starfarer.api.plugins.AutofitPlugin.AutofitPluginDelegate
import com.fs.starfarer.api.plugins.impl.CoreAutofitPlugin
import org.apache.log4j.Logger

/**
 * ASTD 自动装配插件：在原版 [CoreAutofitPlugin] 基础上保护双模式（载人/无人）状态。
 *
 * 动机：vanilla 自动装配勾选「清空当前装配」（CoreAutofitPlugin.STRIP）时会拆下全部非内置船插，
 * 包括双模式切换器；模式 hullmod 的「拆即切」逻辑会在 strip 中途的 stats 重算里触发，
 * 导致确认方案后模式被额外翻转一次，不遵守装配方案保存时的模式。
 *
 * 行为：doFit 前记录当前模式；装配完成后按 [resolveAutofitGoalMode] 的优先级恢复
 * （目标方案声明的模式 → 装配前模式），使「应用装配方案」对双模式舰表现为幂等。
 * 其余装配行为完全继承原版实现。
 */
class ASTDAutofitPlugin(fleetCommander: PersonAPI?) : CoreAutofitPlugin(fleetCommander) {

    private val logger: Logger = Logger.getLogger(ASTDAutofitPlugin::class.java)

    override fun doFit(current: ShipVariantAPI, target: ShipVariantAPI, maxSMods: Int, delegate: AutofitPluginDelegate) {
        // 站点模块递归装配不处理：模块无切换器/模式状态，恢复逻辑只应对主舰执行一次
        if (fittingModule) {
            super.doFit(current, target, maxSMods, delegate)
            return
        }
        val config = ASTDDualModeRegistry.configForVariant(current)
        val preFitMode = activeDualModeId(current, config)
        val preFitHadSwitcher = config != null && current.hasHullMod(config.switcherId)
        // strip 中途的「拆即切」翻转会顺手卸下舰长/AI 核心（activateDualMode 的战役侧清理），
        // 先快照舰长，净模式未变时在恢复阶段还原（见下方 restoreCaptainIfModeUnchanged）
        val member = delegate.fleetMember
        val preFitCaptain = if (preFitMode != null) member?.captain else null
        super.doFit(current, target, maxSMods, delegate)
        if (config == null) return

        // 切换器常驻兜底：无论翻转是否在装配期发生（部分委派上下文的 stats 重算是延迟的），
        // 只要装配前装着切换器，装配后必须在场
        if (preFitHadSwitcher && !current.hasHullMod(config.switcherId)) {
            current.addMod(config.switcherId)
        }

        val desiredMode = resolveAutofitGoalMode(current, target, config, preFitMode)
        if (desiredMode != null && activeDualModeId(current, config) != desiredMode) {
            current.activateDualMode(config, desiredMode, delegate.ship?.mutableStats)
            delegate.syncUIWithVariant(current)
        }
        restoreCaptainIfModeUnchanged(member, preFitCaptain, preFitMode, activeDualModeId(current, config))
    }

    /**
     * 净模式未变时还原被中途翻转误卸的舰长：
     * 翻转与回翻各清一次舰长，若最终模式与装配前一致，这次清理纯属副作用，应撤销。
     * AI 核心舰长：翻转时已把核心退回玩家货舱，还原时从货舱核销一件，避免核心复制。
     * 净模式发生变化（方案真的要求另一模式）时不还原——舰长本就不兼容新模式。
     */
    private fun restoreCaptainIfModeUnchanged(
        member: FleetMemberAPI?,
        preFitCaptain: PersonAPI?,
        preFitMode: String?,
        postFitMode: String?,
    ) {
        if (member == null || preFitCaptain == null || preFitMode == null) return
        if (postFitMode != preFitMode) return
        if (member.captain != null) return
        val aiCoreId = preFitCaptain.aiCoreId
        if (aiCoreId != null) {
            val cargo = Global.getSector()?.playerFleet?.cargo
            if (cargo != null) {
                cargo.removeCommodity(aiCoreId, 1f)
            } else {
                logger.warn("ASTDAutofitPlugin: 还原 AI 核心舰长时玩家货舱不可达，核心退回件无法核销（$aiCoreId）")
            }
        }
        member.setCaptain(preFitCaptain)
    }

    companion object {
        /**
         * 读取 variant 当前激活的双模式 permaMod id；无模式状态或配置缺失返回 null。
         * 供 [ASTDAutofitPlugin] 与单元测试直接使用（纯查询，不写状态）。
         */
        fun activeDualModeId(variant: ShipVariantAPI?, config: ASTDDualModeConfig?): String? {
            if (variant == null || config == null) return null
            return when {
                variant.permaMods.contains(config.crewedModeId) -> config.crewedModeId
                variant.permaMods.contains(config.automatedModeId) -> config.automatedModeId
                else -> null
            }
        }

        /**
         * 决定装配完成后应当处于的模式：
         * 1. 目标方案（保存的装配方案 variant 携带 permaMods）声明的模式优先——遵守方案保存时的模式；
         * 2. 方案未携带模式状态时保持装配前模式——装配不应改变模式；
         * 3. 非双模式舰返回 null（不干预）。
         */
        fun resolveAutofitGoalMode(
            current: ShipVariantAPI?,
            target: ShipVariantAPI?,
            config: ASTDDualModeConfig?,
            preFitMode: String? = activeDualModeId(current, config),
        ): String? {
            if (config == null) return null
            return activeDualModeId(target, config) ?: preFitMode
        }
    }
}

/**
 * 自动装配插件挑选器：仅对「双模式相关」的 ASTD 舰（已装切换器或已有模式 permaMod）
 * 提供 [ASTDAutofitPlugin]；其余舰船返回 null，交还 vanilla 默认插件。
 *
 * 动机：双模式保护只对双模式舰有意义，非双模式舰不应改变任何装配行为。
 */
object ASTDAutofitPluginPicker {

    fun pick(member: FleetMemberAPI?): PluginPick<AutofitPlugin>? {
        val variant = member?.variant ?: return null
        val config = ASTDDualModeRegistry.configForVariant(variant) ?: return null
        val dualModeRelevant = variant.hasHullMod(config.switcherId) ||
            variant.permaMods.contains(config.crewedModeId) ||
            variant.permaMods.contains(config.automatedModeId)
        if (!dualModeRelevant) return null

        val commander = member.fleetCommanderForStats ?: member.fleetCommander
        return PluginPick(ASTDAutofitPlugin(commander), PickPriority.MOD_SPECIFIC)
    }
}

/**
 * ASTD 战役插件：向原版插件挑选体系暴露 [ASTDAutofitPlugin]。
 *
 * 动机：0.98 的 ModPlugin 不继承 CampaignPlugin，pickAutofitPlugin 只对注册进
 * ModAndPluginData 的 CampaignPlugin 生效；因此经 [SectorAPI.registerPlugin] 注册本插件
 * （见 AsteriaDirectoratePlugin.onGameLoad）。固定 id 使重复注册（多次读档）时互相替换而不堆积；
 * transient（基类默认 true）避免被写入存档。
 */
class ASTDCampaignPlugin : BaseCampaignPlugin() {

    override fun getId(): String = "asteria_directorate_campaign_plugin"

    override fun pickAutofitPlugin(member: FleetMemberAPI?): PluginPick<AutofitPlugin>? =
        ASTDAutofitPluginPicker.pick(member)
}
