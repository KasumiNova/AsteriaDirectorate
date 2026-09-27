package cn.kasuminova.astd.impl.difficulty

import com.fs.starfarer.api.Global

/**
 * LunaLib 运行时可用性探测（唯一入口）。
 *
 * 动机：LunaLib 是可选前置——mod_info.json 不声明它，未安装时模组照常加载，
 * 全部 LunaLib 设置项取默认值。本类自身不引用任何 lunalib.* 类型，保证在未安装
 * LunaLib 的 JVM 里也能安全加载；所有触碰 LunaLib 类的代码（[DifficultySettingsRegistrar]
 * 注册/回调路径）必须先经本探测门控，避免 NoClassDefFoundError。
 */
object LunaLibSupport {

    /** LunaLib 的模组 id（mod_info.json）。 */
    const val LUNALIB_MOD_ID: String = "lunalib"

    /** LunaLib 是否在已启用模组列表中。 */
    fun isAvailable(): Boolean =
        Global.getSettings().modManager.isModEnabled(LUNALIB_MOD_ID)
}
