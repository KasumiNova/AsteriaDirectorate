---
name: "dialog-dsl-guidelines"
description: "Dialog DSL 使用指南：对话图、节点、选项、动作、延迟文本队列与常见用法。"
---

# Skill：Dialog DSL 使用指南

## 适用范围

适用于本项目内 **Dialog DSL** 的对话定义与交互实现：

- `cn.kasuminova.astd.campaign.dialog.core.*`
- 示例：`campaign/dialog/demo/DemoDialog.kt`

## 核心概念

### 1) DialogGraph（对话图）

- 由 **nodeId → DialogNode** 映射组成
- `startNodeId` 为起始节点
- `dialogGraph(start = ...) { node(id, node) }` 构建

**重要约束**：

- **节点对象可以是单例**，但不要在节点对象内部存放可变状态
- 可变状态应放在：
  - `DialogContext.sessionState`
  - 或 MemoryAPI（`ctx.globalMemory / ctx.playerMemory / ctx.marketMemory ...`）

### 2) DialogNode（节点）

节点定义一个“状态段”，主要回调：

- `onEnter(ctx)`：进入节点时调用（输出文本/初始化状态）
- `onAdvance(ctx, amount)`：每帧逻辑（计时/自动跳转）
- `buildOptions(ctx)`：生成选项列表

**选项锁定机制**：

- `lockOptionsWhileTextQueueActive(ctx)`：文本队列未输出完时锁定选项
- `showSkipWhileLocked(ctx)`：锁定时是否显示“跳过”按钮

### 3) DialogDsl（轻量 DSL）

常用构造函数：

- `DialogDsl.node { ... }`：普通节点（默认不锁定选项）
- `DialogDsl.timedNode { ... }`：逐条延迟输出节点（自动锁定选项）
- `DialogDsl.option(...)`：选项
- `DialogDsl.goto(nodeId)` / `DialogDsl.close()` / `DialogDsl.run { ... }`

### 4) DialogAction（选项动作）

- `Goto(nodeId)`：切换节点
- `Close(asCancel)`：关闭对话
- `Run(block, then)`：执行逻辑后可链式跳转

> 如果未来要把对话转成数据驱动（JSON/YAML/ss-csv），避免序列化 lambda；应改为 actionId + 映射。

### 5) DialogContext（运行时上下文）

常用能力：

- 文本输出：`say(...)` / `sayI18n(...)`
- 延迟文本：`enqueue(...)` / `enqueueI18n(...)`
- 淡入淡出：`enqueueFading(...)`
- HUD 提示：`hudMessage(...)` / `hudMessageI18n(...)`
- 选项刷新：`markOptionsDirty()`
- 跳转/关闭：`goto(...)` / `close(...)`

## 延迟文本队列（TimedTextQueue）

- `delay` 为 **相对上一条** 的延迟（秒）
- `timedNode` 默认 **锁定选项**，直到队列输出完成
- “跳过”会调用 `textQueue.flush()`，立即输出剩余内容
- `fadeIn/hold/fadeOut` 用于渐显/停留/淡出（注意淡出后仍占布局高度）

## 交互流程（GraphDialogPlugin）

- 使用 `GraphDialogPlugin(graph, closeOnEscapeOptionId, closeOnEscapeText)`
- 首次进入：执行 `onEnter` + 刷新选项
- 每帧：
  - 推进 `textPanel.advance()`
  - 推进 `TimedTextQueue.advance()`
  - 调用 `onAdvance()`
  - 按需刷新选项

**Escape 关闭**：

- `setOptionOnEscape` 通过 `closeOnEscapeOptionId` 统一处理

## 推荐写法

- **i18n 优先**：用 `ctx.sayI18n(...)` / `ctx.enqueueI18n(...)`
- **多段播报**：使用 `timedNode` 并把逐条文本 `enqueue`
- **状态存储**：用 `ctx.sessionState` 或 MemoryAPI
- **避免节点内状态**：不要在 `object DialogNode` 内持久化可变字段

## 示例入口

- `campaign/dialog/demo/DemoDialog.kt`：
  - 普通节点 + Timed 节点
  - `enqueueI18nFading` 示例
  - `DialogDsl.option + goto/close` 示例

## 常见坑位

- 文本队列还在输出，选项仍可点 → 没有启用 `timedNode` 或锁定逻辑
- 选项未刷新 → 忘记 `markOptionsDirty()`（enqueue 会自动触发）
- 节点状态错乱 → 把可变状态放在单例节点字段里
- 选项回显重复 → `ctx.addOptionSelectedEcho` 需在 optionSelected 中统一控制

## 参考文件

- `campaign/dialog/core/DialogDsl.kt`
- `campaign/dialog/core/DialogGraph.kt`
- `campaign/dialog/core/DialogNode.kt`
- `campaign/dialog/core/GraphDialogPlugin.kt`
- `campaign/dialog/core/DialogContext.kt`
- `campaign/dialog/core/TimedTextQueue.kt`
- `campaign/dialog/demo/DemoDialog.kt`
