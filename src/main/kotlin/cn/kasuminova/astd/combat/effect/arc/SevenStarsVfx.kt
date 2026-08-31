package cn.kasuminova.astd.combat.effect.arc

import cn.kasuminova.astd.renderer.effect.explosion.RiftExplosionVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.EmpArcEntityAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.util.Misc
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.ceil

/**
 * “七星”折跃发射器的特效薄层（规格 07 §2.3 / §3.2）：
 * 折跃起止 EMP 电弧 + 路径星云、裂隙爆炸（薄调用 [RiftExplosionVfx] 原语换算半径/位置，
 * 需求定案：爆炸特效换裂隙洪流发射极同款裂隙爆炸、主色蓝、总尺寸 -30%）、
 * 消散小星云、v5 多段终结逐段 EMP 电弧。
 *
 * 动机：机制可视化的唯一编排点——每跳「折跃电弧 + 裂隙征兆 → 延迟裂隙爆炸 + 伤害数字」
 * 同链触发（伤害数字由 applyDamage(showDamageNumbers=true) 原生弹出），满足机制反馈铁律；
 * 纯视觉散布用 [Misc.random]（非结算随机，符合 00-共享基建 §4.1-2）。
 */
object SevenStarsVfx {
    /** 折跃电弧辉光色（ARC 冷蓝白缘色）。 */
    private val ARC_FRINGE = Color(0x6F, 0xB4, 0xFF)

    /** 折跃电弧芯色（ARC 冷蓝白芯色）。 */
    private val ARC_CORE = Color(0xF0, 0xF8, 0xFF)

    /** 路径星云色（低 alpha 冷蓝，防 bloom 提取溢出）。 */
    private val PATH_NEBULA = Color(0x47, 0x8F, 0xEB, 60)

    /** 消散小星云色（比路径更淡：断链/无处可去的「哑火」读感）。 */
    private val DISSIPATE_NEBULA = Color(0x47, 0x8F, 0xEB, 45)

    /** 路径星云间距（su）：沿 from→to 线段取 3~5 点。 */
    private const val PATH_NEBULA_SPACING = 90f

    /** 路径星云口数下限/上限（规格 §3.2：3~5 点）。 */
    private const val PATH_NEBULA_MIN = 3
    private const val PATH_NEBULA_MAX = 5

    /** 消散星云口数。 */
    private const val DISSIPATE_NEBULA_COUNT = 3

    /** 折跃电弧厚度（su）。 */
    private const val ARC_THICKNESS = 4f

    /**
     * 一次折跃的起止视觉（规格 §3.2）：起点与终点 EMP 电弧 + 沿路径 3~5 点小星云 + 少量 flare。
     * [fromEntity]/[toEntity] 为电弧锚定实体（可为 null，退化为世界坐标锚）。
     */
    fun teleport(
        engine: CombatEngineAPI,
        from: Vector2f,
        to: Vector2f,
        fromEntity: ShipAPI?,
        toEntity: ShipAPI?,
    ) {
        val params = EmpArcEntityAPI.EmpArcParams().apply {
            segmentLengthMult = 5f
            zigZagReductionFactor = 0.12f
            fadeOutDist = 72f
            minFadeOutMult = 12f
            flickerRateMult = 0.30f
        }
        // 起止各一道电弧（规格 §3.2「起点与终点产生 EMP 电弧」）：终点一道覆盖折跃到达读感，
        // 距离过近（原地跳/终结贴脸）时单道即可，避免两道重叠过曝。
        engine.spawnEmpArcVisual(from, fromEntity, to, toEntity, ARC_THICKNESS, ARC_FRINGE, ARC_CORE)
        val distance = MathUtils.getDistance(from, to)
        if (distance >= PATH_NEBULA_SPACING * 0.6f) {
            val count = ceil(distance / PATH_NEBULA_SPACING).toInt().coerceIn(PATH_NEBULA_MIN, PATH_NEBULA_MAX)
            for (i in 1 until count) {
                val u = i.toFloat() / count
                val loc = Vector2f(from.x + (to.x - from.x) * u, from.y + (to.y - from.y) * u)
                val size = 10f + Misc.random.nextFloat() * 14f
                engine.addNebulaParticle(loc, ZERO_VEL, size, 1.5f, 0.05f, 0.1f, 0.35f, PATH_NEBULA)
            }
            engine.addSmoothParticle(to, ZERO_VEL, 26f, 1.6f, 0.14f, ARC_CORE)
        }
    }

    /**
     * 裂隙起爆征兆（薄调用层）：换算半径后调 [RiftExplosionVfx.spawnWindup] 并同点播放
     * 原版 windup 音。[scale] 为相对基准半径的倍率（连跳第 N 跳递增 +10%/跳，终结 1.2）。
     */
    fun riftWindup(engine: CombatEngineAPI, at: Vector2f, scale: Float) {
        RiftExplosionVfx.spawnWindup(engine, at, RiftExplosionVfx.DEFAULT_RADIUS * scale)
        Global.getSoundPlayer().playSound(RiftExplosionVfx.SOUND_WINDUP, 1f, 0.9f, at, ZERO_VEL)
    }

    /**
     * 裂隙爆炸（薄调用层，需求定案：裂隙洪流发射极同款、主色蓝、总尺寸 -30%）：
     * 换算半径后调 [RiftExplosionVfx.riftExplosion] 并同点播放原版起爆音；
     * 主色族恒 [RiftExplosionVfx] 默认 BLUE。
     */
    fun riftBlast(engine: CombatEngineAPI, at: Vector2f, scale: Float) {
        RiftExplosionVfx.riftExplosion(engine, at, radius = RiftExplosionVfx.DEFAULT_RADIUS * scale)
        Global.getSoundPlayer().playSound(RiftExplosionVfx.SOUND_EXPLOSION, 1f, 1f, at, ZERO_VEL)
    }

    /** 消散（无处可去）：弹着点小星云淡出，无爆炸（规格 §2.3 表）。 */
    fun dissipate(engine: CombatEngineAPI, at: Vector2f) {
        for (i in 0 until DISSIPATE_NEBULA_COUNT) {
            val angle = Misc.random.nextFloat() * 360f
            val dist = Misc.random.nextFloat() * 18f
            val loc = MathUtils.getPointOnCircumference(at, dist, angle)
            val size = 8f + Misc.random.nextFloat() * 10f
            engine.addNebulaParticle(loc, ZERO_VEL, size, 1.4f, 0.05f, 0.1f, 0.4f, DISSIPATE_NEBULA)
        }
    }

    /**
     * v5 多段终结逐段 EMP 电弧（规格 §2.3：每段电弧连向武器/引擎槽）：
     * 候选 = 目标全部武器槽位置 + 未永久瘫痪引擎位置（[Misc.random] 纯视觉随机选一）；
     * 全无候选（武器引擎全灭）退化连舰心。
     */
    fun terminalSegmentArc(engine: CombatEngineAPI, from: Vector2f, ship: ShipAPI) {
        val candidates = ArrayList<Vector2f>()
        ship.allWeapons?.forEach { candidates += Vector2f(it.location) }
        ship.engineController?.shipEngines
            ?.filter { !it.isPermanentlyDisabled }
            ?.forEach { candidates += Vector2f(it.location) }
        val to = if (candidates.isEmpty()) {
            Vector2f(ship.location)
        } else {
            candidates[Misc.random.nextInt(candidates.size)]
        }
        engine.spawnEmpArcVisual(from, null, to, ship, ARC_THICKNESS, ARC_FRINGE, ARC_CORE)
    }

    /** 静止粒子速度（避免逐次分配）。 */
    private val ZERO_VEL = Vector2f(0f, 0f)
}
