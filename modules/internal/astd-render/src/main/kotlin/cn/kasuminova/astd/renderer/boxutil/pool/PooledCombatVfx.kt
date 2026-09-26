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
 * 统一池化战斗 VFX 入口（防 BoxUtil renderEntityMap 滞留泄漏）。
 *
 * 背景：BoxUtil 的 renderEntityMap 在单场战斗内只增不删——实体定时器到期或 delete()
 * 仅停止渲染，引用滞留至战斗切换才清理。因此「每次 spawn 新建实体 + 定时器自删」的
 * 高频粒子（每帧/每节拍级）会在一场长战斗中累积百万级实例（2026-09 实机堆转储实锤
 * 99.2 万 SpriteEntity / 1 GB）。本设施把这类粒子统一收敛为池化常驻实体：
 *
 * - **sprite 粒子池**（[SpritePoolKey]）：每 key 一个常驻 SpriteEntity + 固定容量
 *   Instance2Data 槽位，CPU 侧积分位置/自转并逐帧写实例数据（速度/自转不再交给
 *   BoxUtil 实例自管理，避免双重积分；暂停时包络冻结）；
 * - **光束段池**（[TrailPoolKey]）：每 key 固定容量个常驻双节点 TrailEntity，spawn 时
 *   认领槽位并重写节点/宽度/材质色，逐帧按 CPU 侧包络写 START/END alpha，到期泊车
 *   （alpha 归零）而非 delete。
 *
 * 池实体随 BoxUtil 战斗切换清理一并回收；单场战斗内实体总数 = Σ key 容量，有界。
 * 低频事件级特效（每次命中数个）不必走本设施，直接新建实体即可。
 */
object PooledCombatVfx {

    private const val MANAGER_KEY = "astd_pooled_combat_vfx"

    private val log = Global.getLogger(PooledCombatVfx::class.java)

    /**
     * sprite 粒子池键：同池共享贴图/渲染层/材质参数（glowPower、alphaToEmissive）。
     * 其余逐粒子属性（位置/尺寸/朝向/速度/自转/颜色/emissive/寿命）均为 spawn 参数。
     */
    data class SpritePoolKey(
        val spritePath: String,
        val layer: CombatEngineLayers,
        val glowPower: Float = 1f,
        val alphaToEmissive: Float = 1f,
        val capacity: Int = 1024,
    )

    /**
     * 光束段池键：同池共享渲染层/核心与 fringe 贴图/mixFactor。
     * 其余逐段属性（位置/朝向/长度/宽度/颜色/alpha 乘数/寿命）均为 spawn 参数。
     */
    data class TrailPoolKey(
        val layer: CombatEngineLayers,
        val coreSpritePath: String,
        val fringeSpritePath: String,
        val mixPower: Float,
        val capacity: Int = 128,
    )

    /**
     * 喷一颗池化 sprite 粒子。[scaleX]/[scaleY] 为世界半尺寸（对齐 Instance2Data scale 语义）；
     * [color]/[emissiveColor] 的 alpha 为基准值，逐帧再乘包络。
     *
     * @return false = 池不可用（BoxUtil 未就绪/实体注册失败），本颗视觉缺席（已记 WARN）
     */
    fun spawnSprite(
        engine: CombatEngineAPI,
        key: SpritePoolKey,
        x: Float, y: Float,
        velX: Float = 0f, velY: Float = 0f,
        facingDeg: Float = 0f, turnRateDeg: Float = 0f,
        scaleX: Float, scaleY: Float,
        color: Color, emissiveColor: Color,
        fadeIn: Float, full: Float, fadeOut: Float,
    ): Boolean {
        val binding = manager(engine).spritePools.getOrPut(key) { SpritePoolBinding(engine, key) }
        return binding.spawn(x, y, velX, velY, facingDeg, turnRateDeg, scaleX, scaleY, color, emissiveColor, fadeIn, full, fadeOut)
    }

    /**
     * 喷一段池化光束（尾→头 taper，节点序对齐 BoxUtilCombatVfx.createTaperedBeamTrail：
     * node[0] 在 +length 端为尾，START_* 作用于尾）。长度 < 1f 直接缺席。
     *
     * @return false = 池不可用，本段视觉缺席（已记 WARN）
     */
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
        val binding = manager(engine).trailPools.getOrPut(key) { TrailPoolBinding(engine, key) }
        return binding.spawn(
            location, facingDeg, length, tailWidth, headWidth, coreColor, fringeColor,
            tailAlphaMul, headAlphaMul, tailEmissiveAlphaMul, headEmissiveAlphaMul,
            fadeIn, full, fadeOut,
        )
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

    /** 池推进插件：暂停时冻结（包络/积分全部停走），与 vanilla 粒子暂停语义一致。 */
    private class Manager(private val engine: CombatEngineAPI) : BaseEveryFrameCombatPlugin() {
        val spritePools = LinkedHashMap<SpritePoolKey, SpritePoolBinding>()
        val trailPools = LinkedHashMap<TrailPoolKey, TrailPoolBinding>()

        override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
            if (engine.isPaused) return
            for (pool in spritePools.values) pool.advance(amount)
            for (pool in trailPools.values) pool.advance(amount)
        }
    }

    /** sprite 粒子池绑定：一个常驻 SpriteEntity + 环形复用实例槽。 */
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
            color: Color, emissiveColor: Color,
            fadeIn: Float, full: Float, fadeOut: Float,
        ): Boolean {
            if (broken) return false
            if (entity == null && createEntity() == null) return false
            slots.spawn(
                x, y, velX, velY, facingDeg, turnRateDeg, scaleX, scaleY,
                color.red, color.green, color.blue, color.alpha,
                emissiveColor.red, emissiveColor.green, emissiveColor.blue, emissiveColor.alpha,
                fadeIn, full, fadeOut,
            )
            return true
        }

        private fun createEntity(): SpriteEntity? {
            return try {
                BoxUtilCombatVfx.ensureReady(engine)
                // 须先 loadTexture 进缓存，裸 getSprite 会拿到 textureID=0 的壳（TriShard 实锤教训）。
                val sprite = Global.getSettings().apply { loadTexture(key.spritePath) }.getSprite(key.spritePath)
                val e = SpriteEntity()
                e.setAdditiveBlend()
                e.materialData.setDiffuse(sprite)
                e.materialData.setEmissive(sprite)
                e.materialData.alphaToEmissive = key.alphaToEmissive
                e.materialData.isColorToEmissive = 0f
                e.materialData.glowPower = key.glowPower
                // 实例坐标即世界坐标：实体锚原点、零朝向。
                e.setStateVanilla(ZERO, 0f)
                e.setLayer(key.layer)

                val list = ArrayList<Instance2Data>(key.capacity)
                repeat(key.capacity) {
                    val d = Instance2Data()
                    // 位置/自转由池 CPU 侧积分，实例侧必须清零，否则与 BoxUtil 自管理双重积分。
                    d.setVelocity(0f, 0f)
                    d.setTurnRate(0f)
                    // 实例定时器钉死：寿命包络由池 CPU 侧驱动 alpha。
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
                // 常驻：全局计时器缺省值会被首个逻辑帧判 TIMER_INVALID 直接 delete。
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

    /** 光束段池绑定：固定容量个常驻双节点 TrailEntity，spawn 认领槽位重写几何/颜色。 */
    private class TrailPoolBinding(
        private val engine: CombatEngineAPI,
        private val key: TrailPoolKey,
    ) {
        private val log = Global.getLogger(TrailPoolBinding::class.java)

        val slots = TrailSlots(key.capacity)

        private val entities = ArrayList<TrailEntity?>(key.capacity)
        private var coreSprite: SpriteAPI? = null
        private var fringeSprite: SpriteAPI? = null
        private var spritesAttempted = false
        private var broken = false

        fun spawn(
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
            if (broken) return false
            if (!ensureSprites()) return false
            val index = slots.claim(
                fadeIn, full, fadeOut,
                tailAlphaMul, headAlphaMul, tailEmissiveAlphaMul, headEmissiveAlphaMul,
            )
            val e = getOrCreateEntity(index) ?: return false
            try {
                e.setStateVanilla(Vector2f(location), BoxUtilCombatVfx.normalizeFacingDeg(facingDeg))
                // 节点序对齐 createTaperedBeamTrail：node[0]=+length 端（尾），node[1]=原点（头）。
                e.setNodes(listOf(Vector2f(length, 0f), Vector2f(ZERO)))
                e.submitNodes()
                e.startWidth = tailWidth
                e.endWidth = headWidth
                e.materialData.setColor(coreColor)
                e.materialData.setEmissiveColor(fringeColor)
            } catch (t: Throwable) {
                log.warn("池化光束段配置失败（layer=${key.layer}），本段视觉缺席", t)
                return false
            }
            applyAlpha(index)
            return true
        }

        private fun ensureSprites(): Boolean {
            if (spritesAttempted) return coreSprite != null && fringeSprite != null
            spritesAttempted = true
            return try {
                BoxUtilCombatVfx.ensureReady(engine)
                val settings = Global.getSettings()
                settings.loadTexture(key.coreSpritePath)
                settings.loadTexture(key.fringeSpritePath)
                coreSprite = settings.getSprite(key.coreSpritePath)
                fringeSprite = settings.getSprite(key.fringeSpritePath)
                true
            } catch (t: Throwable) {
                broken = true
                log.warn("池化光束段贴图加载失败（core=${key.coreSpritePath}），该池视觉缺席", t)
                false
            }
        }

        private fun getOrCreateEntity(index: Int): TrailEntity? {
            while (entities.size <= index) entities += null
            entities[index]?.let { return it }
            return try {
                val e = TrailEntity()
                e.setAdditiveBlend()
                e.setLayer(key.layer)
                // 常驻：消亡由槽位包络泊车（alpha 归零），不走 delete（引用本就会滞留）。
                e.setGlobalTimer(0f, 1e7f, 0f)
                e.mixFactor = key.mixPower
                val mat = e.materialData
                mat.alphaToEmissive = 0f
                mat.isColorToEmissive = 0f
                mat.glowPower = 1f
                mat.setDiffuse(coreSprite)
                mat.setEmissive(fringeSprite)
                parkEntity(e)

                val state = BoxUtilCombatVfx.addEntity(engine, e)
                if (state != 0) {
                    e.delete()
                    broken = true
                    log.warn("池化光束段实体注册失败（addEntity 返回 $state，layer=${key.layer}），该池视觉缺席")
                    return null
                }
                entities[index] = e
                e
            } catch (t: Throwable) {
                broken = true
                log.warn("池化光束段实体创建失败（layer=${key.layer}），该池视觉缺席", t)
                null
            }
        }

        fun advance(amount: Float) {
            slots.advance(amount)
            for (i in entities.indices) {
                val e = entities[i] ?: continue
                val s = slots.slots[i]
                if (!s.active) {
                    parkEntity(e)
                    continue
                }
                applyAlpha(i)
            }
        }

        /** 按槽位包络把基准 alpha 乘数写进 START/END 色（START=尾，END=头）。 */
        private fun applyAlpha(index: Int) {
            val e = entities[index] ?: return
            val s = slots.slots[index]
            val env = slots.alphaAt(index)
            e.setStartColor(1f, 1f, 1f, s.tailAlpha * env)
            e.setEndColor(1f, 1f, 1f, s.headAlpha * env)
            e.setStartEmissive(1f, 1f, 1f, s.tailEmissiveAlpha * env)
            e.setEndEmissive(1f, 1f, 1f, s.headEmissiveAlpha * env)
        }

        private fun parkEntity(e: TrailEntity) {
            e.setStartColor(1f, 1f, 1f, 0f)
            e.setEndColor(1f, 1f, 1f, 0f)
            e.setStartEmissive(1f, 1f, 1f, 0f)
            e.setEndEmissive(1f, 1f, 1f, 0f)
        }
    }

    private val ZERO = Vector2f(0f, 0f)
}
