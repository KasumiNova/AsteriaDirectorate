package cn.kasuminova.astd.sscsv.entries

import cn.kasuminova.astd.sscsv.CsvTarget
import cn.kasuminova.astd.sscsv.SsCsvEntry

/**
 * Starsector `data/shipsystems/ship_systems.csv` 的数据结构。
 *
 * 添加新的舰船系统：创建一个 Kotlin `object : ShipSystemEntry()` 并覆写需要的字段。
 */
abstract class ShipSystemEntry : SsCsvEntry {
    final override val target: CsvTarget = CsvTarget.SHIP_SYSTEMS

    /** 系统唯一 id（也用于 `data/shipsystems/<id>.system` 文件名与引用）。 */
    abstract val id: String

    /** 系统显示名称（通常为本地化后的字符串）。 */
    abstract val name: String

    /** 每秒持续消耗的幅能（原 CSV 字段：flux/second）。 */
    open val fluxPerSecond: Double = 0.0

    /** 基于幅能耗散的每秒持续消耗倍率（原 CSV 字段：f/s (base rate)）。 */
    open val fluxPerSecondBaseRate: Double = 0.0

    /** 基于幅能容量的每秒持续消耗倍率（原 CSV 字段：f/s (base cap)）。 */
    open val fluxPerSecondBaseCap: Double = 0.0

    /** 每次使用消耗的幅能（原 CSV 字段：flux/use）。 */
    open val fluxUse: Double = 0.0

    /** 基于幅能耗散的每次使用消耗倍率（原 CSV 字段：f/u (base rate)）。 */
    open val fluxUseBaseRate: Double = 0.0

    /** 基于幅能容量的每次使用消耗倍率（原 CSV 字段：f/u (base cap)）。 */
    open val fluxUseBaseCap: Double = 0.0

    /** 最大使用次数（原 CSV 字段：max uses）。为空表示不限制/使用默认规则。 */
    open val maxUses: Int? = null

    /** 使用次数每秒回复量（原 CSV 字段：regen）。例如 10 秒回复 1 次应写 0.1。为空表示不启用/使用默认规则。 */
    open val regen: Double? = null

    /** 充能时间（原 CSV 字段：charge up）。 */
    open val chargeUp: Double = 0.5

    /** 持续激活时间（原 CSV 字段：active）。 */
    open val active: Double? = 1.0

    /** 结束/收尾时间（原 CSV 字段：down）。 */
    open val down: Double = 0.5

    /** 冷却时间（原 CSV 字段：cooldown）。 */
    open val cooldown: Double = 10.0

    /** 是否为开关型系统（原 CSV 字段：toggle，TRUE/FALSE）。 */
    open val toggle: Boolean = false

    /** 激活时是否禁止幅能耗散（原 CSV 字段：noDissipation）。 */
    open val noDissipation: Boolean = false

    /** 激活时是否禁止硬幅能耗散（原 CSV 字段：noHardDissipation）。 */
    open val noHardDissipation: Boolean = false

    /** 产生的幅能是否计为硬幅能（原 CSV 字段：hardFlux）。 */
    open val hardFlux: Boolean = false

    /** 激活时是否禁止开火（原 CSV 字段：noFiring）。 */
    open val noFiring: Boolean = false

    /** 激活时是否禁止转向（原 CSV 字段：noTurning）。 */
    open val noTurning: Boolean = false

    /** 激活时是否禁止平移（原 CSV 字段：noStrafing）。 */
    open val noStrafing: Boolean = false

    /** 激活时是否禁止加速（原 CSV 字段：noAccel）。 */
    open val noAccel: Boolean = false

    /** 激活时是否禁止护盾（原 CSV 字段：noShield）。 */
    open val noShield: Boolean = false

    /** 激活时是否禁止泄幅（原 CSV 字段：noVent）。 */
    open val noVent: Boolean = false

    /** 是否为相位披风类系统（原 CSV 字段：isPhaseCloak）。 */
    open val isPhaseCloak: Boolean = false

    /** AI/系统标签（原 CSV 字段：tags；通常为空）。 */
    open val tags: String = ""

    /** 系统图标 sprite 路径（原 CSV 字段：icon）。为避免缺图，默认用原版资源。 */
    open val icon: String = "graphics/icons/hullsys/ammo_feeder.png"

    final override val key: String get() = id

    final override fun toRow(): Map<String, Any?> = linkedMapOf(
        "name" to name,
        "id" to id,
        "flux/second" to fluxPerSecond,
        "f/s (base rate)" to fluxPerSecondBaseRate,
        "f/s (base cap)" to fluxPerSecondBaseCap,
        "flux/use" to fluxUse,
        "f/u (base rate)" to fluxUseBaseRate,
        "f/u (base cap)" to fluxUseBaseCap,
        "cr/u" to 0,
        "max uses" to (maxUses ?: ""),
        "regen" to (regen ?: ""),
        "charge up" to chargeUp,
        "active" to (active ?: ""),
        "down" to down,
        "cooldown" to cooldown,
        "toggle" to if (toggle) "TRUE" else "FALSE",
        "noDissipation" to if (noDissipation) "TRUE" else "FALSE",
        "noHardDissipation" to if (noHardDissipation) "TRUE" else "FALSE",
        "hardFlux" to if (hardFlux) "TRUE" else "FALSE",
        "noFiring" to if (noFiring) "TRUE" else "FALSE",
        "noTurning" to if (noTurning) "TRUE" else "FALSE",
        "noStrafing" to if (noStrafing) "TRUE" else "FALSE",
        "noAccel" to if (noAccel) "TRUE" else "FALSE",
        "noShield" to if (noShield) "TRUE" else "FALSE",
        "noVent" to if (noVent) "TRUE" else "FALSE",
        "isPhaseCloak" to if (isPhaseCloak) "TRUE" else "FALSE",
        "tags" to tags,
        "icon" to icon,
    )
}
