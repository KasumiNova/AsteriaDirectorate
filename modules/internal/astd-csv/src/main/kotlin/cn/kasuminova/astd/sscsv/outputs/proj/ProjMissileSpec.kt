package cn.kasuminova.astd.sscsv.outputs.proj

import cn.kasuminova.astd.sscsv.GeneratedJsonFile
import cn.kasuminova.astd.sscsv.SsJsonOutputs

/** Small helper types for .proj JSON generation (missile spec only, for now). */

data class Vec2i(val x: Int, val y: Int) {
    fun toJson(): List<Int> = listOf(x, y)
}

/**
 * A numeric 2D vector for JSON output.
 *
 * Some vanilla/spec files use fractional centers (e.g. `10.5`), so we can't restrict to Int.
 */
data class Vec2(val x: Number, val y: Number) {
    fun toJson(): List<Number> = listOf(x, y)
}

data class Rgba(val r: Int, val g: Int, val b: Int, val a: Int) {
    fun toJson(): List<Int> = listOf(r, g, b, a)
}

data class MissileEngineSpec(
    val turnAcc: Int,
    val turnRate: Int,
    val acc: Int,
    val dec: Int,
) {
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "turnAcc" to turnAcc,
        "turnRate" to turnRate,
        "acc" to acc,
        "dec" to dec,
    )
}

data class MissileEngineSlotStyleSpec(
    val mode: String,
    val engineColor: Rgba,
    val glowSizeMult: Double,
    val contrailDuration: Double,
    val contrailWidthMult: Double,
    val contrailWidthAddedFractionAtEnd: Double,
    val contrailMinSeg: Int,
    val contrailMaxSpeedMult: Double,
    val contrailAngularVelocityMult: Double,
    val contrailSpawnDistMult: Double,
    val contrailColor: Rgba,
    val type: String,
) {
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "mode" to mode,
        "engineColor" to engineColor.toJson(),
        "glowSizeMult" to glowSizeMult,
        "contrailDuration" to contrailDuration,
        "contrailWidthMult" to contrailWidthMult,
        "contrailWidthAddedFractionAtEnd" to contrailWidthAddedFractionAtEnd,
        "contrailMinSeg" to contrailMinSeg,
        "contrailMaxSpeedMult" to contrailMaxSpeedMult,
        "contrailAngularVelocityMult" to contrailAngularVelocityMult,
        "contrailSpawnDistMult" to contrailSpawnDistMult,
        "contrailColor" to contrailColor.toJson(),
        "type" to type,
    )
}

data class MissileEngineSlot(
    val id: String,
    val loc: Vec2i,
    val style: String,
    val styleSpec: MissileEngineSlotStyleSpec,
    val width: Double,
    val length: Double,
    val angle: Double,
) {
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "loc" to loc.toJson(),
        "style" to style,
        "styleSpec" to styleSpec.toJson(),
        "width" to width,
        "length" to length,
        "angle" to angle,
    )
}

data class MissileProjSpec(
    val id: String,
    val missileType: String = "MISSILE",
    val onFireEffect: String? = null,
    val onHitEffect: String? = null,
    val sprite: String,
    val size: Vec2i,
    val center: Vec2,
    val collisionRadius: Int,
    val collisionClass: String,
    val explosionColor: Rgba,
    val explosionRadius: Int,
    val armingTime: Double? = null,
    val flameoutTime: Double,
    val noEngineGlowTime: Double,
    val fadeTime: Double,
    val engineSpec: MissileEngineSpec,
    val engineSlots: List<MissileEngineSlot> = emptyList(),
    /** 导弹行为声明块（原版 behaviorSpec 原样透传；DEM/MIRV 等行为必需，规格 10 §1.2）。 */
    val behaviorSpec: Map<String, Any?>? = null,
) {
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "specClass" to "missile",
        "missileType" to missileType,
        "behaviorSpec" to behaviorSpec,
        "onFireEffect" to onFireEffect,
        "onHitEffect" to onHitEffect,
        "sprite" to sprite,
        "size" to size.toJson(),
        "center" to center.toJson(),
        "collisionRadius" to collisionRadius,
        "collisionClass" to collisionClass,
        "explosionColor" to explosionColor.toJson(),
        "explosionRadius" to explosionRadius,
        "armingTime" to armingTime,
        "flameoutTime" to flameoutTime,
        "noEngineGlowTime" to noEngineGlowTime,
        "fadeTime" to fadeTime,
        "engineSpec" to engineSpec.toJson(),
        "engineSlots" to engineSlots.map { it.toJson() },
    ).filterValues { it != null }
}

/**
 * Mix-in output interface: an entry can implement this to emit a `.proj` file.
 *
 * Designed for “interface composition”: weapon row entry + proj spec output in one Kotlin object.
 */
interface SsProjMissileOutputs : SsJsonOutputs {
    val projSpec: MissileProjSpec

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
