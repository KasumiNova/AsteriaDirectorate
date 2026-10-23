package cn.kasuminova.astd.renderer.boxutil

import java.util.Collections
import java.util.IdentityHashMap

// BoxUtil 的全局渲染队列不会可靠地随战斗清理，必须保留实体强引用以便确定性删除。
internal class CombatEntityRegistry<E : Any>(
    private val hasDeleted: (E) -> Boolean,
    private val delete: (E) -> Unit,
) {

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

    fun register(entity: E) {
        synchronized(lock) {
            entities += entity
            if (entities.size >= PRUNE_THRESHOLD) pruneDeletedLocked()
        }
    }

    fun unregister(entity: E) {
        synchronized(lock) { entities -= entity }
    }

    fun pruneDeleted(): Int = synchronized(lock) { pruneDeletedLocked() }

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
        const val PRUNE_THRESHOLD = 2048
    }
}
