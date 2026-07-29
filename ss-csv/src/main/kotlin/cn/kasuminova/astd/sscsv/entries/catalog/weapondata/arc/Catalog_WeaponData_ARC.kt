package cn.kasuminova.astd.sscsv.entries.catalog.weapondata.arc

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

/** ARC 系武器（weapon_data.csv）。 */

object Wpn_astd_aod7 : WeaponDataEntry(), SsProjProjectileOutputs {
    override val id: String = "astd_aod7"
    override val name: String = weaponName(id)
    override val tier: Int = 3
    override val baseValue: Int = 50000
    override val range: Int = 950
    override val damagePerSecond: Int = 625
    override val damagePerShot: Int = 750

    // 非 Beam：用 chargedown/burst 描述射速，避免 tooltip 统计除 0 溢出
    override val chargedown: Double = 0.5
    override val burstSize: Int = 1
    override val burstDelay: Double = 0.0
    override val turnRate: Int = 30
    override val type: String = "ENERGY"
    override val energyPerShot: Int = 700
    override val energyPerSecond: Int = 583
    override val projSpeed: Int = 2400

    // 弹匣：5 发，6s 整组装填
    override val ammo: Int = 5
    override val ammoPerSec: Double = 0.8333
    override val reloadSize: Int = 5

    override val tags: String = "astd_signature"
    override val groupTag: String = "astd"
    override val tech: String = "弧光阵列"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val number: Int = 9001

    override val projSpec: ProjectileProjSpec = ProjectileProjSpec(
        id = "astd_aod7_shot",
        spawnType = ProjectileSpawnType.BALLISTIC,
        onFireEffect = "cn.kasuminova.astd.combat.effect.generic.ProjectileSpecOnFireDispatcher",
        onHitEffect = "cn.kasuminova.astd.combat.effect.generic.HighFluxShieldPressureOnHitEffect",
        collisionClass = "PROJECTILE_FF",
        collisionClassByFighter = "PROJECTILE_FIGHTER",
        // 原版 projectile visual 必须不可见（length/width=2 + 色 alpha=0 + BUtil_NONE）。
        length = 2.0,
        width = 2.0,
        // fadeTime=0.2：给超射程后的原版弹体一段滑行窗口，令代码 VFX 拖尾能跟随淡出（否则 fadeTime=0 会被引擎即刻移除、
        // 拖尾在射程环处骤消）。原版弹体已 alpha=0 全隐，此窗口不会造成视觉穿帮。
        fadeTime = 0.2,
        fringeColor = Rgba(255, 198, 126, 0),
        coreColor = Rgba(248, 242, 232, 0),
        textureScrollSpeed = 0.0,
        pixelsPerTexel = 1.0,
        bulletSprite = "graphics/textures/BUtil_NONE.png",
    )
}

/** Arc Flare：整船静态发光层（装配界面/战斗常驻 decorative lights）。 */
object Wpn_astd_arc_flare_lights : WeaponDataEntry() {
    override val id: String = "astd_arc_flare_lights"
    override val name: String = weaponName(id)
    override val tier: Int = 5
    override val baseValue: Int = 0
    override val range: Int = 0
    override val turnRate: Int = 0
    override val type: String = "OTHER"
    override val tags: String = "no_drop, no_drop_salvage"
    override val tech: String = "弧光阵列"
    override val noDpsInTooltip: Boolean = true
    override val number: Int = 9106
}

/** Arc Flare：整船 bloom 描边层（装配界面/战斗 decorative outline）。 */
object Wpn_astd_arc_flare_lights_bloom : WeaponDataEntry() {
    override val id: String = "astd_arc_flare_lights_bloom"
    override val name: String = weaponName(id)
    override val tier: Int = 5
    override val baseValue: Int = 0
    override val range: Int = 0
    override val turnRate: Int = 0
    override val type: String = "OTHER"
    override val tags: String = "no_drop, no_drop_salvage"
    override val tech: String = "弧光阵列"
    override val noDpsInTooltip: Boolean = true
    override val number: Int = 9125
}

/** Negentropy Edge：整船 bloom 描边层（装配界面/战斗 decorative outline）。 */
object Wpn_astd_negentropy_edge_bloom : WeaponDataEntry() {
    override val id: String = "astd_negentropy_edge_bloom"
    override val name: String = weaponName(id)
    override val tier: Int = 5
    override val baseValue: Int = 0
    override val range: Int = 0
    override val turnRate: Int = 0
    override val type: String = "OTHER"
    override val tags: String = "no_drop, no_drop_salvage"
    override val tech: String = "弧光阵列"
    override val noDpsInTooltip: Boolean = true
    override val number: Int = 9126
}

/** Radiation Belt：整船 bloom 描边层（装配界面/战斗 decorative outline）。 */
object Wpn_astd_radiation_belt_bloom : WeaponDataEntry() {
    override val id: String = "astd_radiation_belt_bloom"
    override val name: String = weaponName(id)
    override val tier: Int = 5
    override val baseValue: Int = 0
    override val range: Int = 0
    override val turnRate: Int = 0
    override val type: String = "OTHER"
    override val tags: String = "no_drop, no_drop_salvage"
    override val tech: String = "弧光阵列"
    override val noDpsInTooltip: Boolean = true
    override val number: Int = 9128
}

/** Arc Jet：整船 bloom 描边层（装配界面/战斗 decorative outline）。 */
object Wpn_astd_arc_jet_bloom : WeaponDataEntry() {
    override val id: String = "astd_arc_jet_bloom"
    override val name: String = weaponName(id)
    override val tier: Int = 5
    override val baseValue: Int = 0
    override val range: Int = 0
    override val turnRate: Int = 0
    override val type: String = "OTHER"
    override val tags: String = "no_drop, no_drop_salvage"
    override val tech: String = "弧光阵列"
    override val noDpsInTooltip: Boolean = true
    override val number: Int = 9130
}

/** Plasma Arch：整船 bloom 描边层（装配界面/战斗 decorative outline）。 */
object Wpn_astd_plasma_arch_bloom : WeaponDataEntry() {
    override val id: String = "astd_plasma_arch_bloom"
    override val name: String = weaponName(id)
    override val tier: Int = 5
    override val baseValue: Int = 0
    override val range: Int = 0
    override val turnRate: Int = 0
    override val type: String = "OTHER"
    override val tags: String = "no_drop, no_drop_salvage"
    override val tech: String = "弧光阵列"
    override val noDpsInTooltip: Boolean = true
    override val number: Int = 9129
}

/** Negentropy Edge：追击虚粒子真实导弹体（脚本生成，实体碰撞结算）。 */
object Wpn_astd_virtual_particle_mote_launcher : WeaponDataEntry(), SsProjMissileOutputs {
    override val id: String = "astd_virtual_particle_mote_launcher"
    override val name: String = weaponName(id)
    override val tier: Int = 5
    override val baseValue: Int = 0
    override val range: Int = 1400
    override val damagePerShot: Int = 150
    override val emp: Int = 300
    override val turnRate: Int = 30
    override val type: String = "ENERGY"
    override val projSpeed: Int = 1440
    override val flightTime: Double = 2.0
    override val projHitpoints: Int = 10000
    override val tags: String = "astd_signature"
    override val groupTag: String = ""
    override val tech: String = "弧光阵列"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val noDpsInTooltip: Boolean = true
    override val number: Int = 9127

    override val projSpec: MissileProjSpec = MissileProjSpec(
        id = "astd_virtual_particle_mote",
        missileType = "MISSILE",
        onHitEffect = "cn.kasuminova.astd.combat.hullmods.arc.ASTDPursuitVirtualParticleOnHitEffect",
        sprite = "graphics/textures/BUtil_NONE.png",
        size = Vec2i(4, 4),
        center = Vec2(2, 2),
        collisionRadius = 7,
        collisionClass = "MISSILE_NO_FF",
        explosionColor = Rgba(120, 210, 255, 180),
        explosionRadius = 36,
        armingTime = 0.05,
        flameoutTime = 0.5,
        noEngineGlowTime = 999.0,
        fadeTime = 0.25,
        engineSpec = MissileEngineSpec(
            turnAcc = 1800,
            turnRate = 1440,
            acc = 1800,
            dec = 1600,
        ),
        engineSlots = emptyList(),
    )
}

object Wpn_astd_spc3 : WeaponDataEntry(), SsProjProjectileOutputs {
    override val id: String = "astd_spc3"
    override val name: String = weaponName(id)
    override val tier: Int = 3
    override val baseValue: Int = 50000
    override val range: Int = 700
    override val damagePerSecond: Int = 2239
    override val damagePerShot: Int = 150

    // 非 Beam：用 chargedown/burst 描述射速，避免 tooltip 统计除 0 溢出
    override val chargedown: Double = 0.067
    override val burstSize: Int = 1
    override val burstDelay: Double = 0.0
    override val turnRate: Int = 30

    // 设计案：弹匣式近距爆发主炮（打空 -> 等整匣装填）
    override val ammo: Int = 24

    // 每 4s 装填 8 发，维持三段式再装填节奏。
    override val ammoPerSec: Double = 2.0
    override val reloadSize: Int = 8
    override val type: String = "ENERGY"
    override val energyPerShot: Int = 150
    override val energyPerSecond: Int = 2239
    override val projSpeed: Int = 1350
    override val tags: String = "astd_signature"
    override val groupTag: String = "astd"
    override val tech: String = "弧光阵列"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val number: Int = 9002

    override val projSpec: ProjectileProjSpec = ProjectileProjSpec.standard(
        id = "astd_spc3_shot",
        spawnType = ProjectileSpawnType.BALLISTIC,
        // 弹体视觉全部交给 ASTD VFX 管线；原版弹丸 core/fringe 置透明，
        // 否则偏射弹丸会按弹体朝向渲染出亮头，与速度向拖尾错位（表现为“拖尾歪向反方向”）。
        fringeColor = Rgba(120, 200, 255, 0),
        coreColor = Rgba(220, 245, 255, 0),
    )
}

/**
 * ARC-13「三位一体」：大型能量武器（蓝稀有）。
 *
 * 机制：burst 3 发；每发脚本生成一次弧光（命中点即时结算一次性伤害）。
 * 因此 weapon_data.csv 中 damage 字段仅用于 UI/AI 基础信息，不做实际伤害来源。
 */

/** 电荷针刺：小型能量弹匣速射（量产）。护盾命中淤积抬维持，船体命中概率泄放 EMP 电弧（机制见 ChargeNeedleOnHitEffect）。 */
object Wpn_astd_charge_needle : WeaponDataEntry(), SsProjProjectileOutputs {
    override val id: String = "astd_charge_needle"
    override val name: String = weaponName(id)
    override val tier: Int = 1
    override val rarity: Int = 1
    override val baseValue: Int = 6000
    override val range: Int = 700
    override val damagePerSecond: Int = 1000
    override val damagePerShot: Int = 50
    override val emp: Int = 100
    override val turnRate: Int = 30
    override val ops: Int = 9

    // 非 Beam：用 chargedown/burst 描述射速（20 发/s），避免 tooltip 统计除 0 溢出
    override val chargedown: Double = 0.05
    override val burstSize: Int = 1
    override val burstDelay: Double = 0.0

    // 弹匣三列：30 发弹匣，2.5 发/s 回复，每次装填 15 发
    override val ammo: Int = 30
    override val ammoPerSec: Double = 2.5
    override val reloadSize: Int = 15
    override val type: String = "ENERGY"
    override val energyPerShot: Int = 50
    override val energyPerSecond: Int = 125
    override val projSpeed: Int = 1350
    override val tags: String = "astd_production"
    override val groupTag: String = "astd"
    override val tech: String = "弧光阵列"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val customPrimary: String = SsI18n.t("weapon.$id.tooltip.customPrimary")
    override val customPrimaryHL: String = SsI18n.t("weapon.$id.tooltip.customPrimaryHL")
    override val number: Int = 9210

    override val projSpec: ProjectileProjSpec = ProjectileProjSpec(
        id = "astd_charge_needle_shot",
        spawnType = ProjectileSpawnType.BALLISTIC,
        onFireEffect = "cn.kasuminova.astd.combat.effect.generic.ProjectileSpecOnFireDispatcher",
        onHitEffect = "cn.kasuminova.astd.combat.effect.arc.ChargeNeedleOnHitEffect",
        collisionClass = "PROJECTILE_FF",
        collisionClassByFighter = "PROJECTILE_FIGHTER",
        // 原版 projectile visual 必须不可见（length/width=2 + 色 alpha=0 + BUtil_NONE）。
        length = 2.0,
        width = 2.0,
        // fadeTime=0.2：给超射程后的原版弹体一段滑行窗口，令代码 VFX 拖尾能跟随淡出（否则 fadeTime=0 会被引擎即刻移除、
        // 拖尾在射程环处骤消）。原版弹体已 alpha=0 全隐，此窗口不会造成视觉穿帮。
        fadeTime = 0.2,
        fringeColor = Rgba(140, 200, 255, 0),
        coreColor = Rgba(225, 242, 255, 0),
        textureScrollSpeed = 0.0,
        pixelsPerTexel = 1.0,
        bulletSprite = "graphics/textures/BUtil_NONE.png",
    )
}

/** 重型电荷针刺：中型能量弹匣速射（量产）。机制与小型完全同源（供弹深度与持续火力加倍）。 */
object Wpn_astd_heavy_charge_needle : WeaponDataEntry(), SsProjProjectileOutputs {
    override val id: String = "astd_heavy_charge_needle"
    override val name: String = weaponName(id)
    override val tier: Int = 1
    override val rarity: Int = 1
    override val baseValue: Int = 14000
    override val range: Int = 700
    override val damagePerSecond: Int = 1000
    override val damagePerShot: Int = 50
    override val emp: Int = 100
    override val turnRate: Int = 30
    override val ops: Int = 17

    // 非 Beam：用 chargedown/burst 描述射速（20 发/s），避免 tooltip 统计除 0 溢出
    override val chargedown: Double = 0.05
    override val burstSize: Int = 1
    override val burstDelay: Double = 0.0

    // 弹匣三列：60 发弹匣，5 发/s 回复，每次装填 30 发
    override val ammo: Int = 60
    override val ammoPerSec: Double = 5.0
    override val reloadSize: Int = 30
    override val type: String = "ENERGY"
    override val energyPerShot: Int = 50
    override val energyPerSecond: Int = 250
    override val projSpeed: Int = 1350
    // 窄射界挂载（如野狼 WS 004 仅 5° 弧）下原版 AutofireAI 的目标采纳测试按武器弧判定会拒绝目标
    // （实机诊断：aiTarget=null 永不击发）；对齐 shockrepeater 先例补 25° AI 弧度补偿。
    override val extraArcForAI: Int = 25
    override val tags: String = "astd_production"
    override val groupTag: String = "astd"
    override val tech: String = "弧光阵列"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val customPrimary: String = SsI18n.t("weapon.$id.tooltip.customPrimary")
    override val customPrimaryHL: String = SsI18n.t("weapon.$id.tooltip.customPrimaryHL")
    override val number: Int = 9211

    override val projSpec: ProjectileProjSpec = ProjectileProjSpec(
        id = "astd_heavy_charge_needle_shot",
        spawnType = ProjectileSpawnType.BALLISTIC,
        onFireEffect = "cn.kasuminova.astd.combat.effect.generic.ProjectileSpecOnFireDispatcher",
        onHitEffect = "cn.kasuminova.astd.combat.effect.arc.ChargeNeedleOnHitEffect",
        collisionClass = "PROJECTILE_FF",
        collisionClassByFighter = "PROJECTILE_FIGHTER",
        // 原版 projectile visual 必须不可见（length/width=2 + 色 alpha=0 + BUtil_NONE）。
        length = 2.0,
        width = 2.0,
        // fadeTime=0.2：给超射程后的原版弹体一段滑行窗口，令代码 VFX 拖尾能跟随淡出（否则 fadeTime=0 会被引擎即刻移除、
        // 拖尾在射程环处骤消）。原版弹体已 alpha=0 全隐，此窗口不会造成视觉穿帮。
        fadeTime = 0.2,
        fringeColor = Rgba(140, 200, 255, 0),
        coreColor = Rgba(225, 242, 255, 0),
        textureScrollSpeed = 0.0,
        pixelsPerTexel = 1.0,
        bulletSprite = "graphics/textures/BUtil_NONE.png",
    )
}

/**
 * 电驱加速炮：中型实弹散射连发（量产，规格 03 §1.1）。
 *
 * 「散射 2」不走 weapon_data.csv（原版无 projectileCount 列）——由 `.wpn` 双炮管 offsets +
 * barrelMode LINKED 承担，连发 4 走 burst 列，合计每触发 8 弹；
 * 不稳定装药随机附加伤害走 `.proj` onHitEffect，净空加速射程加成走 `.wpn` everyFrameEffect。
 */
object Wpn_astd_electric_drive_accelerator : WeaponDataEntry(), SsProjProjectileOutputs {
    override val id: String = "astd_electric_drive_accelerator"
    override val name: String = weaponName(id)
    override val tier: Int = 1
    override val rarity: Int = 1
    override val baseValue: Int = 11000
    override val range: Int = 800
    // 持续 2 弹/s × 80（设计案备弹经济口径）
    override val damagePerSecond: Int = 160
    override val damagePerShot: Int = 80
    override val impact: Int = 4
    override val turnRate: Int = 30
    override val ops: Int = 15

    // 发射冷却 1s + 连发 4（散射 2 不在此表，走 .wpn LINKED 双管）
    override val chargedown: Double = 1.0
    override val burstSize: Int = 4
    override val burstDelay: Double = 0.15

    // 弹匣三列：30 发弹匣，2 发/s 回复（重装 4s/+8），每次装填 8 发
    override val ammo: Int = 30
    override val ammoPerSec: Double = 2.0
    override val reloadSize: Int = 8
    override val type: String = "KINETIC"
    // 每颗子弹 88（裁定口径）；176 = 2 弹/s × 88
    override val energyPerShot: Int = 88
    override val energyPerSecond: Int = 176
    override val projSpeed: Int = 1000
    // 中等精确度（对照原版重型针刺 1/10）
    override val minSpread: Double = 1.0
    override val maxSpread: Double = 8.0
    override val spreadPerShot: Double = 0.5
    override val spreadDecayPerSec: Double = 4.0
    override val tags: String = "astd_production"
    override val groupTag: String = "astd"
    override val tech: String = "弧光阵列"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val customPrimary: String = SsI18n.t("weapon.$id.tooltip.customPrimary")
    override val customPrimaryHL: String = SsI18n.t("weapon.$id.tooltip.customPrimaryHL")
    override val number: Int = 9213

    override val projSpec: ProjectileProjSpec = ProjectileProjSpec(
        id = "astd_electric_drive_accelerator_shot",
        spawnType = ProjectileSpawnType.BALLISTIC,
        onFireEffect = "cn.kasuminova.astd.combat.effect.generic.ProjectileSpecOnFireDispatcher",
        onHitEffect = "cn.kasuminova.astd.combat.effect.arc.ElectricDriveAcceleratorOnHitEffect",
        collisionClass = "PROJECTILE_FF",
        collisionClassByFighter = "PROJECTILE_FIGHTER",
        // 原版 projectile visual 必须不可见（length/width=2 + 色 alpha=0 + BUtil_NONE）。
        length = 2.0,
        width = 2.0,
        // fadeTime=0.2：给超射程后的原版弹体一段滑行窗口，令代码 VFX 拖尾能跟随淡出（否则 fadeTime=0 会被引擎即刻移除、
        // 拖尾在射程环处骤消）。原版弹体已 alpha=0 全隐，此窗口不会造成视觉穿帮。
        fadeTime = 0.2,
        fringeColor = Rgba(235, 242, 250, 0),
        coreColor = Rgba(255, 255, 255, 0),
        textureScrollSpeed = 0.0,
        pixelsPerTexel = 1.0,
        bulletSprite = "graphics/textures/BUtil_NONE.png",
    )
}

/**
 * “穷距”相位轨道炮：大型实弹站桩演算主炮（量产，规格 05 §1.1）。
 *
 * 持续演算叠层（同目标命中 +1 层伤害/射速，异目标按保留比例折算，3s 窗口后按速率衰减）
 * 走 `.proj` onHitEffect + Weapon 级叠层 Buff；完美精度 + 非常慢转向走 spec 面板。
 */
object Wpn_astd_qiongjue_phase_railgun : WeaponDataEntry(), SsProjProjectileOutputs {
    override val id: String = "astd_qiongjue_phase_railgun"
    override val name: String = weaponName(id)
    override val tier: Int = 2
    override val baseValue: Int = 25000
    override val range: Int = 1200
    override val damagePerSecond: Int = 300
    override val damagePerShot: Int = 600
    override val turnRate: Int = 8
    override val ops: Int = 27

    // 定案 2s 开火间隔（非 beam 必须走 chargedown/burst，避免 tooltip 统计除 0）
    override val chargedown: Double = 2.0
    override val burstSize: Int = 1
    override val burstDelay: Double = 0.0

    override val type: String = "KINETIC"
    // 单发 900（辐伤比 1.5）；450 = 900 / 2s
    override val energyPerShot: Int = 900
    override val energyPerSecond: Int = 450
    // 与高斯炮同速（2026-07-29 审批裁定，弃 1500 提案）
    override val projSpeed: Int = 1200
    override val turnRateStr: String = "非常慢"
    override val accuracyStr: String = "完美"
    // 完美精度（对齐原版高斯炮口径）
    override val autofireAccBonus: Int = 1
    override val tags: String = "astd_production"
    override val groupTag: String = "astd"
    override val tech: String = "弧光阵列"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val customPrimary: String = SsI18n.t("weapon.$id.tooltip.customPrimary")
    override val customPrimaryHL: String = SsI18n.t("weapon.$id.tooltip.customPrimaryHL")
    override val number: Int = 9214

    override val projSpec: ProjectileProjSpec = ProjectileProjSpec(
        id = "astd_qiongjue_phase_railgun_shot",
        spawnType = ProjectileSpawnType.BALLISTIC,
        onFireEffect = "cn.kasuminova.astd.combat.effect.generic.ProjectileSpecOnFireDispatcher",
        onHitEffect = "cn.kasuminova.astd.combat.effect.arc.qiongjue.QiongjuePhaseRailgunOnHitEffect",
        collisionClass = "PROJECTILE_FF",
        collisionClassByFighter = "PROJECTILE_FIGHTER",
        // 原版 projectile visual 必须不可见（length/width=2 + 色 alpha=0 + BUtil_NONE）。
        length = 2.0,
        width = 2.0,
        // fadeTime=0.2：给超射程后的原版弹体一段滑行窗口，令代码 VFX 拖尾能跟随淡出（否则 fadeTime=0 会被引擎即刻移除、
        // 拖尾在射程环处骤消）。原版弹体已 alpha=0 全隐，此窗口不会造成视觉穿帮。
        fadeTime = 0.2,
        fringeColor = Rgba(200, 225, 255, 0),
        coreColor = Rgba(255, 255, 255, 0),
        textureScrollSpeed = 0.0,
        pixelsPerTexel = 1.0,
        bulletSprite = "graphics/textures/BUtil_NONE.png",
    )
}

/**
 * 正电子冲击波：小型能量点防御近炸弹（量产，规格 06 §1.1）。
 *
 * 无触碰体积（`.proj` collisionClass="NONE"，无 onHit 路径）+ 近炸/满射程双引爆
 * 走 `.proj` onFireEffect 注册引信脚本；锥状冲击结算复用基建 ConeImpactHandler。
 * 弹体 VFX 追踪由 `.wpn` onFireEffect 的 ProjectileSpecOnFireDispatcher 承担（分工见规格 §0-2）。
 */
object Wpn_astd_positron_shockwave : WeaponDataEntry(), SsProjProjectileOutputs {
    override val id: String = "astd_positron_shockwave"
    override val name: String = weaponName(id)
    override val tier: Int = 1
    override val baseValue: Int = 2500
    override val range: Int = 600
    // 200 ÷ 1.5s 折算 tooltip 统计
    override val damagePerSecond: Int = 133
    override val damagePerShot: Int = 200
    override val turnRate: Int = 45
    override val ops: Int = 6

    // 发射间隔 1.5s（非 Beam 用 chargedown 描述射速，避免 tooltip 统计除 0）
    override val chargedown: Double = 1.5
    override val burstSize: Int = 1
    override val burstDelay: Double = 0.0

    override val type: String = "FRAGMENTATION"
    override val energyPerShot: Int = 100
    // 100 ÷ 1.5s 折算
    override val energyPerSecond: Int = 67
    override val projSpeed: Int = 900
    // 弹体原版寿命：原版会将其钳制为 range ÷ projSpeed（≈0.667s），故取值 ≥ 该值即可（0.75 留余量）。
    // 淡出与满射程同帧发生，引信脚本满射程判定先于淡出兜底执行（第四轮烟测实证钳制机制）。
    override val flightTime: Double = 0.75
    override val aiHints: Set<AiHint> = setOf(AiHint.PD)
    override val tags: String = "astd_production"
    override val groupTag: String = "astd"
    override val tech: String = "弧光阵列"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val customPrimary: String = SsI18n.t("weapon.$id.tooltip.customPrimary")
    override val customPrimaryHL: String = SsI18n.t("weapon.$id.tooltip.customPrimaryHL")
    override val number: Int = 9215

    override val projSpec: ProjectileProjSpec = ProjectileProjSpec(
        id = "astd_positron_shockwave_shot",
        spawnType = ProjectileSpawnType.BALLISTIC,
        // 引信脚本注册（规格 §0-2 分工：弹体 VFX 追踪在 .wpn 侧 dispatcher）
        onFireEffect = "cn.kasuminova.astd.combat.effect.arc.PositronShockwaveOnFireEffect",
        // 无触碰体积，无 onHit 路径
        onHitEffect = null,
        // 无触碰体积的真实实现（规格 §0-1：ProjectileProjSpec 无 collisionRadius 字段）
        collisionClass = "NONE",
        // 规格 §1.1「置空不写」与实机冲突：原版 ProjectileSpec 加载强制要求该键（缺键 RuntimeException）；
        // 与 collisionClass 同写 NONE（01 special_items order 判例同族，规格文本待主代理修订）。
        collisionClassByFighter = "NONE",
        // 原版 projectile visual 必须不可见（length/width=2 + 色 alpha=0 + BUtil_NONE）。
        length = 2.0,
        width = 2.0,
        // fadeTime=0.2：给超射程后的原版弹体一段滑行窗口，令代码 VFX 拖尾能跟随淡出（否则 fadeTime=0 会被引擎即刻移除、
        // 拖尾在射程环处骤消）。原版弹体已 alpha=0 全隐，此窗口不会造成视觉穿帮。
        fadeTime = 0.2,
        fringeColor = Rgba(140, 200, 255, 0),
        coreColor = Rgba(240, 248, 255, 0),
        textureScrollSpeed = 0.0,
        pixelsPerTexel = 1.0,
        bulletSprite = "graphics/textures/BUtil_NONE.png",
    )
}

/**
 * “七星”折跃发射器：大型能量点防御超规格（规格 07 §1.1）。
 *
 * 射弹不做正常飞行：发射即折跃至目标位置并闪光十字爆炸，连跳/对舰终结全部脚本结算
 * （`.proj` onFireEffect 挂 SevenStarsOnFireEffect，collisionClass=NONE 无触碰/无 onHit 路径）。
 * 弹体视觉全程隐藏（texTrail 管线不登记，规格 §3.1 决策）；P6 前 no_drop 仅 dev 测试。
 */
object Wpn_astd_seven_stars : WeaponDataEntry(), SsProjProjectileOutputs {
    override val id: String = "astd_seven_stars"
    override val name: String = weaponName(id)
    override val tier: Int = 3
    // 超规格对标 aod7（2026-07-29 审批裁定，弃 60000 提案）
    override val baseValue: Int = 150000
    override val range: Int = 800
    // 250 / 2s，tooltip 展示口径
    override val damagePerSecond: Int = 125
    override val damagePerShot: Int = 250
    // 面板 EMP 为 0；v5 终结 EMP 是脚本结算，不进面板
    override val emp: Int = 0
    override val impact: Int = 0
    override val turnRate: Int = 30
    override val ops: Int = 28
    override val type: String = "ENERGY"
    override val energyPerShot: Int = 750
    // 750 / 2s
    override val energyPerSecond: Int = 375
    // 射速 2s/发
    override val chargedown: Double = 2.0
    override val burstSize: Int = 1
    override val burstDelay: Double = 0.0
    // 名义值；弹体由脚本瞬移接管，speed 仅影响 AI 预判与默认寿命（已被 flightTime 覆盖）
    override val projSpeed: Int = 3000
    // 显式寿命上限保险（规格 §0-2）：连跳预算 ≈3.4s，默认 range/projSpeed≈0.27s 会在第 2 跳前被引擎回收
    override val flightTime: Double = 6.0
    override val aiHints: Set<AiHint> = setOf(AiHint.PD)
    // P6 前口径；P6 后改特定赏金/主线限定（90-plan §14）
    override val tags: String = "no_drop, no_drop_salvage"
    override val groupTag: String = "astd"
    override val tech: String = "弧光阵列"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val customPrimary: String = SsI18n.t("weapon.$id.tooltip.customPrimary")
    override val customPrimaryHL: String = SsI18n.t("weapon.$id.tooltip.customPrimaryHL")
    override val number: Int = 9216

    override val projSpec: ProjectileProjSpec = ProjectileProjSpec(
        id = "astd_seven_stars_shot",
        spawnType = ProjectileSpawnType.BALLISTIC,
        onFireEffect = "cn.kasuminova.astd.combat.effect.arc.SevenStarsOnFireEffect",
        // collisionClass=NONE 永无命中回调
        onHitEffect = null,
        // 射弹发射即折跃，碰撞类别 NONE 杜绝瞬移间隙帧的原版触碰结算（规格 §0-1）
        collisionClass = "NONE",
        // 规格 §1.1「置空不写」与 06 组实机判例冲突：原版 ProjectileSpec 加载强制要求该键
        // （缺键 RuntimeException）；与 collisionClass 同写 NONE（vanilla inimical_emanation_shot.proj 先例，
        // 规格文本待主代理修订）。
        collisionClassByFighter = "NONE",
        // 原版 projectile visual 必须不可见（length/width=2 + 色 alpha=0 + BUtil_NONE）。
        length = 2.0,
        width = 2.0,
        // fadeTime=0.2：脚本以 removeEntity 主动收口，此窗口仅为引擎 fade 回收路径留滑行余量（规格 §0-2）。
        fadeTime = 0.2,
        fringeColor = Rgba(120, 200, 255, 0),
        coreColor = Rgba(220, 245, 255, 0),
        textureScrollSpeed = 0.0,
        pixelsPerTexel = 1.0,
        bulletSprite = "graphics/textures/BUtil_NONE.png",
    )
}
