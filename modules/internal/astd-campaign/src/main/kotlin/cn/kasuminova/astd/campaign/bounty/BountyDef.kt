package cn.kasuminova.astd.campaign.bounty

/**
 * 主线赏金定义（运行时模型）：只承载生成/结算所需数据，长文案在 bounty_strings.json
 * （键名约定 `main.<key>.name` / `main.<key>.desc` / `main.<key>.receipt` / `main.<key>.fleet_name`）。
 *
 * 数值口径见 docs/story 02（FP 全局算法）与各章细化文档（05/07/09/11）。
 *
 * @property key MagicBounty 注册键，同时是结算 memKey（`$<key>`）
 * @property chapter 章节序号：0=序章，1..4=第一~四章（第五章无战斗，走 gating）
 * @property groupId 结清组 id（批次/线路/章），见 [MainBounties.groups]
 * @property dangerLevel 文书危险等级（1..6；第三章起开放六级）
 * @property dangerAbsent 危险等级栏留白（「等级从缺」，第四章 ZQ 类目专用）
 * @property baselineFP 预设 FP（走 02 全局算法：× k_s × 玩家超模系数(≤2) × 舰队大小系数）
 * @property flagshipVariantId 目标旗舰 variant id
 * @property fleetFactionId 目标舰队势力（遗存/自动化目标用 remnant，常规侵占目标用 pirates 等）
 * @property requiredMemKeys 出现门槛（trigger_memKeys_all，含 `$` 前缀）
 * @property affixRule 词缀搭配规则（affixes.md v3：S 2~4 + M 1~2，R 型按章解禁）
 * @property rewardMin/rewardMax 单票星币报酬区间（× k_s 缩放）
 * @property liquidationDisplay 结清后回执打印的清算序列进度（%），null = 本单不显示
 * @property flagshipDMods 旗舰随机 D-mod 数量（两百年失修的直观表达，序章用）
 * @property coreVariantIds 固定核心编成（不含旗舰；其余由缩放模型补齐）
 */
data class BountyDef(
    val key: String,
    val chapter: Int,
    val groupId: String,
    val dangerLevel: Int,
    val baselineFP: Int,
    val flagshipVariantId: String,
    val fleetFactionId: String,
    val dangerAbsent: Boolean = false,
    val requiredMemKeys: List<String> = emptyList(),
    val affixRule: AffixRule = AffixRule.NONE,
    val rewardMin: Int = 0,
    val rewardMax: Int = 0,
    val liquidationDisplay: Float? = null,
    val flagshipDMods: Int = 0,
    val coreVariantIds: List<String> = emptyList(),
    /**
     * 目标池全部切换为模组舰船（总局遗存/战斗群编成，第三章起口径；第二章遗址防御同）：
     * 编队填充只用本模组舰船池，不混入常规势力预设。
     */
    val modOnlyComposition: Boolean = false,
    /**
     * 文书编号骑缝（如 XW-c206-0447／核销-03）：工单终端/流水账展示用，
     * 与 i18n 的 `main.<key>.name` 前缀保持一致。
     */
    val code: String = "",
    /** 文书「追加条款」条数（条款正文在 `main.<key>.clause.N`；0 = 本单无追加条款栏）。 */
    val clauseCount: Int = 0,
)

/**
 * 词缀搭配规则（affixes.md v3，D20 定案：数量完全由难度系数搭配表决定，章节挂钩表作废）。
 *
 * S/M 型数量由固有难度归一化系数 k = (k_s - 1) / 4 在区间内线性取档：
 * S 型 2~4、M 型 1~2；R 型仅第三章赏金与结局后无限赏金开放，按 [rMin]..[rMax] 取档。
 *
 * @property enabled 词缀是否介入（序章与第一章批次一不介入）
 * @property rMin/rMax R 型数量区间；rMax = 0 表示本单不出现 R 型
 */
data class AffixRule(
    val enabled: Boolean,
    val rMin: Int = 0,
    val rMax: Int = 0,
) {
    /** 由固有难度归一化值 k 派生实际词缀数量（S/M 取档口径与 AffixRegistry 一致）。 */
    fun counts(k: Float): AffixCounts {
        if (!enabled) return AffixCounts(0, 0, 0)
        val kk = k.coerceIn(0f, 1f)
        val s = (2 + kk * 2f).toInt().coerceIn(2, 4)
        val m = (1 + kk).toInt().coerceIn(1, 2)
        val r = if (rMax <= 0) 0 else (rMin + (kk * (rMax - rMin).toFloat()).toInt()).coerceIn(0, rMax)
        return AffixCounts(s, m, r)
    }

    companion object {
        /** 词缀不介入（序章 / 第一章批次一）。 */
        val NONE = AffixRule(enabled = false)

        /** 标准搭配：S 2~4 + M 1~2，无 R（第一章批次二起至第三章单 2）。 */
        val STANDARD = AffixRule(enabled = true)

        /** 标准搭配 + R 型 rMin..rMax（第三章单 3 起 / 第四章 / 无限赏金）。 */
        fun withR(rMin: Int, rMax: Int): AffixRule = AffixRule(enabled = true, rMin = rMin, rMax = rMax)
    }
}

/** 一单实际抽取的词缀数量。 */
data class AffixCounts(val s: Int, val m: Int, val r: Int) {
    val total: Int get() = s + m + r
}
