package cn.kasuminova.astd.renderer.effect.lens

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.ShipAPI
import org.lwjgl.util.vector.Vector2f
import org.magiclib.util.MagicRender
import java.awt.Color
import kotlin.math.cos
import kotlin.math.sin

/**
 * 回声定影残影回放渲染器（Echo-Fixation afterimage，LENS 紫罗兰主题 —— Task 7）。
 *
 * 动机（spec §2.1 / 设计语义「它会替你们再死一次」）：回声定影场结束「回放」瞬间，
 * 为每个被记录敌舰在其「过去坐标」绘制一帧半透明的舰体影像残影——浮现本舰拓印下来的
 * 「最初的过去」，随时间淡出，体现时间信息战的残影语义。
 *
 * 技术范式（与 [cn.kasuminova.astd.combat.shipsystems.ASTDArcFlareOverdriveSystemStats]
 * 的 applyVisualFeedback 一致）：BoxUtil 的 TrailEntity 只提供光束曳光，没有 sprite/船体
 * 影像 overlay；舰体残影的正确范式是 [MagicRender.battlespace]——取舰体 hullSprite，在目标
 * 世界坐标按 facing 旋转、additive 叠加绘制，并配 fadeIn/fadeOut 生命周期。本渲染器照搬
 * ArcFlare 的 sprite 中心偏移几何（修正 spriteAPI.centerX/centerY 与 facing 旋转），确保
 * 残影与「过去时刻的舰体」严格对齐；颜色改用 LENS 紫罗兰（不用 ArcFlare 的橙色）。
 *
 * Fail Fast：sprite 取不到（spriteAPI / hullSprite 为 null）是异常情况而非正常路径，
 * 故 log.warn 跳过该残影，不静默 return；telemetry 计数器供 Task 12 实机验证残影确实渲染。
 */
internal object EchoFixationAfterimageRenderer {

    private val log = Global.getLogger(EchoFixationAfterimageRenderer::class.java)

    /** telemetry 计数器 engine.customData 键（与 ASTDArcProductionVfx 同风格）。外部勿直读此键，见 [afterimageFrames]。 */
    private const val TELEMETRY_KEY = "astd_echo_afterimage:echoFixationAfterimageFrames"

    /**
     * 防御性上限：单次回放内允许渲染的残影帧数上限。
     *
     * 动机：调用方（EchoFixationField）已受其 MAX_TARGETS(24) 约束，回放循环自然封顶；
     * 但本渲染器为 internal object，若被别处调用应自带防刷。与 Field 的 MAX_TARGETS 对齐为 24，
     * 超限 log 丢弃，不静默截断。计数随回放批次（spawn 调用流）累加，跨批次不重置——
     * 单场回放为一次性同步循环，批内累加足以封顶；engine 级 telemetry 单调累加另算。
     */
    private const val MAX_AFTERIMAGES_PER_REPLAY = 24

    /** 残影颜色（紫罗兰主色，色彩指令：LENS 主题紫优先）。alpha 由淡入淡出生命周期控制。 */
    private val AFTERIMAGE_VIOLET = Color(170, 110, 230, 190)

    /** 残影高光 glow（淡紫辅色，参照 ArcFlare 的 glowColor 二次 battlespace 叠加）。 */
    private val AFTERIMAGE_GLOW_LILAC = Color(200, 160, 255, 70)

    /** 残影时长基准（s）。主残影总时长约 1.25s（fadeIn=0.25s / full=0.25s / fadeOut=0.75s）。 */
    private const val AFTERIMAGE_DURATION = 1.0f

    /** 本次回放已渲染残影计数（防御性上限用）；由调用方在每场回放起始调 [resetReplayBudget]。 */
    private var replayBudget: Int = 0

    /** 是否已对本场回放的上限超限打过日志，避免刷屏。 */
    private var budgetCapLogged: Boolean = false

    /**
     * 重置单次回放的残影预算（防御性上限）。调用方在每场回放循环开始前调用一次。
     *
     * 动机：上限 [MAX_AFTERIMAGES_PER_REPLAY] 以「单场回放」为单位生效；object 单例的计数器
     * 需要在每场回放起始归零，否则多场累积会过早触顶。
     */
    fun resetReplayBudget() {
        replayBudget = 0
        budgetCapLogged = false
    }

    /**
     * 为单个被记录敌舰在其「过去坐标」绘制一帧紫罗兰残影。
     *
     * @param engine 战斗引擎（telemetry 计数 + 渲染层）。
     * @param ship 被记录敌舰（仍在场，引用有效）——用其 hullSprite 作残影贴图。
     * @param pastX 过去坐标 X（世界坐标，取该敌舰最早一条快照）。
     * @param pastY 过去坐标 Y（世界坐标）。
     * @param facing 残影朝向（度）——「过去时刻」该敌舰的 facing（由 Field 在首条快照时记录）。
     */
    fun spawn(engine: CombatEngineAPI, ship: ShipAPI, pastX: Float, pastY: Float, facing: Float) {
        if (replayBudget >= MAX_AFTERIMAGES_PER_REPLAY) {
            if (!budgetCapLogged) {
                budgetCapLogged = true
                log.warn(
                    "[ASTD] EchoFixationAfterimage replay budget reached " +
                        "($MAX_AFTERIMAGES_PER_REPLAY), dropping further afterimages this replay"
                )
            }
            return
        }

        // Fail Fast：sprite 取不到属异常情况，log.warn 跳过该残影（不静默 return）。
        val spriteApi = ship.spriteAPI
        if (spriteApi == null) {
            log.warn("[ASTD] EchoFixationAfterimage: spriteAPI null for ${ship.hullSpec?.hullId}, skipping afterimage")
            return
        }
        val hullSprite = Global.getSettings().getSprite(ship.hullSpec.spriteName)
        if (hullSprite == null) {
            log.warn("[ASTD] EchoFixationAfterimage: hullSprite null for ${ship.hullSpec.spriteName}, skipping afterimage")
            return
        }

        val width = spriteApi.width
        val height = spriteApi.height
        // sprite 中心偏移修正（照搬 ArcFlare applyVisualFeedback 的几何）：
        // 修正 spriteAPI.centerX/centerY 与几何中心的差，并按 (facing-90°) 旋转，
        // 确保残影位置与「过去时刻舰体」严格对齐。
        val rawOx = width / 2f - spriteApi.centerX
        val rawOy = height / 2f - spriteApi.centerY
        val rad = Math.toRadians((facing - 90.0))
        val cosA = cos(rad).toFloat()
        val sinA = sin(rad).toFloat()
        val spriteLoc = Vector2f(
            pastX + cosA * rawOx - sinA * rawOy,
            pastY + sinA * rawOx + cosA * rawOy,
        )
        // 两次 battlespace 调用复用 vel/growth(=0) 与 size 向量，省去重复 new。
        val zeroVec = Vector2f(0f, 0f)
        val sizeVec = Vector2f(width, height)

        // 主残影：紫罗兰、additive、总时长约 1.25s（fadeIn 0.25 / full 0.25 / fadeOut 0.75），绘于船下方层。
        MagicRender.battlespace(
            hullSprite,
            spriteLoc,
            zeroVec,
            sizeVec,
            zeroVec,
            facing - 90f,
            0f,
            AFTERIMAGE_VIOLET,
            true,
            0f, 0f, 0f, 0f, 0f,
            AFTERIMAGE_DURATION * 0.25f,
            AFTERIMAGE_DURATION * 0.25f,
            AFTERIMAGE_DURATION * 0.75f,
            CombatEngineLayers.BELOW_SHIPS_LAYER,
        )

        // 淡紫高光 glow：利用舰体贴图 alpha 分布叠一层淡紫发光（参照 ArcFlare glowColor 二次叠加），
        // 短促一闪，绘于船下方层（残影整体在船下方）。
        MagicRender.battlespace(
            hullSprite,
            spriteLoc,
            zeroVec,
            sizeVec,
            zeroVec,
            facing - 90f,
            0f,
            AFTERIMAGE_GLOW_LILAC,
            true,
            0f, 0f, 0f, 0f, 0f,
            AFTERIMAGE_DURATION * 0.15f,
            AFTERIMAGE_DURATION * 0.20f,
            AFTERIMAGE_DURATION * 0.45f,
            CombatEngineLayers.BELOW_SHIPS_LAYER,
        )

        replayBudget++
        incrementTelemetry(engine)
    }

    /** 残影帧计数 +1（engine.customData 单调累加，供 Task 12 读取验证）。 */
    private fun incrementTelemetry(engine: CombatEngineAPI) {
        val current = engine.customData[TELEMETRY_KEY] as? Int ?: 0
        engine.customData[TELEMETRY_KEY] = current + 1
    }

    /**
     * 读取 telemetry 残影帧计数（供 Task 12 验证残影 > 0）。
     *
     * 外部读取残影计数请统一调用此函数——它内部使用正确的私有 customData 键 [TELEMETRY_KEY]；
     * 不要直读 engine.customData，键名是实现细节、可能变更。
     */
    fun afterimageFrames(engine: CombatEngineAPI): Int =
        engine.customData[TELEMETRY_KEY] as? Int ?: 0
}
