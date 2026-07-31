package cn.kasuminova.astd.renderer.effect.lens

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import org.magiclib.util.MagicRender
import java.awt.Color
import kotlin.math.cos
import kotlin.math.sin

/**
 * 回声定影残影渲染器（Echo-Fixation afterimage，LENS 紫罗兰主题 —— Task 7 重做）。
 *
 * 动机（用户反馈「残影太水」）：旧实现仅在回放瞬间为每个敌舰画一次性 ~1.25s 淡出残影，
 * 表现单薄。重做后残影**绑定「认知撕裂」debuff 常驻显示**——推进插件在 debuff 存活期内
 * 每帧调 [renderPersistent] 在「过去坐标」重绘一帧极短生命周期残影（靠每帧刷新形成常驻），
 * 直到 debuff 结束自然消失。并按「当前敌舰位置离过去坐标的接近度（closeness）」驱动两项强化：
 * - jitter：越接近抖动越强（站桩重合者残影剧烈撕裂）。
 * - 红化：越接近颜色越红（从紫罗兰插值到红），呼应「被拽回过去、即将再死一次」的危险语义。
 *
 * 技术范式（与 [cn.kasuminova.astd.combat.shipsystems.ASTDArcFlareOverdriveSystemStats]
 * 的 applyVisualFeedback 一致）：舰体残影的正确范式是 [MagicRender.battlespace]——取舰体
 * hullSprite，在「过去坐标」按「过去 facing」旋转、additive 叠加绘制。本渲染器照搬 ArcFlare 的
 * sprite 中心偏移几何（修正 spriteAPI.centerX/centerY 与 facing 旋转），确保残影与「过去时刻
 * 的舰体」严格对齐。
 *
 * Fail Fast：sprite 取不到（hullSprite 为 null）是异常情况而非正常路径，故 log.warn 跳过该
 * 残影，不静默 return；telemetry 计数器供 Task 12 实机验证残影确实渲染。
 */
internal object EchoFixationAfterimageRenderer {

    private val log = Global.getLogger(EchoFixationAfterimageRenderer::class.java)

    /** telemetry 计数器 engine.customData 键（与 ASTDArcProductionVfx 同风格）。外部勿直读此键，见 [afterimageFrames]。 */
    private const val TELEMETRY_KEY = "astd_echo_afterimage:echoFixationAfterimageFrames"

    /** 残影色（closeness=0，远处）：LENS 主题紫罗兰。 */
    private val AFTERIMAGE_VIOLET = Color(170, 110, 230)

    /** 残影色（closeness=1，重合）：危险红——越接近过去坐标越红。 */
    private val AFTERIMAGE_RED = Color(235, 55, 55)

    /** 残影 alpha 基准（closeness=0）。 */
    private const val ALPHA_FAR = 160

    /** 残影 alpha 满值（closeness=1，越近越亮）。 */
    private const val ALPHA_NEAR = 210

    /**
     * jitter 抖动幅度基准（su）。实际抖动半径 = JITTER_AMP × closeness（越近越剧烈）；
     * closeness=0 时无抖动（残影稳定），closeness=1 时抖动半径达基准值。
     */
    private const val JITTER_AMP = 14f

    /**
     * 每帧重绘的单帧残影生命周期（s）。极短（fadeIn 0 / full 0.05 / fadeOut 0.05 ≈ 0.1s），
     * 靠推进插件每帧新生一帧、旧帧在 ~0.1s 内淡出，稳态约 N（≈0.1s/帧时长）帧叠加形成常驻辉光。
     * Additive 叠加下稳态亮度高于单帧 alpha，但**不随时间无界增长**（旧帧持续淡出退场）。
     */
    private const val FRAME_FULL = 0.05f
    private const val FRAME_FADE_OUT = 0.05f

    /**
     * 为单个被撕裂敌舰在其「过去坐标」绘制一帧紫罗兰/红残影（每帧调用，常驻表现）。
     *
     * @param engine 战斗引擎（telemetry 计数 + 渲染层）。
     * @param pastX 过去坐标 X（世界坐标，取该敌舰最早一条快照）。
     * @param pastY 过去坐标 Y（世界坐标）。
     * @param pastFacing 残影朝向（度）——「过去时刻」该敌舰的 facing（由 Field 在首条快照时记录）。
     * @param spriteName 被撕裂敌舰 hullSpec.spriteName（每帧重绘用，比每帧从 ship 取更稳）。
     * @param distToPast 当前敌舰位置到「过去坐标」的距离（su，由调用方每帧算出）。
     * @param standstillRange 站桩有效范围（已缩放，su）——把 distToPast 归一为 closeness∈[0,1]。
     */
    fun renderPersistent(
        engine: CombatEngineAPI,
        pastX: Float,
        pastY: Float,
        pastFacing: Float,
        spriteName: String,
        distToPast: Float,
        standstillRange: Float,
    ) {
        // closeness：当前位置离过去坐标越近 → 越接近 1（dist=0→1, dist>=range→0）。
        val closeness = (1f - (distToPast / standstillRange.coerceAtLeast(1f)).coerceIn(0f, 1f))

        // Fail Fast：sprite 取不到属异常情况，log.warn 跳过该残影（不静默 return）。
        val hullSprite = Global.getSettings().getSprite(spriteName)
        if (hullSprite == null) {
            log.warn("[ASTD] EchoFixationAfterimage: hullSprite null for $spriteName, skipping afterimage")
            return
        }

        val width = hullSprite.width
        val height = hullSprite.height
        // sprite 中心偏移修正（照搬 ArcFlare applyVisualFeedback 的几何）：
        // 修正 spriteAPI.centerX/centerY 与几何中心的差，并按 (facing-90°) 旋转，
        // 确保残影位置与「过去时刻舰体」严格对齐。
        val rawOx = width / 2f - hullSprite.centerX
        val rawOy = height / 2f - hullSprite.centerY
        val rad = Math.toRadians((pastFacing - 90.0))
        val cosA = cos(rad).toFloat()
        val sinA = sin(rad).toFloat()
        var spriteX = pastX + cosA * rawOx - sinA * rawOy
        var spriteY = pastY + sinA * rawOx + cosA * rawOy

        // jitter：抖动半径随 closeness 增长（越近越剧烈）。closeness=0 半径 0 → 无抖动。
        val jitterRadius = JITTER_AMP * closeness
        if (jitterRadius > 0.01f) {
            val jittered = MathUtils.getRandomPointInCircle(Vector2f(spriteX, spriteY), jitterRadius)
            spriteX = jittered.x
            spriteY = jittered.y
        }
        val spriteLoc = Vector2f(spriteX, spriteY)

        // 红随距离：紫罗兰（远）→ 红（近）线性插值；alpha 也随 closeness 略升（越近越亮）。
        val color = lerpColor(AFTERIMAGE_VIOLET, AFTERIMAGE_RED, closeness)
        val alpha = (ALPHA_FAR + (ALPHA_NEAR - ALPHA_FAR) * closeness).toInt().coerceIn(0, 255)
        val frameColor = Color(color.red, color.green, color.blue, alpha)

        val zeroVec = Vector2f(0f, 0f)
        val sizeVec = Vector2f(width, height)

        // 每帧一帧极短生命周期残影（fadeIn 0 / full 0.05 / fadeOut 0.05），绘于船下方层。
        MagicRender.battlespace(
            hullSprite,
            spriteLoc,
            zeroVec,
            sizeVec,
            zeroVec,
            pastFacing - 90f,
            0f,
            frameColor,
            true,
            0f, 0f, 0f, 0f, 0f,
            0f,
            FRAME_FULL,
            FRAME_FADE_OUT,
            CombatEngineLayers.BELOW_SHIPS_LAYER,
        )

        incrementTelemetry(engine)
    }

    /** 颜色线性插值（rgb 分量），t∈[0,1]。alpha 单独按 closeness 处理，故此处仅插值 rgb。 */
    private fun lerpColor(from: Color, to: Color, t: Float): Color {
        val c = t.coerceIn(0f, 1f)
        val r = (from.red + (to.red - from.red) * c).toInt().coerceIn(0, 255)
        val g = (from.green + (to.green - from.green) * c).toInt().coerceIn(0, 255)
        val b = (from.blue + (to.blue - from.blue) * c).toInt().coerceIn(0, 255)
        return Color(r, g, b)
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
