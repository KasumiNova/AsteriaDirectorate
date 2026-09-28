package cn.kasuminova.astd.combat.effect.arc.starfallecho

import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import cn.kasuminova.astd.impl.render.TriShardComponent
import cn.kasuminova.astd.impl.render.TriShardSpec
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.EmpArcEntityAPI
import com.fs.starfarer.api.input.InputEventAPI
import org.boxutil.units.standard.entity.FlareEntity
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/**
 * 坠星残响的共享配色与爆炸特效（第 5 发命中带谐振层目标时的规模化爆发）。
 *
 * 爆炸构成（规格 10-signature 特效节）：
 * - 爆心十字辉星：两枚 FlareEntity SMOOTH_DISC 光柱 90° 交叉 + 一枚 SMOOTH 圆形光斑同位叠放，
 *   随爆炸规模扩散并逐渐变淡至消失（[CenterFlarePlugin] 逐帧推进，FlareEntity 无内建尺寸关键帧，
 *   摧锋十字辉星同款口径）；
 * - 星云：基础 10 片，每 100% 规模（每层）+5 片；单片大小 = 爆炸直径；
 * - 三角碎片：基础 100 片，每 100% 规模 +100 片且尺寸 +25%（走统一粒子池，事件级大批量）；
 * - 电弧：方向恒爆心向边缘，出现位置 25%~75% 半径处，长度 25%~75% 半径，
 *   `setFadedOutAtStart(true)`（先隐后现的爆发帧感）；数量按规模 4+2/层（裁定值，设计案未给）。
 */
object StarfallEchoVfx {

    private val log = Global.getLogger(StarfallEchoVfx::class.java)

    /** 普通弹配色（蓝白）。 */
    val NORMAL_CORE = Color(240, 248, 255)
    val NORMAL_FRINGE = Color(120, 190, 255)

    /** 第 5 发配色（共振红）。 */
    val FINAL_CORE = Color(255, 70, 70)
    val FINAL_FRINGE = Color(255, 40, 20)

    /** 爆心辉星存续（秒）。 */
    private const val CENTER_FLARE_DURATION = 0.8f

    /** 十字光柱起止长度（爆炸半径倍数）；短轴恒为长轴 ×[CROSS_ASPECT]。 */
    private const val CROSS_LEN_START_RATIO = 1.0f
    private const val CROSS_LEN_END_RATIO = 2.5f
    private const val CROSS_ASPECT = 0.125f

    /** 中心 SMOOTH 光斑起止直径（爆炸半径倍数）。 */
    private const val CORE_SIZE_START_RATIO = 0.6f
    private const val CORE_SIZE_END_RATIO = 1.5f

    /** 爆心辉星配色：核心近白暖色，辉光共振红（与爆炸配色同族）。 */
    private val FLARE_CORE_COLOR = Color(255, 236, 228)
    private val FLARE_FRINGE_COLOR = FINAL_FRINGE

    /**
     * 第 5 发爆炸特效：[stacks] = 消耗的谐振层数（1~4 = 100%~400% 规模），[radius] = 爆炸半径。
     * 纯视觉，不含伤害结算（结算在 StarfallEchoOnHitEffect）。
     */
    fun explosion(engine: CombatEngineAPI, center: Vector2f, stacks: Int, radius: Float) {
        val scale = stacks.coerceAtLeast(1)
        spawnCenterFlares(engine, center, radius)
        spawnNebula(engine, center, scale, radius)
        spawnShards(engine, center, scale, radius)
        spawnArcs(engine, center, scale, radius)
    }

    /**
     * 爆心十字辉星：两枚 SMOOTH_DISC 光柱 90° 交叉 + 一枚 SMOOTH 圆形光斑同位叠放，
     * 尺寸随爆炸半径取值，由 [CenterFlarePlugin] 推进扩散/变淡/消失。
     */
    private fun spawnCenterFlares(engine: CombatEngineAPI, center: Vector2f, radius: Float) {
        BoxUtilCombatVfx.ensureReady(engine)
        val crossStart = radius * CROSS_LEN_START_RATIO
        val crossEnd = radius * CROSS_LEN_END_RATIO
        val coreStart = radius * CORE_SIZE_START_RATIO
        val coreEnd = radius * CORE_SIZE_END_RATIO
        // 任一实体建立失败即整组放弃（爆炸其余构成不受影响）
        val crossA = buildFlare(engine, center, crossStart, CROSS_ASPECT, 0f, smoothDisc = true) ?: return
        val crossB = buildFlare(engine, center, crossStart, CROSS_ASPECT, 90f, smoothDisc = true) ?: run {
            crossA.delete()
            return
        }
        val core = buildFlare(engine, center, coreStart, 1f, 0f, smoothDisc = false) ?: run {
            crossA.delete()
            crossB.delete()
            return
        }
        engine.addPlugin(
            CenterFlarePlugin(
                engine,
                listOf(
                    FlareTrack(crossA, crossStart, crossEnd, CROSS_ASPECT),
                    FlareTrack(crossB, crossStart, crossEnd, CROSS_ASPECT),
                    FlareTrack(core, coreStart, coreEnd, 1f),
                ),
            )
        )
    }

    /**
     * 建一枚钉住生命周期的光斑（全局计时器钉超长 full，扩散/消散由插件接管）。
     * [smoothDisc] = true 取 SMOOTH_DISC 光柱（[aspect] 为短轴/长轴比），false 取 SMOOTH 圆形。
     * 无 GL 环境（单测/无头）构造会抛异常，收住并降级为无辉星。
     */
    private fun buildFlare(
        engine: CombatEngineAPI,
        point: Vector2f,
        size: Float,
        aspect: Float,
        facingDeg: Float,
        smoothDisc: Boolean,
    ): FlareEntity? {
        val entity = try {
            FlareEntity()
        } catch (t: Throwable) {
            log.warn("坠星残响爆心辉星建实体失败（${t.javaClass.simpleName}），本次跳过辉星（爆炸其余构成不受影响）", t)
            return null
        }
        entity.setLayer(CombatEngineLayers.ABOVE_PARTICLES)
        entity.setAdditiveBlend()
        if (smoothDisc) entity.setSmoothDisc() else entity.setSmooth()
        entity.isFlick = false
        entity.isSyncFlick = false
        entity.glowPower = 0.1f
        entity.noisePower = 0.05f
        // 用 Color 重载：BoxUtil 的 setCoreColor(float×4) 有源码 bug（误写 fringe 槽位，BoxFlareComponent 注记）
        entity.setCoreColor(FLARE_CORE_COLOR)
        entity.setFringeColor(FLARE_FRINGE_COLOR)
        entity.setSize(size, size * aspect)
        entity.autoAspect()
        entity.setGlobalTimer(0f, 1e7f, 0f)
        entity.setStateVanilla(Vector2f(point), facingDeg)
        val state = BoxUtilCombatVfx.addEntity(engine, entity)
        if (state != 0) {
            log.warn("坠星残响爆心辉星注册失败（addEntity 返回 $state），本次跳过辉星（爆炸其余构成不受影响）")
            entity.delete()
            return null
        }
        return entity
    }

    /** 单枚辉星的动画轨迹：尺寸从 [sizeStart] 线性扩散到 [sizeEnd]，短轴按 [aspect] 等比。 */
    private class FlareTrack(
        val entity: FlareEntity,
        val sizeStart: Float,
        val sizeEnd: Float,
        val aspect: Float,
    )

    /** 爆心辉星推进插件：存续期内尺寸线性扩散、透明度线性归零，到期删实体自注销。 */
    private class CenterFlarePlugin(
        private val engine: CombatEngineAPI,
        private val tracks: List<FlareTrack>,
    ) : BaseEveryFrameCombatPlugin() {
        private var elapsed = 0f

        override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
            if (engine.isPaused) return
            elapsed += amount
            val t = (elapsed / CENTER_FLARE_DURATION).coerceIn(0f, 1f)
            val alpha = 1f - t
            for (track in tracks) {
                val size = track.sizeStart + (track.sizeEnd - track.sizeStart) * t
                track.entity.setSize(size, size * track.aspect)
                track.entity.globalAlpha = alpha
            }
            if (t >= 1f) {
                tracks.forEach { it.entity.delete() }
                engine.removePlugin(this)
            }
        }
    }

    /** 星云：10 + 5×(规模−1) 片，单片大小 = 爆炸直径；整片大尺码下压透明度、拉长淡出保可读性。 */
    private fun spawnNebula(engine: CombatEngineAPI, center: Vector2f, scale: Int, radius: Float) {
        // BoxUtil 星云控制器闲置缺陷旁路（详见 resetNebulaControllerIfIdle 文档）：
        // 每次爆炸事件调一次、在 repeat 喷池之前；严禁逐颗粒调。
        BoxUtilCombatVfx.resetNebulaControllerIfIdle(engine)
        val count = 5 + 5 * (scale - 1)
        repeat(count) {
            val pos = MathUtils.getRandomPointInCircle(center, radius * 0.4f)
            val dir = MathUtils.getRandomNumberInRange(0f, 360f)
            val speed = MathUtils.getRandomNumberInRange(20f, 60f) * scale
            val vel = MathUtils.getPointOnCircumference(Vector2f(), speed, dir)
            val brighten = MathUtils.getRandomNumberInRange(0f, 1f) < 0.3f
            val base = if (brighten) FINAL_CORE else FINAL_FRINGE
            BoxUtilCombatVfx.addNebulaParticle(
                engine, pos, Vector2f(),
                radius * 1.5f,
                1f, 0.1f, 0.5f,
                MathUtils.getRandomNumberInRange(1.0f, 1.8f),
                Color(base.red, base.green, base.blue, 100),
            )
        }
    }

    /** 三角碎片：100×规模 片，尺寸 ×(1 + 0.25×(规模−1))；自爆心向四周飞散。 */
    private fun spawnShards(engine: CombatEngineAPI, center: Vector2f, scale: Int, radius: Float) {
        val shards = TriShardComponent(
            "astd_starfall_echo_explosion",
            radius * 2f,
            FINAL_CORE,
            FINAL_FRINGE,
            TriShardSpec(batchCount = 2, layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER, timerFadeIn = 0.2f, timerFadeOut = 0.5f)
        )
        val count = 100 * scale
        val sizeScale = 0.75f + 0.05f * (scale - 1)
        repeat(count) {
            val pos = MathUtils.getRandomPointInCircle(center, radius * 0.5f)
            val dir = MathUtils.getRandomNumberInRange(0f, 360f)
            val speed = MathUtils.getRandomNumberInRange(60f, 180f) * (0.75f + 0.25f * scale)
            shards.addShard(0, pos, MathUtils.getPointOnCircumference(Vector2f(), speed, dir), sizeScale)
        }
        shards.activatePendingBatches(engine)
    }

    /** 电弧：方向恒爆心向边缘（径向向外），起点 25%~75% 半径，长度 25%~75% 半径，先隐后现。 */
    private fun spawnArcs(engine: CombatEngineAPI, center: Vector2f, scale: Int, radius: Float) {
        val count = 8 + 4 * (scale - 1)
        repeat(count) {
            val dir = MathUtils.getRandomNumberInRange(0f, 360f)
            val from = MathUtils.getPointOnCircumference(
                center, radius * MathUtils.getRandomNumberInRange(0.1f, 0.2f), dir,
            )
            val to = MathUtils.getPointOnCircumference(
                from, radius * MathUtils.getRandomNumberInRange(0.25f, 0.75f), dir,
            )
            engine.spawnEmpArcVisual(
                from, null, to, null,
                10f + 4f * scale,
                FINAL_FRINGE, FINAL_CORE, EmpArcEntityAPI.EmpArcParams().apply {
                    flickerRateMult = 0.3f * MathUtils.getRandomNumberInRange(0.5f, 1f)
                }
            ).apply {
                setFadedOutAtStart(true)
                setSingleFlickerMode(true)
            }
        }
    }
}
