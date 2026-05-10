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

object Wpn_astd_gsp12 : WeaponDataEntry(), SsProjProjectileOutputs {
    override val id: String = "astd_gsp12"
    override val name: String = weaponName(id)
    override val tier: Int = 3
    override val baseValue: Int = 50000
    override val range: Int = 1000
    override val damagePerShot: Int = 260

    // 设计案：间接压制、延迟命中；单发间隔约 1.6–2.4s，这里取 2.0s。
    override val damagePerSecond: Int = 130

    // 非 Beam：用 chargedown/burst 描述射速，避免 tooltip 统计除 0 溢出
    override val chargedown: Double = 2.0
    override val burstSize: Int = 1
    override val burstDelay: Double = 0.0
    override val turnRate: Int = 30
    override val type: String = "ENERGY"
    override val energyPerShot: Int = 200
    override val energyPerSecond: Int = 100
    override val projSpeed: Int = 700

    // 设计案：投射体可被点防击落（击落门槛 400–900）
    override val projHitpoints: Int = 650
    override val tags: String = "astd_signature"
    override val groupTag: String = "astd"
    override val tech: String = "透镜矩阵"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val number: Int = 9004

    override val projSpec: ProjectileProjSpec = ProjectileProjSpec.standard(
        id = "astd_gsp12_rift",
        spawnType = ProjectileSpawnType.BALLISTIC,
        onHitEffect = "cn.kasuminova.astd.combat.effect.lens.production.Gsp12ShearOnHitEffect",
    )
}

object Wpn_astd_mnl3 : WeaponDataEntry(), SsProjMissileOutputs {
    override val id: String = "astd_mnl3"
    override val name: String = weaponName(id)
    override val rarity: Int = 2
    override val baseValue: Int = 12000
    override val range: Int = 1000
    override val damagePerSecond: Int = 512
    override val damagePerShot: Int = 256

    // 非 Beam：用 chargedown/burst 描述射速，避免 tooltip 统计除 0 溢出
    override val chargedown: Double = 0.5
    override val burstSize: Int = 1
    override val burstDelay: Double = 0.0
    override val turnRate: Int = 30
    override val ops: Int = 12
    override val ammo: Int = 10

    // 设计案：再生 1 发 / 8–14s，这里取 10s。
    override val ammoPerSec: Double = 1.0 / 10.0
    override val reloadSize: Int = 1
    override val type: String = "FRAGMENTATION"

    // 导弹武器不应产生幅能（flux），避免 tooltip 误导
    override val energyPerShot: Int = 0
    override val aiHints: Set<AiHint> = setOf(AiHint.PD)
    override val projSpeed: Int = 700

    // 网雷投射体飞行时间（必须 >0，否则导弹可能在发射后立刻消失）
    override val flightTime: Double = 1.8

    // 弹体耐久（proj hitpoints）；为 0 会导致导弹发射后立刻消失
    override val projHitpoints: Int = 500
    override val tags: String = "astd_production"
    override val groupTag: String = "astd"
    override val tech: String = "透镜矩阵"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val number: Int = 9014

    override val projSpec: MissileProjSpec = MissileProjSpec(
        id = "astd_mnl3_mine",
        missileType = "MISSILE",
        onFireEffect = "cn.kasuminova.astd.combat.effect.generic.ProjectileSpecOnFireDispatcher",
        onHitEffect = "cn.kasuminova.astd.combat.effect.lens.production.Mnl3InertialDragOnHitEffect",
        sprite = "graphics/missiles/heavy_mine.png",
        size = Vec2i(12, 12),
        center = Vec2(6, 6),
        collisionRadius = 10,
        collisionClass = "MISSILE_FF",
        explosionColor = Rgba(120, 200, 255, 255),
        explosionRadius = 120,
        armingTime = null,
        flameoutTime = 1.0,
        noEngineGlowTime = 0.5,
        fadeTime = 0.5,
        engineSpec = MissileEngineSpec(
            turnAcc = 0,
            turnRate = 0,
            acc = 50,
            dec = 100,
        ),
        engineSlots = emptyList(),
    )
}

object Wpn_astd_sgl8 : WeaponDataEntry(), SsProjMissileOutputs {
    override val id: String = "astd_sgl8"
    override val name: String = weaponName(id)
    override val rarity: Int = 2
    override val baseValue: Int = 12000
    override val range: Int = 2600

    // 策划案：
    // - 弹药 80
    // - 单发伤害 250
    // - 冷却 3s
    // - 弹体速度 2000
    // - 弹体 HP 600
    override val damagePerShot: Int = 250
    override val damagePerSecond: Int = 83

    // 非 Beam：用 chargedown/burst 描述射速，避免 tooltip 统计除 0 溢出
    override val chargedown: Double = 3.0
    override val burstSize: Int = 1
    override val burstDelay: Double = 0.0
    override val turnRate: Int = 30
    override val ops: Int = 11

    // 策划案：有限弹药，不再生
    override val ammo: Int = 80
    override val ammoPerSec: Double = 0.0
    override val reloadSize: Int = 0
    override val type: String = "ENERGY"

    // 导弹武器不应产生幅能（flux），避免 tooltip 误导
    override val energyPerShot: Int = 0
    override val energyPerSecond: Int = 0
    override val projSpeed: Int = 2000

    // 飞行时间（必须 >0，否则导弹可能在发射后立刻消失）。
    // 速度提高后将 flightTime 下调，但仍留余量给转向/重定向窗口。
    override val flightTime: Double = 2.5
    override val projHitpoints: Int = 600
    override val tags: String = "astd_production"
    override val groupTag: String = "astd"
    override val tech: String = "透镜矩阵"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")

    override val customPrimary: String = SsI18n.t("weapon.$id.tooltip.customPrimary")
    override val customPrimaryHL: String = SsI18n.t("weapon.$id.tooltip.customPrimaryHL")
    override val number: Int = 9015

    override val projSpec: MissileProjSpec = MissileProjSpec(
        id = "astd_sgl8_swarm",
        missileType = "MISSILE",
        onFireEffect = "cn.kasuminova.astd.combat.effect.generic.ProjectileSpecOnFireDispatcher",
        onHitEffect = "cn.kasuminova.astd.combat.effect.lens.signature.singularity.SingularityOnHitEffect",
        sprite = "graphics/missiles/missile_annihilator.png",
        size = Vec2i(4, 12),
        center = Vec2(2, 6),
        collisionRadius = 8,
        collisionClass = "MISSILE_NO_FF",
        explosionColor = Rgba(120, 200, 255, 255),
        explosionRadius = 50,
        armingTime = null,
        flameoutTime = 0.5,
        noEngineGlowTime = 0.25,
        fadeTime = 0.25,
        engineSpec = MissileEngineSpec(
            turnAcc = 800,
            turnRate = 200,
            acc = 600,
            dec = 200,
        ),
        // 奇点系导弹外观完全由代码 VFX 承担：去除原版引擎辉光/尾焰，避免“蓝色喷口/尾气”穿帮。
        engineSlots = emptyList(),
    )
}

object Wpn_astd_jmb2 : WeaponDataEntry(), SsProjProjectileOutputs {
    override val id: String = "astd_jmb2"
    override val name: String = weaponName(id)
    override val rarity: Int = 2
    override val baseValue: Int = 12000
    override val range: Int = 1100
    override val damagePerSecond: Int = 320
    override val damagePerShot: Int = 160

    // 非 Beam：用 chargedown/burst 描述射速，避免 tooltip 统计除 0 溢出
    override val chargedown: Double = 0.5
    override val burstSize: Int = 1
    override val burstDelay: Double = 0.0
    override val turnRate: Int = 30
    override val ops: Int = 12
    override val type: String = "ENERGY"
    override val energyPerShot: Int = 200
    override val energyPerSecond: Int = 400
    override val projSpeed: Int = 2200
    override val tags: String = "astd_production"
    override val groupTag: String = "astd"
    override val tech: String = "透镜矩阵"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val number: Int = 9016

    override val projSpec: ProjectileProjSpec = ProjectileProjSpec.standard(
        id = "astd_jmb2_beam",
        spawnType = ProjectileSpawnType.BALLISTIC,
        onHitEffect = "cn.kasuminova.astd.combat.effect.lens.production.Jmb2CoherenceJammingOnHitEffect",
        fringeColor = Rgba(120, 200, 255, 235),
        coreColor = Rgba(220, 245, 255, 200),
    )
}

object Wpn_astd_fdp4 : WeaponDataEntry(), SsProjMissileOutputs {
    override val id: String = "astd_fdp4"
    override val name: String = weaponName(id)
    override val tier: Int = 2
    override val baseValue: Int = 12000
    override val range: Int = 1400

    // 策划案（v0）：600–950（取 850），表现“裂解”更适合 ENERGY。
    override val damagePerSecond: Int = 1700
    override val damagePerShot: Int = 850

    // 非 Beam：用 chargedown/burst 描述射速，避免 tooltip 统计除 0 溢出
    override val chargedown: Double = 0.5
    override val aiHints: Set<AiHint> = setOf(AiHint.PD)
    override val burstDelay: Double = 0.0
    override val turnRate: Int = 30
    override val ops: Int = 22

    // 策划案（v0）：6–10（取 8），再生 1/10–16s（取 12s）
    override val ammo: Int = 8
    override val ammoPerSec: Double = 1.0 / 12.0
    override val reloadSize: Int = 1
    override val type: String = "ENERGY"

    // 导弹武器不应产生幅能（flux），避免 tooltip 误导
    override val energyPerShot: Int = 0
    override val energyPerSecond: Int = 0
    override val projSpeed: Int = 600

    // 延迟裂解投射体飞行时间（必须 >0，否则导弹可能在发射后立刻消失）
    override val flightTime: Double = 2.6

    // 弹体耐久（proj hitpoints）；为 0 会导致导弹发射后立刻消失
    override val projHitpoints: Int = 300
    override val tags: String = "astd_rare"
    override val groupTag: String = "astd"
    override val tech: String = "透镜矩阵"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val number: Int = 9017

    override val projSpec: MissileProjSpec = MissileProjSpec(
        id = "astd_fdp4_charge",
        missileType = "MISSILE",
        onFireEffect = "cn.kasuminova.astd.combat.effect.generic.ProjectileSpecOnFireDispatcher",
        sprite = "graphics/missiles/missile_annihilator.png",
        size = Vec2i(6, 16),
        center = Vec2(3, 8),
        collisionRadius = 10,
        collisionClass = "MISSILE_FF",
        // 紫红熔核
        explosionColor = Rgba(255, 170, 230, 255),
        explosionRadius = 70,
        armingTime = 1.0,
        flameoutTime = 0.5,
        noEngineGlowTime = 0.25,
        fadeTime = 0.25,
        engineSpec = MissileEngineSpec(
            turnAcc = 200,
            turnRate = 40,
            acc = 400,
            dec = 100,
        ),
        engineSlots = listOf(
            MissileEngineSlot(
                id = "ES1",
                loc = Vec2i(-8, 0),
                style = "CUSTOM",
                styleSpec = MissileEngineSlotStyleSpec(
                    mode = "QUAD_STRIP",
                    engineColor = Rgba(255, 170, 230, 255),
                    glowSizeMult = 1.5,
                    contrailDuration = 1.0,
                    contrailWidthMult = 1.0,
                    contrailWidthAddedFractionAtEnd = 2.0,
                    contrailMinSeg = 5,
                    contrailMaxSpeedMult = 0.0,
                    contrailAngularVelocityMult = 0.5,
                    contrailSpawnDistMult = 0.5,
                    contrailColor = Rgba(170, 70, 160, 110),
                    type = "GLOW",
                ),
                width = 6.0,
                length = 20.0,
                angle = 180.0,
            )
        )
    )
}

object Wpn_astd_jmb9 : WeaponDataEntry(), SsProjProjectileOutputs {
    override val id: String = "astd_jmb9"
    override val name: String = weaponName(id)
    override val tier: Int = 2
    override val baseValue: Int = 12000
    override val range: Int = 1200
    override val damagePerSecond: Int = 320
    override val damagePerShot: Int = 160

    // 非 Beam：用 chargedown/burst 描述射速，避免 tooltip 统计除 0 溢出
    override val chargedown: Double = 0.5
    override val burstSize: Int = 1
    override val burstDelay: Double = 0.0
    override val turnRate: Int = 30
    override val ops: Int = 14
    override val type: String = "ENERGY"
    override val energyPerShot: Int = 200
    override val energyPerSecond: Int = 400
    override val projSpeed: Int = 2200
    override val tags: String = "astd_rare"
    override val groupTag: String = "astd"
    override val tech: String = "透镜矩阵"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val number: Int = 9018

    override val projSpec: ProjectileProjSpec = ProjectileProjSpec.standard(
        id = "astd_jmb9_beam",
        spawnType = ProjectileSpawnType.BALLISTIC,
//        onHitEffect = "cn.kasuminova.astd.combat.effect.lens.production.Jmb9LockJammingOnHitEffect",
        fringeColor = Rgba(120, 200, 255, 235),
        coreColor = Rgba(220, 245, 255, 200),
    )
}

object Wpn_astd_mnl2 : WeaponDataEntry(), SsProjMissileOutputs {
    override val id: String = "astd_mnl2"
    override val name: String = weaponName(id)
    override val tier: Int = 2
    override val baseValue: Int = 12000
    override val range: Int = 1000
    override val damagePerSecond: Int = 512
    override val damagePerShot: Int = 256

    // 非 Beam：用 chargedown/burst 描述射速，避免 tooltip 统计除 0 溢出
    override val chargedown: Double = 0.5
    override val burstSize: Int = 1
    override val burstDelay: Double = 0.0
    override val turnRate: Int = 30
    override val ops: Int = 13
    override val ammo: Int = 10
    override val ammoPerSec: Double = 0.15
    override val reloadSize: Int = 1
    override val type: String = "FRAGMENTATION"

    // 导弹武器不应产生幅能（flux），避免 tooltip 误导
    override val energyPerShot: Int = 0
    override val energyPerSecond: Int = 0
    override val projSpeed: Int = 650

    // 网雷投射体飞行时间（必须 >0，否则导弹可能在发射后立刻消失）
    override val flightTime: Double = 2.0

    // 弹体耐久（proj hitpoints）；为 0 会导致导弹发射后立刻消失
    override val projHitpoints: Int = 500
    override val tags: String = "astd_rare"
    override val groupTag: String = "astd"
    override val tech: String = "透镜矩阵"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val number: Int = 9019

    override val projSpec: MissileProjSpec = MissileProjSpec(
        id = "astd_mnl2_mine",
        missileType = "MISSILE",
        onFireEffect = "cn.kasuminova.astd.combat.effect.generic.ProjectileSpecOnFireDispatcher",
        onHitEffect = "cn.kasuminova.astd.combat.effect.lens.rare.Mnl2PhaseCaptureOnHitEffect",
        sprite = "graphics/missiles/heavy_mine.png",
        size = Vec2i(12, 12),
        center = Vec2(6, 6),
        collisionRadius = 10,
        collisionClass = "MISSILE_FF",
        explosionColor = Rgba(120, 200, 255, 255),
        explosionRadius = 100,
        // 该投射体原始 .proj 无 armingTime
        armingTime = null,
        flameoutTime = 1.0,
        noEngineGlowTime = 0.5,
        fadeTime = 0.5,
        engineSpec = MissileEngineSpec(
            turnAcc = 0,
            turnRate = 0,
            acc = 50,
            dec = 100,
        ),
        engineSlots = emptyList(),
    )
}

object Wpn_astd_jmb_omega : WeaponDataEntry(), SsProjProjectileOutputs {
    override val id: String = "astd_jmb_omega"
    override val name: String = weaponName(id)
    override val tier: Int = 5
    override val baseValue: Int = 50000
    override val range: Int = 1400
    override val damagePerSecond: Int = 320
    override val damagePerShot: Int = 160

    // 非 Beam：用 chargedown/burst 描述射速，避免 tooltip 统计除 0 溢出
    override val chargedown: Double = 0.5
    override val burstSize: Int = 1
    override val burstDelay: Double = 0.0
    override val turnRate: Int = 30
    override val ops: Int = 24
    override val type: String = "ENERGY"
    override val energyPerShot: Int = 200
    override val energyPerSecond: Int = 400
    override val projSpeed: Int = 2400
    override val tags: String = "astd_omega"
    override val groupTag: String = "astd"
    override val tech: String = "透镜矩阵"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val number: Int = 9020

    override val projSpec: ProjectileProjSpec = ProjectileProjSpec.standard(
        id = "astd_jmb_omega_beam",
        spawnType = ProjectileSpawnType.BALLISTIC,
    )
}

object Wpn_astd_vpd_omega : WeaponDataEntry(), SsProjProjectileOutputs {
    override val id: String = "astd_vpd_omega"
    override val name: String = weaponName(id)
    override val tier: Int = 5
    override val baseValue: Int = 50000
    override val range: Int = 700
    override val damagePerSecond: Int = 320
    override val damagePerShot: Int = 160

    // 非 Beam：用 chargedown/burst 描述射速，避免 tooltip 统计除 0 溢出
    override val chargedown: Double = 0.5
    override val burstSize: Int = 1
    override val burstDelay: Double = 0.0
    override val turnRate: Int = 30
    override val ops: Int = 22
    override val type: String = "ENERGY"
    override val energyPerShot: Int = 200
    override val energyPerSecond: Int = 400
    override val projSpeed: Int = 2000
    override val tags: String = "astd_omega"
    override val groupTag: String = "astd"
    override val tech: String = "透镜矩阵"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val number: Int = 9021

    override val projSpec: ProjectileProjSpec = ProjectileProjSpec.standard(
        id = "astd_vpd_omega_arc",
        spawnType = ProjectileSpawnType.BALLISTIC,
    )
}

object Wpn_astd_mnl_omega : WeaponDataEntry(), SsProjMissileOutputs {
    override val id: String = "astd_mnl_omega"
    override val name: String = weaponName(id)
    override val tier: Int = 5
    override val baseValue: Int = 50000
    override val range: Int = 1600
    override val damagePerSecond: Int = 832
    override val damagePerShot: Int = 416

    // 非 Beam：用 chargedown/burst 描述射速，避免 tooltip 统计除 0 溢出
    override val chargedown: Double = 0.5
    override val burstSize: Int = 1
    override val burstDelay: Double = 0.0
    override val turnRate: Int = 30
    override val ops: Int = 26
    override val ammo: Int = 10
    override val ammoPerSec: Double = 0.15
    override val reloadSize: Int = 1
    override val type: String = "FRAGMENTATION"

    // 导弹武器不应产生幅能（flux），避免 tooltip 误导
    override val energyPerShot: Int = 0
    override val energyPerSecond: Int = 0
    override val projSpeed: Int = 550

    // Ω 网格布设投射体飞行时间（必须 >0，否则导弹可能在发射后立刻消失）
    override val flightTime: Double = 3.3

    // 弹体耐久（proj hitpoints）；为 0 会导致导弹发射后立刻消失
    override val projHitpoints: Int = 750
    override val tags: String = "astd_omega"
    override val groupTag: String = "astd"
    override val tech: String = "透镜矩阵"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val number: Int = 9022

    override val projSpec: MissileProjSpec = MissileProjSpec(
        id = "astd_mnl_omega_grid",
        missileType = "MISSILE",
        onFireEffect = "cn.kasuminova.astd.combat.effect.generic.ProjectileSpecOnFireDispatcher",
        sprite = "graphics/missiles/heavy_mine.png",
        size = Vec2i(14, 14),
        center = Vec2(7, 7),
        collisionRadius = 12,
        collisionClass = "MISSILE_FF",
        explosionColor = Rgba(180, 120, 255, 255),
        explosionRadius = 150,
        armingTime = null,
        flameoutTime = 1.0,
        noEngineGlowTime = 0.5,
        fadeTime = 0.5,
        engineSpec = MissileEngineSpec(
            turnAcc = 0,
            turnRate = 0,
            acc = 60,
            dec = 120,
        ),
        engineSlots = emptyList(),
    )
}

object Wpn_astd_ftb_omega : WeaponDataEntry(), SsProjProjectileOutputs {
    override val id: String = "astd_ftb_omega"
    override val name: String = weaponName(id)
    override val tier: Int = 5
    override val baseValue: Int = 50000
    override val range: Int = 1200
    override val damagePerSecond: Int = 320
    override val damagePerShot: Int = 160

    // 非 Beam：用 chargedown/burst 描述射速，避免 tooltip 统计除 0 溢出
    override val chargedown: Double = 0.5
    override val burstSize: Int = 1
    override val burstDelay: Double = 0.0
    override val turnRate: Int = 30
    override val ops: Int = 24
    override val type: String = "ENERGY"
    override val energyPerShot: Int = 200
    override val energyPerSecond: Int = 400
    override val projSpeed: Int = 2400
    override val tags: String = "astd_omega"
    override val groupTag: String = "astd"
    override val tech: String = "透镜矩阵"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val number: Int = 9023

    override val projSpec: ProjectileProjSpec = ProjectileProjSpec.standard(
        id = "astd_ftb_omega_beam",
        spawnType = ProjectileSpawnType.BALLISTIC,
    )
}

/** Event Horizon：时空停滞场（系统武器：终结坍缩炮；原版 beam 负责命中/伤害，脚本负责系统联动与 VFX）。 */
object Wpn_astd_stasis_collapse_emitter : WeaponDataEntry() {
    override val id: String = "astd_stasis_collapse_emitter"
    override val name: String = weaponName(id)
    override val tier: Int = 5
    override val baseValue: Int = 0
    override val range: Int = 3400

    // tooltip 统计用；实际伤害会在 everyFrameEffect 内按“捕获能量强度”动态缩放
    override val damagePerSecond: Int = 1000
    override val damagePerShot: Int = 0
    override val emp: Int = 0
    override val impact: Int = 0

    override val turnRate: Int = 30
    override val type: String = "HIGH_EXPLOSIVE"
    override val energyPerShot: Int = 0
    override val energyPerSecond: Int = 0

    // Beam：chargeup/chargedown 描述束体建立/消散
    override val chargeup: Double = 1.5
    override val chargedown: Double = 0.65
    override val burstSize: Int = 0
    override val burstDelay: Double = 0.0

    override val beamSpeed: Int = 10000
    override val projSpeed: Int = 0

    override val tags: String = "astd_signature"

    // 系统联动/隐藏武器：不参与常规分组
    override val groupTag: String = ""
    override val tech: String = "透镜矩阵"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val customPrimary: String = SsI18n.t("weapon.$id.tooltip.customPrimary")
    override val customPrimaryHL: String = SsI18n.t("weapon.$id.tooltip.customPrimaryHL")
    override val noDpsInTooltip: Boolean = false
    override val number: Int = 9201
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
