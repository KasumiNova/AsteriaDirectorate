package cn.kasuminova.astd.renderer.effect.system

import cn.kasuminova.astd.api.AstdLog
import cn.kasuminova.astd.combat.hullmods.arc.ImaginaryCharge
import cn.kasuminova.astd.combat.hullmods.arc.ImaginaryWingsTuning
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.ViewportAPI
import org.boxutil.units.standard.entity.FlareEntity
import org.lwjgl.opengl.GL11
import org.lwjgl.util.vector.Vector2f
import org.magiclib.util.MagicUI
import java.awt.Color
import java.util.EnumSet
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * 星翼-级虚数光翼渲染：按虚数充能档位（<30% / 30~80% / >80%）驱动呼吸频率、着色与残影密度，
 * 高档位在两侧翼尖维持微型十字星芒；折跃滑行与超翼过载期间整体转入虚数紫红。
 * 玩家舰额外在 HUD 绘制充能条（过载期转为倒计时条）。
 */
object Xc002GhostWingsEffect {

    private const val ENGINE_KEY = "astd_xc_002_ghost_wings"

    const val HULL_ID = "astd_xc_002"
    const val SPRITE_DARK = "graphics/ships/astd_xc_002_ghost_dark.png"
    const val SPRITE_LIGHT = "graphics/ships/astd_xc_002_ghost_light.png"

    private const val WING_WIDTH = 208f
    private const val WING_HEIGHT = 273f

    private const val TIER_MID = 30f
    private const val TIER_HIGH = 80f

    private const val BREATH_PERIOD_LOW = 3.0f
    private const val BREATH_PERIOD_MID = 2.0f
    private const val BREATH_PERIOD_HIGH = 1.2f

    private const val BREATH_ALPHA_MIN = 0.15f

    private const val AFTERIMAGE_INTERVAL_MID = 0.08f
    private const val AFTERIMAGE_INTERVAL_OVERDRIVE = 0.04f
    private const val AFTERIMAGE_ALPHA = 0.12f
    private const val AFTERIMAGE_DURATION = 0.4f
    private const val AFTERIMAGE_DURATION_OVERDRIVE = 0.5f

    private const val WINGTIP_FLARE_SIZE = 30f
    private const val WINGTIP_FLARE_PULSE = 8f

    private val TINT_LOW = Color(170, 200, 255)
    private val TINT_HIGH = Color(160, 130, 255)
    private val TINT_OVERDRIVE = Color(210, 70, 255)

    private val HUD_CHARGE_BLUE = Color(120, 170, 255)
    private val HUD_CHARGE_VIOLET = Color(160, 130, 255)
    private val HUD_OVERDRIVE = Color(210, 70, 255)
    private val HUD_BORDER = Color(40, 30, 70, 200)

    private val log = AstdLog.logger

    private val loadedSprites = mutableSetOf<String>()

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

    fun track(engine: CombatEngineAPI, ship: ShipAPI) {
        val plugin = engine.customData[ENGINE_KEY] as? Plugin ?: return
        plugin.track(ship)
    }

    private class WingtipFlares(
        val left: FlareEntity,
        val right: FlareEntity,
    ) {
        val expired: Boolean get() = left.hasDelete() || right.hasDelete()

        fun dispose() {
            if (!left.hasDelete()) BoxUtilCombatVfx.removeEntity(left)
            if (!right.hasDelete()) BoxUtilCombatVfx.removeEntity(right)
        }
    }

    private class Attachment(
        val ship: ShipAPI,
        var breathPhase: Float = 0f,
        var afterimageTimer: Float = 0f,
        var alpha: Float = 0f,
        var tint: Color = Color.WHITE,
        var wingtips: WingtipFlares? = null,
        var wingtipDisabled: Boolean = false,
        var pulsePhase: Float = 0f,
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
                    att.wingtips?.dispose()
                    it.remove()
                    continue
                }
                updateAttachment(att, amount)
            }
        }

        private fun updateAttachment(att: Attachment, amount: Float) {
            val ship = att.ship
            val pool = ImaginaryCharge.of(ship)
            val chargeFrac = (pool.charge / ImaginaryWingsTuning.CHARGE_MAX).coerceIn(0f, 1f)
            val overdrive = pool.inOverdrive
            val systemActive = ship.system?.state == ShipSystemAPI.SystemState.ACTIVE ||
                ship.system?.state == ShipSystemAPI.SystemState.IN ||
                ship.system?.state == ShipSystemAPI.SystemState.OUT

            val period = when {
                overdrive || chargeFrac * ImaginaryWingsTuning.CHARGE_MAX >= TIER_HIGH -> BREATH_PERIOD_HIGH
                pool.charge >= TIER_MID -> BREATH_PERIOD_MID
                else -> BREATH_PERIOD_LOW
            }
            att.breathPhase = (att.breathPhase + amount / period) % 1f
            // 充能越满呼吸亮度上限越高，低档只剩微弱电容辉光
            val alphaCap = 0.5f + 0.5f * chargeFrac
            att.alpha = (BREATH_ALPHA_MIN + (alphaCap - BREATH_ALPHA_MIN) *
                (0.5f - 0.5f * cos((att.breathPhase * 2f * Math.PI).toFloat())))

            att.tint = when {
                overdrive || systemActive -> TINT_OVERDRIVE
                pool.charge < TIER_MID -> TINT_LOW
                else -> lerpColor(TINT_LOW, TINT_HIGH, (pool.charge - TIER_MID) / (TIER_HIGH - TIER_MID))
            }

            // 残影：低档不产生；中高档 0.08s 节拍；过载期加密到 0.04s 且拖更久
            val afterimageInterval = if (overdrive) AFTERIMAGE_INTERVAL_OVERDRIVE else AFTERIMAGE_INTERVAL_MID
            val afterimageDuration = if (overdrive) AFTERIMAGE_DURATION_OVERDRIVE else AFTERIMAGE_DURATION
            if (pool.charge >= TIER_MID && SPRITE_DARK in loadedSprites) {
                att.afterimageTimer += amount
                if (att.afterimageTimer >= afterimageInterval) {
                    // 单帧最多一张，避免低档积压的计时在跨档后爆发式生成
                    att.afterimageTimer = 0f
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
                            duration = afterimageDuration,
                            growth = 0f,
                        ),
                    )
                }
            } else {
                att.afterimageTimer = 0f
            }

            updateWingtips(att, amount, chargeFrac)
        }

        /** 高充能档位（>80%）在两侧翼尖维持微型十字星芒，随呼吸脉动。 */
        private fun updateWingtips(att: Attachment, amount: Float, chargeFrac: Float) {
            val ship = att.ship
            val high = poolChargeOf(ship) >= TIER_HIGH
            if (!high) {
                att.wingtips?.dispose()
                att.wingtips = null
                return
            }
            var flares = att.wingtips
            if (flares == null || flares.expired) {
                flares?.dispose()
                flares = if (att.wingtipDisabled) null else createWingtips(ship, att)
                att.wingtips = flares ?: return
            }
            att.pulsePhase = (att.pulsePhase + amount / BREATH_PERIOD_HIGH) % 1f
            val pulse = 0.5f - 0.5f * cos((att.pulsePhase * 2f * Math.PI).toFloat())
            val size = WINGTIP_FLARE_SIZE + WINGTIP_FLARE_PULSE * pulse * chargeFrac
            val anchor = ImaginaryWingsTuning.WING_ANCHORS.last()
            val tint = att.tint
            val left = ImaginaryWingsTuning.anchorWorld(ship, anchor, 1)
            val right = ImaginaryWingsTuning.anchorWorld(ship, anchor, -1)
            flares.left.setLocation(left)
            flares.left.setSize(size, size)
            flares.left.setCoreColor(tint)
            flares.right.setLocation(right)
            flares.right.setSize(size, size)
            flares.right.setCoreColor(tint)
        }

        private fun poolChargeOf(ship: ShipAPI): Float = ImaginaryCharge.of(ship).charge

        private fun createWingtips(ship: ShipAPI, att: Attachment): WingtipFlares? {
            BoxUtilCombatVfx.ensureReady(engine)
            val left = createWingtipFlare(ship, 1, att) ?: return null
            val right = createWingtipFlare(ship, -1, att)
            if (right == null) {
                BoxUtilCombatVfx.removeEntity(left)
                return null
            }
            return WingtipFlares(left, right)
        }

        private fun createWingtipFlare(ship: ShipAPI, side: Int, att: Attachment): FlareEntity? {
            val entity = try {
                FlareEntity()
            } catch (t: Throwable) {
                att.wingtipDisabled = true
                log.warn("[ASTD] 虚数之翼翼尖星芒建实体失败（${t.javaClass.simpleName}），本舰星芒缺席", t)
                return null
            }
            entity.setLayer(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER)
            entity.setAdditiveBlend()
            entity.setSharpDisc()
            entity.isFlick = false
            entity.glowPower = 0f
            entity.noisePower = 0.1f
            entity.setCoreColor(TINT_HIGH)
            entity.setFringeColor(TINT_OVERDRIVE)
            entity.setSize(WINGTIP_FLARE_SIZE, WINGTIP_FLARE_SIZE)
            // 常驻计时器钉超长 full，生命周期由档位逻辑显式 dispose
            entity.setGlobalTimer(0f, 1e7f, 0f)
            entity.setStateVanilla(ImaginaryWingsTuning.anchorWorld(ship, ImaginaryWingsTuning.WING_ANCHORS.last(), side), 0f)
            val state = BoxUtilCombatVfx.addEntity(engine, entity)
            if (state != 0) {
                att.wingtipDisabled = true
                log.warn("[ASTD] 虚数之翼翼尖星芒注册失败（addEntity 返回 $state），本舰星芒缺席")
                entity.delete()
                return null
            }
            return entity
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
                    // BoxUtil 实体快照与舰船渲染存在时相差，本体必须在 render 阶段直接采样舰船位置和朝向。
                    renderWing(SPRITE_DARK, ship, att.tint, alpha)
                    renderWing(SPRITE_LIGHT, ship, att.tint, alpha)
                }
            } finally {
                GL11.glPopAttrib()
            }

            // HUD 充能条（MagicUI 内部已过滤非玩家舰与 HUD 隐藏状态）
            for ((_, att) in attachments) {
                val ship = att.ship
                if (ship !== engine.playerShip) continue
                renderHudBar(ship)
            }
        }

        private fun renderHudBar(ship: ShipAPI) {
            val pool = ImaginaryCharge.of(ship)
            // MagicUI 文字路径不重置 MODELVIEW，世界坐标渲染层里必须先将 MODELVIEW 归一，否则文字被相机矩阵甩出屏幕
            GL11.glMatrixMode(GL11.GL_MODELVIEW)
            GL11.glPushMatrix()
            GL11.glLoadIdentity()
            try {
                drawHudBar(ship, pool)
            } finally {
                GL11.glMatrixMode(GL11.GL_MODELVIEW)
                GL11.glPopMatrix()
            }
        }

        private fun drawHudBar(ship: ShipAPI, pool: ImaginaryCharge) {
            if (pool.inOverdrive) {
                val seconds = pool.overdriveRemaining
                val fill = (seconds / ImaginaryWingsTuning.OVERDRIVE_SECONDS).coerceIn(0f, 1f)
                MagicUI.drawHUDStatusBar(
                    ship, fill, HUD_OVERDRIVE, HUD_BORDER, 0f,
                    "${(seconds * 10f).roundToInt() / 10f}s", null, true,
                )
            } else {
                val fill = (pool.charge / ImaginaryWingsTuning.CHARGE_MAX).coerceIn(0f, 1f)
                val color = if (pool.charge < ImaginaryWingsTuning.OVERDRAFT_THRESHOLD) HUD_CHARGE_BLUE else HUD_CHARGE_VIOLET
                MagicUI.drawHUDStatusBar(
                    ship, fill, color, HUD_BORDER, 0f,
                    "${pool.charge.roundToInt()}%", null, true,
                )
            }
        }

        private fun renderWing(spritePath: String, ship: ShipAPI, tint: Color, alpha: Float) {
            if (spritePath !in loadedSprites) return
            val sprite = Global.getSettings().getSprite(spritePath)
            sprite.setAdditiveBlend()
            sprite.color = Color(tint.red, tint.green, tint.blue, 255)
            sprite.alphaMult = alpha
            sprite.setSize(WING_WIDTH, WING_HEIGHT)
            sprite.angle = ship.facing - 90f
            sprite.renderAtCenter(ship.location.x, ship.location.y)
        }
    }

    private fun lerpColor(a: Color, b: Color, t: Float): Color {
        val u = t.coerceIn(0f, 1f)
        fun channel(x: Int, y: Int) = (x + (y - x) * u).roundToInt().coerceIn(0, 255)
        return Color(channel(a.red, b.red), channel(a.green, b.green), channel(a.blue, b.blue))
    }
}
