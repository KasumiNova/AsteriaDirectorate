package cn.kasuminova.astd.api.combat

/**
 * 双模式切换器（astd_dual_mode_switcher）的玩法开关读取面。
 *
 * 动机：「自动模式免自动化点数」是 LunaLib 全局开关（默认开启）。
 * 双模式状态机（ASTDDualModeConfig 的 no_auto_penalty 标签同步）直接读取实现单例的当前值，
 * 禁止各处自行读取 LunaLib 设置或缓存开关状态。
 *
 * 实现：cn.kasuminova.astd.impl.combat.DualModeSettingsImpl（object 单例）。
 * 设置读写全部发生在 impl 侧 LunaLib 设置注册/回调路径；本接口及实现不触碰 LunaLib 类型
 * （单元测试环境没有 LunaLib）。
 */
interface DualModeSettings {

    /**
     * 带双模式切换器的舰船处于自动模式时，是否不计入原版自动化舰船点数。
     *
     * 实现口径：开启时给无人模式 variant 挂原版 `no_auto_penalty` 标签——该标签在原版同时豁免
     * 自动化点数计入（BaseSkillEffectDescription.getAutomatedPoints）与 Automated 船插的
     * 最大 CR 惩罚（com.fs.starfarer.api.impl.hullmods.Automated），二者在原版同属
     * 「无自动化惩罚」语义，无法只豁免其一。
     */
    val automatedModeExemptFromAutoPoints: Boolean
}
