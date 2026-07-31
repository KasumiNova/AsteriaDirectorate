---
name: "hullmod-incompatibility-guidelines"
description: "船插互斥/禁装实现规范：当内置船插或特殊舰体需要禁止、清理或提示不兼容船插时使用。"
---

# Skill：船插互斥 / 禁装实现规范

## 先判断目标

- **硬禁止**：非法组合不应保留在 variant 上。优先在创建路径直接清理。
- **软提示**：允许短暂安装，但要提示“被移除/不兼容”。可考虑 MagicLib warning hullmod。
- **装配界面候选项直接灰掉**：候选船插自己的 `isApplicableToShip()` / `canBeAddedOrRemovedNow()` 必须返回 false；若候选是原版/第三方船插，通常需要 agent/AccessTransformer 包装其 effect。

## API 边界

- 玩家在装配界面点击候选船插时，游戏调用的是**候选船插**的 `HullModEffect`。
- 因此，内置船插自身的 `isApplicableToShip()` 只能限制“安装该内置船插”，不能让原版 `shield_shunt` 这类候选项自动灰掉。
- 如果不 patch 候选船插，最低可靠产品行为是：在本船插的创建/刷新路径中强制移除非法项，保证保存、战斗和面板状态不会保留非法组合。

## 推荐硬禁止模式

在控制方船插中集中定义禁止列表，并复用一个清理函数：

```kotlin
private val FORBIDDEN_HULLMOD_IDS = setOf(HullMods.SHIELD_SHUNT)

override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
    stripForbiddenHullMods(stats.variant)
}

override fun applyEffectsAfterShipCreation(ship: ShipAPI, id: String) {
    stripForbiddenHullMods(ship.variant)
}

private fun stripForbiddenHullMods(variant: ShipVariantAPI?) {
    variant ?: return
    FORBIDDEN_HULLMOD_IDS.forEach { forbiddenId ->
        variant.removeMod(forbiddenId)
        variant.removePermaMod(forbiddenId)
        variant.getSMods().remove(forbiddenId)
        variant.getSModdedBuiltIns().remove(forbiddenId)
        variant.removeSuppressedMod(forbiddenId)
    }
}
```

## 何时用 MagicLib warning

- 参考 `MagicIncompatibleHullmods.removeHullmodWithWarning(variant, toRemove, removeCause)`。
- 适合“玩家刚装了冲突船插，需要临时说明哪个被移除”的场景。
- 不适合硬禁止的基础路径：warning hullmod 会引入额外状态和提示残留，不能替代清理函数。

## 要求 UI 直接阻止安装时

- 如果候选船插由本项目实现：在候选船插中实现 `isApplicableToShip()` / `getUnapplicableReason()` / 必要时 `canBeAddedOrRemovedNow()`。
- 如果候选船插是原版或第三方：不要假装控制方内置船插能让它灰掉；需要 agent/AccessTransformer 包装候选 effect，或接受“安装后立即清理”的产品行为。

## 测试约束

- 测试必须覆盖普通 hullmod、permaMod、S-mod、S-modded built-in 的清理。
- 测试应断言禁止列表集中定义，避免散落字符串。
- 若有玩家可见提示，按 `copy-style-guidelines` 审查文案。
