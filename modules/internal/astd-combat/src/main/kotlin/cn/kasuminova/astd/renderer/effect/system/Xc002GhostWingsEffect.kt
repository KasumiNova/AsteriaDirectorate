package cn.kasuminova.astd.renderer.effect.system

import cn.kasuminova.astd.api.AstdLog
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.input.InputEventAPI
import org.boxutil.manager.TextureManager
import org.boxutil.units.standard.entity.SpriteEntity
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.cos

/**
 * XC-002 淬刃的「虚数之翼」光翼虚影渲染（规格 blue/10-unique.md XC-002 节特殊特效）：
 *
 * - 战斗中常驻渲染 astd_xc_002_ghost_dark.png 与 astd_xc_002_ghost_light.png 两层光翼
 *   （BoxUtil [SpriteEntity] additive；画布 208×273 = 舰体 148×213 四周各扩 30px，
 *   舰体中心恰为几何中心——以舰心居中渲染，无需偏移补偿）；
 * - 呼吸渐变：alpha 在 0.2~1 间正弦呼吸，基础周期 3.0s，舰速越快周期越短（满速 1.0s）；
 * - 残影：每 0.1s 经 [ASTDAfterimageEffect] 留下一帧 ghost_dark 残影（alpha 0.1，持续 0.5s）；
 * - 战术系统（裂隙折跃）激活期间整体染紫。
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

    /** 常驻实体满亮相时长（秒）：生命周期由舰船状态显式驱动，不自然到期。 */
    private const val FULL_SECONDS = 1e7f

    private val log = AstdLog.logger

    /** BoxUtil 直载纹理信息（texId + POT 画布 UV 端点）；ShipGlowRenderer 同款。 */
    private class TextureInfo(val texId: Int, val uEnd: Float, val vEnd: Float)

    private val textures = HashMap<String, TextureInfo>()

    /** 预加载光翼贴图（onApplicationLoad 调用）。 */
    fun preloadTextures() {
        var loaded = 0
        for (path in listOf(SPRITE_DARK, SPRITE_LIGHT)) {
            try {
                // vanilla 缓存仍需要：残影渲染走 getSprite
                Global.getSettings().loadTexture(path)
                val tex = TextureManager.loadTexture(path)
                if (tex[0] <= 0) {
                    log.warn("[ASTD] 虚数之翼光翼贴图上传失败 $path")
                    continue
                }
                textures[path] = TextureInfo(tex[0], tex[4].toFloat() / tex[2], tex[5].toFloat() / tex[3])
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
            BoxUtilCombatVfx.ensureReady(engine)
            val plugin = Plugin(engine)
            engine.addPlugin(plugin)
            engine.customData[ENGINE_KEY] = plugin
        } catch (t: Throwable) {
            log.warn("[ASTD] 虚数之翼渲染器安装失败", t)
        }
    }

    /** 每帧由 ASTDImaginaryWingsHullMod 驱动：登记舰船（首次调用创建光翼实体组）。 */
    fun track(engine: CombatEngineAPI, ship: ShipAPI) {
        val plugin = engine.customData[ENGINE_KEY] as? Plugin ?: return
        plugin.track(ship)
    }

    /** 一舰的光翼实体组：暗层 + 亮层（叠放同位），呼吸相位与残影节拍随舰。 */
    private class Attachment(
        val ship: ShipAPI,
        val dark: SpriteEntity,
        val light: SpriteEntity,
        var breathPhase: Float = 0f,
        var afterimageTimer: Float = 0f,
    )

    private class Plugin(private val engine: CombatEngineAPI) : BaseEveryFrameCombatPlugin() {

        private val attachments = LinkedHashMap<Int, Attachment>()

        /** 实体创建失败的舰船（不再重试，避免每帧重建实体与刷屏告警）。 */
        private val failedShips = HashSet<Int>()

        fun track(ship: ShipAPI) {
            val key = System.identityHashCode(ship)
            if (attachments.containsKey(key) || key in failedShips) return
            if (ship.isHulk) return
            val attachment = try {
                createAttachment(ship)
            } catch (t: Throwable) {
                log.warn("[ASTD] 虚数之翼光翼实体创建异常（ship=${ship.hullSpec?.hullId}），该舰不再重试", t)
                null
            } ?: run {
                failedShips += key
                return
            }
            attachments[key] = attachment
            log.info("[ASTD] 虚数之翼光翼实体已创建：ship=${ship.hullSpec?.hullId}")
        }

        private fun createAttachment(ship: ShipAPI): Attachment? {
            val dark = createEntity(SPRITE_DARK) ?: return null
            val light = createEntity(SPRITE_LIGHT)
            if (light == null) {
                dark.delete()
                return null
            }
            return Attachment(ship, dark, light)
        }

        private fun createEntity(path: String): SpriteEntity? {
            val tex = textures[path] ?: return null
            val entity = SpriteEntity()
            entity.setLayer(CombatEngineLayers.ABOVE_SHIPS_LAYER)
            entity.setAdditiveBlend()
            entity.setBaseSizePerTiles(WING_WIDTH / 2f, WING_HEIGHT / 2f)
            entity.setUVStart(0f, 0f)
            entity.setUVEnd(tex.uEnd, tex.vEnd)
            entity.materialData.setDiffuse(tex.texId)
            entity.materialData.setEmissive(tex.texId)
            entity.materialData.setColor(1f, 1f, 1f, 0f)
            entity.materialData.setEmissiveColor(0.6f, 0.6f, 0.6f, 0f)
            entity.materialData.glowPower = 0.05f
            entity.materialData.isIgnoreIllumination = true
            // 常驻：全局计时器缺省值会在首个逻辑帧被判 TIMER_INVALID 直接 delete，
            // 必须显式钉一个超长 full（消亡由舰船状态驱动 delete）
            entity.setGlobalTimer(0f, FULL_SECONDS, 0f)
            val state = BoxUtilCombatVfx.addEntity(engine, entity)
            if (state != 0) {
                log.warn("[ASTD] 虚数之翼光翼实体注册失败（addEntity 返回 $state，path=$path）")
                entity.delete()
                return null
            }
            return entity
        }

        override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
            if (attachments.isEmpty()) return
            if (engine.isPaused) return

            val it = attachments.entries.iterator()
            while (it.hasNext()) {
                val att = it.next().value
                val ship = att.ship
                if (ship.isHulk || !engine.isEntityInPlay(ship)) {
                    att.dark.delete()
                    att.light.delete()
                    it.remove()
                    continue
                }
                updateAttachment(att, amount)
            }
        }

        private fun updateAttachment(att: Attachment, amount: Float) {
            val ship = att.ship

            // 呼吸推进：舰速占比越高周期越短（3.0s → 1.0s）
            val maxSpeed = ship.mutableStats?.maxSpeed?.modifiedValue ?: 0f
            val speedRatio = if (maxSpeed > 1f) (ship.velocity.length() / maxSpeed).coerceIn(0f, 1f) else 0f
            val period = BREATH_PERIOD_MAX + (BREATH_PERIOD_MIN - BREATH_PERIOD_MAX) * speedRatio
            att.breathPhase = (att.breathPhase + amount / period) % 1f
            val alpha = BREATH_ALPHA_MIN + (1f - BREATH_ALPHA_MIN) *
                (0.5f - 0.5f * cos((att.breathPhase * 2f * Math.PI).toFloat()))

            val systemActive = ship.system?.state == ShipSystemAPI.SystemState.ACTIVE
            val tint = if (systemActive) ACTIVE_TINT else Color.WHITE

            val facing = BoxUtilCombatVfx.normalizeFacingDeg(ship.facing - 90f)
            val loc = Vector2f(ship.location)
            att.dark.setStateVanilla(loc, facing)
            att.dark.materialData.setColor(tint)
            att.dark.materialData.setColorAlpha(alpha)
            att.dark.materialData.setEmissiveColorAlpha(alpha)
            att.light.setStateVanilla(loc, facing)
            att.light.materialData.setColor(tint)
            att.light.materialData.setColorAlpha(alpha)
            att.light.materialData.setEmissiveColorAlpha(alpha)

            // 残影：每 0.1s 一帧 ghost_dark（alpha 0.1，0.5s 平方淡出由残影渲染器承担）
            att.afterimageTimer += amount
            if (att.afterimageTimer >= AFTERIMAGE_INTERVAL) {
                att.afterimageTimer -= AFTERIMAGE_INTERVAL
                ASTDAfterimageEffect.spawn(
                    engine,
                    ASTDAfterimageEffect.Snapshot(
                        spritePath = SPRITE_DARK,
                        location = Vector2f(ship.location),
                        facing = ship.facing,
                        width = WING_WIDTH,
                        height = WING_HEIGHT,
                        color = tint,
                        startAlpha = AFTERIMAGE_ALPHA,
                        duration = AFTERIMAGE_DURATION,
                        growth = 0f,
                    ),
                )
            }
        }
    }
}
