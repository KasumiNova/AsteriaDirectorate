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
    // AI 行为：无需瞄准即可开火 + 战机点防御（对齐原版蜂群/蝗虫系 PD 导弹口径）
    override val aiHints: Set<AiHint> = setOf(AiHint.PD, AiHint.ANTI_FTR, AiHint.DO_NOT_AIM)
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
    // AI 行为：无需瞄准即可开火 + 战机点防御（对齐原版蜂群/蝗虫系 PD 导弹口径）
    override val aiHints: Set<AiHint> = setOf(AiHint.PD, AiHint.ANTI_FTR, AiHint.DO_NOT_AIM)
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

/** 源生冰晶 MIRV 母弹公共弹头口径（purple/30-superlative.md）：两槽位共用同一弹体 spec。 */
private fun iceShardMirvProjSpec(): MissileProjSpec = MissileProjSpec(
    id = "astd_ice_shard_mirv_shot",
    // MIRV 型：面板伤害显示（400x15）、autofit 伤害评估（400×15=6000）与护盾 AI 威慑判定
    // 均按原版 MIRV 语义取值（CargoTooltipFactory/WeaponSpreadsheetLoader 读 behaviorSpec params）。
    // 原版 MirvAI 会随 MIRV 型自动指派（ProjectileFactory）：引导段由其内嵌 MissileAI 承担（原版 MIRV 同款追踪），
    // 内建分裂经 minTimeToSplit=9999 关停——分裂由自定义引信脚本全权承担
    // （随机伤害分配 200~800 / 定制分裂 VFX / 遥测），behaviorSpec 其余参数仅供面板与引擎语义消费。
    missileType = "MIRV",
    onFireEffect = "cn.kasuminova.astd.combat.effect.lens.iceshard.IceShardMirvOnFireEffect",
    // 母弹无 onHitEffect：直击命中仅面板伤害（分裂逻辑由发射时每弹一注册的引信脚本承担）
    sprite = "graphics/missiles/missile_MIRV.png",
    size = Vec2i(12, 28),
    center = Vec2(6, 14),
    collisionRadius = 16,
    collisionClass = "MISSILE_NO_FF",
    explosionColor = Rgba(160, 220, 255, 160),
    explosionRadius = 100,
    armingTime = 0.25,
    flameoutTime = 0.5,
    noEngineGlowTime = 999.0,
    fadeTime = 0.25,
    // “追踪普通”：引擎参数对齐原版 type_1_mirv（飓风 MIRV）
    engineSpec = MissileEngineSpec(turnAcc = 600, turnRate = 150, acc = 250, dec = 200),
    engineSlots = emptyList(),
    behaviorSpec = linkedMapOf(
        "behavior" to "MIRV",
        // 内建分裂关停：分裂时机/产物由 IceShardMirvSplitScript 承担（含 1s 发射豁免）
        "minTimeToSplit" to 9999,
        "canSplitEarly" to false,
        // 以下参数对齐真实机制数值，供面板显示（damage×numShots = 400x15）与引擎 MIRV 语义消费
        "splitRange" to 600,
        "numShots" to 15,
        "damage" to 400,
        "impact" to 5,
        "emp" to 0,
        "damageType" to "FRAGMENTATION",
        "hitpoints" to 100,
        "arc" to 20,
        "arcOffset" to 0,
        "evenSpread" to false,
        // 末端引导速度覆盖对齐子弹实速 1000（MirvAI 取 max(spreadSpeed, 子弹 spec 最大航速)，此处写满一致值）
        "spreadSpeed" to 1000,
        "spreadSpeedRange" to 250,
        "projectileSpec" to "astd_ice_shard_sub_msl",
        "splitSound" to "hurricane_mirv_split",
        "smokeSpec" to linkedMapOf(
            "particleSizeMin" to 30.0,
            "particleSizeRange" to 30.0,
            "cloudParticleCount" to 15,
            "cloudDuration" to 1.0,
            "cloudRadius" to 20.0,
            "blowbackParticleCount" to 0,
            "blowbackDuration" to 0,
            "blowbackLength" to 0,
            "blowbackSpread" to 0,
            "particleColor" to listOf(170, 225, 255, 200),
        ),
    ),
)

/** 源生冰晶 MIRV 发射器（小型导弹，purple/30-superlative.md）：600su 近距分裂 15 枚冰晶射弹。 */
object Wpn_astd_ice_shard_mirv : WeaponDataEntry(), SsProjMissileOutputs {
    override val id: String = "astd_ice_shard_mirv"
    override val name: String = weaponName(id)
    override val tier: Int = 3
    override val baseValue: Int = 12000
    override val range: Int = 1600

    // 面板伤害为单枚冰晶射弹口径（400×15=6000 总伤由脚本分配）；母弹直击仅 400
    override val damagePerShot: Int = 400

    override val turnRate: Int = 30
    override val ops: Int = 6

    // 备弹 1 发，50s/+1
    override val ammo: Int = 1
    override val ammoPerSec: Double = 0.02
    override val reloadSize: Int = 1

    // 发射冷却 4s，单次发射量 1
    override val chargedown: Double = 4.0
    override val burstSize: Number = 1
    override val burstDelay: Double = 0.0

    override val type: String = "FRAGMENTATION"
    override val energyPerShot: Int = 500
    override val energyPerSecond: Int = 125

    // “鱼叉 MRM 100% 航速”：harpoon projSpeed=300；launch speed 对齐 harpoon 100
    override val projSpeed: Int = 300
    override val launchSpeed: Int = 100

    // 1600 射程 / 300 航速 ≈ 5.3s 直线 + 追踪冗余
    override val flightTime: Double = 6.0
    override val projHitpoints: Int = 600

    override val trackingStr: String = "普通"
    override val speedStr: String = "普通"

    // P6 前口径；P6 后改赏金掉落（90-plan §14）
    override val tags: String = "no_drop, no_drop_salvage"
    override val groupTag: String = "astd"
    override val tech: String = "菀星设计局-紫菀"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val customPrimary: String = SsI18n.t("weapon.$id.tooltip.customPrimary")
    override val customPrimaryHL: String = SsI18n.t("weapon.$id.tooltip.customPrimaryHL")
    override val noDpsInTooltip: Boolean = false
    // AI 行为对齐原版阿特罗波斯鱼雷
    override val aiHints: Set<AiHint> = setOf(AiHint.GUIDED_POOR)
    override val number: Int = 9229

    override val projSpec: MissileProjSpec = iceShardMirvProjSpec()
}

/** 源生冰晶 MIRV 发射舱（中型导弹）：与小型共用母弹 spec，单次发射量 2，备弹经济 2/20s。 */
object Wpn_astd_ice_shard_mirv_pod : WeaponDataEntry() {
    override val id: String = "astd_ice_shard_mirv_pod"
    override val name: String = weaponName(id)
    override val tier: Int = 3
    override val baseValue: Int = 24000
    override val range: Int = 1600

    override val damagePerShot: Int = 400

    override val turnRate: Int = 30
    override val ops: Int = 12

    // 备弹 2 发，20s/+1
    override val ammo: Int = 2
    override val ammoPerSec: Double = 0.05
    override val reloadSize: Int = 1

    // 发射冷却 6s，单次发射量 2
    override val chargedown: Double = 6.0
    override val burstSize: Number = 2
    override val burstDelay: Double = 0.0

    override val type: String = "FRAGMENTATION"
    override val energyPerShot: Int = 500
    override val energyPerSecond: Int = 167

    override val projSpeed: Int = 300
    override val launchSpeed: Int = 100
    override val flightTime: Double = 6.0
    override val projHitpoints: Int = 600

    override val trackingStr: String = "普通"
    override val speedStr: String = "普通"

    override val tags: String = "no_drop, no_drop_salvage"
    override val groupTag: String = "astd"
    override val tech: String = "菀星设计局-紫菀"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val customPrimary: String = SsI18n.t("weapon.$id.tooltip.customPrimary")
    override val customPrimaryHL: String = SsI18n.t("weapon.$id.tooltip.customPrimaryHL")
    override val noDpsInTooltip: Boolean = false
    // AI 行为对齐原版阿特罗波斯鱼雷
    override val aiHints: Set<AiHint> = setOf(AiHint.GUIDED_POOR)
    override val number: Int = 9233
}

/** 源生冰晶子射弹（隐藏内部武器，永不装配/掉落）：MIRV 分裂产物，命中舰体附着并施加冻结。 */
object Wpn_astd_ice_shard_sub : WeaponDataEntry(), SsProjMissileOutputs {
    override val id: String = "astd_ice_shard_sub"
    override val name: String = weaponName(id)
    override val tier: Int = 3
    override val baseValue: Int = 0
    override val range: Int = 1000

    // 单枚基准伤害（脚本按“15 枚合计 6000、单枚 200~800”覆写 damageAmount）
    override val damagePerShot: Int = 400

    override val turnRate: Int = 30
    override val type: String = "FRAGMENTATION"

    // 基准弹速 1000（±25% 浮动由脚本经初速矢量实现）；基准寿命 = 1000 射程 / 1000 弹速。
    // launchSpeed 必须保持默认 0：引擎生成导弹的初速 = 脚本矢量 + launchSpeed 沿朝向，非零会污染散布锥
    override val projSpeed: Int = 1000
    override val flightTime: Double = 1.0
    override val projHitpoints: Int = 100

    override val tags: String = "no_drop, no_drop_salvage"
    override val tech: String = "菀星设计局-紫菀"
    override val noDpsInTooltip: Boolean = true
    override val number: Int = 9234

    override val projSpec: MissileProjSpec = MissileProjSpec(
        id = "astd_ice_shard_sub_msl",
        // ROCKET：原版直线飞行语义（无追踪 AI），分裂锥内随机散布由脚本给初速矢量
        missileType = "ROCKET",
        onHitEffect = "cn.kasuminova.astd.combat.effect.lens.iceshard.IceShardSubOnHitEffect",
        // 原版弹体贴图渲染屏蔽：本体由弹体 VFX 管线的 spriteBody 层接管
        // （BoxUtil SpriteEntity 逐帧跟随，贴图 graphics/fx/astd_ice_shard.png）
        sprite = "graphics/textures/BUtil_NONE.png",
        size = Vec2i(20, 20),
        center = Vec2(10, 10),
        collisionRadius = 8,
        collisionClass = "MISSILE_NO_FF",
        explosionColor = Rgba(170, 225, 255, 160),
        explosionRadius = 25,
        flameoutTime = 0.5,
        noEngineGlowTime = 999.0,
        fadeTime = 0.25,
        // 零推力：冰晶速度由分裂脚本赋予的初速矢量全权决定（±25% 浮动），引擎不再干预
        engineSpec = MissileEngineSpec(turnAcc = 0, turnRate = 0, acc = 0, dec = 0),
        engineSlots = emptyList(),
    )
}

/**
 * 双子星 DEM（战机）：双子座轰炸联队武备（purple/30-fighters.md §双子座 v1 定案）。
 *
 * 备弹 2（= 2 次双弹齐射）、不可恢复（ammoPerSec/reloadSize 0）、发射不产辐能；
 * 弹体复用舰装版 dummy spec（astd_gemini_dem_dummy），齐射/追踪/同步冲击机制全部由
 * GeminiDemSalvoOnFireEffect 沿用，不复制第二份实现。.wpn 为手写全隐资源。
 */
object Wpn_astd_gemini_dem_fighter : WeaponDataEntry() {
    override val id: String = "astd_gemini_dem_fighter"
    override val name: String = weaponName(id)
    override val tier: Int = 2
    override val baseValue: Int = 0
    override val range: Int = 2500
    override val damagePerSecond: Int = 0
    override val damagePerShot: Int = 2500
    override val emp: Int = 2000
    override val turnRate: Int = 30
    override val ops: Int = 0
    override val ammo: Int = 2
    override val ammoPerSec: Double = 0.0
    override val reloadSize: Int = 0
    override val type: String = "ENERGY"
    override val chargedown: Double = 12.0
    override val projSpeed: Int = 225
    override val flightTime: Double = 14.0
    override val projHitpoints: Int = 600

    // 战机内置武器：SYSTEM 不进配装列表；AI 口径对齐原版龙炎 DEM（不瞄准直接射、打击定位）
    override val aiHints: Set<AiHint> = setOf(AiHint.SYSTEM, AiHint.DO_NOT_AIM, AiHint.STRIKE)
    override val tags: String = "strike8, missile0, show_in_codex"
    override val groupTag: String = "astd"
    // 跨线武备：LENS 投送平台 × ARC 制式弹药，设计方沿用舰装版星坠口径
    override val tech: String = "菀星设计局-星坠"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val number: Int = 9246
}

/**
 * 电荷针刺（战机）：电涌战斗联队武备（purple/30-fighters.md §电涌 v1 定案）。
 *
 * 连发 10 @ 10发/s（burstSize 10 / burstDelay 0.1）、射程 600、单发辐能 40；
 * 弹匣 20 / 回充 10 发每 4s（ammoPerSec 2.5 × reloadSize 10）；弹体复用 astd_charge_needle_shot，
 * 电荷淤积/泄放机制全部由 ChargeNeedleOnHitEffect 沿用。.wpn 为手写全隐资源。
 */
object Wpn_astd_charge_needle_fighter : WeaponDataEntry() {
    override val id: String = "astd_charge_needle_fighter"
    override val name: String = weaponName(id)
    override val tier: Int = 1
    override val baseValue: Int = 0
    override val range: Int = 600
    override val damagePerSecond: Int = 500
    override val damagePerShot: Int = 50
    override val emp: Int = 100
    override val turnRate: Int = 30
    override val ops: Int = 0

    // 连发 10 @ 10发/s；burst 间隔下限由弹匣回充（2.5/s）自然门控
    override val chargedown: Double = 0.1
    override val burstSize: Number = 10
    override val burstDelay: Double = 0.1
    override val ammo: Int = 20
    override val ammoPerSec: Double = 2.5
    override val reloadSize: Int = 10
    override val type: String = "ENERGY"
    override val energyPerShot: Int = 40

    // 持续口径 = 回充 2.5 发/s × 40 辐能
    override val energyPerSecond: Int = 100
    override val projSpeed: Int = 1350

    // 精度口径沿用舰装版（对齐原版轻型针刺）
    override val minSpread: Double = 0.0
    override val maxSpread: Double = 10.0
    override val spreadPerShot: Double = 0.66
    override val spreadDecayPerSec: Double = 5.0

    // 战机内置武器：SYSTEM 不进配装列表
    override val aiHints: Set<AiHint> = setOf(AiHint.SYSTEM)
    override val tags: String = "energy8, show_in_codex"
    override val groupTag: String = "astd"
    // 跨线武备：设计方沿用舰装版星坠口径
    override val tech: String = "菀星设计局-星坠"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    // 特效描述直接沿用舰装版电荷针刺文案（机制一致，见 ChargeNeedleOnHitEffect）
    override val customPrimary: String = SsI18n.t("weapon.$id.tooltip.customPrimary")
    override val customPrimaryHL: String = SsI18n.t("weapon.$id.tooltip.customPrimaryHL")
    override val number: Int = 9247
}

/**
 * 引力坍缩炮 PD（战机）：引力井截击联队武备（purple/30-fighters.md §引力井 v1 定案）。
 *
 * 数据全量复用舰装版 astd_gcp2（射程 600 / 束内 DPS 300 / 辐能 300/s / 充能-爆发-冷却周期一致），
 * 唯一差异是不渲染武器贴图（.wpn 全隐资源）；光束渲染与引力坍缩机制复用
 * HiddenBeamRenderEffect + GravityCollapseBeamEveryFrameEffect（.wpn 接线同源）。
 */
object Wpn_astd_gcp_fighter : WeaponDataEntry() {
    override val id: String = "astd_gcp_fighter"
    override val name: String = weaponName(id)
    override val tier: Int = 1
    override val baseValue: Int = 0
    override val range: Int = 600
    override val damagePerSecond: Int = 300
    override val damagePerShot: Int = 0
    override val emp: Int = 0
    override val impact: Int = 0
    override val turnRate: Int = 60
    override val ops: Int = 0
    override val type: String = "HIGH_EXPLOSIVE"
    override val energyPerSecond: Int = 300
    override val chargeup: Double = 0.5
    override val chargedown: Double = 0.25
    override val burstSize: Double = 1.0
    override val burstDelay: Double = 1.25
    override val beamSpeed: Int = 10000
    override val projSpeed: Int = 0

    // 战机内置武器：SYSTEM 不进配装列表；PD 口径沿用舰装版
    override val aiHints: Set<AiHint> = setOf(AiHint.SYSTEM, AiHint.PD)
    override val tags: String = "beam6, show_in_codex"
    override val groupTag: String = "astd"
    override val tech: String = "菀星设计局-紫菀"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val number: Int = 9248
}
