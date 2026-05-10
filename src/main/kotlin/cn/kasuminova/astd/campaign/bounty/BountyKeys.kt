package cn.kasuminova.astd.campaign.bounty

/**
 * 赏金系统（Asteria 线）统一常量。
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
     * Fleet memory：该 fleet 关联的 bountyKey（便于交互/调试）。
     */
    const val MEM_BOUNTY_KEY: String = "\$astd_bounty_key"

    /**
     * Fleet memory：战斗结束后在接触对话框里显示的“任务完成文案”。
     *
    * - 主线通常可不写（由 [BountyFidConfigGen] 从 i18n 表读取）
     * - 支线/动态 bounty 可在接受时写入，避免额外建表
     */
    const val MEM_SUCCESS_TEXT: String = "\$astd_bounty_success_text"

    /**
     * Fleet memory：避免同一场遭遇战多次输出 success 文案。
     */
    const val MEM_SUCCESS_SHOWN: String = "\$astd_bounty_success_shown"
}
