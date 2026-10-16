package cn.kasuminova.astd.combat.hullmods.base

import com.fs.starfarer.api.combat.ShipVariantAPI
import com.fs.starfarer.api.loading.HullModSpecAPI

/**
 * 内置船插动态图标解析：同一 hullmod id 在不同舰上需展示不同贴图变体。
 *
 * 动机：纳米重构协议 / 奇点稳定器的素材分 arc（电容蓝）/ lens（信号紫）谱系变体，
 * 双模式切换器分 crew（乘员席位）/ auto（AI 核心）形态变体（其内部模式状态船插
 * hiddenEverywhere 不向玩家展示，形态区分只能由切换器图标承担）。而 hull_mods.csv 的
 * sprite 是全局静态值，唯一运行期出口是 [HullModSpecAPI.setSpriteName]——spec 全局共享，
 * 改动仅进程内生效、不入存档。各船插在 applyEffectsBeforeShipCreation 按当前结算 variant
 * 的语境调用本对象换贴：refit / 舰队界面每次重算 stats 即收敛到当前舰对应变体。
 *
 * 已知边界（接受）：spec 贴图全局最后一次写入生效，无 variant 语境的展示位（图鉴 hullmod
 * 列表等）显示最近一次结算的变体；这三个船插均为内置件、不进安装选择器，无实际影响。
 */
object ASTDHullmodSprites {

    const val SWITCHER_CREW = "graphics/hullmods/astd_dual_mode_switcher_crew.png"
    const val SWITCHER_AUTO = "graphics/hullmods/astd_dual_mode_switcher_auto.png"
    const val NANO_ARC = "graphics/hullmods/astd_nano_restoration_protocol_arc.png"
    const val NANO_LENS = "graphics/hullmods/astd_nano_restoration_protocol_lens.png"
    const val STABILIZER_ARC = "graphics/hullmods/astd_singularity_stabilizer_arc.png"
    const val STABILIZER_LENS = "graphics/hullmods/astd_singularity_stabilizer_lens.png"

    /** 谱系标记：LENS 谱系舰船统一内置 lens 奇点能源（.ship builtInMods 口径），以其存在判定谱系。 */
    private const val LENS_LINEAGE_MARKER = "astd_lens_singularity_power"

    /** 换贴入口：目标与当前一致时不写（stats 重算频繁，避免无意义赋值）。 */
    fun swap(spec: HullModSpecAPI?, sprite: String) {
        if (spec == null || spec.spriteName == sprite) return
        spec.spriteName = sprite
    }

    /** 切换器图标：按 variant 当前模式位（permaMods）选形态变体；缺模式态按载人（与状态机缺省一致）。 */
    fun switcherSpriteFor(variant: ShipVariantAPI, config: ASTDDualModeConfig): String =
        if (variant.permaMods.contains(config.automatedModeId)) SWITCHER_AUTO else SWITCHER_CREW

    /** 谱系图标：LENS 谱系选 lens 变体，否则 arc 变体（含 lh_001 等 ARC 侧联制舰）。 */
    fun lineageSpriteFor(variant: ShipVariantAPI, arcSprite: String, lensSprite: String): String =
        if (variant.hasHullMod(LENS_LINEAGE_MARKER)) lensSprite else arcSprite
}
