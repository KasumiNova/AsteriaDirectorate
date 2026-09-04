package cn.kasuminova.astd.api.buff

import com.fs.starfarer.api.combat.WeaponAPI

/**
 * 一艘船的全部 Buff 登记表。
 *
 * 动机：隔离 customData 键拼接细节——Ship 级键 `astd_buff:ship:<buffId>`、
 * Weapon 级键 `astd_buff:weapon:<buffId>:<slotId>`（WeaponAPI 无 customData，已核实 jar，
 * 武器级状态只能挂舰船侧复合键）由实现内部生成，调用侧不感知拼接规则；
 * 键级联在 ship 上，船变 hulk/移除时状态随实体生命周期自然终结，无需跨表清理。
 */
interface BuffHost {
    /**
     * 按 id 查 Ship 级 Buff；不存在返回 null。
     */
    fun find(id: String): Buff?

    /**
     * 按 id 查指定武器的 Weapon 级 Buff；不存在（或槽位已换装、登记武器与当前武器不符）返回 null。
     */
    fun findByWeapon(id: String, weapon: WeaponAPI): Buff?

    /**
     * 注册一个 Buff（同键已存在且非换装残留时记录 WARN 并覆盖——属程序错误，不静默）。
     * [weapon] 非空时登记为 Weapon 级（复合键），为空时登记为 Ship 级。
     */
    fun register(buff: Buff, weapon: WeaponAPI? = null)

    /**
     * 立即移除并触发 [Buff.onRemove]。
     */
    fun remove(buff: Buff, weapon: WeaponAPI? = null)

    /**
     * 供 BuffTickPlugin 每帧遍历的内部视图；调用侧不得修改。
     */
    fun all(): List<Pair<Buff, WeaponAPI?>>
}
