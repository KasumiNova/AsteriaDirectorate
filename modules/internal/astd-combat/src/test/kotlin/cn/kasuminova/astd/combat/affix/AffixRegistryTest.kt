package cn.kasuminova.astd.combat.affix

import cn.kasuminova.astd.combat.affix.AffixRegistry.AffixDef
import cn.kasuminova.astd.combat.affix.AffixRegistry.AffixType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [AffixRegistry] v3（affixes.md v3.0 / D20）纯逻辑验证：
 * 17 条词缀数据完整性、S/M/R 数量规则、R 开放口径与低权重、互斥表、种子确定性。
 */
class AffixRegistryTest {

    private fun pick(
        k: Float,
        seed: Long,
        rAllowed: Boolean,
        unlocked: Set<String> = AffixRegistry.initialUnlock(),
    ): List<AffixDef> = AffixRegistry.pickAffixes(
        unlockedIds = unlocked,
        mainCompleted = 0,
        threatTier = 1,
        k = k,
        seed = seed,
        rTierAllowed = rAllowed,
    )

    private fun List<AffixDef>.countOf(type: AffixType): Int = count { it.type == type }

    @Test
    fun `注册表为 17 条且 id 编目号 hullmod 均唯一`() {
        val all = AffixRegistry.all()
        assertEquals(17, all.size)
        assertEquals(17, all.map { it.id }.toSet().size)
        assertEquals(17, all.map { it.catalog }.toSet().size)
        assertEquals(17, all.map { it.hullModId }.toSet().size)
        assertEquals(17, all.map { it.nameKey }.toSet().size)
        for (def in all) {
            assertEquals("astd_affix_" + def.id, def.hullModId, "hullModId 约定不符: ${def.id}")
            assertTrue(def.nameKey.isNotBlank(), "nameKey 为空: ${def.id}")
        }
    }

    @Test
    fun `编目号覆盖 S-01 至 S-08 M-09 至 M-14 R-15 至 R-17`() {
        val expected = buildSet {
            for (i in 1..8) add("S-%02d".format(i))
            for (i in 9..14) add("M-%02d".format(i))
            for (i in 15..17) add("R-%02d".format(i))
        }
        assertEquals(expected, AffixRegistry.all().map { it.catalog }.toSet())
    }

    @Test
    fun `类型分布 S8 M6 R3 且 tier 与类型层级一致`() {
        val byType = AffixRegistry.all().groupBy { it.type }
        assertEquals(8, byType.getValue(AffixType.S).size)
        assertEquals(6, byType.getValue(AffixType.M).size)
        assertEquals(3, byType.getValue(AffixType.R).size)
        assertEquals(1, AffixType.S.tier)
        assertEquals(2, AffixType.M.tier)
        assertEquals(3, AffixType.R.tier)
        for (def in AffixRegistry.all()) {
            assertEquals(def.type.tier, def.tier, "tier 与类型层级不一致: ${def.id}")
        }
    }

    @Test
    fun `相位限定词缀恰为调谐 降频 深潜器三条`() {
        val phaseOnly = AffixRegistry.all().filter { it.phaseOnly }.map { it.id }.toSet()
        assertEquals(setOf("phase_coil_tuning", "phase_coil_detuning", "pspace_diver"), phaseOnly)
    }

    @Test
    fun `互斥表与设计三对一致且对称`() {
        assertTrue(AffixRegistry.isMutuallyExclusive("cryo_flux_network", "flux_coil_expansion"))
        assertTrue(AffixRegistry.isMutuallyExclusive("phase_coil_tuning", "phase_coil_detuning"))
        assertTrue(AffixRegistry.isMutuallyExclusive("grid_deepening", "flux_coil_expansion"))
        // 对称性
        assertTrue(AffixRegistry.isMutuallyExclusive("flux_coil_expansion", "grid_deepening"))
        // 设计文档仅列三对：其余组合不互斥
        assertFalse(AffixRegistry.isMutuallyExclusive("ironclad_plating", "flux_coil_expansion"))
        assertFalse(AffixRegistry.isMutuallyExclusive("grid_deepening", "cryo_flux_network"))
        assertFalse(AffixRegistry.isMutuallyExclusive("ironclad_plating", "ironclad_plating"))
    }

    @Test
    fun `初始解锁与主线解锁覆盖全部 17 条`() {
        val allIds = AffixRegistry.all().map { it.id }.toSet()
        assertEquals(allIds, AffixRegistry.initialUnlock())
        assertEquals(allIds, AffixRegistry.unlockForMainCompleted(0))
        assertEquals(allIds, AffixRegistry.unlockForMainCompleted(99))
    }

    @Test
    fun `id 与 hullModId 反查一致且未知 id 返回 null`() {
        for (def in AffixRegistry.all()) {
            assertEquals(def, AffixRegistry.getById(def.id))
            assertEquals(def, AffixRegistry.getByHullModId(def.hullModId))
        }
        assertNull(AffixRegistry.getById("astd_affix_not_exists"))
        assertNull(AffixRegistry.getByHullModId("not_exists"))
    }

    @Test
    fun `兼容签名调用下 S 2至4 M 1至2 且绝无 R`() {
        // 与 FleetComposer 相同的调用形态（不传 rTierAllowed）。
        for (seed in 0L until 200L) {
            for (k in listOf(0f, 0.3f, 0.7f, 1f)) {
                val result = AffixRegistry.pickAffixes(
                    unlockedIds = AffixRegistry.initialUnlock(),
                    mainCompleted = 0,
                    threatTier = 1,
                    k = k,
                    seed = seed,
                )
                val s = result.countOf(AffixType.S)
                val m = result.countOf(AffixType.M)
                val r = result.countOf(AffixType.R)
                assertTrue(s in 2..4, "S 数量越界: s=$s k=$k seed=$seed")
                assertTrue(m in 1..2, "M 数量越界: m=$m k=$k seed=$seed")
                assertEquals(0, r, "R 未开放却出现 R: k=$k seed=$seed")
            }
        }
    }

    @Test
    fun `数量上下限由难度系数决定 k0 取下限 k1 取上限`() {
        for (seed in 0L until 50L) {
            val low = pick(0f, seed, rAllowed = false)
            assertEquals(2, low.countOf(AffixType.S), "k=0 时 S 应取下限 2: seed=$seed")
            assertEquals(1, low.countOf(AffixType.M), "k=0 时 M 应取下限 1: seed=$seed")

            val high = pick(1f, seed, rAllowed = false)
            assertEquals(4, high.countOf(AffixType.S), "k=1 时 S 应取上限 4: seed=$seed")
            assertEquals(2, high.countOf(AffixType.M), "k=1 时 M 应取上限 2: seed=$seed")
        }
    }

    @Test
    fun `R 开放时数量 0至2 且低权重随 k 上升`() {
        fun rPresenceRate(k: Float, seeds: LongRange): Double {
            var hits = 0
            var total = 0
            for (seed in seeds) {
                val result = pick(k, seed, rAllowed = true)
                val rCount = result.countOf(AffixType.R)
                assertTrue(rCount in 0..2, "R 数量越界: r=$rCount k=$k seed=$seed")
                if (rCount > 0) hits++
                total++
            }
            return hits.toDouble() / total
        }

        val lowRate = rPresenceRate(0f, 0L until 500L)
        val highRate = rPresenceRate(1f, 0L until 500L)
        // 首条概率 = 0.25 + 0.45k（叠加互斥损耗只会更低）：k=0 显著低于 k=1，且 k=1 也非必出。
        assertTrue(lowRate < 0.35, "k=0 时 R 权重过高: $lowRate")
        assertTrue(highRate in 0.45..0.9, "k=1 时 R 命中率偏离设计权重: $highRate")
        assertTrue(highRate > lowRate, "R 权重未随 k 上升: low=$lowRate high=$highRate")
    }

    @Test
    fun `R 开放且高 k 时能抽到两条 R`() {
        val maxR = (0L until 1000L).maxOf { pick(1f, it, rAllowed = true).countOf(AffixType.R) }
        assertEquals(2, maxR, "高 k 下 R 应可达到搭配表上限 2")
    }

    @Test
    fun `互斥对在任何种子下不共现`() {
        val pairs = listOf(
            setOf("cryo_flux_network", "flux_coil_expansion"),
            setOf("phase_coil_tuning", "phase_coil_detuning"),
            setOf("grid_deepening", "flux_coil_expansion"),
        )
        for (seed in 0L until 2000L) {
            val ids = pick(1f, seed, rAllowed = true).map { it.id }.toSet()
            for (pair in pairs) {
                assertFalse(ids.containsAll(pair), "互斥对共现: $pair seed=$seed")
            }
        }
    }

    @Test
    fun `同 id 不重复且相同种子结果确定`() {
        for (seed in 0L until 100L) {
            val result = pick(1f, seed, rAllowed = true)
            assertEquals(result.size, result.map { it.id }.toSet().size, "同 id 重复: seed=$seed")
            assertEquals(result, pick(1f, seed, rAllowed = true), "相同种子结果不一致: seed=$seed")
        }
    }

    @Test
    fun `不同种子产生不同组合`() {
        val distinct = (0L until 50L)
            .map { pick(1f, it, rAllowed = true).map(AffixDef::id) }
            .toSet()
        assertTrue(distinct.size > 1, "50 个种子抽取结果完全相同")
    }

    @Test
    fun `未解锁词缀不进入抽取 空池返回空`() {
        val subset = setOf("ironclad_plating", "polarized_shield", "engine_overclock")
        for (seed in 0L until 100L) {
            val result = pick(1f, seed, rAllowed = true, unlocked = subset)
            assertTrue(result.isNotEmpty(), "解锁子集非空但抽取为空: seed=$seed")
            assertTrue(result.all { it.id in subset }, "抽中未解锁词缀: seed=$seed")
            assertTrue(result.size <= subset.size, "抽取数量超过解锁池: seed=$seed")
        }
        assertTrue(pick(1f, 42L, rAllowed = true, unlocked = emptySet()).isEmpty())
    }

    @Test
    fun `解锁池不足时按池内可抽数量返回`() {
        // 仅 1 条 S 解锁：S 目标 2~4 无法凑满，应返回池内仅有的 1 条而非报错或混入锁定词缀。
        val unlocked = setOf("ironclad_plating")
        for (seed in 0L until 50L) {
            val result = pick(0f, seed, rAllowed = false, unlocked = unlocked)
            assertEquals(listOf("ironclad_plating"), result.map { it.id }, "seed=$seed")
        }
    }

    @Test
    fun `k 超界按 0 与 1 钳制`() {
        for (seed in 0L until 20L) {
            assertEquals(pick(0f, seed, rAllowed = false), pick(-3f, seed, rAllowed = false), "k 下界未钳制: seed=$seed")
            assertEquals(pick(1f, seed, rAllowed = false), pick(99f, seed, rAllowed = false), "k 上界未钳制: seed=$seed")
        }
    }

    @Test
    fun `getById 命中编目数据与注册表条目一致`() {
        val plating = AffixRegistry.getById("ironclad_plating")
        assertNotNull(plating)
        assertEquals("S-01", plating.catalog)
        assertEquals(AffixType.S, plating.type)
        assertFalse(plating.phaseOnly)

        val singularity = AffixRegistry.getById("singularity_drive")
        assertNotNull(singularity)
        assertEquals("R-17", singularity.catalog)
        assertEquals(AffixType.R, singularity.type)
        assertEquals(3, singularity.tier)
    }
}
