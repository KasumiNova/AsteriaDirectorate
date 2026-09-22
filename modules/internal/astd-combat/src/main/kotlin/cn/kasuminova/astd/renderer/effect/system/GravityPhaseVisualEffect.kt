package cn.kasuminova.astd.renderer.effect.system

import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.input.InputEventAPI
import org.boxutil.units.standard.entity.SpriteEntity
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.cos
import kotlin.math.sin

/**
 * 「引力相位」（astd_gravity_phase）相位激活态视觉：
 * - 描边红色辉光：BoxUtil `SpriteEntity` 以 `<舰体>_phase_outline.png`（alpha 外环掩码，
 *   由 `tools/generate_phase_vfx_assets.py` 生成）为 emissive 层红色发光， hull 轮廓描边 + bloom 外扩；
 * - bloom 层红化：bloom 贴图紫色素材无法靠乘法染色转红，改用预生成的 `<舰体>_bloom_red.png`
 *   色相旋转变体做加法叠加，同时 [ASTDDecorativeLightsEffect] 按相位等级淡出原紫色 bloom 层；
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

    /** 相位等级低于该值视为未激活（辉光归零、不产残影）。 */
    private const val ACTIVE_LEVEL_MIN = 0.05f

    /** 描边辉光 emissive 颜色（纯红，bloom 外扩成红色光晕）。 */
    private val OUTLINE_EMISSIVE = Color(255, 28, 28, 255)

    /** 红色 bloom 叠加的 emissive 颜色（贴图已预转红，白色原样透出）。 */
    private val BLOOM_EMISSIVE = Color(255, 255, 255, 255)

    /** 残影颜色（70％ 不透明度红）。 */
    private val AFTERIMAGE_COLOR = Color(255, 60, 60)

    /** 常驻实体时长（秒）：生命周期由舰船状态显式驱动，不自然到期。 */
    private const val GLOW_FULL_SECONDS = 1e7f

    private val log = Global.getLogger(GravityPhaseVisualEffect::class.java)

    /** 引力相位系统 id（相位斗篷 spec id 过滤用）。 */
    const val SYSTEM_ID = "astd_gravity_phase"

    /** preloadTextures 加载成功的贴图路径集合；实体创建只使用此集合内的路径。 */
    private val preloadedPaths = HashSet<String>()

    /**
     * 预加载所有引力相位舰船的描边/红化 bloom 贴图（onApplicationLoad 调用）。
     *
     * SSOptimizer 延迟加载下战斗中 loadTexture 既可能损坏其上传队列（实机 2026-09-23
     * glTexImage2D 缓冲尺寸不匹配崩溃），且 SpriteAPI.textureId 捕获时机早于上传完成会恒为 0，
     * 故必须在应用加载阶段完成 GL 上传。
     */
    fun preloadTextures() {
        for (spec in Global.getSettings().allShipHullSpecs) {
            if (!spec.isPhase || spec.shipDefenseId != SYSTEM_ID) continue
            val base = spec.spriteName?.removeSuffix(".png") ?: continue
            for (path in listOf(base + "_phase_outline.png", base + "_bloom_red.png")) {
                try {
                    Global.getSettings().loadTexture(path)
                    preloadedPaths += path
                } catch (t: Throwable) {
                    log.warn("[ASTD] 引力相位视觉：贴图预加载失败 $path（hull=${spec.hullId}）", t)
                }
            }
        }
        log.info("[ASTD] 引力相位视觉贴图预加载完成：${preloadedPaths.size} 张")
    }

    /**
     * 相位等级：引力相位斗篷的 effectLevel；非本系统（或无斗篷）恒 0。
     * [ASTDDecorativeLightsEffect] 淡出原 bloom 层也读这里。
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

    /** 舰船的相位视觉实体组：描边辉光 + 红色 bloom 叠加（任一缺失即不建对应层）。 */
    private class Attachment(
        val ship: ShipAPI,
        val outline: SpriteEntity?,
        val bloom: SpriteEntity?,
        /** 舰体贴图中心补偿（世界偏移由朝向旋转后叠加）：sprite 中心相对几何中心的偏移量。 */
        val centerOffsetX: Float,
        val centerOffsetY: Float,
        var afterimageAcc: Float = 0f,
    )

    private class Plugin(private val engine: CombatEngineAPI) : BaseEveryFrameCombatPlugin() {

        private val attachments = LinkedHashMap<Int, Attachment>()
        private val missingTextureWarned = HashSet<String>()

        fun track(ship: ShipAPI) {
            val key = System.identityHashCode(ship)
            if (attachments.containsKey(key)) return
            val attachment = createAttachment(ship) ?: return
            attachments[key] = attachment
        }

        private fun createAttachment(ship: ShipAPI): Attachment? {
            val sprite = ship.spriteAPI ?: return null
            val spriteName = ship.hullSpec?.spriteName ?: return null
            val base = spriteName.removeSuffix(".png")

            val outline = createGlowEntity(ship, sprite, base + "_phase_outline.png", OUTLINE_EMISSIVE, 1.2f)
            val bloom = createGlowEntity(ship, sprite, base + "_bloom_red.png", BLOOM_EMISSIVE, 1.0f)
            if (outline == null && bloom == null) return null

            log.info(
                "[ASTD] 引力相位视觉 attached ship=${ship.hullSpec?.hullId} " +
                        "outline=${outline != null} bloom=${bloom != null}",
            )
            return Attachment(
                ship = ship,
                outline = outline,
                bloom = bloom,
                centerOffsetX = sprite.width / 2f - sprite.centerX,
                centerOffsetY = sprite.height / 2f - sprite.centerY,
            )
        }

        /** 创建单层发光实体：emissive 贴图缺失时记一次 WARN 并返回 null（不建该层）。 */
        private fun createGlowEntity(
            ship: ShipAPI,
            sprite: com.fs.starfarer.api.graphics.SpriteAPI,
            texturePath: String,
            emissiveColor: Color,
            glowPower: Float,
        ): SpriteEntity? {
            if (texturePath !in preloadedPaths) {
                if (missingTextureWarned.add(texturePath)) {
                    log.warn("[ASTD] 引力相位视觉：贴图未预加载 $texturePath（ship=${ship.hullSpec?.hullId}），跳过该层")
                }
                return null
            }
            val texture = Global.getSettings().getSprite(texturePath)

            val glow = try {
                SpriteEntity(texture, true)
            } catch (t: Throwable) {
                log.warn("[ASTD] 引力相位辉光：SpriteEntity 创建失败（ship=${ship.hullSpec?.hullId}）", t)
                return null
            }
            try {
                glow.setLayer(CombatEngineLayers.ABOVE_SHIPS_LAYER)
                glow.setAdditiveBlend()
                glow.setBaseSizePerTiles(sprite.width / 2f, sprite.height / 2f)
                // diffuse+emissive 同贴图（TriShard/Bolt 已验证路径；emissive-only 实机不渲染）：
                // additive 下 diffuse 提供形状与亮度，emissive 接 bloom 外扩光晕
                glow.setEmissiveSprite(texture)
                glow.materialData.setColor(emissiveColor)
                glow.materialData.setEmissiveColor(emissiveColor)
                glow.materialData.setGlowPower(glowPower)
                glow.setGlobalTimer(0f, GLOW_FULL_SECONDS, 0f)
                glow.materialData.setColorAlpha(0f)
                glow.materialData.setEmissiveColorAlpha(0f)
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
                    att.outline?.delete()
                    att.bloom?.delete()
                    it.remove()
                    continue
                }

                val level = phaseLevelOf(ship)
                updateGlow(att, level)

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
            // 描边是细线需要满透明度；红化 bloom 与原紫色层交叉淡入淡出，0.9 上限避免过曝
            att.outline?.let {
                it.setStateVanilla(loc, facing)
                val alpha = level.coerceIn(0f, 0.2f)
                it.materialData.setColorAlpha(alpha)
                it.materialData.setEmissiveColorAlpha(alpha)
            }
            att.bloom?.let {
                it.setStateVanilla(loc, facing)
                val alpha = (level * 0.9f).coerceIn(0f, 1f)
                it.materialData.setColorAlpha(alpha)
                it.materialData.setEmissiveColorAlpha(alpha)
            }
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
