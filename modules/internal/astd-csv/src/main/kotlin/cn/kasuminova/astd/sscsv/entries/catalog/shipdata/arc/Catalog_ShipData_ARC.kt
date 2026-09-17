package cn.kasuminova.astd.sscsv.entries.catalog.shipdata.arc

import cn.kasuminova.astd.sscsv.entries.ShipDataEntry
import cn.kasuminova.astd.sscsv.entries.catalog.shipdata.shipName

/** ARC 设计系舰体数据（ship_data.csv）。 */

object Ship_astd_xc_001 : ShipDataEntry() {
    override val id: String = "astd_xc_001"
    override val name: String = shipName(id)
    override val designation: String = "主力舰"
    override val tech: String = "菀星设计局-星坠"
    override val systemId: String = "astd_xc_001_overdrive_crewed"
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
    override val codexVariantId: String = "astd_xc_001_Standard"
    override val number: Int = 9101
}

object Ship_astd_xc_002 : ShipDataEntry() {
    override val id: String = "astd_xc_002"
    override val name: String = shipName(id)
    override val designation: String = "驱逐舰"
    override val tech: String = "菀星设计局-星坠"
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
    override val codexVariantId: String = "astd_xc_002_Standard"
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

object Ship_astd_xc_102 : ShipDataEntry() {
    override val id: String = "astd_xc_102"
    override val name: String = shipName(id)
    override val designation: String = "主力舰"
    override val tech: String = "菀星设计局-星坠"
    override val systemId: String = "astd_arc_shared_flux_network"
    override val fleetPts: Int = 55
    override val hitpoints: Int = 20000
    override val armorRating: Int = 1800
    override val maxFlux: Int = 25000
    override val fluxDissipation: Int = 1400
    override val ordnancePoints: Int = 360
    override val maxSpeed: Int = 40
    override val acceleration: Int = 15
    override val deceleration: Int = 15
    override val maxTurnRate: Int = 20
    override val turnAcceleration: Int = 40
    override val mass: Int = 4200
    override val shieldType: String = "OMNI"
    override val shieldArc: Int = 240
    // 护盾维持 750 = 耗散 1400 × 0.5357。
    override val shieldUpkeep: Double = 0.5357
    override val shieldEfficiency: Double = 0.70
    override val minCrew: Int = 400
    override val maxCrew: Int = 800
    override val cargo: Int = 800
    override val fuel: Int = 600
    override val fuelPerLy: Int = 10
    override val range: Int = 30
    override val maxBurn: Int = 8
    override val baseValue: Int = 300000
    override val crPercentPerDay: Double = 3.0
    override val crToDeploy: Double = 15.0
    override val peakCrSec: Int = 720
    override val crLossPerSec: Double = 0.25
    override val suppliesRec: Int = 60
    override val suppliesPerMonth: Int = 60
    override val tags: String = "astd_production"
    override val codexVariantId: String = "astd_xc_102_Standard"
    override val number: Int = 9107
}

object Ship_astd_xc_101 : ShipDataEntry() {
    override val id: String = "astd_xc_101"
    override val name: String = shipName(id)
    override val designation: String = "巡洋舰"
    override val tech: String = "菀星设计局-星坠"
    override val systemId: String = "astd_plasma_armor_shield_boost"
    override val fleetPts: Int = 32
    override val hitpoints: Int = 13000
    override val armorRating: Int = 1600
    override val maxFlux: Int = 14000
    override val fluxDissipation: Int = 750
    override val ordnancePoints: Int = 185
    override val maxSpeed: Int = 40
    override val acceleration: Int = 15
    override val deceleration: Int = 15
    override val maxTurnRate: Int = 10
    override val turnAcceleration: Int = 20
    override val mass: Int = 2250
    override val shieldType: String = "FRONT"
    override val shieldArc: Int = 360
    // 护盾维持 420 = 耗散 750 × 0.56。
    override val shieldUpkeep: Double = 0.56
    override val shieldEfficiency: Double = 1.00
    override val minCrew: Int = 150
    override val maxCrew: Int = 300
    override val cargo: Int = 400
    override val fuel: Int = 300
    override val fuelPerLy: Int = 3
    override val range: Int = 33
    override val maxBurn: Int = 8
    override val baseValue: Int = 100000
    override val crPercentPerDay: Double = 3.0
    override val crToDeploy: Double = 12.0
    override val peakCrSec: Int = 600
    override val crLossPerSec: Double = 0.25
    override val suppliesRec: Int = 26
    override val suppliesPerMonth: Int = 26
    override val tags: String = "astd_production"
    override val codexVariantId: String = "astd_xc_101_Standard"
    override val number: Int = 9108
}

object Ship_astd_xc_103 : ShipDataEntry() {
    override val id: String = "astd_xc_103"
    override val name: String = shipName(id)
    override val designation: String = "驱逐舰"
    override val tech: String = "菀星设计局-星坠"
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
    override val codexVariantId: String = "astd_xc_103_Standard"
    override val number: Int = 9109
}

object Ship_astd_xc_104 : ShipDataEntry() {
    override val id: String = "astd_xc_104"
    override val name: String = shipName(id)
    override val designation: String = "护卫舰"
    override val tech: String = "菀星设计局-星坠"
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
    override val codexVariantId: String = "astd_xc_104_Standard"
    override val number: Int = 9110
}
