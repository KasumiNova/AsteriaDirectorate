package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.ProjectileHost
import cn.kasuminova.astd.api.render.RenderContext
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import cn.kasuminova.astd.renderer.boxutil.pool.PooledCombatVfx
import cn.kasuminova.astd.renderer.boxutil.pool.SpriteLease
import cn.kasuminova.astd.renderer.boxutil.pool.SpriteLeaseKey
import cn.kasuminova.astd.renderer.boxutil.pool.SpriteLeaseSpec
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.MissileAPI
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.sqrt

/**
 * Box 螺栓弹头组件（RenderEntity 叶子）：取代原版 ProjectileRenderer 的螺栓渲染路径
 * （原版视觉由 .proj 的 `bulletSprite=BUtil_NONE.png` + 双色 alpha=0 屏蔽，见 ss-csv 侧
 * `ProjectileProjSpec.boxBolt`）。
 *
 * 观感对齐原版 built-in 螺栓（ProjectileRenderer.render 的 var47==null 路径）：
 * - 贴图 [BoltSpec.texturePath]：彗形 + 纵向渐隐（头全亮→尾透明）+ 收窄（头全宽→尾半宽）
 *   已烘焙进 alpha（原版靠逐顶点色与梯形几何实现，SpriteEntity 单 quad 无此能力，故烘焙）；
 * - 单颗池化 SpriteEntity（[PooledCombatVfx.checkoutSprite] 租约）additive 叠加，
 *   统一染 [BoltSpec.color]
 *   （原版弹头只用 coreColor；fringeColor 的 projtrail 外带语义由 Static Trail 接替）；
 * - 每帧从弹体 API 实时同步：`boltFrame`（贴图跨 [tailEnd → 弹体位置]），
 *   alpha = brightness² × 染色 alpha（原版 body 两趟均吃平方亮度）；
 * - 出生伸入：X 向缩放随 |头−尾|/spec.length 从 0 拉满（原版 TrailExtender distanceRatio 同语义）。
 *
 * 租约口径：手动 alpha + 心跳看门狗（0.5s 未触活即泊车兜底）；弹体移出引擎/组件 detach
 * 即 release(0f) 归还。命中时补发一次命中光晕（原版用 .proj 的 fringeColor 画 hit glow，
 * 屏蔽后 alpha=0 不可见，这里用 DSL 染色补回）。
 *
 * 导弹（MissileAPI）默认不接管：原版导弹贴图渲染保留，组件 attach 时直接禁用自身；
 * [BoltSpec.allowMissile] 开启时接管（原版贴图须另行屏蔽，尺寸取 [BoltSpec.lengthOverride]/
 * [BoltSpec.widthOverride]——导弹 spec 无 length/width 键；导弹 getTailEnd 恒 null，
 * 螺栓尾点沿朝向反推全长合成，无出生伸入）。显式接管路径（override 尺寸齐全）不读
 * projectileSpec——脚本 spawnProjectile 产出的 MissileAPI 该值为 null（[resolveBoltDimensions]）。
 * BoxUtil 未就绪/建实体失败时 WARN 一次并禁用自身（不重试风暴），弹体其余特效层不受影响。
 */
class BoltRenderComponent(
    id: String,
    internal val spec: BoltSpec,
) : RenderEntityImpl(id, CombatEngineLayers.ABOVE_SHIPS_LAYER, RENDER_ORDER_BOLT) {

    private val log = Global.getLogger(BoltRenderComponent::class.java)
    private var boltLease: SpriteLease? = null
    private var disabled = false
    private var hitGlowSpawned = false

    private var specLength = 0f
    private var specWidth = 0f
    private var hitGlowRadius = 0f
    private var missileMode = false

    override fun onAttachSelf(ctx: RenderContext): Boolean {
        val engine = ctx.engine ?: return false
        val projectile = (ctx.host as? ProjectileHost)?.projectile ?: return false
        if (projectile is MissileAPI && !spec.allowMissile) {
            disabled = true
            return true
        }
        missileMode = projectile is MissileAPI
        // 显式接管路径（override 尺寸齐全）不依赖 projectileSpec：脚本 spawnProjectile 产出的
        // MissileAPI 其 projectileSpec 为 null（实机判例：astd_starfall_wing_mote 螺栓层曾因此自禁用）；
        // 只有缺 override 时才需要 spec 读 length/width。WARN 弹体级一次性（onAttachSelf 每实例只跑一次）。
        val dims = resolveBoltDimensions(
            spec.lengthOverride, spec.widthOverride,
            projectile.projectileSpec?.length, projectile.projectileSpec?.width,
            projectile.projectileSpec?.hitGlowRadius,
        )
        if (dims == null) {
            log.warn(
                "ASTD box bolt 尺寸裁定失败（hasSpec=${projectile.projectileSpec != null} " +
                    "lengthOverride=${spec.lengthOverride} widthOverride=${spec.widthOverride}）：id=$id，" +
                    "本弹体螺栓层缺失，其余特效层照常",
            )
            disabled = true
            return true
        }
        specLength = dims.length
        specWidth = dims.width
        hitGlowRadius = dims.hitGlowRadius
        try {
            Global.getSettings().openStream(spec.texturePath).use { }
        } catch (e: Exception) {
            log.warn("ASTD box bolt texture unreadable: id=$id path=${spec.texturePath}", e)
            disabled = true
            return true
        }

        val lease = checkoutBolt(engine, projectile)
        if (lease == null) {
            disabled = true
            return true
        }
        boltLease = lease
        syncBolt(ctx, projectile)
        return true
    }

    override fun advanceSelf(ctx: RenderContext, amount: Float) {
        if (disabled) return
        val engine = ctx.engine ?: return
        val projectile = (ctx.host as? ProjectileHost)?.projectile ?: return
        if (!engine.isEntityInPlay(projectile)) {
            releaseBolt()
            disabled = true
            return
        }
        syncBolt(ctx, projectile)
        if (!hitGlowSpawned && projectile.didDamage()) {
            hitGlowSpawned = true
            spawnHitGlow(engine, projectile)
        }
    }

    /** 检出租约：additive、glowPower=0.5、diffuse/emissive 同贴图染 spec.color；手动 alpha + 看门狗兜底。 */
    private fun checkoutBolt(engine: CombatEngineAPI, projectile: DamagingProjectileAPI): SpriteLease? {
        val tint = spec.color.toAwtColor()
        // 材质口径对齐出厂 new SpriteEntity(path)：alphaToEmissive=1（emissive alpha 跟随
        // 逐帧 colorAlpha）、additionEmissive / ignoreIllumination=true
        return PooledCombatVfx.checkoutSprite(
            engine,
            SpriteLeaseKey(
                layer = CombatEngineLayers.ABOVE_SHIPS_LAYER,
                textureKey = spec.texturePath,
                additive = true,
                instanced = false,
                capacity = 64,
            ),
            SpriteLeaseSpec(
                location = Vector2f(projectile.location),
                facingDeg = BoxUtilCombatVfx.normalizeFacingDeg(projectile.facing),
                spritePath = spec.texturePath,
                bindEmissive = true,
                baseSizeHalfWidth = specLength / 2f,
                baseSizeHalfHeight = specWidth / 2f,
                color = tint,
                emissiveColor = tint,
                glowPower = 0.5f,
                alphaToEmissive = 1f,
                isAdditionEmissive = true,
                isIgnoreIllumination = true,
                watchdogHeartbeat = WATCHDOG_HEARTBEAT_SECONDS,
                watchdogFadeOut = 0f,
            ),
        )
    }

    private fun syncBolt(ctx: RenderContext, projectile: DamagingProjectileAPI) {
        val brightness = projectile.brightness
        if (!brightness.isFinite()) return
        val facing = BoxUtilCombatVfx.normalizeFacingDeg(ctx.frame.facing)
        // 导弹无 TrailExtender 尾迹真值（getTailEnd 恒 null）：沿朝向反推 specLength 合成全长尾点，
        // 螺栓按全长渲染（无出生伸入）；实弹仍消费原版 tailEnd 真值。
        val tail = projectile.tailEnd ?: if (missileMode) missileBoltTail(projectile.location, facing, specLength) else null
        val frame = boltFrame(
            head = projectile.location,
            tail = tail,
            facingDeg = facing,
            specLength = specLength,
        )
        val alpha = brightness * brightness * spec.color.alpha
        val scale = Vector2f(frame.scaleX, 1f)
        val lease = boltLease ?: return
        lease.entity.setStateVanilla(frame.center, frame.facingDeg, scale)
        lease.entity.materialData.colorAlpha = alpha
        lease.touch()
    }

    /**
     * 命中光晕补偿：原版屏蔽后 fringeColor alpha=0，引擎自发的 hit particle 不可见。
     * 外缘大光斑（hitGlowRadius×3×伤害缩放）+ 白色小内芯，尺寸/寿命对齐原版 spawnHitParticlesLarge 口径。
     */
    private fun spawnHitGlow(engine: CombatEngineAPI, projectile: DamagingProjectileAPI) {
        val scale = hitGlowScale(projectile.damageAmount)
        val tint = Color(
            (spec.color.red.coerceIn(0f, 1f) * 255f).toInt(),
            (spec.color.green.coerceIn(0f, 1f) * 255f).toInt(),
            (spec.color.blue.coerceIn(0f, 1f) * 255f).toInt(),
        )
        val at = Vector2f(projectile.location)
        val zero = Vector2f(0f, 0f)
        engine.addHitParticle(at, zero, hitGlowRadius * 3f * scale, 1f, 0.4f, tint)
        engine.addHitParticle(at, zero, hitGlowRadius * 0.5f * scale, 1f, 0.8f, Color.WHITE)
    }

    private fun releaseBolt() {
        boltLease?.release(0f)
        boltLease = null
    }

    override fun onDetachSelf() {
        releaseBolt()
    }

    companion object {
        /** 螺栓绘制序：原版弹体同层（ABOVE_SHIPS），拖尾/光斑在其上的 ABOVE_PARTICLES 层。 */
        const val RENDER_ORDER_BOLT = 200

        /** 看门狗心跳（秒）：弹体宿主停更超此时长即自动泊车归还（实体不滞留兜底）。 */
        private const val WATCHDOG_HEARTBEAT_SECONDS = 0.5f
    }
}

/** DSL 染色（0..1 浮点分量）转 AWT Color（0..255，池 spec 统一走 AWT）。 */
private fun ASTDColor.toAwtColor(): Color = Color(
    (red.coerceIn(0f, 1f) * 255f).toInt(),
    (green.coerceIn(0f, 1f) * 255f).toInt(),
    (blue.coerceIn(0f, 1f) * 255f).toInt(),
    (alpha.coerceIn(0f, 1f) * 255f).toInt(),
)

/** 螺栓帧几何输出：世界中心、归一化朝向（度）、X 向伸入缩放（0..1，出生拉长）。 */
internal data class BoltFrame(val center: Vector2f, val facingDeg: Float, val scaleX: Float)

/** 螺栓尺寸裁定输出：全长/全宽（su）与命中光晕半径。 */
internal data class BoltDimensions(val length: Float, val width: Float, val hitGlowRadius: Float)

/**
 * 螺栓尺寸裁定（纯函数）：override 优先，缺省读弹体 spec 值；spec 缺失（脚本 spawnProjectile
 * 产出的 MissileAPI 其 projectileSpec 为 null，实机判例）但 override 齐全时直接裁定——
 * 显式接管路径不依赖 spec。命中光晕半径无 spec 时退化为螺栓全宽（最保守视觉量级）。
 * 长度/宽度任一无法裁定或 ≤0 → null（调用方 WARN + 禁用本层）。
 */
internal fun resolveBoltDimensions(
    lengthOverride: Float?,
    widthOverride: Float?,
    specLength: Float?,
    specWidth: Float?,
    specHitGlowRadius: Float?,
): BoltDimensions? {
    val length = lengthOverride ?: specLength ?: return null
    val width = widthOverride ?: specWidth ?: return null
    if (length <= 0f || width <= 0f) return null
    return BoltDimensions(length, width, specHitGlowRadius ?: width)
}

/**
 * 螺栓帧几何：贴图跨 [tail → head]（原版 body 带体区间），sprite 基准全长 = specLength，
 * X 向缩放 = 当前覆盖长 / specLength（出生时 tail≈head，螺栓从炮口一点拉成全长——
 * 原版 TrailExtender distanceRatio 同语义）。
 */
internal fun boltFrame(
    head: Vector2f,
    tail: Vector2f?,
    facingDeg: Float,
    specLength: Float,
): BoltFrame {
    val rad = Math.toRadians(facingDeg.toDouble())
    val dirX = kotlin.math.cos(rad).toFloat()
    val dirY = kotlin.math.sin(rad).toFloat()
    val realTail = tail ?: head
    val span = (head.x - realTail.x) * dirX + (head.y - realTail.y) * dirY
    val scaleX = (span / specLength).coerceIn(0.02f, 1.2f)
    val center = Vector2f(
        (head.x + realTail.x) / 2f,
        (head.y + realTail.y) / 2f,
    )
    return BoltFrame(center, facingDeg, scaleX)
}

/** 命中光晕伤害缩放：sqrt(damage/250) 钳 [0.8, 2.5]，对齐原版按伤害放大的观感量级。 */
internal fun hitGlowScale(damageAmount: Float): Float =
    (sqrt((damageAmount.coerceAtLeast(0f)) / 250f)).coerceIn(0.8f, 2.5f)

/**
 * 导弹螺栓尾点（纯函数）：MissileAPI.getTailEnd 恒 null（无 TrailExtender），
 * 沿朝向反推 specLength 合成全长尾点——喂给 [boltFrame] 后 scaleX=1（全长螺栓）、
 * 中心 = 头沿朝向退半程。
 */
internal fun missileBoltTail(head: Vector2f, facingDeg: Float, specLength: Float): Vector2f {
    val rad = Math.toRadians(facingDeg.toDouble())
    return Vector2f(
        head.x - (kotlin.math.cos(rad) * specLength).toFloat(),
        head.y - (kotlin.math.sin(rad) * specLength).toFloat(),
    )
}
