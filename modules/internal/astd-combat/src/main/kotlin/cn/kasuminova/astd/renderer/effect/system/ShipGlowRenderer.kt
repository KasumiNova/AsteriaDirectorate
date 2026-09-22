package cn.kasuminova.astd.renderer.effect.system

import cn.kasuminova.astd.api.AstdLog
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.input.InputEventAPI
import org.boxutil.base.api.InstanceDataAPI
import org.boxutil.base.api.InstanceRenderAPI
import org.boxutil.define.BoxEnum
import org.boxutil.define.InstanceType
import org.boxutil.units.standard.attribute.Instance2Data
import org.boxutil.units.standard.entity.SpriteEntity
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.cos
import kotlin.math.sin

/**
 * ASTD 舰船覆盖发光层（bloom / 装饰灯）渲染器：战斗内以 BoxUtil [SpriteEntity]
 * （additive）完全代替原版装饰武器渲染（原版侧由 [ASTDShipGlowEffect] 把装饰武器
 * alpha 压 0），装配界面仍走原版渲染。
 *
 * 动机：原版装饰武器渲染无法做发光外扩（bloom 泛光）与运行期换色；
 * 统一走 BoxUtil 实体后，引力相位等系统可经 [setRecolor] 把发光层实时切换为
 * 预生成的颜色变体（如 `_red.png`），并与原色层交叉淡入淡出。
 *
 * 接线：
 * - [preloadTextures]：onApplicationLoad 扫描全部「DECORATIVE 且贴图在
 *   `graphics/ships/` 下」的武器并预载贴图（战斗中 loadTexture 会损坏 SSOptimizer
 *   上传队列，必须在此完成）；同时为引力相位舰船预载红色变体；
 * - 覆盖层装饰武器的 everyFrameEffect（[ASTDShipGlowEffect]）每帧调用
 *   [onWeaponFrame] 登记舰船并驱动基底颜色；
 * - 换色方（引力相位）每帧调用 [setRecolor]（blend=0 视为清除）。
 *
 * 约束：每舰仅支持一个覆盖层武器（attachments 以舰为 key）；覆盖层贴图是与舰体
 * sprite 同画布的整幅对齐图，实体按舰体中心摆放并做 sprite 中心补偿，不使用武器
 * 槽位偏移（槽位坐标仅服务于装配界面的原版静态渲染）。
 */
internal object ShipGlowRenderer {

    private const val ENGINE_KEY = "astd_ship_glow_renderer"

    /** 常驻实体实例满亮相时长（秒）：生命周期由舰船状态显式驱动，不自然到期。 */
    private const val FULL_SECONDS = 1e7f

    /** 覆盖层贴图路径前缀（ASTD 舰体覆盖层约定放在 ships 目录）。 */
    private const val OVERLAY_SPRITE_PREFIX = "graphics/ships/"

    private val log = AstdLog.logger

    /** 覆盖层武器 id → 贴图路径（preloadTextures 填充）。 */
    private val overlaySpritePaths = HashMap<String, String>()

    /** 预加载成功的贴图路径集合；渲染只使用此集合内的贴图。 */
    private val loadedPaths = HashSet<String>()

    /** 覆盖层武器 id → 红色变体路径（仅引力相位舰船预载）。 */
    private val redVariantPaths = HashMap<String, String>()

    /** 预加载覆盖层贴图（onApplicationLoad 调用）。 */
    fun preloadTextures() {
        for (spec in Global.getSettings().allWeaponSpecs) {
            val id = spec.weaponId ?: continue
            if (spec.type != WeaponAPI.WeaponType.DECORATIVE) continue
            val path = spec.turretSpriteName ?: continue
            if (!path.startsWith(OVERLAY_SPRITE_PREFIX)) continue
            if (load(path)) overlaySpritePaths[id] = path
        }

        // 引力相位舰船的覆盖层红色变体（相位换色由本渲染器承接）
        for (hull in Global.getSettings().allShipHullSpecs) {
            if (!hull.isPhase || hull.shipDefenseId != GravityPhaseVisualEffect.SYSTEM_ID) continue
            val overlayWeaponId = hull.builtInWeapons?.values?.firstOrNull { it in overlaySpritePaths } ?: continue
            val redPath = overlaySpritePaths.getValue(overlayWeaponId).removeSuffix(".png") + "_red.png"
            if (load(redPath)) redVariantPaths[overlayWeaponId] = redPath
        }
        log.info("[ASTD] 舰船覆盖发光层渲染器贴图预加载完成：基础 ${overlaySpritePaths.size} 张，红色变体 ${redVariantPaths.size} 张")
    }

    private fun load(path: String): Boolean {
        return try {
            Global.getSettings().loadTexture(path)
            loadedPaths += path
            true
        } catch (t: Throwable) {
            log.warn("[ASTD] 舰船覆盖发光层渲染器：贴图预加载失败 $path", t)
            false
        }
    }

    /** 是否为覆盖层装饰武器（[ASTDShipGlowEffect] 据此禁用原版渲染并转发本渲染器）。 */
    fun isOverlayWeapon(weaponId: String): Boolean = weaponId in overlaySpritePaths

    /**
     * 引力相位换色入口：blend>0 时把该舰覆盖层切换为红色变体并按 blend 交叉淡入，
     * blend<=0 恢复为原色层。舰船未被登记（无覆盖层）时静默忽略。
     */
    fun setRecolor(ship: ShipAPI, blend: Float) {
        val engine = Global.getCombatEngine() ?: return
        val plugin = engine.customData[ENGINE_KEY] as? Plugin ?: return
        plugin.setRecolor(ship, blend)
    }

    /** 每帧由覆盖层装饰武器的 everyFrameEffect 驱动：登记舰船 + 当帧基底颜色。 */
    fun onWeaponFrame(engine: CombatEngineAPI, weapon: WeaponAPI, baseColor: Color) {
        val plugin = engine.customData[ENGINE_KEY] as? Plugin ?: return
        plugin.track(weapon, baseColor)
    }

    /** 渲染器是否已在该战斗中安装（[ASTDShipGlowEffect] 据此决定是否压制原版渲染）。 */
    fun isReady(engine: CombatEngineAPI): Boolean = engine.customData[ENGINE_KEY] is Plugin

    /** 战斗开始由 CombatVfxBootstrap 安装。 */
    fun ensureInstalled(engine: CombatEngineAPI) {
        if (engine.customData[ENGINE_KEY] != null) return
        try {
            BoxUtilCombatVfx.ensureReady(engine)
            val plugin = Plugin(engine)
            engine.addPlugin(plugin)
            engine.customData[ENGINE_KEY] = plugin
            log.info("[ASTD] ShipGlowRenderer installed")
        } catch (t: Throwable) {
            log.warn("[ASTD] ShipGlowRenderer install failed", t)
        }
    }

    /** 舰船的覆盖层实体组：原色层 + 换色层（换色层按需创建）。 */
    private class Attachment(
        val ship: ShipAPI,
        val weaponId: String,
        val base: SpriteEntity,
        var recolor: SpriteEntity?,
        var recolorBlend: Float = 0f,
        var baseColor: Color = Color.WHITE,
        /** 舰体贴图中心补偿（覆盖层与舰体 sprite 同画布）。 */
        val centerOffsetX: Float,
        val centerOffsetY: Float,
    )

    private class Plugin(private val engine: CombatEngineAPI) : BaseEveryFrameCombatPlugin() {

        private val attachments = LinkedHashMap<Int, Attachment>()
        private val missingRecolorWarned = HashSet<String>()

        /** 实体创建失败的舰船（不再重试，避免每帧重建实体与刷屏告警）。 */
        private val failedShips = HashSet<Int>()

        fun track(weapon: WeaponAPI, baseColor: Color) {
            val ship = weapon.ship ?: return
            val key = System.identityHashCode(ship)
            val existing = attachments[key]
            if (existing != null) {
                existing.baseColor = baseColor
                return
            }
            if (ship.isHulk || key in failedShips) return
            val attachment = try {
                createAttachment(ship, weapon, baseColor)
            } catch (t: Throwable) {
                log.warn("[ASTD] 舰船覆盖发光层渲染器：实体创建异常（ship=${ship.hullSpec?.hullId}），该舰不再重试", t)
                null
            } ?: run {
                failedShips += key
                return
            }
            attachments[key] = attachment
            log.info("[ASTD] 舰船覆盖发光层实体已创建：ship=${ship.hullSpec?.hullId} weapon=${attachment.weaponId} " +
                "size=${ship.spriteAPI?.width}x${ship.spriteAPI?.height} tex=${attachment.base.materialData.diffuse?.textureId}")
        }

        fun setRecolor(ship: ShipAPI, blend: Float) {
            val att = attachments[System.identityHashCode(ship)] ?: return
            att.recolorBlend = blend.coerceIn(0f, 1f)
        }

        private fun createAttachment(
            ship: ShipAPI,
            weapon: WeaponAPI,
            baseColor: Color,
        ): Attachment? {
            val sprite = ship.spriteAPI ?: return null
            val path = overlaySpritePaths[weapon.spec?.weaponId] ?: return null
            val base = createEntity(ship, path) ?: return null
            return Attachment(
                ship = ship,
                weaponId = weapon.spec.weaponId,
                base = base,
                recolor = null,
                baseColor = baseColor,
                centerOffsetX = sprite.width / 2f - sprite.centerX,
                centerOffsetY = sprite.height / 2f - sprite.centerY,
            )
        }

        private fun createEntity(ship: ShipAPI, path: String): SpriteEntity? {
            if (path !in loadedPaths) return null
            val shipSprite = ship.spriteAPI ?: return null
            val entity = try {
                SpriteEntity(path)
            } catch (t: Throwable) {
                log.warn("[ASTD] 舰船覆盖发光层渲染器：实体创建失败 $path（ship=${ship.hullSpec?.hullId}）", t)
                return null
            }
            try {
                entity.setLayer(CombatEngineLayers.ABOVE_SHIPS_LAYER)
                entity.setAdditiveBlend()
                entity.setBaseSizePerTiles(shipSprite.width / 2f, shipSprite.height / 2f)
                entity.materialData.setColor(1f, 1f, 1f, 0f)
                entity.materialData.emissive = entity.materialData.diffuse
                entity.materialData.setEmissiveColor(1f, 1f, 1f, 0f)
                entity.materialData.glowPower = 0.5f

                // SpriteEntity 走实例化渲染：无实例数据时 glDraw 绘制 0 个实例（什么都不画），
                // 必须灌一个 FIXED 单实例（实体本体承载位置/朝向/尺寸，实例锚原点单位缩放）。
                if (!initFixedOneInstance(entity)) {
                    log.warn("[ASTD] 舰船覆盖发光层渲染器：实例初始化失败（ship=${ship.hullSpec?.hullId}），覆盖层视觉缺席")
                    entity.delete()
                    return null
                }

                val state = BoxUtilCombatVfx.addEntity(engine, entity)
                if (state != 0) {
                    log.warn("[ASTD] 舰船覆盖发光层渲染器：addEntity 失败（state=$state，ship=${ship.hullSpec?.hullId}）")
                    entity.delete()
                    return null
                }
            } catch (t: Throwable) {
                log.warn("[ASTD] 舰船覆盖发光层渲染器：实体配置失败 $path（ship=${ship.hullSpec?.hullId}）", t)
                entity.delete()
                return null
            }
            return entity
        }

        /**
         * 灌入一个常驻 FIXED_2D 实例（镜像 Xc001EmissiveOverlayEffect 的已验证路径）：
         * 实例位置/朝向归零、单位缩放（由实体本体的 setStateVanilla/setBaseSizePerTiles 承载），
         * 满亮相 timer 由 setInstanceTimerOverride 钉住，任何一步失败返回 false（覆盖层缺席，禁兜底）。
         */
        private fun initFixedOneInstance(entity: InstanceRenderAPI): Boolean {
            val inst = Instance2Data().apply {
                setLocation(0f, 0f)
                setFacing(0f)
                setTurnRate(0f)
                setScale(1f, 1f)
                setTimer(0f, FULL_SECONDS, 0f)
                setColor(255, 255, 255, 255)
                setEmissiveColor(255, 255, 255, 255)
                setFixedInstanceAlpha(1f, BoxEnum.TIMER_FULL)
            }

            val dataList: MutableList<InstanceDataAPI> = mutableListOf(inst)
            if (entity.setInstanceData(dataList, 0f, FULL_SECONDS, 0f) != BoxEnum.STATE_SUCCESS) return false

            entity.renderingCount = 1
            entity.instanceDataRefreshIndex = 0
            entity.instanceDataRefreshSize = 1
            entity.setInstanceTimerOverride(1f, BoxEnum.TIMER_FULL)

            val memory = entity.instanceDataMemory
            if (memory == null || !memory.is_type_fixed) {
                entity.mallocInstance(InstanceType.FIXED_2D, 1)
                entity.instanceDataRefreshOffset = 0
                entity.setInstanceDataRefreshAllFromCurrentIndex()
            }
            val after = entity.instanceDataMemory
            if (after == null || !after.is_type_fixed) return false

            entity.submitInstance()
            return entity.haveValidInstanceData() && entity.validInstanceDataCount >= 1
        }

        override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
            if (attachments.isEmpty()) return

            val it = attachments.entries.iterator()
            while (it.hasNext()) {
                val att = it.next().value
                val ship = att.ship
                if (ship.isHulk || !engine.isEntityInPlay(ship)) {
                    att.base.delete()
                    att.recolor?.delete()
                    it.remove()
                    continue
                }
                updateAttachment(att)
            }
        }

        private fun updateAttachment(att: Attachment) {
            val ship = att.ship
            val facing = BoxUtilCombatVfx.normalizeFacingDeg(ship.facing - 90f)
            val theta = Math.toRadians(facing.toDouble())
            val loc = Vector2f(
                ship.location.x + (att.centerOffsetX * cos(theta) - att.centerOffsetY * sin(theta)).toFloat(),
                ship.location.y + (att.centerOffsetX * sin(theta) + att.centerOffsetY * cos(theta)).toFloat(),
            )

            val blend = att.recolorBlend
            if (blend > 0f && att.recolor == null) {
                val redPath = redVariantPaths[att.weaponId]
                if (redPath != null) {
                    att.recolor = createEntity(ship, redPath)
                    if (att.recolor != null) {
                        log.info("[ASTD] 舰船覆盖发光层换色实体已创建：ship=${ship.hullSpec?.hullId} path=$redPath")
                    }
                } else if (missingRecolorWarned.add(att.weaponId)) {
                    log.warn("[ASTD] 舰船覆盖发光层渲染器：${att.weaponId} 无红色变体预载，相位换色缺席")
                }
            }

            val baseAlpha = 1f - blend
            att.base.setStateVanilla(loc, facing)
            att.base.materialData.setColor(att.baseColor)
            att.base.materialData.setColorAlpha(baseAlpha)
            att.base.materialData.setEmissiveColorAlpha(baseAlpha)

            att.recolor?.let {
                it.setStateVanilla(loc, facing)
                it.materialData.setColorAlpha(blend)
                it.materialData.setEmissiveColorAlpha(blend)
            }
        }
    }
}
