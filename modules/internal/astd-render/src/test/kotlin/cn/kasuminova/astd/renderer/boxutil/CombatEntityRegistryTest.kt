package cn.kasuminova.astd.renderer.boxutil

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CombatEntityRegistryTest {

    /** 假实体：仅携带 delete 状态，验证登记簿逻辑不依赖 BoxUtil/GL。 */
    private class FakeEntity {
        var deleted = false
        var deleteFailures = 0
    }

    private fun newRegistry(failOnDelete: MutableSet<FakeEntity> = mutableSetOf()) =
        CombatEntityRegistry<FakeEntity>(
            hasDeleted = { it.deleted },
            delete = { e ->
                if (e in failOnDelete) {
                    e.deleteFailures++
                    throw RuntimeException("delete failed")
                }
                e.deleted = true
            },
        )

    @Test
    fun `purge deletes all live entities and clears the registry`() {
        val registry = newRegistry()
        val live = FakeEntity()
        val liveTwo = FakeEntity()
        val alreadyDead = FakeEntity().also { it.deleted = true }
        registry.register(live)
        registry.register(liveTwo)
        registry.register(alreadyDead)

        val result = registry.purge()

        assertTrue(live.deleted)
        assertTrue(liveTwo.deleted)
        assertEquals(3, result.registered)
        assertEquals(2, result.deleted)
        assertEquals(1, result.alreadyDeleted)
        assertEquals(0, result.failures)
        assertEquals(0, registry.size)
    }

    @Test
    fun `purge continues past a failing entity and retains it for the next round`() {
        val failing = FakeEntity()
        val registry = newRegistry(failOnDelete = mutableSetOf(failing))
        val live = FakeEntity()
        registry.register(failing)
        registry.register(live)

        val result = registry.purge()

        assertTrue(live.deleted)
        assertEquals(1, failing.deleteFailures)
        assertEquals(1, result.deleted)
        assertEquals(1, result.failures)
        assertTrue(result.firstFailure is RuntimeException)
        // 失败条目留簿，成功条目出簿
        assertEquals(1, registry.size)
    }

    @Test
    fun `a retained failure is retried and deleted by a later purge`() {
        val failing = FakeEntity()
        val failSet = mutableSetOf(failing)
        val registry = newRegistry(failOnDelete = failSet)
        registry.register(failing)

        registry.purge()
        assertEquals(1, registry.size)
        assertTrue(!failing.deleted)

        failSet.remove(failing)
        val retry = registry.purge()

        assertTrue(failing.deleted)
        assertEquals(1, retry.deleted)
        assertEquals(0, retry.failures)
        assertEquals(null, retry.firstFailure)
        assertEquals(0, registry.size)
    }

    @Test
    fun `unregister removes the entity without deleting it`() {
        val registry = newRegistry()
        val entity = FakeEntity()
        registry.register(entity)

        registry.unregister(entity)

        assertEquals(0, registry.size)
        assertTrue(!entity.deleted)
        assertEquals(0, registry.purge().registered)
    }

    @Test
    fun `registering the same entity twice keeps a single entry`() {
        val registry = newRegistry()
        val entity = FakeEntity()
        registry.register(entity)
        registry.register(entity)

        assertEquals(1, registry.size)
        assertEquals(1, registry.purge().deleted)
    }

    @Test
    fun `pruneDeleted drops only deleted entries`() {
        val registry = newRegistry()
        val live = FakeEntity()
        val dead = FakeEntity().also { it.deleted = true }
        registry.register(live)
        registry.register(dead)

        assertEquals(1, registry.pruneDeleted())
        assertEquals(1, registry.size)
        assertEquals(1, registry.purge().deleted)
    }

    @Test
    fun `register self-prunes deleted entries once the threshold is exceeded`() {
        val registry = newRegistry()
        repeat(CombatEntityRegistry.PRUNE_THRESHOLD) {
            registry.register(FakeEntity().also { e -> e.deleted = true })
        }
        val live = FakeEntity()

        registry.register(live)

        assertEquals(1, registry.size)
        assertEquals(1, registry.purge().deleted)
        assertTrue(live.deleted)
    }
}
