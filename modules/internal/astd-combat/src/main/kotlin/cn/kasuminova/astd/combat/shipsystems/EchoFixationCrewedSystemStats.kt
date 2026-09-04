package cn.kasuminova.astd.combat.shipsystems

/**
 * 回声定影（载人模式）系统脚本。
 *
 * 机制与无人版完全一致（spec §2 定影 → 回放无模式差异）；本类仅以 [isAutomatedSystem]=false
 * 标识模式，用于状态栏台词分版（crewed.*）。
 */
class EchoFixationCrewedSystemStats : EchoFixationSystemStats() {
    override val isAutomatedSystem: Boolean = false
}
