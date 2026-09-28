package cn.kasuminova.astd.renderer.effect.system

import cn.kasuminova.astd.api.AstdLog
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.ViewportAPI
import org.lwjgl.opengl.GL11
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import java.util.EnumSet
import kotlin.math.cos

/**
 * XC-002 淬刃的「虚数之翼」光翼虚影渲染（规格 blue/10-unique.md XC-002 节特殊特效）：
 *
 * - 战斗中常驻渲染 astd_xc_002_ghost_dark.png 与 astd_xc_002_ghost_light.png 两层光翼
 *   （additive；画布 208×273 = 舰体 148×213 四周各扩 30px，
 *   舰体中心恰为几何中心——以舰心居中渲染，无需偏移补偿）；
 * - 呼吸渐变：alpha 在 0.2~1 间正弦呼吸，基础周期 3.0s，舰速越快周期越短（满速 1.0s）；
 * - 残影：每 0.1s 经 [ASTDAfterimageEffect] 留下一帧 ghost_dark 残影（alpha 0.1，持续 0.5s）；
 * - 战术系统（裂隙折跃）激活期间整体染紫。
 *
 * 时相裁定（实机跳变/漂移修复）：光翼本体不走 BoxUtil 常驻 SpriteEntity——BoxUtil 实体状态
 * 由逻辑帧写入、渲染线程经条件同步栅栏消费快照，与原版舰船渲染存在时相差，舰船移动时
 * 光翼会出现一帧级跳变。本体改由分层渲染插件在 render 阶段直接采样 ship.location/ship.facing
 * 当前值绘制（WeaponGlowLayer 同款路径），与舰体渲染天然同相；只有残影用生成时刻的历史快照。
 * 呼吸相位/染色/残影节拍仍在 advance（逻辑帧）推进，这些量无位置时相问题。
 *
 * 接线：[preloadTextures]（onApplicationLoad，战斗中 loadTexture 会损坏 SSOptimizer 上传队列）
 * + [ensureInstalled]（CombatVfxBootstrap）+ [track]（ASTDImaginaryWingsHullMod 每帧登记）。
 */
object Xc002GhostWingsEffect {

    private const val ENGINE_KEY = "astd_xc_002_ghost_wings"

    const val HULL_ID = "astd_xc_002"
    const val SPRITE_DARK = "graphics/ships/astd_xc_002_ghost_dark.png"
    const val SPRITE_LIGHT = "graphics/ships/astd_xc_002_ghost_light.png"

    /** 光翼画布尺寸（像素 = su，舰体画布四周各扩 30px）。 */
    private const val WING_WIDTH = 208f
    private const val WING_HEIGHT = 273f

    /** 呼吸周期（秒）：静止 3.0s，满速收缩到 1.0s。 */
    private const val BREATH_PERIOD_MAX = 3.0f
    private const val BREATH_PERIOD_MIN = 1.0f

    /** 呼吸 alpha 下限（上限恒 1）。 */
    private const val BREATH_ALPHA_MIN = 0.2f

    /** 残影节拍 / 起始 alpha / 持续（秒，规格定值）。 */
    private const val AFTERIMAGE_INTERVAL = 0.1f
    private const val AFTERIMAGE_ALPHA = 0.1f
    private const val AFTERIMAGE_DURATION = 0.5f

    /** 系统激活期染色（虚空紫，与裂隙配色同族）。 */
    private val ACTIVE_TINT = Color(190, 140, 255)

    private val log = AstdLog.logger

    /** 预加载成功的贴图路径集合：render 只画登记在册的路径，缺失贴图不得在渲染循环里触发战斗中懒加载。 */
    private val loadedSprites = mutableSetOf<String>()

    /** 预加载光翼贴图（onApplicationLoad 调用；渲染循环走 vanilla getSprite 共享缓存）。 */
    fun preloadTextures() {
        var loaded = 0
        for (path in listOf(SPRITE_DARK, SPRITE_LIGHT)) {
            try {
                Global.getSettings().loadTexture(path)
                loadedSprites += path
                loaded++
            } catch (t: Throwable) {
                log.warn("[ASTD] 虚数之翼光翼贴图预加载失败 $path", t)
            }
        }
        log.info("[ASTD] 虚数之翼光翼贴图预加载完成：$loaded/2")
    }

    /** 战斗开始由 CombatVfxBootstrap 安装。 */
    fun ensureInstalled(engine: CombatEngineAPI) {
        if (engine.customData[ENGINE_KEY] != null) return
        try {
            val plugin = Plugin(engine)
            engine.addLayeredRenderingPlugin(plugin)
            engine.customData[ENGINE_KEY] = plugin
        } catch (t: Throwable) {
            log.warn("[ASTD] 虚数之翼渲染器安装失败", t)
        }
    }

    /** 每帧由 ASTDImaginaryWingsHullMod 驱动：登记舰船（首次调用创建挂载记录）。 */
    fun track(engine: CombatEngineAPI, ship: ShipAPI) {
        val plugin = engine.customData[ENGINE_KEY] as? Plugin ?: return
        plugin.track(ship)
    }

    /** 一舰的光翼挂载：呼吸相位、当前染色/透明度（逻辑帧推进）与残影节拍。 */
    private class Attachment(
        val ship: ShipAPI,
        var breathPhase: Float = 0f,
        var afterimageTimer: Float = 0f,
        var alpha: Float = 0f,
        var tint: Color = Color.WHITE,
    )

    private class Plugin(private val engine: CombatEngineAPI) : BaseCombatLayeredRenderingPlugin(
        CombatEngineLayers.ABOVE_SHIPS_LAYER,
    ) {

        private val attachments = LinkedHashMap<Int, Attachment>()
        private var firstRenderLogged = false

        fun track(ship: ShipAPI) {
            val key = System.identityHashCode(ship)
            if (attachments.containsKey(key)) return
            if (ship.isHulk) return
            attachments[key] = Attachment(ship)
            log.info("[ASTD] 虚数之翼光翼挂载已创建：ship=${ship.hullSpec?.hullId}")
        }

        override fun getActiveLayers(): EnumSet<CombatEngineLayers> =
            EnumSet.of(CombatEngineLayers.ABOVE_SHIPS_LAYER)

        override fun getRenderRadius(): Float = Float.MAX_VALUE

        override fun advance(amount: Float) {
            if (attachments.isEmpty()) return
            if (engine.isPaused) return

            val it = attachments.entries.iterator()
            while (it.hasNext()) {
                val att = it.next().value
                val ship = att.ship
                if (ship.isHulk || !engine.isEntityInPlay(ship)) {
                    it.remove()
                    continue
                }
                updateAttachment(att, amount)
            }
        }

        /** 逻辑帧推进：呼吸相位、染色与残影节拍（不含任何位置采样）。 */
        private fun updateAttachment(att: Attachment, amount: Float) {
            val ship = att.ship

            // 呼吸推进：舰速占比越高周期越短（3.0s → 1.0s）
            val maxSpeed = ship.mutableStats?.maxSpeed?.modifiedValue ?: 0f
            val speedRatio = if (maxSpeed > 1f) (ship.velocity.length() / maxSpeed).coerceIn(0f, 1f) else 0f
            val period = BREATH_PERIOD_MAX + (BREATH_PERIOD_MIN - BREATH_PERIOD_MAX) * speedRatio
            att.breathPhase = (att.breathPhase + amount / period) % 1f
            att.alpha = BREATH_ALPHA_MIN + (1f - BREATH_ALPHA_MIN) *
                (0.5f - 0.5f * cos((att.breathPhase * 2f * Math.PI).toFloat()))

            val systemActive = ship.system?.state == ShipSystemAPI.SystemState.ACTIVE
            att.tint = if (systemActive) ACTIVE_TINT else Color.WHITE

            // 残影：每 0.1s 一帧 ghost_dark（alpha 0.1，0.5s 平方淡出由残影渲染器承担）；
            // 残影是历史快照语义，位置/朝向取生成时刻值
            att.afterimageTimer += amount
            if (att.afterimageTimer >= AFTERIMAGE_INTERVAL && SPRITE_DARK in loadedSprites) {
                att.afterimageTimer -= AFTERIMAGE_INTERVAL
                ASTDAfterimageEffect.spawn(
                    engine,
                    ASTDAfterimageEffect.Snapshot(
                        spritePath = SPRITE_DARK,
                        location = Vector2f(ship.location),
                        facing = ship.facing,
                        width = WING_WIDTH,
                        height = WING_HEIGHT,
                        color = att.tint,
                        startAlpha = AFTERIMAGE_ALPHA,
                        duration = AFTERIMAGE_DURATION,
                        growth = 0f,
                    ),
                )
            }
        }

        override fun render(layer: CombatEngineLayers, viewport: ViewportAPI) {
            if (layer != CombatEngineLayers.ABOVE_SHIPS_LAYER) return
            if (attachments.isEmpty()) return
            if (!firstRenderLogged) {
                firstRenderLogged = true
                log.info("[ASTD] 虚数之翼光翼首次渲染，attachments=${attachments.size}")
            }

            GL11.glPushAttrib(GL11.GL_ENABLE_BIT or GL11.GL_COLOR_BUFFER_BIT)
            try {
                GL11.glEnable(GL11.GL_BLEND)
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE)
                for ((_, att) in attachments) {
                    val ship = att.ship
                    if (ship.isHulk || !engine.isEntityInPlay(ship)) continue
                    val alpha = att.alpha * viewport.alphaMult
                    if (alpha <= 0.002f) continue
                    // 渲染时相直接采样舰船当前位置/朝向（不做跨帧缓存），与舰体渲染同相
                    renderWing(SPRITE_DARK, ship, att.tint, alpha)
                    renderWing(SPRITE_LIGHT, ship, att.tint, alpha)
                }
            } finally {
                GL11.glPopAttrib()
            }
        }

        private fun renderWing(spritePath: String, ship: ShipAPI, tint: Color, alpha: Float) {
            if (spritePath !in loadedSprites) return
            // SpriteAPI 为全局共享缓存实例，尺寸/混合/颜色状态可能被其他渲染方改写，须逐帧重取并重置
            val sprite = Global.getSettings().getSprite(spritePath)
            sprite.setAdditiveBlend()
            // 不透明度只走 alphaMult 一路：color alpha 再乘一次会变成 alpha²（双重衰减）
            sprite.color = Color(tint.red, tint.green, tint.blue, 255)
            sprite.alphaMult = alpha
            sprite.setSize(WING_WIDTH, WING_HEIGHT)
            sprite.angle = ship.facing - 90f
            sprite.renderAtCenter(ship.location.x, ship.location.y)
        }
    }
}
