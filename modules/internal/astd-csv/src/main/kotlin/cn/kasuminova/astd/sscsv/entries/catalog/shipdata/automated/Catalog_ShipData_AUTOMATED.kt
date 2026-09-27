package cn.kasuminova.astd.sscsv.entries.catalog.shipdata.automated

import cn.kasuminova.astd.sscsv.entries.ShipDataEntry
import cn.kasuminova.astd.sscsv.entries.catalog.shipdata.shipName

/** AUTOMATED 设计系舰体数据（ship_data.csv）。 */

object Ship_astd_zl_101 : ShipDataEntry() {
    override val id: String = "astd_zl_101"
    override val name: String = shipName(id)
    override val designation: String = "巡洋舰"
    override val tech: String = "自律核心"
    override val systemId: String = "astd_grid_hardening"

    // 自动战斗分数对齐原版无人巡洋舰（辉煌 fp=16）；部署点 25 由 supplies/rec 承担。
    override val fleetPts: Int = 16
    override val hitpoints: Int = 9000
    override val armorRating: Int = 600
    override val maxFlux: Int = 12000
    override val fluxDissipation: Int = 800
    override val ordnancePoints: Int = 160
    override val maxSpeed: Int = 65
    override val acceleration: Int = 32
    override val deceleration: Int = 32
    override val maxTurnRate: Int = 30
    override val turnAcceleration: Int = 60
    override val mass: Int = 16000
    override val shieldType: String = "FRONT"
    override val shieldArc: Int = 120
    override val shieldUpkeep: Double = 0.6
    override val shieldEfficiency: Double = 0.8
    override val minCrew: Int = 10
    override val maxCrew: Int = 250
    override val cargo: Int = 150
    override val fuel: Int = 100
    override val fuelPerLy: Int = 3
    override val range: Int = 33
    override val maxBurn: Int = 8
    override val baseValue: Int = 100000
    override val crPercentPerDay: Double = 3.0
    override val crToDeploy: Double = 12.0
    override val peakCrSec: Int = 600
    override val crLossPerSec: Double = 0.25
    override val suppliesRec: Int = 25
    override val suppliesPerMonth: Int = 25
    override val tags: String = "astd_automated"
    override val codexVariantId: String = "astd_zl_101_Standard"
    override val number: Int = 9115
}

object Ship_astd_zl_102 : ShipDataEntry() {
    override val id: String = "astd_zl_102"
    override val name: String = shipName(id)
    override val designation: String = "驱逐舰"
    override val tech: String = "自律核心"
    override val systemId: String = "astd_emp_burst"

    // 自动战斗分数对齐原版无人/高速驱逐舰（光辉、美杜莎 fp=12）；部署点 14 由 supplies/rec 承担。
    override val fleetPts: Int = 12
    override val hitpoints: Int = 3200
    override val armorRating: Int = 300
    override val maxFlux: Int = 4000
    override val fluxDissipation: Int = 400
    override val ordnancePoints: Int = 95
    override val maxSpeed: Int = 120
    override val acceleration: Int = 60
    override val deceleration: Int = 60
    override val maxTurnRate: Int = 30
    override val turnAcceleration: Int = 60
    override val mass: Int = 8000
    override val shieldType: String = "FRONT"
    override val shieldArc: Int = 120
    override val shieldUpkeep: Double = 0.6
    override val shieldEfficiency: Double = 0.8
    override val minCrew: Int = 10
    override val maxCrew: Int = 250
    override val cargo: Int = 60
    override val fuel: Int = 80
    override val fuelPerLy: Int = 2
    override val range: Int = 40
    override val maxBurn: Int = 9
    override val baseValue: Int = 100000
    override val crPercentPerDay: Double = 5.0
    override val crToDeploy: Double = 20.0
    override val peakCrSec: Int = 480
    override val crLossPerSec: Double = 0.25
    override val suppliesRec: Int = 14
    override val suppliesPerMonth: Int = 14
    override val tags: String = "astd_automated"
    override val codexVariantId: String = "astd_zl_102_Standard"
    override val number: Int = 9116
}

object Ship_astd_zl_103 : ShipDataEntry() {
    override val id: String = "astd_zl_103"
    override val name: String = shipName(id)
    override val designation: String = "护卫舰"
    override val tech: String = "自律核心"
    override val systemId: String = "astd_signal_overload"
    override val fleetPts: Int = 4
    override val hitpoints: Int = 600
    override val armorRating: Int = 50
    override val maxFlux: Int = 1000
    override val fluxDissipation: Int = 100
    override val ordnancePoints: Int = 35
    override val maxSpeed: Int = 180
    override val acceleration: Int = 90
    override val deceleration: Int = 90
    override val maxTurnRate: Int = 30
    override val turnAcceleration: Int = 60
    override val mass: Int = 4000
    override val shieldType: String = "FRONT"
    override val shieldArc: Int = 120
    override val shieldUpkeep: Double = 0.6
    override val shieldEfficiency: Double = 0.8
    override val minCrew: Int = 10
    override val maxCrew: Int = 250
    override val cargo: Int = 15
    override val fuel: Int = 15
    override val fuelPerLy: Int = 1
    override val maxBurn: Int = 10
    override val baseValue: Int = 100000
    override val crPercentPerDay: Double = 10.0
    override val crToDeploy: Double = 20.0
    override val peakCrSec: Int = 120
    override val crLossPerSec: Double = 0.5
    override val suppliesRec: Int = 4
    override val suppliesPerMonth: Int = 4
    override val tags: String = "astd_automated"
    override val codexVariantId: String = "astd_zl_103_Standard"
    override val number: Int = 9117
}

object Ship_astd_zl_001 : ShipDataEntry() {
    override val id: String = "astd_zl_001"
    override val name: String = shipName(id)
    override val designation: String = "主力舰"
    override val tech: String = "自律核心"
    override val systemId: String = "astd_logic_collapse"

    // 自动战斗分数对齐原版 Boss 级主力舰（通灵塔 fp=40）；部署点 60 由 supplies/rec 承担。
    override val fleetPts: Int = 40
    override val hitpoints: Int = 14000
    override val armorRating: Int = 800
    override val maxFlux: Int = 20000
    override val fluxDissipation: Int = 1500
    override val ordnancePoints: Int = 280
    override val maxSpeed: Int = 50
    override val acceleration: Int = 25
    override val deceleration: Int = 25
    override val maxTurnRate: Int = 30
    override val turnAcceleration: Int = 60
    override val mass: Int = 30000
    override val shieldType: String = "FRONT"
    override val shieldArc: Int = 120
    override val shieldUpkeep: Double = 0.6
    override val shieldEfficiency: Double = 0.8
    override val minCrew: Int = 10
    override val maxCrew: Int = 250
    override val cargo: Int = 300
    override val fuel: Int = 300
    override val fuelPerLy: Int = 10
    override val range: Int = 30
    override val maxBurn: Int = 7
    override val baseValue: Int = 100000
    override val crPercentPerDay: Double = 4.0
    override val crToDeploy: Double = 20.0
    override val peakCrSec: Int = 920
    override val crLossPerSec: Double = 0.25
    override val suppliesRec: Int = 60
    override val suppliesPerMonth: Int = 60
    override val tags: String = "astd_unique"
    override val codexVariantId: String = "astd_zl_001_Standard"
    override val number: Int = 9118
}
