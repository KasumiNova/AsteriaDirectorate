package cn.kasuminova.astd.campaign.bounty

import cn.kasuminova.astd.campaign.ui.HudMessages
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.impl.campaign.ids.Commodities
import com.fs.starfarer.api.loading.WeaponSpecAPI
import java.awt.Color
import java.util.Random
import kotlin.math.max

/**
 * 赏金报酬结算。
 *
 * 说明：MagicBounty 自带的 credit/reputation/item_reward 机制偏静态配置；
 * 本模组在代码侧按固有难度系数 k_s（轨一，玩家 LunaLib 自选档位 1.0~5.0）动态发放，
 * 口径见 docs/story 各章数值表（批次结清奖金“随难度系数缩放、最高 5×”即 k_s 上限）。
 */
object BountyRewards {

    private val RECEIPT_COLOR = Color(200, 170, 120)

    data class SideLootResult(
        val weapons: Map<String, Int>,
        val commodities: Map<String, Int>,
    )

    /** 主线单票报酬使用接取时已锁定的报价，调用方负责一次性核销。 */
    fun grantMainPayout(def: BountyDef, amount: Int): Int {
        require(amount >= 0)
        if (amount == 0) return 0
        val credits = requireNotNull(Global.getSector()?.playerFleet?.cargo?.credits) {
            "Cannot settle bounty ${def.key}: player cargo is unavailable"
        }
        credits.add(amount.toFloat())
        HudMessages.campaign(
            I18n.t(BountyKeys.I18N_CATEGORY, "hud.main_payout", "credits" to amount.toString()),
            RECEIPT_COLOR,
        )
        return amount
    }

    /**
     * 结清组（批次/线路/章）结清奖金：基数 × k_s（k_s ≤ 5，即“最高 5×”）。
     *
     * @return 实际发放星币数
     */
    fun grantGroupBonus(group: MainBounties.GroupDef): Int {
        if (group.bonusBase <= 0) return 0
        val amount = computeGroupBonus(group.bonusBase, DifficultyTuningImpl.fixedScale)
        val sector = Global.getSector() ?: return 0
        val credits = sector.playerFleet?.cargo?.credits
        if (credits == null) {
            Global.getLogger(BountyRewards::class.java).warn("[BountyRewards] 发放结清奖金失败：无玩家舰队（group=${group.id}, amount=$amount）")
            return 0
        }
        credits.add(amount.toFloat())
        return amount
    }

    /** 单票报酬纯计算：区间内均匀抽取后按 k_s 缩放。 */
    fun computeTicketPayout(min: Int, max: Int, ks: Float, rnd: Random): Int {
        if (max <= 0 || max < min) return 0
        val base = min + rnd.nextInt(max - min + 1)
        return (base * ks).toInt()
    }

    /** 结清奖金纯计算：基数 × k_s（k_s 上限 5 ⇒ 最高 5×）。 */
    fun computeGroupBonus(base: Int, ks: Float): Int =
        if (base <= 0) 0 else (base * ks.coerceIn(1f, 5f)).toInt()

    fun grantSideLoot(bountyKey: String, threatTier: Int, k: Float, seed: Long): SideLootResult {
        val sector = Global.getSector() ?: return SideLootResult(emptyMap(), emptyMap())
        val cargo = sector.playerFleet?.cargo ?: return SideLootResult(emptyMap(), emptyMap())

        val rnd = Random(seed xor bountyKey.hashCode().toLong())

        val modWeapons = pickModWeapons(rnd, threatTier, k)
        for ((wid, count) in modWeapons) {
            cargo.addWeapons(wid, count)
        }

        val comm = LinkedHashMap<String, Int>()
        // AI cores：支线里更常见（但数量控制住）
        val gamma = (1 + (k * 2f).toInt()).coerceIn(1, 3)
        comm[Commodities.GAMMA_CORE] = gamma
        if (threatTier >= 4 && rnd.nextFloat() < 0.55f) {
            comm[Commodities.BETA_CORE] = 1
        }
        if (threatTier >= 5 && rnd.nextFloat() < 0.25f + 0.25f * k) {
            comm[Commodities.ALPHA_CORE] = 1
        }

        for ((cid, count) in comm) {
            cargo.addCommodity(cid, count.toFloat())
        }

        // 小额补给：避免支线变成“亏补给的剧情税”。
        val supplies = (15 + threatTier * 8 + (k * 25f).toInt()).coerceAtMost(120)
        cargo.addSupplies(supplies.toFloat())

        HudMessages.campaign(
            I18n.t(BountyKeys.I18N_CATEGORY, "hud.side_loot", "count" to modWeapons.values.sum().toString()),
            RECEIPT_COLOR
        )

        val commOut = LinkedHashMap(comm)
        commOut[Commodities.SUPPLIES] = supplies
        return SideLootResult(modWeapons, commOut)
    }

    private fun pickModWeapons(rnd: Random, threatTier: Int, k: Float): Map<String, Int> {
        val all = Global.getSettings().allWeaponSpecs
        val pool = all
            .asSequence()
            .filterNotNull()
            .filter { it.weaponId.startsWith("astd_") }
            .filter { isSaneLootWeapon(it) }
            .toList()

        if (pool.isEmpty()) return emptyMap()

        // 数量随 tier 与 k 增长，但控制在 2~6。
        val count = (2 + (threatTier / 2) + (k * 3f).toInt()).coerceIn(2, 6)
        val result = LinkedHashMap<String, Int>()

        // 轻度偏好高 tier 武器。
        val weighted = pool.flatMap { spec ->
            val w = max(1, spec.tier)
            val bonus = if (k > 0.6f && spec.tier >= 2) 1 else 0
            List((w + bonus).coerceAtMost(4)) { spec }
        }

        repeat(count) {
            val pick = weighted[rnd.nextInt(weighted.size)]
            result[pick.weaponId] = (result[pick.weaponId] ?: 0) + 1
        }
        return result
    }

    private fun isSaneLootWeapon(spec: WeaponSpecAPI): Boolean {
        // 过滤掉明显不适合作为打捞奖励的条目（后续可补 tag 规则）。
        val tags = spec.tags
        if (tags != null) {
            if (tags.contains("NO_DROP")) return false
            if (tags.contains("DECORATIVE")) return false
        }
        return true
    }
}
