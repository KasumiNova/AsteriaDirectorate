package cn.kasuminova.astd.renderer.boxutil

import java.util.Collections
import java.util.IdentityHashMap

/**
 * 战斗域 BoxUtil 实体登记簿（纯逻辑容器，不触碰 GL 与 BoxUtil 静态状态，可独立单测）。
 *
 * 动机：BoxUtil `CombatRenderingManager.addEntity` 是静态全局注册、无 engine 作用域，
 * 其 `renderEntityMap` 战斗内只增不删，跨战斗清理（`cleanupAllQueue`）在生涯追击/多轮接战
 * 路径上证据不足（实机堆转储实锤 99 万实例滞留）。本簿记录 astd 侧注册成功的全部战斗实体，
 * 供 [cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx] 在战斗切换时兜底 delete。
 *
 * 设计权衡：
 * - **实体直引而非弱引用**：purge 需要确定性遍历并 delete 每个实体；弱引用可能在 purge 前被 GC，
 *   丢失 delete 句柄，实体将永远滞留渲染队列——与登记目的直接冲突。簿体积由 [pruneDeleted]
 *   （注册越阈时顺手清理已 delete 条目）与战斗切换 purge 双重兜底，不会自身成为泄漏源。
 * - **IdentityHashMap 身份语义**：BoxUtil 实体实现不保证值相等性，身份比较既防重复登记，
 *   也避免自定义 equals/hashCode 的意外开销与语义漂移。
 * - **delete 失败不中断整体清理**：单实体异常记入 [PurgeResult.failures]（首个异常见
 *   [PurgeResult.firstFailure]）由调用方升级告警，其余实体照常收口；失败条目**留簿**，
 *   供下一轮 purge 重试，避免清簿后失联。
 */
internal class CombatEntityRegistry<E : Any>(
    private val hasDeleted: (E) -> Boolean,
    private val delete: (E) -> Unit,
) {

    /** 一次 purge 的对账结果；[firstFailure] 为首个 delete 异常（无失败为 null）。 */
    data class PurgeResult(
        val registered: Int,
        val deleted: Int,
        val alreadyDeleted: Int,
        val failures: Int,
        val firstFailure: Throwable?,
    )

    private val lock = Any()
    private val entities = Collections.newSetFromMap(IdentityHashMap<E, Boolean>())

    val size: Int get() = synchronized(lock) { entities.size }

    /** 登记实体；簿体越 [PRUNE_THRESHOLD] 时顺手清理已 delete 的失效条目。 */
    fun register(entity: E) {
        synchronized(lock) {
            entities += entity
            if (entities.size >= PRUNE_THRESHOLD) pruneDeletedLocked()
        }
    }

    /** 摘除登记（实体已确认收尸时使用；不触发 delete）。 */
    fun unregister(entity: E) {
        synchronized(lock) { entities -= entity }
    }

    /** 清理簿中已 delete 的失效条目，返回清理数量。 */
    fun pruneDeleted(): Int = synchronized(lock) { pruneDeletedLocked() }

    /** delete 全部未失效实体并清簿；delete 失败的条目留簿，供下一轮 purge 重试。 */
    fun purge(): PurgeResult {
        val snapshot: List<E>
        synchronized(lock) { snapshot = entities.toList() }
        var deleted = 0
        var alreadyDeleted = 0
        var failures = 0
        var firstFailure: Throwable? = null
        val failed = Collections.newSetFromMap(IdentityHashMap<E, Boolean>())
        for (entity in snapshot) {
            if (hasDeleted(entity)) {
                alreadyDeleted++
                continue
            }
            try {
                delete(entity)
                deleted++
            } catch (t: Throwable) {
                failures++
                if (firstFailure == null) firstFailure = t
                failed += entity
            }
        }
        synchronized(lock) { for (entity in snapshot) if (entity !in failed) entities -= entity }
        return PurgeResult(snapshot.size, deleted, alreadyDeleted, failures, firstFailure)
    }

    private fun pruneDeletedLocked(): Int {
        var removed = 0
        val it = entities.iterator()
        while (it.hasNext()) {
            if (hasDeleted(it.next())) {
                it.remove()
                removed++
            }
        }
        return removed
    }

    companion object {
        /** 触发顺手清理的簿体阈值：常驻池/光束钉死实体单场战斗量级在数十，2048 已覆盖事件级特效的极端密度。 */
        const val PRUNE_THRESHOLD = 2048
    }
}
