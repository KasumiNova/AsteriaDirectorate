package cn.kasuminova.astd.combat.effect.arc

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.combat.effect.arc.GeminiDemSyncHandler.SyncRecord
import cn.kasuminova.astd.combat.effect.arc.GeminiDemSyncHandler.WarheadKind
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.ShipAPI
import org.lwjgl.util.vector.Vector2f
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 规格 10 §4.1 用例 1~8：同步窗口判定、恰界/越界、同种/异种配对、异源规则、
 * 触发清零、难度取值三档与 WARN 路径——显式注入 registry/now/tuning/onWarn 驱动真实判定逻辑。
 */
class GeminiDemSyncHandlerTest {

    private fun stubEngine(): CombatEngineAPI {
        val engine = mock(CombatEngineAPI::class.java)
        `when`(engine.customData).thenReturn(mutableMapOf())
        return engine
    }

    private fun stubShip(id: String, owner: Int): ShipAPI {
        val ship = mock(ShipAPI::class.java)
        `when`(ship.id).thenReturn(id)
        `when`(ship.owner).thenReturn(owner)
        return ship
    }

    /** 注入系数的 DifficultyTuning fake：走真实 ScalingMap 映射（三锚点映射逻辑即被测面）。 */
    private fun fakeTuning(scale: Float): DifficultyTuning = object : DifficultyTuning {
        override val fixedScale: Float = scale
        override fun value(entry: ScalingEntry): Float = entry.map.value(scale, entry.v1, entry.v2, entry.v5)
    }

    private val p1 = Vector2f(10f, 20f)
    private val p2 = Vector2f(30f, 40f)

    @Test
    fun `用例1 同步窗口触发：K(10_0) 后 HE(10_8) 同目标同源，applyDamage 收到 2500x0_4375 ENERGY 且触发即清`() {
        val engine = stubEngine()
        val target = stubShip("T1", 1)
        val source = stubShip("P1", 0)
        val registry = mutableMapOf<String, SyncRecord>()

        assertFalse(
            GeminiDemSyncHandler.recordHit(engine, target, WarheadKind.KINETIC, p1, source, now = 10.0f, registry = registry),
            "首击只登记不触发",
        )
        assertEquals(WarheadKind.KINETIC, registry["T1"]?.kind)

        assertTrue(
            GeminiDemSyncHandler.recordHit(engine, target, WarheadKind.HE, p2, source, now = 10.8f, registry = registry),
            "异种配对 Δt=0.8s ≤1s 应触发",
        )
        // 玩家来源（owner==0）固定 v2：2500 × 0.4375 = 1093.75
        verify(engine).applyDamage(target, p2, 1093.75f, DamageType.ENERGY, 0f, true, false, source, true)
        assertTrue(registry.isEmpty(), "触发即清，不重复触发")
        assertEquals(1, GeminiDemSyncHandler.syncTriggerCount(engine))
    }

    @Test
    fun `用例2 恰界 Δt=1_0s 触发（含边界）`() {
        val engine = stubEngine()
        val target = stubShip("T1", 1)
        val source = stubShip("P1", 0)
        val registry = mutableMapOf<String, SyncRecord>()

        GeminiDemSyncHandler.recordHit(engine, target, WarheadKind.KINETIC, p1, source, now = 10.0f, registry = registry)
        assertTrue(
            GeminiDemSyncHandler.recordHit(engine, target, WarheadKind.HE, p2, source, now = 11.0f, registry = registry),
            "Δt 恰等 1s 窗口含边界触发",
        )
    }

    @Test
    fun `用例3 越界 Δt=1_1s 不触发，次击覆盖为新首击`() {
        val engine = stubEngine()
        val target = stubShip("T1", 1)
        val source = stubShip("P1", 0)
        val registry = mutableMapOf<String, SyncRecord>()

        GeminiDemSyncHandler.recordHit(engine, target, WarheadKind.KINETIC, p1, source, now = 10.0f, registry = registry)
        assertFalse(
            GeminiDemSyncHandler.recordHit(engine, target, WarheadKind.HE, p2, source, now = 11.1f, registry = registry),
            "Δt=1.1s 越界不触发",
        )
        val record = registry["T1"]
        assertEquals(WarheadKind.HE, record?.kind, "次击覆盖为新首击记录")
        assertEquals(11.1f, record?.hitTime)
    }

    @Test
    fun `用例4 同种弹头配对不触发，记录覆盖`() {
        val engine = stubEngine()
        val target = stubShip("T1", 1)
        val source = stubShip("P1", 0)
        val registry = mutableMapOf<String, SyncRecord>()

        GeminiDemSyncHandler.recordHit(engine, target, WarheadKind.KINETIC, p1, source, now = 10.0f, registry = registry)
        assertFalse(
            GeminiDemSyncHandler.recordHit(engine, target, WarheadKind.KINETIC, p2, source, now = 10.5f, registry = registry),
            "同种弹头配对不触发",
        )
        assertEquals(10.5f, registry["T1"]?.hitTime, "记录被覆盖为最新首击")
    }

    @Test
    fun `用例5 不同目标各自登记，互不触发`() {
        val engine = stubEngine()
        val t1 = stubShip("T1", 1)
        val t2 = stubShip("T2", 1)
        val source = stubShip("P1", 0)
        val registry = mutableMapOf<String, SyncRecord>()

        assertFalse(GeminiDemSyncHandler.recordHit(engine, t1, WarheadKind.KINETIC, p1, source, now = 10.0f, registry = registry))
        assertFalse(GeminiDemSyncHandler.recordHit(engine, t2, WarheadKind.HE, p2, source, now = 10.2f, registry = registry))
        assertEquals(2, registry.size, "两目标各有首击记录")
        assertEquals(2, GeminiDemSyncHandler.hitRegisteredCount(engine))
        assertEquals(0, GeminiDemSyncHandler.syncTriggerCount(engine))
    }

    @Test
    fun `用例6 异源可判不触发（两 sourceId 均非空且不同）`() {
        val engine = stubEngine()
        val target = stubShip("T1", 1)
        val sourceA = stubShip("PA", 0)
        val sourceB = stubShip("PB", 0)
        val registry = mutableMapOf<String, SyncRecord>()

        GeminiDemSyncHandler.recordHit(engine, target, WarheadKind.KINETIC, p1, sourceA, now = 10.0f, registry = registry)
        assertFalse(
            GeminiDemSyncHandler.recordHit(engine, target, WarheadKind.HE, p2, sourceB, now = 10.5f, registry = registry),
            "异源可判严格不触发",
        )
        assertEquals("PB", registry["T1"]?.sourceId, "未触发覆盖为新首击")
    }

    @Test
    fun `用例7 触发后清零：第三击不重复触发（须重新配对）`() {
        val engine = stubEngine()
        val target = stubShip("T1", 1)
        val source = stubShip("P1", 0)
        val registry = mutableMapOf<String, SyncRecord>()

        GeminiDemSyncHandler.recordHit(engine, target, WarheadKind.KINETIC, p1, source, now = 10.0f, registry = registry)
        assertTrue(GeminiDemSyncHandler.recordHit(engine, target, WarheadKind.HE, p2, source, now = 10.5f, registry = registry))
        assertFalse(
            GeminiDemSyncHandler.recordHit(engine, target, WarheadKind.KINETIC, p1, source, now = 10.7f, registry = registry),
            "触发后 registry 已清，第三击只是新首击",
        )
        verify(engine).applyDamage(target, p2, 1093.75f, DamageType.ENERGY, 0f, true, false, source, true)
    }

    @Test
    fun `用例8 难度取值：玩家恒 v2、敌版轨一三档、source 缺失 WARN 取 v2`() {
        // 玩家（owner==0）：tuning fake 给破晓档也恒取 v2
        run {
            val engine = stubEngine()
            val target = stubShip("T1", 1)
            val player = stubShip("P1", 0)
            val registry = mutableMapOf<String, SyncRecord>()
            GeminiDemSyncHandler.recordHit(engine, target, WarheadKind.KINETIC, p1, player, tuning = fakeTuning(5f), now = 10.0f, registry = registry)
            GeminiDemSyncHandler.recordHit(engine, target, WarheadKind.HE, p2, player, tuning = fakeTuning(5f), now = 10.5f, registry = registry)
            verify(engine).applyDamage(target, p2, 1093.75f, DamageType.ENERGY, 0f, true, false, player, true)
        }
        // 敌版轨一三档：v1 625 / v2 1093.75 / v5 2500
        val expected = listOf(1f to 625f, 2f to 1093.75f, 5f to 2500f)
        for ((scale, damage) in expected) {
            val engine = stubEngine()
            val target = stubShip("T1", 1)
            val enemy = stubShip("E1", 1)
            val registry = mutableMapOf<String, SyncRecord>()
            GeminiDemSyncHandler.recordHit(engine, target, WarheadKind.KINETIC, p1, enemy, tuning = fakeTuning(scale), now = 10.0f, registry = registry)
            GeminiDemSyncHandler.recordHit(engine, target, WarheadKind.HE, p2, enemy, tuning = fakeTuning(scale), now = 10.5f, registry = registry)
            verify(engine).applyDamage(target, p2, damage, DamageType.ENERGY, 0f, true, false, enemy, true)
        }
        // source 为 null：取 v2 且 WARN 被记录
        run {
            val engine = stubEngine()
            val target = stubShip("T1", 1)
            val warns = mutableListOf<String>()
            val registry = mutableMapOf<String, SyncRecord>()
            GeminiDemSyncHandler.recordHit(engine, target, WarheadKind.KINETIC, p1, null, now = 10.0f, registry = registry, onWarn = { warns += it })
            GeminiDemSyncHandler.recordHit(engine, target, WarheadKind.HE, p2, null, now = 10.5f, registry = registry, onWarn = { warns += it })
            verify(engine).applyDamage(target, p2, 1093.75f, DamageType.ENERGY, 0f, true, false, null, true)
            assertEquals(1, warns.size, "source 解析失败 WARN 恰好一次")
            assertTrue(warns[0].contains("source"), "WARN 含来源解析失败说明")
        }
    }

    @Test
    fun `用例7补 遥测：触发后 mult 遥测写入 v2 值`() {
        val engine = stubEngine()
        val target = stubShip("T1", 1)
        val source = stubShip("P1", 0)
        val registry = mutableMapOf<String, SyncRecord>()
        GeminiDemSyncHandler.recordHit(engine, target, WarheadKind.KINETIC, p1, source, now = 10.0f, registry = registry)
        GeminiDemSyncHandler.recordHit(engine, target, WarheadKind.HE, p2, source, now = 10.5f, registry = registry)
        assertEquals(0.4375f, engine.customData[GeminiDemSyncHandler.TELEMETRY_SYNC_LAST_MULT] as? Float)
        assertNull(registry["T1"])
    }
}
