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
    override val baseValue: Int = 100000
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
    override val baseValue: Int = 100000
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
    override val baseValue: Int = 100000
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
    override val baseValue: Int = 100000
    override val tags: String = "astd_unique"
    override val codexVariantId: String = "astd_apex_logic_Standard"
    override val number: Int = 9118
}
