package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.RenderEntity
import cn.kasuminova.astd.api.render.RenderHost
import cn.kasuminova.astd.api.render.RenderPhase
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.input.InputEventAPI
import org.lwjgl.util.vector.Vector2f

/**
 * 锚定世界坐标点的一次性特效宿主。
 *
 * 动机：锥状冲击等「在某点放一发即走」的特效没有弹体/光束宿主，但 RenderEntity 树要求一个
 * [RenderHost]。本类直接实现 RenderHost（无宿主侧查询行为，不另立空接口），把锚点与朝向
 * 摊平为每帧 [FrameStateImpl] 的 origin/facing；树节点的几何常量（锥角/锥长/调色）由各自
 * spec 持有，本宿主只提供世界锚点。
 */
class PointHost(
    override val hostId: String,
    /** 锚点（世界坐标，su）：每帧 FrameState.origin 的取值。 */
    val origin: Vector2f,
    /** 中轴朝向（度）：每帧 FrameState.facing 的取值。 */
    val facingDeg: Float,
) : RenderHost

/**
 * 一次性世界锚点特效树的驱动插件（规格 00-共享基建 §2.2-5 的落地）。
 *
 * 动机：正电子/贯星/摧锋的命中锥面特效共用 RenderEntity 组件，但三案的触发点
 * （弹体自爆/OnHitEffect）都不存在常驻宿主去逐帧推进一棵树；本插件承担这棵短寿命树的
 * 每帧 onAttach + advance，到期后 onDetach 并把自己从引擎摘除。
 *
 * 生命周期：暂停（engine.isPaused）整帧跳过且 elapsed 不推进；elapsed 达 [durationSeconds] 后
 * 当帧 detach + removePlugin。duration 应略大于树内节点的视觉总长（含 BoxUtil 淡出收尾），
 * 由 spawn 入口计算；onDetach 对节点是幂等的（句柄已被 BoxUtil 全局定时器自删时 delete 为空操作）。
 */
class OneShotVfxPlugin(
    private val engine: CombatEngineAPI,
    private val host: PointHost,
    private val tree: RenderEntity,
    private val durationSeconds: Float,
) : BaseEveryFrameCombatPlugin() {

    private var elapsed = 0f
    private var finished = false

    override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
        if (finished) return
        if (engine.isPaused) return
        val step = amount.coerceAtLeast(0f)
        elapsed += step

        if (elapsed >= durationSeconds) {
            finished = true
            tree.onDetach()
            engine.removePlugin(this)
            return
        }

        val ctx = RenderContextImpl(engine = engine, host = host, frame = buildFrame(step))
        tree.onAttach(ctx)
        tree.advance(ctx, step)
    }

    private fun buildFrame(amount: Float): FrameStateImpl = FrameStateImpl(
        elapsed = elapsed,
        logicElapsed = elapsed,
        amountThisFrame = amount,
        origin = Vector2f(host.origin),
        facing = host.facingDeg,
        // 一次性锚点特效的几何常量由节点 spec 持有，frame.length 无承载对象，恒 0。
        length = 0f,
        endpoint = null,
        worldUnitsPerPixel = 1f,
        active = true,
        intensity = 1f,
        phase = RenderPhase.Active,
        flightProgress = 0f,
        dissolve = 0f,
        fadeReason = null,
    )

    /** 测试探针：驱动已推进的逻辑秒数（暂停不推进）。 */
    internal fun elapsedForTests(): Float = elapsed

    /** 测试探针：驱动是否已完成收尾（detach + removePlugin）。 */
    internal fun isFinishedForTests(): Boolean = finished
}
