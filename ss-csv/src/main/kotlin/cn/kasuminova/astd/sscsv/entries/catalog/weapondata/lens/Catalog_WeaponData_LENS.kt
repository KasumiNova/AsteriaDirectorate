package cn.kasuminova.astd.sscsv.entries.catalog.weapondata.lens

import cn.kasuminova.astd.sscsv.entries.AiHint
import cn.kasuminova.astd.sscsv.entries.WeaponDataEntry
import cn.kasuminova.astd.sscsv.entries.catalog.weapondata.weaponName
import cn.kasuminova.astd.sscsv.i18n.SsI18n
import cn.kasuminova.astd.sscsv.outputs.proj.MissileEngineSlot
import cn.kasuminova.astd.sscsv.outputs.proj.MissileEngineSlotStyleSpec
import cn.kasuminova.astd.sscsv.outputs.proj.MissileEngineSpec
import cn.kasuminova.astd.sscsv.outputs.proj.MissileProjSpec
import cn.kasuminova.astd.sscsv.outputs.proj.ProjectileProjSpec
import cn.kasuminova.astd.sscsv.outputs.proj.ProjectileSpawnType
import cn.kasuminova.astd.sscsv.outputs.proj.Rgba
import cn.kasuminova.astd.sscsv.outputs.proj.SsProjMissileOutputs
import cn.kasuminova.astd.sscsv.outputs.proj.SsProjProjectileOutputs
import cn.kasuminova.astd.sscsv.outputs.proj.Vec2
import cn.kasuminova.astd.sscsv.outputs.proj.Vec2i

/** LENS 系武器（weapon_data.csv）。 */

/** Gravitational Lens：整船 bloom 描边层（装配界面/战斗 decorative outline）。 */
object Wpn_astd_gravitational_lens_bloom : WeaponDataEntry() {
    override val id: String = "astd_gravitational_lens_bloom"
    override val name: String = weaponName(id)
    override val tier: Int = 5
    override val baseValue: Int = 0
    override val range: Int = 0
    override val turnRate: Int = 0
    override val type: String = "OTHER"
    override val tags: String = "no_drop, no_drop_salvage"
    override val tech: String = "透镜矩阵"
    override val noDpsInTooltip: Boolean = true
    override val number: Int = 9206
}

/** GCP 系列：引力坍缩炮（可装配版本；以持续命中坍缩 tick + AOE + 引力撕裂为核心机制）。 */
object Wpn_astd_gcp12 : WeaponDataEntry() {
    override val id: String = "astd_gcp12"
    override val name: String = weaponName(id)
    override val tier: Int = 3
    override val baseValue: Int = 24000
    override val range: Int = 1100

    // Burst beam：爆发伤害=3600，发射时间=3s => 束内 DPS=1200
    override val damagePerSecond: Int = 1200
    override val damagePerShot: Int = 0
    override val emp: Int = 0
    override val impact: Int = 0

    override val turnRate: Int = 20
    override val ops: Int = 22
    override val type: String = "HIGH_EXPLOSIVE"

    // 维持与旧版大致一致的耗幅比（~1.2 flux/dmg）
    override val energyPerSecond: Int = 1440

    override val chargeup: Double = 1.5
    override val chargedown: Double = 0.6

    // Beam 的 burst size/ delay 对应 burstDuration / burstCooldown
    override val burstSize: Double = 3.0
    override val burstDelay: Double = 8.0

    override val beamSpeed: Int = 10000
    override val projSpeed: Int = 0

    override val tags: String = "astd_production"
    override val groupTag: String = "astd"
    override val tech: String = "透镜矩阵"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val customPrimary: String = SsI18n.t("weapon.$id.tooltip.customPrimary")
    override val customPrimaryHL: String = SsI18n.t("weapon.$id.tooltip.customPrimaryHL")
    override val noDpsInTooltip: Boolean = false
    override val number: Int = 9202
}

object Wpn_astd_gcp8 : WeaponDataEntry() {
    override val id: String = "astd_gcp8"
    override val name: String = weaponName(id)
    override val tier: Int = 2
    override val baseValue: Int = 12000
    override val range: Int = 900

    // Burst beam：爆发伤害=1600，发射时间=2s => 束内 DPS=800
    override val damagePerSecond: Int = 800
    override val damagePerShot: Int = 0
    override val emp: Int = 0
    override val impact: Int = 0

    override val turnRate: Int = 30
    override val ops: Int = 14
    override val type: String = "HIGH_EXPLOSIVE"
    override val energyPerSecond: Int = 960

    override val chargeup: Double = 1.0
    override val chargedown: Double = 0.45
    override val burstSize: Double = 2.0
    override val burstDelay: Double = 6.0

    override val beamSpeed: Int = 10000
    override val projSpeed: Int = 0

    override val tags: String = "astd_production"
    override val groupTag: String = "astd"
    override val tech: String = "透镜矩阵"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val customPrimary: String = SsI18n.t("weapon.$id.tooltip.customPrimary")
    override val customPrimaryHL: String = SsI18n.t("weapon.$id.tooltip.customPrimaryHL")
    override val noDpsInTooltip: Boolean = false
    override val number: Int = 9203
}

object Wpn_astd_gcp4 : WeaponDataEntry() {
    override val id: String = "astd_gcp4"
    override val name: String = weaponName(id)
    override val tier: Int = 1
    override val baseValue: Int = 6000
    override val range: Int = 700

    // Burst beam：爆发伤害=700，发射时间=1.5s => 束内 DPS≈466.7（取 467）
    override val damagePerSecond: Int = 467
    override val damagePerShot: Int = 0
    override val emp: Int = 0
    override val impact: Int = 0

    override val turnRate: Int = 30
    override val ops: Int = 8
    override val type: String = "HIGH_EXPLOSIVE"
    override val energyPerSecond: Int = 584

    override val chargeup: Double = 0.5
    override val chargedown: Double = 0.35
    override val burstSize: Double = 1.5
    override val burstDelay: Double = 4.0

    override val beamSpeed: Int = 10000
    override val projSpeed: Int = 0

    override val tags: String = "astd_production"
    override val groupTag: String = "astd"
    override val tech: String = "透镜矩阵"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val customPrimary: String = SsI18n.t("weapon.$id.tooltip.customPrimary")
    override val customPrimaryHL: String = SsI18n.t("weapon.$id.tooltip.customPrimaryHL")
    override val noDpsInTooltip: Boolean = false
    override val number: Int = 9204
}

object Wpn_astd_gcp2 : WeaponDataEntry() {
    override val id: String = "astd_gcp2"
    override val name: String = weaponName(id)
    override val tier: Int = 1
    override val baseValue: Int = 5000
    override val range: Int = 550

    // Burst beam：爆发伤害=300，发射时间=1s => 束内 DPS=300
    override val damagePerSecond: Int = 300
    override val damagePerShot: Int = 0
    override val emp: Int = 0
    override val impact: Int = 0

    override val turnRate: Int = 60
    override val ops: Int = 6
    override val type: String = "HIGH_EXPLOSIVE"
    override val energyPerSecond: Int = 400

    override val chargeup: Double = 0.5
    override val chargedown: Double = 0.25
    override val burstSize: Double = 1.0
    override val burstDelay: Double = 2.0

    override val beamSpeed: Int = 10000
    override val projSpeed: Int = 0

    override val aiHints: Set<AiHint> = setOf(AiHint.PD)
    override val tags: String = "astd_production"
    override val groupTag: String = "astd"
    override val tech: String = "透镜矩阵"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val customPrimary: String = SsI18n.t("weapon.$id.tooltip.customPrimary")
    override val customPrimaryHL: String = SsI18n.t("weapon.$id.tooltip.customPrimaryHL")
    override val noDpsInTooltip: Boolean = false
    override val number: Int = 9205
}

/** 湮灭涡旋（规格 04 §1.1）：爆发光束终点展开引力涡旋，牵引/吞噬敌方射弹导弹，停火坍缩转化为范围能量伤害。 */
object Wpn_astd_annihilation_vortex : WeaponDataEntry() {
    override val id: String = "astd_annihilation_vortex"
    override val name: String = weaponName(id)
    override val tier: Int = 3
    override val baseValue: Int = 36000
    override val range: Int = 900

    // Burst beam：爆发伤害=1600，发射时间=2s => 束内 DPS=800
    override val damagePerSecond: Int = 800
    override val damagePerShot: Int = 0
    override val emp: Int = 0
    override val impact: Int = 0

    override val turnRate: Int = 20
    override val ops: Int = 30
    override val type: String = "ENERGY"

    // 爆发辐能 3200 / 2s（设计案）
    override val energyPerSecond: Int = 1600

    override val chargeup: Double = 0.0
    override val chargedown: Double = 0.0

    // Beam 的 burst size/ delay 对应 burstDuration（爆发 2s）/ burstCooldown（冷却 9s）
    override val burstSize: Double = 2.0
    override val burstDelay: Double = 9.0

    override val beamSpeed: Int = 10000
    override val projSpeed: Int = 0

    // P6 前口径（首批计划 §14）；P6 后改赏金掉落，另立任务
    override val tags: String = "no_drop, no_drop_salvage"
    override val groupTag: String = "astd"
    override val tech: String = "透镜矩阵"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val customPrimary: String = SsI18n.t("weapon.$id.tooltip.customPrimary")
    override val customPrimaryHL: String = SsI18n.t("weapon.$id.tooltip.customPrimaryHL")
    override val noDpsInTooltip: Boolean = false
    override val number: Int = 9220
}
