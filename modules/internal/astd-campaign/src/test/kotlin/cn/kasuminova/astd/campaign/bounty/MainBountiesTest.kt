package cn.kasuminova.astd.campaign.bounty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * MainBounties 注册表完整性：编号唯一性、FP 预设对照、gating 链无环、
 * 结清组归属、R 型词缀仅三/四章开放、危险等级与报酬区间口径。
 */
class MainBountiesTest {

    @Test
    fun `key 与文书编号全局唯一`() {
        val keys = MainBounties.all.map { it.key }
        assertEquals(keys.size, keys.toSet().size, "bounty key 重复：$keys")
        val serials = MainBounties.all.map { it.serial }
        assertEquals(serials.size, serials.toSet().size, "文书编号重复：$serials")
        // 全部带模组前缀，与动态赏金同命名空间
        MainBounties.all.forEach { assertTrue(it.key.startsWith(BountyKeys.BOUNTY_KEY_PREFIX), it.key) }
    }

    @Test
    fun `FP 预设对照定稿文档`() {
        val expected = mapOf(
            MainBounties.KEY_PROLOGUE to listOf(80),
            MainBounties.KEY_YJ_1102 to listOf(120),
            MainBounties.KEY_YJ_1103 to listOf(120),
            MainBounties.KEY_YJ_1198 to listOf(200),
            MainBounties.KEY_YJ_1201 to listOf(200),
            MainBounties.KEY_YJ_1204 to listOf(200),
            MainBounties.KEY_JJ_0007 to listOf(300),
            MainBounties.KEY_XC_0216 to listOf(400),
            MainBounties.KEY_XC_0217 to listOf(600),
            MainBounties.KEY_XC_0221 to listOf(800),
            MainBounties.KEY_ZW_0309 to listOf(300, 300, 300, 800),
            MainBounties.KEY_ZX_1001 to listOf(1000),
            MainBounties.KEY_ZX_0344 to listOf(1200),
            MainBounties.KEY_ZX_0002 to listOf(1500),
            MainBounties.KEY_ZQ_0001 to listOf(1800, 2200, 2800),
        )
        assertEquals(expected.size, MainBounties.all.size, "主线条目数量变动需同步本表")
        for ((key, fps) in expected) {
            val def = assertNotNull(MainBounties.byKey(key), key)
            assertEquals(fps, def.stages.map { it.baselineFP }, key)
        }
    }

    @Test
    fun `八个结清组且工单归属与章节一致`() {
        assertEquals(8, MainBounties.groups.size)
        val groupIds = MainBounties.groups.map { it.id }
        assertEquals(8, groupIds.toSet().size, "结清组 id 重复")

        // 每张工单归属一个已声明的组，且章节一致
        for (def in MainBounties.all) {
            val group = assertNotNull(MainBounties.group(def.groupId), def.key)
            assertEquals(def.chapter, group.chapter, def.key)
        }
        // 每组至少一张工单，且全部被覆盖
        for (group in MainBounties.groups) {
            assertTrue(MainBounties.ordersOfGroup(group.id).isNotEmpty(), group.id)
        }

        // 一章三批结构：2/3/1 单
        assertEquals(2, MainBounties.ordersOfGroup(MainBounties.GROUP_CH1_BATCH1).size)
        assertEquals(3, MainBounties.ordersOfGroup(MainBounties.GROUP_CH1_BATCH2).size)
        assertEquals(1, MainBounties.ordersOfGroup(MainBounties.GROUP_CH1_BATCH3).size)
        // 二章双线：XC 3 单 + ZW 单工单四阶段
        assertEquals(3, MainBounties.ordersOfGroup(MainBounties.GROUP_CH2_XC).size)
        assertEquals(4, MainBounties.ordersOfGroup(MainBounties.GROUP_CH2_ZW).single().stages.size)
        // 四章 ZQ 单工单三阶段
        assertEquals(3, MainBounties.ordersOfGroup(MainBounties.GROUP_CH4).single().stages.size)
    }

    @Test
    fun `结清奖金基数对照定稿`() {
        val expected = mapOf(
            MainBounties.GROUP_PROLOGUE to 0,
            MainBounties.GROUP_CH1_BATCH1 to 300_000,
            MainBounties.GROUP_CH1_BATCH2 to 500_000,
            MainBounties.GROUP_CH1_BATCH3 to 750_000,
            MainBounties.GROUP_CH2_XC to 1_000_000,
            MainBounties.GROUP_CH2_ZW to 1_000_000,
            MainBounties.GROUP_CH3 to 1_500_000,
            MainBounties.GROUP_CH4 to 2_000_000,
        )
        for ((id, bonus) in expected) {
            assertEquals(bonus, assertNotNull(MainBounties.group(id)).bonusBase, id)
        }
    }

    @Test
    fun `gating 链无环且前置均可达`() {
        // 工单依赖 = requiresOrders + 前置组的全部工单
        val deps: Map<String, Set<String>> = MainBounties.all.associate { def ->
            val viaGroups = def.requiresGroups.flatMap { g -> MainBounties.ordersOfGroup(g).map { it.key } }
            def.key to (def.requiresOrders + viaGroups).toSet()
        }
        // 前置必须存在且不得自依赖
        for ((key, requires) in deps) {
            assertFalse(key in requires, "$key 自依赖")
            requires.forEach { assertNotNull(MainBounties.byKey(it), "$key 前置 $it 不存在") }
        }
        // 拓扑排序验证无环
        val done = HashSet<String>()
        val pending = deps.keys.toMutableSet()
        var guard = 1000
        while (pending.isNotEmpty() && guard-- > 0) {
            val ready = pending.filter { deps.getValue(it).all { d -> d in done } }
            assertTrue(ready.isNotEmpty(), "gating 链存在环：剩余 $pending")
            done.addAll(ready)
            pending.removeAll(ready.toSet())
        }
        assertTrue(pending.isEmpty())
    }

    @Test
    fun `章节 gating 链符合定稿结构`() {
        fun def(key: String) = assertNotNull(MainBounties.byKey(key), key)

        // 序章无前置、不挂牌（酒馆对话接取）
        assertEquals(emptyList(), def(MainBounties.KEY_PROLOGUE).requiresGroups)
        assertFalse(def(MainBounties.KEY_PROLOGUE).boardPosted)
        MainBounties.all.filter { it.boardPosted.not() }.forEach {
            assertEquals(MainBounties.KEY_PROLOGUE, it.key, "仅序章不挂牌")
        }

        // 一章三批依次结清解锁
        assertEquals(listOf(MainBounties.GROUP_PROLOGUE), def(MainBounties.KEY_YJ_1102).requiresGroups)
        assertEquals(listOf(MainBounties.GROUP_CH1_BATCH1), def(MainBounties.KEY_YJ_1198).requiresGroups)
        assertEquals(listOf(MainBounties.GROUP_CH1_BATCH2), def(MainBounties.KEY_JJ_0007).requiresGroups)

        // 二章双线并行：均只依赖一章批三，互不卡
        val ch2 = MainBounties.all.filter { it.chapter == 2 }
        ch2.forEach { assertEquals(listOf(MainBounties.GROUP_CH1_BATCH3), it.requiresGroups, it.key) }
        // 星坠线内部线性递进，紫菀线单工单无内部前置
        assertEquals(listOf(MainBounties.KEY_XC_0216), def(MainBounties.KEY_XC_0217).requiresOrders)
        assertEquals(listOf(MainBounties.KEY_XC_0217), def(MainBounties.KEY_XC_0221).requiresOrders)
        assertEquals(emptyList(), def(MainBounties.KEY_ZW_0309).requiresOrders)

        // 三章依赖二章双线全部结清，内部线性递进
        val ch3 = MainBounties.all.filter { it.chapter == 3 }
        ch3.forEach {
            assertEquals(
                setOf(MainBounties.GROUP_CH2_XC, MainBounties.GROUP_CH2_ZW),
                it.requiresGroups.toSet(),
                it.key,
            )
        }
        assertEquals(listOf(MainBounties.KEY_ZX_1001), def(MainBounties.KEY_ZX_0344).requiresOrders)
        assertEquals(listOf(MainBounties.KEY_ZX_0344), def(MainBounties.KEY_ZX_0002).requiresOrders)

        // 四章依赖三章结清
        assertEquals(listOf(MainBounties.GROUP_CH3), def(MainBounties.KEY_ZQ_0001).requiresGroups)
    }

    @Test
    fun `allowR 仅三四章开放且三章单3固定至少一条R`() {
        // R 型开放集合：三章单 3（首秀，固定至少 1 条）+ 四章 ZQ（全量开放）
        val expected = setOf(MainBounties.KEY_ZX_0002, MainBounties.KEY_ZQ_0001)
        for (def in MainBounties.all) {
            assertEquals(def.key in expected, def.allowRAffixes, def.key)
            if (def.allowRAffixes) assertTrue(def.chapter >= 3, "${def.key} 过早开放 R 型")
        }
        assertEquals(1, assertNotNull(MainBounties.byKey(MainBounties.KEY_ZX_0002)).minRAffixes)
        MainBounties.all.filter { it.key != MainBounties.KEY_ZX_0002 }.forEach {
            assertEquals(0, it.minRAffixes, it.key)
        }
        // 四章中军旗舰固定 R-17 奇点驱动
        val zq = assertNotNull(MainBounties.byKey(MainBounties.KEY_ZQ_0001))
        assertEquals(listOf("astd_affix_singularity_drive"), zq.stages.last().flagshipAffixIds)
    }

    @Test
    fun `词缀开关与危险等级口径`() {
        // 序章与一章批一不挂词缀
        assertFalse(assertNotNull(MainBounties.byKey(MainBounties.KEY_PROLOGUE)).allowAffixes)
        MainBounties.ordersOfGroup(MainBounties.GROUP_CH1_BATCH1).forEach { assertFalse(it.allowAffixes, it.key) }
        // 批二起全部挂词缀
        MainBounties.all.filter { it.key != MainBounties.KEY_PROLOGUE && it.groupId != MainBounties.GROUP_CH1_BATCH1 }
            .forEach { assertTrue(it.allowAffixes, it.key) }

        // 危险等级：序章一级、批一二级、批二三级、批三四级、二章四五级、三章五六级、四章从缺
        fun tier(key: String) = assertNotNull(MainBounties.byKey(key)).threatTier
        assertEquals(1, tier(MainBounties.KEY_PROLOGUE))
        assertEquals(2, tier(MainBounties.KEY_YJ_1102))
        assertEquals(3, tier(MainBounties.KEY_YJ_1198))
        assertEquals(4, tier(MainBounties.KEY_JJ_0007))
        assertEquals(4, tier(MainBounties.KEY_XC_0216))
        assertEquals(5, tier(MainBounties.KEY_XC_0221))
        assertEquals(5, tier(MainBounties.KEY_ZW_0309))
        assertEquals(6, tier(MainBounties.KEY_ZX_0002))
        // 四章等级从缺仅 ZQ
        assertTrue(assertNotNull(MainBounties.byKey(MainBounties.KEY_ZQ_0001)).dangerOmitted)
        MainBounties.all.filter { it.key != MainBounties.KEY_ZQ_0001 }.forEach { assertFalse(it.dangerOmitted, it.key) }
    }

    @Test
    fun `单票报酬区间按章节对照定稿`() {
        val expected = mapOf(
            0 to (200_000 to 1_000_000),
            1 to (300_000 to 1_500_000),
            2 to (400_000 to 2_000_000),
            3 to (500_000 to 2_500_000),
            4 to (750_000 to 3_750_000),
        )
        for (def in MainBounties.all) {
            val (min, max) = expected.getValue(def.chapter)
            assertEquals(min, def.rewardMin, def.key)
            assertEquals(max, def.rewardMax, def.key)
        }
    }

    @Test
    fun `清算进度增量对照定稿节拍`() {
        // 三章单 2 反常跳动 -0.5%
        assertEquals(0.6f, assertNotNull(MainBounties.byKey(MainBounties.KEY_ZX_1001)).liquidationDelta, 1e-6f)
        assertEquals(-0.5f, assertNotNull(MainBounties.byKey(MainBounties.KEY_ZX_0344)).liquidationDelta, 1e-6f)
        assertEquals(1.4f, assertNotNull(MainBounties.byKey(MainBounties.KEY_ZX_0002)).liquidationDelta, 1e-6f)
        // 四章阶段增量 0.3/0.5/0.4
        val zq = assertNotNull(MainBounties.byKey(MainBounties.KEY_ZQ_0001))
        assertEquals(listOf(0.3f, 0.5f, 0.4f), zq.stages.map { it.liquidationDelta })
        // 其余条目不推动显示读数（推进计入两百年自动推进基准口径）
        MainBounties.all.filter { it.chapter <= 2 }.forEach { def ->
            assertEquals(0f, def.liquidationDelta, 1e-6f, def.key)
            def.stages.forEach { assertEquals(0f, it.liquidationDelta, 1e-6f, def.key) }
        }
        // ZQ 各阶段均有定稿阶段回执；其余工单无阶段回执
        zq.stages.forEach { assertTrue(it.stageReceipt) }
        MainBounties.all.filter { it.key != MainBounties.KEY_ZQ_0001 }.forEach { def ->
            def.stages.forEach { assertFalse(it.stageReceipt, def.key) }
        }
    }
}
