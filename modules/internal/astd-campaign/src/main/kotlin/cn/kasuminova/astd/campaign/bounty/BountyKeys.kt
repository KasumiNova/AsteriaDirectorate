package cn.kasuminova.astd.campaign.bounty

import cn.kasuminova.astd.campaign.bounty.BountyKeys.MEM_AFFIXES
import cn.kasuminova.astd.campaign.bounty.BountyKeys.MEM_SETTLED_PREFIX


/**
 * 赏金系统统一常量。
 */
object BountyKeys {

    const val MOD_PREFIX: String = "astd"

    /**
     * 用于将本模组生成的 MagicBounty 与其它来源区分开。
     */
    const val BOUNTY_KEY_PREFIX: String = "astd_"

    /**
     * Sector persistentData key：保存主线进度、解锁词缀池、已处理的 bounty 状态等。
     */
    const val PERSISTENT_STATE_KEY: String = "astd_bounty_state"

    /**
     * Sector memory key：仅用于避免重复 addScript（不写入存档也可以，但用 memory 足够）。
     */
    const val MEMORY_MANAGER_ADDED: String = "\$astd_bounty_manager_added"

    /**
     * FleetMember memory：难度系数 k（0..1）。
     */
    const val MEM_K: String = "\$astd_bounty_k"

    /**
     * FleetMember memory：总缩放（1..15），便于调试与某些 affix 做离散档位。
     */
    const val MEM_TOTAL_MULT: String = "\$astd_bounty_total_mult"

    /**
     * Fleet memory：该 fleet 已应用过动态生成/词缀（避免多次重建）。
     */
    const val MEM_FLEET_PATCHED: String = "\$astd_bounty_fleet_patched"

    /**
     * Fleet memory：本次 bounty 选中的词缀列表（CSV：id1,id2,...）。
     */
    const val MEM_AFFIXES: String = "\$astd_bounty_affixes"

    /**
     * Fleet memory：旗舰专属词缀列表（CSV：id1,id2,...；如四章中军旗舰 R-17）。
     * 与 [MEM_AFFIXES] 分列：编队词缀全队挂载，旗舰词缀仅旗舰挂载，词缀面板分区展示。
     */
    const val MEM_FLAGSHIP_AFFIXES: String = "\$astd_bounty_flagship_affixes"

    /**
     * Fleet memory：该 fleet 关联的 bountyKey（便于交互/调试）。
     */
    const val MEM_BOUNTY_KEY: String = "\$astd_bounty_key"

    /**
     * Fleet memory：战斗结束后在接触对话框里显示的“任务完成文案”。
     */
    const val MEM_SUCCESS_TEXT: String = "\$astd_bounty_success_text"

    /**
     * Fleet memory：避免同一场遭遇战多次输出 success 文案。
     */
    const val MEM_SUCCESS_SHOWN: String = "\$astd_bounty_success_shown"

    /**
     * Sector memory 前缀：主线工单已核销回执标记（`$astd_main_settled_<bountyKey>`）。
     */
    const val MEM_SETTLED_PREFIX: String = "\$astd_main_settled_"

    /**
     * Sector memory 前缀：主线工单已击毁待核销标记（`$astd_main_destroyed_<bountyKey>`）。
     *
     * 与 MagicLib 的 `$<bountyKey>`（job_memKey）分工：后者由 MagicLib 独占读写
     * （接受时置 false、任意终态——含失败——置 true），语义是「该 bounty 已终态」；
     * 本模组的内容 gating 一律使用本键与 [MEM_SETTLED_PREFIX]，不读 `$<bountyKey>`，
     * 避免失败终态被误当作「已完成」。
     */
    const val MEM_DESTROYED_PREFIX: String = "\$astd_main_destroyed_"

    /**
     * Sector memory：最近一次结算后的清算序列进度读数（Float，供后续内容 gating 与 UI 消费）。
     */
    const val MEM_LIQUIDATION_PROGRESS: String = "\$astd_main_liquidation_progress"

    /**
     * Sector memory 前缀：章末钩子标记（`$astd_main_hook_<hookId>`），
     * hookId 见 MainlineProgression.HOOK_*。
     */
    const val MEM_HOOK_PREFIX: String = "\$astd_main_hook_"

    /**
     * Sector memory：四章末归档挂起标记（档案处置申请待签署，→ 第五章）。
     */
    const val MEM_ARCHIVAL_PENDING: String = "\$astd_main_archival_pending"
}
