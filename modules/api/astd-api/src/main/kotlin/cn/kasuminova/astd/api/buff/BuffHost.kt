package cn.kasuminova.astd.api.buff

import com.fs.starfarer.api.combat.WeaponAPI

/**
 * 一艘船的 Buff 登记表，区分舰船级状态与武器槽位关联状态。
 *
 * 所有方法仅在战斗主线程调用。
 */
interface BuffHost {
    /** 返回舰船级 Buff，不存在时返回 null。 */
    fun find(id: String): Buff?

    /** 返回关联指定武器的 Buff；不存在或槽位已换装时返回 null。 */
    fun findByWeapon(id: String, weapon: WeaponAPI): Buff?

    /**
     * 注册舰船级 Buff；[weapon] 非空时改为关联该武器。
     * 同一键已有有效 Buff 时记录 WARN 并覆盖。
     */
    fun register(buff: Buff, weapon: WeaponAPI? = null)

    /** 立即移除 Buff 并调用 [Buff.onRemove]。 */
    fun remove(buff: Buff, weapon: WeaponAPI? = null)

    /** 返回全部 Buff 及其关联武器。返回列表仅供遍历，调用方不得修改。 */
    fun all(): List<Pair<Buff, WeaponAPI?>>
}
