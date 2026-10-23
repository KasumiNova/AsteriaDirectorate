package cn.kasuminova.astd.sscsv.entries

import cn.kasuminova.astd.sscsv.CsvTarget
import cn.kasuminova.astd.sscsv.SsCsvEntry

abstract class WeaponDataEntry : SsCsvEntry {
    final override val target: CsvTarget = CsvTarget.WEAPON_DATA

    abstract val name: String

    abstract val id: String

    open val tier: Int = 1

    open val rarity: Int = 1

    open val baseValue: Int = 0

    open val range: Int = 0

    open val damagePerSecond: Number = 0

    open val damagePerShot: Int = 0

    open val emp: Int = 0

    open val impact: Int = 0

    open val turnRate: Int = 0

    open val ops: Int = 0

    open val ammo: Int = 0

    open val ammoPerSec: Double = 0.0

    open val reloadSize: Int = 0

    open val type: String = ""

    open val energyPerShot: Int = 0

    open val energyPerSecond: Int = 0

    open val chargeup: Double = 0.0

    open val chargedown: Double = 0.0

    open val burstSize: Number = 0

    open val burstDelay: Double = 0.0

    open val minSpread: Double = 0.0

    open val maxSpread: Double = 0.0

    open val spreadPerShot: Double = 0.0

    open val spreadDecayPerSec: Double = 0.0

    open val beamSpeed: Int = 0

    open val projSpeed: Int = 0

    open val launchSpeed: Int = 0

    open val flightTime: Double = 0.0

    open val projHitpoints: Int = 0

    open val autofireAccBonus: Int = 0

    open val extraArcForAI: Int = 0

    open val aiHints: Set<AiHint> = emptySet()

    @Deprecated("Use aiHints: Set<AiHint> instead")
    open val hints: String? = null

    open val tags: String = ""

    open val groupTag: String = ""

    open val tech: String = ""

    open val forWeaponTooltip: String = ""

    open val primaryRoleStr: String = ""

    open val speedStr: String = ""

    open val trackingStr: String = ""

    open val turnRateStr: String = ""

    open val accuracyStr: String = ""

    open val customPrimary: String = ""

    open val customPrimaryHL: String = ""

    open val customAncillary: String = ""

    open val customAncillaryHL: String = ""

    open val noDpsInTooltip: Boolean = false

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
        if (aiHints.isEmpty()) {
            @Suppress("DEPRECATION")
            val legacy = hints?.trim().orEmpty()
            return legacy.takeIf { it.isNotBlank() && it != "0" }
        }

        val encoded = aiHints.asSequence().map { it.csvToken }.sorted().joinToString(",")
        return encoded.takeIf { it.isNotBlank() && it != "0" }
    }
}

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

    val csvToken: String get() = name
}
