package cn.kasuminova.astd.campaign.bounty

import cn.kasuminova.astd.campaign.world.StoryWorldIds

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
     * 赏金文案的 I18n category（对应 contents/data/strings/bounty_strings.json）。
     */
    const val I18N_CATEGORY: String = "asteria_directorate_bounty"

    /**
     * Sector persistentData key：保存主线进度、解锁词缀池、已处理的 bounty 状态等。
     */
    const val PERSISTENT_STATE_KEY: String = "astd_bounty_state"

    /**
     * Sector memory key：仅用于避免重复 addScript（不写入存档也可以，但用 memory 足够）。
     */
    const val MEMORY_MANAGER_ADDED: String = "\$astd_bounty_manager_added"

    /**
     * 分局空间站（剧情主星系）的 MagicBounty trigger_market_id 列表，
     * 主线第一章及以后的工单仅在该市场的赏金板出现。
     *
     * 同时包含市场 id 与实体 id：MagicCampaign.isAvailableAtMarket 先按市场 id 匹配放行，
     * 再以「id 对应实体存在但市场 id 不匹配」兜底拒绝其余市场——剧情世界生成的市场 id
     * 规范为 `astd_market_<实体ID>`（[StoryWorldIds.marketIdFor]），仅传市场 id 时
     * 其余市场会因实体不存在而 fall-through 成全市场可挂，故实体 id 必须一并传入。
     *
     * 注意：空间站实体尚未生成时（如序章星系生成未跑）两段检查都会落空，
     * 工单仍会全市场可挂，由 memKey 出现门槛兜底。
     */
    val STATION_TRIGGER_MARKET_IDS: List<String> = listOf(
        StoryWorldIds.marketIdFor(StoryWorldIds.MAIN_STATION_BRANCH),
        StoryWorldIds.MAIN_STATION_BRANCH,
    )

    /**
     * Sector memory：序章代办对话已递出首份赏金文书（由酒馆对话侧写入），序章工单以此为出现门槛。
     */
    const val MEM_PROLOGUE_DOC_RECEIVED: String = "\$astd_prologue_doc_received"

    /**
     * Sector memory：第四章结清后「档案处置申请」已受理，等待玩家前往分局空间站终端签署（第五章入口）。
     */
    const val MEM_ARCHIVE_PENDING: String = "\$astd_archive_pending"

    /**
     * Sector memory：归档三选结果（公开/封存/交易），由终端签署流程写入。
     */
    const val MEM_ARCHIVE_CHOICE: String = "\$astd_archive_choice"

    /**
     * Sector memory：交易选的定向移交对象势力 id（其余势力可据此做关系/强度变动）。
     */
    const val MEM_ARCHIVE_TRADE_FACTION: String = "\$astd_archive_trade_faction"

    /**
     * Sector memory：归档完成后玩家被认证为「无限期承包商」，随机无限赏金解锁。
     */
    const val MEM_INFINITE_CONTRACTOR: String = "\$astd_infinite_contractor"

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
     * - 主线在“接受”时由 [BountyCampaignManager] 按 def 从 i18n 表读取核销回执批注写入
     * - 其它动态 bounty 可在接受时写入，避免额外建表
     */
    const val MEM_SUCCESS_TEXT: String = "\$astd_bounty_success_text"

    /**
     * Fleet memory：避免同一场遭遇战多次输出 success 文案。
     */
    const val MEM_SUCCESS_SHOWN: String = "\$astd_bounty_success_shown"

    /**
     * Sector memory key：仅用于避免重复 addScript 剧情运行时脚本（序章酒馆事件注册/执行官签发重试等）。
     */
    const val MEMORY_STORY_RUNTIME_ADDED: String = "\$astd_story_runtime_added"
}
