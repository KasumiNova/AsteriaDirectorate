package cn.kasuminova.astd.combat.hullmods.base

import com.fs.starfarer.api.combat.ShipVariantAPI
import com.fs.starfarer.api.loading.HullModSpecAPI

/**
 * 双模式切换器动态图标：同一切换器 hullmod 需按当前模式展示不同形态贴图。
 *
 * 动机：切换器素材分 crew（乘员席位）/ auto（AI 核心）形态变体，其内部模式状态船插
 * hiddenEverywhere 不向玩家展示，形态区分只能由切换器图标承担；而 hull_mods.csv 的
 * sprite 是全局静态值，唯一运行期出口是 [HullModSpecAPI.setSpriteName]——spec 全局共享，
 * 改动仅进程内生效、不入存档。切换器在 applyEffectsBeforeShipCreation 按当前结算 variant
 * 的模式位调用本对象换贴：refit / 舰队界面每次重算 stats 即收敛到当前模式变体。
 *
 * 谱系变体（纳米重构协议 / 奇点稳定器 / 奇点能源）不走本机制——它们按 arc/lens 拆为
 * 独立 hullmod id，贴图与主题色均随 spec 静态确定。
 */
object ASTDHullmodSprites {

    const val SWITCHER_CREW = "graphics/hullmods/astd_dual_mode_switcher_crew.png"
    const val SWITCHER_AUTO = "graphics/hullmods/astd_dual_mode_switcher_auto.png"

    /** 换贴入口：目标与当前一致时不写（stats 重算频繁，避免无意义赋值）。 */
    fun swap(spec: HullModSpecAPI?, sprite: String) {
        if (spec == null || spec.spriteName == sprite) return
        spec.spriteName = sprite
    }

    /** 切换器图标：按 variant 当前模式位（permaMods）选形态变体；缺模式态按载人（与状态机缺省一致）。 */
    fun switcherSpriteFor(variant: ShipVariantAPI, config: ASTDDualModeConfig): String =
        if (variant.permaMods.contains(config.automatedModeId)) SWITCHER_AUTO else SWITCHER_CREW
}
