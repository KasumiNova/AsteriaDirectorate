package cn.kasuminova.astd.sscsv.entries

import cn.kasuminova.astd.sscsv.CsvTarget
import cn.kasuminova.astd.sscsv.SsCsvEntry

/**
 * Starsector `data/hullmods/hull_mods.csv` 的数据结构。
 *
 * 添加新的 HullMod：创建一个 Kotlin `object : HullModEntry()` 并覆写需要的字段。
 */
abstract class HullModEntry : SsCsvEntry {
    final override val target: CsvTarget = CsvTarget.HULL_MODS

    /** HullMod 的唯一 id（也用于脚本/存档引用）。 */
    abstract val id: String

    /** 在游戏内 UI 中显示的名称（通常是本地化后的字符串）。 */
    abstract val name: String

    /** 分级/强度等级（原 CSV 字段：tier）。 */
    open val tier: Int = 1

    /** 稀有度（原 CSV 字段：rarity）。 */
    open val rarity: Int = 1

    /** 科技/制造商分类（原 CSV 字段：tech/manufacturer）。 */
    open val tech: String = ""

    /** 逻辑标签（原 CSV 字段：tags）。一般用来分类、检索或驱动特定规则。 */
    open val tags: String = ""

    /** UI 展示标签（原 CSV 字段：uiTags）。用于在改装界面显示分类标签等。 */
    open val uiTags: String = ""

    /** 基础价值（原 CSV 字段：base value）。通常影响市场价格/估值。 */
    open val baseValue: Int = 0

    /** 是否默认解锁（原 CSV 字段：unlocked，TRUE/FALSE）。 */
    open val unlocked: Boolean = false

    /** 是否隐藏（原 CSV 字段：hidden，TRUE/FALSE）。 */
    open val hidden: Boolean = false

    /** 是否在所有场景中隐藏（原 CSV 字段：hiddenEverywhere，TRUE/FALSE）。 */
    open val hiddenEverywhere: Boolean = false

    /** 安装 OP 消耗（护卫舰）。 */
    open val costFrigate: Int = 0

    /** 安装 OP 消耗（驱逐舰）。 */
    open val costDestroyer: Int = 0

    /** 安装 OP 消耗（巡洋舰）。 */
    open val costCruiser: Int = 0

    /** 安装 OP 消耗（主力舰）。 */
    open val costCapital: Int = 0

    /** 完整限定名的脚本类；纯数据 HullMod 可留空。 */
    open val script: String = ""

    /** 详细描述（原 CSV 字段：desc）。通常对应 tooltip 的正文。 */
    open val desc: String = ""

    /** 简短描述（原 CSV 字段：short）。通常对应 tooltip 的标题/摘要。 */
    open val short: String = ""

    /** S-Mod 描述（原 CSV 字段：sModDesc）。为空时通常使用默认逻辑。 */
    open val sModDesc: String = ""

    /** 图标 sprite 路径（原 CSV 字段：sprite）。 */
    open val sprite: String = ""

    final override val key: String get() = id

    final override fun toRow(): Map<String, Any?> = linkedMapOf(
        "name" to name,
        "id" to id,
        "tier" to tier,
        "rarity" to rarity,
        "tech/manufacturer" to tech,
        "tags" to tags,
        "uiTags" to uiTags,
        "base value" to baseValue,
        "unlocked" to if (unlocked) "TRUE" else "FALSE",
        "hidden" to if (hidden) "TRUE" else "FALSE",
        "hiddenEverywhere" to if (hiddenEverywhere) "TRUE" else "FALSE",
        "cost_frigate" to costFrigate,
        "cost_dest" to costDestroyer,
        "cost_cruiser" to costCruiser,
        "cost_capital" to costCapital,
        "script" to script,
        "desc" to desc,
        "short" to short,
        "sModDesc" to sModDesc,
        "sprite" to sprite,
    )
}
