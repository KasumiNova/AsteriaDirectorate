---
name: "system-status-text-guidelines"
description: "舰船系统状态文本规范：getStatusData 返回台词风格描述，而非属性数值列表；多状态与多模式的键名约定。"
---

# Skill：系统状态文本规范（台词风格）

## 目标

用简短、有代入感的台词（flavor text）取代枯燥的属性变化描述，在战斗 HUD 底部左侧状态栏传递系统激活的"感受"，而非数值说明。

> 参考：Bultach Coalition `bt_temporalshell_dash.java`
> - IN: "Grasping threads"
> - ACTIVE: "Time is your birthright"
> - OUT: "Releasing threads"

## 约束（强制）

- `getStatusData(index, state, effectLevel)` 中 **index 0** 是主状态行；其余 index 可用于额外信息但不得写属性数值。
- **不得** 在状态文本中写具体百分比或属性名（如 "+50% ROF"、"护盾减耗 -14%"）。
- **文本必须是 i18n key**，从 `strings.json` 读取，不得硬编码中文。
- 每个 `State`（IN / ACTIVE / OUT）必须返回不同的文本，区分系统的"充能中 / 激活 / 结束"阶段感。
- 若系统有多种模式（如载人 / 自动），必须为每种模式独立提供三阶段文本，不得共用同一套文字。

## 键名约定

```
system.<system_id_without_astd_prefix>.status.<mode>.<state>
```

- `system_id_without_astd_prefix`：系统 CSV id 去掉 `astd_` 前缀，如 `arc_flare_overdrive`
- `mode`：模式名（单模式用 `default`；多模式按语义命名，如 `crewed` / `automated`）
- `state`：`in` / `active` / `out`

**示例（弧光耀斑过驱系统）：**

```json
"system.arc_flare_overdrive.status.crewed.in"    : "弧光链路 2 充能中",
"system.arc_flare_overdrive.status.crewed.active": "耀斑超频已激活",
"system.arc_flare_overdrive.status.crewed.out"   : "超频释放中",
"system.arc_flare_overdrive.status.automated.in" : "目标标定中",
"system.arc_flare_overdrive.status.automated.active": "耀斑突袭已激活",
"system.arc_flare_overdrive.status.automated.out": "电弧消散中"
```

## 实现模式

### 单模式系统

```kotlin
override fun getStatusData(index: Int, state: State, effectLevel: Float): StatusData? {
    if (index != 0) return null
    val suffix = when (state) {
        State.IN     -> "in"
        State.ACTIVE -> "active"
        State.OUT    -> "out"
        else         -> return null
    }
    return StatusData(I18n[I18n.Categories.MOD, "system.<id>.status.default.$suffix"], false)
}
```

### 多模式系统（如载人 / 自动）

- 在类中定义 `private var isAutomatedMode: Boolean = false`
- 在 `apply()` 中每帧更新：`isAutomatedMode = ship.variant?.hasASTDArcFlareAutomatedMode() == true`
- 在 `getStatusData()` 中：

```kotlin
val prefix = if (isAutomatedMode) "automated" else "crewed"
val suffix = when (state) { State.IN -> "in"; State.ACTIVE -> "active"; State.OUT -> "out"; else -> return null }
return StatusData(I18n[I18n.Categories.MOD, "system.<id>.status.$prefix.$suffix"], false)
```

## 写作指南

- **IN**：传达"即将激活"的紧张感，可用动词进行时（充能中、锁定中、追踪中）。
- **ACTIVE**：传达激活状态的感受，可用短语或宣言（1-8 字）。
- **OUT**：传达消退感，可用动词进行时或描述余韵（释放中、消散中、冷却中）。
- 载人模式：偏向稳定、掌控、长时（例：弧光链路充能 / 耀斑超频已激活 / 超频释放中）。
- 自动模式：偏向急速、突袭、短暂（例：目标标定中 / 耀斑突袭已激活 / 电弧消散中）。
