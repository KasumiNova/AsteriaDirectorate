package cn.kasuminova.astd.sscsv.entries.catalog.shipdata.lens

import cn.kasuminova.astd.sscsv.entries.ShipDataEntry
import cn.kasuminova.astd.sscsv.entries.catalog.shipdata.shipName

/** LENS 设计系舰体数据（ship_data.csv）。 */

object Ship_astd_gravitational_lens : ShipDataEntry() {
    override val id: String = "astd_gravitational_lens"
    override val name: String = shipName(id)
    override val designation: String = "巡洋舰"
    override val tech: String = "透镜矩阵"
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
    // 引力透镜级：OMNI 全向护盾（240°）+ 4 甲板（与 contents/.ship 一致，阶段一验收要求）。
    override val fighterBays: Int = 4
    override val shieldType: String = "OMNI"
    override val shieldArc: Int = 240
    override val shieldUpkeep: Double = 0.6
    override val shieldEfficiency: Double = 0.6
    override val minCrew: Int = 10
    override val maxCrew: Int = 250
    override val baseValue: Int = 100000
    override val tags: String = "astd_unique"
    override val codexVariantId: String = "astd_gravitational_lens_Standard"
    override val number: Int = 9104
}

object Ship_astd_nebula_echo : ShipDataEntry() {
    override val id: String = "astd_nebula_echo"
    override val name: String = shipName(id)
    override val designation: String = "驱逐舰"
    override val tech: String = "透镜矩阵"
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
    override val baseValue: Int = 100000
    override val tags: String = "astd_unique"
    override val codexVariantId: String = "astd_nebula_echo_Standard"
    override val number: Int = 9105
}

object Ship_astd_magnetosphere_disturbance : ShipDataEntry() {
    override val id: String = "astd_magnetosphere_disturbance"
    override val name: String = shipName(id)
    override val designation: String = "主力舰"
    override val tech: String = "透镜矩阵"
    override val systemId: String = "astd_emergency_recall"
    override val fleetPts: Int = 40
    override val hitpoints: Int = 13000
    override val armorRating: Int = 900
    override val maxFlux: Int = 15000
    override val fluxDissipation: Int = 800
    override val ordnancePoints: Int = 220
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
    override val tags: String = "astd_production"
    override val codexVariantId: String = "astd_magnetosphere_disturbance_Standard"
    override val number: Int = 9111
}

object Ship_astd_dark_tide_nebula : ShipDataEntry() {
    override val id: String = "astd_dark_tide_nebula"
    override val name: String = shipName(id)
    override val designation: String = "巡洋舰"
    override val tech: String = "透镜矩阵"
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
    override val baseValue: Int = 100000
    override val tags: String = "astd_production"
    override val codexVariantId: String = "astd_dark_tide_nebula_Standard"
    override val number: Int = 9112
}

object Ship_astd_diffraction : ShipDataEntry() {
    override val id: String = "astd_diffraction"
    override val name: String = shipName(id)
    override val designation: String = "驱逐舰"
    override val tech: String = "透镜矩阵"
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
    override val baseValue: Int = 100000
    override val tags: String = "astd_production"
    override val codexVariantId: String = "astd_diffraction_Standard"
    override val number: Int = 9113
}

object Ship_astd_flickering_phantom : ShipDataEntry() {
    override val id: String = "astd_flickering_phantom"
    override val name: String = shipName(id)
    override val designation: String = "护卫舰"
    override val tech: String = "透镜矩阵"
    override val systemId: String = "astd_holographic_decoy"
    override val fleetPts: Int = 4
    override val hitpoints: Int = 900
    override val armorRating: Int = 150
    override val maxFlux: Int = 1800
    override val fluxDissipation: Int = 150
    override val ordnancePoints: Int = 45
    override val maxSpeed: Int = 150
    override val acceleration: Int = 75
    override val deceleration: Int = 75
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
    override val tags: String = "astd_production"
    override val codexVariantId: String = "astd_flickering_phantom_Standard"
    override val number: Int = 9114
}
