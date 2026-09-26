package cn.kasuminova.astd.combat.effect.arc

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.combat.effect.arc.geminidem.GeminiDemDifficulty
import cn.kasuminova.astd.combat.effect.arc.geminidem.GeminiDemSyncHandler
import cn.kasuminova.astd.combat.effect.arc.geminidem.GeminiDemSyncHandler.SyncRecord
import cn.kasuminova.astd.combat.effect.arc.geminidem.GeminiDemSyncHandler.WarheadKind
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.MutableStat
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
 * 规格 10 §4.1 用例 1~8（2026-09 同步机制重做：弃一次性爆发伤害，改持续增伤乘区 + 紫色视觉状态）：
 * 同步窗口判定、恰界/越界、同种/异种配对、异源规则、触发清零、难度取值三档与 WARN 路径
 * ——显式注入 registry/now/tuning/onWarn 驱动真实判定逻辑。
 */
class GeminiDemSyncHandlerTest {

    private fun stubEngine(): CombatEngineAPI {
        val engine = mock(CombatEngineAPI::class.java)
        `when`(engine.customData).thenReturn(mutableMapOf())
        return engine
    }

    /** 弹头 demDrone stub：增伤乘区落在 mutableStats.energyWeaponDamageMult 上；紫色视觉写在实体 customData。 */
    private fun stubMissile(id: String, owner: Int): Pair<ShipAPI, MutableStat> {
        val stat = mock(MutableStat::class.java)
        val stats = mock(MutableShipStatsAPI::class.java)
        `when`(stats.energyWeaponDamageMult).thenReturn(stat)
        val ship = mock(ShipAPI::class.java)
        `when`(ship.id).thenReturn(id)
        `when`(ship.owner).thenReturn(owner)
        `when`(ship.mutableStats).thenReturn(stats)
        `when`(ship.customData).thenReturn(mutableMapOf())
        return ship to stat
    }

    /** 注入系数的 DifficultyTuning fake：走真实 ScalingMap 映射（三锚点映射逻辑即被测面）。 */
    private fun fakeTuning(scale: Float): DifficultyTuning = object : DifficultyTuning {
        override val fixedScale: Float = scale
        override fun value(entry: ScalingEntry): Float = entry.map.value(scale, entry.v1, entry.v2, entry.v5)
    }

    private val p1 = Vector2f(10f, 20f)
    private val p2 = Vector2f(30f, 40f)

    @Test
    fun `用例1 同步窗口触发：K(10_0) 后 HE(10_8) 同目标同源，双弹 stats 施加 v2 乘区 2_0 且触发即清`() {
        val engine = stubEngine()
        val target = mock(ShipAPI::class.java)
        `when`(target.id).thenReturn("T1")
        val (missile, stat) = stubMissile("P1", 0)
        val registry = mutableMapOf<String, SyncRecord>()

        assertFalse(
            GeminiDemSyncHandler.recordHit(engine, target, WarheadKind.KINETIC, p1, missile, now = 10.0f, registry = registry),
            "首击只登记不触发",
        )
        assertEquals(WarheadKind.KINETIC, registry["T1"]?.kind)

        assertTrue(
            GeminiDemSyncHandler.recordHit(engine, target, WarheadKind.HE, p2, missile, now = 10.8f, registry = registry),
            "异种配对 Δt=0.8s ≤1s 应触发",
        )
        // 玩家来源（owner==0）固定 v2：1 + 1.0 = 2.0，两枚弹头同实体各施一次
        verify(stat, org.mockito.Mockito.times(2)).modifyMult(GeminiDemDifficulty.SYNC_STAT_MOD_ID, 2.0f)
        assertTrue(registry.isEmpty(), "触发即清，不重复触发")
        assertEquals(1, GeminiDemSyncHandler.syncTriggerCount(engine))
        // 紫色视觉状态：写在弹头 demDrone 实体 customData，到期 = 触发时刻 + SYNC_VISUAL_DURATION
        assertEquals(
            10.8f + GeminiDemDifficulty.SYNC_VISUAL_DURATION,
            missile.customData[GeminiDemDifficulty.SYNC_VISUAL_KEY] as? Float,
        )
    }

    @Test
    fun `用例2 恰界 Δt=1_0s 触发（含边界）`() {
        val engine = stubEngine()
        val target = mock(ShipAPI::class.java)
        `when`(target.id).thenReturn("T1")
        val (missile, _) = stubMissile("P1", 0)
        val registry = mutableMapOf<String, SyncRecord>()

        GeminiDemSyncHandler.recordHit(engine, target, WarheadKind.KINETIC, p1, missile, now = 10.0f, registry = registry)
        assertTrue(
            GeminiDemSyncHandler.recordHit(engine, target, WarheadKind.HE, p2, missile, now = 11.0f, registry = registry),
            "Δt 恰等 1s 窗口含边界触发",
        )
    }

    @Test
    fun `用例3 越界 Δt=1_1s 不触发，次击覆盖为新首击`() {
        val engine = stubEngine()
        val target = mock(ShipAPI::class.java)
        `when`(target.id).thenReturn("T1")
        val (missile, _) = stubMissile("P1", 0)
        val registry = mutableMapOf<String, SyncRecord>()

        GeminiDemSyncHandler.recordHit(engine, target, WarheadKind.KINETIC, p1, missile, now = 10.0f, registry = registry)
        assertFalse(
            GeminiDemSyncHandler.recordHit(engine, target, WarheadKind.HE, p2, missile, now = 11.1f, registry = registry),
            "Δt=1.1s 越界不触发",
        )
        val record = registry["T1"]
        assertEquals(WarheadKind.HE, record?.kind, "次击覆盖为新首击记录")
        assertEquals(11.1f, record?.hitTime)
    }

    @Test
    fun `用例4 同种弹头配对不触发，记录覆盖`() {
        val engine = stubEngine()
        val target = mock(ShipAPI::class.java)
        `when`(target.id).thenReturn("T1")
        val (missile, _) = stubMissile("P1", 0)
        val registry = mutableMapOf<String, SyncRecord>()

        GeminiDemSyncHandler.recordHit(engine, target, WarheadKind.KINETIC, p1, missile, now = 10.0f, registry = registry)
        assertFalse(
            GeminiDemSyncHandler.recordHit(engine, target, WarheadKind.KINETIC, p2, missile, now = 10.5f, registry = registry),
            "同种弹头配对不触发",
        )
        assertEquals(10.5f, registry["T1"]?.hitTime, "记录被覆盖为最新首击")
    }

    @Test
    fun `用例5 不同目标各自登记，互不触发`() {
        val engine = stubEngine()
        val t1 = mock(ShipAPI::class.java)
        `when`(t1.id).thenReturn("T1")
        val t2 = mock(ShipAPI::class.java)
        `when`(t2.id).thenReturn("T2")
        val (missile, _) = stubMissile("P1", 0)
        val registry = mutableMapOf<String, SyncRecord>()

        assertFalse(GeminiDemSyncHandler.recordHit(engine, t1, WarheadKind.KINETIC, p1, missile, now = 10.0f, registry = registry))
        assertFalse(GeminiDemSyncHandler.recordHit(engine, t2, WarheadKind.HE, p2, missile, now = 10.2f, registry = registry))
        assertEquals(2, registry.size, "两目标各有首击记录")
        assertEquals(2, GeminiDemSyncHandler.hitRegisteredCount(engine))
        assertEquals(0, GeminiDemSyncHandler.syncTriggerCount(engine))
    }

    @Test
    fun `用例6 异源可判不触发（两 sourceId 均非空且不同）`() {
        val engine = stubEngine()
        val target = mock(ShipAPI::class.java)
        `when`(target.id).thenReturn("T1")
        val (missileA, _) = stubMissile("PA", 0)
        val (missileB, _) = stubMissile("PB", 0)
        val registry = mutableMapOf<String, SyncRecord>()

        GeminiDemSyncHandler.recordHit(engine, target, WarheadKind.KINETIC, p1, missileA, now = 10.0f, registry = registry)
        assertFalse(
            GeminiDemSyncHandler.recordHit(engine, target, WarheadKind.HE, p2, missileB, now = 10.5f, registry = registry),
            "异源可判严格不触发",
        )
        assertEquals("PB", (registry["T1"]?.source as? ShipAPI)?.id, "未触发覆盖为新首击")
    }

    @Test
    fun `用例7 触发后清零：第三击不重复触发（须重新配对）`() {
        val engine = stubEngine()
        val target = mock(ShipAPI::class.java)
        `when`(target.id).thenReturn("T1")
        val (missile, stat) = stubMissile("P1", 0)
        val registry = mutableMapOf<String, SyncRecord>()

        GeminiDemSyncHandler.recordHit(engine, target, WarheadKind.KINETIC, p1, missile, now = 10.0f, registry = registry)
        assertTrue(GeminiDemSyncHandler.recordHit(engine, target, WarheadKind.HE, p2, missile, now = 10.5f, registry = registry))
        assertFalse(
            GeminiDemSyncHandler.recordHit(engine, target, WarheadKind.KINETIC, p1, missile, now = 10.7f, registry = registry),
            "触发后 registry 已清，第三击只是新首击",
        )
        verify(stat, org.mockito.Mockito.times(2)).modifyMult(GeminiDemDifficulty.SYNC_STAT_MOD_ID, 2.0f)
    }

    @Test
    fun `用例8 难度取值：玩家恒 v2、敌版轨一三档、source 缺失 WARN 取 v2`() {
        // 玩家（owner==0）：tuning fake 给破晓档也恒取 v2（1 + 1.0 = 2.0）
        run {
            val engine = stubEngine()
            val target = mock(ShipAPI::class.java)
            `when`(target.id).thenReturn("T1")
            val (missile, stat) = stubMissile("P1", 0)
            val registry = mutableMapOf<String, SyncRecord>()
            GeminiDemSyncHandler.recordHit(engine, target, WarheadKind.KINETIC, p1, missile, tuning = fakeTuning(5f), now = 10.0f, registry = registry)
            GeminiDemSyncHandler.recordHit(engine, target, WarheadKind.HE, p2, missile, tuning = fakeTuning(5f), now = 10.5f, registry = registry)
            verify(stat, org.mockito.Mockito.times(2)).modifyMult(GeminiDemDifficulty.SYNC_STAT_MOD_ID, 2.0f)
        }
        // 敌版轨一三档：1 + 0.5 = 1.5 / 1 + 1.0 = 2.0 / 1 + 2.5 = 3.5
        val expected = listOf(1f to 1.5f, 2f to 2.0f, 5f to 3.5f)
        for ((scale, mult) in expected) {
            val engine = stubEngine()
            val target = mock(ShipAPI::class.java)
            `when`(target.id).thenReturn("T1")
            val (missile, stat) = stubMissile("E1", 1)
            val registry = mutableMapOf<String, SyncRecord>()
            GeminiDemSyncHandler.recordHit(
                engine,
                target,
                WarheadKind.KINETIC,
                p1,
                missile,
                tuning = fakeTuning(scale),
                now = 10.0f,
                registry = registry
            )
            GeminiDemSyncHandler.recordHit(engine, target, WarheadKind.HE, p2, missile, tuning = fakeTuning(scale), now = 10.5f, registry = registry)
            verify(stat, org.mockito.Mockito.times(2)).modifyMult(GeminiDemDifficulty.SYNC_STAT_MOD_ID, mult)
        }
        // source 为 null：取 v2 且 WARN 被记录（来源解析失败 + 两侧实体不可施加各一条）
        run {
            val engine = stubEngine()
            val target = mock(ShipAPI::class.java)
            `when`(target.id).thenReturn("T1")
            val warns = mutableListOf<String>()
            val registry = mutableMapOf<String, SyncRecord>()
            GeminiDemSyncHandler.recordHit(engine, target, WarheadKind.KINETIC, p1, null, now = 10.0f, registry = registry, onWarn = { warns += it })
            GeminiDemSyncHandler.recordHit(engine, target, WarheadKind.HE, p2, null, now = 10.5f, registry = registry, onWarn = { warns += it })
            assertEquals(1, warns.size, "source 解析失败 WARN 恰好一次（实体侧 WARN 走真实 log，非注入通道）")
            assertTrue(warns[0].contains("source"), "WARN 含来源解析失败说明")
        }
    }

    @Test
    fun `用例7补 遥测：触发后 mult 遥测写入施加乘区（玩家 v2=2_0）`() {
        val engine = stubEngine()
        val target = mock(ShipAPI::class.java)
        `when`(target.id).thenReturn("T1")
        val (missile, _) = stubMissile("P1", 0)
        val registry = mutableMapOf<String, SyncRecord>()
        GeminiDemSyncHandler.recordHit(engine, target, WarheadKind.KINETIC, p1, missile, now = 10.0f, registry = registry)
        GeminiDemSyncHandler.recordHit(engine, target, WarheadKind.HE, p2, missile, now = 10.5f, registry = registry)
        assertEquals(2.0f, engine.customData[GeminiDemSyncHandler.TELEMETRY_SYNC_LAST_MULT] as? Float)
        assertNull(registry["T1"])
    }
}
