package cn.kasuminova.astd.campaign.ending

import cn.kasuminova.astd.campaign.bounty.BountyState
import cn.kasuminova.astd.campaign.bounty.MainBounties
import cn.kasuminova.astd.campaign.bounty.MainlineProgression
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 第五章「归档」结局纯逻辑：三选计划展开 / 签署守卫与落账 / 延迟激活 /
 * 执行官签发不可逆 / 任命守卫 / 终局触发链（四章末清算 100% → 签署 → 无限期承包）。
 */
class EndingProgressionTest {

    private val affected = listOf("hegemony", "sindrian_diktat", "tritachyon", "luddic_church", "independent")

    /** 模拟游戏侧完整流程：挂出（锁整单报价）→ 逐阶段击毁 → 终端核销（与 MainlineProgressionTest 同口径）。 */
    private fun playOrder(state: BountyState, key: String) {
        val d = assertNotNull(MainBounties.byKey(key), key)
        state.quotedRewards.putIfAbsent(
            MainlineProgression.quoteKey(key),
            MainlineProgression.quoteOrderReward(d, 1f, key.hashCode().toLong()),
        )
        state.postedWorkOrders.add(key)
        repeat(d.stages.size) { MainlineProgression.onStageDestroyed(state, key) }
        assertTrue(MainlineProgression.settle(state, key, 1f).success, "核销应成功：$key")
    }

    /** 走完序章~四章全部主线工单，抵达 archivalPending 的状态。 */
    private fun archivalPendingState(): BountyState {
        val state = BountyState()
        listOf(
            MainBounties.KEY_PROLOGUE,
            MainBounties.KEY_YJ_1102, MainBounties.KEY_YJ_1103,
            MainBounties.KEY_YJ_1198, MainBounties.KEY_YJ_1201, MainBounties.KEY_YJ_1204,
            MainBounties.KEY_JJ_0007,
            MainBounties.KEY_XC_0216, MainBounties.KEY_XC_0217, MainBounties.KEY_XC_0221,
            MainBounties.KEY_ZW_0309,
            MainBounties.KEY_ZX_1001, MainBounties.KEY_ZX_0344, MainBounties.KEY_ZX_0002,
            MainBounties.KEY_ZQ_0001,
        ).forEach { playOrder(state, it) }
        assertTrue(state.archivalPending, "全主线核销后应置归档挂起")
        return state
    }

    // ─── 三选计划展开 ───

    @Test
    fun `公开选全体立即加25`() {
        val plan = EndingProgression.planSign(EndingProgression.Choice.PUBLISH, null, affected, 1f)
        assertEquals(affected.size, plan.effects.size)
        assertTrue(plan.effects.all { it.pct == EndingProgression.PUBLISH_ALL_PCT && it.delayDays == 0f })
        assertFalse(plan.keepArchiveAccess)
        assertEquals(0, plan.tradePayout)
        assertTrue(plan.relationDeltas.isEmpty())
    }

    @Test
    fun `封存选全体延迟加12且保留档案访问`() {
        val plan = EndingProgression.planSign(EndingProgression.Choice.SEAL, null, affected, 1f)
        assertTrue(plan.effects.all { it.pct == EndingProgression.SEAL_ALL_PCT && it.delayDays == EndingProgression.DELAY_DAYS })
        assertTrue(plan.keepArchiveAccess)
    }

    @Test
    fun `交易选对象立即50其余延迟10附报酬与关系`() {
        val plan = EndingProgression.planSign(EndingProgression.Choice.TRADE, "hegemony", affected, 1f)
        assertEquals("hegemony", plan.tradeFactionId)
        val target = plan.effects.single { it.factionId == "hegemony" }
        assertEquals(EndingProgression.TRADE_TARGET_PCT, target.pct)
        assertEquals(0f, target.delayDays)
        val others = plan.effects.filter { it.factionId != "hegemony" }
        assertTrue(others.all { it.pct == EndingProgression.TRADE_OTHERS_PCT && it.delayDays == EndingProgression.DELAY_DAYS })
        assertTrue(plan.tradePayout in EndingProgression.TRADE_REWARD_MIN..EndingProgression.TRADE_REWARD_MAX)
        assertEquals(EndingProgression.TRADE_REL_TARGET, plan.relationDeltas["hegemony"])
        assertEquals(EndingProgression.TRADE_REL_OTHERS, plan.relationDeltas["tritachyon"])
    }

    @Test
    fun `交易选必须指定候选对象`() {
        var threw = false
        try {
            EndingProgression.planSign(EndingProgression.Choice.TRADE, null, affected, 1f)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw, "交易选缺对象应抛 IllegalArgumentException")

        threw = false
        try {
            EndingProgression.planSign(EndingProgression.Choice.TRADE, "remnants", affected, 1f)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw, "余晖不在候选列表（不向非在编自动智能出售）")
    }

    @Test
    fun `交易报价区间确定性与难度缩放`() {
        val q1 = EndingProgression.tradeQuote("hegemony", 1f)
        val q2 = EndingProgression.tradeQuote("hegemony", 1f)
        assertEquals(q1, q2, "同候选方报价恒定（报价函为两百年前的询价记录）")
        assertTrue(q1 in EndingProgression.TRADE_REWARD_MIN..EndingProgression.TRADE_REWARD_MAX)
        val scaled = EndingProgression.tradeQuote("hegemony", 3f)
        assertEquals(q1 * 3, scaled, "报价按难度系数线性缩放")
        assertEquals(EndingProgression.tradeQuote("hegemony", 5f), EndingProgression.tradeQuote("hegemony", 9f), "缩放封顶 5×")
    }

    // ─── 签署守卫与落账 ───

    @Test
    fun `未挂起不可签署且签署后不可反悔`() {
        val state = BountyState()
        val plan = EndingProgression.planSign(EndingProgression.Choice.PUBLISH, null, affected, 1f)
        assertFalse(EndingProgression.sign(state, plan, 0L, 10f), "未挂起应拒绝")

        state.archivalPending = true
        assertTrue(EndingProgression.sign(state, plan, 0L, 10f))
        assertEquals("PUBLISH", state.archivalChoice)
        assertTrue(state.indefiniteContractor, "签署即转入无限期承包")
        assertTrue(state.archivesReadOnly, "公开选档案室转只读")
        assertEquals(affected.toSet(), state.appliedStrengthPct.keys)
        assertTrue(state.pendingStrengthEffects.isEmpty())

        val again = EndingProgression.planSign(EndingProgression.Choice.SEAL, null, affected, 1f)
        assertFalse(EndingProgression.sign(state, again, 0L, 10f), "已签署应拒绝")
        assertEquals("PUBLISH", state.archivalChoice, "二次签署不得覆盖")
    }

    @Test
    fun `封存签署落账为延迟条目且保留档案访问`() {
        val state = BountyState()
        state.archivalPending = true
        val plan = EndingProgression.planSign(EndingProgression.Choice.SEAL, null, affected, 1f)
        assertTrue(EndingProgression.sign(state, plan, 1000L, 10f))
        assertFalse(state.archivesReadOnly, "封存选保留档案室访问权")
        assertTrue(state.appliedStrengthPct.isEmpty())
        assertEquals(affected.toSet(), state.pendingStrengthEffects.keys)
        val expectAt = 1000L + (EndingProgression.DELAY_DAYS * 10f).toLong()
        assertTrue(state.pendingStrengthEffects.values.all { it.activateTimestamp == expectAt })
    }

    // ─── 延迟激活 ───

    @Test
    fun `延迟条目到期激活且同势力取最大幅度`() {
        val state = BountyState()
        state.archivalPending = true
        val plan = EndingProgression.planSign(EndingProgression.Choice.SEAL, null, affected, 1f)
        EndingProgression.sign(state, plan, 0L, 10f)
        val activateAt = (EndingProgression.DELAY_DAYS * 10f).toLong()

        assertTrue(EndingProgression.activateDelayedEffects(state, activateAt - 1).isEmpty(), "未到期不激活")
        assertTrue(state.appliedStrengthPct.isEmpty())

        // 同势力已有更大生效幅度时不覆盖（口径声明）
        state.appliedStrengthPct["hegemony"] = 0.5f
        val due = EndingProgression.activateDelayedEffects(state, activateAt)
        assertEquals(affected.size, due.size)
        assertTrue(state.pendingStrengthEffects.isEmpty())
        assertEquals(0.5f, state.appliedStrengthPct["hegemony"], "已有更大幅度不覆盖")
        assertEquals(EndingProgression.SEAL_ALL_PCT, state.appliedStrengthPct["tritachyon"])

        assertTrue(EndingProgression.activateDelayedEffects(state, activateAt).isEmpty(), "激活幂等")
    }

    @Test
    fun `延迟条目到期时势力灭国则作废不残留`() {
        val state = BountyState()
        state.archivalPending = true
        val plan = EndingProgression.planSign(EndingProgression.Choice.SEAL, null, affected, 1f)
        EndingProgression.sign(state, plan, 0L, 10f)
        val activateAt = (EndingProgression.DELAY_DAYS * 10f).toLong()

        // 灭国势力：tritachyon 与 luddic_church 判定为不存续
        val extinct = setOf("tritachyon", "luddic_church")
        val activated = EndingProgression.activateDelayedEffects(state, activateAt) { it !in extinct }

        assertEquals(affected.size - extinct.size, activated.size, "灭国势力条目不激活")
        assertTrue(state.pendingStrengthEffects.isEmpty(), "灭国条目到期即移出待生效表，不残留")
        assertNull(state.appliedStrengthPct["tritachyon"], "灭国势力不入已生效表")
        assertNull(state.appliedStrengthPct["luddic_church"])
        assertEquals(EndingProgression.SEAL_ALL_PCT, state.appliedStrengthPct["hegemony"])
        assertTrue(activated.none { it.factionId in extinct })
    }

    // ─── 执行官状态机 ───

    @Test
    fun `签发需先签署归档且一经签发不予退换`() {
        val state = BountyState()
        assertFalse(EndingProgression.issueExecutor(state, EndingProgression.ExecutorSpec.COMBAT), "未签署归档应拒绝")

        state.archivalPending = true
        val plan = EndingProgression.planSign(EndingProgression.Choice.PUBLISH, null, affected, 1f)
        EndingProgression.sign(state, plan, 0L, 10f)

        assertTrue(EndingProgression.issueExecutor(state, EndingProgression.ExecutorSpec.COMBAT))
        assertEquals("COMBAT", state.executorSpec)
        assertTrue(state.executorIssued)
        assertFalse(EndingProgression.issueExecutor(state, EndingProgression.ExecutorSpec.ADMIN), "已签发应拒绝（不予退换）")
        assertEquals("COMBAT", state.executorSpec)
    }

    @Test
    fun `任命守卫按特化分派`() {
        val state = BountyState()
        state.archivalPending = true
        EndingProgression.sign(state, EndingProgression.planSign(EndingProgression.Choice.PUBLISH, null, affected, 1f), 0L, 10f)

        assertFalse(EndingProgression.assignCommandShip(state, "m1"), "未签发应拒绝")
        EndingProgression.issueExecutor(state, EndingProgression.ExecutorSpec.COMBAT)
        assertFalse(EndingProgression.appointAdmin(state, "market1"), "战斗特化不得任命市场")
        assertTrue(EndingProgression.assignCommandShip(state, "m1"))
        assertEquals("m1", state.executorCommandShipId)
        // 舰只离场/损毁后可重新指定
        assertTrue(EndingProgression.assignCommandShip(state, "m2"))
        assertEquals("m2", state.executorCommandShipId)

        val admin = BountyState()
        admin.archivalPending = true
        EndingProgression.sign(admin, EndingProgression.planSign(EndingProgression.Choice.PUBLISH, null, affected, 1f), 0L, 10f)
        EndingProgression.issueExecutor(admin, EndingProgression.ExecutorSpec.ADMIN)
        assertFalse(EndingProgression.assignCommandShip(admin, "m1"), "行政特化不得指定指挥舰")
        assertTrue(EndingProgression.appointAdmin(admin, "market1"))
        assertEquals("market1", admin.executorAdminMarketId)
    }

    @Test
    fun `任命前置校验失败不落账`() {
        // D7 事务口径：装配层先 canAssign/canAppoint 校验，游戏侧成功后才落账；
        // 此处验证校验与落账的真值表一致（校验失败 ⇒ 落账函数同样拒绝且 state 不被污染）
        val state = BountyState()
        state.archivalPending = true
        EndingProgression.sign(state, EndingProgression.planSign(EndingProgression.Choice.PUBLISH, null, affected, 1f), 0L, 10f)

        assertFalse(EndingProgression.canAssignCommandShip(state), "未签发不得指定指挥舰")
        assertFalse(EndingProgression.assignCommandShip(state, "m1"))
        assertNull(state.executorCommandShipId, "校验失败不得写入 state")

        EndingProgression.issueExecutor(state, EndingProgression.ExecutorSpec.ADMIN)
        assertFalse(EndingProgression.canAssignCommandShip(state), "行政特化不得指定指挥舰")
        assertFalse(EndingProgression.assignCommandShip(state, "m1"))
        assertNull(state.executorCommandShipId)

        assertTrue(EndingProgression.canAppointAdmin(state))
        assertTrue(EndingProgression.appointAdmin(state, "market1"))
        assertEquals("market1", state.executorAdminMarketId)

        // 重新任命：记录更换（旧市场游戏侧清理由 ExecutorCores.appointAdmin 清扫负责）
        assertTrue(EndingProgression.appointAdmin(state, "market2"))
        assertEquals("market2", state.executorAdminMarketId)
    }

    // ─── 终局触发链（四章末清算 100% → 签署 → 无限期承包 → 签发 → 任命） ───

    @Test
    fun `终局触发链全流程`() {
        val state = archivalPendingState()
        assertNull(EndingProgression.choiceOf(state))
        assertNull(EndingProgression.specOf(state))

        // 签署（封存）
        val plan = EndingProgression.planSign(EndingProgression.Choice.SEAL, null, affected, 2f)
        assertTrue(EndingProgression.sign(state, plan, 500L, 10f))
        assertEquals(EndingProgression.Choice.SEAL, EndingProgression.choiceOf(state))
        assertTrue(state.indefiniteContractor)

        // 无限赏金槽位初始化（《无限期承包合同》常设委托）
        val created = InfiniteBountyGenerator.ensureSlots(state, 2f, InfiniteBountyBridge.SEED_BASE)
        assertEquals(InfiniteBountyGenerator.SLOT_COUNT, created.size)
        assertEquals(3, state.infiniteSlots.size)
        assertTrue(InfiniteBountyGenerator.ensureSlots(state, 2f, InfiniteBountyBridge.SEED_BASE).isEmpty(), "补齐幂等")

        // 签发 + 任命
        assertTrue(EndingProgression.issueExecutor(state, EndingProgression.ExecutorSpec.ADMIN))
        assertEquals(EndingProgression.ExecutorSpec.ADMIN, EndingProgression.specOf(state))
        assertTrue(EndingProgression.appointAdmin(state, "market1"))
    }
}
