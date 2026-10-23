package cn.kasuminova.astd.renderer.beam.driver

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import org.lwjgl.util.vector.Vector2f

enum class BeamVfxDriverState { Active, Removed }

class BeamFrame(
    val start: Vector2f,
    val facing: Float,
    val length: Float,
    val endpoint: Vector2f?,
    val firing: Boolean,
    val strength: Float,
    val fadeMul: Float = 1f,
    val hitTarget: CombatEntityAPI? = null,
    val isShieldHit: Boolean = false,
)

interface BeamVfxDriver {

    val state: BeamVfxDriverState

    fun advance(engine: CombatEngineAPI, frame: BeamFrame, amount: Float)

    fun dispose()
}
