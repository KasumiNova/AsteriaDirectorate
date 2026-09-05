package cn.kasuminova.astd.campaign.bounty

import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.campaign.ui.HudMessages
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.FleetAssignment
import com.fs.starfarer.api.impl.campaign.events.OfficerManagerEvent
import com.fs.starfarer.api.util.Misc
import org.apache.log4j.Logger
import org.magiclib.bounty.ActiveBounty
import org.magiclib.bounty.MagicBountyCoordinator
import org.magiclib.bounty.MagicBountyLoader
import org.magiclib.bounty.MagicBountySpec
import java.awt.Color
import java.util.Random

/**
 * 终局无限赏金：归档三选签署（《无限期承包合同》签发）后，分局空间站持续在册
 * [SLOT_COUNT] 个随机工单槽；每单击破目标后返回分局交付（[onSettled]），
 * 交付即清理 MagicBounty 旧记录并刷新同槽新一代随机工单。
 *
 * 与主线的分工：
 * - 定义模型复用 [BountyDef]（[definitions] / [definition] 供管理脚本与终端取数）；
 * - 状态自管（[InfiniteBountyState]），不读写 [BountyState] 的 defeated/quoted/settlement 集合；
 *   对 [BountyState] 仅读 `infiniteContractor` 解锁标记，并在清理时移除本体系 key 的
 *   `patchedBountyKeys` 补丁标记（管理脚本接受后重建舰队时写入，每代 key 唯一，必须随单清理）；
 * - 每代 key 唯一（`astd_inf_s<槽位>_g<代次>`），MagicLib 侧不会因旧阶段 memKey 误结算；
 * - 存档/Intel 累积有界：交付后删除加载器 spec、完成表记录与 sector memKey，账单只留最近 [BILL_CAP] 条。
 *
 * 文案在独立字符串表 `data/strings/infinite_bounty_strings.json`（category [I18N_CATEGORY]），
 * 危险等级栏复用主线 `danger.level.*`。
 */
object InfiniteBounties {

    private val log: Logger = Global.getLogger(InfiniteBounties::class.java)

    /** 在册工单槽数量（终局后持续 3 单）。 */
    const val SLOT_COUNT: Int = 3

    /** MagicBounty 注册键前缀（带 [BountyKeys.BOUNTY_KEY_PREFIX]，管理脚本可识别为模组单）。 */
    const val KEY_PREFIX: String = "astd_inf_"

    /** 账单保留上限（只留最近 N 条，避免无限历史）。 */
    const val BILL_CAP: Int = 10

    /** 文案 category（contents/data/strings/infinite_bounty_strings.json）。 */
    const val I18N_CATEGORY: String = "asteria_directorate_infinite_bounty"

    /** 预设 FP 随机区间。 */
    const val FP_MIN: Int = 800
    const val FP_MAX: Int = 2800

    /** 危险等级随机区间。 */
    const val DANGER_MIN: Int = 1
    const val DANGER_MAX: Int = 5

    /** 报酬基数区间（每级危险等级；× k_s 后为报价）。 */
    const val REWARD_MIN_PER_DANGER: Int = 150_000
    const val REWARD_MAX_PER_DANGER: Int = 500_000

    /**
     * 旗舰池：总局遗存/战斗群编成沿用主线第三、四章验证过的模组 variant，
     * 目标池全部模组舰船（[BountyDef.modOnlyComposition]）。
     */
    val FLAGSHIP_POOL: List<String> = listOf(
        "astd_arc_flare_Standard",
        "astd_radiation_belt_Standard",
        "astd_plasma_arch_Standard",
        "astd_magnetosphere_disturbance_Standard",
        "astd_dark_tide_nebula_Standard",
        "astd_arc_jet_Standard",
        "astd_apex_logic_Standard",
        "astd_gravitational_lens_Automated",
    )

    private val RECEIPT_COLOR = Color(200, 170, 120)

    /**
     * 终态失败阶段：进入这些阶段的在册单不会自行回到赏金板，由本体系清理记录并换代。
     * （[ActiveBounty.Stage] 全集中扣除 NotAccepted/Accepted/Succeeded 的其余全部。）
     */
    internal val TERMINAL_FAILURE_STAGES: Set<ActiveBounty.Stage> = setOf(
        ActiveBounty.Stage.FailedSalvagedFlagship,
        ActiveBounty.Stage.ExpiredAfterAccepting,
        ActiveBounty.Stage.Dismissed,
        ActiveBounty.Stage.ExpiredWithoutAccepting,
        ActiveBounty.Stage.EndedWithoutPlayerInvolvement,
    )

    /** 核销校验结论。 */
    enum class SettlementVerdict {
        /** 在册且有待交付战果，受理。 */
        ACCEPTABLE,

        /** 不在任何在册槽（非本体系 key，或已换代/已核销的旧 key）。 */
        UNKNOWN_KEY,

        /** 在册但没有待交付战果（未击破，或已核销——拒绝重复发奖）。 */
        NO_PENDING_DELIVERY,
    }

    // ── 纯计算层（不触游戏环境，供单元测试直接驱动） ─────────────────────────

    /** 注册键：`astd_inf_s<槽位>_g<代次>`。 */
    fun keyOf(slotIndex: Int, generation: Int): String = "${KEY_PREFIX}s${slotIndex}_g$generation"

    /** 文书编号骑缝：WX-c209-<流水号>／续展-<槽位>。 */
    fun codeFor(serial: Int, slotIndex: Int): String =
        String.format(java.util.Locale.ROOT, "WX-c209-%04d／续展-%02d", serial, slotIndex + 1)

    /** R 型词缀数量区间随危险等级搭配：1 级不出现，5 级打满搭配表（R 2 条）。 */
    fun rRangeForDanger(danger: Int): Pair<Int, Int> = when (danger) {
        1 -> 0 to 0
        2 -> 0 to 1
        3 -> 1 to 1
        4 -> 1 to 2
        else -> 2 to 2
    }

    /** 报酬基数区间（未乘 k_s）：随危险等级线性放大。 */
    fun rewardRangeForDanger(danger: Int): Pair<Int, Int> =
        (danger * REWARD_MIN_PER_DANGER) to (danger * REWARD_MAX_PER_DANGER)

    /**
     * 抽取一代随机工单内容。
     *
     * @param slotIndex 槽位序号（0..[SLOT_COUNT]）
     * @param generation 代次（该槽第几单，从 1 起）
     * @param serial 全局签发流水号（文书编号用）
     * @param ks 固有难度系数 k_s（报价 = 基数区间抽取 × k_s，签发时锁定）
     */
    fun rollSlot(slotIndex: Int, generation: Int, serial: Int, rnd: Random, ks: Float): InfiniteBountySlot {
        val danger = DANGER_MIN + rnd.nextInt(DANGER_MAX - DANGER_MIN + 1)
        val fp = FP_MIN + rnd.nextInt(FP_MAX - FP_MIN + 1)
        val (rMin, rMax) = rRangeForDanger(danger)
        val (rewardMin, rewardMax) = rewardRangeForDanger(danger)
        return InfiniteBountySlot().also { slot ->
            slot.key = keyOf(slotIndex, generation)
            slot.generation = generation
            slot.code = codeFor(serial, slotIndex)
            slot.dangerLevel = danger
            slot.baselineFP = fp
            slot.flagshipVariantId = FLAGSHIP_POOL[rnd.nextInt(FLAGSHIP_POOL.size)]
            slot.affixRMin = rMin
            slot.affixRMax = rMax
            slot.rewardMin = rewardMin
            slot.rewardMax = rewardMax
            slot.quotedReward = BountyRewards.computeTicketPayout(rewardMin, rewardMax, ks, rnd)
        }
    }

    /**
     * 核销校验（纯函数）：返回槽位序号与结论；仅 [SettlementVerdict.ACCEPTABLE] 时槽位序号有效。
     * 单票只受理一次——核销后槽位换代，旧 key 再提交只会得到 [SettlementVerdict.UNKNOWN_KEY]。
     */
    fun judgeSettlement(state: InfiniteBountyState, key: String): Pair<Int, SettlementVerdict> {
        val index = state.slots.indexOfFirst { it.key.isNotEmpty() && it.key == key }
        if (index < 0) return -1 to SettlementVerdict.UNKNOWN_KEY
        if (key !in state.pendingDelivery) return index to SettlementVerdict.NO_PENDING_DELIVERY
        return index to SettlementVerdict.ACCEPTABLE
    }

    /** 核销落账（纯函数，状态层部分）：清除该槽待交付登记。报酬/清理/换代由运行时层完成。 */
    fun markDelivered(state: InfiniteBountyState, slotIndex: Int) {
        state.pendingDelivery.remove(state.slots[slotIndex].key)
    }

    /** 由槽位内容还原 [BountyDef]（舰队重建/终端取数用；不含任何运行时缓存依赖）。 */
    fun defOfSlot(slot: InfiniteBountySlot): BountyDef = BountyDef(
        key = slot.key,
        code = slot.code,
        chapter = 5,
        groupId = "infinite",
        dangerLevel = slot.dangerLevel,
        baselineFP = slot.baselineFP,
        flagshipVariantId = slot.flagshipVariantId,
        fleetFactionId = "remnant",
        affixRule = AffixRule.withR(slot.affixRMin, slot.affixRMax),
        rewardMin = slot.rewardMin,
        rewardMax = slot.rewardMax,
        modOnlyComposition = true,
    )

    // ── 对父整合开放的查询接口 ──────────────────────────────────────────────

    /** key 是否属于无限赏金体系。 */
    @JvmStatic
    fun isInfiniteKey(key: String): Boolean = key.startsWith(KEY_PREFIX)

    /** 当前在册工单定义（槽位已签发的部分，最多 [SLOT_COUNT] 单）。 */
    @JvmStatic
    fun definitions(): List<BountyDef> =
        InfiniteBountyState.getOrCreate().slots.filter { it.key.isNotEmpty() }.map(::defOfSlot)

    /** 单个在册工单定义（管理脚本接受后重建舰队用）；非在册 key 返回 null。 */
    @JvmStatic
    fun definition(key: String): BountyDef? = slotOf(key)?.let(::defOfSlot)

    /** 签发时锁定的报价（终端展示与交付实发同值）；非在册 key 返回 null。 */
    @JvmStatic
    fun quotedReward(key: String): Int? = slotOf(key)?.quotedReward

    /** 文书编号骑缝（终端/流水账展示用）。 */
    @JvmStatic
    fun slotCode(key: String): String? = slotOf(key)?.code

    /** 已击破目标、等待返回分局交付的工单 key（终端「可交付」列表）。 */
    @JvmStatic
    fun pendingDeliveries(): Set<String> =
        InfiniteBountyState.getOrCreate().pendingDelivery.toSet()

    /** 履约账单（时间序，最旧在前；只保留最近 [BILL_CAP] 条）。 */
    @JvmStatic
    fun bills(): List<InfiniteBountyBill> =
        InfiniteBountyState.getOrCreate().bills.toList()

    /** 工单抬头（与赏金板 job_name 同文）。 */
    @JvmStatic
    fun displayName(key: String): String? = slotOf(key)?.let(::nameOf)

    /** 目标编队名称（与赏金板 fleet_name 同文）。 */
    @JvmStatic
    fun fleetName(key: String): String? = slotOf(key)?.let(::fleetNameOf)

    /** 工单正文（与赏金板 job_description 同文，酬金栏为锁定报价）。 */
    @JvmStatic
    fun description(key: String): String? = slotOf(key)?.let(::descOf)

    /** 危险等级栏文本（复用主线 danger.level.*）。 */
    @JvmStatic
    fun dangerText(key: String): String? = slotOf(key)?.let(::dangerOf)

    /**
     * 核销回执批注（接受后由管理脚本写入 fleet memory [BountyKeys.MEM_SUCCESS_TEXT]，战后对话框输出）。
     */
    @JvmStatic
    fun receiptText(key: String): String? = slotOf(key)?.let(::receiptOf)

    // ── 运行时驱动 ─────────────────────────────────────────────────────────

    /**
     * 在册维护（由战役管理脚本周期性调用；未解锁无限期承包商时直接返回）。
     *
     * 逐槽保证：
     * - 未签发 → 抽取并注册新一代；
     * - 战斗取胜（MagicLib 侧 Succeeded）→ 登记待交付；
     * - 进入终态失败阶段（仍占用活跃表，不会自行回到赏金板）→ 当场清理记录并换代；
     * - 已被 MagicLib 移出活跃表且未交付（清理线程搬入完成表）→ 清理并换代；
     * - 加载器重载丢定义 → 补注册。
     *
     * 最后清理 MagicBounty 侧不再属于任何在册槽的旧代残留（spec/完成表/memKey/舰队补丁标记）。
     * 槽位数量异常时记错误日志并跳过本次维护，不做静默兜底。
     */
    @JvmStatic
    fun ensureAvailable() {
        val sector = Global.getSector() ?: return
        if (!BountyState.getOrCreate().infiniteContractor) return
        val state = InfiniteBountyState.getOrCreate()
        if (state.slots.size != SLOT_COUNT) {
            log.error("[InfiniteBounties] 存档槽位数量异常（${state.slots.size}，应为 $SLOT_COUNT），本次跳过在册维护")
            return
        }
        val coord = try {
            MagicBountyCoordinator.getInstance()
        } catch (t: Throwable) {
            log.warn("[InfiniteBounties] MagicBountyCoordinator 不可用：${t.message}")
            return
        }

        val active = coord.activeBounties
        val completed = coord.completedBounties

        for (index in 0 until SLOT_COUNT) {
            val slot = state.slots[index]
            if (slot.key.isEmpty()) {
                rollRegisterAndStore(state, index)
                continue
            }

            val bounty = active[slot.key]
            if (bounty != null) {
                when {
                    bounty.stage == ActiveBounty.Stage.Succeeded ->
                        if (state.pendingDelivery.add(slot.key)) {
                            log.info("[InfiniteBounties] 工单战斗目标已击破，等待分局交付：${slot.key}")
                        }

                    // 终态失败：MagicLib 要等 Intel 消亡后才把它搬进完成表，期间赏金板不再挂出，
                    // 不能原地 continue 干等——当场清理并换代。
                    bounty.stage in TERMINAL_FAILURE_STAGES -> {
                        log.info("[InfiniteBounties] 工单进入终态失败阶段（${bounty.stage}），清理并换代：${slot.key}")
                        cleanupMagicBountyRecords(coord, slot.key)
                        rollRegisterAndStore(state, index)
                    }

                    // NotAccepted / Accepted：正常流转，原地等待。
                    else -> Unit
                }
                continue
            }

            // 已取胜待交付：原地等待玩家回分局，不刷新。
            if (slot.key in state.pendingDelivery) continue

            // 已被 MagicLib 清理线程搬进完成表且未交付（失败/异常流失）：清记录并换代。
            if (completed.contains(slot.key)) {
                log.info("[InfiniteBounties] 工单已终结（未交付），刷新新一代：${slot.key}")
                cleanupMagicBountyRecords(coord, slot.key)
                rollRegisterAndStore(state, index)
                continue
            }

            // 加载器内部重载后定义可能丢失，补注册。
            if (MagicBountyLoader.getBountyData(slot.key) == null) {
                registerSlot(slot)
            }
        }

        pruneStaleRecords(coord, state)
    }

    /**
     * 分局交付核销（由终端交付流程调用；只受理「在册 + 待交付」的工单，见 [judgeSettlement]）。
     *
     * 依次：发放锁定报价（展示=实发）→ 记有界账单 → 清除待交付登记 →
     * 清理 MagicBounty 旧记录 → 同槽刷新新一代。核销后旧 key 立即失效，重复提交只会被拒绝，
     * 不会重复发奖。
     *
     * @return 是否受理成功
     */
    @JvmStatic
    fun onSettled(key: String): Boolean {
        val sector = Global.getSector() ?: return false
        val state = InfiniteBountyState.getOrCreate()
        val (index, verdict) = judgeSettlement(state, key)
        when (verdict) {
            SettlementVerdict.UNKNOWN_KEY -> {
                log.warn("[InfiniteBounties] onSettled 收到非在册（或已核销换代）工单 key：$key，已拒绝")
                return false
            }

            SettlementVerdict.NO_PENDING_DELIVERY -> {
                log.warn("[InfiniteBounties] 工单 '$key' 当前没有待交付战果（未击破或已核销），拒绝核销")
                return false
            }

            SettlementVerdict.ACCEPTABLE -> Unit
        }
        val slot = state.slots[index]
        val credits = sector.playerFleet?.cargo?.credits
        if (credits == null) {
            log.warn("[InfiniteBounties] 续展报酬发放失败：无玩家舰队（key=$key, amount=${slot.quotedReward}）")
            return false
        }

        credits.add(slot.quotedReward.toFloat())
        HudMessages.campaign(
            I18n.t(I18N_CATEGORY, "infinite.hud.payout", "credits" to slot.quotedReward.toString()),
            RECEIPT_COLOR,
        )
        state.addBill(
            InfiniteBountyBill(
                slot.code,
                sector.clock.getDateString(),
                slot.quotedReward.toLong(),
                I18n[I18N_CATEGORY, "infinite.bill.note"],
            )
        )
        markDelivered(state, index)

        val coord = try {
            MagicBountyCoordinator.getInstance()
        } catch (t: Throwable) {
            log.warn("[InfiniteBounties] 清理 MagicBounty 记录时 Coordinator 不可用：${t.message}")
            null
        }
        if (coord != null) {
            cleanupMagicBountyRecords(coord, key)
        }

        rollRegisterAndStore(state, index)
        return true
    }

    // ── 内部实现 ────────────────────────────────────────────────────────────

    private fun slotOf(key: String): InfiniteBountySlot? =
        InfiniteBountyState.getOrCreate().slots.firstOrNull { it.key == key && key.isNotEmpty() }

    /** 抽取新一代并注册到 MagicBounty 加载器。 */
    private fun rollRegisterAndStore(state: InfiniteBountyState, slotIndex: Int) {
        val generation = state.slots[slotIndex].generation + 1
        state.totalSerials += 1
        val slot = rollSlot(
            slotIndex, generation, state.totalSerials,
            Random(Misc.genRandomSeed()), DifficultyTuningImpl.fixedScale,
        )
        state.slots[slotIndex] = slot
        registerSlot(slot)
        log.info(
            "[InfiniteBounties] 续展工单已签发：${slot.key}（${slot.code}，危险 ${slot.dangerLevel} 级，" +
                "FP ${slot.baselineFP}，报价 ${slot.quotedReward}）"
        )
    }

    /** 注册单槽当前代（overwrite=true：同 key 重注册覆盖加载器旧数据）。 */
    private fun registerSlot(slot: InfiniteBountySlot) {
        MagicBountyLoader.addBountyData(slot.key, buildSpec(slot), true)
    }

    /**
     * 清理一单的全部痕迹：MagicBounty 活跃表条目（含 Intel）、完成表记录、加载器 spec、
     * sector memKey（战斗键与 `$<key>` 及阶段后缀），以及管理脚本在 [BountyState.patchedBountyKeys]
     * 里留下的舰队补丁标记（每代 key 唯一，不清理会无限积累）。
     */
    private fun cleanupMagicBountyRecords(coord: MagicBountyCoordinator, key: String) {
        val bounty = coord.activeBounties.remove(key)
        if (bounty != null) {
            val intel = bounty.intel
            if (intel != null) {
                intel.endImmediately()
                Global.getSector()?.intelManager?.removeIntel(intel)
            }
        }
        coord.completedBounties.remove(key)
        MagicBountyLoader.deleteBountyData(key)
        BountyState.getOrCreate().patchedBountyKeys.remove(key)
        unsetMemKeys(key)
    }

    /** 清理不再属于任何在册槽的旧代残留（防御性；正常流程下 [cleanupMagicBountyRecords] 已清干净）。 */
    private fun pruneStaleRecords(coord: MagicBountyCoordinator, state: InfiniteBountyState) {
        val currentKeys = state.slots.mapNotNull { it.key.takeIf(String::isNotEmpty) }.toSet()
        state.pendingDelivery.retainAll(currentKeys)

        val staleKeys = HashSet<String>()
        for (stale in MagicBountyLoader.BOUNTIES.keys.filter { isInfiniteKey(it) && it !in currentKeys }) {
            MagicBountyLoader.deleteBountyData(stale)
            staleKeys.add(stale)
        }
        val staleCompleted = coord.completedBounties.filter { isInfiniteKey(it) && it !in currentKeys }
        if (staleCompleted.isNotEmpty()) {
            coord.completedBounties.removeAll(staleCompleted.toSet())
            staleKeys.addAll(staleCompleted)
        }
        if (staleKeys.isNotEmpty()) {
            val patched = BountyState.getOrCreate().patchedBountyKeys
            staleKeys.forEach { patched.remove(it) }
            staleKeys.forEach(::unsetMemKeys)
        }
    }

    private fun unsetMemKeys(key: String) {
        val mem = Global.getSector()?.memoryWithoutUpdate ?: return
        mem.unset("\$astd_battle_$key")
        mem.unset("\$astd_battle_${key}_succeeded")
        mem.unset("\$astd_battle_${key}_expired")
        mem.unset("\$astd_battle_${key}_failed")
        mem.unset("\$$key")
    }

    // ── 文案（spec 与终端共用同一套取值，保证展示一致） ─────────────────────

    private fun dangerOf(slot: InfiniteBountySlot): String =
        I18n[BountyKeys.I18N_CATEGORY, "danger.level.${slot.dangerLevel}"]

    private fun nameOf(slot: InfiniteBountySlot): String =
        I18n.t(I18N_CATEGORY, "infinite.name", "code" to slot.code)

    private fun descOf(slot: InfiniteBountySlot): String = I18n.t(
        I18N_CATEGORY, "infinite.desc",
        "code" to slot.code,
        "danger" to dangerOf(slot),
        "reward" to slot.quotedReward.toString(),
    )

    private fun fleetNameOf(slot: InfiniteBountySlot): String =
        I18n.t(I18N_CATEGORY, "infinite.fleet_name", "code" to slot.code)

    private fun receiptOf(slot: InfiniteBountySlot): String =
        I18n.t(I18N_CATEGORY, "infinite.receipt", "code" to slot.code)

    /**
     * 无限工单 spec：与 [MagicBountyBridge] 同一套口径（assassination / 分局空间站挂出 /
     * credit_reward=0 由本侧结算），差异仅在文案来源（独立字符串表）与独立战斗结果键。
     */
    private fun buildSpec(slot: InfiniteBountySlot): MagicBountySpec {
        // 独立战斗结果键，不能提前满足任何其它工单的核销门槛；每代 key 唯一，不会误结算旧阶段。
        val jobMemKey = "\$astd_battle_${slot.key}"

        return MagicBountySpec(
            // trigger：只在分局空间站赏金板挂出；出现门槛由注册时机（归档后）保证。
            BountyKeys.STATION_TRIGGER_MARKET_IDS,
            emptyList(),
            false,
            emptyList(),
            false,
            -1,
            -1,
            -1,
            0,
            1.0f,
            emptyMap(),
            emptyMap(),
            emptyMap(),
            emptyMap(),
            emptyMap(),
            -99.0f,
            99.0f,
            // job
            nameOf(slot),
            descOf(slot),
            null,
            "",
            I18n[BountyKeys.I18N_CATEGORY, "generic.fail"],
            I18n[BountyKeys.I18N_CATEGORY, "generic.expired"],
            null,
            dangerOf(slot),
            -1,
            0,
            0.0f,
            0.0f,
            emptyMap(),
            "assassination",
            true,
            false,
            "FlagshipText",
            "Vague",
            true,
            I18n[BountyKeys.I18N_CATEGORY, "generic.option.accept"],
            null,
            jobMemKey,
            null,
            // target
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            -1,
            -1,
            OfficerManagerEvent.SkillPickPreference.GENERIC,
            emptyMap(),
            // fleet
            fleetNameOf(slot),
            "remnant",
            slot.flagshipVariantId,
            nameOf(slot),
            false,
            false,
            emptyMap(),
            false,
            0.0f,
            slot.baselineFP,
            "remnant",
            1.0f,
            false,
            true,
            FleetAssignment.DEFEND_LOCATION,
            "AGGRESSIVE",
            null,
            // location：遗存目标沿用主线第三章起口径（remnant 主题遗址）。
            emptyList(),
            emptyList(),
            "VAGUE",
            listOf("theme_remnant"),
            emptyList(),
            emptyList(),
            true,
            true,
        )
    }
}
