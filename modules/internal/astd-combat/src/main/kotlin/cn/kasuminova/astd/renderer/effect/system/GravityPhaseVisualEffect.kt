package cn.kasuminova.astd.renderer.effect.system

import cn.kasuminova.astd.api.AstdLog
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.input.InputEventAPI
import org.boxutil.manager.TextureManager
import org.boxutil.units.standard.entity.SpriteEntity
import org.lwjgl.opengl.GL11
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 「引力相位」（astd_gravity_phase）相位激活态视觉：
 * - 描边红色辉光：运行期从舰体贴图 alpha 做 CPU 距离变换生成外环描边纹理，
 *   由 [SpriteEntity] 红色发光渲染（不用 BoxUtil genLegacySDF：其 legacy
 *   GL_INTENSITY 结果纹理在本 GL 上下文创建即 GL_INVALID_ENUM，详见
 *   [generateOutline] 注释）；
 * - bloom 层红化：复用 [ShipGlowRenderer.setRecolor]，按相位等级把覆盖发光层
 *   交叉淡入到预生成的红色变体；
 * - 相位残影：相位期间每 0.5s 经 [ASTDAfterimageEffect] 生成一次 70％ 不透明度红色残影（平方淡出）。
 *
 * 接入点：[cn.kasuminova.astd.combat.shipsystems.GravityPhaseCloakStats] 每帧 apply 时调用 [track] 登记舰船；
 * 相位等级由本插件逐帧读 `ship.phaseCloak.effectLevel`（数据源唯一，不依赖 stats 调用时序）。
 */
internal object GravityPhaseVisualEffect {

    private const val ENGINE_KEY = "astd_gravity_phase_visuals"

    /** 残影生成间隔（秒）。 */
    private const val AFTERIMAGE_INTERVAL = 0.5f

    /** 残影起始不透明度（70％）。 */
    private const val AFTERIMAGE_ALPHA = 0.7f

    /** 残影淡出时长（秒）。 */
    private const val AFTERIMAGE_DURATION = 0.7f

    /** 相位等级低于该值视为未激活（辉光归零、不产残影、换色层淡出至原色）。 */
    private const val ACTIVE_LEVEL_MIN = 0.05f

    /** 描边辉光颜色（纯红，bloom 外扩成红色光晕）。 */
    private val OUTLINE_COLOR = Color(255, 28, 28, 255)

    /** 残影颜色（70％ 不透明度红）。 */
    private val AFTERIMAGE_COLOR = Color(255, 60, 60)

    /** 常驻实体时长（秒）：生命周期由舰船状态显式驱动，不自然到期。 */
    private const val GLOW_FULL_SECONDS = 1e7f

    /** 描边外扩像素（SDF 边界宽度，同时是描边衰减长度）。 */
    private const val OUTLINE_BORDER = 8

    /** SDF alpha 阈值：低于该 alpha 的像素视为舰体外部（抗锯齿边缘算作内部，描边贴紧轮廓）。 */
    private const val OUTSIDE_THRESHOLD = 0.1f

    private val log = AstdLog.logger

    /** 引力相位系统 id（相位斗篷 spec id 过滤用）。 */
    const val SYSTEM_ID = "astd_gravity_phase"

    /** 舰体 id → 动态生成的描边纹理。 */
    private class OutlineTex(
        val textureId: Int,
        val width: Int,
        val height: Int,
        val potWidth: Int,
        val potHeight: Int,
    )

    private val outlineTextures = HashMap<String, OutlineTex>()

    /**
     * 预生成所有引力相位舰船的描边纹理（onApplicationLoad 调用）。
     * 经 BoxUtil [TextureManager] 真 GL 上传舰体贴图，读回 alpha 做 CPU 距离变换，
     * 再上传为描边纹理。
     */
    fun preloadTextures() {
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
        log.info("[ASTD] 引力相位视觉描边纹理生成完成：${outlineTextures.size} 张")
    }

    /**
     * 单舰描边纹理生成：舰体贴图 alpha → CPU 两遍 chamfer 距离变换 →
     * 「外环二次衰减」的 RGBA 描边纹理（白色 RGB，alpha 承载形状）。
     *
     * 为何不用 BoxUtil genLegacySDF：其内部硬编码 legacy
     * GL_INTENSITY8/GL_INTENSITY 结果纹理，在本 GL 上下文创建即抛
     * GL_INVALID_ENUM(1280)（纹理不完整，后续写入/读回静默得零，
     * 诊断日志证实源 alpha 14380 像素可读而 SDF 读回 min=max=0），
     * 连自提供结果纹理的重载也会在 glTexSubImage2D(GL_INTENSITY) 上撞墙，无药可救。
     *
     * 源贴图走 BoxUtil [TextureManager] 真 GL 上传（vanilla getSprite 的懒加载
     * textureId 在本环境未真实上传，与 bloom 贴图根因同源），glGetTexImage(RGBA)
     * 读回 alpha 后全部 CPU 计算，结果上传 RGBA8，全程不触 legacy GL。
     */
    private fun generateOutline(hullId: String, spriteName: String): OutlineTex? {
        val src = TextureManager.loadTexture(spriteName)
        if (src[0] <= 0) {
            log.warn("[ASTD] 引力相位视觉：舰体贴图 BoxUtil 上传失败（hull=$hullId），跳过描边生成")
            return null
        }
        val srcW = src[4]
        val srcH = src[5]

        val probe = ByteBuffer.allocateDirect(src[2] * src[3] * 4)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, src[0])
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, probe)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)

        // 描边画布 = 源图四边各外扩 OUTLINE_BORDER，源图锚 (BORDER, BORDER)
        val outW = srcW + OUTLINE_BORDER * 2
        val outH = srcH + OUTLINE_BORDER * 2
        val potW = nextPOT(outW)
        val potH = nextPOT(outH)

        val insideThreshold = (OUTSIDE_THRESHOLD * 255f).roundToInt()
        val dist = FloatArray(outW * outH) { i ->
            val x = i % outW - OUTLINE_BORDER
            val y = i / outW - OUTLINE_BORDER
            val inside = x >= 0 && y >= 0 && x < srcW && y < srcH &&
                (probe.get((y * src[2] + x) * 4 + 3).toInt() and 0xFF) > insideThreshold
            if (inside) 0f else Float.MAX_VALUE
        }
        // 两遍 chamfer 距离变换（3x3 邻域，直角 1、对角 √2），外环只需 ≤BORDER 的近似欧氏距离
        val diag = 1.4142135f
        for (y in 0 until outH) {
            for (x in 0 until outW) {
                val i = y * outW + x
                var d = dist[i]
                if (x > 0) d = minOf(d, dist[i - 1] + 1f)
                if (y > 0) {
                    d = minOf(d, dist[i - outW] + 1f)
                    if (x > 0) d = minOf(d, dist[i - outW - 1] + diag)
                    if (x < outW - 1) d = minOf(d, dist[i - outW + 1] + diag)
                }
                dist[i] = d
            }
        }
        for (y in outH - 1 downTo 0) {
            for (x in outW - 1 downTo 0) {
                val i = y * outW + x
                var d = dist[i]
                if (x < outW - 1) d = minOf(d, dist[i + 1] + 1f)
                if (y < outH - 1) {
                    d = minOf(d, dist[i + outW] + 1f)
                    if (x < outW - 1) d = minOf(d, dist[i + outW + 1] + diag)
                    if (x > 0) d = minOf(d, dist[i + outW - 1] + diag)
                }
                dist[i] = d
            }
        }

        // 阈值化：仅 hull 外侧成环，alpha 按 (1-d/BORDER)² 衰减；内侧（dist=0）必须为 0，
        // 否则整舰被填成实心剪影；POT  padding 恒 0
        var litPixels = 0
        val outBuf = ByteBuffer.allocateDirect(potW * potH * 4)
        for (i in 0 until potW * potH) {
            val x = i % potW
            val y = i / potW
            val alpha = if (x < outW && y < outH) {
                val d = dist[y * outW + x]
                if (d > 0f && d <= OUTLINE_BORDER) {
                    val t = 1f - d / OUTLINE_BORDER
                    (t * t * 255f).roundToInt().coerceIn(0, 255)
                } else {
                    0
                }
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
        // 覆盖率为 0 即描边静默缺席（源 alpha 全零），必须显式告警而不是正常出图
        if (litPixels <= 0) {
            log.warn("[ASTD] 引力相位视觉：描边纹理覆盖率为 0（hull=$hullId，源贴图 alpha 读取异常），跳过描边生成")
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
                GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, potW, potH, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, outBuf,
            )
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)
        } catch (t: Throwable) {
            GL11.glDeleteTextures(outlineTex)
            throw t
        }
        log.info("[ASTD] 引力相位视觉：描边纹理已生成 hull=$hullId 尺寸=${outW}x${outH} " +
            "发光像素覆盖率=${"%.2f".format(100f * litPixels / (potW * potH))}％")
        return OutlineTex(outlineTex, outW, outH, potW, potH)
    }

    /** 不小于 n 的最小 2 次幂（POT 画布对齐）。 */
    private fun nextPOT(n: Int): Int {
        var pot = 1
        while (pot < n) pot = pot shl 1
        return pot
    }

    /**
     * 相位等级：引力相位斗篷的 effectLevel；非本系统（或无斗篷）恒 0。
     */
    fun phaseLevelOf(ship: ShipAPI): Float {
        val cloak = ship.phaseCloak ?: return 0f
        if (cloak.specAPI?.id != SYSTEM_ID) return 0f
        return cloak.effectLevel.coerceIn(0f, 1f)
    }

    /** 每帧由 [cn.kasuminova.astd.combat.shipsystems.GravityPhaseCloakStats] 驱动：登记舰船并确保插件安装。 */
    fun track(engine: CombatEngineAPI, ship: ShipAPI) {
        if (ship.isHulk) return
        val plugin = getOrCreate(engine) ?: return
        plugin.track(ship)
    }

    private fun getOrCreate(engine: CombatEngineAPI): Plugin? {
        engine.customData[ENGINE_KEY]?.let { return it as? Plugin }
        return try {
            BoxUtilCombatVfx.ensureReady(engine)
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

    /** 舰船的相位视觉实体组：SDF 描边辉光（缺失即不建）。 */
    private class Attachment(
        val ship: ShipAPI,
        val outline: SpriteEntity,
        /** 舰体贴图中心补偿（世界偏移由朝向旋转后叠加）：sprite 中心相对几何中心的偏移量。 */
        val centerOffsetX: Float,
        val centerOffsetY: Float,
        var afterimageAcc: Float = 0f,
    )

    private class Plugin(private val engine: CombatEngineAPI) : BaseEveryFrameCombatPlugin() {

        private val attachments = LinkedHashMap<Int, Attachment>()

        /** 实体创建失败的舰船（不再重试，避免每帧重建实体与刷屏告警）。 */
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

            val outline = createOutlineEntity(ship, sprite, outlineTex) ?: return null
            return Attachment(
                ship = ship,
                outline = outline,
                centerOffsetX = sprite.width / 2f - sprite.centerX,
                centerOffsetY = sprite.height / 2f - sprite.centerY,
            )
        }

        private fun createOutlineEntity(
            ship: ShipAPI,
            sprite: com.fs.starfarer.api.graphics.SpriteAPI,
            tex: OutlineTex,
        ): SpriteEntity? {
            val glow = try {
                SpriteEntity()
            } catch (t: Throwable) {
                log.warn("[ASTD] 引力相位辉光：SpriteEntity 创建失败（ship=${ship.hullSpec?.hullId}）", t)
                return null
            }
            try {
                glow.setLayer(CombatEngineLayers.ABOVE_SHIPS_LAYER)
                glow.setAdditiveBlend()
                glow.setBaseSizePerTiles(tex.width / 2f, tex.height / 2f)
                glow.setUVStart(0f, 0f)
                glow.setUVEnd(tex.width.toFloat() / tex.potWidth, tex.height.toFloat() / tex.potHeight)
                // diffuse+emissive 同纹理（TriShard/Bolt 已验证路径）：原始 GL 纹理 id 直挂
                glow.materialData.setDiffuse(tex.textureId)
                glow.materialData.setEmissive(tex.textureId)
                glow.materialData.setColor(OUTLINE_COLOR)
                glow.materialData.setEmissiveColor(OUTLINE_COLOR)
                glow.materialData.setGlowPower(1.2f)
                glow.materialData.setColorAlpha(0f)
                glow.materialData.setEmissiveColorAlpha(0f)
                // 常驻：全局计时器缺省值会在首个逻辑帧被判 TIMER_INVALID 直接 delete，
                // 必须显式钉一个超长 full（消亡由舰船状态驱动 delete）
                glow.setGlobalTimer(0f, GLOW_FULL_SECONDS, 0f)
                // 非实例化直绘：QuadObject.glDraw 对无实例数据按 max(count,1) 画单 quad，
                // 位置/朝向/尺寸由实体本体承载（updateGlow 每帧 setStateVanilla）；
                // FIXED_2D 单实例灌数据路径在本环境实测零像素（SSBO 数据未生效），已弃用
            } catch (t: Throwable) {
                log.warn("[ASTD] 引力相位辉光：实体配置失败（ship=${ship.hullSpec?.hullId}）", t)
                glow.delete()
                return null
            }

            val state = BoxUtilCombatVfx.addEntity(engine, glow)
            if (state != 0) {
                log.warn("[ASTD] 引力相位辉光：addEntity 失败（state=$state，ship=${ship.hullSpec?.hullId}）")
                glow.delete()
                return null
            }
            return glow
        }

        override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
            if (attachments.isEmpty()) return

            val it = attachments.entries.iterator()
            while (it.hasNext()) {
                val att = it.next().value
                val ship = att.ship
                if (ship.isHulk || !engine.isEntityInPlay(ship)) {
                    att.outline.delete()
                    it.remove()
                    continue
                }

                val level = phaseLevelOf(ship)
                updateGlow(att, level)
                // bloom 层红化：经 ShipGlowRenderer 交叉淡入红色变体
                ShipGlowRenderer.setRecolor(ship, level)

                if (engine.isPaused) continue
                if (level > ACTIVE_LEVEL_MIN) {
                    att.afterimageAcc += amount
                    if (att.afterimageAcc >= AFTERIMAGE_INTERVAL) {
                        att.afterimageAcc -= AFTERIMAGE_INTERVAL
                        spawnAfterimage(ship)
                    }
                } else {
                    att.afterimageAcc = 0f
                }
            }
        }

        private fun updateGlow(att: Attachment, level: Float) {
            val ship = att.ship
            val facing = BoxUtilCombatVfx.normalizeFacingDeg(ship.facing - 90f)
            // 舰体贴图中心补偿：原版按 sprite.center 对齐舰体坐标，BoxUtil quad 以几何中心为原点
            val theta = Math.toRadians(facing.toDouble())
            val loc = Vector2f(
                ship.location.x + (att.centerOffsetX * cos(theta) - att.centerOffsetY * sin(theta)).toFloat(),
                ship.location.y + (att.centerOffsetX * sin(theta) + att.centerOffsetY * cos(theta)).toFloat(),
            )
            val alpha = level.coerceIn(0f, 1f)
            att.outline.setStateVanilla(loc, facing)
            att.outline.materialData.setColorAlpha(alpha)
            att.outline.materialData.setEmissiveColorAlpha(alpha)
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
