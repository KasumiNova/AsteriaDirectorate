package cn.kasuminova.astd.sscsv.outputs.proj

import cn.kasuminova.astd.sscsv.GeneratedJsonFile
import cn.kasuminova.astd.sscsv.SsJsonOutputs

/**
 * 与 `com.fs.starfarer.api.loading.ProjectileSpawnType` 对齐。
 *
 * ss-csv 侧使用强类型枚举，避免在编辑/生成时把该字段误当作数字等其他类型。
 */
enum class ProjectileSpawnType {
    BEAM,
    BALLISTIC_AS_BEAM,
    BALLISTIC,
    PLASMA,
    MISSILE,
    OTHER,
}

/**
 * `.proj` generator model for `specClass = projectile`.
 *
 * This project currently uses a fairly uniform "PLASMA/BALLISTIC + scrolling texture" spec,
 * so we implement that subset first.
 */

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
    val bulletSprite: String,
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
        "bulletSprite" to bulletSprite,
    ).filterValues { it != null }

    companion object {
        /**
         * Matches the project's common "BUtil_NONE + scroll" projectile style.
         *
         * 弹体外观完全由代码 VFX（RenderEntity/DSL）承担，故原版弹丸辉光（fringeColor/coreColor）默认 alpha=0 全隐藏——
         * 否则会与模组拖尾同时渲染出一段原版弹芯。个别弹体如需保留原版可见弹芯，显式传入非零 alpha 的颜色。
         */
        fun standard(
            id: String,
            spawnType: ProjectileSpawnType = ProjectileSpawnType.PLASMA,
            onFireEffect: String = "cn.kasuminova.astd.combat.effect.generic.ProjectileSpecOnFireDispatcher",
            onHitEffect: String? = null,
            fringeColor: Rgba = Rgba(120, 200, 255, 0),
            coreColor: Rgba = Rgba(220, 245, 255, 0),
        ): ProjectileProjSpec {
            return ProjectileProjSpec(
                id = id,
                spawnType = spawnType,
                onFireEffect = onFireEffect,
                onHitEffect = onHitEffect,
                collisionClass = "PROJECTILE_FF",
                collisionClassByFighter = "PROJECTILE_FIGHTER",
                length = 24.0,
                width = 8.0,
                fadeTime = 0.2,
                fringeColor = fringeColor,
                coreColor = coreColor,
                textureScrollSpeed = 64.0,
                pixelsPerTexel = 5.0,
                bulletSprite = "graphics/textures/BUtil_NONE.png",
            )
        }

        /**
         * 兼容：历史代码使用字符串传入 spawnType（例如 "BALLISTIC"）。
         *
         * 该重载会强制解析为 [ProjectileSpawnType]，避免写出非法值。
         */
        @Deprecated("Use standard(id, spawnType: ProjectileSpawnType, ...) instead")
        fun standard(
            id: String,
            spawnType: String,
            onFireEffect: String = "cn.kasuminova.astd.combat.effect.generic.ProjectileSpecOnFireDispatcher",
            onHitEffect: String? = null,
            fringeColor: Rgba = Rgba(120, 200, 255, 0),
            coreColor: Rgba = Rgba(220, 245, 255, 0),
        ): ProjectileProjSpec {
            // 注意：这里不要使用命名参数调用（Kotlin 在同名重载+默认参数场景下可能误选回自身）。
            return standard(
                id,
                ProjectileSpawnType.valueOf(spawnType),
                onFireEffect,
                onHitEffect,
                fringeColor,
                coreColor,
            )
        }
    }
}

/** Interface mix-in: emit a `specClass=projectile` `.proj` file. */
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
