package cn.kasuminova.astd.campaign.story

import com.fs.starfarer.api.Global

/**
 * 第五章归档终局的统一常量。
 */
object EndingKeys {

    /** 终局文案的 I18n category（对应 contents/data/strings/ending_strings.json）。 */
    const val I18N_CATEGORY: String = "asteria_directorate_ending"

    /** Sector persistentData key：保存归档后果结算进度与「执行官」签发状态。 */
    const val PERSISTENT_STATE_KEY: String = "astd_ending_state"

    /** Sector memory：仅用于避免重复 addScript 终局运行时脚本。 */
    const val MEMORY_ENDING_RUNTIME_ADDED: String = "\$astd_ending_runtime_added"

    /** Sector memory：「执行官」已签发的特化类型（combat/admin），供终端 UI 读取 gating。 */
    const val MEM_EXECUTIVE_CORE_TYPE: String = "\$astd_executive_core_type"

    /**
     * 周期长度裁定（天）：13 文档「延迟若干周期」未定周期长度，实装裁定为 1 周期 = 30 星区天。
     * 封存延迟 2 周期 = 60 天，交易其余势力延迟 1 周期 = 30 天。
     */
    const val CYCLE_DAYS: Float = 30f

    /** 归档势力增幅的市场修正来源 id（舰队规模/可达性/地面防御三通道共用）。 */
    const val BOOST_MOD_ID: String = "astd_archive_boost"

    /** 「执行官」战斗特化的指挥舰修正来源 id。 */
    const val COMMAND_MOD_ID: String = "astd_executive_command"

    /** 「执行官」行政特化的殖民地修正来源 id。 */
    const val ADMIN_MOD_ID: String = "astd_executive_admin"

    /** 「执行官」特化类型：战斗特化（选舰远程指挥）。 */
    const val CORE_TYPE_COMBAT: String = "combat"

    /** 「执行官」特化类型：行政特化（玩家殖民地被动）。 */
    const val CORE_TYPE_ADMIN: String = "admin"
}

/**
 * 存档持久化的终局状态（第五章归档后果 + 「执行官」签发）。
 *
 * 与 [cn.kasuminova.astd.campaign.bounty.BountyState] 相同的 XStream 兼容约定：
 * 可序列化普通字段 + 无参构造；新增字段必须带默认值。
 *
 * 增幅结算的“是否已生效”以本状态为唯一事实来源；市场修正本身每 tick 幂等重挂，
 * 因此重复进入归档流程或读档均不会叠乘。
 */
class EndingState() {

    /** 立即生效档是否已激活（公开全体 / 交易对象）。激活后由运行时脚本持续挂载市场修正。 */
    @JvmField
    var archiveImmediateApplied: Boolean = false

    /** 延迟档到期时间戳（[com.fs.starfarer.api.campaign.CampaignClockAPI.getTimestamp] 口径；-1 = 无延迟档）。 */
    @JvmField
    var archiveDelayedDueTimestamp: Long = -1L

    /** 延迟档是否已到期激活。 */
    @JvmField
    var archiveDelayedApplied: Boolean = false

    /**
     * 「执行官」已签发的特化类型：""=未签发（待玩家在终端选择）；
     * [EndingKeys.CORE_TYPE_COMBAT] / [EndingKeys.CORE_TYPE_ADMIN]。
     * 一经签发不可更改（公文口径：「一经签发，不予退换」）。
     */
    @JvmField
    var executiveCoreType: String = ""

    /**
     * 战斗特化的指挥舰 member id；空串 = 未显式指定，回落到玩家旗舰。
     * 目标舰船离队/灭失时同样回落旗舰，不产生错误状态。
     */
    @JvmField
    var commandShipId: String = ""

    companion object {
        @JvmStatic
        fun getOrCreate(): EndingState {
            val sector = Global.getSector() ?: return EndingState()
            val pd = sector.persistentData
            val existing = pd[EndingKeys.PERSISTENT_STATE_KEY]
            if (existing is EndingState) return existing
            val created = EndingState()
            pd[EndingKeys.PERSISTENT_STATE_KEY] = created
            return created
        }
    }
}
