package cn.kasuminova.astd.campaign.bounty

/**
 * 仅包含“动态生成所需”的主线定义（与 docs/story 的长文案分离）。
 */
data class BountyDef(
    val key: String,
    val title: String,
    val shortDesc: String,
    val threatTier: Int,
    val baselineFP: Int,
    val flagshipVariantId: String,
    val requiredPreviousMainKey: String? = null,
    val isMain: Boolean = true,
    /**
     * 该赏金的“固定核心编成”（不含旗舰也可以包含旗舰；推荐只放护航）。
     *
     * 用途：在难度缩放/词缀影响下，舰队规模会变化，但仍保留剧情/设计指定的基础阵容。
     */
    val coreVariantIds: List<String> = emptyList(),
    /**
     * R 型词缀开关（affixes.md v3：仅第三章赏金与结局后的无限赏金允许 R 型出现）。
     * 不由主线进度解锁；新赏金内容接入时按章节显式置 true。
     * 后续阶段的无限赏金生成器接入时同样必须显式传 allowRAffixes（默认 false 即动态赏金无 R）。
     */
    val allowRAffixes: Boolean = false,
    /**
     * 是否抽取词缀（序章与第一章批一不挂词缀；false 时完全跳过词缀抽取）。
     */
    val allowAffixes: Boolean = true,
    /**
     * 固定至少携带的 R 型词缀数量（三章单 3 = 1；抽取落空时按种子强制补足）。
     * 仅在随机抽取路径生效；[fixedAffixIds] 非空时由固定表自身保证 R 型数量。
     */
    val minRAffixes: Int = 0,
    /**
     * 旗舰专属词缀 id（编队词缀之外仅施加于旗舰，如四章中军旗舰的 R-17 奇点驱动）。
     */
    val flagshipAffixIds: List<String> = emptyList(),
    /**
     * 固定词缀表（编队词缀 id 列表）。非空时跳过随机抽取，整支编队按文书追加条款
     * 具名的编目号挂载（docs/story 06/08/10/12 口径）；null 时走 [cn.kasuminova.astd.combat.affix.AffixRegistry.pickAffixes] 随机抽取
     * （动态赏金与未来无限赏金路径）。合法性规则与随机抽取一致（互斥/相位约束），
     * 见 [cn.kasuminova.astd.combat.affix.AffixRegistry.validateFixedTable]。
     */
    val fixedAffixIds: List<String>? = null,
)
