package cn.kasuminova.astd.combat.effect.lens.iceshard

import com.fs.starfarer.api.combat.CombatEngineAPI
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.random.Random

/**
 * 源生冰晶 MIRV 特效触发层（purple/30-superlative.md §特效）：
 * 分裂爆发（爆炸闪光 + 少量冰蓝星云）与附着期星云（每个伤害周期 3 个 50px ±25% 淡蓝星云，存续 2s）。
 * 冰晶射体本体走 `.proj` 原版贴图渲染 + ProjectileVfxSpecs 贴图拖尾层，不在本类职责内。
 */
object IceShardMirvVfx {

    /** 冰晶主色（淡冰蓝）。 */
    private val ICE_COLOR = Color(170, 225, 255, 120)

    /** 附着星云色（淡蓝，alpha 由 addNebulaParticle 亮度参数调制）。 */
    private val ATTACH_NEBULA_COLOR = Color(150, 215, 255, 110)

    /** 静止速度矢量（爆炸闪光用，避免逐次分配）。 */
    private val ZERO_VEL = Vector2f(0f, 0f)

    /** 分裂爆发：小型冰蓝爆炸闪光 + 6 颗四散星云（分裂帧母弹位置的视觉锚）。 */
    fun spawnSplitBurst(engine: CombatEngineAPI, origin: Vector2f, random: Random) {
        engine.spawnExplosion(origin, ZERO_VEL, ICE_COLOR, SPLIT_FLASH_SIZE, SPLIT_FLASH_DURATION)
        repeat(SPLIT_NEBULA_COUNT) {
            val angle = random.nextFloat() * 360f
            val rad = Math.toRadians(angle.toDouble())
            val speed = SPLIT_NEBULA_SPEED_MIN +
                    random.nextFloat() * (SPLIT_NEBULA_SPEED_MAX - SPLIT_NEBULA_SPEED_MIN)
            engine.addNebulaParticle(
                Vector2f(origin),
                Vector2f((Math.cos(rad) * speed).toFloat(), (Math.sin(rad) * speed).toFloat()),
                SPLIT_NEBULA_SIZE, 1.9f, 0.25f, 0.55f,
                SPLIT_NEBULA_DURATION + (random.nextFloat() - 0.5f) * 0.4f, ICE_COLOR,
            )
        }
        bumpTelemetry(engine, TELEMETRY_SPLIT_VFX)
    }

    /** 附着期星云节奏批（由附着脚本按伤害周期节奏触发：3 个 50px、25% 大小浮动、淡蓝色、存续 2s）。 */
    fun spawnAttachNebula(engine: CombatEngineAPI, point: Vector2f, random: Random) {
        repeat(ATTACH_NEBULA_COUNT) {
            val size = ATTACH_NEBULA_SIZE * (0.75f + random.nextFloat() * 0.5f)
            val angle = random.nextFloat() * 360f
            val rad = Math.toRadians(angle.toDouble())
            val speed = random.nextFloat() * ATTACH_NEBULA_SPEED_MAX
            engine.addNebulaParticle(
                Vector2f(point),
                Vector2f((Math.cos(rad) * speed).toFloat(), (Math.sin(rad) * speed).toFloat()),
                size, 1.9f, 0.25f, 0.55f,
                ATTACH_NEBULA_DURATION + (random.nextFloat() - 0.5f) * 0.4f, ATTACH_NEBULA_COLOR,
            )
        }
        bumpTelemetry(engine, TELEMETRY_ATTACH_NEBULA)
    }

    /** dev 自动化烟测证据计数（对齐贯星 VFX 遥测先例）：engine.customData 整数自增。 */
    private fun bumpTelemetry(engine: CombatEngineAPI, key: String) {
        engine.customData[key] = (engine.customData[key] as? Int ?: 0) + 1
    }

    // ---- dev 自动化烟测遥测键（engine.customData）----
    const val TELEMETRY_SPLIT_VFX = "astd_ice_shard_mirv_split_vfx"
    const val TELEMETRY_ATTACH_NEBULA = "astd_ice_shard_attach_nebula"

    /** 读整数遥测计数（无记录为 0）。 */
    fun telemetryCount(engine: CombatEngineAPI, key: String): Int = engine.customData[key] as? Int ?: 0

    /** 分裂闪光参数。 */
    private const val SPLIT_FLASH_SIZE = 120f
    private const val SPLIT_FLASH_DURATION = 0.25f

    /** 分裂星云参数。 */
    private const val SPLIT_NEBULA_COUNT = 6
    private const val SPLIT_NEBULA_SIZE = 40f
    private const val SPLIT_NEBULA_SPEED_MIN = 30f
    private const val SPLIT_NEBULA_SPEED_MAX = 90f
    private const val SPLIT_NEBULA_DURATION = 1.5f

    /** 附着星云参数：3 个 / 50px ±25% / 存续 2s。 */
    private const val ATTACH_NEBULA_COUNT = 3
    private const val ATTACH_NEBULA_SIZE = 50f
    private const val ATTACH_NEBULA_SPEED_MAX = 20f
    private const val ATTACH_NEBULA_DURATION = 2.0f
}
