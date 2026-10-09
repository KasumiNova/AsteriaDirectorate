package cn.kasuminova.astd.sscsv.entries.catalog.shipdata.lens

import cn.kasuminova.astd.sscsv.entries.ShipDataEntry
import cn.kasuminova.astd.sscsv.entries.catalog.shipdata.shipName

/** LENS 设计系舰体数据（ship_data.csv）。 */

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

    // 对齐原版航空战列舰（Legion）与本舰 zw_102 判例 hints "CARRIER, COMBAT, NO_AUTO_ESCORT"：
    // 决明为 4 甲板母舰 + 半巡洋平台（purple/10-unique.md §1 定位「锚点控场型指挥舰 / 4 甲板母舰」），
    // 需要 CARRIER 令图鉴分类与 ShipHullSpec.isCarrier() 成立；其电战潮汐/引力场均为自身中圈机制，
    // 缺 COMBAT + NO_AUTO_ESCORT 时原版 BasicShipAI 会让航母自动护航友舰并回避交战，
    // 导致旗舰脱离战场中圈、机制半径形同虚设（判例注释详见 [Ship_astd_zw_102]）。
    override val hints: String = "CARRIER, COMBAT, NO_AUTO_ESCORT"
    override val shieldType: String = "OMNI"
    override val shieldArc: Int = 240

    // 护盾维持 360 = 耗散 900 × 0.4。
    override val shieldUpkeep: Double = 0.4
    override val shieldEfficiency: Double = 0.6
    override val minCrew: Int = 200
    override val maxCrew: Int = 400
    override val cargo: Int = 400
    override val fuel: Int = 300
    override val fuelPerLy: Int = 3
    override val range: Int = 33
    override val maxBurn: Int = 8
    override val baseValue: Int = 100000
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

/**
 * 舜华级（ZW-101）：相位驱逐舰·量产档（purple/20-production.md §3，2026-09 D27 全重做）。
 *
 * 防御方式由 FRONT 护盾改为相位（复用紫菀防御系统「引力相位」astd_gravity_phase），
 * hints 必须带 PHASE（原版 ShipHullSpec.isPhase() 判定口径，见 [Ship_astd_zw_002] 注释）。
 * 相位激活/维持 200 辐能 = 辐能容量比例 200/8500 ≈ 0.0235。
 */
object Ship_astd_zw_101 : ShipDataEntry() {
    override val id: String = "astd_zw_101"
    override val name: String = shipName(id)
    override val designation: String = "相位驱逐舰"
    override val tech: String = "菀星设计局-紫菀"
    override val systemId: String = "astd_grav_replicator"

    // 自动战斗分数对齐原版相位驱逐舰（灾星 fp=15）；部署点 20 由 supplies/rec 承担。
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
    override val baseValue: Int = 100000
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

    // 护盾维持 640 = 耗散 1200 * 0.5333
    override val shieldUpkeep: Double = 0.5333
    override val shieldEfficiency: Double = 0.6

    // 对齐原版军团级（Legion，航空战列舰）hints 判例 "CARRIER, COMBAT, NO_AUTO_ESCORT"：
    // - CARRIER：图鉴「航母」分类的判定 hint（原版 ShipBlueprintRow 按 hints 分类），
    //   同时令 ShipHullSpec.isCarrier() 为 true；
    // - COMBAT + NO_AUTO_ESCORT：战斗航母口径。原版 BasicShipAI 对 CARRIER 舰默认走
    //   「自动护航友舰/回避交战」机动（hints 无 NO_AUTO_ESCORT 且甲板完好数 ≥ 66% 时
    //   触发 EscortTarget 机动并抢占目标选择），OrderResponseModule 对无 COMBAT 的航母
    //   不响应 STRIKE 类任务的交战引导；缺这两个 hint 会导致飞蓬 AI 倾向脱离战场。
    override val hints: String = "CARRIER, COMBAT, NO_AUTO_ESCORT"
    override val minCrew: Int = 500
    override val maxCrew: Int = 1000
    override val cargo: Int = 1000
    override val fuel: Int = 750
    override val fuelPerLy: Int = 10
    override val range: Int = 30
    override val maxBurn: Int = 7
    override val baseValue: Int = 100000
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

    // 自动战斗分数对齐原版相位巡洋舰（厄运 fp=22）；部署点 40 由 supplies/rec 承担。
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

    // 密蒙：相位化改造——防御方式由 FRONT 护盾改为自定义相位系统「引力相位」
    // （defense id=astd_gravity_phase，stats 脚本 GravityPhaseCloakStats 为后续特效接入点）。
    // hints 必须带 PHASE：原版 ShipHullSpec.isPhase() 仅在 hints 含 PHASE 或
    // defense id 恰为原版 "phasecloak" 时返回 true，自定义相位系统 id 不走第二个分支，
    // 缺此 hint 会导致装配面板按护盾舰显示、相位船插不可安装、图鉴不收录。
    override val hints: String = "PHASE"
    override val shieldType: String = "PHASE"
    override val defenseId: String = "astd_gravity_phase"

    // 相位激活/维持辐能均为 400：ship_data 的 phase cost/upkeep 是辐能容量比例（400/12000）。
    override val phaseCost: Double = 0.0333
    override val phaseUpkeep: Double = 0.0333
    override val minCrew: Int = 100
    override val maxCrew: Int = 300
    override val cargo: Int = 300
    override val fuel: Int = 200
    override val fuelPerLy: Int = 3
    override val range: Int = 33
    override val maxBurn: Int = 8
    override val baseValue: Int = 100000
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

/**
 * 茑萝级（ZW-103）：相位护航航母（purple/20-production.md §2，2026-09 D27 全重做）。
 *
 * 防御方式由 FRONT 护盾改为相位（复用紫菀防御系统「引力相位」astd_gravity_phase），
 * hints 必须带 PHASE（原版 ShipHullSpec.isPhase() 判定口径，见 [Ship_astd_zw_002] 注释）。
 * 2 个内置机库（variant 固定内置战机联队），槽位仅 2x 中型能量（全向）。
 * 相位激活/维持 200 辐能 = 辐能容量比例 200/8000 = 0.025。
 */
object Ship_astd_zw_103 : ShipDataEntry() {
    override val id: String = "astd_zw_103"
    override val name: String = shipName(id)
    override val designation: String = "相位护航航母"
    override val tech: String = "菀星设计局-紫菀"
    override val systemId: String = "astd_grav_rift_generator"

    // 自动战斗分数对齐原版相位驱逐舰（灾星 fp=15）；部署点 24 由 supplies/rec 承担。
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
    override val mass: Int = 8000

    // PHASE：自定义相位系统 id 不走原版 isPhase() 的 "phasecloak" 分支，必须显式声明
    // （见 [Ship_astd_zw_002] 注释）。CARRIER：护航航母定位（2 内置机库），对齐原版纯航母
    // （Astral/苍鹭/兀鹫 hints 仅 CARRIER）——相位护航航母舰体脆弱、不抢交战引导，
    // 故不加 COMBAT / NO_AUTO_ESCORT（与 zw_102 战斗航母口径区分）。
    override val hints: String = "PHASE, CARRIER"
    override val shieldType: String = "PHASE"
    override val defenseId: String = "astd_gravity_phase"

    // 相位激活/维持辐能均为 200：ship_data 的 phase cost/upkeep 是辐能容量比例（200/8000）。
    override val phaseCost: Double = 0.025
    override val phaseUpkeep: Double = 0.025
    override val minCrew: Int = 20
    override val maxCrew: Int = 120
    override val cargo: Int = 100
    override val fuel: Int = 60
    override val fuelPerLy: Int = 2
    override val range: Int = 40
    override val maxBurn: Int = 9
    override val baseValue: Int = 100000
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

/**
 * 茑萝级内置相位无人战机「游丝」（purple/20-production.md §2 内置战机）。
 */
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
    override val mass: Int = 30
    override val shieldType: String = "FRONT"
    override val shieldArc: Int = 270
    override val shieldUpkeep: Double = 0.3
    override val shieldEfficiency: Double = 0.6
    override val minCrew: Int = 0
    override val maxCrew: Int = 0
    override val number: Int = 9121
}

/**
 * 双子座（LENS 量产轰炸联队单机，purple/30-fighters.md §双子座 v1 定案）。
 */
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
    override val mass: Int = 40
    override val shieldType: String = "FRONT"
    override val shieldArc: Int = 240
    override val shieldUpkeep: Double = 0.3
    override val shieldEfficiency: Double = 0.6
    override val minCrew: Int = 0
    override val maxCrew: Int = 0
    override val number: Int = 9130
}

/**
 * 电涌（LENS 量产战斗联队单机）。
 */
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
    override val mass: Int = 25
    override val shieldType: String = "FRONT"
    override val shieldArc: Int = 270
    override val shieldUpkeep: Double = 0.3
    override val shieldEfficiency: Double = 0.6
    override val minCrew: Int = 0
    override val maxCrew: Int = 0
    override val number: Int = 9131
}

/**
 * 引力井（LENS 量产支援联队单机，purple/30-fighters.md §引力井 v1 定案）。
 *
 * 3 机编组无人支援战斗机：挂战机版小型 GCP PD（数据全量复用舰装版 astd_gcp2，仅不渲染贴图），
 * wing role=SUPPORT、支援范围 2000（贴身护卫行为走原版支援战斗机原生 AI，标签显示为「最大支援范围」）。
 * 护盾全向 180° 效率 0.6；战术系统为原版闪现（displacer）。
 */
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
    override val mass: Int = 35
    override val shieldType: String = "OMNI"
    override val shieldArc: Int = 180
    override val shieldUpkeep: Double = 0.3
    override val shieldEfficiency: Double = 0.6
    override val minCrew: Int = 0
    override val maxCrew: Int = 0
    override val number: Int = 9132
}
