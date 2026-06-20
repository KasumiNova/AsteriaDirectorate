package cn.kasuminova.astd.sscsv.entries.catalog.shipdata.arc

import cn.kasuminova.astd.sscsv.entries.ShipDataEntry
import cn.kasuminova.astd.sscsv.entries.catalog.shipdata.shipName

/** ARC 设计系舰体数据（ship_data.csv）。 */

object Ship_astd_arc_flare : ShipDataEntry() {
    override val id: String = "astd_arc_flare"
    override val name: String = shipName(id)
    override val designation: String = "主力舰"
    override val tech: String = "弧光阵列"
    override val systemId: String = "astd_arc_flare_overdrive_crewed"
    override val fleetPts: Int = 55
    override val hitpoints: Int = 20000
    override val armorRating: Int = 1500
    override val maxFlux: Int = 23000
    override val fluxDissipation: Int = 1300
    override val ordnancePoints: Int = 300
    override val maxSpeed: Int = 65
    override val acceleration: Int = 20
    override val deceleration: Int = 20
    override val maxTurnRate: Int = 20
    override val turnAcceleration: Int = 40
    override val mass: Int = 3500
    override val shieldType: String = "OMNI"
    override val shieldArc: Int = 180
    override val shieldUpkeep: Double = 0.37
    override val shieldEfficiency: Double = 0.70
    override val minCrew: Int = 400
    override val maxCrew: Int = 1000
    override val cargo: Int = 300
    override val fuel: Int = 300
    override val fuelPerLy: Int = 10
    override val range: Int = 30
    override val maxBurn: Int = 7
    override val baseValue: Int = 500000
    override val crPercentPerDay: Double = 4.0
    override val crToDeploy: Double = 20.0
    override val peakCrSec: Int = 920
    override val crLossPerSec: Double = 0.25
    override val suppliesRec: Int = 60
    override val suppliesPerMonth: Int = 60
    override val tags: String = "astd_unique"
    override val codexVariantId: String = "astd_arc_flare_Standard"
    override val number: Int = 9101
}

object Ship_astd_negentropy_edge : ShipDataEntry() {
    override val id: String = "astd_negentropy_edge"
    override val name: String = shipName(id)
    override val designation: String = "驱逐舰"
    override val tech: String = "弧光阵列"
    override val systemId: String = "astd_collapse_shift"
    override val fleetPts: Int = 20
    override val hitpoints: Int = 6500
    override val armorRating: Int = 650
    override val maxFlux: Int = 9000
    override val fluxDissipation: Int = 600
    override val ordnancePoints: Int = 120
    override val fighterBays: Int = 1
    override val maxSpeed: Int = 115
    override val acceleration: Int = 74
    override val deceleration: Int = 74
    override val maxTurnRate: Int = 30
    override val turnAcceleration: Int = 60
    override val mass: Int = 350
    override val shieldType: String = "OMNI"
    override val shieldArc: Int = 180
    override val shieldUpkeep: Double = 0.0267
    override val shieldEfficiency: Double = 0.65
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
    override val tags: String = "astd_unique"
    override val codexVariantId: String = "astd_negentropy_edge_Standard"
    override val number: Int = 9102
}

object Ship_astd_conjugate_terminal : ShipDataEntry() {
    override val id: String = "astd_conjugate_terminal"
    override val name: String = shipName(id)
    override val hitpoints: Int = 120
    override val armorRating: Int = 40
    override val maxFlux: Int = 300
    override val fluxDissipation: Int = 120
    override val maxSpeed: Int = 240
    override val acceleration: Int = 300
    override val deceleration: Int = 300
    override val maxTurnRate: Int = 180
    override val turnAcceleration: Int = 360
    override val mass: Int = 10
    override val shieldType: String = "NONE"
    override val logisticsNaReason: String = "不可用 (无人机)"
    override val hints: String = "HIDE_IN_CODEX"
    override val rarity: Int = 0
    override val number: Int = 9127
}

object Ship_astd_arc_nova : ShipDataEntry() {
    override val id: String = "astd_arc_nova"
    override val name: String = shipName(id)
    override val designation: String = "主力舰"
    override val tech: String = "弧光阵列"
    override val systemId: String = "astd_stellar_jet"
    override val fleetPts: Int = 72
    override val hitpoints: Int = 22000
    override val armorRating: Int = 1000
    override val maxFlux: Int = 27500
    override val fluxDissipation: Int = 1100
    override val ordnancePoints: Int = 320
    override val maxSpeed: Int = 35
    override val acceleration: Int = 20
    override val deceleration: Int = 20
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
    override val codexVariantId: String = "astd_arc_nova_Standard"
    override val number: Int = 9103
}

object Ship_astd_arc_jet : ShipDataEntry() {
    override val id: String = "astd_arc_jet"
    override val name: String = shipName(id)
    override val designation: String = "主力舰"
    override val tech: String = "弧光阵列"
    override val systemId: String = "astd_arc_shared_flux_network"
    override val fleetPts: Int = 55
    override val hitpoints: Int = 27500
    override val armorRating: Int = 1800
    override val maxFlux: Int = 25000
    override val fluxDissipation: Int = 1400
    override val ordnancePoints: Int = 350
    override val maxSpeed: Int = 40
    override val acceleration: Int = 15
    override val deceleration: Int = 15
    override val maxTurnRate: Int = 20
    override val turnAcceleration: Int = 40
    override val mass: Int = 4200
    override val shieldType: String = "OMNI"
    override val shieldArc: Int = 240
    override val shieldUpkeep: Double = 0.6
    override val shieldEfficiency: Double = 0.70
    override val minCrew: Int = 400
    override val maxCrew: Int = 500
    override val cargo: Int = 300
    override val fuel: Int = 300
    override val fuelPerLy: Int = 10
    override val range: Int = 30
    override val maxBurn: Int = 8
    override val baseValue: Int = 300000
    override val crPercentPerDay: Double = 3.0
    override val crToDeploy: Double = 15.0
    override val peakCrSec: Int = 720
    override val crLossPerSec: Double = 0.25
    override val suppliesRec: Int = 40
    override val suppliesPerMonth: Int = 40
    override val tags: String = "astd_production"
    override val codexVariantId: String = "astd_arc_jet_Standard"
    override val number: Int = 9107
}

object Ship_astd_plasma_arch : ShipDataEntry() {
    override val id: String = "astd_plasma_arch"
    override val name: String = shipName(id)
    override val designation: String = "巡洋舰"
    override val tech: String = "弧光阵列"
    override val systemId: String = "astd_plasma_armor_shield_boost"
    override val fleetPts: Int = 32
    override val hitpoints: Int = 13000
    override val armorRating: Int = 1600
    override val maxFlux: Int = 14000
    override val fluxDissipation: Int = 750
    override val ordnancePoints: Int = 185
    override val maxSpeed: Int = 45
    override val acceleration: Int = 15
    override val deceleration: Int = 15
    override val maxTurnRate: Int = 10
    override val turnAcceleration: Int = 20
    override val mass: Int = 2250
    override val shieldType: String = "FRONT"
    override val shieldArc: Int = 360
    override val shieldUpkeep: Double = 0.6
    override val shieldEfficiency: Double = 1.00
    override val minCrew: Int = 150
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
    override val tags: String = "astd_production"
    override val codexVariantId: String = "astd_plasma_arch_Standard"
    override val number: Int = 9108
}

object Ship_astd_radiation_belt : ShipDataEntry() {
    override val id: String = "astd_radiation_belt"
    override val name: String = shipName(id)
    override val designation: String = "驱逐舰"
    override val tech: String = "弧光阵列"
    override val systemId: String = "astd_limit_temporal_thruster"
    override val fleetPts: Int = 14
    override val hitpoints: Int = 5500
    override val armorRating: Int = 650
    override val maxFlux: Int = 6500
    override val fluxDissipation: Int = 500
    override val ordnancePoints: Int = 90
    override val maxSpeed: Int = 95
    override val acceleration: Int = 42
    override val deceleration: Int = 42
    override val maxTurnRate: Int = 30
    override val turnAcceleration: Int = 60
    override val mass: Int = 8000
    override val shieldType: String = "OMNI"
    override val shieldArc: Int = 200
    override val shieldUpkeep: Double = 0.6
    override val shieldEfficiency: Double = 0.70
    override val minCrew: Int = 50
    override val maxCrew: Int = 70
    override val cargo: Int = 80
    override val fuel: Int = 50
    override val fuelPerLy: Int = 2
    override val range: Int = 25
    override val maxBurn: Int = 9
    override val baseValue: Int = 45000
    override val crPercentPerDay: Double = 5.0
    override val crToDeploy: Double = 15.0
    override val peakCrSec: Int = 360
    override val crLossPerSec: Double = 0.25
    override val suppliesRec: Int = 11
    override val suppliesPerMonth: Int = 11
    override val tags: String = "astd_production"
    override val codexVariantId: String = "astd_radiation_belt_Standard"
    override val number: Int = 9109
}

object Ship_astd_arc_flash : ShipDataEntry() {
    override val id: String = "astd_arc_flash"
    override val name: String = shipName(id)
    override val designation: String = "护卫舰"
    override val tech: String = "弧光阵列"
    override val systemId: String = "astd_static_discharge"
    override val fleetPts: Int = 5
    override val hitpoints: Int = 1200
    override val armorRating: Int = 200
    override val maxFlux: Int = 2000
    override val fluxDissipation: Int = 180
    override val ordnancePoints: Int = 55
    override val maxSpeed: Int = 140
    override val acceleration: Int = 70
    override val deceleration: Int = 70
    override val maxTurnRate: Int = 30
    override val turnAcceleration: Int = 60
    override val mass: Int = 200
    override val shieldType: String = "FRONT"
    override val shieldArc: Int = 120
    override val shieldUpkeep: Double = 0.6
    override val shieldEfficiency: Double = 0.8
    override val minCrew: Int = 5
    override val maxCrew: Int = 15
    override val cargo: Int = 15
    override val fuel: Int = 15
    override val fuelPerLy: Int = 1
    override val maxBurn: Int = 10
    override val baseValue: Int = 12000
    override val crPercentPerDay: Double = 10.0
    override val crToDeploy: Double = 20.0
    override val peakCrSec: Int = 120
    override val crLossPerSec: Double = 0.5
    override val suppliesRec: Int = 3
    override val suppliesPerMonth: Int = 3
    override val tags: String = "astd_production"
    override val codexVariantId: String = "astd_arc_flash_Standard"
    override val number: Int = 9110
}
