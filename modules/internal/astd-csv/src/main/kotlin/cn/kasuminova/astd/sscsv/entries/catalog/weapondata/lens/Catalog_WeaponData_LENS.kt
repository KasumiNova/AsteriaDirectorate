package cn.kasuminova.astd.sscsv.entries.catalog.weapondata.lens

import cn.kasuminova.astd.sscsv.entries.AiHint
import cn.kasuminova.astd.sscsv.entries.WeaponDataEntry
import cn.kasuminova.astd.sscsv.entries.catalog.weapondata.weaponName
import cn.kasuminova.astd.sscsv.i18n.SsI18n
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

/** 决明：整船 bloom 描边层（装配界面/战斗 decorative outline）。 */
object Wpn_astd_zw_001_bloom : WeaponDataEntry() {
    override val id: String = "astd_zw_001_bloom"
    override val name: String = weaponName(id)
    override val tier: Int = 5
    override val baseValue: Int = 0
    override val range: Int = 0
    override val turnRate: Int = 0
    override val type: String = "OTHER"
    override val tags: String = "no_drop, no_drop_salvage"
    override val tech: String = "菀星设计局-紫菀"
    override val noDpsInTooltip: Boolean = true
    override val number: Int = 9206
}

/** 密蒙：整船 bloom 描边层（装配界面/战斗 decorative outline）。 */
object Wpn_astd_zw_002_bloom : WeaponDataEntry() {
    override val id: String = "astd_zw_002_bloom"
    override val name: String = weaponName(id)
    override val tier: Int = 5
    override val baseValue: Int = 0
    override val range: Int = 0
    override val turnRate: Int = 0
    override val type: String = "OTHER"
    override val tags: String = "no_drop, no_drop_salvage"
    override val tech: String = "菀星设计局-紫菀"
    override val noDpsInTooltip: Boolean = true
    override val number: Int = 9230
}

/** 舜华：整船 bloom 描边层（装配界面/战斗 decorative outline）。 */
object Wpn_astd_zw_101_bloom : WeaponDataEntry() {
    override val id: String = "astd_zw_101_bloom"
    override val name: String = weaponName(id)
    override val tier: Int = 5
    override val baseValue: Int = 0
    override val range: Int = 0
    override val turnRate: Int = 0
    override val type: String = "OTHER"
    override val tags: String = "no_drop, no_drop_salvage"
    override val tech: String = "菀星设计局-紫菀"
    override val noDpsInTooltip: Boolean = true
    override val number: Int = 9232
}

/** 飞蓬：整船 bloom 描边层（装配界面/战斗 decorative outline）。 */
object Wpn_astd_zw_102_bloom : WeaponDataEntry() {
    override val id: String = "astd_zw_102_bloom"
    override val name: String = weaponName(id)
    override val tier: Int = 5
    override val baseValue: Int = 0
    override val range: Int = 0
    override val turnRate: Int = 0
    override val type: String = "OTHER"
    override val tags: String = "no_drop, no_drop_salvage"
    override val tech: String = "菀星设计局-紫菀"
    override val noDpsInTooltip: Boolean = true
    override val number: Int = 9231
}

object Wpn_astd_zw_103_bloom : WeaponDataEntry() {
    override val id: String = "astd_zw_103_bloom"
    override val name: String = weaponName(id)
    override val tier: Int = 5
    override val baseValue: Int = 0
    override val range: Int = 0
    override val turnRate: Int = 0
    override val type: String = "OTHER"
    override val tags: String = "no_drop, no_drop_salvage"
    override val tech: String = "菀星设计局-紫菀"
    override val noDpsInTooltip: Boolean = true
    override val number: Int = 9232
}

/** GCP 系列：引力坍缩炮（可装配版本；以持续命中坍缩 tick + AOE + 引力撕裂为核心机制）。 */
object Wpn_astd_gcp12 : WeaponDataEntry() {
    override val id: String = "astd_gcp12"
    override val name: String = weaponName(id)
    override val tier: Int = 3
    override val baseValue: Int = 24000
    override val range: Int = 1000

    // 光束每秒伤害
    override val damagePerSecond: Int = 800
    override val damagePerShot: Int = 0
    override val emp: Int = 0
    override val impact: Int = 0

    override val turnRate: Int = 20
    override val ops: Int = 28
    override val type: String = "HIGH_EXPLOSIVE"

    override val energyPerSecond: Int = 880

    // 开火前摇与开火后冷却
    override val chargeup: Double = 1.0
    override val chargedown: Double = 0.5

    // Beam 的 burst size/ delay 对应 burstDuration / burstCooldown
    override val burstSize: Double = 2.5
    override val burstDelay: Double = 3.0

    override val beamSpeed: Int = 10000
    override val projSpeed: Int = 0

    // autofit 类别标签（CoreAutofitPlugin 按 类别+等级 匹配，缺失会导致装配方案无法装回本武器）
    override val tags: String = "beam16, he16, LR, astd_production"
    override val groupTag: String = "astd"
    override val tech: String = "菀星设计局-紫菀"
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
    override val range: Int = 800

    override val damagePerSecond: Int = 500
    override val damagePerShot: Int = 0
    override val emp: Int = 0
    override val impact: Int = 0

    override val turnRate: Int = 30
    override val ops: Int = 14
    override val type: String = "HIGH_EXPLOSIVE"
    override val energyPerSecond: Int = 550

    override val chargeup: Double = 0.75
    override val chargedown: Double = 0.5
    override val burstSize: Double = 2.0
    override val burstDelay: Double = 2.5

    override val beamSpeed: Int = 10000
    override val projSpeed: Int = 0

    // autofit 类别标签（CoreAutofitPlugin 按 类别+等级 匹配，缺失会导致装配方案无法装回本武器）
    override val tags: String = "beam12, he12, astd_production"
    override val groupTag: String = "astd"
    override val tech: String = "菀星设计局-紫菀"
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
    override val range: Int = 600

    override val damagePerSecond: Int = 350
    override val damagePerShot: Int = 0
    override val emp: Int = 0
    override val impact: Int = 0

    override val turnRate: Int = 30
    override val ops: Int = 8
    override val type: String = "HIGH_EXPLOSIVE"
    override val energyPerSecond: Int = 385

    override val chargeup: Double = 0.5
    override val chargedown: Double = 0.25
    override val burstSize: Double = 1.5
    override val burstDelay: Double = 2.75

    override val beamSpeed: Int = 10000
    override val projSpeed: Int = 0

    // autofit 类别标签（CoreAutofitPlugin 按 类别+等级 匹配，缺失会导致装配方案无法装回本武器）
    override val tags: String = "beam8, he8, astd_production"
    override val groupTag: String = "astd"
    override val tech: String = "菀星设计局-紫菀"
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
    override val range: Int = 600

    // Burst beam：爆发伤害=300，发射时间=1s => 束内 DPS=300
    override val damagePerSecond: Int = 300
    override val damagePerShot: Int = 0
    override val emp: Int = 0
    override val impact: Int = 0

    override val turnRate: Int = 60
    override val ops: Int = 6
    override val type: String = "HIGH_EXPLOSIVE"
    override val energyPerSecond: Int = 300

    override val chargeup: Double = 0.5
    override val chargedown: Double = 0.25
    override val burstSize: Double = 1.0
    override val burstDelay: Double = 1.25

    override val beamSpeed: Int = 10000
    override val projSpeed: Int = 0

    override val aiHints: Set<AiHint> = setOf(AiHint.PD)

    // autofit 类别标签（CoreAutofitPlugin 按 类别+等级 匹配，缺失会导致装配方案无法装回本武器）
    override val tags: String = "pd6, beam6, SR, astd_production"
    override val groupTag: String = "astd"
    override val tech: String = "菀星设计局-紫菀"
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
    override val tech: String = "菀星设计局-紫菀"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val customPrimary: String = SsI18n.t("weapon.$id.tooltip.customPrimary")
    override val customPrimaryHL: String = SsI18n.t("weapon.$id.tooltip.customPrimaryHL")
    override val noDpsInTooltip: Boolean = false
    override val number: Int = 9220
}

/** 辉星 MRM 发射器（规格 08 §1.1）：猎机追踪 + 战机全武器 EMP + 十字辉星爆炸的小型稀有导弹。 */
object Wpn_astd_stellar_mrm_launcher : WeaponDataEntry(), SsProjMissileOutputs {
    override val id: String = "astd_stellar_mrm_launcher"
    override val name: String = weaponName(id)
    override val tier: Int = 3
    override val baseValue: Int = 15000
    override val range: Int = 2500

    // 导弹武器 DPS 列留空（对照 amsrm csv 行 damage/second 空）；EMP 机制走脚本不进面板
    override val damagePerShot: Int = 100

    override val turnRate: Int = 30
    override val ops: Int = 4

    // 弹匣 8 发，5s/+1
    override val ammo: Int = 8
    override val ammoPerSec: Double = 0.2
    override val reloadSize: Int = 1

    // 发射冷却 1s，单次发射量 1
    override val chargedown: Double = 1.0
    override val burstSize: Number = 1
    override val burstDelay: Double = 0.0

    override val type: String = "ENERGY"
    override val energyPerShot: Int = 60
    override val energyPerSecond: Int = 60

    // “反物质 SRM 75% 航速”：amsrm projSpeed=1000 × 0.75；launch speed 对齐 amsrm 200
    override val projSpeed: Int = 750
    override val launchSpeed: Int = 200

    // 2500 射程 / 750 航速 ≈ 3.3s 直线 + 追踪冗余（目检微调）
    override val flightTime: Double = 4.0
    override val projHitpoints: Int = 200

    // 追踪优秀（amsrm 同文案）；航速 75% 降一档“快”（amsrm 为“极快”）
    override val trackingStr: String = "优秀"
    override val speedStr: String = "快"

    // P6 前口径（首批计划 §14）；P6 后改稀有赏金掉落，另立任务
    override val tags: String = "no_drop, no_drop_salvage"
    override val groupTag: String = "astd"
    override val tech: String = "菀星设计局-紫菀"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val customPrimary: String = SsI18n.t("weapon.$id.tooltip.customPrimary")
    override val customPrimaryHL: String = SsI18n.t("weapon.$id.tooltip.customPrimaryHL")
    override val noDpsInTooltip: Boolean = false
    override val number: Int = 9217

    override val projSpec: MissileProjSpec = MissileProjSpec(
        id = "astd_stellar_mrm_launcher_shot",
        missileType = "MISSILE",
        onFireEffect = "cn.kasuminova.astd.combat.effect.lens.stellar.StellarMrmOnFireEffect",
        onHitEffect = "cn.kasuminova.astd.combat.effect.lens.stellar.StellarMrmOnHitEffect",
        // 弹头交回原版渲染：导弹无 projbody 路径，用原版导弹贴图 am_srm（紫反物质弹，尺寸/中心照抄 amsrm_srm.proj）；
        // 拖尾仍由 ProjectileVfxSpecs 三层贴图混合承担，引擎辉光/尾焰保持隐藏（不与我们拖尾叠穿帮）。
        sprite = "graphics/missiles/am_srm.png",
        size = Vec2i(13, 17),
        center = Vec2(7, 9),
        collisionRadius = 12,
        collisionClass = "MISSILE_NO_FF",
        explosionColor = Rgba(170, 110, 255, 160),
        explosionRadius = 75,
        armingTime = 0.0,
        flameoutTime = 0.5,
        noEngineGlowTime = 999.0,
        fadeTime = 0.25,
        engineSpec = MissileEngineSpec(
            turnAcc = 2000,
            turnRate = 500,
            acc = 2000,
            dec = 2000,
        ),
        engineSlots = emptyList(),
    )
}

/** 辉星 MRM 发射舱（规格 08 §1.1）：中型位，单次发射量 2，备弹经济 20/0.5/2。 */
object Wpn_astd_stellar_mrm_pod : WeaponDataEntry(), SsProjMissileOutputs {
    override val id: String = "astd_stellar_mrm_pod"
    override val name: String = weaponName(id)
    override val tier: Int = 3
    override val baseValue: Int = 30000
    override val range: Int = 2500

    override val damagePerShot: Int = 100

    override val turnRate: Int = 30
    override val ops: Int = 10

    // 弹匣 20 发，4s/+2
    override val ammo: Int = 20
    override val ammoPerSec: Double = 0.5
    override val reloadSize: Int = 2

    // 发射冷却 1s，单次发射量 2
    override val chargedown: Double = 1.0
    override val burstSize: Number = 2
    override val burstDelay: Double = 0.0

    override val type: String = "ENERGY"
    override val energyPerShot: Int = 60
    override val energyPerSecond: Int = 120

    override val projSpeed: Int = 750
    override val launchSpeed: Int = 200
    override val flightTime: Double = 4.0
    override val projHitpoints: Int = 200

    override val trackingStr: String = "优秀"
    override val speedStr: String = "快"

    override val tags: String = "no_drop, no_drop_salvage"
    override val groupTag: String = "astd"
    override val tech: String = "菀星设计局-紫菀"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val customPrimary: String = SsI18n.t("weapon.$id.tooltip.customPrimary")
    override val customPrimaryHL: String = SsI18n.t("weapon.$id.tooltip.customPrimaryHL")
    override val noDpsInTooltip: Boolean = false
    override val number: Int = 9218

    override val projSpec: MissileProjSpec = MissileProjSpec(
        id = "astd_stellar_mrm_pod_shot",
        missileType = "MISSILE",
        onFireEffect = "cn.kasuminova.astd.combat.effect.lens.stellar.StellarMrmOnFireEffect",
        onHitEffect = "cn.kasuminova.astd.combat.effect.lens.stellar.StellarMrmOnHitEffect",
        // 与发射器同弹头（刻意一致）：原版 am_srm 贴图承担弹头，拖尾走 ProjectileVfxSpecs 三层混合
        sprite = "graphics/missiles/am_srm.png",
        size = Vec2i(13, 17),
        center = Vec2(7, 9),
        collisionRadius = 12,
        collisionClass = "MISSILE_NO_FF",
        explosionColor = Rgba(170, 110, 255, 160),
        explosionRadius = 75,
        armingTime = 0.0,
        flameoutTime = 0.5,
        noEngineGlowTime = 999.0,
        fadeTime = 0.25,
        engineSpec = MissileEngineSpec(
            turnAcc = 2000,
            turnRate = 500,
            acc = 2000,
            dec = 2000,
        ),
        engineSlots = emptyList(),
    )
}

/**
 * 离子脉冲（战机）：茑萝级内置战机「游丝」武备（purple/20-production.md §2）。
 *
 * 原版离子脉冲（ionpulser）的战机化调参：burst 2 发、单发 90 能量 + 200 EMP，
 * 单发辐能 50（战机 900/150 辐能池可持续）；hints SYSTEM 不进常规配装列表。
 */
object Wpn_astd_ion_pulse_fighter : WeaponDataEntry(), SsProjProjectileOutputs {
    override val id: String = "astd_ion_pulse_fighter"
    override val name: String = weaponName(id)
    override val tier: Int = 3
    override val baseValue: Int = 0
    override val range: Int = 500
    override val damagePerShot: Int = 90
    override val emp: Int = 200
    override val turnRate: Int = 40
    override val ops: Int = 0
    override val type: String = "ENERGY"
    override val energyPerShot: Int = 100
    override val chargeup: Double = 0.05
    override val chargedown: Double = 0.05
    override val burstSize: Number = 3
    override val burstDelay: Double = 0.1
    override val minSpread: Double = 3.0
    override val maxSpread: Double = 12.0
    override val spreadPerShot: Double = 1.0
    override val spreadDecayPerSec: Double = 4.0
    override val projSpeed: Int = 1000
    override val hints: String = "SYSTEM"
    override val ammo: Int = 15
    override val ammoPerSec: Double = 1.5
    override val reloadSize: Int = 3
    override val tags: String = "energy8, show_in_codex"
    override val tech: String = "菀星设计局-紫菀"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val number: Int = 9242

    // 对齐原版 ionpulser_shot 的能量螺栓观感（RAY 碰撞 + 原版离子电弧 onHit）
    override val projSpec: ProjectileProjSpec = ProjectileProjSpec(
        id = "astd_ion_pulse_fighter_shot",
        spawnType = ProjectileSpawnType.BALLISTIC_AS_BEAM,
        onHitEffect = "com.fs.starfarer.api.impl.combat.IonCannonOnHitEffect",
        collisionClass = "RAY",
        collisionClassByFighter = "RAY_FIGHTER",
        length = 60.0,
        width = 16.0,
        fadeTime = 0.25,
        fringeColor = Rgba(25, 100, 175, 255),
        coreColor = Rgba(225, 225, 255, 200),
        textureScrollSpeed = -256.0,
        pixelsPerTexel = 1.0,
    )
}

/**
 * 相位长矛（战机）：茑萝级内置战机「游丝」武备（purple/20-production.md §2）。
 *
 * 原版相位长矛（phasebeam）的战机化调参：1s 脉冲光束 / 3s 间隔，150 DPS、120 辐能/秒
 * （战机 900/150 辐能池可持续）；hints SYSTEM 不进常规配装列表。.wpn 为手写光束资源。
 */
object Wpn_astd_phase_lance_fighter : WeaponDataEntry() {
    override val id: String = "astd_phase_lance_fighter"
    override val name: String = weaponName(id)
    override val tier: Int = 3
    override val baseValue: Int = 0
    override val range: Int = 550
    override val damagePerSecond: Int = 800
    override val turnRate: Int = 40
    override val ops: Int = 0
    override val type: String = "ENERGY"
    override val energyPerSecond: Int = 733
    override val chargeup: Double = 0.25
    override val chargedown: Double = 0.5
    override val burstSize: Number = 1
    override val burstDelay: Double = 3.0
    override val beamSpeed: Int = 3200
    override val hints: String = "SYSTEM"
    override val tags: String = "beam10, show_in_codex"
    override val tech: String = "菀星设计局-紫菀"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val number: Int = 9243
}

/**
 * 引力裂隙光束（隐藏 SYSTEM 光束武器）：舰船系统「引力裂隙发生器」的光束载体。
 *
 * 对齐原版裂隙洪流发射极（riftcascade）的口径——specClass=beam，颜色改红色正色，
 * beamEffect 换为 [GravityRiftBeamEffect]（红色布雷、不使用反色星云粒子）。
 * 面板射程 1000 是光束基础射程（实际挂载在 FX drone 上，射程/伤害加成由系统脚本
 * 从源舰 energyWeaponRangeBonus/beamWeaponRangeBonus 等折算到 drone）。
 * damage/second 1000 取原版 riftcascade 平价（裁定价，待实机校准）。
 * burst size 30 = 光束持续秒数：远超系统 2s 光束窗口，收口由系统脚本停止强火 +
 * chargedown 0.3s 淡出后移除 drone 承担。
 */
object Wpn_astd_grav_rift_beam : WeaponDataEntry() {
    override val id: String = "astd_grav_rift_beam"
    override val name: String = weaponName(id)
    override val tier: Int = 3
    override val baseValue: Int = 0
    override val range: Int = 1000
    override val damagePerSecond: Int = 1000
    override val turnRate: Int = 0
    override val ops: Int = 0
    override val type: String = "ENERGY"
    override val chargeup: Double = 0.0
    override val chargedown: Double = 0.3
    override val burstSize: Number = 30
    override val burstDelay: Double = 0.0
    override val beamSpeed: Int = 10000
    override val aiHints: Set<AiHint> = setOf(AiHint.SYSTEM)
    override val tags: String = "no_drop, no_drop_salvage"
    override val tech: String = "菀星设计局-紫菀"
    override val noDpsInTooltip: Boolean = true
    override val number: Int = 9245
}

/**
 * 引力裂隙布雷器（隐藏 SYSTEM 武器）：舰船系统「引力裂隙发生器」的裂隙载体。
 *
 * 对齐原版 riftcascade_minelayer 的口径——除 specClass/projectileSpecId 与伤害列外均不重要；
 * damage/shot 1000 是裂隙基础伤害来源（实际伤害由系统脚本按难度锚点与裂隙序位插值成乘区）。
 * 弹体为 PHASE_MINE（近炸引信 0.5s），爆炸特效由 GravityRiftMineExplosion 生成红色裂隙。
 */
object Wpn_astd_grav_rift_minelayer : WeaponDataEntry(), SsProjMissileOutputs {
    override val id: String = "astd_grav_rift_minelayer"
    override val name: String = weaponName(id)
    override val tier: Int = 3
    override val baseValue: Int = 0
    override val range: Int = 0
    override val damagePerShot: Int = 1000
    override val turnRate: Int = 0
    override val ops: Int = 0
    override val type: String = "ENERGY"
    override val flightTime: Double = 20.0
    override val projHitpoints: Int = 1000
    override val hints: String = "SYSTEM"
    override val tech: String = "菀星设计局-紫菀"
    override val noDpsInTooltip: Boolean = true
    override val number: Int = 9244

    override val projSpec: MissileProjSpec = MissileProjSpec(
        id = "astd_grav_rift_mine",
        missileType = "PHASE_MINE",
        sprite = "",
        glowSprite = "",
        // 红色主题：NegativeExplosionVisual 裂隙取色来源（原版注释口径）
        glowColor = Rgba(255, 70, 70, 255),
        size = Vec2i(96, 96),
        center = Vec2(48, 48),
        collisionRadius = 20,
        collisionClass = "NONE",
        collisionClassAfterFlameout = "NONE",
        renderTargetIndicator = false,
        explosionColor = Rgba(255, 70, 70, 255),
        explosionRadius = 0,
        flameoutTime = 0.1,
        noEngineGlowTime = 0.05,
        fadeTime = 0.1,
        engineSpec = MissileEngineSpec(turnAcc = 500, turnRate = 150, acc = 500, dec = 500),
        engineSlots = emptyList(),
        behaviorSpec = linkedMapOf(
            "behavior" to "PROXIMITY_FUSE",
            "onExplosionEffect" to "cn.kasuminova.astd.combat.effect.lens.GravityRiftMineExplosion",
            "range" to 0,
            "slowToMaxSpeed" to true,
            "delay" to 0.5,
            "pingColor" to listOf(255, 70, 70, 255),
            "mineHasNoSprite" to true,
            "flashRateMult" to 0.25,
            "pingRadius" to 100,
            "pingDuration" to 0.25,
            "windupSound" to "riftcascade_windup",
            "windupDelay" to 0.3,
            "explosionSpec" to linkedMapOf(
                "duration" to 0.1,
                "radius" to 100,
                "coreRadius" to 50,
                "collisionClass" to "PROJECTILE_FF",
                "collisionClassByFighter" to "PROJECTILE_FF",
                "particleDuration" to 1,
                "particleCount" to 0,
                "particleColor" to listOf(0, 0, 0, 0),
                "explosionColor" to listOf(0, 0, 0, 0),
                "sound" to "riftcascade_rift",
            ),
        ),
    )
}
