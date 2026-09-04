package cn.kasuminova.astd.campaign.bounty

import cn.kasuminova.astd.campaign.ui.HudMessages
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.impl.campaign.ids.Commodities
import com.fs.starfarer.api.loading.WeaponSpecAPI
import java.awt.Color
import java.util.Random
import kotlin.math.max

/**
 * 支线/故事碎片的“额外打捞奖励”。
 *
 * 说明：MagicBounty 自带的 credit/reputation/item_reward 机制更偏静态配置。
 * 本模组选择在代码侧按难度 k 与 tier 动态发放，以匹配“机制与词缀驱动”的体验。
 */
object BountyRewards {

    data class SideLootResult(
        val weapons: Map<String, Int>,
        val commodities: Map<String, Int>,
    )

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
                I18n.t("asteria_directorate_bounty", "hud.side_loot", "count" to modWeapons.values.sum().toString()),
                Color(200, 170, 120)
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
            val give = if (pick.size.name.equals("LARGE", ignoreCase = true) && rnd.nextFloat() < 0.65f) 1 else 1
            result[pick.weaponId] = (result[pick.weaponId] ?: 0) + give
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
