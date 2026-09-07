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
    /**
     * 命中光晕基准半径；null 时不写 `hitGlowRadius` 键，原版默认取 `length × 2`
     * （length 75 即 150 基准，再按伤害放大，高射速武器会堆成吞没舰船的巨球，必须显式给值）。
     */
    val hitGlowRadius: Double? = null,
    /**
     * 自定义弹体贴图；null 时不写 `bulletSprite` 键，走原版默认弹体渲染
     * （projtrail.png + projbody.png 按 fringeColor/coreColor 染色的能量螺栓，尺寸取 length/width）。
     */
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
        /**
         * 原版螺栓渲染弹体（无 bulletSprite）：弹头/弹芯由原版 projbody/projtrail 渲染器按
         * fringeColor/coreColor 染色绘制，尺寸/消散节奏对齐原版“离子脉冲”（ionpulser_shot.proj：
         * length 75 / width 20 / fadeTime 0.25 / textureScrollSpeed -256 / pixelsPerTexel 1）。
         * 拖尾仍由 ASTD VFX 管线（ProjectileVfxSpecs 三层贴图混合）承担。
         *
         * 需要全隐弹体（如七星折跃弹）不用本工厂，显式构造并传
         * `bulletSprite = "graphics/textures/BUtil_NONE.png"` + 色 alpha=0。
         *
         * @param hitGlowRadius 命中光晕基准半径，默认 25（原版高射速武器口径：火神 15 / 重机枪 20 /
         * 重型针刺 25）。不显式给值时原版取 `length × 2` = 150 并再按伤害放大，高射速叠加下会糊满全屏。
         */
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
            /**
             * 弹体尺寸（su）：默认对齐原版“离子脉冲”长条螺栓（75×20）；
             * 圆球状弹体（如贯星之矛）传等长等宽。ASTD VFX 管线的拖尾锚点前移量自动取 length/2。
             */
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

        /**
         * Box 螺栓渲染弹体：原版螺栓视觉整体屏蔽（`bulletSprite = BUtil_NONE.png` + 双色 alpha=0 +
         * scroll=0），弹头由 ASTD VFX 管线的 Box 螺栓组件（SpriteEntity 双趟烘焙彗形贴图）逐帧接管。
         *
         * length/width/fadeTime 保持真实值：原版弹体逻辑（TrailExtender 出生伸入/超射程淡出、
         * getBrightness、tailEnd）仍是 Box 螺栓与拖尾锚点（headLead 缺省 0 = 弹体前端）的数据源；
         * 颜色 RGB 仅作存档可读性保留，alpha 强制为 0。
         *
         * 注意：屏蔽后原版命中光晕（fringeColor 染色粒子）不可见，由 Box 螺栓组件在命中时补发。
         *
         * @param hitGlowRadius 命中光晕基准半径，默认 25；同时被 Box 螺栓组件的补发光晕复用。
         */
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
