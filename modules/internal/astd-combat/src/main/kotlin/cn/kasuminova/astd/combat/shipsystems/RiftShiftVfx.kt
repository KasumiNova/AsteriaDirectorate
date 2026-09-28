package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.api.AstdLog
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import org.boxutil.units.standard.entity.FlareEntity
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.sqrt

/**
 * 裂隙折跃的紫色虚空星云裂隙特效（规格 blue/10-unique.md XC-002 节特效段，
 * 参考 tools/game-vfx-preview#Void Cutter Beam 与 docs/design/ships/blue/fissure.png）。
 *
 * - 裂隙本体（[riftBodyFrame]/[closeRiftBody]，映射 Void Cutter Beam 预设的「黑色空洞核心 +
 *   两侧紫白高温撕裂边缘」到 BoxUtil 实体）：normal 混合的近黑紫虚空暗核压暗背景
 *   （BELOW_SHIPS 层，不遮舰船）+ additive 的 SHARP_DISC 热缘亮线（逐帧横向抖动/宽度抖动
 *   表达边缘撕裂）+ SMOOTH_DISC 外鞘辉光；
 * - 成形/闭合观感：调用方传入的存续段即动画——成形期随折跃舰位拉长，闭合期自末端反向
 *   收拢回起点（「拉上」观感，不再有爆炸表现），收拢完毕由 [closeRiftBody] 立即回收；
 * - [riftFrame]：每帧沿存续段释放同色星云并向两侧扩散，偶发垂直裂隙的电弧。
 *
 * 纯视觉，不含伤害结算（结算在 RiftShiftSystemStats）。
 */
object RiftShiftVfx {

    /** 裂隙配色（虚空紫）。 */
    val RIFT_CORE = Color(160, 150, 255)
    val RIFT_FRINGE = Color(100, 80, 255)

    /** 星云释放密度：基础 24 片/秒 + 每 20su 裂隙长度追加 1 片/秒（800su 全程 ≈ 64 片/秒）。 */
    private const val NEBULA_BASE_PER_SECOND = 24f
    private const val NEBULA_PER_LENGTH_PER_SECOND = 1f / 20f

    /** 星云横向散布半宽（su，视觉裁定值）：裂隙带星云片横向抖散幅度。 */
    private const val NEBULA_LATERAL_SPREAD = 24f

    /** 裂隙电弧每帧出现概率（≈5 次/秒 @60fps）。 */
    private const val RIFT_ARC_CHANCE_PER_FRAME = 0.08f

    // —— 裂隙本体（尺寸/观感裁定值，锚 fissure.png 与 Void Cutter Beam 预设）——

    /** 本体淡入时长（秒）：撕开瞬间不是满亮弹出。 */
    private const val BODY_FADE_IN_SECONDS = 0.2f

    /** 虚空暗核：比成形段两端各多出的覆盖长 / 带高（su）/ 峰值覆盖 alpha。 */
    private const val VOID_CORE_EXTRA_LENGTH = 60f
    private const val VOID_CORE_HEIGHT = 96f
    private const val VOID_CORE_ALPHA = 0.85f

    /** 热缘亮线：宽度抖动区间（su）/ 垂直裂隙向的位置抖动幅度（su，撕裂感）。 */
    private const val EDGE_HOT_HEIGHT_MIN = 8f
    private const val EDGE_HOT_HEIGHT_MAX = 14f
    private const val EDGE_HOT_JITTER = 5f

    /** 外鞘辉光：带高（su）/ 峰值 alpha。 */
    private const val EDGE_GLOW_HEIGHT = 44f
    private const val EDGE_GLOW_ALPHA = 0.55f

    /** 虚空暗核配色（近黑紫；normal 混合下压暗背景形成「空洞」）。 */
    private val VOID_COLOR = Color(8, 4, 18, 255)

    /** 外鞘辉光边缘色（比 RIFT_FRINGE 更暗一挡的紫）。 */
    private val EDGE_GLOW_FRINGE = Color(120, 60, 220)

    /** 一条未闭合裂隙的本体实体组：虚空暗核 + 热缘亮线 + 外鞘辉光。 */
    private class RiftBody(
        val voidCore: FlareEntity,
        val edgeHot: FlareEntity,
        val edgeGlow: FlareEntity,
    )

    /** engine key →（裂隙 key → 本体实体组）。 */
    private const val KEY_BODIES = "astd_rift_shift_bodies"

    /** engine key → 本体实体创建已失败（Boolean）；addEntity 失败是系统性故障，不再逐帧重试刷屏。 */
    private const val KEY_BODIES_DISABLED = "astd_rift_shift_bodies_disabled"

    private val log = AstdLog.logger

    /**
     * 裂隙本体逐帧推进：成形段 from→to 上同步三枚本体实体（位置/朝向/长度/撕裂抖动）。
     * [riftKey] 为调用方持有的裂隙稳定标识（engine.customData 键）；
     * [intensity] 为本体淡入包络（0..1，由调用方按裂隙年龄折算）。
     * 实体创建/注册失败记 WARN 并让该层缺席，其余层照常。
     */
    fun riftBodyFrame(engine: CombatEngineAPI, riftKey: String, from: Vector2f, to: Vector2f, intensity: Float) {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val len = sqrt(dx * dx + dy * dy)
        if (len < 1f) return
        val angle = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
        val facing = BoxUtilCombatVfx.normalizeFacingDeg(angle)
        val mid = Vector2f((from.x + to.x) * 0.5f, (from.y + to.y) * 0.5f)
        val ux = dx / len
        val uy = dy / len

        val bodies = engine.customData.getOrPut(KEY_BODIES) { HashMap<String, RiftBody>() } as MutableMap<String, RiftBody>
        var body = bodies[riftKey]
        if (body == null) {
            if (engine.customData[KEY_BODIES_DISABLED] == true) return
            body = createRiftBody(engine)
            if (body == null) {
                engine.customData[KEY_BODIES_DISABLED] = true
                return
            }
            bodies[riftKey] = body
        }

        // 虚空暗核：normal 混合压暗背景（含两端余量），不喂 bloom
        body.voidCore.setStateVanilla(mid, facing)
        body.voidCore.setSize(len + VOID_CORE_EXTRA_LENGTH, VOID_CORE_HEIGHT)
        body.voidCore.globalAlpha = VOID_CORE_ALPHA * intensity

        // 热缘亮线：宽度与横向位置逐帧抖动（边缘撕裂感），additive + 少量 bloom
        val jitter = MathUtils.getRandomNumberInRange(-EDGE_HOT_JITTER, EDGE_HOT_JITTER)
        val hotPos = Vector2f(mid.x - uy * jitter, mid.y + ux * jitter)
        body.edgeHot.setStateVanilla(hotPos, facing)
        body.edgeHot.setSize(len, MathUtils.getRandomNumberInRange(EDGE_HOT_HEIGHT_MIN, EDGE_HOT_HEIGHT_MAX))
        body.edgeHot.autoAspect()
        body.edgeHot.globalAlpha = MathUtils.getRandomNumberInRange(0.7f, 1f) * intensity

        // 外鞘辉光：宽大低亮的紫色鞘层
        body.edgeGlow.setStateVanilla(mid, facing)
        body.edgeGlow.setSize(len, EDGE_GLOW_HEIGHT)
        body.edgeGlow.autoAspect()
        body.edgeGlow.globalAlpha = EDGE_GLOW_ALPHA * intensity
    }

    /** 裂隙闭合（或宿主舰离场）：立即回收本体实体（收拢末帧段长 <1su 时本体已不可见）。 */
    fun closeRiftBody(engine: CombatEngineAPI, riftKey: String) {
        val bodies = engine.customData[KEY_BODIES] as? MutableMap<String, RiftBody> ?: return
        val body = bodies.remove(riftKey) ?: return
        body.voidCore.delete()
        body.edgeHot.delete()
        body.edgeGlow.delete()
        if (bodies.isEmpty()) engine.customData.remove(KEY_BODIES)
    }

    /** 本体淡入包络（纯函数）：裂隙撕开 [BODY_FADE_IN_SECONDS] 内线性升起。 */
    fun bodyIntensity(riftAgeSeconds: Float): Float =
        (riftAgeSeconds / BODY_FADE_IN_SECONDS).coerceIn(0f, 1f)

    private fun createRiftBody(engine: CombatEngineAPI): RiftBody? {
        BoxUtilCombatVfx.ensureReady(engine)
        val voidCore = createBodyFlare("暗核") ?: return null
        val edgeHot = createBodyFlare("热缘")
        if (edgeHot == null) {
            voidCore.delete()
            return null
        }
        val edgeGlow = createBodyFlare("外鞘")
        if (edgeGlow == null) {
            voidCore.delete()
            edgeHot.delete()
            return null
        }

        // 虚空暗核：SMOOTH + normal 混合 + 近黑紫（压暗背景的空洞带）
        voidCore.setLayer(CombatEngineLayers.BELOW_SHIPS_LAYER)
        voidCore.setNormalBlend()
        voidCore.setSmooth()
        voidCore.setCoreColor(VOID_COLOR)
        voidCore.setFringeColor(VOID_COLOR)
        voidCore.glowPower = 0f
        voidCore.noisePower = 0.15f

        // 热缘亮线：SHARP_DISC 细长光柱 + additive + 噪声撕裂
        edgeHot.setLayer(CombatEngineLayers.ABOVE_SHIPS_LAYER)
        edgeHot.setAdditiveBlend()
        edgeHot.setSharpDisc()
        edgeHot.setCoreColor(RIFT_CORE)
        edgeHot.setFringeColor(RIFT_FRINGE)
        edgeHot.glowPower = 0.6f
        edgeHot.noisePower = 0.35f

        // 外鞘辉光：SMOOTH_DISC 宽鞘 + additive
        edgeGlow.setLayer(CombatEngineLayers.ABOVE_SHIPS_LAYER)
        edgeGlow.setAdditiveBlend()
        edgeGlow.setSmoothDisc()
        edgeGlow.setCoreColor(RIFT_FRINGE)
        edgeGlow.setFringeColor(EDGE_GLOW_FRINGE)
        edgeGlow.glowPower = 0.3f
        edgeGlow.noisePower = 0.25f

        // 注册必须在图层/样式配置之后：addEntity 按 getLayer() 路由，未设图层返回 1（实机判例）
        if (!registerBodyFlare(engine, voidCore, "暗核")) {
            edgeHot.delete()
            edgeGlow.delete()
            return null
        }
        if (!registerBodyFlare(engine, edgeHot, "热缘")) {
            voidCore.delete()
            edgeGlow.delete()
            return null
        }
        if (!registerBodyFlare(engine, edgeGlow, "外鞘")) {
            voidCore.delete()
            edgeHot.delete()
            return null
        }
        return RiftBody(voidCore, edgeHot, edgeGlow)
    }

    /** 本体光斑公共骨架：常驻计时器钉超长 full（生命周期由裂隙状态显式驱动 delete）。 */
    private fun createBodyFlare(tag: String): FlareEntity? {
        val entity = try {
            FlareEntity()
        } catch (t: Throwable) {
            log.warn("[ASTD] 裂隙折跃本体$tag 建实体失败（${t.javaClass.simpleName}），本次裂隙该层缺席", t)
            return null
        }
        entity.isFlick = false
        entity.setGlobalTimer(0f, 1e7f, 0f)
        entity.setSize(1f, 1f)
        entity.globalAlpha = 0f
        return entity
    }

    private fun registerBodyFlare(engine: CombatEngineAPI, entity: FlareEntity, tag: String): Boolean {
        val state = BoxUtilCombatVfx.addEntity(engine, entity)
        if (state != 0) {
            log.warn("[ASTD] 裂隙折跃本体$tag 注册失败（addEntity 返回 $state），本次裂隙该层缺席")
            entity.delete()
            return false
        }
        return true
    }

    /**
     * 裂隙逐帧喷放：沿 from→to（存续段）均匀取采样点，横向抖动后生成星云片，
     * 速度恒垂直裂隙向两侧扩散（「裂隙周围不断释放并扩散同色星云」）。
     */
    fun riftFrame(engine: CombatEngineAPI, from: Vector2f, to: Vector2f, amount: Float) {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val len = sqrt(dx * dx + dy * dy)
        if (len < 1f) return
        val ux = dx / len
        val uy = dy / len

        // 期望片数按帧时长折算，零头概率化取整
        val expected = (NEBULA_BASE_PER_SECOND + len * NEBULA_PER_LENGTH_PER_SECOND) * amount
        var count = expected.toInt()
        if (MathUtils.getRandomNumberInRange(0f, 1f) < expected - count) count++
        // BoxUtil 星云控制器闲置缺陷旁路（详见 resetNebulaControllerIfIdle 文档）：
        // 每个喷发批次（帧）调一次、在喷池之前；严禁逐颗粒调。
        if (count > 0) BoxUtilCombatVfx.resetNebulaControllerIfIdle(engine)
        repeat(count) {
            val t = MathUtils.getRandomNumberInRange(0f, 1f)
            val lateral = MathUtils.getRandomNumberInRange(-1f, 1f) * NEBULA_LATERAL_SPREAD
            val pos = Vector2f(from.x + dx * t - uy * lateral, from.y + dy * t + ux * lateral)
            val side = if (MathUtils.getRandomNumberInRange(0f, 1f) < 0.5f) 1f else -1f
            val speed = MathUtils.getRandomNumberInRange(12f, 36f)
            val vel = Vector2f(-uy * speed * side, ux * speed * side)
            val brighten = MathUtils.getRandomNumberInRange(0f, 1f) < 0.25f
            val base = if (brighten) RIFT_CORE else RIFT_FRINGE
            BoxUtilCombatVfx.addNebulaParticle(
                engine, pos, vel,
                MathUtils.getRandomNumberInRange(36f, 80f),
                1.6f, 0.12f, 0.3f,
                MathUtils.getRandomNumberInRange(1.0f, 1.8f),
                Color(base.red, base.green, base.blue, MathUtils.getRandomNumberInRange(100, 200)),
            )
        }

        if (MathUtils.getRandomNumberInRange(0f, 1f) < RIFT_ARC_CHANCE_PER_FRAME) {
            val t = MathUtils.getRandomNumberInRange(0f, 1f)
            val fromP = Vector2f(from.x + dx * t, from.y + dy * t)
            val side = if (MathUtils.getRandomNumberInRange(0f, 1f) < 0.5f) 1f else -1f
            val arcLen = MathUtils.getRandomNumberInRange(30f, 80f)
            val toP = Vector2f(fromP.x - uy * arcLen * side, fromP.y + ux * arcLen * side)
            engine.spawnEmpArcVisual(
                fromP, null, toP, null,
                MathUtils.getRandomNumberInRange(3f, 6f),
                RIFT_FRINGE, RIFT_CORE,
            ).setFadedOutAtStart(true)
        }
    }
}
