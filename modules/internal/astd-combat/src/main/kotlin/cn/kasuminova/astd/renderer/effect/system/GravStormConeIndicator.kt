package cn.kasuminova.astd.renderer.effect.system

import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.ShipAPI
import org.boxutil.manager.TextureManager
import org.boxutil.units.standard.entity.SpriteEntity
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/**
 * 引力磁暴发生器：充能期间的锥状范围提示圈（BoxUtil [SpriteEntity] 贴图实体）。
 *
 * 素材 contents/graphics/fx/astd_grav_storm_cone.png：1024×1024 加法混合贴图，
 * 锥顶点在图像中心、开口朝 +Y（图像上方），±30° 扇形，外缘零 alpha 边界位于
 * 半径 [SECTOR_RIM_FRACTION] × 512px 处——实体半尺寸按 射程 / [SECTOR_RIM_FRACTION]
 * 放大，使扇形外缘恰好落在游戏内实际射程上（经 systemRangeBonus 折算后的口径由调用方传入）。
 *
 * 朝向对齐：与舰体贴图同约定（图像上方 = 舰船朝向），进入 BoxUtil 变换前
 * facing - 90° 并过 [BoxUtilCombatVfx.normalizeFacingDeg]（对照 GravityPhaseVisualEffect）。
 *
 * 生命周期：充能首帧 [attach]，充能中每帧 [update] 跟随舰位/朝向并按充能进度调 alpha，
 * 充能结束（释放/打断/系统取消）即 [dispose]；低频事件级实体（每次激活 1 个，冷却 24s），
 * 不走实例池（对照 boxutil 实体池化规范的低频例外口径）。
 */
class GravStormConeIndicator private constructor(
    private val ship: ShipAPI,
    private val entity: SpriteEntity,
) {
    /** 上一帧的实体半尺寸（避免每帧重写基尺寸）。 */
    private var lastQuadHalf = -1f

    /** 每帧跟随：位置/朝向/尺寸（射程变化时）/alpha（充能进度）。 */
    fun update(range: Float, alpha: Float) {
        val quadHalf = range / SECTOR_RIM_FRACTION
        if (quadHalf != lastQuadHalf) {
            entity.setBaseSizePerTiles(quadHalf, quadHalf)
            lastQuadHalf = quadHalf
        }
        entity.setStateVanilla(
            Vector2f(ship.location),
            BoxUtilCombatVfx.normalizeFacingDeg(ship.facing - 90f),
        )
        val a = alpha.coerceIn(0f, 1f)
        entity.materialData.colorAlpha = a
        entity.materialData.emissiveColorAlpha = a
    }

    /** 充能结束收口：删除实体（下次充能重新 attach）。 */
    fun dispose() {
        entity.delete()
    }

    companion object {
        private val log = Global.getLogger(GravStormConeIndicator::class.java)

        /** 锥形提示圈贴图（预生成加法混合素材：扇形外缘零 alpha 边界在 440/512 半径处）。 */
        const val TEXTURE_PATH = "graphics/fx/astd_grav_storm_cone.png"

        /** 贴图扇形外缘（alpha 归零处）占图像半尺寸的比例：440/512。 */
        const val SECTOR_RIM_FRACTION = 440f / 512f

        /** 常驻实体时长（秒）：生命周期由系统脚本显式驱动 dispose，不自然到期。 */
        private const val FULL_SECONDS = 1e7f

        /** 提示圈配色（透镜协议紫）。 */
        private val CONE_COLOR = Color(170, 100, 255, 255)

        /** BoxUtil 直载纹理信息（texId + 实际图像在 POT 画布上的 UV 端点）。 */
        private class TextureInfo(val texId: Int, val uEnd: Float, val vEnd: Float)

        /** 预加载成功的贴图纹理（[preloadTextures] 填充）；attach 只使用此预载结果。 */
        private var texture: TextureInfo? = null

        /**
         * 预加载锥形提示圈贴图（onApplicationLoad 调用）：vanilla 侧 loadTexture +
         * BoxUtil TextureManager 真 GL 上传（SSOptimizer 懒加载下 vanilla getSprite 的
         * textureId 未真实上传，直绑采样恒为 0；战斗中 loadTexture 会损坏 SSOptimizer
         * 上传队列，必须在此完成，口径同 ShipGlowRenderer.preloadTextures）。
         */
        @JvmStatic
        fun preloadTextures() {
            try {
                Global.getSettings().loadTexture(TEXTURE_PATH)
                val tex = TextureManager.loadTexture(TEXTURE_PATH)
                if (tex[0] <= 0) {
                    log.warn("[ASTD] 引力磁暴提示圈贴图上传失败（$TEXTURE_PATH）")
                    return
                }
                texture = TextureInfo(tex[0], tex[4].toFloat() / tex[2], tex[5].toFloat() / tex[3])
            } catch (t: Throwable) {
                log.warn("[ASTD] 引力磁暴提示圈贴图预加载失败（$TEXTURE_PATH）", t)
            }
        }

        /**
         * 创建并注册提示圈实体；只使用 [preloadTextures] 预载的纹理（战斗中不做 loadTexture）。
         * 未预载（上传失败/onApplicationLoad 未执行）或 addEntity 非 0 记 WARN 并返回 null，
         * 本次提示圈缺席但系统机制照常。
         */
        fun attach(engine: CombatEngineAPI, ship: ShipAPI): GravStormConeIndicator? {
            val tex = texture
            if (tex == null) {
                log.warn("[ASTD] 引力磁暴提示圈贴图未预载（$TEXTURE_PATH），本次提示圈缺席")
                return null
            }

            BoxUtilCombatVfx.ensureReady(engine)

            val entity = SpriteEntity()
            entity.setLayer(CombatEngineLayers.BELOW_SHIPS_LAYER)
            entity.setAdditiveBlend()
            entity.setUVStart(0f, 0f)
            entity.setUVEnd(tex.uEnd, tex.vEnd)
            entity.materialData.setDiffuse(tex.texId)
            entity.materialData.setEmissive(tex.texId)
            entity.materialData.setColor(CONE_COLOR)
            entity.materialData.setEmissiveColor(CONE_COLOR)
            entity.materialData.glowPower = 0.3f
            entity.materialData.isIgnoreIllumination = true
            entity.materialData.colorAlpha = 0f
            entity.materialData.emissiveColorAlpha = 0f
            entity.setGlobalTimer(0f, FULL_SECONDS, 0f)

            val state = BoxUtilCombatVfx.addEntity(engine, entity)
            if (state != 0) {
                log.warn("[ASTD] 引力磁暴提示圈 addEntity 失败（state=$state，ship=${ship.id}），本次提示圈缺席")
                entity.delete()
                return null
            }
            return GravStormConeIndicator(ship, entity)
        }
    }
}
