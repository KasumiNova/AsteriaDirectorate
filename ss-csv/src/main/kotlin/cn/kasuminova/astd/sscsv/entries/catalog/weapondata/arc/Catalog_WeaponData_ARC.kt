package cn.kasuminova.astd.sscsv.entries.catalog.weapondata.arc

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
        // 原版 projectile visual 必须不可见，避免盖过 ASTD 自定义 VFX。
        length = 2.0,
        width = 2.0,
        fadeTime = 0.0,
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

/** Arc Nova：恒星喷射（系统武器：原版 beam 负责命中/伤害；脚本主要负责系统联动与额外 VFX）。 */
object Wpn_astd_stellar_jet_emitter : WeaponDataEntry() {
    override val id: String = "astd_stellar_jet_emitter"
    override val name: String = weaponName(id)
    override val tier: Int = 5
    override val baseValue: Int = 0
    override val range: Int = 4200

    // tooltip 统计用；实际伤害由脚本按最大幅能动态调整
    override val damagePerSecond: Int = 900

    // Beam：连续输出，避免用 damage/shot + burst 组合造成“一帧脉冲束”的观感/行为。
    override val damagePerShot: Int = 0
    override val emp: Int = 600
    override val impact: Int = 0

    override val turnRate: Int = 30
    override val type: String = "ENERGY"
    override val energyPerShot: Int = 0
    override val energyPerSecond: Int = 0

    // Beam：参考原版 ionbeam（连续 beam），用 chargeup/chargedown 描述束体的建立/消散。
    override val chargeup: Double = 0.1
    override val chargedown: Double = 0.1

    // 连续 beam：不使用 burst
    override val burstSize: Int = 0
    override val burstDelay: Double = 0.0

    override val beamSpeed: Int = 10000
    override val projSpeed: Int = 0

    override val tags: String = "astd_signature"

    // 系统联动/隐藏武器：不参与常规分组
    override val groupTag: String = ""
    override val tech: String = "弧光阵列"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")

    // Witch Citadel 同款：
    // - 用 CSV 单元格内的“真实换行”（由 properties 的 \n 转换而来）实现多行 tooltip。
    // - 用 `{%s}` 占位符 + customPrimaryHL 的 `|` 列表实现高亮替换。
    override val customPrimary: String = SsI18n.t("weapon.$id.tooltip.customPrimary")
    override val customPrimaryHL: String = SsI18n.t("weapon.$id.tooltip.customPrimaryHL")
    override val noDpsInTooltip: Boolean = false
    override val number: Int = 9104
}

/** Arc Nova：恒星喷射（隐藏弹体发射器；由系统脚本在系统启用时驱动）。 */
object Wpn_astd_stellar_jet_bolt_emitter : WeaponDataEntry() {
    override val id: String = "astd_stellar_jet_bolt_emitter"
    override val name: String = weaponName(id)
    override val tier: Int = 5
    override val baseValue: Int = 0
    override val range: Int = 4200

    // 隐藏武器：用于生成弹体，本体伤害/数量由脚本控制
    override val damagePerShot: Int = 1
    override val turnRate: Int = 30
    override val type: String = "ENERGY"
    override val energyPerShot: Int = 0
    override val energyPerSecond: Int = 0
    override val projSpeed: Int = 3000

    override val tags: String = "astd_signature"
    override val groupTag: String = ""
    override val tech: String = "弧光阵列"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val noDpsInTooltip: Boolean = true
    override val number: Int = 9105
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
    )
}

object Wpn_astd_tsm2 : WeaponDataEntry(), SsProjMissileOutputs {
    override val id: String = "astd_tsm2"
    override val name: String = weaponName(id)
    override val tier: Int = 3
    override val baseValue: Int = 50000
    override val range: Int = 3200

    // 策划案：
    // - 弹药 20
    // - 单发伤害 2000
    // - 冷却 12s
    // - 弹体速度 3000
    // - 弹体 HP 1800
    override val damagePerSecond: Int = 167
    override val damagePerShot: Int = 2000

    // 非 Beam：用 chargedown/burst 描述射速，避免 tooltip 统计除 0 溢出
    override val chargedown: Double = 12.0
    override val burstSize: Int = 1
    override val burstDelay: Double = 0.0
    override val turnRate: Int = 30
    override val ammo: Int = 20
    override val ammoPerSec: Double = 0.0
    override val reloadSize: Int = 0
    override val type: String = "ENERGY"

    // 导弹武器不产生幅能（flux）
    override val energyPerShot: Int = 0
    override val energyPerSecond: Int = 0
    override val projSpeed: Int = 3000

    // 飞行时间（必须 >0，否则导弹可能在发射后立刻消失）。
    // 高速端突导弹：留一定余量给转向/重定向与 armingTime。
    override val flightTime: Double = 2.4
    override val projHitpoints: Int = 1800
    override val tags: String = "astd_signature"
    override val groupTag: String = "astd"
    override val tech: String = "弧光阵列"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")

    override val customPrimary: String = SsI18n.t("weapon.$id.tooltip.customPrimary")
    override val customPrimaryHL: String = SsI18n.t("weapon.$id.tooltip.customPrimaryHL")
    override val number: Int = 9003

    override val projSpec: MissileProjSpec = MissileProjSpec(
        id = "astd_tsm2_missile",
        missileType = "MISSILE",
        onFireEffect = "cn.kasuminova.astd.combat.effect.generic.ProjectileSpecOnFireDispatcher",
        onHitEffect = "cn.kasuminova.astd.combat.effect.lens.signature.singularity.SingularityOnHitEffect",
        sprite = "graphics/missiles/torpedo_guided2.png",
        size = Vec2i(10, 21),
        center = Vec2(5, 10.5),
        collisionRadius = 15,
        collisionClass = "MISSILE_NO_FF",
        explosionColor = Rgba(120, 200, 255, 255),
        explosionRadius = 300,
        armingTime = 0.5,
        flameoutTime = 0.5,
        noEngineGlowTime = 0.25,
        fadeTime = 0.25,
        engineSpec = MissileEngineSpec(
            turnAcc = 400,
            turnRate = 60,
            acc = 800,
            dec = 200,
        ),
        // 事件视界导弹外观完全由代码 VFX 承担：去除原版引擎辉光/尾焰，避免“蓝色喷口/尾气”穿帮。
        engineSlots = emptyList(),
    )
}

object Wpn_astd_drv9 : WeaponDataEntry(), SsProjProjectileOutputs {
    override val id: String = "astd_drv9"
    override val name: String = weaponName(id)
    override val rarity: Int = 2
    override val baseValue: Int = 12000
    override val range: Int = 1050
    override val damagePerSecond: Int = 320
    override val damagePerShot: Int = 160

    // 非 Beam：用 chargedown/burst 描述射速，避免 tooltip 统计除 0 溢出
    override val chargedown: Double = 0.5
    override val burstSize: Int = 1
    override val burstDelay: Double = 0.0
    override val turnRate: Int = 30
    override val ops: Int = 11
    override val type: String = "KINETIC"
    override val energyPerShot: Int = 200
    override val energyPerSecond: Int = 400
    override val projSpeed: Int = 1900
    override val tags: String = "astd_production"
    override val groupTag: String = "astd"
    override val tech: String = "弧光阵列"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val number: Int = 9005

    override val projSpec: ProjectileProjSpec = ProjectileProjSpec.standard(
        id = "astd_drv9_slug",
        spawnType = ProjectileSpawnType.BALLISTIC,
        onHitEffect = "cn.kasuminova.astd.combat.effect.arc.production.Drv9ShieldPressureOnHitEffect",
    )
}

object Wpn_astd_slt4 : WeaponDataEntry(), SsProjProjectileOutputs {
    override val id: String = "astd_slt4"
    override val name: String = weaponName(id)
    override val rarity: Int = 2
    override val baseValue: Int = 12000
    override val range: Int = 900
    override val damagePerSecond: Int = 520
    override val damagePerShot: Int = 260

    // 非 Beam：用 chargedown/burst 描述射速，避免 tooltip 统计除 0 溢出
    override val chargedown: Double = 0.5
    override val burstSize: Int = 1
    override val burstDelay: Double = 0.0
    override val turnRate: Int = 30
    override val ops: Int = 20
    override val type: String = "KINETIC"
    override val energyPerShot: Int = 200
    override val energyPerSecond: Int = 400
    override val projSpeed: Int = 1100
    override val tags: String = "astd_production"
    override val groupTag: String = "astd"
    override val tech: String = "弧光阵列"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val number: Int = 9006

    override val projSpec: ProjectileProjSpec = ProjectileProjSpec.standard(
        id = "astd_slt4_burst",
        spawnType = ProjectileSpawnType.BALLISTIC,
        onHitEffect = "cn.kasuminova.astd.combat.effect.arc.production.Slt4SuppressionOnHitEffect",
    )
}

object Wpn_astd_vpd6 : WeaponDataEntry(), SsProjProjectileOutputs {
    override val id: String = "astd_vpd6"
    override val name: String = weaponName(id)
    override val rarity: Int = 3
    override val baseValue: Int = 12000
    override val range: Int = 600
    override val damagePerSecond: Int = 360
    override val damagePerShot: Int = 90

    // 非 Beam：用 chargedown/burst 描述射速，避免 tooltip 统计除 0 溢出
    override val chargedown: Double = 0.25
    override val burstSize: Int = 1
    override val burstDelay: Double = 0.0
    override val turnRate: Int = 30
    override val ops: Int = 7
    override val type: String = "ENERGY"

    // 与 damage/second(360) & damage/shot(90) 保持一致：射速=4 发/s → energy/second 应为 energy/shot * 4
    override val energyPerShot: Int = 100
    override val energyPerSecond: Int = 400
    override val projSpeed: Int = 1600
    override val tags: String = "astd_production"
    override val groupTag: String = "astd"
    override val tech: String = "弧光阵列"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val number: Int = 9007

    override val projSpec: ProjectileProjSpec = ProjectileProjSpec.standard(
        id = "astd_vpd6_pulse",
        spawnType = ProjectileSpawnType.BALLISTIC,
        onHitEffect = "cn.kasuminova.astd.combat.effect.arc.production.Vpd6PointDefenseOnHitEffect",
    )
}

object Wpn_astd_drv11 : WeaponDataEntry(), SsProjProjectileOutputs {
    override val id: String = "astd_drv11"
    override val name: String = weaponName(id)
    override val tier: Int = 2
    override val baseValue: Int = 12000
    override val range: Int = 1200
    override val damagePerSecond: Int = 520
    override val damagePerShot: Int = 260
    override val turnRate: Int = 30
    override val ops: Int = 22
    override val ammo: Int = 8

    // 弹匣：打空后约 6 秒整匣回满（使用原生 ammo/sec + reload size 机制）
    override val reloadSize: Int = 8
    override val ammoPerSec: Double = 8.0 / 6.0

    // 注意：对非 Beam 武器，射速由 chargedown/burst 相关字段决定；
    // 之前全 0 会导致某些 tooltip（以及可能的内部统计）出现除 0 溢出 → 2147483647。
    // 这里按设计案的“弹匣期约 2 发/s”设置：refireDelay = 0.5s。
    override val burstSize: Int = 1
    override val burstDelay: Double = 0.0
    override val chargedown: Double = 0.5
    override val type: String = "KINETIC"
    override val energyPerShot: Int = 220
    override val energyPerSecond: Int = 440
    override val projSpeed: Int = 3200
    override val tags: String = "astd_rare"
    override val groupTag: String = "astd"
    override val tech: String = "弧光阵列"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val number: Int = 9008

    override val projSpec: ProjectileProjSpec = ProjectileProjSpec.standard(
        id = "astd_drv11_slug",
        spawnType = ProjectileSpawnType.BALLISTIC,
        onHitEffect = "cn.kasuminova.astd.combat.effect.generic.HighFluxShieldPressureOnHitEffect",
    )
}

object Wpn_astd_slt3 : WeaponDataEntry(), SsProjProjectileOutputs {
    override val id: String = "astd_slt3"
    override val name: String = weaponName(id)
    override val tier: Int = 2
    override val baseValue: Int = 12000
    override val range: Int = 950
    override val damagePerSecond: Int = 1400
    override val damagePerShot: Int = 175

    // 非 Beam：连射窗口期约 8 发/s
    override val chargedown: Double = 0.125
    override val burstSize: Int = 1
    override val burstDelay: Double = 0.0
    override val turnRate: Int = 30
    override val ops: Int = 24
    override val ammo: Int = 32

    // 弹匣：连射窗口结束（打空）后约 9 秒整匣回满
    override val reloadSize: Int = 32
    override val ammoPerSec: Double = 32.0 / 9.0
    override val type: String = "ENERGY"
    override val energyPerShot: Int = 220
    override val energyPerSecond: Int = 1760
    override val projSpeed: Int = 1200
    override val tags: String = "astd_rare"
    override val groupTag: String = "astd"
    override val tech: String = "弧光阵列"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val number: Int = 9009

    override val projSpec: ProjectileProjSpec = ProjectileProjSpec.standard(
        id = "astd_slt3_pulse",
        spawnType = ProjectileSpawnType.BALLISTIC,
        onHitEffect = "cn.kasuminova.astd.combat.effect.generic.HighFluxShieldPressureOnHitEffect",
    )
}

object Wpn_astd_rct6 : WeaponDataEntry(), SsProjMissileOutputs {
    override val id: String = "astd_rct6"
    override val name: String = weaponName(id)
    override val tier: Int = 2
    override val baseValue: Int = 12000
    override val range: Int = 1400
    override val damagePerSecond: Int = 1100
    override val damagePerShot: Int = 2200

    // 非 Beam：名义射速 0.5 发/s（用于 tooltip 统计；真实表现由导弹/AI 等决定）
    override val chargedown: Double = 2.0
    override val burstSize: Int = 1
    override val burstDelay: Double = 0.0
    override val turnRate: Int = 30
    override val ops: Int = 16
    override val ammo: Int = 3
    override val ammoPerSec: Double = 0.04
    override val reloadSize: Int = 1
    override val type: String = "HIGH_EXPLOSIVE"
    override val energyPerShot: Int = 0
    override val energyPerSecond: Int = 0
    override val projSpeed: Int = 950

    // 鱼雷飞行时间：按射程/速度给一个保守值
    override val flightTime: Double = 1.6

    // 弹体耐久（proj hitpoints）；为 0 会导致导弹发射后立刻消失
    override val projHitpoints: Int = 300
    override val tags: String = "astd_rare"
    override val groupTag: String = "astd"
    override val tech: String = "弧光阵列"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val number: Int = 9010

    override val projSpec: MissileProjSpec = MissileProjSpec(
        id = "astd_rct6_torp",
        missileType = "MISSILE",
        onFireEffect = "cn.kasuminova.astd.combat.effect.generic.ProjectileSpecOnFireDispatcher",
        onHitEffect = null,
        sprite = "graphics/missiles/torpedo_guided2.png",
        size = Vec2i(10, 21),
        center = Vec2(5, 10.5),
        collisionRadius = 15,
        collisionClass = "MISSILE_NO_FF",
        explosionColor = Rgba(120, 200, 255, 255),
        explosionRadius = 250,
        armingTime = 0.5,
        flameoutTime = 0.5,
        noEngineGlowTime = 0.25,
        fadeTime = 0.25,
        engineSpec = MissileEngineSpec(
            turnAcc = 500,
            turnRate = 75,
            acc = 1200,
            dec = 300,
        ),
        engineSlots = listOf(
            MissileEngineSlot(
                id = "ES1",
                loc = Vec2i(-10, 0),
                style = "CUSTOM",
                styleSpec = MissileEngineSlotStyleSpec(
                    mode = "QUAD_STRIP",
                    engineColor = Rgba(120, 200, 255, 255),
                    glowSizeMult = 2.0,
                    contrailDuration = 1.5,
                    contrailWidthMult = 1.0,
                    contrailWidthAddedFractionAtEnd = 2.0,
                    contrailMinSeg = 5,
                    contrailMaxSpeedMult = 0.0,
                    contrailAngularVelocityMult = 0.5,
                    contrailSpawnDistMult = 0.5,
                    contrailColor = Rgba(80, 160, 220, 100),
                    type = "GLOW",
                ),
                width = 10.0,
                length = 30.0,
                angle = 180.0,
            )
        )
    )
}

object Wpn_astd_drv_omega : WeaponDataEntry(), SsProjProjectileOutputs {
    override val id: String = "astd_drv_omega"
    override val name: String = weaponName(id)
    override val tier: Int = 5
    override val baseValue: Int = 50000
    override val range: Int = 1000

    // 非 Beam：一次性伤害 + 极短飞行（hitscan 观感），命中时用 onHit 渲染"弹道光束"效果。
    // 终结技（弹匣最后一发）由脚本处理：300% 伤害 + 额外 EMP 电弧。
    // sustained 仅用于 UI：800/shot × (ammo/sec=0.6 => 0.6 shot/s) ≈ 480 DPS
    override val damagePerSecond: Int = 480
    override val damagePerShot: Int = 800

    // 充能：基础 0.5s；最后一发慢充能由 everyFrameEffect 在极短窗口内将 chargeup 变为 1.5s
    override val chargeup: Double = 0.5
    override val chargedown: Double = 0.5
    override val burstSize: Int = 1
    override val burstDelay: Double = 0.0
    override val turnRate: Int = 30
    override val ops: Int = 28
    override val type: String = "KINETIC"
    override val energyPerShot: Int = 1200
    // sustained 仅用于 UI：1200/shot × 0.6 shot/s = 720 flux/s
    override val energyPerSecond: Int = 720

    // 弹匣：3 发；约 5s 装填一次（0.6 ammo/s × 5s = 3 ammo）
    override val ammo: Int = 3
    override val ammoPerSec: Double = 0.6
    // 面板：每次重载恢复 3 发（chunk reload）
    override val reloadSize: Int = 3

    // 速度保持“正常弹体”水平，避免高速度导致擦过/穿透；hitscan 观感由 onFire 的“落点预计算+传送”保证。
    override val projSpeed: Int = 1600
    override val tags: String = "astd_omega"
    override val groupTag: String = "astd"
    override val tech: String = "弧光阵列"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val customPrimary: String = SsI18n.t("weapon.$id.customPrimary")
    override val customPrimaryHL: String = SsI18n.t("weapon.$id.customPrimaryHL")
    override val number: Int = 9011

    override val projSpec: ProjectileProjSpec = ProjectileProjSpec(
        id = "astd_drv_omega_slug",
        spawnType = ProjectileSpawnType.BALLISTIC,
        onFireEffect = "cn.kasuminova.astd.combat.effect.generic.ProjectileSpecOnFireDispatcher",
        onHitEffect = "cn.kasuminova.astd.combat.effect.arc.omega.DrvOmegaOnHitEffect",
        collisionClass = "PROJECTILE_FF",
        collisionClassByFighter = "PROJECTILE_FIGHTER",
        // 彻底隐形：避免 1 帧闪现
        length = 2.0,
        width = 2.0,
        fadeTime = 0.0,
        fringeColor = Rgba(120, 200, 255, 0),
        coreColor = Rgba(220, 245, 255, 0),
        textureScrollSpeed = 0.0,
        pixelsPerTexel = 1.0,
        bulletSprite = "graphics/textures/BUtil_NONE.png",
    )
}

/**
 * ARC-13「三位一体」：大型能量武器（蓝稀有）。
 *
 * 机制：burst 3 发；每发脚本生成一次弧光（命中点即时结算一次性伤害）。
 * 因此 weapon_data.csv 中 damage 字段仅用于 UI/AI 基础信息，不做实际伤害来源。
 */
object Wpn_astd_arc13 : WeaponDataEntry(), SsProjProjectileOutputs {
    override val id: String = "astd_arc13"
    override val name: String = weaponName(id)
    override val tier: Int = 4
    override val baseValue: Int = 28000
    override val range: Int = 1000

    // 伤害实际由脚本结算；这里的数值仅用于 UI/AI。
    // 设计：单发基础 500；最后一发（破片相）为 300%（1500）。
    // 总计 2500/ burst，均值 833/shot。
    // 周期约 2.9s（burstDelay=0.2, chargedown=2.5），持续 DPS 约 862。
    override val damagePerSecond: Int = 862
    // weapon_data.csv 只能填单一值：这里填“基础单发伤害”，并在提示里说明第三发加成。
    override val damagePerShot: Int = 500

    // 设计：三发 burst；发间隔 0.2s；冷却 2.5s
    override val chargeup: Double = 0.0
    override val chargedown: Double = 2.5
    override val burstSize: Int = 3
    override val burstDelay: Double = 0.2

    override val turnRate: Int = 25
    override val ops: Int = 25

    // 多段不同伤害类型：UI 上按 ENERGY 展示，并在提示中说明三相伤害。
    override val type: String = "ENERGY"

    // 600 flux/shot => 1800/ burst；周期约 2.9s => ~621 flux/s
    override val energyPerShot: Int = 600
    override val energyPerSecond: Int = 621

    override val projSpeed: Int = 1600

    override val tags: String = "astd_arc13"
    override val groupTag: String = "astd"
    override val tech: String = "弧光阵列"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val customPrimary: String = SsI18n.t("weapon.$id.customPrimary")
    override val customPrimaryHL: String = SsI18n.t("weapon.$id.customPrimaryHL")
    override val customAncillary: String = SsI18n.t("weapon.$id.customAncillary")

    override val number: Int = 9024

    override val projSpec: ProjectileProjSpec = ProjectileProjSpec(
        id = "astd_arc13_dummy",
        spawnType = ProjectileSpawnType.BALLISTIC,
        onFireEffect = "cn.kasuminova.astd.combat.effect.arc.arc13.Arc13OnFireEffect",
        collisionClass = "NONE",
        collisionClassByFighter = "NONE",
        length = 2.0,
        width = 2.0,
        fadeTime = 0.0,
        fringeColor = Rgba(255, 255, 255, 0),
        coreColor = Rgba(255, 255, 255, 0),
        textureScrollSpeed = 0.0,
        pixelsPerTexel = 1.0,
        bulletSprite = "graphics/textures/BUtil_NONE.png",
    )
}

object Wpn_astd_slt_omega : WeaponDataEntry(), SsProjProjectileOutputs {
    override val id: String = "astd_slt_omega"
    override val name: String = weaponName(id)
    override val tier: Int = 5
    override val baseValue: Int = 50000
    override val range: Int = 1050
    override val damagePerSecond: Int = 520
    override val damagePerShot: Int = 260

    // 非 Beam：用 chargedown/burst 描述射速，避免 tooltip 统计除 0 溢出
    override val chargedown: Double = 0.5
    override val burstSize: Int = 1
    override val burstDelay: Double = 0.0
    override val turnRate: Int = 30
    override val ops: Int = 26
    override val type: String = "ENERGY"
    override val energyPerShot: Int = 200
    override val energyPerSecond: Int = 400
    override val projSpeed: Int = 1600
    override val tags: String = "astd_omega"
    override val groupTag: String = "astd"
    override val tech: String = "弧光阵列"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val number: Int = 9012

    override val projSpec: ProjectileProjSpec = ProjectileProjSpec.standard(
        id = "astd_slt_omega_stream",
        spawnType = ProjectileSpawnType.BALLISTIC,
    )
}

object Wpn_astd_tsm_omega : WeaponDataEntry(), SsProjMissileOutputs {
    override val id: String = "astd_tsm_omega"
    override val name: String = weaponName(id)
    override val tier: Int = 5
    override val baseValue: Int = 50000
    override val range: Int = 3600
    override val damagePerSecond: Int = 750
    override val damagePerShot: Int = 5000

    // 非 Beam：用 chargedown/burst 描述射速，避免 tooltip 统计除 0 溢出
    override val chargedown: Double = 0.5
    override val burstSize: Int = 1
    override val burstDelay: Double = 0.0
    override val turnRate: Int = 30
    override val ops: Int = 30
    override val ammo: Int = 10
    override val ammoPerSec: Double = 0.15
    override val reloadSize: Int = 1
    override val type: String = "ENERGY"

    // 导弹武器不产生幅能（flux）
    override val energyPerShot: Int = 0
    override val energyPerSecond: Int = 0
    override val projSpeed: Int = 650

    // 导弹飞行时间（必须 >0，否则导弹可能在发射后立刻消失）
    override val flightTime: Double = 6.0

    // 弹体耐久（proj hitpoints）；为 0 会导致导弹发射后立刻消失
    override val projHitpoints: Int = 450
    override val tags: String = "astd_omega"
    override val groupTag: String = "astd"
    override val tech: String = "弧光阵列"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val number: Int = 9013

    override val projSpec: MissileProjSpec = MissileProjSpec(
        id = "astd_tsm_omega_missile",
        missileType = "MISSILE",
        onFireEffect = "cn.kasuminova.astd.combat.effect.generic.ProjectileSpecOnFireDispatcher",
        onHitEffect = "cn.kasuminova.astd.combat.effect.arc.signature.tsm.TsmOmegaTerminalVerdictOnHitEffect",
        sprite = "graphics/missiles/torpedo_guided2.png",
        size = Vec2i(10, 21),
        center = Vec2(5, 10.5),
        collisionRadius = 15,
        collisionClass = "MISSILE_NO_FF",
        explosionColor = Rgba(180, 120, 255, 255),
        explosionRadius = 350,
        armingTime = 0.4,
        flameoutTime = 0.5,
        noEngineGlowTime = 0.25,
        fadeTime = 0.25,
        engineSpec = MissileEngineSpec(
            turnAcc = 500,
            turnRate = 80,
            acc = 1000,
            dec = 250,
        ),
        engineSlots = listOf(
            MissileEngineSlot(
                id = "ES1",
                loc = Vec2i(-10, 0),
                style = "CUSTOM",
                styleSpec = MissileEngineSlotStyleSpec(
                    mode = "QUAD_STRIP",
                    engineColor = Rgba(180, 120, 255, 255),
                    glowSizeMult = 2.5,
                    contrailDuration = 1.5,
                    contrailWidthMult = 1.0,
                    contrailWidthAddedFractionAtEnd = 2.0,
                    contrailMinSeg = 5,
                    contrailMaxSpeedMult = 0.0,
                    contrailAngularVelocityMult = 0.5,
                    contrailSpawnDistMult = 0.5,
                    contrailColor = Rgba(140, 80, 200, 100),
                    type = "GLOW",
                ),
                width = 10.0,
                length = 35.0,
                angle = 180.0,
            )
        )
    )
}
