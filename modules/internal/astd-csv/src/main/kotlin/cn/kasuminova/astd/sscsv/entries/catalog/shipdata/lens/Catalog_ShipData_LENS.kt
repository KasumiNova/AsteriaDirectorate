package cn.kasuminova.astd.sscsv.entries.catalog.shipdata.lens

import cn.kasuminova.astd.sscsv.entries.ShipDataEntry
import cn.kasuminova.astd.sscsv.entries.catalog.shipdata.shipName

object Ship_astd_zw_001 : ShipDataEntry() {
    override val id: String = "astd_zw_001"
    override val name: String = shipName(id)
    override val designation: String = "指挥巡洋舰"
    override val tech: String = "菀星设计局-紫菀"
    override val systemId: String = "astd_grav_timeflow_interference"

    override val fleetPts: Int = 30
    override val hitpoints: Int = 8000
    override val armorRating: Int = 1000
    override val maxFlux: Int = 12000
    override val fluxDissipation: Int = 800
    override val ordnancePoints: Int = 210
    override val maxSpeed: Int = 60
    override val acceleration: Int = 30
    override val deceleration: Int = 30
    override val maxTurnRate: Int = 30
    override val turnAcceleration: Int = 60
    override val mass: Int = 2250

    override val fighterBays: Int = 3

    override val hints: String = "CARRIER, COMBAT, NO_AUTO_ESCORT"
    override val shieldType: String = "OMNI"
    override val shieldArc: Int = 240

    override val shieldUpkeep: Double = 0.4
    override val shieldEfficiency: Double = 0.6
    override val minCrew: Int = 200
    override val maxCrew: Int = 400
    override val cargo: Int = 400
    override val fuel: Int = 300
    override val fuelPerLy: Int = 3
    override val range: Int = 33
    override val maxBurn: Int = 8
    override val baseValue: Int = 600_000
    override val crPercentPerDay: Double = 3.0
    override val crToDeploy: Double = 12.0
    override val peakCrSec: Int = 720
    override val crLossPerSec: Double = 0.25
    override val suppliesRec: Int = 30
    override val suppliesPerMonth: Int = 30
    override val tags: String = "astd_unique"
    override val codexVariantId: String = "astd_zw_001_Empty"
    override val number: Int = 9104
}

object Ship_astd_zw_101 : ShipDataEntry() {
    override val id: String = "astd_zw_101"
    override val name: String = shipName(id)
    override val designation: String = "相位驱逐舰"
    override val tech: String = "菀星设计局-紫菀"
    override val systemId: String = "astd_grav_replicator"

    override val fleetPts: Int = 15
    override val hitpoints: Int = 5000
    override val armorRating: Int = 700
    override val maxFlux: Int = 8500
    override val fluxDissipation: Int = 550
    override val ordnancePoints: Int = 95
    override val maxSpeed: Int = 130
    override val acceleration: Int = 100
    override val deceleration: Int = 100
    override val maxTurnRate: Int = 50
    override val turnAcceleration: Int = 80
    override val mass: Int = 2000
    override val hints: String = "PHASE"
    override val shieldType: String = "PHASE"
    override val defenseId: String = "astd_gravity_phase"
    override val phaseCost: Double = 0.0235
    override val phaseUpkeep: Double = 0.0235
    override val minCrew: Int = 10
    override val maxCrew: Int = 250
    override val cargo: Int = 60
    override val fuel: Int = 80
    override val fuelPerLy: Int = 2
    override val range: Int = 40
    override val maxBurn: Int = 9
    override val baseValue: Int = 160_000
    override val crPercentPerDay: Double = 5.0
    override val crToDeploy: Double = 20.0
    override val peakCrSec: Int = 420
    override val crLossPerSec: Double = 0.25
    override val suppliesRec: Int = 18
    override val suppliesPerMonth: Int = 18
    override val tags: String = "astd_production"
    override val codexVariantId: String = "astd_zw_101_Empty"
    override val number: Int = 9105
}

object Ship_astd_zw_102 : ShipDataEntry() {
    override val id: String = "astd_zw_102"
    override val name: String = shipName(id)
    override val designation: String = "航空战列舰"
    override val tech: String = "菀星设计局-紫菀"
    override val systemId: String = "astd_fighter_grav_link"

    override val fleetPts: Int = 35
    override val hitpoints: Int = 12000
    override val armorRating: Int = 1200
    override val maxFlux: Int = 18000
    override val fluxDissipation: Int = 1200
    override val ordnancePoints: Int = 340
    override val fighterBays: Int = 3
    override val maxSpeed: Int = 50
    override val acceleration: Int = 20
    override val deceleration: Int = 30
    override val maxTurnRate: Int = 30
    override val turnAcceleration: Int = 35
    override val mass: Int = 3500

    override val shieldType: String = "OMNI"
    override val shieldArc: Int = 240

    override val shieldUpkeep: Double = 0.5333
    override val shieldEfficiency: Double = 0.6

    override val hints: String = "CARRIER, COMBAT, NO_AUTO_ESCORT"
    override val minCrew: Int = 500
    override val maxCrew: Int = 1000
    override val cargo: Int = 1000
    override val fuel: Int = 750
    override val fuelPerLy: Int = 10
    override val range: Int = 30
    override val maxBurn: Int = 7
    override val baseValue: Int = 500_000
    override val crPercentPerDay: Double = 4.0
    override val crToDeploy: Double = 20.0
    override val peakCrSec: Int = 920
    override val crLossPerSec: Double = 0.25
    override val suppliesRec: Int = 55
    override val suppliesPerMonth: Int = 55
    override val tags: String = "astd_production"
    override val codexVariantId: String = "astd_zw_102_Empty"
    override val number: Int = 9111
}

object Ship_astd_zw_002 : ShipDataEntry() {
    override val id: String = "astd_zw_002"
    override val name: String = shipName(id)
    override val designation: String = "相位巡洋舰"
    override val tech: String = "菀星设计局-紫菀"
    override val systemId: String = "astd_grav_storm"

    override val fleetPts: Int = 22
    override val hitpoints: Int = 8000
    override val armorRating: Int = 1200
    override val maxFlux: Int = 12000
    override val fluxDissipation: Int = 900
    override val ordnancePoints: Int = 165
    override val maxSpeed: Int = 80
    override val acceleration: Int = 60
    override val deceleration: Int = 60
    override val maxTurnRate: Int = 60
    override val turnAcceleration: Int = 80
    override val mass: Int = 1500

    // hints 必须带 PHASE：原版 ShipHullSpec.isPhase() 仅在 hints 含 PHASE 或 defense id 恰为
    // 原版 "phasecloak" 时返回 true，自定义相位系统 id 不走第二个分支。
    override val hints: String = "PHASE"
    override val shieldType: String = "PHASE"
    override val defenseId: String = "astd_gravity_phase"

    override val phaseCost: Double = 0.0333
    override val phaseUpkeep: Double = 0.0333
    override val minCrew: Int = 100
    override val maxCrew: Int = 300
    override val cargo: Int = 300
    override val fuel: Int = 200
    override val fuelPerLy: Int = 3
    override val range: Int = 33
    override val maxBurn: Int = 8
    override val baseValue: Int = 800_000
    override val crPercentPerDay: Double = 3.0
    override val crToDeploy: Double = 12.0
    override val peakCrSec: Int = 720
    override val crLossPerSec: Double = 0.25
    override val suppliesRec: Int = 40
    override val suppliesPerMonth: Int = 40
    override val tags: String = "astd_unique"
    override val codexVariantId: String = "astd_zw_002_Empty"
    override val number: Int = 9112
}

object Ship_astd_zw_103 : ShipDataEntry() {
    override val id: String = "astd_zw_103"
    override val name: String = shipName(id)
    override val designation: String = "相位护航航母"
    override val tech: String = "菀星设计局-紫菀"
    override val systemId: String = "astd_grav_rift_generator"

    override val fleetPts: Int = 15
    override val hitpoints: Int = 5000
    override val armorRating: Int = 800
    override val maxFlux: Int = 8000
    override val fluxDissipation: Int = 600
    override val ordnancePoints: Int = 115
    override val fighterBays: Int = 2
    override val maxSpeed: Int = 110
    override val acceleration: Int = 80
    override val deceleration: Int = 80
    override val maxTurnRate: Int = 60
    override val turnAcceleration: Int = 80
    override val mass: Int = 1750

    override val hints: String = "PHASE, CARRIER"
    override val shieldType: String = "PHASE"
    override val defenseId: String = "astd_gravity_phase"

    override val phaseCost: Double = 0.025
    override val phaseUpkeep: Double = 0.025
    override val minCrew: Int = 20
    override val maxCrew: Int = 120
    override val cargo: Int = 100
    override val fuel: Int = 60
    override val fuelPerLy: Int = 2
    override val range: Int = 40
    override val maxBurn: Int = 9
    override val baseValue: Int = 180_000
    override val crPercentPerDay: Double = 5.0
    override val crToDeploy: Double = 15.0
    override val peakCrSec: Int = 480
    override val crLossPerSec: Double = 0.25
    override val suppliesRec: Int = 22
    override val suppliesPerMonth: Int = 22
    override val tags: String = "astd_production"
    override val codexVariantId: String = "astd_zw_103_Empty"
    override val number: Int = 9113
}

object Ship_astd_zw_103_fighter : ShipDataEntry() {
    override val id: String = "astd_zw_103_fighter"
    override val name: String = shipName(id)
    override val designation: String = "重型战斗机"
    override val tech: String = "菀星设计局-紫菀"
    override val systemId: String = "astd_burst_flow_fighter"
    override val hitpoints: Int = 1000
    override val armorRating: Int = 300
    override val maxFlux: Int = 1500
    override val fluxDissipation: Int = 300
    override val maxSpeed: Int = 200
    override val acceleration: Int = 400
    override val deceleration: Int = 350
    override val maxTurnRate: Int = 90
    override val turnAcceleration: Int = 180
    override val mass: Int = 100
    override val shieldType: String = "FRONT"
    override val shieldArc: Int = 270
    override val shieldUpkeep: Double = 0.3
    override val shieldEfficiency: Double = 0.6
    override val minCrew: Int = 0
    override val maxCrew: Int = 0
    override val number: Int = 9121
}

object Ship_astd_gemini_bomber : ShipDataEntry() {
    override val id: String = "astd_gemini_bomber"
    override val name: String = shipName(id)
    override val designation: String = "轰炸机"
    override val tech: String = "菀星设计局-紫菀"
    override val hitpoints: Int = 800
    override val armorRating: Int = 200
    override val maxFlux: Int = 800
    override val fluxDissipation: Int = 80
    override val maxSpeed: Int = 150
    override val acceleration: Int = 300
    override val deceleration: Int = 250
    override val maxTurnRate: Int = 60
    override val turnAcceleration: Int = 120
    override val mass: Int = 80
    override val shieldType: String = "FRONT"
    override val shieldArc: Int = 240
    override val shieldUpkeep: Double = 0.3
    override val shieldEfficiency: Double = 0.6
    override val minCrew: Int = 0
    override val maxCrew: Int = 0
    override val number: Int = 9130
}

object Ship_astd_surge_fighter : ShipDataEntry() {
    override val id: String = "astd_surge_fighter"
    override val name: String = shipName(id)
    override val designation: String = "战斗机"
    override val tech: String = "菀星设计局-紫菀"
    override val systemId: String = "plasmajets"
    override val hitpoints: Int = 500
    override val armorRating: Int = 150
    override val maxFlux: Int = 900
    override val fluxDissipation: Int = 300
    override val maxSpeed: Int = 250
    override val acceleration: Int = 500
    override val deceleration: Int = 400
    override val maxTurnRate: Int = 100
    override val turnAcceleration: Int = 180
    override val mass: Int = 40
    override val shieldType: String = "FRONT"
    override val shieldArc: Int = 270
    override val shieldUpkeep: Double = 0.3
    override val shieldEfficiency: Double = 0.6
    override val minCrew: Int = 0
    override val maxCrew: Int = 0
    override val number: Int = 9131
}

object Ship_astd_gravwell_interceptor : ShipDataEntry() {
    override val id: String = "astd_gravwell_interceptor"
    override val name: String = shipName(id)
    override val designation: String = "支援战斗机"
    override val tech: String = "菀星设计局-紫菀"
    override val systemId: String = "displacer"
    override val hitpoints: Int = 500
    override val armorRating: Int = 100
    override val maxFlux: Int = 900
    override val fluxDissipation: Int = 300
    override val maxSpeed: Int = 150
    override val acceleration: Int = 300
    override val deceleration: Int = 250
    override val maxTurnRate: Int = 60
    override val turnAcceleration: Int = 120
    override val mass: Int = 50
    override val shieldType: String = "OMNI"
    override val shieldArc: Int = 180
    override val shieldUpkeep: Double = 0.3
    override val shieldEfficiency: Double = 0.6
    override val minCrew: Int = 0
    override val maxCrew: Int = 0
    override val number: Int = 9132
}
