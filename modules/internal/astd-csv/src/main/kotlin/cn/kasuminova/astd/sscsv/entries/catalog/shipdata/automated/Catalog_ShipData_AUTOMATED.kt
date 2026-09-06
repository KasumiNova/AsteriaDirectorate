package cn.kasuminova.astd.sscsv.entries.catalog.shipdata.automated

import cn.kasuminova.astd.sscsv.entries.ShipDataEntry
import cn.kasuminova.astd.sscsv.entries.catalog.shipdata.shipName

/** AUTOMATED 设计系舰体数据（ship_data.csv）。 */

object Ship_astd_aurora_grid : ShipDataEntry() {
    override val id: String = "astd_aurora_grid"
    override val name: String = shipName(id)
    override val designation: String = "巡洋舰"
    override val tech: String = "自律核心"
    override val systemId: String = "astd_grid_hardening"
    override val fleetPts: Int = 25
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
    override val suppliesRec: Int = 20
    override val suppliesPerMonth: Int = 20
    override val tags: String = "astd_automated"
    override val codexVariantId: String = "astd_aurora_grid_Standard"
    override val number: Int = 9115
}

object Ship_astd_magnetic_storm_zigzag : ShipDataEntry() {
    override val id: String = "astd_magnetic_storm_zigzag"
    override val name: String = shipName(id)
    override val designation: String = "驱逐舰"
    override val tech: String = "自律核心"
    override val systemId: String = "astd_emp_burst"
    override val fleetPts: Int = 14
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
    override val suppliesRec: Int = 12
    override val suppliesPerMonth: Int = 12
    override val tags: String = "astd_automated"
    override val codexVariantId: String = "astd_magnetic_storm_zigzag_Standard"
    override val number: Int = 9116
}

object Ship_astd_echo_shimmer : ShipDataEntry() {
    override val id: String = "astd_echo_shimmer"
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
    override val suppliesRec: Int = 3
    override val suppliesPerMonth: Int = 3
    override val tags: String = "astd_automated"
    override val codexVariantId: String = "astd_echo_shimmer_Standard"
    override val number: Int = 9117
}

object Ship_astd_apex_logic : ShipDataEntry() {
    override val id: String = "astd_apex_logic"
    override val name: String = shipName(id)
    override val designation: String = "主力舰"
    override val tech: String = "自律核心"
    override val systemId: String = "astd_logic_collapse"
    override val fleetPts: Int = 60
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
    override val codexVariantId: String = "astd_apex_logic_Standard"
    override val number: Int = 9118
}
