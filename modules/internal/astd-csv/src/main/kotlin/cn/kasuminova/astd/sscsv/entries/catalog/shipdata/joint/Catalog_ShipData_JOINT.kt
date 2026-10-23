package cn.kasuminova.astd.sscsv.entries.catalog.shipdata.joint

import cn.kasuminova.astd.sscsv.entries.ShipDataEntry
import cn.kasuminova.astd.sscsv.entries.catalog.shipdata.shipName

object Ship_astd_lh_001 : ShipDataEntry() {
    override val id: String = "astd_lh_001"
    override val name: String = shipName(id)
    override val designation: String = "先进炮艇"
    override val tech: String = "菀星设计局-星坠"
    override val systemId: String = "astd_lh_001_burst_flow"
    override val fleetPts: Int = 15
    override val hitpoints: Int = 3000
    override val armorRating: Int = 400
    override val maxFlux: Int = 7000
    override val fluxDissipation: Int = 600
    override val ordnancePoints: Int = 60
    override val maxSpeed: Int = 120
    override val acceleration: Int = 175
    override val deceleration: Int = 150
    override val maxTurnRate: Int = 50
    override val turnAcceleration: Int = 100
    override val mass: Int = 250
    override val shieldType: String = "OMNI"
    override val shieldArc: Int = 240

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
    override val codexVariantId: String = "astd_lh_001_Empty"
    override val number: Int = 9119
}

object Ship_astd_lh_002 : ShipDataEntry() {
    override val id: String = "astd_lh_002"
    override val name: String = shipName(id)
    override val designation: String = "先进炮艇"
    override val tech: String = "菀星设计局-紫菀"
    override val systemId: String = "astd_lh_002_vision_shift"
    override val fleetPts: Int = 15
    override val hitpoints: Int = 3000
    override val armorRating: Int = 400
    override val maxFlux: Int = 9000
    override val fluxDissipation: Int = 450
    override val ordnancePoints: Int = 60
    override val maxSpeed: Int = 100
    override val acceleration: Int = 175
    override val deceleration: Int = 150
    override val maxTurnRate: Int = 50
    override val turnAcceleration: Int = 100
    override val mass: Int = 250
    override val shieldType: String = "OMNI"
    override val shieldArc: Int = 240

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
    override val codexVariantId: String = "astd_lh_002_Empty"
    override val number: Int = 9120
}
