package cn.kasuminova.astd.impl.difficulty

import com.fs.starfarer.api.Global

/** 本类不得引用 `lunalib.*`，所有 LunaLib 类型只能在可用性检查后加载。 */
object LunaLibSupport {

    const val LUNALIB_MOD_ID: String = "lunalib"

    fun isAvailable(): Boolean =
        Global.getSettings().modManager.isModEnabled(LUNALIB_MOD_ID)
}
