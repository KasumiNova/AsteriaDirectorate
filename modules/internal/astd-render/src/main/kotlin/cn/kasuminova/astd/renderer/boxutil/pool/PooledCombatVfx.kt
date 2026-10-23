package cn.kasuminova.astd.renderer.boxutil.pool

import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.graphics.SpriteAPI
import com.fs.starfarer.api.input.InputEventAPI
import org.boxutil.base.api.InstanceDataAPI
import org.boxutil.define.InstanceType
import org.boxutil.units.standard.attribute.Instance2Data
import org.boxutil.units.standard.entity.SpriteEntity
import org.boxutil.units.standard.entity.TrailEntity
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/**
 * 池化战斗 VFX 入口。BoxUtil 的 renderEntityMap 在单场战斗内只增不删，实体 delete 后引用
 * 滞留至战斗切换才清理，高频新建实体的粒子会不断累积，必须统一改为池化常驻实体复用。
 */
object PooledCombatVfx {

    private const val MANAGER_KEY = "astd_pooled_combat_vfx"

    private val log = Global.getLogger(PooledCombatVfx::class.java)

    data class SpritePoolKey(
        val spritePath: String,
        val layer: CombatEngineLayers,
        val glowPower: Float = 1f,
        val alphaToEmissive: Float = 1f,
        val capacity: Int = 1024,
    )

    data class TrailPoolKey(
        val layer: CombatEngineLayers,
        val coreSpritePath: String,
        val fringeSpritePath: String,
        val mixPower: Float,
        val capacity: Int = 128,
    )

    fun spawnSprite(
        engine: CombatEngineAPI,
        key: SpritePoolKey,
        x: Float, y: Float,
        velX: Float = 0f, velY: Float = 0f,
        facingDeg: Float = 0f, turnRateDeg: Float = 0f,
        scaleX: Float, scaleY: Float,
        scaleRateX: Float = 0f, scaleRateY: Float = 0f,
        color: Color, emissiveColor: Color,
        fadeIn: Float, full: Float, fadeOut: Float,
    ): Boolean {
        val binding = manager(engine).spritePools.getOrPut(key) { SpritePoolBinding(engine, key) }
        return binding.spawn(
            x, y, velX, velY, facingDeg, turnRateDeg, scaleX, scaleY, scaleRateX, scaleRateY,
            color, emissiveColor, fadeIn, full, fadeOut,
        )
    }

    fun spawnTrail(
        engine: CombatEngineAPI,
        key: TrailPoolKey,
        location: Vector2f,
        facingDeg: Float,
        length: Float,
        tailWidth: Float,
        headWidth: Float,
        coreColor: Color,
        fringeColor: Color,
        tailAlphaMul: Float,
        headAlphaMul: Float,
        tailEmissiveAlphaMul: Float,
        headEmissiveAlphaMul: Float,
        fadeIn: Float, full: Float, fadeOut: Float,
    ): Boolean {
        if (length < 1f) return false
        return checkoutTrail(
            engine,
            TrailLeaseKey(key.layer, key.coreSpritePath, key.fringeSpritePath, key.mixPower, key.capacity),
            TrailLeaseSpec(
                location = location,
                facingDeg = facingDeg,
                nodes = listOf(Vector2f(length, 0f), Vector2f(0f, 0f)),
                startWidth = tailWidth,
                endWidth = headWidth,
                coreColor = coreColor,
                fringeColor = fringeColor,
                startAlpha = tailAlphaMul,
                endAlpha = headAlphaMul,
                startEmissiveAlpha = tailEmissiveAlphaMul,
                endEmissiveAlpha = headEmissiveAlphaMul,
                envelope = TrailLeaseEnvelope(fadeIn, full, fadeOut),
            ),
        ) != null
    }

    fun checkoutTrail(engine: CombatEngineAPI, key: TrailLeaseKey, spec: TrailLeaseSpec): TrailLease? {
        if (spec.nodes.size < 2) {
            log.warn("池化光束租约检出拒绝：节点数 ${spec.nodes.size} < 2（layer=${key.layer}，core=${key.coreSpritePath}）——调用方编程错误，本次视觉缺席")
            return null
        }
        return manager(engine).trailLeasePools.getOrPut(key) { TrailLeaseBinding(engine, key) }.checkout(spec)
    }

    fun checkoutSprite(engine: CombatEngineAPI, key: SpriteLeaseKey, spec: SpriteLeaseSpec): SpriteLease? {
        val textureBindings = (if (spec.spritePath != null) 1 else 0) +
            (if (spec.sprite != null) 1 else 0) +
            (if (spec.texId != null) 1 else 0)
        if (textureBindings != 1) {
            log.warn("池化 sprite 租约检出拒绝：纹理绑定必须三选一（实际 $textureBindings 个，layer=${key.layer}，texture=${key.textureKey}）——调用方编程错误，本次视觉缺席")
            return null
        }
        if (key.instanced && spec.maxInstances <= 0) {
            log.warn("池化 sprite 租约检出拒绝：实例化池 maxInstances 必须 > 0（实际 ${spec.maxInstances}，layer=${key.layer}，texture=${key.textureKey}）——调用方编程错误，本次视觉缺席")
            return null
        }
        return manager(engine).spriteLeasePools.getOrPut(key) { SpriteLeaseBinding(engine, key) }.checkout(spec)
    }

    private fun manager(engine: CombatEngineAPI): Manager {
        val existing = engine.customData[MANAGER_KEY] as? Manager
        if (existing != null) return existing
        val manager = Manager(engine)
        try {
            engine.addPlugin(manager)
        } catch (t: Throwable) {
            log.warn("[ASTD] 池化 VFX 管理器注册失败，本战斗池化粒子全部缺席", t)
        }
        engine.customData[MANAGER_KEY] = manager
        return manager
    }

    private class Manager(private val engine: CombatEngineAPI) : BaseEveryFrameCombatPlugin() {
        val spritePools = LinkedHashMap<SpritePoolKey, SpritePoolBinding>()
        val trailLeasePools = LinkedHashMap<TrailLeaseKey, TrailLeaseBinding>()
        val spriteLeasePools = LinkedHashMap<SpriteLeaseKey, SpriteLeaseBinding>()

        override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
            if (engine.isPaused) return
            for (pool in spritePools.values) pool.advance(amount)
            for (pool in trailLeasePools.values) pool.advance(amount)
            for (pool in spriteLeasePools.values) pool.advance(amount)
        }
    }

    private class SpritePoolBinding(
        private val engine: CombatEngineAPI,
        private val key: SpritePoolKey,
    ) {
        private val log = Global.getLogger(SpritePoolBinding::class.java)

        val slots = SpriteParticleSlots(key.capacity)

        private var entity: SpriteEntity? = null
        private var instances: MutableList<Instance2Data>? = null
        private var broken = false

        fun spawn(
            x: Float, y: Float,
            velX: Float, velY: Float,
            facingDeg: Float, turnRateDeg: Float,
            scaleX: Float, scaleY: Float,
            scaleRateX: Float, scaleRateY: Float,
            color: Color, emissiveColor: Color,
            fadeIn: Float, full: Float, fadeOut: Float,
        ): Boolean {
            if (broken) return false
            if (entity == null && createEntity() == null) return false
            slots.spawn(
                x, y, velX, velY, facingDeg, turnRateDeg, scaleX, scaleY, scaleRateX, scaleRateY,
                color.red, color.green, color.blue, color.alpha,
                emissiveColor.red, emissiveColor.green, emissiveColor.blue, emissiveColor.alpha,
                fadeIn, full, fadeOut,
            )
            return true
        }

        private fun createEntity(): SpriteEntity? {
            return try {
                BoxUtilCombatVfx.ensureReady(engine)
                // 必须先 loadTexture 再 getSprite，裸 getSprite 返回 textureID=0 的空壳
                val sprite = Global.getSettings().apply { loadTexture(key.spritePath) }.getSprite(key.spritePath)
                val e = SpriteEntity()
                e.setAdditiveBlend()
                e.materialData.setDiffuse(sprite)
                e.materialData.setEmissive(sprite)
                e.materialData.alphaToEmissive = key.alphaToEmissive
                e.materialData.isColorToEmissive = 0f
                e.materialData.glowPower = key.glowPower
                e.setStateVanilla(ZERO, 0f)
                e.setLayer(key.layer)

                val list = ArrayList<Instance2Data>(key.capacity)
                repeat(key.capacity) {
                    val d = Instance2Data()
                    // 位置/自转由池 CPU 侧积分，实例侧必须清零，否则与 BoxUtil 实例自管理双重积分
                    d.setVelocity(0f, 0f)
                    d.setTurnRate(0f)
                    d.setTimer(0f, 1e7f, 0f)
                    d.setColor(0, 0, 0, 0)
                    d.setEmissiveColor(0, 0, 0, 0)
                    list += d
                }
                @Suppress("UNCHECKED_CAST")
                e.setInstanceData(list as MutableList<InstanceDataAPI>, 0f, 1e7f, 0f)
                e.mallocInstance(InstanceType.DYNAMIC_2D, key.capacity)
                e.instanceDataRefreshIndex = 0
                e.instanceDataRefreshOffset = 0
                e.setInstanceDataRefreshAllFromCurrentIndex()
                e.submitInstance()
                e.setRenderingCount(0)
                e.isAlwaysRefreshInstanceData = true
                // BoxUtil 全局计时器缺省值会被首个逻辑帧判 TIMER_INVALID 直接 delete，必须钉常驻值
                e.setGlobalTimer(0f, 1e7f, 0f)

                val state = BoxUtilCombatVfx.addEntity(engine, e)
                if (state != 0) {
                    e.delete()
                    broken = true
                    log.warn("池化粒子实体注册失败（addEntity 返回 $state，sprite=${key.spritePath}），该池视觉缺席")
                    return null
                }
                entity = e
                instances = list
                e
            } catch (t: Throwable) {
                broken = true
                log.warn("池化粒子实体创建失败（sprite=${key.spritePath}），该池视觉缺席", t)
                null
            }
        }

        fun advance(amount: Float) {
            slots.advance(amount)
            val e = entity ?: return
            val list = instances ?: return
            var write = 0
            slots.forEachActive { s, alphaMul ->
                val d = list[write++]
                d.setLocation(s.posX, s.posY)
                d.setFacing(s.facingDeg)
                d.setScale(s.scaleX, s.scaleY)
                d.setColor(s.r, s.g, s.b, (s.a * alphaMul).toInt().coerceIn(0, 255))
                d.setEmissiveColor(s.er, s.eg, s.eb, (s.ea * alphaMul).toInt().coerceIn(0, 255))
            }
            e.setRenderingCount(write)
            if (write > 0) {
                e.setInstanceDataRefreshIndex(0)
                e.setInstanceDataRefreshSize(write)
                e.submitInstance()
            }
        }
    }

    private val ZERO = Vector2f(0f, 0f)
}
