package cn.kasuminova.astd.combat.shipsystems

/**
 * 回声定影（无人模式）系统脚本。
 *
 * 机制与载人版完全一致（spec §2 定影 → 回放无模式差异）；本类仅以 [isAutomatedSystem]=true
 * 标识模式，用于状态栏台词分版（automated.*）。
 */
class EchoFixationAutomatedSystemStats : EchoFixationSystemStats() {
    override val isAutomatedSystem: Boolean = true
}
