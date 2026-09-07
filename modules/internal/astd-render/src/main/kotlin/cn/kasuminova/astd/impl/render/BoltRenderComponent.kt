package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.ProjectileHost
import cn.kasuminova.astd.api.render.RenderContext
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx.addEntity
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.MissileAPI
import org.boxutil.define.BoxEnum
import org.boxutil.units.standard.entity.SpriteEntity
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Box 螺栓弹头组件（RenderEntity 叶子）：取代原版 ProjectileRenderer 的螺栓渲染路径
 * （原版视觉由 .proj 的 `bulletSprite=BUtil_NONE.png` + 双色 alpha=0 屏蔽，见 ss-csv 侧
 * `ProjectileProjSpec.boxBolt`）。
 *
 * 每帧从弹体 API 实时同步两颗 SpriteEntity（projbody 彗星贴图，additive，ABOVE_SHIPS 层）：
 * - 几何：`boltFrame`（头=弹体位置、尾=tailEnd、前伸段=原版同款 max(width/2, length×0.2)）；
 * - 亮度：外缘层 alpha = brightness（一次方）、核心层 alpha = brightness²（对齐原版两趟叠加）；
 * - 出生伸入：X 向缩放随 |头−尾|/length 从 0 拉满（原版 TrailExtender distanceRatio 同语义）。
 *
 * 弹体移出引擎即删除实体；命中时补发一次命中光晕（原版用 .proj 的 fringeColor 画 hit glow，
 * 屏蔽后 alpha=0 不可见，这里用 DSL 缘色补回）。
 *
 * 导弹（MissileAPI）不接管：原版导弹贴图渲染保留，组件 attach 时直接禁用自身。
 * BoxUtil 未就绪/建实体失败时 WARN 一次并禁用自身（不重试风暴），弹体其余特效层不受影响。
 */
class BoltRenderComponent(
    id: String,
    internal val spec: BoltSpec,
) : RenderEntityImpl(id, CombatEngineLayers.ABOVE_SHIPS_LAYER, RENDER_ORDER_BOLT) {

    private val log = Global.getLogger(BoltRenderComponent::class.java)
    private var fringe: SpriteEntity? = null
    private var core: SpriteEntity? = null
    private var disabled = false
    private var hitGlowSpawned = false

    private var specLength = 0f
    private var specWidth = 0f
    private var coreWidthMult = 1f
    private var hitGlowRadius = 0f

    override fun onAttachSelf(ctx: RenderContext): Boolean {
        val engine = ctx.engine ?: return false
        val projectile = (ctx.host as? ProjectileHost)?.projectile ?: return false
        if (projectile is MissileAPI) {
            disabled = true
            return true
        }
        val projSpec = projectile.projectileSpec
        if (projSpec == null) {
            log.warn("ASTD box bolt 无法读取 projectileSpec：id=$id，本弹体螺栓层缺失，其余特效层照常")
            disabled = true
            return true
        }
        specLength = projSpec.length
        specWidth = projSpec.width
        coreWidthMult = projSpec.coreWidthMult
        hitGlowRadius = projSpec.hitGlowRadius
        if (specLength <= 0f || specWidth <= 0f) {
            log.warn("ASTD box bolt 弹体尺寸非法（length=$specLength width=$specWidth）：id=$id，本弹体螺栓层缺失，其余特效层照常")
            disabled = true
            return true
        }
        try {
            Global.getSettings().openStream(spec.texturePath).use { }
        } catch (e: Exception) {
            log.warn("ASTD box bolt texture unreadable: id=$id path=${spec.texturePath}", e)
            disabled = true
            return true
        }

        val stretch = boltStretch(specLength, specWidth)
        val halfLen = (specLength + stretch) / 2f
        fringe = createBoltSprite(engine, halfLen, specWidth / 2f, spec.fringeColor) ?: run {
            disabled = true
            return true
        }
        core = createBoltSprite(engine, halfLen, specWidth * coreWidthMult / 2f, spec.coreColor) ?: run {
            fringe?.delete()
            fringe = null
            disabled = true
            return true
        }
        syncBolt(ctx, projectile)
        return true
    }

    override fun advanceSelf(ctx: RenderContext, amount: Float) {
        if (disabled) return
        val engine = ctx.engine ?: return
        val projectile = (ctx.host as? ProjectileHost)?.projectile ?: return
        if (!engine.isEntityInPlay(projectile)) {
            deleteBoth()
            return
        }
        syncBolt(ctx, projectile)
        if (!hitGlowSpawned && projectile.didDamage()) {
            hitGlowSpawned = true
            spawnHitGlow(engine, projectile)
        }
    }

    private fun createBoltSprite(
        engine: CombatEngineAPI,
        halfLen: Float,
        halfWidth: Float,
        color: ASTDColor,
    ): SpriteEntity? {
        val entity = SpriteEntity(spec.texturePath)
        entity.setLayer(CombatEngineLayers.ABOVE_SHIPS_LAYER)
        entity.setAdditiveBlend()
        entity.setBaseSizePerTiles(halfLen, halfWidth)
        entity.materialData.setColor(color.red, color.green, color.blue, color.alpha)
        // 常驻：消亡由组件按弹体状态显式 delete，不走全局计时器
        entity.setGlobalTimer(0f, BOLT_FULL_SECONDS, 0f)
        BoxUtilCombatVfx.ensureReady(engine)
        val state = addEntity(engine, BoxEnum.ENTITY_SPRITE, entity)
        if (state != 0) {
            log.warn("ASTD box bolt 注册失败（addEntity 返回 $state）：id=$id，本弹体螺栓层缺失，其余特效层照常")
            entity.delete()
            return null
        }
        return entity
    }

    private fun syncBolt(ctx: RenderContext, projectile: DamagingProjectileAPI) {
        val brightness = projectile.brightness
        if (!brightness.isFinite()) return
        val frame = boltFrame(
            head = projectile.location,
            tail = projectile.tailEnd,
            facingDeg = BoxUtilCombatVfx.normalizeFacingDeg(ctx.frame.facing),
            specLength = specLength,
            specWidth = specWidth,
        )
        fringe?.let {
            it.setStateVanilla(frame.center, frame.facingDeg, Vector2f(frame.scaleX, 1f))
            it.materialData.setColorAlpha(brightness * spec.fringeColor.alpha)
        }
        core?.let {
            it.setStateVanilla(frame.center, frame.facingDeg, Vector2f(frame.scaleX, 1f))
            it.materialData.setColorAlpha(brightness * brightness * spec.coreColor.alpha)
        }
    }

    /**
     * 命中光晕补偿：原版屏蔽后 fringeColor alpha=0，引擎自发的 hit particle 不可见。
     * 外缘大光斑（hitGlowRadius×3×伤害缩放）+ 白色小内芯，尺寸/寿命对齐原版 spawnHitParticlesLarge 口径。
     */
    private fun spawnHitGlow(engine: CombatEngineAPI, projectile: DamagingProjectileAPI) {
        val scale = hitGlowScale(projectile.damageAmount)
        val fringeColor = Color(
            (spec.fringeColor.red.coerceIn(0f, 1f) * 255f).toInt(),
            (spec.fringeColor.green.coerceIn(0f, 1f) * 255f).toInt(),
            (spec.fringeColor.blue.coerceIn(0f, 1f) * 255f).toInt(),
        )
        val at = Vector2f(projectile.location)
        val zero = Vector2f(0f, 0f)
        engine.addHitParticle(at, zero, hitGlowRadius * 3f * scale, 1f, 0.4f, fringeColor)
        engine.addHitParticle(at, zero, hitGlowRadius * 0.5f * scale, 1f, 0.8f, Color.WHITE)
    }

    private fun deleteBoth() {
        fringe?.delete()
        core?.delete()
        fringe = null
        core = null
        disabled = true
    }

    override fun onDetachSelf() {
        fringe?.delete()
        core?.delete()
        fringe = null
        core = null
    }

    companion object {
        /** 螺栓绘制序：原版弹体同层（ABOVE_SHIPS），拖尾/光斑在其上的 ABOVE_PARTICLES 层。 */
        const val RENDER_ORDER_BOLT = 200

        /** 螺栓常驻时长（秒）：生命周期由弹体状态显式驱动，这里给一个永不自然到期的值。 */
        private const val BOLT_FULL_SECONDS = 1e7f
    }
}

/** 螺栓帧几何输出：世界中心、归一化朝向（度）、X 向伸入缩放（0..1，出生拉长）。 */
internal data class BoltFrame(val center: Vector2f, val facingDeg: Float, val scaleX: Float)

/** 弹头前伸段长度（世界单位）：对齐原版 ProjectileRenderer 的 max(width/2, length×0.2)。 */
internal fun boltStretch(specLength: Float, specWidth: Float): Float = max(specWidth / 2f, specLength * 0.2f)

/**
 * 螺栓帧几何：弹体覆盖区间 = [tail, head + dir×stretch]，sprite 基准全长 = specLength + stretch，
 * X 向缩放 = 当前覆盖长 / 基准全长（出生时 tail≈head，螺栓从炮口一点拉成全长——原版 distanceRatio 同语义）。
 */
internal fun boltFrame(
    head: Vector2f,
    tail: Vector2f?,
    facingDeg: Float,
    specLength: Float,
    specWidth: Float,
): BoltFrame {
    val rad = Math.toRadians(facingDeg.toDouble())
    val dirX = cos(rad).toFloat()
    val dirY = sin(rad).toFloat()
    val stretch = boltStretch(specLength, specWidth)
    val front = Vector2f(head.x + dirX * stretch, head.y + dirY * stretch)
    val realTail = tail ?: head
    val span = (front.x - realTail.x) * dirX + (front.y - realTail.y) * dirY
    val baseFull = specLength + stretch
    val scaleX = (span / baseFull).coerceIn(0.02f, 1.2f)
    val center = Vector2f(
        (front.x + realTail.x) / 2f,
        (front.y + realTail.y) / 2f,
    )
    return BoltFrame(center, facingDeg, scaleX)
}

/** 命中光晕伤害缩放：sqrt(damage/250) 钳 [0.8, 2.5]，对齐原版按伤害放大的观感量级。 */
internal fun hitGlowScale(damageAmount: Float): Float =
    (sqrt((damageAmount.coerceAtLeast(0f)) / 250f)).coerceIn(0.8f, 2.5f)
