package cn.kasuminova.astd.sscsv.entries.catalog.shipdata.lens

import cn.kasuminova.astd.sscsv.entries.ShipDataEntry
import cn.kasuminova.astd.sscsv.entries.catalog.shipdata.shipName

/** LENS 设计系舰体数据（ship_data.csv）。 */

object Ship_astd_zw_001 : ShipDataEntry() {
    override val id: String = "astd_zw_001"
    override val name: String = shipName(id)
    override val designation: String = "巡洋舰"
    override val tech: String = "菀星设计局-紫菀"
    override val systemId: String = "astd_jamming_swarm"
    override val fleetPts: Int = 30
    override val hitpoints: Int = 8500
    override val armorRating: Int = 800
    override val maxFlux: Int = 14000
    override val fluxDissipation: Int = 800
    override val ordnancePoints: Int = 150
    override val maxSpeed: Int = 60
    override val acceleration: Int = 30
    override val deceleration: Int = 30
    override val maxTurnRate: Int = 30
    override val turnAcceleration: Int = 60
    override val mass: Int = 16000
    // 决明级：OMNI 全向护盾（240°）+ 4 甲板（与 contents/.ship 一致，阶段一验收要求）。
    override val fighterBays: Int = 4
    override val shieldType: String = "OMNI"
    override val shieldArc: Int = 240
    override val shieldUpkeep: Double = 0.6
    override val shieldEfficiency: Double = 0.6
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
    override val tags: String = "astd_unique"
    override val codexVariantId: String = "astd_zw_001_Standard"
    override val number: Int = 9104
}

object Ship_astd_zw_101 : ShipDataEntry() {
    override val id: String = "astd_zw_101"
    override val name: String = shipName(id)
    override val designation: String = "驱逐舰"
    override val tech: String = "菀星设计局-紫菀"
    override val systemId: String = "astd_targeting_beacon"
    override val fleetPts: Int = 12
    override val hitpoints: Int = 2000
    override val armorRating: Int = 300
    override val maxFlux: Int = 4000
    override val fluxDissipation: Int = 300
    override val ordnancePoints: Int = 60
    override val maxSpeed: Int = 160
    override val acceleration: Int = 80
    override val deceleration: Int = 80
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
    override val tags: String = "astd_production"
    override val codexVariantId: String = "astd_zw_101_Standard"
    override val number: Int = 9105
}

object Ship_astd_zw_102 : ShipDataEntry() {
    override val id: String = "astd_zw_102"
    override val name: String = shipName(id)
    override val designation: String = "主力舰"
    override val tech: String = "菀星设计局-紫菀"
    override val systemId: String = "astd_emergency_recall"
    override val fleetPts: Int = 40
    override val hitpoints: Int = 13000
    override val armorRating: Int = 900
    override val maxFlux: Int = 15000
    override val fluxDissipation: Int = 800
    override val ordnancePoints: Int = 220
    override val fighterBays: Int = 3
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
    override val tags: String = "astd_production"
    override val codexVariantId: String = "astd_zw_102_Standard"
    override val number: Int = 9111
}

object Ship_astd_zw_002 : ShipDataEntry() {
    override val id: String = "astd_zw_002"
    override val name: String = shipName(id)
    override val designation: String = "巡洋舰"
    override val tech: String = "菀星设计局-紫菀"
    override val systemId: String = "astd_em_smoke"
    override val fleetPts: Int = 22
    override val hitpoints: Int = 7000
    override val armorRating: Int = 700
    override val maxFlux: Int = 9000
    override val fluxDissipation: Int = 600
    override val ordnancePoints: Int = 150
    override val maxSpeed: Int = 70
    override val acceleration: Int = 35
    override val deceleration: Int = 35
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
    override val tags: String = "astd_unique"
    override val codexVariantId: String = "astd_zw_002_Standard"
    override val number: Int = 9112
}

object Ship_astd_zw_103 : ShipDataEntry() {
    override val id: String = "astd_zw_103"
    override val name: String = shipName(id)
    override val designation: String = "驱逐舰"
    override val tech: String = "菀星设计局-紫菀"
    override val systemId: String = "astd_drone_surge"
    override val fleetPts: Int = 12
    override val hitpoints: Int = 3000
    override val armorRating: Int = 350
    override val maxFlux: Int = 5000
    override val fluxDissipation: Int = 400
    override val ordnancePoints: Int = 95
    override val maxSpeed: Int = 100
    override val acceleration: Int = 50
    override val deceleration: Int = 50
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
    override val tags: String = "astd_production"
    override val codexVariantId: String = "astd_zw_103_Standard"
    override val number: Int = 9113
}
