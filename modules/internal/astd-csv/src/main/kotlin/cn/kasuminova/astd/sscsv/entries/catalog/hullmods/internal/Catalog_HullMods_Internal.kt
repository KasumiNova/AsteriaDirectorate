package cn.kasuminova.astd.sscsv.entries.catalog.hullmods.internal

import cn.kasuminova.astd.sscsv.entries.HullModEntry

/**
 * 内部/测试用途 HullMods。
 *
 * 注意：这些 HullMod 可能不会在 UI 中显示，但会被脚本/测试逻辑自动添加。
 */
object Hm_astd_bounty_scaling : HullModEntry() {
    override val id: String = "astd_bounty_scaling"
    override val name: String = "赏金难度缩放（隐藏）"
    override val tech: String = "astd_hidden"
    override val uiTags: String = ""
    override val tags: String = ""
    override val hidden: Boolean = true
    override val hiddenEverywhere: Boolean = true
    override val unlocked: Boolean = true
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.BountyScalingHullMod"
    override val desc: String = "内部用途：赏金敌方小幅数值缩放（由 k 控制）。"
    override val short: String = "赏金缩放（隐藏）"
    override val sprite: String = "graphics/hullmods/astd_zero_point_compute_core.png"
}

object Hm_astd_affix_overclocked_coils : HullModEntry() {
    override val id: String = "astd_affix_overclocked_coils"
    override val name: String = "超频线圈（词缀·隐藏）"
    override val tech: String = "astd_hidden"
    override val uiTags: String = ""
    override val tags: String = ""
    override val hidden: Boolean = true
    override val hiddenEverywhere: Boolean = true
    override val unlocked: Boolean = true
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixOverclockedCoilsHullMod"
    override val desc: String = "内部用途：赏金词缀。提高能量武器节奏并增加热债。"
    override val short: String = "超频线圈（隐藏）"
    override val sprite: String = "graphics/hullmods/astd_zero_point_compute_core.png"
}

object Hm_astd_affix_jamming_nodes : HullModEntry() {
    override val id: String = "astd_affix_jamming_nodes"
    override val name: String = "干扰节点（词缀·隐藏）"
    override val tech: String = "astd_hidden"
    override val uiTags: String = ""
    override val tags: String = ""
    override val hidden: Boolean = true
    override val hiddenEverywhere: Boolean = true
    override val unlocked: Boolean = true
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixJammingNodesHullMod"
    override val desc: String = "内部用途：赏金词缀。强化机动与自动火控。"
    override val short: String = "干扰节点（隐藏）"
    override val sprite: String = "graphics/hullmods/astd_zero_point_compute_core.png"
}

object Hm_astd_affix_entropy_shields : HullModEntry() {
    override val id: String = "astd_affix_entropy_shields"
    override val name: String = "熵化护盾（词缀·隐藏）"
    override val tech: String = "astd_hidden"
    override val uiTags: String = ""
    override val tags: String = ""
    override val hidden: Boolean = true
    override val hiddenEverywhere: Boolean = true
    override val unlocked: Boolean = true
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixEntropyShieldsHullMod"
    override val desc: String = "内部用途：赏金词缀。护盾更硬但维持更贵。"
    override val short: String = "熵护盾（隐藏）"
    override val sprite: String = "graphics/hullmods/astd_zero_point_compute_core.png"
}

object Hm_astd_affix_reckless_drive : HullModEntry() {
    override val id: String = "astd_affix_reckless_drive"
    override val name: String = "超压推进（词缀·隐藏）"
    override val tech: String = "astd_hidden"
    override val uiTags: String = ""
    override val tags: String = ""
    override val hidden: Boolean = true
    override val hiddenEverywhere: Boolean = true
    override val unlocked: Boolean = true
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixRecklessDriveHullMod"
    override val desc: String = "内部用途：赏金词缀。机动更强但装甲更脆。"
    override val short: String = "超压推进（隐藏）"
    override val sprite: String = "graphics/hullmods/astd_zero_point_compute_core.png"
}

object Hm_astd_affix_phase_instability : HullModEntry() {
    override val id: String = "astd_affix_phase_instability"
    override val name: String = "相位不稳定（词缀·隐藏）"
    override val tech: String = "astd_hidden"
    override val uiTags: String = ""
    override val tags: String = ""
    override val hidden: Boolean = true
    override val hiddenEverywhere: Boolean = true
    override val unlocked: Boolean = true
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixPhaseInstabilityHullMod"
    override val desc: String = "内部用途：赏金词缀。相位成本惩罚 + 少量火力提升。"
    override val short: String = "相位不稳（隐藏）"
    override val sprite: String = "graphics/hullmods/astd_zero_point_compute_core.png"
}

object Hm_astd_affix_zero_margin : HullModEntry() {
    override val id: String = "astd_affix_zero_margin"
    override val name: String = "零余量纪律（词缀·隐藏）"
    override val tech: String = "astd_hidden"
    override val uiTags: String = ""
    override val tags: String = ""
    override val hidden: Boolean = true
    override val hiddenEverywhere: Boolean = true
    override val unlocked: Boolean = true
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixZeroMarginHullMod"
    override val desc: String = "内部用途：赏金词缀。更省幅能更强压制，但峰值更短。"
    override val short: String = "零余量（隐藏）"
    override val sprite: String = "graphics/hullmods/astd_zero_point_compute_core.png"
}

// ─── M 型词缀 ───

object Hm_astd_affix_decoy_swarm : HullModEntry() {
    override val id: String = "astd_affix_decoy_swarm"
    override val name: String = "诱饵蜂群（词缀·隐藏）"
    override val tech: String = "astd_hidden"
    override val uiTags: String = ""
    override val tags: String = ""
    override val hidden: Boolean = true
    override val hiddenEverywhere: Boolean = true
    override val unlocked: Boolean = true
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixDecoySwarmHullMod"
    override val desc: String = "内部用途：赏金词缀。增强点防覆盖与战机生存，降低导弹速度。"
    override val short: String = "诱饵蜂群（隐藏）"
    override val sprite: String = "graphics/hullmods/astd_zero_point_compute_core.png"
}

object Hm_astd_affix_fragmented_orders : HullModEntry() {
    override val id: String = "astd_affix_fragmented_orders"
    override val name: String = "断章（词缀·隐藏）"
    override val tech: String = "astd_hidden"
    override val uiTags: String = ""
    override val tags: String = ""
    override val hidden: Boolean = true
    override val hiddenEverywhere: Boolean = true
    override val unlocked: Boolean = true
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixFragmentedOrdersHullMod"
    override val desc: String = "内部用途：赏金词缀。突发火力增强但精准度降低。"
    override val short: String = "断章（隐藏）"
    override val sprite: String = "graphics/hullmods/astd_zero_point_compute_core.png"
}

object Hm_astd_affix_coherent_link : HullModEntry() {
    override val id: String = "astd_affix_coherent_link"
    override val name: String = "协同链路（词缀·隐藏）"
    override val tech: String = "astd_hidden"
    override val uiTags: String = ""
    override val tags: String = ""
    override val hidden: Boolean = true
    override val hiddenEverywhere: Boolean = true
    override val unlocked: Boolean = true
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixCoherentLinkHullMod"
    override val desc: String = "内部用途：赏金词缀。编队协同增强但孤立时更脆弱。"
    override val short: String = "协同链路（隐藏）"
    override val sprite: String = "graphics/hullmods/astd_zero_point_compute_core.png"
}

object Hm_astd_affix_overdrive_window : HullModEntry() {
    override val id: String = "astd_affix_overdrive_window"
    override val name: String = "过驱窗口（词缀·隐藏）"
    override val tech: String = "astd_hidden"
    override val uiTags: String = ""
    override val tags: String = ""
    override val hidden: Boolean = true
    override val hiddenEverywhere: Boolean = true
    override val unlocked: Boolean = true
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixOverdriveWindowHullMod"
    override val desc: String = "内部用途：赏金词缀。能量武器爆发增强但通量容量降低。"
    override val short: String = "过驱窗口（隐藏）"
    override val sprite: String = "graphics/hullmods/astd_zero_point_compute_core.png"
}

object Hm_astd_affix_lens_refraction : HullModEntry() {
    override val id: String = "astd_affix_lens_refraction"
    override val name: String = "透镜折射（词缀·隐藏）"
    override val tech: String = "astd_hidden"
    override val uiTags: String = ""
    override val tags: String = ""
    override val hidden: Boolean = true
    override val hiddenEverywhere: Boolean = true
    override val unlocked: Boolean = true
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixLensRefractionHullMod"
    override val desc: String = "内部用途：赏金词缀。点防拦截能力增强。"
    override val short: String = "透镜折射（隐藏）"
    override val sprite: String = "graphics/hullmods/astd_zero_point_compute_core.png"
}

object Hm_astd_affix_reactor_backfeed : HullModEntry() {
    override val id: String = "astd_affix_reactor_backfeed"
    override val name: String = "反应堆回灌（词缀·隐藏）"
    override val tech: String = "astd_hidden"
    override val uiTags: String = ""
    override val tags: String = ""
    override val hidden: Boolean = true
    override val hiddenEverywhere: Boolean = true
    override val unlocked: Boolean = true
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixReactorBackfeedHullMod"
    override val desc: String = "内部用途：赏金词缀。过载恢复增强但结构脆弱。"
    override val short: String = "反应堆回灌（隐藏）"
    override val sprite: String = "graphics/hullmods/astd_zero_point_compute_core.png"
}

object Hm_astd_affix_payload_denial : HullModEntry() {
    override val id: String = "astd_affix_payload_denial"
    override val name: String = "载荷拒止（词缀·隐藏）"
    override val tech: String = "astd_hidden"
    override val uiTags: String = ""
    override val tags: String = ""
    override val hidden: Boolean = true
    override val hiddenEverywhere: Boolean = true
    override val unlocked: Boolean = true
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixPayloadDenialHullMod"
    override val desc: String = "内部用途：赏金词缀。导弹拦截特化。"
    override val short: String = "载荷拒止（隐藏）"
    override val sprite: String = "graphics/hullmods/astd_zero_point_compute_core.png"
}

object Hm_astd_affix_recorded_loop : HullModEntry() {
    override val id: String = "astd_affix_recorded_loop"
    override val name: String = "重放回路（词缀·隐藏）"
    override val tech: String = "astd_hidden"
    override val uiTags: String = ""
    override val tags: String = ""
    override val hidden: Boolean = true
    override val hiddenEverywhere: Boolean = true
    override val unlocked: Boolean = true
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixRecordedLoopHullMod"
    override val desc: String = "内部用途：赏金词缀。可预测但精确的机动模式。"
    override val short: String = "重放回路（隐藏）"
    override val sprite: String = "graphics/hullmods/astd_zero_point_compute_core.png"
}

object Hm_astd_affix_distributed_grid : HullModEntry() {
    override val id: String = "astd_affix_distributed_grid"
    override val name: String = "栅格轮换（词缀·隐藏）"
    override val tech: String = "astd_hidden"
    override val uiTags: String = ""
    override val tags: String = ""
    override val hidden: Boolean = true
    override val hiddenEverywhere: Boolean = true
    override val unlocked: Boolean = true
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixDistributedGridHullMod"
    override val desc: String = "内部用途：赏金词缀。分布式护盾系统。"
    override val short: String = "栅格轮换（隐藏）"
    override val sprite: String = "graphics/hullmods/astd_zero_point_compute_core.png"
}

object Hm_astd_affix_thermodynamic_exchange : HullModEntry() {
    override val id: String = "astd_affix_thermodynamic_exchange"
    override val name: String = "热力交换（词缀·隐藏）"
    override val tech: String = "astd_hidden"
    override val uiTags: String = ""
    override val tags: String = ""
    override val hidden: Boolean = true
    override val hiddenEverywhere: Boolean = true
    override val unlocked: Boolean = true
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixThermodynamicExchangeHullMod"
    override val desc: String = "内部用途：赏金词缀。散热增强但峰值缩短。"
    override val short: String = "热力交换（隐藏）"
    override val sprite: String = "graphics/hullmods/astd_zero_point_compute_core.png"
}

object Hm_astd_affix_gravity_pulse : HullModEntry() {
    override val id: String = "astd_affix_gravity_pulse"
    override val name: String = "引力脉冲（词缀·隐藏）"
    override val tech: String = "astd_hidden"
    override val uiTags: String = ""
    override val tags: String = ""
    override val hidden: Boolean = true
    override val hiddenEverywhere: Boolean = true
    override val unlocked: Boolean = true
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixGravityPulseHullMod"
    override val desc: String = "内部用途：赏金词缀。弹道干扰：弹速增加但射程降低。"
    override val short: String = "引力脉冲（隐藏）"
    override val sprite: String = "graphics/hullmods/astd_zero_point_compute_core.png"
}

object Hm_astd_affix_phase_debt : HullModEntry() {
    override val id: String = "astd_affix_phase_debt"
    override val name: String = "相债（词缀·隐藏）"
    override val tech: String = "astd_hidden"
    override val uiTags: String = ""
    override val tags: String = ""
    override val hidden: Boolean = true
    override val hiddenEverywhere: Boolean = true
    override val unlocked: Boolean = true
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixPhaseDebtHullMod"
    override val desc: String = "内部用途：赏金词缀。相位成本大增但火力增强。"
    override val short: String = "相债（隐藏）"
    override val sprite: String = "graphics/hullmods/astd_zero_point_compute_core.png"
}

object Hm_astd_affix_reinforcement_ping : HullModEntry() {
    override val id: String = "astd_affix_reinforcement_ping"
    override val name: String = "增援信标（词缀·隐藏）"
    override val tech: String = "astd_hidden"
    override val uiTags: String = ""
    override val tags: String = ""
    override val hidden: Boolean = true
    override val hiddenEverywhere: Boolean = true
    override val unlocked: Boolean = true
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixReinforcementPingHullMod"
    override val desc: String = "内部用途：赏金词缀。战机编队强化。"
    override val short: String = "增援信标（隐藏）"
    override val sprite: String = "graphics/hullmods/astd_zero_point_compute_core.png"
}

// ─── R 型词缀 ───

object Hm_astd_affix_no_retreat : HullModEntry() {
    override val id: String = "astd_affix_no_retreat"
    override val name: String = "退场否决（词缀·隐藏）"
    override val tech: String = "astd_hidden"
    override val uiTags: String = ""
    override val tags: String = ""
    override val hidden: Boolean = true
    override val hiddenEverywhere: Boolean = true
    override val unlocked: Boolean = true
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixNoRetreatHullMod"
    override val desc: String = "内部用途：赏金词缀。不撤退，追击强化。"
    override val short: String = "退场否决（隐藏）"
    override val sprite: String = "graphics/hullmods/astd_zero_point_compute_core.png"
}

object Hm_astd_affix_target_smear : HullModEntry() {
    override val id: String = "astd_affix_target_smear"
    override val name: String = "目标涂抹（词缀·隐藏）"
    override val tech: String = "astd_hidden"
    override val uiTags: String = ""
    override val tags: String = ""
    override val hidden: Boolean = true
    override val hiddenEverywhere: Boolean = true
    override val unlocked: Boolean = true
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixTargetSmearHullMod"
    override val desc: String = "内部用途：赏金词缀。远程精准度降低但近距压制增强。"
    override val short: String = "目标涂抹（隐藏）"
    override val sprite: String = "graphics/hullmods/astd_zero_point_compute_core.png"
}

object Hm_astd_affix_logic_gate : HullModEntry() {
    override val id: String = "astd_affix_logic_gate"
    override val name: String = "逻辑门槛（词缀·隐藏）"
    override val tech: String = "astd_hidden"
    override val uiTags: String = ""
    override val tags: String = ""
    override val hidden: Boolean = true
    override val hiddenEverywhere: Boolean = true
    override val unlocked: Boolean = true
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixLogicGateHullMod"
    override val desc: String = "内部用途：赏金词缀。ECM 增强与导弹干扰。"
    override val short: String = "逻辑门槛（隐藏）"
    override val sprite: String = "graphics/hullmods/astd_zero_point_compute_core.png"
}

object Hm_astd_affix_causality_lag : HullModEntry() {
    override val id: String = "astd_affix_causality_lag"
    override val name: String = "因果延迟（词缀·隐藏）"
    override val tech: String = "astd_hidden"
    override val uiTags: String = ""
    override val tags: String = ""
    override val hidden: Boolean = true
    override val hiddenEverywhere: Boolean = true
    override val unlocked: Boolean = true
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixCausalityLagHullMod"
    override val desc: String = "内部用途：赏金词缀。系统冷却更快但护盾响应更慢。"
    override val short: String = "因果延迟（隐藏）"
    override val sprite: String = "graphics/hullmods/astd_zero_point_compute_core.png"
}

object Hm_astd_affix_reconstruction_verdict : HullModEntry() {
    override val id: String = "astd_affix_reconstruction_verdict"
    override val name: String = "重构裁决（词缀·隐藏）"
    override val tech: String = "astd_hidden"
    override val uiTags: String = ""
    override val tags: String = ""
    override val hidden: Boolean = true
    override val hiddenEverywhere: Boolean = true
    override val unlocked: Boolean = true
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixReconstructionVerdictHullMod"
    override val desc: String = "内部用途：赏金词缀。击毁触发装甲/护盾回滚。"
    override val short: String = "重构裁决（隐藏）"
    override val sprite: String = "graphics/hullmods/astd_zero_point_compute_core.png"
}

object Hm_astd_affix_lockdown_zone : HullModEntry() {
    override val id: String = "astd_affix_lockdown_zone"
    override val name: String = "战区封闭（词缀·隐藏）"
    override val tech: String = "astd_hidden"
    override val uiTags: String = ""
    override val tags: String = ""
    override val hidden: Boolean = true
    override val hiddenEverywhere: Boolean = true
    override val unlocked: Boolean = true
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixLockdownZoneHullMod"
    override val desc: String = "内部用途：赏金词缀。战场边界封闭效应。"
    override val short: String = "战区封闭（隐藏）"
    override val sprite: String = "graphics/hullmods/astd_zero_point_compute_core.png"
}

object Hm_astd_affix_vector_silence : HullModEntry() {
    override val id: String = "astd_affix_vector_silence"
    override val name: String = "向量静默（词缀·隐藏）"
    override val tech: String = "astd_hidden"
    override val uiTags: String = ""
    override val tags: String = ""
    override val hidden: Boolean = true
    override val hiddenEverywhere: Boolean = true
    override val unlocked: Boolean = true
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixVectorSilenceHullMod"
    override val desc: String = "内部用途：赏金词缀。信息抑制与传感器优势。"
    override val short: String = "向量静默（隐藏）"
    override val sprite: String = "graphics/hullmods/astd_zero_point_compute_core.png"
}

// ─── 特殊/Omega 词缀 ───

object Hm_astd_affix_fractal_shards : HullModEntry() {
    override val id: String = "astd_affix_fractal_shards"
    override val name: String = "分形碎片（词缀·隐藏）"
    override val tech: String = "astd_hidden"
    override val uiTags: String = ""
    override val tags: String = ""
    override val hidden: Boolean = true
    override val hiddenEverywhere: Boolean = true
    override val unlocked: Boolean = true
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixFractalShardsHullMod"
    override val desc: String = "内部用途：赏金词缀。击毁释放碎片无人机。"
    override val short: String = "分形碎片（隐藏）"
    override val sprite: String = "graphics/hullmods/astd_zero_point_compute_core.png"
}

object Hm_astd_affix_reality_sieve : HullModEntry() {
    override val id: String = "astd_affix_reality_sieve"
    override val name: String = "现实筛网（词缀·隐藏）"
    override val tech: String = "astd_hidden"
    override val uiTags: String = ""
    override val tags: String = ""
    override val hidden: Boolean = true
    override val hiddenEverywhere: Boolean = true
    override val unlocked: Boolean = true
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixRealitySieveHullMod"
    override val desc: String = "内部用途：赏金词缀。时间流偏差效应。"
    override val short: String = "现实筛网（隐藏）"
    override val sprite: String = "graphics/hullmods/astd_zero_point_compute_core.png"
}

object Hm_astd_affix_dual_validation : HullModEntry() {
    override val id: String = "astd_affix_dual_validation"
    override val name: String = "双重校验（词缀·隐藏）"
    override val tech: String = "astd_hidden"
    override val uiTags: String = ""
    override val tags: String = ""
    override val hidden: Boolean = true
    override val hiddenEverywhere: Boolean = true
    override val unlocked: Boolean = true
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixDualValidationHullMod"
    override val desc: String = "内部用途：赏金词缀。两段式验证：火力压制与干扰封锁。"
    override val short: String = "双重校验（隐藏）"
    override val sprite: String = "graphics/hullmods/astd_zero_point_compute_core.png"
}

object Hm_astd_affix_verdict_warmup : HullModEntry() {
    override val id: String = "astd_affix_verdict_warmup"
    override val name: String = "审判预热（词缀·隐藏）"
    override val tech: String = "astd_hidden"
    override val uiTags: String = ""
    override val tags: String = ""
    override val hidden: Boolean = true
    override val hiddenEverywhere: Boolean = true
    override val unlocked: Boolean = true
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixVerdictWarmupHullMod"
    override val desc: String = "内部用途：赏金词缀。锚点保护阶段防御增强。"
    override val short: String = "审判预热（隐藏）"
    override val sprite: String = "graphics/hullmods/astd_zero_point_compute_core.png"
}

// ─── 测试用途 ───

object Hm_astd_test_shield_coverage : HullModEntry() {
    override val id: String = "astd_test_shield_coverage"
    override val name: String = "测试：护盾覆盖率（隐藏）"
    override val tech: String = "astd_hidden"
    override val uiTags: String = ""
    override val tags: String = ""
    override val hidden: Boolean = true
    override val hiddenEverywhere: Boolean = true
    override val unlocked: Boolean = true
    override val script: String = "cn.kasuminova.astd.combat.hullmods.AsteriaTestShieldCoverageHullMod"
    override val desc: String = "内部用途：在战斗中显示护盾覆盖范围调试信息。"
    override val short: String = "护盾覆盖（测试）"
    override val sprite: String = "graphics/hullmods/astd_zero_point_compute_core.png"
}
