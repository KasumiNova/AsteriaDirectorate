package cn.kasuminova.astd.sscsv.entries

import cn.kasuminova.astd.sscsv.CsvTarget
import cn.kasuminova.astd.sscsv.SsCsvEntry

/**
 * Starsector `data/hulls/ship_data.csv` 的标准条目基类（字段化版本）。
 *
 * 说明：
 * - 与“迁移期”的 `csvLine` 方式不同，这里改为显式字段，提升可读性与可维护性。
 * - 生成器会按 schema header 的列名写入；未提供的列会写空值。
 */
abstract class ShipDataEntry : SsCsvEntry {
    final override val target: CsvTarget = CsvTarget.SHIP_DATA

    /** 舰体显示名称（ship_data.csv 的第一列）。 */
    abstract val name: String

    /** 舰体 id（ship_data.csv 的第二列；同时作为 key）。 */
    abstract val id: String

    /** 舰体定位/等级（designation，例如 U/C/D/F/A 等）。 */
    open val designation: String = ""

    /** 科技/制造商（tech/manufacturer）。 */
    open val tech: String = ""

    /** 系统 id（system id）。 */
    open val systemId: String = ""

    /** 舰队点数（fleet pts）。 */
    open val fleetPts: Int = 0

    /** 结构值（hitpoints）。 */
    open val hitpoints: Int = 0

    /** 装甲（armor rating）。 */
    open val armorRating: Int = 0

    /** 最大幅能（max flux）。 */
    open val maxFlux: Int = 0

    /** 原版 ship_data.csv 的“8/6/5/4%”列，通常留空即可。 */
    open val fluxCapacityPercent_8654: String = ""

    /** 幅能耗散（flux dissipation）。 */
    open val fluxDissipation: Int = 0

    /** OP 总量（ordnance points）。 */
    open val ordnancePoints: Int = 0

    /** 机库数量（fighter bays）。 */
    open val fighterBays: Int = 0

    /** 最大航速（max speed）。 */
    open val maxSpeed: Int = 0

    /** 加速度（acceleration）。 */
    open val acceleration: Int = 0

    /** 减速度（deceleration）。 */
    open val deceleration: Int = 0

    /** 最大转向率（max turn rate）。 */
    open val maxTurnRate: Int = 0

    /** 转向加速度（turn acceleration）。 */
    open val turnAcceleration: Int = 0

    /** 质量（mass）。 */
    open val mass: Int = 0

    /** 护盾类型（shield type：FRONT/OMNI/NONE 等）。 */
    open val shieldType: String = ""

    /** 防御 id（defense id；通常留空）。 */
    open val defenseId: String = ""

    /** 护盾弧度（shield arc）。 */
    open val shieldArc: Int = 0

    /** 护盾维持消耗（shield upkeep）。 */
    open val shieldUpkeep: Double = 0.0

    /** 护盾效率（shield efficiency）。 */
    open val shieldEfficiency: Double = 0.0

    /** 相位启动消耗（phase cost；非相位舰通常为空）。 */
    open val phaseCost: Double? = null

    /** 相位维持消耗（phase upkeep；非相位舰通常为空）。 */
    open val phaseUpkeep: Double? = null

    /** 最少船员（min crew）。 */
    open val minCrew: Int = 0

    /** 最大船员（max crew）。 */
    open val maxCrew: Int = 0

    /** 货仓（cargo）。 */
    open val cargo: Int = 0

    /** 燃料（fuel）。 */
    open val fuel: Int = 0

    /** 每光年燃料消耗（fuel/ly）。 */
    open val fuelPerLy: Int = 0

    /** 航程（range）。 */
    open val range: Int = 0

    /** 最大燃烧等级（max burn）。 */
    open val maxBurn: Int = 0

    /** 基础价值（base value）。 */
    open val baseValue: Int = 0

    /** CR 每日损耗（cr %/day）。 */
    open val crPercentPerDay: Double = 0.0

    /** 部署所需 CR（CR to deploy）。 */
    open val crToDeploy: Double = 0.0

    /** 峰值 CR 持续时间（peak CR sec）。 */
    open val peakCrSec: Int = 0

    /** 峰值后 CR 流失速率（CR loss/sec）。 */
    open val crLossPerSec: Double = 0.0

    /** 修复补给消耗（supplies/rec）。 */
    open val suppliesRec: Int = 0

    /** 每月补给消耗（supplies/mo）。 */
    open val suppliesPerMonth: Int = 0

    /** cargo/s（c/s）。 */
    open val cPerS: Int = 0

    /** cargo/f（c/f）。 */
    open val cPerF: Int = 0

    /** fuel/s（f/s）。 */
    open val fPerS: Int = 0

    /** fuel/f（f/f）。 */
    open val fPerF: Int = 0

    /** crew/s（crew/s）。 */
    open val crewPerS: Int = 0

    /** crew/f（crew/f）。 */
    open val crewPerF: Int = 0

    /** 提示（hints）。 */
    open val hints: String = ""

    /** 标签（tags）。 */
    open val tags: String = ""

    /** 后勤不可用原因（logistics n/a reason）。 */
    open val logisticsNaReason: String = ""

    /** 词条/百科关联的变体 id（codex variant id）。 */
    open val codexVariantId: String = ""

    /** 稀有度（rarity）。 */
    open val rarity: Int = 1

    /** 掉落分解概率（breakProb）。 */
    open val breakProb: Double = 0.0

    /** 最小碎片数（minPieces）。 */
    open val minPieces: Int = 1

    /** 最大碎片数（maxPieces）。 */
    open val maxPieces: Int = 1

    /** 旅行驱动（travel drive；通常留空）。 */
    open val travelDrive: String = ""

    /** 行号/编号（number）。 */
    open val number: Int = 0

    final override val key: String get() = id

    final override fun toRow(): Map<String, Any?> = linkedMapOf(
        "name" to name,
        "id" to id,
        "designation" to designation,
        "tech/manufacturer" to tech,
        "system id" to systemId,
        "fleet pts" to fleetPts,
        "hitpoints" to hitpoints,
        "armor rating" to armorRating,
        "max flux" to maxFlux,
        "8/6/5/4%" to fluxCapacityPercent_8654,
        "flux dissipation" to fluxDissipation,
        "ordnance points" to ordnancePoints,
        "fighter bays" to fighterBays,
        "max speed" to maxSpeed,
        "acceleration" to acceleration,
        "deceleration" to deceleration,
        "max turn rate" to maxTurnRate,
        "turn acceleration" to turnAcceleration,
        "mass" to mass,
        "shield type" to shieldType,
        "defense id" to defenseId,
        "shield arc" to shieldArc,
        "shield upkeep" to shieldUpkeep,
        "shield efficiency" to shieldEfficiency,
        "phase cost" to phaseCost,
        "phase upkeep" to phaseUpkeep,
        "min crew" to minCrew,
        "max crew" to maxCrew,
        "cargo" to cargo,
        "fuel" to fuel,
        "fuel/ly" to fuelPerLy,
        "range" to range,
        "max burn" to maxBurn,
        "base value" to baseValue,
        "cr %/day" to crPercentPerDay,
        "CR to deploy" to crToDeploy,
        "peak CR sec" to peakCrSec,
        "CR loss/sec" to crLossPerSec,
        "supplies/rec" to suppliesRec,
        "supplies/mo" to suppliesPerMonth,
        "c/s" to cPerS,
        "c/f" to cPerF,
        "f/s" to fPerS,
        "f/f" to fPerF,
        "crew/s" to crewPerS,
        "crew/f" to crewPerF,
        "hints" to hints,
        "tags" to tags,
        "logistics n/a reason" to logisticsNaReason,
        "codex variant id" to codexVariantId,
        "rarity" to rarity,
        "breakProb" to breakProb,
        "minPieces" to minPieces,
        "maxPieces" to maxPieces,
        "travel drive" to travelDrive,
        "number" to number,
    )
}
