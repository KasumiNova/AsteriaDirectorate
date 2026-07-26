# 射弹 VFX 渲染架构重写：RenderEntity + DSL

> 状态：设计定稿待评审 · 日期：2026-07-22
> 取代：[2026-05-17-runtime-renderer-decision.md](./2026-05-17-runtime-renderer-decision.md) 所确立的
> `ASTDProjectileVfxRenderLayer` / preset-catalog 运行时架构（该架构的**渲染语义与后端桥接保留**，
> 被取代的是它的**组织方式与作者面**）。

## 1. 背景：为什么要重写

当前生效的射弹拖尾管线（`.proj onFire` → `ProjectileSpecOnFireDispatcher` →
`ProjectileVfxRegistry` → `ASTDProjectileVfxPresetCatalog` →
`ASTDProjectileVfxRuntimeManager/Runtime` → `runtime/*RenderLayer`）在观感上是达标的，
但**几乎不可扩展**，具体表现为五点，全部有代码佐证：

1. **参数是硬编码的 Kotlin 对象图，配置与路由分离。**
   `astd_projectile_vfx.json` 只是 `projectileSpecId → presetId` 的路由表，真正决定画面的参数
   全在 `ASTDProjectileVfxPresetCatalog` 里写死。改一个观感 = 改 Kotlin + 重编译 + 进游戏看。
2. **加一种组件要改 5 处。** 新增一类效果需要同时动：`ASTDProjectileVfxComponentSpec`（sealed）、
   `ASTDProjectileVfxComponentContext`、`ASTDProjectileVfxComponentRegistry` 的映射、一个新的
   `*RenderLayer`、以及 preset 里手工接线。链条过长，改一点牵一串。
3. **作者面暴露渲染内务。** 单个 `ASTDTrailLayerSpec` 约 30 个字段
   （`flickerSyncCode` / `stripLineMode` / `uvOffset` / `fillStartFactor` …），是 BoxUtil
   `TrailEntity` 旋钮的 1:1 镜像。作者要在一堆管线内务里找那几个有意义的旋钮。
4. **两套作者风格并存且不一致。** `aod7Shot()` 全手写（~160 行、每字段显式），其余 23 个走
   `preset(id, color, width, length, glowScale, ribbon, head)` 工厂。同一个仓库两种心智模型。
5. **组合与叠加顺序是隐式的。** 层序藏在 `ASTDProjectileVfxComponentRegistry.layersFor` 的
   列表顺序里，不显式、不可声明。

结论：观感对，组织错。要保留**渲染语义**（轨迹采样、朝向、淡出、BoxUtil/自定义 GL 桥接），
把**作者面与组合方式**换成一套可声明、可组合、可扩展的模型 —— 即 `api/render` 里已起草的
`RenderEntity` + DSL。

## 2. 目标与非目标

### 目标

- **一处声明一个特效。** 每个 `projectileSpecId` 对应一个 DSL 构建函数，树即全部结构与参数。
- **组合式可扩展。** 新增一类组件 = 加一个 `RenderEntity` 子类 + 一个 DSL 构建器函数，**不**牵动
  注册表 / sealed 类 / 五处接线。
- **作者面只暴露要紧旋钮。** 30 字段收敛成命名 setter（`segments(4)` / `color(RED)` / `width(12f)`），
  其余走默认。低层管线内务从作者面消失。
- **稳定唯一的弹体数据源。** 顶层每帧提供一份稳定、唯一的取数入口（弹体已存在时间、坐标、生命周期状态），
  全体节点从此读取，不各自向 `projectile` 取数或重算（见 D2）。
- **调试环境小参数免重启迭代。**（见 §7）
- **后端相关代码集中在节点内部。** 树本身与 `render()` 调度是后端无关的；BoxUtil / 自定义 GL 只出现在
  节点的生命周期实现里。这为将来"同一棵树、第二个渲染器（LWJGL 预览）"留门，但预览本身不在本次范围。
- **宿主中立、兼容光束。** 核心模型不钉死弹体，弹体与光束共用同一套树 / DSL / 生命周期（见 D6）；
  本次先迁弹体，光束走后续阶段（P7）迁到同一轨道。

### 非目标（本次明确不做）

- **外部数据（JSON/CSV）驱动 + 文件热重载。** 已决定用 DSL（编译期），不做数据前端。
  `ASTDProjectileVfxHotReloadManager/Source` 这套空接缝随旧管线一并删除。
- **WebUI 预览一致性。** 选 DSL 即意味着源真相在 JVM 侧；若日后要预览，走 JVM/LWJGL 程序调同一 DSL，
  另立文档。
- **弃用 BoxUtil。** BoxUtil 是游戏运行时的必然绑定，继续作为主要后端。
- **大特效结构热重载。** 加删组件、改类结构一律接受重启；仅小参数走调试热交换。真出现大功能重载需求再议。

## 3. 目标架构总览

```
.proj onFire (generic 垫片, 保留)
  → ProjectileSpecOnFireDispatcher (保留：去重 + 导弹 AI 注入)
  → RenderEntityRegistry.builderFor(projectileSpecId)   ← 新：specId → DSL 构建函数
  → ProjectileVfxDriver.track(engine, projectile, builder())  ← 保留驱动，喂 RenderEntity 树
       每帧：计算帧状态(FrameState) → tree.advance/render(ctx) → 命中/过期触发 fade → 结束 detach
```

分层：

- **`api/render`（接口层，对外开放）**
  - `RenderEntity`：一个可渲染节点，持子节点、自带 `layer`、有完整生命周期。
  - `RenderLayer`：绘制层枚举（对齐 Starsector `CombatEngineLayers` + 模组内部叠加序）。**待填**。
  - `RenderContext`：逐帧上下文（engine、projectile、父节点栈、**帧状态**）。
- **`impl/render`（实现层）**
  - `RenderEntityImpl`：composite 基类（子节点遍历 + 生命周期骨架）。
  - 组件节点：`HeadComponent` / `TrailComponent` / `GlowComponent` / `MistComponent` /
    `SideWispComponent` / `RibbonComponent` / `BodyComponent`，各为 `RenderEntityImpl` 子类，
    自带 `layer` 与后端桥接。
  - DSL 构建器：`renderEntity {}` / `head {}` / `trail {}` / `glow {}` / … + 各命名 setter。
  - `builders/`：每个 `projectileSpecId` 一个构建函数（`aod7Shot()` 等）。
- **保留复用（不动或小改）**
  - 派发去重与 AI 注入：`ProjectileSpecOnFireDispatcher` 及其复用件
    （`ProjectileVfxKeys` / `ProjectileVfxDispatchState` / `ProjectileMissileAiInjector`）。
  - 轨迹与几何：`ASTDProjectileHistory` / `ASTDProjectileVfxLayout` / `...Math` /
    `...Centerline` / `...SoftMesh`（这是弹体轨迹语义，不是后端特有）。
  - 自定义 GL 网格插件：`ASTDProjectileVfxBodyRenderManager`（Body/Head 的填充多边形，
    BoxUtil 画不了，见旧决策文档）。
  - BoxUtil 桥接工具：`renderer/boxutil/BoxUtilCombatVfx`。

## 4. 核心设计决策

### D1 · 保留模式生命周期（关键，当前草稿缺失）

BoxUtil `TrailEntity`/`SpriteEntity` 与自定义 GL 网格插件**都是保留模式**：创建一次、逐帧更新、
结束删除。当前 `RenderEntityImpl.render()` 只有"遍历子节点 + renderInternal"，**没有 create/delete/fade**，
若照此每帧建实体会导致 BoxUtil 句柄泄漏（`addEntity` 不配对 `delete`）。

因此 `RenderEntity` 必须补齐生命周期，与现有 `ASTDProjectileVfxRenderLayer`（`create/advance/beginFadeOut/delete`）
**语义一一对应**：

```kotlin
typealias RenderLayer = CombatEngineLayers   // 直接复用原版层，见 D3

interface RenderEntity {
    val id: String
    val layer: RenderLayer
    val children: List<RenderEntity>         // 有序，按 layer 定序（见 D3）；运行时可增删（见 D4）

    /** 运行时挂子节点：立即 onAttach 并纳入 advance 遍历（root component 动态管理用，见 D4）。 */
    fun addChild(child: RenderEntity)
    /** 运行时摘子节点：立即 onDetach 并移除。 */
    fun removeChild(id: String)

    /** 首帧：创建后端句柄（BoxUtil 实体 / GL 网格 handle）。engine 为 null 时应返回 false，供无引擎测试。 */
    fun onAttach(ctx: RenderContext): Boolean
    /** 每帧：用 ctx.frame 更新后端句柄（节点/宽度/颜色/位置/淡出 alpha）；root component 可在此增删 children。 */
    fun advance(ctx: RenderContext, amount: Float)
    /** 触发淡出（命中/过期/移除/销毁），递归传播到 children。 */
    fun beginFadeOut(reason: FadeReason, seconds: Float)
    /** 释放后端句柄，递归 onDetach 全部 children。 */
    fun onDetach()
}
```

`RenderEntityImpl` 提供骨架：`onAttach`/`advance`/`beginFadeOut`/`onDetach` 默认递归子节点，
子类只覆写自己那段后端逻辑（对应现有各 `*RenderLayer` 的 body）。
`render(ctx)`（立即模式绘制）只对**自定义 GL 节点或将来的 LWJGL 预览**有意义；BoxUtil 节点靠 advance
更新句柄、由 BoxUtil 自行每帧绘制，`render()` 对它是空操作。

### D2 · RenderContext 作为稳定唯一的帧数据源

现有 `ASTDProjectileVfxRenderContext` 携带全体节点共享的逐帧派生量：
`location / renderFacing / velocityFacing / projectileFacing / elapsed / logicElapsed /
flightProgress / dissolve / visibleLength / beamAlpha / historyNodes / worldUnitsPerPixel`。
新 `RenderContext` 目前只有 `engine / projectile / stack`，缺这份。

决策：**由驱动每帧计算一次 `FrameState` 塞进 `RenderContext`，作为全体节点唯一、稳定的取数入口**——
节点一律从 `ctx.frame` 读弹体的时间/坐标/生命周期，**不得**各自去调 `projectile.getElapsed()` /
`getLocation()` 或自行重算。这样"弹体已存在时间、当前坐标、当前生命周期状态"只有一个真相源，
也是 §2 目标里"稳定唯一数据源"的落点。轨迹采样 / 朝向 / dissolve 这些逻辑保留在驱动侧
（复用现有 `ASTDProjectileHistory` 等）。

```kotlin
interface RenderContext {
    val engine: CombatEngineAPI
    val host: RenderHost             // 宿主中立：弹体或光束，见 D6
    val stack: Deque<RenderEntity>   // 父节点栈（嵌套变换/状态继承用）
    val frame: FrameState            // 本帧唯一数据源
}
```

`FrameState` 以现有 `ASTDProjectileVfxRenderContext` 为蓝本逐字段搬入（避免观感回归），
并显式暴露稳定数据供 root component 判断增删与淡出；字段做**宿主中立**（见 D6，弹体/光束共用）：

- **时间**：`elapsed`（宿主已存在秒数）、`logicElapsed`、`amountThisFrame`。
- **空间**：`origin`（弹体位置 / 光束炮口）、`facing`、`length`（弹体可见拖尾长 / 光束到命中点长）、
  `endpoint?`（光束命中端）、`worldUnitsPerPixel`。
- **几何**（探查修正，原草稿漏列）：`historyNodes`（宿主轨迹采样点快照）+ 由其派生的**中线**采样能力
  （head-first 归一化折线，按弧长/比例取点、切线/法线、直线判定 `isEffectivelyStraight`、body 半宽剖面）。
  这是拖尾类节点（trail/glow/sideWisp/mist/body/ribbon）唯一的动态几何输入。探查证实：旧实现里**每个 renderer
  各自重复调 `Centerline.build`**，无共享无缓存。新架构**由驱动每帧算一次中线、全体节点共享**（复用现有
  `ASTDProjectileVfxCenterline`/`Math`），正是 §2 目标"稳定唯一数据源"的落点。trail/head 是直线/本地几何，
  只用 `length`+`facing`，不碰中线。
- **连续信号**：`active`（弹体飞行中 / 光束 firing）、`intensity`（0..1；弹体 alpha / 光束 strength）——
  节点据此做连续淡入淡出，天然覆盖光束"停火淡出、复火拉回"。
- **终止**：`phase`（`Active` / `FadingOut` / `Removed`）、`flightProgress`、`dissolve`、`fadeReason?`——
  仅用于一次性终止（弹体命中/过期、光束所属武器/舰体消失）。淡出期间**冻结几何快照**（沿用现有 Runtime：
  死亡前最后一帧的 `historyNodes/origin/length` 定格，只推进 fade alpha、不再采样），节点 `beginFadeOut`
  后据此收束。这条行为必须保留，否则命中瞬间拖尾会抽搐。

### D3 · 绘制顺序按 layer，`RenderLayer` 直接复用 `CombatEngineLayers`

当前草稿 `components: SortedMap<String, RenderEntity>`（`TreeMap` 按 id 字典序）与节点自带的
`layer` **相互打架**：`HeadComponent.layer` 注释是"Top layer"，但 id `"head"` 字典序在
`"primary"/"secondary"` 之前、子节点又先画 → **head 会被画在最底，正好与意图相反**。

决策：
- **`RenderLayer` 不自定义枚举，直接复用原版 `CombatEngineLayers`**
  （`typealias RenderLayer = CombatEngineLayers`）。层的含义、取值、先后完全对齐游戏本体，
  免维护第二份映射，也天然对齐 BoxUtil 的 `setLayer` 与自定义 GL 插件的 active-layer 过滤。
- **层容器改为按 `layer`（即 `CombatEngineLayers` 顺序）排序**，同层按插入序保持 DSL 书写顺序。
  `SortedMap<String, _>` 弃用；id 仅作定位/去重键，不参与绘制序。
- **同层内的跨节点次序用 `renderOrder`**（探查修正）：探查证实，自定义 GL 那批（glow/body/sideWisp/head/ribbon）
  在**同一** `CombatEngineLayers`（现全在 `ABOVE_PARTICLES`）内的像素叠放，靠 `ASTDProjectileVfxBodyRenderManager`
  按固定 `renderOrder` 常量排序：`GLOW 100 < BODY_SHADOW 180 < BODY 200 < SIDE_WISP 240 < HEAD_SHADOW 280 <
  HEAD 300 < RIBBON 360`。故仅靠 `CombatEngineLayers` 不足以复刻叠加序——节点除 `layer` 外须携带 `renderOrder`，
  `children` 排序细化为 `(layer.ordinal, renderOrder)`。BoxUtil 实体（trail/mist）走 BoxUtil 自己的绘制通道，
  不参与这套 renderOrder 排序（它们与自定义 GL 分属两条渲染管线，这是既有事实，保留）。

### D4 · child 作为动态管理容器（root component）

`child {}` 不是投机深度，而是**特效分组与动态生命周期管理**的一等机制。真实用例：

- **AOD7 动态马赫环**：随弹体飞行距离**动态产生/移除**新环组件。用一个 root component
  （如 `MachRingRoot`）在自己的 `advance` 里按距离 `addChild` 新环、按寿命 `removeChild` 旧环。
  好处是这堆动态子组件由该 root **独立管理**，不必全塞进单个组件、也不污染其它组件。
- **GCP 系列**：多处光圈 + 多处组合 + 大量粒子等需持续维护的实例。用**多个 root component**
  分别控制不同系列（一个 root 管光圈、一个管粒子、一个管组合体），各自维护各自的子集。

因此设计必须支持：
- **运行时增删子节点**：`addChild` 立即 `onAttach` 并纳入遍历，`removeChild` 立即 `onDetach`；
  root component 在 `advance` 内按弹体状态（读 `ctx.frame`）自行增删。
- **任意深嵌套**：root 之下可再挂 root，深度由业务决定，不设人为上限。
- **生命周期归属清晰**：父 `onDetach` 递归 `onDetach` 全部 children；父 `beginFadeOut` 递归传播。

`RenderContext.stack` 承载父节点链，供子节点读取父的变换/状态。

DSL 侧，root component 由业务子类 + 一个构建器暴露；动态增删发生在其 `advance` 内，作者无需手写循环：

```kotlin
internal fun aod7Shot(): RenderEntity = renderEntity("aod7_shot") {
    trail("primary") { color(0x478FEB); width(40f); length(420f) }
    glow("glow") { scale(5.4f); alpha(0.18f) }
    // root component：马赫环由它按距离动态产生/回收，其余组件不受影响
    machRingRoot("mach") { spacing(60f); ringLife(0.4f); maxRings(12) }
}

// MachRingRoot.advance 内（伪码）：读 ctx.frame.location 距上一环 ≥ spacing 则 addChild(ring)；
// 环 elapsed ≥ ringLife 则 removeChild(id)。增删各自触发 onAttach/onDetach。
```

### D5 · 命名遵循仓库规范

新增类型避免 `XxxManager/Service/Runtime`（仓库禁用词）。驱动命名用 `ProjectileVfxDriver`
（逻辑层，接口 + Impl），注册表用 `RenderEntityRegistry`。既有的
`ASTDProjectileVfxRuntimeManager` / `ASTDProjectileVfxBodyRenderManager` 属保留件，
本次不做无谓改名；新写代码不得再引入禁用词。接口需写清简介 + 每个成员的动机与作用。

### D6 · 宿主中立：兼容光束（beam）

问题：`RenderContext` 一旦把宿主钉成 `projectile: DamagingProjectileAPI`，光束就无法接入——光束没有
projectile，它是 `BeamAPI`（from/to/weapon/brightness），或如 Stellar Jet 由 ship+weapon 算出
start/facing/length/contact。而本模组现有大量光束特效（`StellarJetBeamVfx` / `GravityCollapseBeamVfx` /
`PsiOmegaBeamVfx` 等）**就是手搓的同款模式**：常驻 `TrailEntity`（core/glow/wisp）创建一次、逐帧更新、
firing 时喷 muzzle/impact/ambient 粒子、停火淡出、复火拉回。所以光束天然适配 RenderEntity 模型——
只要不把宿主钉死。

决策：

- **`RenderContext` 宿主中立**：以 `host: RenderHost`（接口）取代具体 `projectile`。当前两种实现：
  `ProjectileHost`（移动点 + 朝向 + 飞行生命周期 + 历史）、`BeamHost`（from/to 或 start+facing+length +
  firing + strength + contact/hit）。节点 95% 只读 `ctx.frame`，仅少数 root 需 host 特有查询
  （光束命中目标 / 弹体 `cutTrails`）。
- **`FrameState` 宿主中立**（见 D2）：几何用 `origin/facing/length/endpoint?`，连续淡入淡出用
  `active/intensity`，一次性终止用 `phase/fadeReason`。同一个 `trail` 节点不关心端点来自弹体历史还是光束
  from/to。
- **非渲染逻辑不进树**：光束特效里的伤害结算（EMP 电弧 `applyDamage`）、命中判定、firing 门控留在
  驱动 / `BeamEffectPlugin`，RenderEntity 树只拥有视觉节点。

结论：**核心 RenderEntity / DSL / 生命周期模型兼容光束**，要改的只是宿主与帧状态做成中立。
本次重写**先迁弹体**，光束作为后续阶段迁到同一套轨道（见 §9 P7）。

## 5. DSL 设计

DSL 是**唯一构建入口**，也是作者面。它把 30 字段折叠为"命名 setter + 默认值"——这正是
`preset(color, width, length, glowScale, ribbon, head)` 工厂早已证明的：24 个里 23 个只需约 6 个高层旋钮。

```kotlin
internal fun aod7Shot(): RenderEntity = renderEntity("aod7_shot") {
    trail("primary") {
        color(0x478FEB)          // 起止色/emissive 由单色派生，默认加色混合
        width(40f); length(420f)
        // 需要时才下探低层旋钮：texture(pixels = 96f, speed = 0.9f), fill(...), strip(true)
    }
    glow("glow") { scale(5.4f); alpha(0.18f); blur(34f) }   // 多子层辉光由 scale 展开
    body("body") {}                                          // 复用 GL 网格插件
    head("head") { color(WHITE); length(138f); width(24f) }
    mist("mist") { blobs(52); alpha(0.016f, 0.075f) }
    sideWisp("wisp") { offsets(-2.1f, -1.36f, 1.28f, 2f); alpha(0.24f) }
    ribbon("ribbon") { frequency(1.1f); amplitude(1.35f); wave("noise") }
}
```

要点：

- 每个组件构建器（`trail`/`glow`/…）**必须把节点挂到父**（当前草稿 `trail()` 漏了 `add()`，
  只有 `head()` 有 —— 见 §8 待修）。
- 每个 setter 有默认，省略即默认。低层旋钮（`texture`/`fill`/`strip`/`flick`）作为二级 setter，
  常规特效不碰。
- `采样/淡出/生命周期`（`samplingPolicy` / `fadePolicy` / `lifecycle`）作为 `renderEntity {}` 顶层的
  可选块，默认值覆盖 23/24 情形，仅 hero 特效下探。

## 6. 与后端的桥接（各节点实现）

| 组件节点 | 后端 | 现有可复用实现 |
|---|---|---|
| TrailComponent | BoxUtil `TrailEntity` | `ASTDProjectileVfxTrailRenderer` 逻辑 |
| GlowComponent | BoxUtil `TrailEntity`（多子层） | `ASTDProjectileVfxGlowRenderer` |
| SideWispComponent | BoxUtil `TrailEntity`（多偏移） | `ASTDProjectileVfxSideWispRenderer` |
| RibbonComponent | BoxUtil `TrailEntity`（正弦扰动节点） | `ASTDProjectileVfxRibbonRenderer` |
| MistComponent | BoxUtil `SpriteEntity`（blob） | `ASTDProjectileVfxMistRenderer` |
| BodyComponent | 自定义 GL 三角网格 | `ASTDProjectileVfxBodyRenderManager` + `Layout.bodyPolygon` |
| HeadComponent | 自定义 GL 三角网格 | 同上 + `Layout.headFillLayout` |

**这些是把现有 `*RenderLayer` 的 body 平移进新节点，不是重写渲染逻辑。** 具体地：调好观感的网格数学
（`ASTDProjectileVfxCenterline` / `...SoftMesh` / `...Layout` / Trail 的 `applyLayer` BoxUtil 属性写入 /
各 renderer 的 `meshesForTests`/`samplesForTests`/`applySamples`）**原样复用**，节点只接管生命周期与 FrameState 取数；
自定义 GL 那批继续经 `ASTDProjectileVfxBodyRenderManager` 提交（携各自 `renderOrder`）。手抄重推这些数学
= 观感回归风险，禁止（这是复用既有正确实现，不是薄适配层）。迁移主要是"换外壳、接生命周期、接 FrameState"，
观感风险集中在 §10 所列的 aod7 hero。

## 7. 调试热交换（免重启的边界）

机制：**每次生成弹体都重新调用 DSL 构建函数**生成新树（而非缓存单例树）。因此：

- **小参数改动免重启**：在 SSOptimizer 调试环境改 DSL 里的字面量（`segments(4)`→`6`、`color(RED)`、
  `width(40f)`→`44f`），靠 JVM 方法体热交换（HotSwap / DCEVM / 调试器 redefine）替换构建函数字节码，
  **下一发弹**就用新树。无需重启。
- **结构改动仍重启**：加/删组件、改类层次、改方法签名，超出 HotSwap 能力，接受重启。
- 不引入任何文件监听/数据解析/脚本宿主 —— 与"非目标"一致。

前提：构建函数必须**无状态、每次调用产出全新树**，不得缓存。驱动侧按 projectile 持有各自的树实例。

## 8. 当前草稿待修（`impl/render/RenderEntityImpl.kt`）

1. **`trail()` 未挂父节点**：缺 `this@trail.add(this)`（`head()` 有），照现状 `primary`/`secondary`
   构建后即丢弃。
2. **层容器按 id 排序**：`SortedMap<String, RenderEntity>` 应改为按 `layer` 定序（见 D3）。
3. **缺生命周期**：`RenderEntity` 需补 `onAttach/advance/beginFadeOut/onDetach`（见 D1），
   否则 BoxUtil 句柄泄漏。
4. **`RenderLayer` 空枚举**：删除，改为 `typealias RenderLayer = CombatEngineLayers`（见 D3）。
5. **`RenderContext` 缺 `frame`、且 `projectile` 钉死宿主**：需补 `FrameState`（D2），并把
   `projectile: DamagingProjectileAPI` 改为 `host: RenderHost`（D6，兼容光束）。
6. **`color()` / `segments()` 为 `TODO()`**：待实现为真正的参数写入。
7. `BusinessRenderEntity.kt` 是空 stub，`businessRenderEntity()` 仅作 DSL 示例，落地后移除或改为测试样例。

## 9. 重写计划（每阶段完成即完成，不留半成品）

- **P0 · 定稿 API。** 按 D1–D5 补全 `RenderEntity`/`RenderLayer`/`RenderContext`/`RenderEntityImpl`
  骨架 + DSL 构建器 + setter 默认。自检：构建 `businessRenderEntity` 树，断言组件数、layer 定序、
  每个 setter 生效（一个 `test_*.kt`，直接调 DSL 验证，不做源码 contain 测试）。
- **P1 · 后端桥接节点。** 逐个组件节点实现 `onAttach/advance/beginFadeOut/onDetach`，平移现有
  `*RenderLayer` 逻辑；接 `FrameState`。自检：无引擎时 `onAttach` 返回 false（与现有 layer 测试对齐）。
- **P2 · 驱动接线。** 用 `RenderEntity` 树替换 `ASTDProjectileVfxRenderGraph`；驱动每帧算 `FrameState`
  → 遍历树 `advance`；命中/过期 → `beginFadeOut`；结束 → `onDetach`。派发/去重/AI 注入复用。
- **P3 · 迁移 hero（aod7_shot）。** 写成 DSL 构建函数，逐字段对齐旧观感。parity：golden 截图对比
  （复用试验场 + 现有 parity 流程）。
- **P4 · 迁移其余 23 个。** 逐个 specId 写 DSL 构建函数（高层旋钮为主）。
- **P5 · 删除旧管线。** 移除 `ASTDProjectileVfxPresetCatalog`、`ASTDProjectileVfxComponentSpec/Context/Registry`、
  各 `*RenderLayer`、`preset()` 工厂、`astd_projectile_vfx.json` 路由（改由 `RenderEntityRegistry`）、
  `HotReloadManager/Source` 空接缝，以及被测试隔离的 legacy 管线（`ProjectileVfxPresets` /
  `ProjectileTracerManager` / `BoxUtilProjectileTrails` / `CompositeProjectileVisual`）。
  `ProjectileVfxOldPathRemovalTest` 改写为守护"新架构不回退旧路径"。
- **P6 · verify。** 进游戏跑试验场，逐 spec 目检 + parity 回归 + 调试热交换验证（改一个字面量、重刷、
  确认下一发生效、无需重启）。
- **P7 · 光束迁移（后续阶段，非本次范围）。** 增加 `BeamHost`，把 `StellarJetBeamVfx` /
  `GravityCollapseBeamVfx` / `PsiOmegaBeamVfx` 等手搓光束特效迁到同一套 RenderEntity 轨道
  （core/glow/wisp 为 `trail`/`glow` 节点，muzzle/impact 为 root component 的动态 child，
  伤害/命中留在 `BeamEffectPlugin`）。P0–P2 的宿主中立保证这一步无需再改核心。

## 10. 风险与决策点

- **观感 parity（主要风险，集中在 aod7 hero）。** 用 golden 截图对比兜住。
- **layer 定序改变叠加顺序。** 现按 `CombatEngineLayers` + 同层插入序；旧的既定层内顺序
  （trail→glow→body→sideWisp→head→mist→ribbon）靠 DSL 书写顺序保持。P3/P6 目检。
- **动态 child 的句柄泄漏 / 帧内抖动。** root component 的 `removeChild` 必须配对 `onDetach`；
  大量马赫环/粒子要控增删节流，避免同帧增删风暴。P1 定增删时序（见 §11）。
- **保留模式钩子若漏接 → BoxUtil 句柄泄漏。** P1 每个节点必须 `onDetach` 配对 `onAttach`，
  addEntity 失败即 delete（沿用 `BoxUtilCombatVfx` 既有约定）。
- **热交换边界预期管理。** 文档化"仅小参数免重启"，避免误期望结构热重载。
- **`FrameState` 归属。** 已定：放 `RenderContext.frame`，驱动每帧算。

## 11. 待定 TODO

- **（已定）** `RenderLayer` = `CombatEngineLayers`（D3）；`child` 为动态管理容器、支持运行时增删与
  任意深嵌套（D4）。
- root component 增删子节点的**帧内时序**：`addChild` 当帧是否立即 `advance`。建议
  当帧 `onAttach` + 当帧 `advance`（避免新环首帧不可见），P1 定稿。
- DSL 低层 setter 的完整清单（覆盖 `ASTDTrailLayerSpec` 全部 30 字段中"确需下探"的那批）。
- 动态子节点的增删节流策略（马赫环/粒子的最小间隔、上限），P1 定。

## 12. 迁移进展（垂直切片）

- **spc3（`astd_spc3_shot`）** 已迁：Glow + Body（有 Body → BoxUtil 直线拖尾丢弃，对齐 hasBodyForTrail）。
  原版弹丸 core/fringe 置透明屏蔽亮头；facing 归一化到 [0,360) 修复右下开火拖尾歪斜。
- **aod7（`astd_aod7_shot`）** 已迁：七层齐活——Mist(50) / Glow(100) / Body(200) / SideWisp(240) /
  Head(300) / Ribbon(360)（括号为节点 renderOrder；Body 存在 → 无 BoxUtil 直线拖尾）。
- **节点实现**：Glow/Body/SideWisp/Head/Ribbon 塌缩为单个 `MeshComponent(produce)` + 5 个工厂函数
  （`impl/render/MeshRenderComponents.kt`），`produce` 逐帧调旧渲染器 `*ForTests` 纯数学产网格，
  每片网格一个 `BodyRenderManager.Handle`，绘制序由 mesh.renderOrder 全局排序决定。Mist 单列
  `MistComponent`（BoxUtil `SpriteEntity` 实例化粒子，复用 `samplesForTests`）。
- **装配（P3，已落地作者面 DSL）**：不再从旧 Preset 反解。`projectileVfx(id){ trail{}; glow{}; body(); … }`
  （`driver/ProjectileVfxDsl.kt`）为唯一作者面：`trail{}` 定主拖尾风格，组件块声明层参数并挂节点，
  `lifecycle/sampling/fade` 声明驱动策略，`build()` 一次产出 `ProjectileVfx(tree, policy)`。
  `aod7Shot()`/`spc3Shot()` 手写于 `driver/ProjectileVfxSpecs.kt`，逐字段对齐旧 preset（颜色用 `rgba(0xRRGGBBAA)`）。
  旧的 `sliceTree(preset)`/`policyFrom(preset)` 已删除，新管线**不再触碰 Preset/ComponentRegistry**。
- **路由**：`ProjectileVfxDriverPlugin.isMigrated(projId) = ProjectileVfxSpecs.has(projId)`，命中即
  `track(engine, projectile, projId)` 现构建新树；否则回落旧 Runtime。路由键是 **projectileSpecId**
  （`astd_<name>_shot`）而非 preset id（`<name>_shot`）。加一个 spec 只需在 `ProjectileVfxSpecs.builders` 加一行。
- **尺寸调整**：目检反馈弹体相对舰体偏小。head 宽 24→34、headScale 1.5→1.9；body/glow/mist/sideWisp 横截宽都吃
  `widthBase = max(startWidth*0.075, 3.5)`，故 startWidth 40→96 使 widthBase 3.5→7.2（约 2×），令核心/辉光随胖弹头等比放大
  （首次只调到 56 → widthBase 4.2，几乎无变化，因 0.075 系数不敏感 + 40 时被 3.5 地板夹住）。
  startWidth≤176 时 `viewportTailCap` 仍由 layoutRef 主导（=849），故 FrameState.length 不受尺寸调整影响、golden 恒绿。
- **测试**：`Aod7GoldenParityTest`（DSL 策略 ≡ 旧 preset 飞行几何，逐帧逐字段）+
  `ProjectileVfxSpecsTest`（DSL→树拓扑 + 策略字段）+ `GenericSpecParityTest`（通用构建器 plain/ribbon/head 三代表逐帧对齐旧 preset）。
- **P4 状态（23/24 已迁）**：通用 `ProjectileVfxSpecs.simpleProjectileVfx(color,width,length,glowScale,ribbon,head)`
  逐字段复现旧 `preset()` 工厂公式（起止色/emissive 由主色按固定 alpha 系数派生、glow 单层、body 承接拖尾、
  sampling/fade/lifecycle 同旧默认），22 个简单 spec 各一行登记；aod7 仍为逐层显式 hero。
  **唯 `astd_stellar_jet_bolt` 有意留旧管线**：其由 `StellarJetEmitter` 直连旧 `RuntimeManager.track` 旁路发射
  （绕过 onFire/spawn 派发），迁移需连同发射器改造，另行处理。
- **未做**：新管线未接 `ASTDProjectileVfxDebug` 逐层可见性开关（dev-only，按需再补）。旧
  `Preset/ComponentRegistry/RenderLayer/Runtime/RenderGraph` 仍服务 stellar_jet 及热重载路径，
  stellar_jet 迁完 + 热重载改造后（P5）再删。
