package cn.kasuminova.astd.sscsv.entries.catalog.shipdata.joint

import cn.kasuminova.astd.sscsv.entries.ShipDataEntry
import cn.kasuminova.astd.sscsv.entries.catalog.shipdata.shipName

/**
 * 联制线（LH，星坠 × 紫菀联合设计品）舰船数据，规格来源 `docs/design/ships/20-joint.md`。
 *
 * 双线共用飞星级骨架；两舰在 design type、战术系统、视觉风格上分化，2026-09 起辐能/航速
 * 亦按定位分化（ARC 低容高耗高航速、LENS 高容低耗低航速）：
 * - 锻萼（LH-001）：星坠侧表达，系统「落叶飞花」；
 * - 飞星（LH-002）：紫菀侧表达，系统「视界变速」。
 *
 * 表外字段（机动/后勤/CR 系）doc 未给，按原版超级护卫舰（亥伯龙档）补齐。
 * 代价三件套落地：fleet pts 18（护卫舰档极高位）、supplies/rec+mo 18、base value 150000。
 */

/** 联制线共用数值块（[ShipDataEntry] 字段不可抽基类复用，两舰逐字段保持一致，改动需同步两侧）。 */
object Ship_astd_lh_001 : ShipDataEntry() {
    override val id: String = "astd_lh_001"
    override val name: String = shipName(id)
    override val designation: String = "先进炮艇"
    override val tech: String = "菀星设计局-星坠"
    override val systemId: String = "astd_lh_001_burst_flow"
    override val fleetPts: Int = 18
    override val hitpoints: Int = 3000
    override val armorRating: Int = 400
    override val maxFlux: Int = 7000
    override val fluxDissipation: Int = 600
    override val ordnancePoints: Int = 60
    override val maxSpeed: Int = 120
    override val acceleration: Int = 100
    override val deceleration: Int = 80
    override val maxTurnRate: Int = 30
    override val turnAcceleration: Int = 60
    override val mass: Int = 400
    override val shieldType: String = "OMNI"
    override val shieldArc: Int = 240

    // 护盾维持 240（绝对值）= 0.4 × 耗散 600。
    override val shieldUpkeep: Double = 0.4
    override val shieldEfficiency: Double = 0.65
    override val minCrew: Int = 4
    override val maxCrew: Int = 12
    override val cargo: Int = 40
    override val fuel: Int = 30
    override val fuelPerLy: Int = 1
    override val range: Int = 20
    override val maxBurn: Int = 9
    override val baseValue: Int = 150000
    override val crPercentPerDay: Double = 10.0
    override val crToDeploy: Double = 15.0
    override val peakCrSec: Int = 360
    override val crLossPerSec: Double = 0.5
    override val suppliesRec: Int = 18
    override val suppliesPerMonth: Int = 18
    override val tags: String = "astd_production"
    override val codexVariantId: String = "astd_lh_001_Standard"
    override val number: Int = 9119
}

/** 飞星（LH-002）：紫菀侧表达；骨架与锻萼共用，辐能/航速按定位分化（高容低耗低航速）。 */
object Ship_astd_lh_002 : ShipDataEntry() {
    override val id: String = "astd_lh_002"
    override val name: String = shipName(id)
    override val designation: String = "先进炮艇"
    override val tech: String = "菀星设计局-紫菀"
    override val systemId: String = "astd_lh_002_vision_shift"
    override val fleetPts: Int = 18
    override val hitpoints: Int = 3000
    override val armorRating: Int = 400
    override val maxFlux: Int = 9000
    override val fluxDissipation: Int = 450
    override val ordnancePoints: Int = 60
    override val maxSpeed: Int = 100
    override val acceleration: Int = 100
    override val deceleration: Int = 80
    override val maxTurnRate: Int = 30
    override val turnAcceleration: Int = 60
    override val mass: Int = 400
    override val shieldType: String = "OMNI"
    override val shieldArc: Int = 240

    // 护盾维持 180（绝对值）= 0.4 × 耗散 450。
    override val shieldUpkeep: Double = 0.4
    override val shieldEfficiency: Double = 0.65
    override val minCrew: Int = 4
    override val maxCrew: Int = 12
    override val cargo: Int = 40
    override val fuel: Int = 30
    override val fuelPerLy: Int = 1
    override val range: Int = 20
    override val maxBurn: Int = 9
    override val baseValue: Int = 150000
    override val crPercentPerDay: Double = 10.0
    override val crToDeploy: Double = 15.0
    override val peakCrSec: Int = 360
    override val crLossPerSec: Double = 0.5
    override val suppliesRec: Int = 18
    override val suppliesPerMonth: Int = 18
    override val tags: String = "astd_production"
    override val codexVariantId: String = "astd_lh_002_Standard"
    override val number: Int = 9120
}
