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
