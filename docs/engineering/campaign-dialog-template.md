# Campaign 对话模板（GraphDialogPlugin）

本工程新增了一套可复用的战役对话骨架：用 `InteractionDialogPlugin` 驱动“对话图”（node/option/goto），并内置支持“按延迟逐条输出文案”。

## 你得到的东西

核心代码位于：
- `src/main/kotlin/cn/kasuminova/asteriadirectorate/campaign/dialog/core/GraphDialogPlugin.kt`
- `src/main/kotlin/cn/kasuminova/asteriadirectorate/campaign/dialog/core/DialogGraph.kt`
- `src/main/kotlin/cn/kasuminova/asteriadirectorate/campaign/dialog/core/DialogContext.kt`
- `src/main/kotlin/cn/kasuminova/asteriadirectorate/campaign/dialog/core/TimedTextQueue.kt`
- 轻量 DSL：`src/main/kotlin/.../DialogDsl.kt`

示例对话：
- `src/main/kotlin/cn/kasuminova/asteriadirectorate/campaign/dialog/demo/DemoDialog.kt`

示例文案：
- `contents/data/strings/strings.json`（`asteria_directorate` 分类下的 `dialog.demo.*`）

## 基本用法

1) 定义一个 `DialogGraph`：
- 每个 node 负责：进入时输出文本、提供选项、（可选）每帧推进逻辑。
- 选项点击后返回 `DialogAction`：`Goto(nodeId)` / `Close()` / `Run{...}`。

2) 用 `GraphDialogPlugin(graph)` 弹出对话：
- 你需要一个交互目标 `SectorEntityToken`。

示例（见 `DemoDialog.open(target)`）：
- `Global.getSector().campaignUI.showInteractionDialog(GraphDialogPlugin(graph), target)`

## 延迟逐条输出（TimedTextQueue）

需求：打开对话后，文案不会一次性弹出，而是按延迟逐条自动输出。

做法：在 node 的 `onEnter` 里把文本“排队”。
- `ctx.enqueue("第一句", delay = 0f)`
- `ctx.enqueue("第二句", delay = 0.6f)`
- `ctx.enqueueI18n(I18n.Categories.MOD, "some.key", delay = 0.8f, ...)`

### 淡入淡出（可选）

如果你希望对话文本面板里的每条输出带淡入/淡出动画：

- `ctx.enqueueFading(text, delay, fadeIn, hold, fadeOut, maxOpacity, baseColor)`
- `ctx.enqueueI18nFading(category, key, delay, fadeIn, hold, fadeOut, maxOpacity, baseColor, ...)`

说明：
- fade 使用 `LabelAPI.setOpacity()` 实现（`TextPanelAPI.addPara()` 返回的 label）。
- `fadeOut > 0` 会让该段文本在一段时间后变为透明（仍占据布局高度）。
- 适合做“提示/旁白/播报”，不建议对大量永久历史文本使用淡出。

注意：`delay` 是“相对上一条输出”的延迟（不是绝对时间轴）。

### 默认交互策略（模板内置）

- 当 node 处于“锁定”状态（`lockOptionsWhileTextQueueActive = true`）且队列未输出完：
  - 选项面板会被锁定
  - 默认提供一个“跳过”按钮（会 `flush()` 输出剩余全部文本）
- 文本输出完毕后：
  - 自动恢复 node 自己提供的选项

如果你不希望锁定选项：用 `DialogDsl.node(...)`（默认不锁定），或在自定义 node 里覆盖 `lockOptionsWhileTextQueueActive()` 返回 `false`。

## 状态与数据联动

- **对话内临时状态**：`ctx.sessionState`（一个 `MutableMap<String, Any?>`），生命周期仅限本次对话实例。
- **跨对话持久状态**：建议写入 `MemoryAPI`（例如 `Global.getSector().memoryWithoutUpdate` 或实体/市场 memory）。

模板提供了 `ctx.memoryMap`（尽力填充常用 key，如 `global/player/entity/market/...`），便于未来与 rules/token replacement 体系对接。

## I18n 与高亮

- `DialogContext.sayI18n(...)` / `enqueueI18n(...)` 直接走工程内 `I18n`。
- 支持 `<param:#RRGGBB:key>` 这种“可高亮变量标记”。
  - 示例：`dialog.demo.intro.0`。

## 设计注意事项（踩坑指南）

- **不要把可变状态塞进 node 的字段**（尤其是 `object` 单例 node）。
  - 状态请放 `ctx.sessionState` 或 `MemoryAPI`。
- 如果你要做“可自动生成”的对话（JSON/ss-csv）：
  - 建议生成 `DialogGraph` + 固定 actionId 的 `Run` 映射表
  - 避免试图序列化 lambda

## 右侧 HUD 消息栏（舰队消息）

如果你想把信息投递到战役右侧消息栏（原版自带淡入淡出）：

- `ctx.hudMessage("...", color = ...)`
- 或直接使用 `HudMessages.campaign(...)`（见 `src/main/kotlin/.../campaign/ui/HudMessages.kt`）

注：原版不暴露自定义消息栏 fade 时长的 API；一般不建议用“反复 remove + add 变 alpha”的方式模拟。
