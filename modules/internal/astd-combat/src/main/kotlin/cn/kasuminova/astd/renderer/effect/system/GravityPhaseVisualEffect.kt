package cn.kasuminova.astd.renderer.effect.system

import cn.kasuminova.astd.api.AstdLog
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import cn.kasuminova.astd.renderer.boxutil.pool.PooledCombatVfx
import cn.kasuminova.astd.renderer.boxutil.pool.SpriteLease
import cn.kasuminova.astd.renderer.boxutil.pool.SpriteLeaseKey
import cn.kasuminova.astd.renderer.boxutil.pool.SpriteLeaseSpec
import cn.kasuminova.astd.renderer.effect.system.GravityPhaseVisualEffect.getOrCreate
import cn.kasuminova.astd.renderer.effect.system.GravityPhaseVisualEffect.track
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.input.InputEventAPI
import org.boxutil.BoxUtilModPlugin
import org.boxutil.manager.ShaderCore
import org.boxutil.manager.TextureManager
import org.boxutil.util.ShaderUtil
import org.lwjgl.opengl.GL11
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

internal object GravityPhaseVisualEffect {

    private const val ENGINE_KEY = "astd_gravity_phase_visuals"

    private const val AFTERIMAGE_INTERVAL = 0.5f

    private const val AFTERIMAGE_ALPHA = 0.7f

    private const val AFTERIMAGE_DURATION = 0.7f

    private const val ACTIVE_LEVEL_MIN = 0.05f

    private val OUTLINE_COLOR = Color(255, 28, 28, 255)

    private val AFTERIMAGE_COLOR = Color(255, 60, 60)

    private const val OUTLINE_BORDER = 16

    private const val OUTSIDE_THRESHOLD = 0.1f

    private val log = AstdLog.logger

    const val SYSTEM_ID = "astd_gravity_phase"

    private class OutlineTex(
        val textureId: Int,
        val width: Int,
        val height: Int,
        val potWidth: Int,
        val potHeight: Int,
    )

    private val outlineTextures = HashMap<String, OutlineTex>()

    @Volatile
    private var texturesInitialized = false

    // BoxUtil 的 SDF compute program 在 onGameLoad 才初始化；onApplicationLoad 阶段禁止调用 initLater，否则全局初始化失败后无法重试。
    fun preloadTextures() {
        if (texturesInitialized) return
        if (!BoxUtilModPlugin.isGlobalInitialized()) {
            log.info("[ASTD] 引力相位视觉：BoxUtil 尚未全局初始化（onApplicationLoad 阶段正常），描边生成推迟到战斗期")
            return
        }
        if (!ShaderCore.isSDFGenValid()) {
            log.warn("[ASTD] 引力相位视觉：BoxUtil SDF compute program 不可用（isSDFGenValid=false），描边纹理生成跳过")
            return
        }
        for (spec in Global.getSettings().allShipHullSpecs) {
            if (!spec.isPhase || spec.shipDefenseId != SYSTEM_ID) continue
            val spriteName = spec.spriteName ?: continue
            try {
                val outline = generateOutline(spec.hullId, spriteName)
                if (outline != null) outlineTextures[spec.hullId] = outline
            } catch (t: Throwable) {
                log.warn("[ASTD] 引力相位视觉：描边纹理生成失败（hull=${spec.hullId}）", t)
            }
        }
        texturesInitialized = true
        log.info("[ASTD] 引力相位视觉描边纹理生成完成：${outlineTextures.size} 张")
    }

    private fun generateOutline(hullId: String, spriteName: String): OutlineTex? {
        val src = TextureManager.loadTexture(spriteName)
        if (src[0] <= 0) {
            log.warn("[ASTD] 引力相位视觉：舰体贴图 BoxUtil 上传失败（hull=$hullId），跳过描边生成")
            return null
        }
        val srcW = src[4]
        val srcH = src[5]

        val sdf = ShaderUtil.genSDF(
            src[0], GL11.GL_ALPHA, 0, 0, srcW, srcH,
            OUTLINE_BORDER, OUTLINE_BORDER, OUTSIDE_THRESHOLD, 8,
            0.01f, 1f / OUTLINE_BORDER, false,
        )
        if (sdf[0] <= 0) {
            log.warn("[ASTD] 引力相位视觉：SDF 生成失败（hull=$hullId），跳过描边生成")
            return null
        }
        val outW = sdf[1]
        val outH = sdf[2]

        try {
            val sdfBuf = ByteBuffer.allocateDirect(outW * outH)
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, sdf[0])
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1)
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, sdfBuf)
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4)
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)

            var litPixels = 0
            val outBuf = ByteBuffer.allocateDirect(outW * outH * 4)
            for (i in 0 until outW * outH) {
                val sdfValue = (sdfBuf.get(i).toInt() and 0xFF) / 255f
                val alpha = if (sdfValue > 0.5f) {
                    val outside = ((sdfValue - 0.5f) * 2f).coerceIn(0f, 1f)
                    ((1f - outside) * (1f - outside) * 255f).roundToInt().coerceIn(0, 255)
                } else {
                    0
                }
                if (alpha > 0) litPixels++
                outBuf.put(255.toByte())
                outBuf.put(255.toByte())
                outBuf.put(255.toByte())
                outBuf.put(alpha.toByte())
            }
            outBuf.flip()
            if (litPixels <= 0) {
                log.warn("[ASTD] 引力相位视觉：描边纹理覆盖率为 0（hull=$hullId，源贴图 alpha 或 SDF 读回异常），跳过描边生成")
                return null
            }

            val outlineTex = GL11.glGenTextures()
            if (outlineTex <= 0) {
                log.warn("[ASTD] 引力相位视觉：描边纹理 glGenTextures 失败（hull=$hullId）")
                return null
            }
            try {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, outlineTex)
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP)
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP)
                GL11.glTexImage2D(
                    GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, outW, outH, 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, outBuf,
                )
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)
            } catch (t: Throwable) {
                GL11.glDeleteTextures(outlineTex)
                throw t
            }
            log.info(
                "[ASTD] 引力相位视觉：描边纹理已生成 hull=$hullId 尺寸=${outW}x${outH} " +
                        "发光像素覆盖率=${"%.2f".format(100f * litPixels / (outW * outH))}％"
            )
            return OutlineTex(outlineTex, outW, outH, outW, outH)
        } finally {
            GL11.glDeleteTextures(sdf[0])
        }
    }

    fun phaseLevelOf(ship: ShipAPI): Float {
        val cloak = ship.phaseCloak ?: return 0f
        if (cloak.specAPI?.id != SYSTEM_ID) return 0f
        return cloak.effectLevel.coerceIn(0f, 1f)
    }

    fun track(engine: CombatEngineAPI, ship: ShipAPI) {
        if (ship.isHulk) return
        val plugin = getOrCreate(engine) ?: return
        plugin.track(ship)
    }

    private fun getOrCreate(engine: CombatEngineAPI): Plugin? {
        engine.customData[ENGINE_KEY]?.let { return it as? Plugin }
        return try {
            BoxUtilCombatVfx.ensureReady(engine)
            preloadTextures()
            val plugin = Plugin(engine)
            engine.addPlugin(plugin)
            engine.customData[ENGINE_KEY] = plugin
            log.info("[ASTD] GravityPhaseVisualEffect installed")
            plugin
        } catch (t: Throwable) {
            log.warn("[ASTD] GravityPhaseVisualEffect install failed", t)
            null
        }
    }

    private class Attachment(
        val ship: ShipAPI,
        val outline: SpriteLease,
        val centerOffsetX: Float,
        val centerOffsetY: Float,
        var afterimageAcc: Float = 0f,
    )

    private class Plugin(private val engine: CombatEngineAPI) : BaseEveryFrameCombatPlugin() {

        private val attachments = LinkedHashMap<Int, Attachment>()

        private val failedShips = HashSet<Int>()

        fun track(ship: ShipAPI) {
            val key = System.identityHashCode(ship)
            if (attachments.containsKey(key) || key in failedShips) return
            val attachment = try {
                createAttachment(ship)
            } catch (t: Throwable) {
                log.warn("[ASTD] 引力相位辉光：实体创建异常（ship=${ship.hullSpec?.hullId}），该舰不再重试", t)
                null
            } ?: run {
                failedShips += key
                log.info("[ASTD] 引力相位辉光：附件未创建（ship=${ship.hullSpec?.hullId}，描边纹理缺失或实体创建失败）")
                return
            }
            attachments[key] = attachment
            log.info("[ASTD] 引力相位辉光实体已创建：ship=${ship.hullSpec?.hullId}")
        }

        private fun createAttachment(ship: ShipAPI): Attachment? {
            val sprite = ship.spriteAPI ?: return null
            val hullId = ship.hullSpec?.hullId ?: return null
            val outlineTex = outlineTextures[hullId] ?: return null

            val outline = createOutlineEntity(ship, outlineTex) ?: return null
            return Attachment(
                ship = ship,
                outline = outline,
                centerOffsetX = sprite.width / 2f - sprite.centerX,
                centerOffsetY = sprite.height / 2f - sprite.centerY,
            )
        }

        private fun createOutlineEntity(
            ship: ShipAPI,
            tex: OutlineTex,
        ): SpriteLease? {
            val hullId = ship.hullSpec?.hullId ?: return null
            // FIXED_2D 单实例路径在当前 BoxUtil/SSOptimizer 环境不产生像素，必须使用实体本体变换的非实例化渲染。
            return PooledCombatVfx.checkoutSprite(
                engine,
                SpriteLeaseKey(
                    layer = CombatEngineLayers.ABOVE_SHIPS_LAYER,
                    textureKey = "outline:$hullId",
                    additive = true,
                    instanced = false,
                    capacity = 8,
                ),
                SpriteLeaseSpec(
                    location = Vector2f(ship.location),
                    facingDeg = BoxUtilCombatVfx.normalizeFacingDeg(ship.facing - 90f),
                    texId = tex.textureId,
                    uvEndX = tex.width.toFloat() / tex.potWidth,
                    uvEndY = tex.height.toFloat() / tex.potHeight,
                    bindEmissive = true,
                    baseSizeHalfWidth = tex.width / 2f,
                    baseSizeHalfHeight = tex.height / 2f,
                    color = Color(OUTLINE_COLOR.red, OUTLINE_COLOR.green, OUTLINE_COLOR.blue, 0),
                    emissiveColor = Color(OUTLINE_COLOR.red, OUTLINE_COLOR.green, OUTLINE_COLOR.blue, 0),
                    glowPower = 0.1f,
                    alphaToEmissive = 1f,
                    isAdditionEmissive = true,
                    isIgnoreIllumination = true,
                ),
            )
        }

        override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
            if (attachments.isEmpty()) return

            val it = attachments.entries.iterator()
            while (it.hasNext()) {
                val att = it.next().value
                val ship = att.ship
                if (ship.isHulk || !engine.isEntityInPlay(ship)) {
                    att.outline.release(0f)
                    it.remove()
                    continue
                }

                val level = phaseLevelOf(ship)
                updateGlow(att, level)
                ShipGlowRenderer.setRecolor(ship, level)

                if (engine.isPaused) continue
                if (level > ACTIVE_LEVEL_MIN) {
                } else {
                    att.afterimageAcc = 0f
                }
            }
        }

        private fun updateGlow(att: Attachment, level: Float) {
            val ship = att.ship
            val facing = BoxUtilCombatVfx.normalizeFacingDeg(ship.facing - 90f)
            val theta = Math.toRadians(facing.toDouble())
            val loc = Vector2f(
                ship.location.x + (att.centerOffsetX * cos(theta) - att.centerOffsetY * sin(theta)).toFloat(),
                ship.location.y + (att.centerOffsetX * sin(theta) + att.centerOffsetY * cos(theta)).toFloat(),
            )
            val alpha = level.coerceIn(0f, 1f) * 0.4f
            att.outline.entity.setStateVanilla(loc, facing)
            att.outline.entity.materialData.colorAlpha = alpha
            att.outline.entity.materialData.emissiveColorAlpha = alpha
        }

        private fun spawnAfterimage(ship: ShipAPI) {
            val sprite = ship.spriteAPI ?: return
            val spritePath = ship.hullSpec?.spriteName ?: return
            ASTDAfterimageEffect.spawn(
                engine,
                ASTDAfterimageEffect.Snapshot(
                    spritePath = spritePath,
                    location = Vector2f(ship.location),
                    facing = ship.facing,
                    width = sprite.width,
                    height = sprite.height,
                    color = AFTERIMAGE_COLOR,
                    startAlpha = AFTERIMAGE_ALPHA,
                    duration = AFTERIMAGE_DURATION,
                    growth = 0f,
                ),
            )
        }
    }
}
