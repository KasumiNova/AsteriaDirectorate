package cn.kasuminova.astd.campaign.ending

import cn.kasuminova.astd.campaign.bounty.BountyState
import cn.kasuminova.astd.campaign.bounty.FleetComposer
import cn.kasuminova.astd.campaign.bounty.InfiniteSettleRecord
import cn.kasuminova.astd.campaign.bounty.InfiniteSlotState
import cn.kasuminova.astd.campaign.bounty.LockedFleetPlan
import cn.kasuminova.astd.campaign.bounty.StandardCores
import cn.kasuminova.astd.campaign.ui.HudMessages
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.FleetAssignment
import java.awt.Color
import org.apache.log4j.Logger
import org.magiclib.bounty.MagicBountyCoordinator
import org.magiclib.bounty.MagicBountySpec

/**
 * 《无限期承包合同》无限赏金 ↔ MagicBounty 桥接层（游戏侧副作用集中在这里）。
 *
 * 与主线 [cn.kasuminova.astd.campaign.bounty.MainBountyBridge] 的分工：
 * - 主线工单定义在注册表（MainBounties），无限赏金的每代参数在 [InfiniteSlotState]（存档态）；
 * - 报价在生成时锁定进 [InfiniteSlotState.quotedReward]（13：报价锁定），不走 quotedRewards 表；
 * - 失败防死档与主线同口径：POSTED 条目消失/失败终态 → 清理 MagicLib 残留 →
 *   **换代重滚**（新目标/新报价，与交付核销换代同路径，[InfiniteBountyGenerator.regenerateSlot]），
 *   生命周期归待接取，下一 tick 重新挂出（[tick]）；随机目标每代重滚，
 *   同一槽位连续失败亦正常换代（防呆）；
 * - 核销走「分局终端交付」：[settle] 发放锁定报价 → 记账 → [InfiniteBountyGenerator.regenerateSlot]
 *   换代重挂。
 *
 * MagicBounty 生命周期口径（completed 残留/resetBounty 安全性）与主线一致，
 * 见 MainBountyBridge 类注释。
 */
object InfiniteBountyBridge {

    /** i18n category（无限赏金文案表 infinite_bounty_strings.json）。 */
    private const val CAT = "asteria_directorate_bounty"

    private val log: Logger = Global.getLogger(InfiniteBountyBridge::class.java)

    private val RECEIPT_COLOR = Color(200, 170, 120)

    /** 槽位随机种子基址（与槽位序号/换代序号异或出每代种子；存档无关的固定常量）。 */
    const val SEED_BASE: Long = 0xA5701EA5L

    /**
     * 周期性维护：补齐槽位 → 挂出待接取工单 → 失败/消失终态回收重挂。
     * 由 [cn.kasuminova.astd.campaign.bounty.BountyCampaignManager.advance] 调用。
     */
    fun tick(state: BountyState, coord: MagicBountyCoordinator) {
        if (!state.indefiniteContractor) return
        val kS = DifficultyTuningImpl.fixedScale

        val created = InfiniteBountyGenerator.ensureSlots(state, kS, SEED_BASE)
        for (slot in created) {
            log.info("[ASTD] 无限赏金槽位初始化：${InfiniteBountyGenerator.serialOf(slot.index, slot.generation)}")
        }

        val active = coord.activeBounties
        for (slot in state.infiniteSlots) {
            val key = InfiniteBountyGenerator.keyOf(slot.index, slot.generation)
            when (slot.lifecycle) {
                "POSTED" -> if (!active.containsKey(key)) {
                    // 失败/消失终态（含被第三方消灭、尸体消散）：清残留 → 换代重滚 → 下 tick 重挂
                    onInfiniteFailed(state, key, slot, coord)
                }
                "DESTROYED" -> Unit // 待分局终端核销
                else -> postSlot(state, slot, coord)
            }
        }
    }

    /**
     * 挂出并激活一张无限赏金工单（当前代）。
     *
     * 旗舰 variant 在首次挂出时从模组标准装配池按种子选定并写回槽位（同代重挂沿用）。
     *
     * @return 是否成功（落点/舰队创建失败记错误日志并返回 false，下 tick 重试）
     */
    fun postSlot(state: BountyState, slot: InfiniteSlotState, coord: MagicBountyCoordinator): Boolean {
        val sector = Global.getSector()
        if (sector == null) {
            log.error("[ASTD] 挂出无限赏金失败：sector 不可用（槽位 ${slot.index}）")
            return false
        }
        val source = sector.playerFleet
        if (source == null) {
            log.error("[ASTD] 挂出无限赏金失败：playerFleet 不可用（槽位 ${slot.index}）")
            return false
        }

        if (slot.flagshipVariantId.isEmpty()) {
            slot.flagshipVariantId = pickFlagshipVariant(slot.seed)
        }

        val key = InfiniteBountyGenerator.keyOf(slot.index, slot.generation)

        // 挂出（接取）时锁定舰队组建：舰载核心配置与核心打捞表由同一份组建结果滚动定型
        // （本代种子已锁定在槽位，lockKey 即本代工单 key；换代重滚自然换新锁定），
        // 失败重挂沿用本代首次锁定，保证「掉落的正是舰队里装的」
        val plan = StandardCores.lockFleetPlan(state.lockedFleetPlans, key) {
            val comp = FleetComposer.buildComposition(
                InfiniteBountyGenerator.toBountyDef(slot),
                slot.seed xor 0xC0E57A11L,
            )
            LockedFleetPlan(comp, StandardCores.rollCoreLoot(comp.officerCoreIds, slot.seed xor 0x1007L))
        }

        if (coord.completedBounties.remove(key)) {
            log.info("[ASTD] 清理无限赏金 completed 残留标记：$key")
        }

        val active = try {
            coord.createActiveBounty(key, buildSpec(slot, plan.coreLoot))
        } catch (t: Throwable) {
            log.error("[ASTD] 创建无限赏金异常：$key", t)
            return false
        }
        if (active == null) {
            log.error("[ASTD] 创建无限赏金失败（无合适落点或舰队生成失败）：$key")
            return false
        }

        active.acceptBounty(source, null, null, null)
        slot.lifecycle = "POSTED"
        // 挂出即目标在世：清理本代可能残留的处理标记，保证当前代可被再次处理
        state.concludedBountyKeys.remove(key)
        state.patchedBountyKeys.remove(key)

        log.info(
            "[ASTD] 无限赏金已挂出：${InfiniteBountyGenerator.serialOf(slot.index, slot.generation)}" +
                "（$key，危险级 ${slot.danger}，${slot.fp} FP，报价 ${slot.quotedReward}）",
        )
        return true
    }

    /** 无限赏金击毁（MagicBounty Succeeded）处理：登记已击毁待核销。幂等由管理脚本的标记保证。 */
    fun onSucceeded(key: String, state: BountyState) {
        val slot = state.infiniteSlots.firstOrNull {
            InfiniteBountyGenerator.keyOf(it.index, it.generation) == key
        } ?: return
        slot.lifecycle = "DESTROYED"
        HudMessages.campaign(
            I18n.t(CAT, "hud.infinite.pending_settle", "serial" to InfiniteBountyGenerator.serialOf(slot.index, slot.generation)),
            RECEIPT_COLOR,
        )
        log.info("[ASTD] 无限赏金目标已击毁，待分局终端交付核销：$key")
    }

    /**
     * 「分局终端交付核销」结算入口（终端 UI 调用；按工单 key 路由）。
     *
     * 校验已击毁 → 发放锁定报价 → 记核销流水 → 换代重滚（下 tick 重新挂出）。
     *
     * @return 是否核销成功
     */
    fun settleByKey(state: BountyState, key: String): Boolean {
        val slot = state.infiniteSlots.firstOrNull {
            InfiniteBountyGenerator.keyOf(it.index, it.generation) == key
        } ?: return false
        return settle(state, slot.index)
    }

    /**
     * 「分局终端交付核销」结算入口（按槽位序号）。
     *
     * @return 是否核销成功（槽位不存在/未击毁/玩家货舱不可用 → false 并记日志）
     */
    fun settle(state: BountyState, slotIndex: Int): Boolean {
        val sector = Global.getSector()
        if (sector == null) {
            log.error("[ASTD] 无限赏金核销失败：sector 不可用（槽位 $slotIndex）")
            return false
        }
        val slot = state.infiniteSlots.firstOrNull { it.index == slotIndex }
        if (slot == null || slot.lifecycle != "DESTROYED") {
            log.warn("[ASTD] 无限赏金核销被拒绝：槽位 $slotIndex 不在待核销状态")
            return false
        }
        val cargo = sector.playerFleet?.cargo
        if (cargo == null) {
            log.error("[ASTD] 无限赏金核销发款失败：playerFleet 不可用（槽位 $slotIndex，金额 ${slot.quotedReward}）")
            return false
        }

        val serial = InfiniteBountyGenerator.serialOf(slot.index, slot.generation)
        cargo.credits.add(slot.quotedReward.toFloat())
        state.infiniteSettlements += InfiniteSettleRecord(serial, slot.quotedReward)

        HudMessages.campaign(
            I18n.t(CAT, "hud.infinite.settled", "serial" to serial, "amount" to slot.quotedReward),
            RECEIPT_COLOR,
        )

        val next = InfiniteBountyGenerator.regenerateSlot(state, slotIndex, DifficultyTuningImpl.fixedScale, SEED_BASE)
        log.info(
            "[ASTD] 无限赏金已核销：$serial，发放 ${slot.quotedReward}；换代至 " +
                (next?.let { InfiniteBountyGenerator.serialOf(it.index, it.generation) } ?: "（失败）"),
        )
        return true
    }

    /**
     * 失败终态处理入口（管理脚本在条目仍 active 时探测到失败终态即调用；
     * 条目已消失的兜底由 [tick] 的 POSTED 探测覆盖）。
     */
    fun onFailed(key: String, state: BountyState, coord: MagicBountyCoordinator) {
        val slot = slotOf(state, key) ?: return
        onInfiniteFailed(state, key, slot, coord)
    }

    /**
     * 失败终态处理：清理 MagicBounty 侧状态并**换代重滚**（[InfiniteBountyGenerator.regenerateSlot]，
     * 新目标/新报价，与交付核销换代同路径；随机目标每代重滚，同一槽位连续失败亦正常换代，防呆）。
     * resetBounty 仅在条目仍 active 时安全（口径见 MainBountyBridge 类注释）。
     */
    private fun onInfiniteFailed(state: BountyState, key: String, slot: InfiniteSlotState, coord: MagicBountyCoordinator) {
        log.warn("[ASTD] 无限赏金失败终态，清理并换代重滚：$key")
        try {
            if (coord.getActiveBounty(key) != null) {
                coord.resetBounty(key)
            } else {
                coord.completedBounties.remove(key)
            }
        } catch (t: Throwable) {
            log.error("[ASTD] 无限赏金 MagicBounty 状态重置异常：$key", t)
        }
        val next = InfiniteBountyGenerator.regenerateSlot(state, slot.index, DifficultyTuningImpl.fixedScale, SEED_BASE)
        log.info(
            "[ASTD] 无限赏金失败换代：${InfiniteBountyGenerator.serialOf(slot.index, slot.generation)} → " +
                (next?.let { InfiniteBountyGenerator.serialOf(it.index, it.generation) } ?: "（槽位缺失）"),
        )
    }

    /** 旗舰 variant 选取：模组标准装配池按种子确定性取值；池为空记错误日志（此时 fleet 生成会失败重试）。 */
    private fun pickFlagshipVariant(seed: Long): String {
        val pool = Global.getSettings().allVariantIds
            ?.filter { it.startsWith("astd_") && it.endsWith("_Standard") }
            ?.sorted()
            ?: emptyList()
        if (pool.isEmpty()) {
            log.error("[ASTD] 无限赏金旗舰选取失败：模组标准装配池为空（astd_*_Standard variant 未加载）")
            return ""
        }
        return pool[java.util.Random(seed xor 0xF1A6).nextInt(pool.size)]
    }

    /** 按 key 反查槽位（管理脚本 patch/终态处理用）。 */
    fun slotOf(state: BountyState, key: String): InfiniteSlotState? = state.infiniteSlots.firstOrNull {
        InfiniteBountyGenerator.keyOf(it.index, it.generation) == key
    }

    /**
     * 构造无限赏金当前代的 MagicBounty 规格（代码注册，不经过 magicBounty_data.json）。
     *
     * 口径与主线一致：赏金自身信用点/声望奖励置 0（报酬由 [settle] 发放）；无时限；
     * 舰队缩放由本模组管线负责（fleet_scaling_multiplier=0）；[itemReward] 为挂出时
     * 锁定的核心打捞表（lockKey = 本代工单 key，见 [postSlot]）。
     */
    private fun buildSpec(slot: InfiniteSlotState, itemReward: Map<String, Int>): MagicBountySpec {
        val serial = InfiniteBountyGenerator.serialOf(slot.index, slot.generation)
        val factionName = Global.getSector()?.getFaction(slot.targetFactionId)?.displayName ?: slot.targetFactionId

        val description = buildString {
            append(
                I18n.t(
                    CAT, "main.infinite.desc",
                    "serial" to serial,
                    "faction" to factionName,
                    "fp" to slot.fp,
                ),
            )
            for (affixId in slot.affixIds) {
                append('\n')
                append(I18n[CAT, "affix.$affixId.clause"])
            }
        }

        return MagicBountySpec(
            // ── trigger_*：不走赏金板刷新，全部置空/置零 ──
            emptyList(), // trigger_market_id
            emptyList(), // trigger_marketFaction_any
            false, // trigger_marketFaction_alliedWith
            emptyList(), // trigger_marketFaction_none
            false, // trigger_marketFaction_enemyWith
            0, // trigger_market_minSize
            0, // trigger_player_minLevel
            0, // trigger_min_days_elapsed
            0, // trigger_min_fleet_size
            0f, // trigger_weight_mult
            emptyMap(), // trigger_memKeys_all
            emptyMap(), // trigger_memKeys_any
            emptyMap(), // trigger_memKeys_none
            emptyMap(), // trigger_playerRelationship_atLeast
            emptyMap(), // trigger_playerRelationship_atMost
            null, // trigger_giverTargetRelationship_atLeast
            null, // trigger_giverTargetRelationship_atMost
            // ── job_* ──
            I18n.t(CAT, "main.infinite.name", "serial" to serial), // job_name
            description, // job_description
            null, // job_comm_reply
            I18n.t(CAT, "main.infinite.receipt", "serial" to serial), // job_intel_success
            null, // job_intel_failure
            null, // job_intel_expired
            null, // job_forFaction
            I18n.t(CAT, "danger.${slot.danger}"), // job_difficultyDescription
            0, // job_deadline：无时限
            0, // job_credit_reward：报酬由 settle 发放
            0f, // job_credit_scaling
            0f, // job_reputation_reward
            // 核心打捞表（挂出时锁定，掉落的正是舰队实际装舰核心；O 档不可获取不参与打捞，
            // 口径见 StandardCores.rollCoreLoot）。不可为 null：generatePlayerLoot 不判空直接迭代 entrySet
            itemReward, // job_item_reward
            "destruction", // job_type
            false, // job_show_type
            false, // job_show_captain
            "vanilla", // job_show_fleet
            "exact", // job_show_distance
            true, // job_show_arrow
            null, // job_pick_option
            null, // job_pick_script
            "\$${InfiniteBountyGenerator.keyOf(slot.index, slot.generation)}", // job_memKey
            null, // job_conclusion_script
            null, // existing_target_memkey
            // ── target_* ──
            null, // target_importantPersonId
            null, // target_first_name
            null, // target_last_name
            null, // target_portrait
            null, // target_gender
            null, // target_rank
            null, // target_post
            null, // target_personality
            null, // target_aiCoreId
            0, // target_level
            0, // target_elite_skills
            null, // target_skill_preference
            null, // target_skills
            // ── fleet_* ──
            I18n.t(CAT, "main.infinite.fleet_name", "serial" to serial), // fleet_name
            slot.targetFactionId, // fleet_faction
            slot.flagshipVariantId, // fleet_flagship_variant
            null, // fleet_flagship_name
            false, // fleet_flagship_alwaysRecoverable
            true, // fleet_flagship_autofit
            null, // fleet_preset_ships
            true, // fleet_preset_autofit
            0f, // fleet_scaling_multiplier：缩放由本模组管线负责
            slot.fp, // fleet_min_FP
            slot.targetFactionId, // fleet_composition_faction
            1f, // fleet_composition_quality
            true, // fleet_transponder
            true, // fleet_no_retreat
            FleetAssignment.ORBIT_AGGRESSIVE, // fleet_behavior
            "hostile", // fleet_attitude
            null, // fleet_musicSetId
            // ── location_*：常设辖区安全维护，跳点偏好任意落位（无锚点工单与主线同口径：
            //    location 偏好全空时 MagicCampaign.findSuitableTarget 直接返回 null，
            //    且偏好检索要求 location_themes 非 null，见 MainBountyBridge.buildSpec 注释）。 ──
            null, // location_marketIDs
            null, // location_marketFactions
            null, // location_distance
            emptyList(), // location_themes
            emptyList(), // location_themes_blacklist
            listOf("jump_point"), // location_entities：任意跳点落位偏好
            false, // location_prioritizeUnexplored
            true, // location_defaultToAnyEntity
        )
    }
}
