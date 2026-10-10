package cn.kasuminova.astd.ui.dsl

import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.internal.i18n.I18nUi
import com.fs.starfarer.api.ui.Alignment
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.util.Misc
import java.awt.Color

/**
 * Hullmod Tooltip 卡片 DSL——本模组全部船插 tooltip 的统一渲染风格与路径。
 *
 * 设计同构于 Dialog DSL（`dialogGraph { node(...) }`）：DSL 块先构建出数据声明
 * （[HullmodTooltipSpec]），再由统一渲染器落地到 [TooltipMakerAPI]；
 * 骨架（顶部间距 + 全息背景 + 标题）只存在于 [hullmodCard] 一处。
 *
 * 用法：`addPostDescriptionSection` 内联声明卡片，**每次调用现场构建**——
 * 刻意的每帧构建便于热重载调整后即时观察文案内容：
 * ```kotlin
 * override fun addPostDescriptionSection(tooltip: TooltipMakerAPI, hullSize: ShipAPI.HullSize?, ship: ShipAPI?, width: Float, isForModSpec: Boolean) {
 *     tooltip.hullmodCard(width, HullmodThemes.ARC, spec?.displayName) {
 *         para("ui.hullmod.example.summary")
 *         heading("ui.hullmod.export.section.effect")
 *         table {
 *             row("ui.hullmod.example.attr.rate", "ui.hullmod.example.value.rate")
 *             row("ui.hullmod.example.attr.penalty", "ui.hullmod.example.value.penalty", tone = HullmodTone.RED)
 *         }
 *         para("ui.hullmod.example.note", hl("50%", HullmodTone.HIGHLIGHT))
 *         para("ui.hullmod.example.runtime", v("range", range), v("pct", percent(ratio)))
 *     }
 * }
 * ```
 * 标题栏由原版描述承接时（不渲染卡片标题），title 传 `null`。
 */

// ========== 主题 ==========

/** 语义配色角色：段落高亮与表格单元格不直接写字面色，由 [HullmodTheme] 统一解析。 */
enum class HullmodTone {
    /** 默认色（段落正文 / 表格标签列 / 标题列各自的后备色）。 */
    DEFAULT,

    /** 高亮色：注意点/特殊数值。 */
    HIGHLIGHT,

    /** 增益绿：正面收益。 */
    POSITIVE,

    /** 强调橙：次级警示/特殊机制。 */
    ORANGE,

    RED,
}

/**
 * Hullmod tooltip 主题：标题/分节/背景强调色与语义三色。
 *
 * 注：等离子装甲护盾与原 ARC_PRISM 变体的 nameColor 微差（150,232,255 / 160,236,255）合并为后者。
 */
data class HullmodTheme(
    /** 标题与表格值列的主色。 */
    val nameColor: Color,
    /** 装配界面船插条目边框色（各 hullmod 的 `getBorderColor()` 统一读取此字段）。 */
    val borderColor: Color,
    /** 卡片标题栏底色。 */
    val headerBackground: Color,
    /** 分节标题栏底色。 */
    val sectionBackground: Color,
    /** 全息背景强调色（角标/线条基调，见 [cn.kasuminova.astd.ui.effect.ASTDHullModTooltipBackground]）。 */
    val accentColor: Color,
    val warningColor: Color = Color(255, 224, 36),
    val positiveColor: Color = Color(96, 224, 126),
    val orangeColor: Color = Color(255, 148, 42),
    /**
     * 警示红：负面效果/惩罚条目。缺省 `null` 表示在使用处动态解析
     * `Misc.getNegativeHighlightColor()`（追随原版色盲模式与玩家色配；
     * 不能在构造期求值，否则单测环境无 `Global.getSettings()` 会 NPE）。
     */
    val redColor: Color? = null,
) {
    /** 把语义角色解析为主题色；[HullmodTone.DEFAULT] 回退 [fallback]。 */
    fun colorFor(tone: HullmodTone, fallback: Color): Color = when (tone) {
        HullmodTone.DEFAULT -> fallback
        HullmodTone.HIGHLIGHT -> Misc.getHighlightColor()
        HullmodTone.POSITIVE -> Misc.getPositiveHighlightColor()
        HullmodTone.ORANGE -> orangeColor
        HullmodTone.RED -> redColor ?: Misc.getNegativeHighlightColor()
    }
}

/** 内置主题预设：覆盖全模组船插的既有配色（迁移自各 hullmod 内联 Theme，逐色保持一致）。 */
object HullmodThemes {
    /** 弧光线标准蓝（烽燧/缙云等 ARC 量产内置）。 */
    val ARC = HullmodTheme(
        nameColor = Color(150, 232, 255),
        borderColor = Color(90, 180, 255, 150),
        headerBackground = Color(20, 52, 82, 180),
        sectionBackground = Color(14, 36, 58, 120),
        accentColor = Color(60, 140, 220),
    )

    /** 弧光线亮蓝（XC-101 内置船插：等离子装甲护盾/离子化反冲蓄能器）。 */
    val ARC_PRISM = HullmodTheme(
        nameColor = Color(160, 236, 255),
        borderColor = Color(160, 110, 255, 150),
        headerBackground = Color(20, 52, 82, 190),
        sectionBackground = Color(14, 36, 58, 135),
        accentColor = Color(88, 190, 255),
    )

    /** 透镜线紫（LENS 量产与透镜协议旗舰内置）。 */
    val LENS = HullmodTheme(
        nameColor = Color(200, 160, 255),
        borderColor = Color(160, 110, 255, 150),
        headerBackground = Color(40, 18, 70, 185),
        sectionBackground = Color(28, 12, 52, 120),
        accentColor = Color(150, 90, 230),
    )

    /** 有人模式淡蓝（双模式切换器/有人模式）。 */
    val CREWED = HullmodTheme(
        nameColor = Color(168, 190, 230),
        borderColor = Color(120, 150, 200, 150),
        headerBackground = Color(24, 34, 56, 185),
        sectionBackground = Color(20, 28, 46, 120),
        accentColor = Color(143, 182, 255),
    )

    /** 自动模式橙。 */
    val AUTOMATED = HullmodTheme(
        nameColor = Color(255, 196, 150),
        borderColor = Color(220, 150, 90, 150),
        headerBackground = Color(66, 40, 18, 185),
        sectionBackground = Color(48, 28, 14, 120),
        accentColor = Color(230, 170, 100),
    )

    /** 纳米重构协议绿。 */
    val NANO = HullmodTheme(
        nameColor = Color(170, 255, 196),
        borderColor = Color(88, 212, 140, 150),
        headerBackground = Color(18, 68, 44, 180),
        sectionBackground = Color(12, 46, 30, 120),
        accentColor = Color(60, 180, 100),
    )

    /** 虚数之翼紫罗兰（XC-002 星翼内置）。 */
    val IMAGINARY = HullmodTheme(
        nameColor = Color(216, 178, 255),
        borderColor = Color(170, 110, 255, 150),
        headerBackground = Color(46, 24, 82, 180),
        sectionBackground = Color(32, 16, 58, 120),
        accentColor = Color(130, 80, 220),
    )
}

// ========== 卡片内容块（数据声明） ==========

/** 段落行内参数：静态高亮（[hl]）或运行期命名变量（[v]），可在同一段落混排。 */
sealed interface ParaArg

/**
 * 静态高亮：把 i18n 文本中已烘焙的 [value] 片段着色。
 * 颜色二选一：显式 [color]，或按 [tone] 由主题解析；两者都缺省时按 HIGHLIGHT 色处理。
 */
internal class StaticHighlight(
    val value: String,
    val color: Color?,
    val tone: HullmodTone,
) : ParaArg

/** 运行期命名变量：对应 i18n 文本中的 `%name%` / `<param:#RRGGBB:name>` 标记（见 [I18n.tr]）。 */
internal class RuntimeVar(val name: String, val value: Any?) : ParaArg

/** 卡片内容块：DSL 构建的数据节点（仅 [HullmodCardRenderer] 消费）。 */
internal sealed interface HullmodBlock

/** 段落块：i18n key + 行内参数（静态高亮/运行期变量）；[baseColor] 缺省为 `Misc.getTextColor()`。 */
internal class ParaBlock(
    val key: String,
    val padTop: Float,
    val args: List<ParaArg>,
    val baseColor: Color? = null,
) : HullmodBlock

/**
 * 直写段落块：与 [ParaBlock] 的唯一区别是不读取 I18n 键，[text] 即最终模板文本，
 * 直接对内联模板做变量/高亮格式化（`%name%` / `<param:#RRGGBB:name>` / `%%`），
 * 便于游戏内热重载调整文案。
 *
 * 仅开发阶段临时使用：发布场景禁止出现，定稿文案必须迁回 strings.json 并改用 [ParaBlock]。
 */
internal class PlainTextBlock(
    val text: String,
    val padTop: Float,
    val args: List<ParaArg>,
    val baseColor: Color? = null,
) : HullmodBlock

/** 分节标题块（i18n key，主题 sectionBackground 底色的通栏标题）。 */
internal class HeadingBlock(
    val key: String,
    val padTop: Float,
    /** true 时 [key] 按内联文本直写（开发调文案用），false 时按 i18n key 查表。 */
    val plain: Boolean = false,
) : HullmodBlock

/**
 * 双列表格行（i18n key；[labelTone]/[valueTone] 语义配色）。
 * [plain] 为 true 时 label/value 按内联文本直写（开发调文案用）。
 */
internal class TableRow(
    val labelKey: String,
    val valueKey: String,
    val labelTone: HullmodTone = HullmodTone.DEFAULT,
    val valueTone: HullmodTone = HullmodTone.DEFAULT,
    val plain: Boolean = false,
)

/** 双列表格块（62/38 分栏、行高 24f、表头默认 属性/效果；[plain] 语义同 [HeadingBlock.plain]，仅作用于表头）。 */
internal class TableBlock(
    val headerAKey: String,
    val headerBKey: String,
    val rows: List<TableRow>,
    val padTop: Float,
    val plain: Boolean = false,
) : HullmodBlock

/** 垂直间距块（仅特殊排版需要；常规段落间距走各块 padTop）。 */
internal class SpacerBlock(val height: Float) : HullmodBlock

/**
 * 一张 hullmod tooltip 卡片的完整声明（DSL 构建产物，纯数据）。
 *
 * @property blocks 卡片内容块（声明序即渲染序）。
 */
internal class HullmodTooltipSpec(
    val blocks: List<HullmodBlock>,
)

// ========== DSL 构建器 ==========

/** Hullmod 卡片 DSL 构建器：收集内容块为数据声明，渲染由 [hullmodCard] 统一执行。 */
@TooltipDsl
class HullmodCardBuilder {
    internal val blocks = mutableListOf<HullmodBlock>()

    /** 段落（i18n key；可混排静态高亮 [hl] 与运行期变量 [v]）。 */
    fun para(key: String, padTop: Float = 8f, vararg args: ParaArg) {
        blocks += ParaBlock(key, padTop, args.toList())
    }

    /** 段落（默认间距、仅行内参数版本）。 */
    fun para(key: String, vararg args: ParaArg) {
        blocks += ParaBlock(key, 8f, args.toList())
    }

    /** 段落（显式正文色版本；仅既有排版需要非默认正文色时使用）。 */
    fun para(key: String, color: Color, padTop: Float = 8f, vararg args: ParaArg) {
        blocks += ParaBlock(key, padTop, args.toList(), color)
    }

    /**
     * 直写段落（不读 I18n 键，文本内联便于热重载调文案；可混排静态高亮 [hl] 与运行期变量 [v]）。
     *
     * 仅开发阶段临时使用：发布场景禁止调用，定稿文案必须迁回 strings.json 并改用 [para]。
     */
    fun plainText(text: String, padTop: Float = 8f, vararg args: ParaArg) {
        blocks += PlainTextBlock(text, padTop, args.toList())
    }

    /**
     * 直写段落（默认间距、仅行内参数版本）。
     *
     * 仅开发阶段临时使用：发布场景禁止调用，定稿文案必须迁回 strings.json 并改用 [para]。
     */
    fun plainText(text: String, vararg args: ParaArg) {
        blocks += PlainTextBlock(text, 8f, args.toList())
    }

    /**
     * 直写段落（显式正文色版本）。
     *
     * 仅开发阶段临时使用：发布场景禁止调用，定稿文案必须迁回 strings.json 并改用 [para]。
     */
    fun plainText(text: String, color: Color, padTop: Float = 8f, vararg args: ParaArg) {
        blocks += PlainTextBlock(text, padTop, args.toList(), color)
    }

    /** 分节标题（i18n key）。 */
    fun heading(key: String, padTop: Float = 12f) {
        blocks += HeadingBlock(key, padTop)
    }

    /**
     * 直写分节标题（不读 I18n 键，文本内联）。
     *
     * 仅开发阶段临时使用：发布场景禁止调用，定稿文案必须迁回 strings.json 并改用 [heading]。
     */
    fun headingPlain(text: String, padTop: Float = 12f) {
        blocks += HeadingBlock(text, padTop, plain = true)
    }

    /** 双列表格（i18n key 行；表头默认 `ui.hullmod.table.attribute` / `ui.hullmod.table.effect`）。 */
    fun table(
        headerAKey: String = HEADER_A_KEY,
        headerBKey: String = HEADER_B_KEY,
        padTop: Float = 8f,
        block: HullmodTableBuilder.() -> Unit,
    ) {
        val builder = HullmodTableBuilder()
        builder.block()
        blocks += TableBlock(headerAKey, headerBKey, builder.rows.toList(), padTop)
    }

    /**
     * 直写双列表格（表头不读 I18n 键；行内文本用 [HullmodTableBuilder.rowPlain]）。
     *
     * 仅开发阶段临时使用：发布场景禁止调用，定稿文案必须迁回 strings.json 并改用 [table]。
     */
    fun tablePlain(
        headerA: String,
        headerB: String,
        padTop: Float = 8f,
        block: HullmodTableBuilder.() -> Unit,
    ) {
        val builder = HullmodTableBuilder()
        builder.block()
        blocks += TableBlock(headerA, headerB, builder.rows.toList(), padTop, plain = true)
    }

    /** 垂直间距（仅特殊排版需要；常规段落间距走各块 padTop）。 */
    fun spacer(height: Float) {
        blocks += SpacerBlock(height)
    }

    /** 静态高亮（按语义角色取主题色）。 */
    fun hl(value: String, tone: HullmodTone): ParaArg = StaticHighlight(value, null, tone)

    /** 静态高亮（显式颜色）。 */
    fun hl(value: String, color: Color): ParaArg = StaticHighlight(value, color, HullmodTone.DEFAULT)

    /** 运行期命名变量（对应 i18n 文本的 `%name%` / `<param:...>` 标记）。 */
    fun v(name: String, value: Any?): ParaArg = RuntimeVar(name, value)

    companion object {
        /** 表格默认表头（左列：属性）。 */
        const val HEADER_A_KEY = "ui.hullmod.table.attribute"

        /** 表格默认表头（右列：效果）。 */
        const val HEADER_B_KEY = "ui.hullmod.table.effect"
    }
}

/** 表格行构建器（见 [HullmodCardBuilder.table]）。 */
@TooltipDsl
class HullmodTableBuilder {
    internal val rows = mutableListOf<TableRow>()

    /** 一行（标签列 + 值列同一语义色）。 */
    fun row(labelKey: String, valueKey: String, tone: HullmodTone = HullmodTone.DEFAULT) {
        rows += TableRow(labelKey, valueKey, tone, tone)
    }

    /** 一行（标签列与值列分别指定语义色）。 */
    fun row(labelKey: String, valueKey: String, labelTone: HullmodTone, valueTone: HullmodTone) {
        rows += TableRow(labelKey, valueKey, labelTone, valueTone)
    }

    /**
     * 直写一行（标签/值不读 I18n 键，文本内联；标签列 + 值列同一语义色）。
     *
     * 仅开发阶段临时使用：发布场景禁止调用，定稿文案必须迁回 strings.json 并改用 [row]。
     */
    fun rowPlain(label: String, value: String, tone: HullmodTone = HullmodTone.DEFAULT) {
        rows += TableRow(label, value, tone, tone, plain = true)
    }

    /**
     * 直写一行（标签列与值列分别指定语义色）。
     *
     * 仅开发阶段临时使用：发布场景禁止调用，定稿文案必须迁回 strings.json 并改用 [row]。
     */
    fun rowPlain(label: String, value: String, labelTone: HullmodTone, valueTone: HullmodTone) {
        rows += TableRow(label, value, labelTone, valueTone, plain = true)
    }
}

/** 构建一张 hullmod tooltip 卡片的数据声明（[hullmodCard] 的内部中间产物）。 */
internal fun hullmodTooltip(block: HullmodCardBuilder.() -> Unit): HullmodTooltipSpec {
    val builder = HullmodCardBuilder()
    builder.block()
    return HullmodTooltipSpec(builder.blocks.toList())
}

// ========== 渲染入口 ==========

/**
 * 声明并渲染一张 hullmod 卡片：统一骨架（顶部间距 + 全息背景 + 可选标题栏）。
 * [title] 一般传 `spec?.displayName`；为空时跳过标题栏（标题由原版描述承接的卡片传 `null`）。
 */
fun TooltipMakerAPI.hullmodCard(
    width: Float,
    theme: HullmodTheme,
    title: String?,
    block: HullmodCardBuilder.() -> Unit,
) {
    HullmodCardRenderer.render(this, width, title, theme, hullmodTooltip(block))
}

// ========== 渲染器（唯一落地路径） ==========

/**
 * Hullmod 卡片的统一渲染器：数据声明 → TooltipMakerAPI 调用。
 * 表格几何（行高 24f、62/38 分栏、宽度 `max(width-20, 280)`）与多色高亮通路
 * （[I18nUi.addParaRendered]）全模组只此一份。
 */
internal object HullmodCardRenderer {

    private const val TABLE_ROW_HEIGHT = 24f

    fun render(
        tooltip: TooltipMakerAPI,
        width: Float,
        title: String?,
        theme: HullmodTheme,
        spec: HullmodTooltipSpec,
    ) {
        tooltip.buildWith {
            spacer(-20f) // 填充 Hullmod Tooltip 顶部空隙
            withHullmodBackground(accentColor = theme.accentColor, width = width) {
                spacer(6f) // 拉开空隙用于展示背景特效
                if (!title.isNullOrEmpty()) {
                    heading(title, theme.nameColor, theme.headerBackground, 6f)
                }
                for (block in spec.blocks) {
                    when (block) {
                        is ParaBlock -> renderPara(block, theme)
                        is PlainTextBlock -> renderPlainText(block, theme)
                        is HeadingBlock -> heading(
                            if (block.plain) block.key else I18n[I18n.Categories.MOD, block.key],
                            theme.nameColor,
                            theme.sectionBackground,
                            block.padTop,
                        )

                        is TableBlock -> renderTable(tooltip, block, theme, width)
                        is SpacerBlock -> spacer(block.height)
                    }
                }
                spacer(6f) // 拉开空隙用于展示背景特效
            }
        }
    }

    private fun TooltipBuilder.renderPara(block: ParaBlock, theme: HullmodTheme) {
        val baseColor = block.baseColor ?: Misc.getTextColor()
        val vars = block.args.filterIsInstance<RuntimeVar>()
        val statics = block.args.filterIsInstance<StaticHighlight>()
        if (vars.isEmpty() && statics.isEmpty()) {
            para(I18n.Categories.MOD, block.key, baseColor, block.padTop)
            return
        }
        val rendered = I18n.tr(
            I18n.Categories.MOD,
            block.key,
            *vars.map { it.name to it.value }.toTypedArray(),
        )
        addMergedPara(rendered, statics, theme, block.padTop, baseColor)
    }

    private fun TooltipBuilder.renderPlainText(block: PlainTextBlock, theme: HullmodTheme) {
        val baseColor = block.baseColor ?: Misc.getTextColor()
        val vars = block.args.filterIsInstance<RuntimeVar>()
        val statics = block.args.filterIsInstance<StaticHighlight>()
        val rendered = I18n.format(
            block.text,
            *vars.map { it.name to it.value }.toTypedArray(),
        )
        addMergedPara(rendered, statics, theme, block.padTop, baseColor)
    }

    private fun TooltipBuilder.addMergedPara(
        rendered: I18n.Rendered,
        statics: List<StaticHighlight>,
        theme: HullmodTheme,
        padTop: Float,
        baseColor: Color,
    ) {
        val staticHighlights = statics.map {
            I18n.Highlight(it.value, it.color ?: theme.colorFor(it.tone, theme.warningColor))
        }
        // I18nUi 的高亮匹配是顺序敏感的前向 indexOf，合并后必须按文本出现位置排序，
        // 否则位于运行期变量之前的静态高亮会匹配失败被丢弃。
        val merged = (rendered.highlights + staticHighlights)
            .sortedBy { rendered.text.indexOf(it.text).let { idx -> if (idx < 0) Int.MAX_VALUE else idx } }
        I18nUi.addParaRendered(
            tooltip,
            I18n.Rendered(rendered.text, merged),
            padTop,
            baseColor,
        )
    }

    private fun renderTable(
        tooltip: TooltipMakerAPI,
        table: TableBlock,
        theme: HullmodTheme,
        width: Float,
    ) {
        val tableWidth = (width - 20f).coerceAtLeast(280f)
        val labelWidth = tableWidth * 0.62f
        val valueWidth = tableWidth - labelWidth
        tooltip.beginTable(
            theme.nameColor,
            Misc.getDarkPlayerColor(),
            Misc.getBrightPlayerColor(),
            TABLE_ROW_HEIGHT,
            true,
            true,
            if (table.plain) table.headerAKey else I18n[I18n.Categories.MOD, table.headerAKey],
            labelWidth,
            if (table.plain) table.headerBKey else I18n[I18n.Categories.MOD, table.headerBKey],
            valueWidth,
        )
        for (row in table.rows) {
            tooltip.addRow(
                Alignment.MID,
                theme.colorFor(row.labelTone, theme.nameColor),
                if (row.plain) row.labelKey else I18n[I18n.Categories.MOD, row.labelKey],
                Alignment.MID,
                theme.colorFor(row.valueTone, theme.nameColor),
                if (row.plain) row.valueKey else I18n[I18n.Categories.MOD, row.valueKey],
            )
        }
        tooltip.addTable("", 0, table.padTop)
    }
}
