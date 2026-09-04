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
)
