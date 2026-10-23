package cn.kasuminova.astd.sscsv.entries.catalog.weapondata.joint

import cn.kasuminova.astd.sscsv.entries.AiHint
import cn.kasuminova.astd.sscsv.entries.WeaponDataEntry
import cn.kasuminova.astd.sscsv.entries.catalog.weapondata.weaponName
import cn.kasuminova.astd.sscsv.i18n.SsI18n
import cn.kasuminova.astd.sscsv.outputs.proj.MissileEngineSlot
import cn.kasuminova.astd.sscsv.outputs.proj.MissileEngineSlotStyleSpec
import cn.kasuminova.astd.sscsv.outputs.proj.MissileEngineSpec
import cn.kasuminova.astd.sscsv.outputs.proj.MissileProjSpec
import cn.kasuminova.astd.sscsv.outputs.proj.Rgba
import cn.kasuminova.astd.sscsv.outputs.proj.SsProjMissileOutputs
import cn.kasuminova.astd.sscsv.outputs.proj.Vec2
import cn.kasuminova.astd.sscsv.outputs.proj.Vec2i

object Wpn_astd_lh_001_bloom : WeaponDataEntry() {
    override val id: String = "astd_lh_001_bloom"
    override val name: String = weaponName(id)
    override val tier: Int = 5
    override val baseValue: Int = 0
    override val range: Int = 0
    override val turnRate: Int = 0
    override val type: String = "OTHER"
    override val tags: String = "no_drop, no_drop_salvage"
    override val aiHints: Set<AiHint> = setOf(AiHint.SYSTEM)
    override val tech: String = "菀星设计局-星坠"
    override val noDpsInTooltip: Boolean = true
    override val number: Int = 9133
}

object Wpn_astd_lh_002_bloom : WeaponDataEntry() {
    override val id: String = "astd_lh_002_bloom"
    override val name: String = weaponName(id)
    override val tier: Int = 5
    override val baseValue: Int = 0
    override val range: Int = 0
    override val turnRate: Int = 0
    override val type: String = "OTHER"
    override val tags: String = "no_drop, no_drop_salvage"
    override val aiHints: Set<AiHint> = setOf(AiHint.SYSTEM)
    override val tech: String = "菀星设计局-紫菀"
    override val noDpsInTooltip: Boolean = true
    override val number: Int = 9134
}

object Wpn_astd_lh_stardust_launcher_arc : WeaponDataEntry(), SsProjMissileOutputs {
    override val id: String = "astd_lh_stardust_launcher_arc"
    override val name: String = weaponName("astd_lh_stardust_launcher")
    override val tier: Int = 5
    override val rarity: Int = 1
    override val baseValue: Int = 0
    override val range: Int = 800

    override val damagePerShot: Int = 400

    override val emp: Int = 400
    override val turnRate: Int = 30
    override val ammo: Int = 8
    override val ammoPerSec: Double = 0.25
    override val reloadSize: Int = 2
    override val type: String = "ENERGY"

    override val energyPerShot: Int = 200
    override val chargedown: Double = 1.0

    override val burstSize: Number = 1

    override val minSpread: Double = 30.0
    override val maxSpread: Double = 30.0

    override val projSpeed: Int = 450
    override val launchSpeed: Int = 200

    override val flightTime: Double = 60.0
    override val projHitpoints: Int = 400
    override val tags: String = "no_drop, no_drop_salvage"
    override val tech: String = "联制"

    override val aiHints: Set<AiHint> = setOf(AiHint.NO_MANUAL_FIRE)
    override val primaryRoleStr: String = SsI18n.t("weapon.astd_lh_stardust_launcher.primaryRoleStr")
    override val customPrimary: String = SsI18n.t("weapon.$id.tooltip.customPrimary")
    override val customPrimaryHL: String = SsI18n.t("weapon.$id.tooltip.customPrimaryHL")
    override val number: Int = 9240

    override val projSpec: MissileProjSpec = stardustMoteProj(
        id = "astd_lh_stardust_mote_arc",
        engineColor = Rgba(100, 165, 255, 255),
        contrailColor = Rgba(100, 165, 255, 25),
        explosionColor = Rgba(100, 165, 255, 180),
    )
}

object Wpn_astd_lh_stardust_launcher_lens : WeaponDataEntry(), SsProjMissileOutputs {
    override val id: String = "astd_lh_stardust_launcher_lens"
    override val name: String = weaponName("astd_lh_stardust_launcher")
    override val tier: Int = 5
    override val rarity: Int = 1
    override val baseValue: Int = 0
    override val range: Int = 800
    override val damagePerShot: Int = 400
    override val emp: Int = 400
    override val turnRate: Int = 30
    override val ammo: Int = 8
    override val ammoPerSec: Double = 0.25
    override val reloadSize: Int = 2
    override val type: String = "ENERGY"
    override val energyPerShot: Int = 200
    override val chargedown: Double = 1.0
    override val burstSize: Number = 1
    override val minSpread: Double = 30.0
    override val maxSpread: Double = 30.0

    override val projSpeed: Int = 450
    override val launchSpeed: Int = 200
    override val flightTime: Double = 60.0
    override val projHitpoints: Int = 400
    override val tags: String = "no_drop, no_drop_salvage"
    override val tech: String = "联制"

    override val aiHints: Set<AiHint> = setOf(AiHint.NO_MANUAL_FIRE)
    override val primaryRoleStr: String = SsI18n.t("weapon.astd_lh_stardust_launcher.primaryRoleStr")
    override val customPrimary: String = SsI18n.t("weapon.$id.tooltip.customPrimary")
    override val customPrimaryHL: String = SsI18n.t("weapon.$id.tooltip.customPrimaryHL")
    override val number: Int = 9241

    override val projSpec: MissileProjSpec = stardustMoteProj(
        id = "astd_lh_stardust_mote_lens",
        engineColor = Rgba(186, 120, 255, 255),
        contrailColor = Rgba(186, 120, 255, 25),
        explosionColor = Rgba(186, 120, 255, 180),
    )
}

private fun stardustMoteProj(
    id: String,
    engineColor: Rgba,
    contrailColor: Rgba,
    explosionColor: Rgba,
): MissileProjSpec = MissileProjSpec(
    id = id,
    missileType = "MOTE",
    onFireEffect = "cn.kasuminova.astd.combat.effect.generic.ProjectileSpecOnFireDispatcher",
    onHitEffect = "cn.kasuminova.astd.combat.effect.joint.stardust.StardustMoteOnHitEffect",
    sprite = "graphics/missiles/bomblet0.png",
    size = Vec2i(4, 4),
    center = Vec2(2, 2),
    collisionRadius = 11,
    collisionClass = "MISSILE_NO_FF",
    explosionColor = explosionColor,
    explosionRadius = 36,
    armingTime = 0.05,
    flameoutTime = 1.0,
    noEngineGlowTime = 0.5,
    fadeTime = 0.5,
    engineSpec = MissileEngineSpec(turnAcc = 600, turnRate = 400, acc = 650, dec = 650),
    engineSlots = listOf(
        MissileEngineSlot(
            id = "ES1",
            loc = Vec2i(0, 0),
            style = "CUSTOM",
            styleSpec = MissileEngineSlotStyleSpec(
                mode = "QUAD_STRIP",
                engineColor = engineColor,
                glowSizeMult = 1.0,
                contrailDuration = 2.0,
                contrailWidthMult = 1.0,
                contrailWidthAddedFractionAtEnd = 0.0,
                contrailMinSeg = 5,
                contrailMaxSpeedMult = -0.1,
                contrailAngularVelocityMult = 0.0,
                contrailSpawnDistMult = 1.0,
                contrailColor = contrailColor,
                type = "GLOW",
            ),
            width = 12.0,
            length = 35.0,
            angle = 180.0,
        ),
    ),
)
