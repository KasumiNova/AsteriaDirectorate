package cn.kasuminova.astd.sscsv.entries.catalog.weapondata.arc

import cn.kasuminova.astd.sscsv.GeneratedJsonFile
import cn.kasuminova.astd.sscsv.SsJsonOutputs
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

object Wpn_astd_starfall_echo : WeaponDataEntry(), SsJsonOutputs {
    override val id: String = "astd_starfall_echo"
    override val name: String = weaponName(id)
    override val tier: Int = 3
    override val baseValue: Int = 50000
    override val range: Int = 1000
    override val damagePerShot: Int = 750

    override val chargedown: Double = 4.6
    override val burstSize: Int = 5
    override val burstDelay: Double = 0.1
    override val turnRate: Int = 30
    override val type: String = "ENERGY"
    override val energyPerShot: Int = 1125
    override val projSpeed: Int = 2000

    override val minSpread: Double = 2.0
    override val maxSpread: Double = 15.0
    override val spreadPerShot: Double = 1.0
    override val spreadDecayPerSec: Double = 5.0
    override val accuracyStr: String = "较差"

    override val ammo: Int = 10
    override val ammoPerSec: Double = 0.5
    override val reloadSize: Int = 5

    override val tags: String = "energy20, no_drop, no_drop_salvage, astd_signature"
    override val groupTag: String = "astd"
    override val tech: String = "菀星设计局-星坠"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val customPrimary: String = SsI18n.t("weapon.$id.tooltip.customPrimary")
    override val customPrimaryHL: String = SsI18n.t("weapon.$id.tooltip.customPrimaryHL")
    override val number: Int = 9001

    val projSpec: ProjectileProjSpec = ProjectileProjSpec.boxBolt(
        id = "astd_starfall_echo_shot",
        onFireEffect = "cn.kasuminova.astd.combat.effect.arc.starfallecho.StarfallEchoOnFireEffect",
        onHitEffect = "cn.kasuminova.astd.combat.effect.arc.starfallecho.StarfallEchoOnHitEffect",
        fringeColor = Rgba(120, 190, 255, 255),
        coreColor = Rgba(240, 248, 255, 200),
        length = 120.0,
        width = 24.0,
    )

    override fun jsonExtraFiles(): List<GeneratedJsonFile> = listOf(
        GeneratedJsonFile("data/weapons/proj/${projSpec.id}.proj", projSpec.toJson()),
    )
}

object Wpn_astd_xc_001_lights : WeaponDataEntry() {
    override val id: String = "astd_xc_001_lights"
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
    override val number: Int = 9106
}

object Wpn_astd_xc_001_lights_bloom : WeaponDataEntry() {
    override val id: String = "astd_xc_001_lights_bloom"
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
    override val number: Int = 9125
}

object Wpn_astd_xc_002_bloom : WeaponDataEntry() {
    override val id: String = "astd_xc_002_bloom"
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
    override val number: Int = 9126
}

object Wpn_astd_xc_103_bloom : WeaponDataEntry() {
    override val id: String = "astd_xc_103_bloom"
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
    override val number: Int = 9128
}

object Wpn_astd_xc_102_bloom : WeaponDataEntry() {
    override val id: String = "astd_xc_102_bloom"
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
    override val number: Int = 9130
}

object Wpn_astd_xc_104_bloom : WeaponDataEntry() {
    override val id: String = "astd_xc_104_bloom"
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
    override val number: Int = 9131
}

object Wpn_astd_xc_101_bloom : WeaponDataEntry() {
    override val id: String = "astd_xc_101_bloom"
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
    override val number: Int = 9129
}

object Wpn_astd_starfall_wing : WeaponDataEntry(), SsProjProjectileOutputs {
    override val id: String = "astd_starfall_wing"
    override val name: String = weaponName(id)
    override val tier: Int = 3
    override val baseValue: Int = 50000
    override val range: Int = 1200
    override val damagePerShot: Int = 1000
    override val emp: Int = 500

    override val chargedown: Double = 1.5
    override val burstSize: Int = 1
    override val burstDelay: Double = 0.0
    override val turnRate: Int = 30
    override val type: String = "ENERGY"
    override val energyPerShot: Int = 1000
    override val projSpeed: Int = 1500

    override val tags: String = "energy20, no_drop, no_drop_salvage, astd_signature"
    override val groupTag: String = "astd"
    override val tech: String = "菀星设计局-星坠"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val customPrimary: String = SsI18n.t("weapon.$id.tooltip.customPrimary")
    override val customPrimaryHL: String = SsI18n.t("weapon.$id.tooltip.customPrimaryHL")
    override val number: Int = 9002

    override val projSpec: ProjectileProjSpec = ProjectileProjSpec.boxBolt(
        id = "astd_starfall_wing_shot",
        onFireEffect = "cn.kasuminova.astd.combat.effect.arc.starfallwing.StarfallWingOnFireEffect",
        onHitEffect = null,
        collisionClass = "NONE",
        collisionClassByFighter = "NONE",
        fringeColor = Rgba(170, 110, 255, 255),
        coreColor = Rgba(240, 225, 255, 200),
        length = 138.0,
        width = 34.0,
    )
}

object Wpn_astd_starfall_wing_mote_launcher : WeaponDataEntry(), SsProjMissileOutputs {
    override val id: String = "astd_starfall_wing_mote_launcher"
    override val name: String = weaponName(id)
    override val tier: Int = 5
    override val baseValue: Int = 0
    override val range: Int = 1200

    override val damagePerShot: Int = 200
    override val turnRate: Int = 30
    override val type: String = "ENERGY"
    override val projSpeed: Int = 900
    override val flightTime: Double = 2.0
    override val projHitpoints: Int = 10000
    override val tags: String = "no_drop, no_drop_salvage, astd_signature"
    override val aiHints: Set<AiHint> = setOf(AiHint.SYSTEM)
    override val groupTag: String = ""
    override val tech: String = "菀星设计局-星坠"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val noDpsInTooltip: Boolean = true
    override val number: Int = 9132

    override val projSpec: MissileProjSpec = MissileProjSpec(
        id = "astd_starfall_wing_mote",
        missileType = "MISSILE",
        onHitEffect = "cn.kasuminova.astd.combat.effect.arc.starfallwing.StarfallWingMoteOnHitEffect",
        sprite = "graphics/textures/BUtil_NONE.png",
        size = Vec2i(4, 4),
        center = Vec2(2, 2),
        collisionRadius = 7,
        collisionClass = "MISSILE_NO_FF",
        explosionColor = Rgba(170, 110, 255, 180),
        explosionRadius = 24,
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

object Wpn_astd_rift_mine_layer : WeaponDataEntry(), SsProjMissileOutputs {
    override val id: String = "astd_rift_mine_layer"
    override val name: String = weaponName(id)
    override val tier: Int = 5
    override val baseValue: Int = 0
    override val range: Int = 0

    override val damagePerShot: Int = 4000
    override val type: String = "ENERGY"
    override val projSpeed: Int = 0
    override val flightTime: Double = 10.0
    override val projHitpoints: Int = 10000
    override val tags: String = "no_drop, no_drop_salvage, astd_signature"
    override val aiHints: Set<AiHint> = setOf(AiHint.SYSTEM)
    override val groupTag: String = ""
    override val tech: String = "菀星设计局-星坠"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val noDpsInTooltip: Boolean = true
    override val number: Int = 9133

    override val projSpec: MissileProjSpec = MissileProjSpec(
        id = "astd_rift_mine",
        missileType = "PHASE_MINE",
        behaviorSpec = mapOf(
            "behavior" to "PROXIMITY_FUSE",
            "range" to 0,
            "slowToMaxSpeed" to false,
            "delay" to 3,
            "pingSound" to "mine_ping",
            "pingColor" to listOf(170, 110, 255, 0),
            "pingRadius" to 0,
            "pingDuration" to 0.25,
            "windupSound" to "mine_windup_heavy",
            "windupDelay" to 1,
            "explosionSpec" to mapOf(
                "duration" to 0.1,
                "radius" to 0,
                "coreRadius" to 0,
                "collisionClass" to "MISSILE_FF",
                "collisionClassByFighter" to "MISSILE_FF",
                "particleSizeMin" to 3.0,
                "particleSizeRange" to 3.0,
                "particleDuration" to 1,
                "particleCount" to 0,
                "particleColor" to listOf(170, 110, 255, 0),
                "explosionColor" to listOf(170, 110, 255, 0),
                "useDetailedExplosion" to false,
                "sound" to "mine_explosion",
            ),
        ),
        sprite = "graphics/textures/BUtil_NONE.png",
        size = Vec2i(4, 4),
        center = Vec2(2, 2),
        collisionRadius = 20,
        collisionClass = "NONE",
        collisionClassAfterFlameout = "NONE",
        renderTargetIndicator = false,
        explosionColor = Rgba(170, 110, 255, 0),
        explosionRadius = 0,
        armingTime = 0.0,
        flameoutTime = 0.5,
        noEngineGlowTime = 999.0,
        fadeTime = 0.25,
        engineSpec = MissileEngineSpec(
            turnAcc = 0,
            turnRate = 0,
            acc = 0,
            dec = 0,
        ),
        engineSlots = emptyList(),
    )
}

object Wpn_astd_charge_needle : WeaponDataEntry(), SsProjProjectileOutputs {
    override val id: String = "astd_charge_needle"
    override val name: String = weaponName(id)
    override val tier: Int = 1
    override val rarity: Int = 1
    override val baseValue: Int = 6000
    override val range: Int = 700
    override val damagePerShot: Int = 50
    override val emp: Int = 200
    override val turnRate: Int = 30
    override val ops: Int = 8

    override val chargedown: Double = 0.05
    override val burstSize: Int = 1
    override val burstDelay: Double = 0.0

    override val ammo: Int = 30
    override val ammoPerSec: Double = 2.5
    override val reloadSize: Int = 15
    override val type: String = "ENERGY"
    override val energyPerShot: Int = 40

    override val projSpeed: Int = 1350

    override val minSpread: Double = 5.0
    override val maxSpread: Double = 15.0
    override val spreadPerShot: Double = 0.75
    override val spreadDecayPerSec: Double = 5.0

    override val tags: String = "energy8, astd_production"
    override val groupTag: String = "astd"
    override val tech: String = "菀星设计局-星坠"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val customPrimary: String = SsI18n.t("weapon.$id.tooltip.customPrimary")
    override val customPrimaryHL: String = SsI18n.t("weapon.$id.tooltip.customPrimaryHL")
    override val number: Int = 9210

    override val projSpec: ProjectileProjSpec = ProjectileProjSpec.boxBolt(
        id = "astd_charge_needle_shot",
        spawnType = ProjectileSpawnType.BALLISTIC,
        onHitEffect = "cn.kasuminova.astd.combat.effect.arc.chargeneedle.ChargeNeedleOnHitEffect",
        fringeColor = Rgba(140, 200, 255, 255),
        coreColor = Rgba(225, 242, 255, 200),
        width = 5.0,
    )
}

object Wpn_astd_heavy_charge_needle : WeaponDataEntry(), SsProjProjectileOutputs {
    override val id: String = "astd_heavy_charge_needle"
    override val name: String = weaponName(id)
    override val tier: Int = 1
    override val rarity: Int = 1
    override val baseValue: Int = 14000
    override val range: Int = 700
    override val damagePerShot: Int = 50
    override val emp: Int = 200
    override val turnRate: Int = 30
    override val ops: Int = 16

    override val chargedown: Double = 0.05
    override val burstSize: Int = 1
    override val burstDelay: Double = 0.0

    override val ammo: Int = 60
    override val ammoPerSec: Double = 5.0
    override val reloadSize: Int = 30
    override val type: String = "ENERGY"
    override val energyPerShot: Int = 40

    override val projSpeed: Int = 1350

    override val minSpread: Double = 5.0
    override val maxSpread: Double = 15.0
    override val spreadPerShot: Double = 0.75
    override val spreadDecayPerSec: Double = 5.0

    override val extraArcForAI: Int = 25

    override val tags: String = "energy14, astd_production"
    override val groupTag: String = "astd"
    override val tech: String = "菀星设计局-星坠"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val customPrimary: String = SsI18n.t("weapon.$id.tooltip.customPrimary")
    override val customPrimaryHL: String = SsI18n.t("weapon.$id.tooltip.customPrimaryHL")
    override val number: Int = 9211

    override val projSpec: ProjectileProjSpec = ProjectileProjSpec.boxBolt(
        id = "astd_heavy_charge_needle_shot",
        spawnType = ProjectileSpawnType.BALLISTIC,
        onHitEffect = "cn.kasuminova.astd.combat.effect.arc.chargeneedle.ChargeNeedleOnHitEffect",
        fringeColor = Rgba(140, 200, 255, 255),
        coreColor = Rgba(225, 242, 255, 200),
        width = 5.0,
    )
}

object Wpn_astd_electric_drive_accelerator : WeaponDataEntry(), SsProjProjectileOutputs {
    override val id: String = "astd_electric_drive_accelerator"
    override val name: String = weaponName(id)
    override val tier: Int = 1
    override val rarity: Int = 1
    override val baseValue: Int = 11000
    override val range: Int = 750

    override val damagePerShot: Int = 120
    override val impact: Int = 4
    override val turnRate: Int = 30
    override val ops: Int = 15

    override val chargedown: Double = 0.3
    override val burstSize: Int = 4
    override val burstDelay: Double = 0.15

    override val ammo: Int = 20
    override val ammoPerSec: Double = 1.6
    override val reloadSize: Int = 8
    override val type: String = "KINETIC"

    override val energyPerShot: Int = 150
    override val projSpeed: Int = 1000

    override val minSpread: Double = 4.0
    override val maxSpread: Double = 8.0
    override val spreadPerShot: Double = 2.0
    override val spreadDecayPerSec: Double = 4.0

    override val tags: String = "kinetic12, astd_production"
    override val groupTag: String = "astd"
    override val tech: String = "菀星设计局-星坠"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val customPrimary: String = SsI18n.t("weapon.$id.tooltip.customPrimary")
    override val customPrimaryHL: String = SsI18n.t("weapon.$id.tooltip.customPrimaryHL")
    override val number: Int = 9213

    override val projSpec: ProjectileProjSpec = ProjectileProjSpec.boxBolt(
        id = "astd_electric_drive_accelerator_shot",
        spawnType = ProjectileSpawnType.BALLISTIC,
        onHitEffect = "cn.kasuminova.astd.combat.effect.arc.eda.ElectricDriveAcceleratorOnHitEffect",
        fringeColor = Rgba(255, 220, 120, 255),
        coreColor = Rgba(255, 240, 200, 200),
        length = 56.25,
        width = 10.0,
    )
}

object Wpn_astd_qiongjue_phase_railgun : WeaponDataEntry(), SsProjProjectileOutputs {
    override val id: String = "astd_qiongjue_phase_railgun"
    override val name: String = weaponName(id)
    override val tier: Int = 2
    override val baseValue: Int = 25000
    override val range: Int = 1100
    override val damagePerShot: Int = 640
    override val turnRate: Int = 8
    override val ops: Int = 28

    override val chargeup: Double = 1.0
    override val chargedown: Double = 1.5
    override val burstSize: Int = 1
    override val burstDelay: Double = 0.0

    override val type: String = "KINETIC"

    override val energyPerShot: Int = 960
    override val projSpeed: Int = 1800
    override val turnRateStr: String = "非常慢"
    override val accuracyStr: String = "完美"

    override val autofireAccBonus: Int = 1

    override val tags: String = "kinetic18, LR, astd_production"
    override val groupTag: String = "astd"
    override val tech: String = "菀星设计局-星坠"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val customPrimary: String = SsI18n.t("weapon.$id.tooltip.customPrimary")
    override val customPrimaryHL: String = SsI18n.t("weapon.$id.tooltip.customPrimaryHL")
    override val number: Int = 9214

    override val projSpec: ProjectileProjSpec = ProjectileProjSpec.boxBolt(
        id = "astd_qiongjue_phase_railgun_shot",
        spawnType = ProjectileSpawnType.BALLISTIC,
        onHitEffect = "cn.kasuminova.astd.combat.effect.arc.qiongjue.QiongjuePhaseRailgunOnHitEffect",
        fringeColor = Rgba(200, 225, 255, 255),
        coreColor = Rgba(255, 255, 255, 200),
        length = 93.75,
        width = 10.0,
    )
}

object Wpn_astd_positron_shockwave : WeaponDataEntry(), SsProjProjectileOutputs {
    override val id: String = "astd_positron_shockwave"
    override val name: String = weaponName(id)
    override val tier: Int = 1
    override val baseValue: Int = 2500
    override val range: Int = 600

    override val damagePerShot: Int = 150
    override val turnRate: Int = 45
    override val ops: Int = 6

    override val chargedown: Double = 1.0
    override val burstSize: Int = 1
    override val burstDelay: Double = 0.0

    override val type: String = "FRAGMENTATION"
    override val energyPerShot: Int = 75

    override val projSpeed: Int = 900

    override val flightTime: Double = 0.75
    override val aiHints: Set<AiHint> = setOf(AiHint.PD)

    override val tags: String = "pd7, SR, astd_production"
    override val groupTag: String = "astd"
    override val tech: String = "菀星设计局-星坠"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val customPrimary: String = SsI18n.t("weapon.$id.tooltip.customPrimary")
    override val customPrimaryHL: String = SsI18n.t("weapon.$id.tooltip.customPrimaryHL")
    override val number: Int = 9215

    override val projSpec: ProjectileProjSpec = ProjectileProjSpec.boxBolt(
        id = "astd_positron_shockwave_shot",
        spawnType = ProjectileSpawnType.BALLISTIC,
        onFireEffect = "cn.kasuminova.astd.combat.effect.arc.positronshockwave.PositronShockwaveOnFireEffect",
        onHitEffect = "cn.kasuminova.astd.combat.effect.arc.positronshockwave.PositronShockwaveOnHitEffect",
        collisionClass = "PROJECTILE_NO_FF",
        collisionClassByFighter = "PROJECTILE_NO_FF",
        fringeColor = Rgba(140, 200, 255, 255),
        coreColor = Rgba(240, 248, 255, 200),
    )
}

object Wpn_astd_seven_stars : WeaponDataEntry(), SsProjProjectileOutputs {
    override val id: String = "astd_seven_stars"
    override val name: String = weaponName(id)
    override val tier: Int = 3

    override val baseValue: Int = 150000
    override val range: Int = 800

    override val damagePerSecond: Int = 125
    override val damagePerShot: Int = 250

    override val emp: Int = 0
    override val impact: Int = 0
    override val turnRate: Int = 30
    override val ops: Int = 28
    override val type: String = "ENERGY"
    override val energyPerShot: Int = 750

    override val chargedown: Double = 2.0
    override val burstSize: Int = 1
    override val burstDelay: Double = 0.0

    override val projSpeed: Int = 3000

    override val flightTime: Double = 6.0
    override val aiHints: Set<AiHint> = setOf(AiHint.PD)

    override val tags: String = "pd19, energy20, no_drop, no_drop_salvage, restricted, codex_unlockable"
    override val groupTag: String = "astd"
    override val tech: String = "菀星设计局-星坠"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val customPrimary: String = SsI18n.t("weapon.$id.tooltip.customPrimary")
    override val customPrimaryHL: String = SsI18n.t("weapon.$id.tooltip.customPrimaryHL")
    override val number: Int = 9216

    override val projSpec: ProjectileProjSpec = ProjectileProjSpec(
        id = "astd_seven_stars_shot",
        spawnType = ProjectileSpawnType.BALLISTIC,
        onFireEffect = "cn.kasuminova.astd.combat.effect.arc.sevenstars.SevenStarsOnFireEffect",
        onHitEffect = null,
        collisionClass = "NONE",
        collisionClassByFighter = "NONE",
        length = 2.0,
        width = 2.0,
        fadeTime = 0.2,
        fringeColor = Rgba(120, 200, 255, 0),
        coreColor = Rgba(220, 245, 255, 0),
        textureScrollSpeed = 0.0,
        pixelsPerTexel = 1.0,
        bulletSprite = "graphics/textures/BUtil_NONE.png",
    )
}

object Wpn_astd_gemini_dem_launcher : WeaponDataEntry(), SsProjMissileOutputs {
    override val id: String = "astd_gemini_dem_launcher"
    override val name: String = weaponName(id)
    override val tier: Int = 2
    override val rarity: Int = 1
    override val baseValue: Int = 6000
    override val range: Int = 2000

    override val damagePerSecond: Int = 0

    override val damagePerShot: Int = 1000
    override val energyPerShot: Int = 500

    override val emp: Int = 1000
    override val turnRate: Int = 30
    override val ops: Int = 14
    override val ammo: Int = 4
    override val ammoPerSec: Double = 0.0333
    override val reloadSize: Int = 2

    override val type: String = "ENERGY"
    override val chargedown: Double = 12.0
    override val burstSize: Int = 2
    override val burstDelay: Double = 0.1
    override val projSpeed: Int = 225

    override val flightTime: Double = 14.0
    override val projHitpoints: Int = 600

    override val tags: String = "missile12, strike8, astd_production"
    override val groupTag: String = "astd"
    override val tech: String = "菀星设计局-星坠"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val customPrimary: String = SsI18n.t("weapon.$id.tooltip.customPrimary")
    override val customPrimaryHL: String = SsI18n.t("weapon.$id.tooltip.customPrimaryHL")
    override val noDpsInTooltip: Boolean = true
    override val aiHints: Set<AiHint> = setOf(AiHint.DO_NOT_AIM, AiHint.STRIKE)
    override val number: Int = 9221

    override val projSpec: MissileProjSpec = MissileProjSpec(
        id = "astd_gemini_dem_dummy",
        missileType = "MISSILE",
        onFireEffect = "cn.kasuminova.astd.combat.effect.arc.geminidem.GeminiDemSalvoOnFireEffect",
        sprite = "graphics/weapons/astd_gemini_dem_missile.png",
        size = Vec2i(12, 25),
        center = Vec2(6, 12.5),
        collisionRadius = 7,
        collisionClass = "MISSILE_NO_FF",
        explosionColor = Rgba(0, 0, 0, 0),
        explosionRadius = 0,
        flameoutTime = 0.5,
        noEngineGlowTime = 999.0,
        fadeTime = 0.25,
        engineSpec = MissileEngineSpec(turnAcc = 1800, turnRate = 1440, acc = 1800, dec = 1600),
        engineSlots = emptyList(),
    )
}

object Wpn_astd_gemini_dem_pod : WeaponDataEntry() {
    override val id: String = "astd_gemini_dem_pod"
    override val name: String = weaponName(id)
    override val tier: Int = 2
    override val rarity: Int = 1
    override val baseValue: Int = 14000
    override val range: Int = 2000
    override val damagePerSecond: Int = 0
    override val damagePerShot: Int = 1000
    override val energyPerShot: Int = 500
    override val emp: Int = 1000
    override val turnRate: Int = 30
    override val ops: Int = 28
    override val ammo: Int = 8
    override val ammoPerSec: Double = 0.0666
    override val reloadSize: Int = 2
    override val type: String = "ENERGY"
    override val chargedown: Double = 12.0
    override val burstSize: Int = 2
    override val burstDelay: Double = 0.1
    override val projSpeed: Int = 225
    override val flightTime: Double = 14.0
    override val projHitpoints: Int = 600

    override val tags: String = "missile17, strike13, astd_production"
    override val groupTag: String = "astd"
    override val tech: String = "菀星设计局-星坠"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val customPrimary: String = SsI18n.t("weapon.$id.tooltip.customPrimary")
    override val customPrimaryHL: String = SsI18n.t("weapon.$id.tooltip.customPrimaryHL")
    override val noDpsInTooltip: Boolean = true
    override val aiHints: Set<AiHint> = setOf(AiHint.DO_NOT_AIM, AiHint.STRIKE)
    override val number: Int = 9222
}

object Wpn_astd_gemini_dem_kinetic : WeaponDataEntry(), SsProjMissileOutputs {
    override val id: String = "astd_gemini_dem_kinetic"
    override val name: String = weaponName(id)
    override val tier: Int = 2
    override val baseValue: Int = 0
    override val range: Int = 2000

    override val damagePerShot: Int = 1000
    override val emp: Int = 1000
    override val turnRate: Int = 30
    override val type: String = "KINETIC"
    override val projSpeed: Int = 225
    override val flightTime: Double = 14.0
    override val projHitpoints: Int = 600
    override val tags: String = "no_drop, no_drop_salvage"
    override val aiHints: Set<AiHint> = setOf(AiHint.SYSTEM)
    override val tech: String = "菀星设计局-星坠"
    override val noDpsInTooltip: Boolean = true
    override val number: Int = 9223

    override val projSpec: MissileProjSpec = geminiDemWarheadProjSpec(
        id = "astd_gemini_dem_kinetic_msl",
        payloadWeaponId = "astd_gemini_dem_kinetic_payload",
        targetingLaserId = "astd_gemini_dem_targetinglaser_kinetic",
        explosionColor = Rgba(140, 190, 255, 180),
        engineColor = Rgba(140, 190, 255, 255),
        contrailColor = Rgba(120, 170, 255, 75),
        glowSprite = "graphics/weapons/astd_gemini_dem_missile_glow_blue.png",
    )
}

object Wpn_astd_gemini_dem_he : WeaponDataEntry(), SsProjMissileOutputs {
    override val id: String = "astd_gemini_dem_he"
    override val name: String = weaponName(id)
    override val tier: Int = 2
    override val baseValue: Int = 0
    override val range: Int = 2000
    override val damagePerShot: Int = 1000
    override val emp: Int = 0
    override val turnRate: Int = 30
    override val type: String = "HIGH_EXPLOSIVE"
    override val projSpeed: Int = 225
    override val flightTime: Double = 14.0
    override val projHitpoints: Int = 600
    override val tags: String = "no_drop, no_drop_salvage"
    override val aiHints: Set<AiHint> = setOf(AiHint.SYSTEM)
    override val tech: String = "菀星设计局-星坠"
    override val noDpsInTooltip: Boolean = true
    override val number: Int = 9224

    override val projSpec: MissileProjSpec = geminiDemWarheadProjSpec(
        id = "astd_gemini_dem_he_msl",
        payloadWeaponId = "astd_gemini_dem_he_payload",
        targetingLaserId = "astd_gemini_dem_targetinglaser_he",
        explosionColor = Rgba(255, 60, 70, 180),
        engineColor = Rgba(255, 90, 100, 255),
        contrailColor = Rgba(255, 60, 70, 75),
        glowSprite = "graphics/weapons/astd_gemini_dem_missile_glow_red.png",
    )
}

private fun geminiDemWarheadProjSpec(
    id: String,
    payloadWeaponId: String,
    targetingLaserId: String,
    explosionColor: Rgba,
    engineColor: Rgba,
    contrailColor: Rgba,
    glowSprite: String,
): MissileProjSpec = MissileProjSpec(
    id = id,
    missileType = "MISSILE",
    onFireEffect = null,
    sprite = "graphics/weapons/astd_gemini_dem_missile.png",
    glowSprite = glowSprite,
    glowColor = Rgba(255, 255, 255, 255),
    size = Vec2i(12, 25),
    center = Vec2(6, 12.5),
    collisionRadius = 7,
    collisionClass = "MISSILE_NO_FF",
    explosionColor = explosionColor,
    explosionRadius = 50,
    armingTime = 0.3,
    flameoutTime = 0.5,
    noEngineGlowTime = 0.0,
    fadeTime = 0.25,
    engineSpec = MissileEngineSpec(turnAcc = 225, turnRate = 75, acc = 600, dec = 105),
    engineSlots = listOf(
        MissileEngineSlot(
            id = "ES1",
            loc = Vec2i(-13, 0),
            style = "CUSTOM",
            styleSpec = MissileEngineSlotStyleSpec(
                mode = "QUAD_STRIP",
                engineColor = engineColor,
                glowSizeMult = 2.5,
                contrailDuration = 1.0,
                contrailWidthMult = 1.0,
                contrailWidthAddedFractionAtEnd = 2.5,
                contrailMinSeg = 5,
                contrailMaxSpeedMult = 0.5,
                contrailAngularVelocityMult = 0.5,
                contrailSpawnDistMult = 1.0,
                contrailColor = contrailColor,
                type = "GLOW",
            ),
            width = 7.0,
            length = 40.0,
            angle = 180.0,
        ),
    ),
    behaviorSpec = mapOf(
        "behavior" to "CUSTOM",
        "minDelayBeforeTriggering" to 0.5,
        "triggerDistance" to listOf(700, 750),
        "preferredMinFireDistance" to listOf(700, 750),
        "turnRateBoost" to 100,
        "targetingTime" to 2,
        "firingTime" to 1,
        "targetingLaserId" to targetingLaserId,
        "targetingLaserFireOffset" to listOf(8, 0, 8, 0),
        "targetingLaserSweepAngles" to listOf(0, -7, 0, 7),
        "payloadWeaponId" to payloadWeaponId,
        "targetingLaserRange" to 900,
        "targetingLaserArc" to 10,
        "bombPumped" to true,
        "fadeOutEngineWhenFiring" to false,
        "destroyMissleWhenDoneFiring" to false,
        "snapFacingToTargetIfCloseEnough" to false,
    ),
)

object Wpn_astd_gemini_dem_kinetic_payload : WeaponDataEntry() {
    override val id: String = "astd_gemini_dem_kinetic_payload"
    override val name: String = weaponName(id)
    override val tier: Int = 2
    override val baseValue: Int = 0

    override val range: Int = 1000

    override val damagePerSecond: Int = 1000

    override val damagePerShot: Int = 0
    override val type: String = "KINETIC"

    override val burstSize: Int = 1
    override val burstDelay: Double = 0.0

    override val beamSpeed: Int = 1000000
    override val aiHints: Set<AiHint> = setOf(AiHint.SYSTEM, AiHint.DANGEROUS)
    override val tags: String = "fires_one_burst, no_drop, no_drop_salvage"
    override val tech: String = "菀星设计局-星坠"
    override val noDpsInTooltip: Boolean = true
    override val number: Int = 9225
}

object Wpn_astd_gemini_dem_he_payload : WeaponDataEntry() {    override val id: String = "astd_gemini_dem_he_payload"
    override val name: String = weaponName(id)
    override val tier: Int = 2
    override val baseValue: Int = 0

    override val range: Int = 1000
    override val damagePerSecond: Int = 1500
    override val damagePerShot: Int = 0
    override val type: String = "HIGH_EXPLOSIVE"
    override val burstSize: Int = 1
    override val burstDelay: Double = 0.0
    override val beamSpeed: Int = 1000000
    override val aiHints: Set<AiHint> = setOf(AiHint.SYSTEM, AiHint.DANGEROUS)
    override val tags: String = "fires_one_burst, no_drop, no_drop_salvage"
    override val tech: String = "菀星设计局-星坠"
    override val noDpsInTooltip: Boolean = true
    override val number: Int = 9226
}

object Wpn_astd_gemini_dem_kinetic_fighter : WeaponDataEntry(), SsProjMissileOutputs {
    override val id: String = "astd_gemini_dem_kinetic_fighter"
    override val name: String = weaponName(id)
    override val tier: Int = 2
    override val baseValue: Int = 0
    override val range: Int = 1500

    override val damagePerShot: Int = 750
    override val emp: Int = 750
    override val turnRate: Int = 30
    override val type: String = "KINETIC"
    override val projSpeed: Int = 225
    override val flightTime: Double = 14.0
    override val projHitpoints: Int = 600
    override val tags: String = "no_drop, no_drop_salvage"
    override val aiHints: Set<AiHint> = setOf(AiHint.SYSTEM)
    override val tech: String = "菀星设计局-星坠"
    override val noDpsInTooltip: Boolean = true
    override val number: Int = 9249

    override val projSpec: MissileProjSpec = geminiDemWarheadProjSpec(
        id = "astd_gemini_dem_kinetic_fighter_msl",
        payloadWeaponId = "astd_gemini_dem_kinetic_payload_fighter",
        targetingLaserId = "astd_gemini_dem_targetinglaser_kinetic",
        explosionColor = Rgba(140, 190, 255, 180),
        engineColor = Rgba(140, 190, 255, 255),
        contrailColor = Rgba(120, 170, 255, 75),
        glowSprite = "graphics/weapons/astd_gemini_dem_missile_glow_blue.png",
    )
}

object Wpn_astd_gemini_dem_he_fighter : WeaponDataEntry(), SsProjMissileOutputs {
    override val id: String = "astd_gemini_dem_he_fighter"
    override val name: String = weaponName(id)
    override val tier: Int = 2
    override val baseValue: Int = 0
    override val range: Int = 1500
    override val damagePerShot: Int = 750
    override val emp: Int = 0
    override val turnRate: Int = 30
    override val type: String = "HIGH_EXPLOSIVE"
    override val projSpeed: Int = 225
    override val flightTime: Double = 14.0
    override val projHitpoints: Int = 600
    override val tags: String = "no_drop, no_drop_salvage"
    override val aiHints: Set<AiHint> = setOf(AiHint.SYSTEM)
    override val tech: String = "菀星设计局-星坠"
    override val noDpsInTooltip: Boolean = true
    override val number: Int = 9250

    override val projSpec: MissileProjSpec = geminiDemWarheadProjSpec(
        id = "astd_gemini_dem_he_fighter_msl",
        payloadWeaponId = "astd_gemini_dem_he_payload_fighter",
        targetingLaserId = "astd_gemini_dem_targetinglaser_he",
        explosionColor = Rgba(255, 60, 70, 180),
        engineColor = Rgba(255, 90, 100, 255),
        contrailColor = Rgba(255, 60, 70, 75),
        glowSprite = "graphics/weapons/astd_gemini_dem_missile_glow_red.png",
    )
}

object Wpn_astd_gemini_dem_kinetic_payload_fighter : WeaponDataEntry() {
    override val id: String = "astd_gemini_dem_kinetic_payload_fighter"
    override val name: String = weaponName(id)
    override val tier: Int = 2
    override val baseValue: Int = 0

    override val range: Int = 1000
    override val damagePerSecond: Int = 750

    override val damagePerShot: Int = 0
    override val type: String = "KINETIC"

    override val burstSize: Int = 1
    override val burstDelay: Double = 0.0

    override val beamSpeed: Int = 1000000
    override val aiHints: Set<AiHint> = setOf(AiHint.SYSTEM, AiHint.DANGEROUS)
    override val tags: String = "fires_one_burst, no_drop, no_drop_salvage"
    override val tech: String = "菀星设计局-星坠"
    override val noDpsInTooltip: Boolean = true
    override val number: Int = 9251
}

object Wpn_astd_gemini_dem_he_payload_fighter : WeaponDataEntry() {
    override val id: String = "astd_gemini_dem_he_payload_fighter"
    override val name: String = weaponName(id)
    override val tier: Int = 2
    override val baseValue: Int = 0
    override val range: Int = 1000
    override val damagePerSecond: Int = 1125
    override val damagePerShot: Int = 0
    override val type: String = "HIGH_EXPLOSIVE"
    override val burstSize: Int = 1
    override val burstDelay: Double = 0.0
    override val beamSpeed: Int = 1000000
    override val aiHints: Set<AiHint> = setOf(AiHint.SYSTEM, AiHint.DANGEROUS)
    override val tags: String = "fires_one_burst, no_drop, no_drop_salvage"
    override val tech: String = "菀星设计局-星坠"
    override val noDpsInTooltip: Boolean = true
    override val number: Int = 9252
}

object Wpn_astd_gemini_dem_targetinglaser_kinetic : WeaponDataEntry() {
    override val id: String = "astd_gemini_dem_targetinglaser_kinetic"
    override val name: String = weaponName(id)
    override val tier: Int = 0
    override val baseValue: Int = 0
    override val type: String = "ENERGY"
    override val beamSpeed: Int = 1000000
    override val aiHints: Set<AiHint> = setOf(AiHint.SYSTEM)
    override val tags: String = "no_drop, no_drop_salvage"
    override val tech: String = "菀星设计局-星坠"
    override val noDpsInTooltip: Boolean = true
    override val number: Int = 9235
}

object Wpn_astd_gemini_dem_targetinglaser_he : WeaponDataEntry() {
    override val id: String = "astd_gemini_dem_targetinglaser_he"
    override val name: String = weaponName(id)
    override val tier: Int = 0
    override val baseValue: Int = 0
    override val type: String = "ENERGY"
    override val beamSpeed: Int = 1000000
    override val aiHints: Set<AiHint> = setOf(AiHint.SYSTEM)
    override val tags: String = "no_drop, no_drop_salvage"
    override val tech: String = "菀星设计局-星坠"
    override val noDpsInTooltip: Boolean = true
    override val number: Int = 9236
}

object Wpn_astd_heavy_ion_pulse : WeaponDataEntry(), SsProjProjectileOutputs {
    override val id: String = "astd_heavy_ion_pulse"
    override val name: String = weaponName(id)
    override val tier: Int = 2
    override val rarity: Int = 1
    override val baseValue: Int = 24000
    override val range: Int = 800

    override val damagePerSecond: Int = 400
    override val damagePerShot: Int = 250
    override val emp: Int = 500
    override val impact: Int = 0
    override val turnRate: Int = 20
    override val ops: Int = 27

    override val chargeup: Double = 0.05
    override val chargedown: Double = 0.4
    override val burstSize: Int = 4
    override val burstDelay: Double = 0.1

    override val ammo: Int = 24
    override val ammoPerSec: Double = 1.6
    override val reloadSize: Int = 8
    override val type: String = "ENERGY"
    override val energyPerShot: Int = 275

    override val projSpeed: Int = 1000

    override val minSpread: Double = 3.0
    override val maxSpread: Double = 20.0
    override val spreadPerShot: Double = 1.0
    override val spreadDecayPerSec: Double = 4.0

    override val tags: String = "energy18, astd_production"
    override val groupTag: String = "astd"
    override val tech: String = "菀星设计局-星坠"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val customPrimary: String = SsI18n.t("weapon.$id.tooltip.customPrimary")
    override val customPrimaryHL: String = SsI18n.t("weapon.$id.tooltip.customPrimaryHL")
    override val number: Int = 9212

    override val projSpec: ProjectileProjSpec = ProjectileProjSpec.boxBolt(
        id = "astd_heavy_ion_pulse_shot",
        spawnType = ProjectileSpawnType.BALLISTIC,
        onHitEffect = "cn.kasuminova.astd.combat.effect.arc.heavyionpulse.HeavyIonPulseOnHitEffect",
        fringeColor = Rgba(140, 200, 255, 255),
        coreColor = Rgba(225, 242, 255, 200),
        width = 10.0,
    )
}

object Wpn_astd_piercing_lance : WeaponDataEntry(), SsProjProjectileOutputs {
    override val id: String = "astd_piercing_lance"
    override val name: String = weaponName(id)
    override val tier: Int = 3
    override val baseValue: Int = 60000
    override val range: Int = 1000

    override val damagePerSecond: Int = 357
    override val damagePerShot: Int = 2500

    override val emp: Int = 0
    override val impact: Int = 0
    override val turnRate: Int = 30
    override val ops: Int = 30

    override val chargeup: Double = 2.0
    override val chargedown: Double = 5.0
    override val burstSize: Int = 1
    override val burstDelay: Double = 0.0

    override val type: String = "ENERGY"
    override val energyPerShot: Int = 3000

    override val projSpeed: Int = 3000

    override val tags: String = "energy20, LR, no_drop, no_drop_salvage, restricted, codex_unlockable"
    override val groupTag: String = "astd"
    override val tech: String = "菀星设计局-星坠"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val customPrimary: String = SsI18n.t("weapon.$id.tooltip.customPrimary")
    override val customPrimaryHL: String = SsI18n.t("weapon.$id.tooltip.customPrimaryHL")
    override val number: Int = 9219

    override val projSpec: ProjectileProjSpec = ProjectileProjSpec.boxBolt(
        id = "astd_piercing_lance_shot",
        spawnType = ProjectileSpawnType.BALLISTIC,
        onHitEffect = "cn.kasuminova.astd.combat.effect.arc.piercinglance.PiercingLanceOnHitEffect",
        fringeColor = Rgba(120, 200, 255, 255),
        coreColor = Rgba(220, 245, 255, 200),
        length = 36.0,
        width = 36.0,
    )
}

private fun cuifengTorpedoProjSpec(): MissileProjSpec = MissileProjSpec(
    id = "astd_cuifeng_torpedo_shot",
    missileType = "MISSILE",
    onFireEffect = "cn.kasuminova.astd.combat.effect.arc.cuifeng.CuifengTorpedoOnFireEffect",
    onHitEffect = "cn.kasuminova.astd.combat.effect.arc.cuifeng.CuifengTorpedoOnHitEffect",
    sprite = "graphics/weapons/astd_cuifeng_missile.png",
    glowSprite = "graphics/weapons/astd_cuifeng_missile_glow.png",
    glowColor = Rgba(255, 255, 255, 255),
    size = Vec2i(13, 35),
    center = Vec2(6.5, 17.5),
    collisionRadius = 15,
    collisionClass = "MISSILE_NO_FF",
    explosionColor = Rgba(140, 190, 255, 160),
    explosionRadius = 150,
    armingTime = 0.0,
    flameoutTime = 0.5,
    noEngineGlowTime = 999.0,
    fadeTime = 0.25,
    engineSpec = MissileEngineSpec(turnAcc = 2000, turnRate = 500, acc = 2000, dec = 2000),
    engineSlots = emptyList(),
)

object Wpn_astd_cuifeng_torpedo : WeaponDataEntry(), SsProjMissileOutputs {
    override val id: String = "astd_cuifeng_torpedo"
    override val name: String = weaponName(id)
    override val tier: Int = 3
    override val baseValue: Int = 20000
    override val range: Int = 1600

    override val damagePerShot: Int = 1500

    override val turnRate: Int = 30
    override val ops: Int = 8

    override val ammo: Int = 2
    override val ammoPerSec: Double = 0.0166
    override val reloadSize: Int = 1

    override val chargedown: Double = 5.0
    override val burstSize: Number = 1
    override val burstDelay: Double = 0.0

    override val type: String = "ENERGY"
    override val energyPerShot: Int = 500

    override val projSpeed: Int = 1500
    override val launchSpeed: Int = 200

    override val flightTime: Double = 2.0
    override val projHitpoints: Int = 500

    override val trackingStr: String = "优秀"
    override val speedStr: String = "极快"

    override val tags: String = "missile8, strike9, no_drop, no_drop_salvage, restricted, codex_unlockable"
    override val groupTag: String = "astd"
    override val tech: String = "菀星设计局-星坠"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val customPrimary: String = SsI18n.t("weapon.$id.tooltip.customPrimary")
    override val customPrimaryHL: String = SsI18n.t("weapon.$id.tooltip.customPrimaryHL")
    override val noDpsInTooltip: Boolean = true
    override val aiHints: Set<AiHint> = setOf(AiHint.DO_NOT_AIM, AiHint.STRIKE)
    override val number: Int = 9227

    override val projSpec: MissileProjSpec = cuifengTorpedoProjSpec()
}

object Wpn_astd_cuifeng_launcher : WeaponDataEntry() {
    override val id: String = "astd_cuifeng_launcher"
    override val name: String = weaponName(id)
    override val tier: Int = 3
    override val baseValue: Int = 40000
    override val range: Int = 1600

    override val damagePerShot: Int = 1500

    override val turnRate: Int = 30
    override val ops: Int = 16

    override val ammo: Int = 5
    override val ammoPerSec: Double = 1.0 / 40.0
    override val reloadSize: Int = 1

    override val chargedown: Double = 10.0
    override val burstSize: Number = 1
    override val burstDelay: Double = 0.0

    override val type: String = "ENERGY"
    override val energyPerShot: Int = 500

    override val projSpeed: Int = 1500
    override val launchSpeed: Int = 200
    override val flightTime: Double = 2.0
    override val projHitpoints: Int = 500

    override val trackingStr: String = "优秀"
    override val speedStr: String = "极快"

    override val tags: String = "missile12, strike13, no_drop, no_drop_salvage, restricted, codex_unlockable"
    override val groupTag: String = "astd"
    override val tech: String = "菀星设计局-星坠"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val customPrimary: String = SsI18n.t("weapon.$id.tooltip.customPrimary")
    override val customPrimaryHL: String = SsI18n.t("weapon.$id.tooltip.customPrimaryHL")
    override val noDpsInTooltip: Boolean = true
    override val aiHints: Set<AiHint> = setOf(AiHint.DO_NOT_AIM, AiHint.STRIKE)
    override val number: Int = 9228
}
