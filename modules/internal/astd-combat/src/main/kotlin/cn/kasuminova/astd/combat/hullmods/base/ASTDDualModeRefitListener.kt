package cn.kasuminova.astd.combat.hullmods.base

import com.fs.starfarer.api.GameState
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.econ.MarketAPI
import com.fs.starfarer.api.campaign.listeners.RefitScreenListener
import com.fs.starfarer.api.combat.ShipVariantAPI
import com.fs.starfarer.api.fleet.FleetMemberAPI

/**
 * 装配提交监听：refit 界面 `saveCurrentVariant` 把编辑克隆提交到真实成员后，
 * 对双模式舰做两件事——
 *
 * 1. **模式状态收敛**（仅对已参与双模式的舰：存在模式 permaMod / next marker，或装着切换器）：
 *    提交的 variant 缺切换器 = 玩家拆切换器触发的「拆即切」尚未经 stats 刷新结算
 *    （例如拆完立刻关界面），此处按 mode hullmod 的同语义补翻转并把切换器加回；
 *    切换器在场则走 [ensureASTDDualModeState] 幂等自举。正常情况下提交内容已收敛，两个分支都不做事。
 *    从未参与双模式的 ASTD 量产舰（无切换器且无模式状态，如 astd_xc_104）不在此收敛范围，
 *    否则会被误强加无人模式与切换器。
 * 2. **舰长兼容清理**（仅玩家舰队成员）：无人模式不得有人类舰长、载人模式不得有 AI 核心
 *    （核心退还玩家货舱）。覆盖「自动装配确认但未勾选清空装配」等不经 strip 中途翻转、
 *    因而绕过 [activateDualMode] 身份门清理的路径。
 */
class ASTDDualModeRefitListener : RefitScreenListener {

    private val log = Global.getLogger(ASTDDualModeRefitListener::class.java)

    override fun reportFleetMemberVariantSaved(member: FleetMemberAPI, dockedAt: MarketAPI?) {
        // 主菜单模拟装配（stock variant / 假成员）不收敛：无战役上下文，清理与退还都无从谈起
        if (Global.getCurrentState() == GameState.TITLE) return
        val variant = member.variant ?: return
        if (!variant.isASTDShipVariant()) return
        val config = ASTDDualModeRegistry.configForVariant(variant) ?: return

        if (variant.hasHullMod(config.switcherId)) {
            variant.ensureASTDDualModeState(config, null)
        } else if (hasDualModeState(variant, config)) {
            // 拆即切：提交时切换器缺席且该舰已参与双模式 → 翻到对侧模式并加回切换器
            // （与 mode hullmod 同语义）；stats=null：舰长清理由下方兼容清理按最终模式统一处理
            val target = if (variant.hasASTDDualModeAutomated(config)) config.crewedModeId else config.automatedModeId
            variant.activateDualMode(config, target, null)
            variant.addMod(config.switcherId)
            log.info("[ASTD] 装配提交收敛：${member.id} 拆即切补翻转到 $target")
        }

        // 兼容清理只对玩家舰队成员执行：AI 核心退还的是玩家货舱，且战役内只有玩家会装配双模式舰
        if (member.fleetData?.fleet?.isPlayerFleet == true) {
            clearIncompatibleDualModeCaptain(member, config)
        }
    }

    /** 该 variant 是否已参与双模式（带任一模式 permaMod 或 next marker）。 */
    private fun hasDualModeState(variant: ShipVariantAPI, config: ASTDDualModeConfig): Boolean =
        variant.permaMods.contains(config.crewedModeId) ||
            variant.permaMods.contains(config.automatedModeId) ||
            variant.permaMods.contains(config.nextCrewedMarker) ||
            variant.permaMods.contains(config.nextAutomatedMarker)
}
