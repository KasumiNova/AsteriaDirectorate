package cn.kasuminova.astd.sscsv.outputs.proj

import cn.kasuminova.astd.sscsv.GeneratedJsonFile
import cn.kasuminova.astd.sscsv.SsJsonOutputs

enum class ProjectileSpawnType {
    BEAM,
    BALLISTIC_AS_BEAM,
    BALLISTIC,
    PLASMA,
    MISSILE,
    OTHER,
}

data class ProjectileProjSpec(
    val id: String,
    val spawnType: ProjectileSpawnType,
    val onFireEffect: String? = null,
    val onHitEffect: String? = null,
    val collisionClass: String,
    val collisionClassByFighter: String? = null,
    val length: Double,
    val width: Double,
    val fadeTime: Double,
    val fringeColor: Rgba,
    val coreColor: Rgba,
    val textureScrollSpeed: Double,
    val pixelsPerTexel: Double,
    // null 时不写 hitGlowRadius 键，原版默认取 length × 2 并按伤害放大，高射速武器必须显式给值。
    val hitGlowRadius: Double? = null,
    // null 时不写 bulletSprite 键，走原版默认弹体渲染。
    val bulletSprite: String? = null,
) {
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "specClass" to "projectile",
        "spawnType" to spawnType.name,
        "onFireEffect" to onFireEffect,
        "onHitEffect" to onHitEffect,
        "collisionClass" to collisionClass,
        "collisionClassByFighter" to collisionClassByFighter,
        "length" to length,
        "width" to width,
        "fadeTime" to fadeTime,
        "fringeColor" to fringeColor.toJson(),
        "coreColor" to coreColor.toJson(),
        "textureScrollSpeed" to textureScrollSpeed,
        "pixelsPerTexel" to pixelsPerTexel,
        "hitGlowRadius" to hitGlowRadius,
        "bulletSprite" to bulletSprite,
    ).filterValues { it != null }

    companion object {
        fun vanillaBolt(
            id: String,
            spawnType: ProjectileSpawnType = ProjectileSpawnType.BALLISTIC,
            onFireEffect: String = "cn.kasuminova.astd.combat.effect.generic.ProjectileSpecOnFireDispatcher",
            onHitEffect: String? = null,
            collisionClass: String = "PROJECTILE_FF",
            collisionClassByFighter: String = "PROJECTILE_FIGHTER",
            fringeColor: Rgba,
            coreColor: Rgba,
            hitGlowRadius: Double = 25.0,
            length: Double = 75.0,
            width: Double = 20.0,
        ): ProjectileProjSpec {
            return ProjectileProjSpec(
                id = id,
                spawnType = spawnType,
                onFireEffect = onFireEffect,
                onHitEffect = onHitEffect,
                collisionClass = collisionClass,
                collisionClassByFighter = collisionClassByFighter,
                length = length,
                width = width,
                fadeTime = 0.25,
                fringeColor = fringeColor,
                coreColor = coreColor,
                textureScrollSpeed = -256.0,
                pixelsPerTexel = 1.0,
                hitGlowRadius = hitGlowRadius,
            )
        }

        fun boxBolt(
            id: String,
            spawnType: ProjectileSpawnType = ProjectileSpawnType.BALLISTIC,
            onFireEffect: String = "cn.kasuminova.astd.combat.effect.generic.ProjectileSpecOnFireDispatcher",
            onHitEffect: String? = null,
            collisionClass: String = "PROJECTILE_FF",
            collisionClassByFighter: String = "PROJECTILE_FIGHTER",
            fringeColor: Rgba,
            coreColor: Rgba,
            hitGlowRadius: Double = 25.0,
            length: Double = 75.0,
            width: Double = 20.0,
        ): ProjectileProjSpec {
            return ProjectileProjSpec(
                id = id,
                spawnType = spawnType,
                onFireEffect = onFireEffect,
                onHitEffect = onHitEffect,
                collisionClass = collisionClass,
                collisionClassByFighter = collisionClassByFighter,
                length = length,
                width = width,
                fadeTime = 0.25,
                fringeColor = fringeColor.copy(a = 0),
                coreColor = coreColor.copy(a = 0),
                textureScrollSpeed = 0.0,
                pixelsPerTexel = 1.0,
                hitGlowRadius = hitGlowRadius,
                bulletSprite = "graphics/textures/BUtil_NONE.png",
            )
        }
    }
}

interface SsProjProjectileOutputs : SsJsonOutputs {
    val projSpec: ProjectileProjSpec

    val projRelativePath: String
        get() = "data/weapons/proj/${projSpec.id}.proj"

    override fun jsonExtraFiles(): List<GeneratedJsonFile> {
        return listOf(
            GeneratedJsonFile(
                relativePath = projRelativePath,
                jsonValue = projSpec.toJson(),
            )
        )
    }
}
