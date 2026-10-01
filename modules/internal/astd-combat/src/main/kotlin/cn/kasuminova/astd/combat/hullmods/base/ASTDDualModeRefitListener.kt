package cn.kasuminova.astd.combat.hullmods.base

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.econ.MarketAPI
import com.fs.starfarer.api.campaign.listeners.RefitScreenListener
import com.fs.starfarer.api.fleet.FleetMemberAPI

/**
 * 装配提交监听：refit 界面 `saveCurrentVariant` 把编辑克隆提交到真实成员后，
 * 对双模式舰做两件事——
 *
 * 1. **模式状态收敛**：提交的 variant 缺切换器 = 玩家拆切换器触发的「拆即切」尚未经 stats
 *    刷新结算（例如拆完立刻关界面），此处按 mode hullmod 的同语义补翻转并把切换器加回；
 *    切换器在场则走 [ensureASTDDualModeState] 幂等自举。正常情况下提交内容已收敛，两个分支都不做事。
 * 2. **舰长兼容清理**：无人模式不得有人类舰长、载人模式不得有 AI 核心（核心退还玩家货舱）。
 *    覆盖「自动装配确认但未勾选清空装配」等不经 strip 中途翻转、因而绕过
 *    [activateDualMode] 身份门清理的路径。
 */
class ASTDDualModeRefitListener : RefitScreenListener {

    private val log = Global.getLogger(ASTDDualModeRefitListener::class.java)

    override fun reportFleetMemberVariantSaved(member: FleetMemberAPI, dockedAt: MarketAPI?) {
        val variant = member.variant ?: return
        if (!variant.isASTDShipVariant()) return
        val config = ASTDDualModeRegistry.configForVariant(variant) ?: return

        if (variant.hasHullMod(config.switcherId)) {
            variant.ensureASTDDualModeState(config, null)
        } else {
            // 拆即切：提交时切换器缺席 → 翻到对侧模式并加回切换器（与 mode hullmod 同语义）；
            // stats=null：舰长清理由下方兼容清理按最终模式统一处理
            val target = if (variant.hasASTDDualModeAutomated(config)) config.crewedModeId else config.automatedModeId
            variant.activateDualMode(config, target, null)
            variant.addMod(config.switcherId)
            log.info("[ASTD] 装配提交收敛：${member.id} 拆即切补翻转到 $target")
        }

        clearIncompatibleCaptain(member, config)
    }

    /**
     * 按提交后的最终模式精确清理不兼容舰长：
     * - 无人模式 + 人类舰长 → 直接卸下（人类军官回到未分配列表）；
     * - 载人模式 + AI 核心 → 核心退还玩家货舱后卸下；
     * - 默认舰长（无名）与兼容组合不动。
     */
    private fun clearIncompatibleCaptain(member: FleetMemberAPI, config: ASTDDualModeConfig) {
        val captain = member.captain ?: return
        if (captain.isDefault) return
        val automated = member.variant?.hasASTDDualModeAutomated(config) ?: return
        val aiCoreId = captain.aiCoreId
        when {
            automated && aiCoreId == null -> {
                member.captain = null
                log.info("[ASTD] 装配提交清理：${member.id} 无人模式卸下人类舰长 ${captain.nameString}")
            }
            !automated && aiCoreId != null -> {
                val cargo = Global.getSector()?.playerFleet?.cargo
                if (cargo != null) {
                    cargo.addCommodity(aiCoreId, 1f)
                } else {
                    log.warn("[ASTD] 装配提交清理：${member.id} 退还 AI 核心时玩家货舱不可达，核心丢失（$aiCoreId）")
                }
                member.captain = null
                log.info("[ASTD] 装配提交清理：${member.id} 载人模式卸下 AI 核心 $aiCoreId")
            }
        }
    }
}
