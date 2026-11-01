package cn.kasuminova.astd.renderer.boxutil.pool

import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import org.boxutil.base.SimpleParticleControlData
import org.boxutil.units.standard.entity.FlareEntity
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/**
 * BoxUtil FlareEntity 批量粒子池：一个常驻实体挂 [SimpleParticleControlData]，支持高频一次性粒子。
 *
 * 池以 `engine.customData` 为作用域，战斗结束随引擎释放；同一 key 重复 [obtain] 返回现有实例，
 * 但控制器已清空（上一批粒子全部结算）的池会因 BoxUtil 提交游标残留缺陷一律重建。
 * 创建/注册失败会记录 WARN 日志并以 key 标记本场战斗禁用（后续 obtain 直接返回 null）。
 */
class FlareParticlePool private constructor(
    private val engine: CombatEngineAPI,
    private val entity: FlareEntity,
    private val controller: SimpleParticleControlData,
) {

    val expired: Boolean get() = entity.hasDelete()

    /** 最近一次成功 spawn 的战斗时间戳；用于空闲超时与控制器清空判定。 */
    var lastSpawnTime: Float = Float.NEGATIVE_INFINITY
        private set

    /**
     * 发射一个粒子。[facingDeg] 仅对长条型（smoothDisc）粒子的朝向有意义。
     * [sizeX]/[sizeY] 为相对池基础尺寸的缩放倍率；颜色为内核/边缘双色。
     */
    fun spawn(
        location: Vector2f,
        facingDeg: Float,
        velocity: Vector2f,
        sizeX: Float,
        sizeY: Float,
        core: Color,
        fringe: Color,
        fadeIn: Float,
        full: Float,
        fadeOut: Float,
    ) {
        val data = controller.addParticle(
            location, facingDeg, 0f, velocity, Vector2f(sizeX, sizeY), ZERO,
            core, fringe, fadeIn, full, fadeOut,
        )
        if (data == null) {
            if (!spawnFailureLogged) {
                spawnFailureLogged = true
                log.warn("[ASTD] FlareEntity 粒子池 spawn 被控制器拒绝（粒子控制器未就绪或已失效），本池后续同类失败不再重复告警")
            }
            return
        }
        lastSpawnTime = engine.getTotalElapsedTime(false)
    }

    /** 控制器已清空（上一批粒子全部结算）：残留提交游标可能导致下同批量粒子整批不可见，必须重建。 */
    val controllerCleared: Boolean
        get() = lastSpawnTime > Float.NEGATIVE_INFINITY &&
            engine.getTotalElapsedTime(false) > lastSpawnTime &&
            entity.renderingCount == 0

    private var spawnFailureLogged = false

    fun dispose() {
        BoxUtilCombatVfx.removeEntity(entity)
    }

    companion object {
        private val log = com.fs.starfarer.api.Global.getLogger(FlareParticlePool::class.java)

        private const val DISABLED = "disabled"
        private const val RESIDENT_FULL_SECONDS = 1e7f

        private val ZERO = Vector2f(0f, 0f)

        /**
         * 获取或创建池。
         *
         * @param smooth true 为 SMOOTH 圆形光斑，false 为 SMOOTH_DISC 长条（按朝向拉伸）。
         * @param sizeX/sizeY 池基础尺寸（世界单位），单个粒子再乘 spawn 的缩放倍率。
         * @param idleTimeout 池空闲超时（秒）：距上次 spawn 超过该时长后再次 obtain 会重建池；
         * 控制器已清空时不受此限，直接重建。
         */
        fun obtain(
            engine: CombatEngineAPI,
            key: String,
            layer: CombatEngineLayers,
            smooth: Boolean,
            sizeX: Float,
            sizeY: Float,
            capacity: Int,
            maxDuration: Float,
            glowPower: Float = 0.1f,
            idleTimeout: Float = maxDuration + 1f,
        ): FlareParticlePool? {
            if (engine.customData[key] == DISABLED) return null
            val existing = engine.customData[key] as? FlareParticlePool
            if (existing != null && !existing.expired) {
                val now = engine.getTotalElapsedTime(false)
                // BoxUtil SimpleParticleControlData.clearParticles() 漏复位提交游标 state[4]：
                // 控制器清空后若下一批粒子数恰好等于残留游标，提交块被整体跳过，整批粒子不可见。
                // 控制器已清空的池一律重建，不复用；同帧连续 spawn 时 renderingCount 尚未提交（恒 0），
                // 由 controllerCleared 内部的时间守卫排除，不会误判。
                if (!existing.controllerCleared && now - existing.lastSpawnTime <= idleTimeout) return existing
                existing.dispose()
            }
            val created = create(engine, key, layer, smooth, sizeX, sizeY, capacity, maxDuration, glowPower)
            if (created == null) {
                engine.customData[key] = DISABLED
                return null
            }
            engine.customData[key] = created
            return created
        }

        fun dispose(engine: CombatEngineAPI, key: String) {
            (engine.customData.remove(key) as? FlareParticlePool)?.dispose()
        }

        private fun create(
            engine: CombatEngineAPI,
            key: String,
            layer: CombatEngineLayers,
            smooth: Boolean,
            sizeX: Float,
            sizeY: Float,
            capacity: Int,
            maxDuration: Float,
            glowPower: Float,
        ): FlareParticlePool? {
            BoxUtilCombatVfx.ensureReady(engine)
            val entity = try {
                FlareEntity()
            } catch (t: Throwable) {
                log.warn("[ASTD] FlareEntity 粒子池建实体失败（key=$key，smooth=$smooth），本池视觉本场缺席", t)
                return null
            }
            entity.setLayer(layer)
            entity.setAdditiveBlend()
            entity.isFlick = false
            entity.glowPower = glowPower
            if (smooth) entity.setSmooth() else entity.setSmoothDisc()
            entity.setSize(sizeX, sizeY)
            entity.autoAspect()
            entity.setStateVanilla(ZERO, 0f)
            entity.setGlobalTimer(0f, RESIDENT_FULL_SECONDS, 0f)

            val controller = SimpleParticleControlData(capacity, maxDuration, -5120f, false)
            entity.setControlData(controller)
            entity.setAutoSubmitEntityData(false)
            entity.setAutoSubmitModelMatrix(false)
            entity.submitEntityData()
            entity.submitModelMatrix()

            val state = BoxUtilCombatVfx.addEntity(engine, entity)
            if (state != 0) {
                log.warn("[ASTD] FlareEntity 粒子池注册失败（key=$key，addEntity 返回 $state），本池视觉本场缺席")
                entity.delete()
                return null
            }
            return FlareParticlePool(engine, entity, controller)
        }
    }
}
