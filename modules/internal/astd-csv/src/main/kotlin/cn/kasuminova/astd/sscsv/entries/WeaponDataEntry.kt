package cn.kasuminova.astd.sscsv.entries

import cn.kasuminova.astd.sscsv.CsvTarget
import cn.kasuminova.astd.sscsv.SsCsvEntry

/**
 * Starsector `data/weapons/weapon_data.csv` 的标准条目基类（字段化版本）。
 *
 * 说明：
 * - 与“迁移期”的 `csvLine` 方式不同，这里改为显式字段，提升可读性与可维护性。
 * - 生成器会按 schema header 的列名写入；未提供的列会写空值。
 */
abstract class WeaponDataEntry : SsCsvEntry {
    final override val target: CsvTarget = CsvTarget.WEAPON_DATA

    /** 武器显示名称（weapon_data.csv 的第一列）。 */
    abstract val name: String

    /** 武器 id（weapon_data.csv 的第二列；同时作为 key）。 */
    abstract val id: String

    /** 分级/强度等级（tier）。 */
    open val tier: Int = 1

    /** 稀有度（rarity）。 */
    open val rarity: Int = 1

    /** 基础价值（base value）。 */
    open val baseValue: Int = 0

    /** 射程（range）。 */
    open val range: Int = 0

    /** 每秒伤害（damage/second；用于 tooltip 统计）。 */
    open val damagePerSecond: Int = 0

    /** 单发伤害（damage/shot）。 */
    open val damagePerShot: Int = 0

    /** EMP 伤害（emp）。 */
    open val emp: Int = 0

    /** 冲击值（impact）。 */
    open val impact: Int = 0

    /** 转向速度（turn rate）。 */
    open val turnRate: Int = 0

    /** OP 消耗（OPs）。 */
    open val ops: Int = 0

    /** 弹药量（ammo）。 */
    open val ammo: Int = 0

    /** 弹药回复速度（ammo/sec）。 */
    open val ammoPerSec: Double = 0.0

    /** 每次装填数量（reload size）。 */
    open val reloadSize: Int = 0

    /**
     * 伤害类型（type；对应 Starsector `DamageType` 枚举，例如：
     * - KINETIC
     * - HIGH_EXPLOSIVE
     * - FRAGMENTATION
     * - ENERGY
     * - OTHER
     *
     * 注意：武器“挂载类型/武器类别”（BALLISTIC/ENERGY/MISSILE 等）在 `.wpn` 里也有一个 `type` 字段，
     * 但那是另一个维度；不要把两者混用。
     */
    open val type: String = ""

    /** 单发能量消耗（energy/shot）。 */
    open val energyPerShot: Int = 0

    /** 每秒能量消耗（energy/second；用于 tooltip 统计）。 */
    open val energyPerSecond: Int = 0

    /** 充能时间（chargeup）。 */
    open val chargeup: Double = 0.0

    /** 释能时间（chargedown）。 */
    open val chargedown: Double = 0.0

    /** 连发数量（burst size）。 */
    open val burstSize: Number = 0

    /** 连发间隔（burst delay）。 */
    open val burstDelay: Double = 0.0

    /** 最小散布（min spread）。 */
    open val minSpread: Double = 0.0

    /** 最大散布（max spread）。 */
    open val maxSpread: Double = 0.0

    /** 每发增加散布（spread/shot）。 */
    open val spreadPerShot: Double = 0.0

    /** 每秒散布衰减（spread decay/sec）。 */
    open val spreadDecayPerSec: Double = 0.0

    /** 光束速度（beam speed）。 */
    open val beamSpeed: Int = 0

    /** 弹丸速度（proj speed）。 */
    open val projSpeed: Int = 0

    /** 发射速度（launch speed）。 */
    open val launchSpeed: Int = 0

    /** 飞行时间（flight time）。 */
    open val flightTime: Double = 0.0

    /** 弹体耐久（proj hitpoints）。 */
    open val projHitpoints: Int = 0

    /** AI 自动开火命中加成（autofireAccBonus）。 */
    open val autofireAccBonus: Int = 0

    /** AI 额外弧度（extraArcForAI）。 */
    open val extraArcForAI: Int = 0

    /**
     * AI Hints（给自动驾驶 AI 的使用标签）。
     *
     * - 输出 CSV 时仍写入原版列名 `hints`（兼容游戏读取）。
     * - ss-csv 侧使用强类型枚举 [AiHint]（与 `WeaponAPI.AIHints` 对齐）避免误填/误判。
     */
    open val aiHints: Set<AiHint> = emptySet()

    /**
     * 兼容字段：历史上直接用字符串写入 weapon_data.csv 的 `hints` 列。
     * 新代码请改用 [aiHints]。
     */
    @Deprecated("Use aiHints: Set<AiHint> instead")
    open val hints: String? = null

    /** 标签（tags）。 */
    open val tags: String = ""

    /** 组标签（groupTag）。 */
    open val groupTag: String = ""

    /** 科技/制造商（tech/manufacturer）。 */
    open val tech: String = ""

    /** 原版 CSV 的“分隔提示列”（for weapon tooltip>>），通常留空即可。 */
    open val forWeaponTooltip: String = ""

    /** 武器战术应用（primaryRoleStr）。 */
    open val primaryRoleStr: String = ""

    /** 速度描述文字（speedStr）。 */
    open val speedStr: String = ""

    /** 追踪能力描述文字（trackingStr）。 */
    open val trackingStr: String = ""

    /** 转向能力描述文字（turnRateStr）。 */
    open val turnRateStr: String = ""

    /** 精度描述文字（accuracyStr）。 */
    open val accuracyStr: String = ""

    /** 描述文本，主要位于武器描述中部位置（customPrimary）。 */
    open val customPrimary: String = ""

    /** customPrimary 的高亮显示，SS 内部使用 `|` 分割（例如 highlight1 | highlight2）（customPrimaryHL）。 */
    open val customPrimaryHL: String = ""

    /** 副描述文本，主要位于武器描述底部位置（customAncillary）。 */
    open val customAncillary: String = ""

    /** customAncillary 的高亮显示，格式与 `customPrimary` 类似（customAncillaryHL）。 */
    open val customAncillaryHL: String = ""

    /** 是否在 tooltip 中隐藏 DPS（noDPSInTooltip，TRUE/FALSE）。 */
    open val noDpsInTooltip: Boolean = false

    /** 行号/编号（number）。 */
    open val number: Int = 0

    final override val key: String get() = id

    final override fun toRow(): Map<String, Any?> = linkedMapOf(
        "name" to name,
        "id" to id,
        "tier" to tier,
        "rarity" to rarity,
        "base value" to baseValue,
        "range" to range,
        "damage/second" to damagePerSecond,
        // 原版约定：beam（damage/second 非空）时，damage/shot 需要留空；否则面板/tooltip 可能会走“单发伤害”分支导致显示异常。
        "damage/shot" to damagePerShot.takeUnless { it == 0 && damagePerSecond != 0 },
        "emp" to emp,
        "impact" to impact,
        "turn rate" to turnRate,
        "OPs" to ops,
        // 与原版 weapon_data.csv 保持一致：不使用弹药/弹匣系统时让列保持空值，避免 tooltip 显示“Ammo: 0”。
        "ammo" to ammo.takeIf { it != 0 },
        "ammo/sec" to ammoPerSec.takeIf { it != 0.0 },
        "reload size" to reloadSize.takeIf { it != 0 },
        "type" to type,
        // 原版约定：beam 且 energy/second 非空时，energy/shot 留空；否则会出现“能量/发: 0”或统计分支混用。
        "energy/shot" to energyPerShot.takeUnless { it == 0 && energyPerSecond != 0 },
        "energy/second" to energyPerSecond,
        "chargeup" to chargeup,
        "chargedown" to chargedown,
        "burst size" to burstSize,
        "burst delay" to burstDelay,
        "min spread" to minSpread,
        "max spread" to maxSpread,
        "spread/shot" to spreadPerShot,
        "spread decay/sec" to spreadDecayPerSec,
        "beam speed" to beamSpeed,
        "proj speed" to projSpeed,
        "launch speed" to launchSpeed,
        "flight time" to flightTime,
        "proj hitpoints" to projHitpoints,
        "autofireAccBonus" to autofireAccBonus,
        "extraArcForAI" to extraArcForAI,
        "hints" to encodeAiHintsForCsv(),
        "tags" to tags,
        "groupTag" to groupTag,
        "tech/manufacturer" to tech,
        "for weapon tooltip>>" to forWeaponTooltip,
        "primaryRoleStr" to primaryRoleStr,
        "speedStr" to speedStr,
        "trackingStr" to trackingStr,
        "turnRateStr" to turnRateStr,
        "accuracyStr" to accuracyStr,
        "customPrimary" to customPrimary,
        "customPrimaryHL" to customPrimaryHL,
        "customAncillary" to customAncillary,
        "customAncillaryHL" to customAncillaryHL,
        "noDPSInTooltip" to if (noDpsInTooltip) "TRUE" else "FALSE",
        "number" to number,
    )
    private fun encodeAiHintsForCsv(): String? {
        // 兼容：若 aiHints 未指定，则回退到旧的 hints 字符串。
        if (aiHints.isEmpty()) {
    @Suppress("DEPRECATION")
            val legacy = hints?.trim().orEmpty()
            // 防御：出现过列错位导致 hints=0 的情况；输出时直接视为未填写。
            return legacy.takeIf { it.isNotBlank() && it != "0" }
        }

        // weapon_data.csv 的 hints 列约定为逗号分隔：PD,DO_NOT_AIM,...
        val encoded = aiHints.asSequence().map { it.csvToken }.sorted().joinToString(",")
        return encoded.takeIf { it.isNotBlank() && it != "0" }
    }
}

/**
 * weapon_data.csv 的 `hints` 列允许的 token。
 *
 * ss-csv 子工程不依赖 Starsector API，因此在此定义本地枚举。
 * 若需要新增 token，可先临时使用旧字段 [WeaponDataEntry.hints] 直接输出字符串，再补充枚举。
 */
enum class AiHint {
    PD,
    PD_ONLY,
    PD_ALSO,
    USE_VS_FRIGATES,
    STRIKE,
    DANGEROUS,
    BOMB,
    GUIDED_POOR,
    DO_NOT_AIM,
    ANTI_FTR,
    HEATSEEKER,
    SYSTEM,
    SHOW_IN_CODEX,
    AUTOZOOM,
    DO_NOT_CONSERVE,
    CONSERVE_1,
    CONSERVE_2,
    CONSERVE_3,
    CONSERVE_4,
    CONSERVE_5,
    CONSERVE_ALL,
    CONSERVE_FOR_ANTI_ARMOR,
    FIRE_WHEN_INEFFICIENT,
    EXTRA_RANGE_ON_FIGHTER,
    IGNORES_FLARES,
    GROUP_LINKED,
    GROUP_ALTERNATING,
    MISSILE_SPREAD,
    DIRECT_AIM,
    NO_TURN_RATE_BOOST_WHEN_IDLE,
    RESET_BARREL_INDEX_ON_BURST,
    USE_LESS_VS_SHIELDS,
    RANGE_FROM_TARGETING_OVAL,
    RANGE_FROM_SHIP_RADIUS,
    IMPORTANT,
    NO_MANUAL_FIRE;
    ;

    val csvToken: String get() = name
}
