# Projectile VFX Runtime Redesign

日期：2026-05-10

## 目标

将前端射弹 VFX 预览工具中的渲染方案，转换为游戏内等效的 BoxUtil 射弹渲染体系，并一次性替代现有射弹渲染体系。

同时完成必要的包名与代码结构重组，为后续渲染、战斗特效、舰船系统、技能、Hullmod、Affix 等内容建立统一约定。

## 关键修正

- 游戏内不使用前端专用字段。
- 前端只导出特效预设，不导出会干预射弹生命周期的模拟参数。
- 射弹速度、射程、生命周期由 Starsector 游戏内机制决定。
- 拖尾长度、采样策略、淡出策略属于 VFX 参数，可以导出到游戏内。
- Sprite、Flare、实例粒子仅作为保留设计，不在首版功能实现中加入。
- 非直线路径射弹通过真实历史轨迹支持，包括导弹、追踪射弹、受力偏转弹道。

## 实施总顺序

1. 包名与结构重构
2. 前端导出边界修正
3. 新射弹 VFX 体系实现
4. 旧射弹 VFX 体系一次性替换
5. 游戏内验证

## 包名与代码结构

根包改为：

`cn.kasuminova.astd`

### 通用工具

`cn.kasuminova.astd.internal`

用于存放通用工具、基础设施、非业务特定代码。

### 渲染相关

`cn.kasuminova.astd.renderer`

展开后包含：

- `renderer.projectile`
- `renderer.system`
- `renderer.effect.projectile`
- `renderer.effect.skill`
- `renderer.effect.hullmods`
- `renderer.effect.affix`

### 战斗相关

`cn.kasuminova.astd.combat`

射弹/武器特效：

- `combat.effect.base`
- `combat.effect.generic`
- `combat.effect.arc`
- `combat.effect.lens`

舰船系统：

- `combat.shipsystems`
- `combat.shipsystems.base`

军官技能：

- `combat.skills`
- `combat.skills.base`

舰船插件：

- `combat.hullmods`
- `combat.hullmods.base`
- `combat.hullmods.affix`

Affix 体系：

- `combat.affix`
- `combat.affix.base`

### 文件组织约定

- 每个特效使用单一 Kt 文件，即便包含多个类。
- 每个系统、技能、Hullmod、Affix 使用单一 Kt 文件，即便包含多个类。
- 系统、技能、Hullmod、Affix 的具体实现文件中不放抽象类。
- 抽象类和通用接口放入各领域 `base` 包。
- 涉及渲染的额外实现可以放入 `astd.renderer.effect.xxx`。
- `ss-csv` 项目需要安排重构，本轮优先改包名。

### 需要新增 Skill

新增：

`.agents/skills/package-structure-guidelines/SKILL.md`

并更新：

`.agents/skills/00-skill-index/SKILL.md`

## 前端与游戏内模型边界

### 前端 Preview 模型

`BoxUtilPreviewPreset` 保留前端专用字段：

- `timeline`
- `simulation`
- `previewCamera`

这些字段只用于预览画布模拟，不进入游戏内 Kotlin 导出。

### 游戏内导出模型

新增游戏内导出模型：

`ASTDProjectileVfxPreset`

导出内容只包含 VFX 参数：

- VFX layer 列表
- 长度策略
- 采样策略
- 淡出策略
- 颜色、宽度、alpha、emissive
- blend、layer、material 参数
- ribbon wave、offset、thickness、gradient
- 可选 hook id

导出内容不包含：

- `TimelineConfig`
- `SimulationConfig`
- `PreviewCameraConfig`
- `projectileVelocity`
- `curveAmount`
- `curveFrequency`
- `loop`

## 游戏内 VFX 数据模型

核心包：

`cn.kasuminova.astd.renderer.projectile`

建议类型：

- `ASTDProjectileVfxPreset`
- `ASTDProjectileVfxLayer`
- `ASTDProjectileVfxLengthPolicy`
- `ASTDProjectileVfxFadePolicy`
- `ASTDProjectileVfxSamplingPolicy`

### Layer 类型

首版实现：

- `TrailLayer`
- `GlowLayer`
- `RibbonLayer`
- `HeadTrailLayer`

保留设计，首版不实现：

- `MistLayer`
- `SpriteEntity`
- `FlareEntity`
- 实例粒子
- 专用 projectile head sprite

### 长度策略

`ASTDProjectileVfxLengthPolicy`：

- `Fixed(worldUnits)`
- `VelocityScaled(seconds)`
- `ProjectileRangeRatio(ratio)`
- `LifetimeWindow(seconds)`

长度策略只影响 VFX 形状，不影响 projectile 生命周期。

### 采样策略

`ASTDProjectileVfxSamplingPolicy`：

- `historyFps`
- `maxHistoryNodes`
- `minDistancePerNode`
- `smoothingPasses`
- `distanceWindow`

### 淡出策略

`ASTDProjectileVfxFadePolicy`：

- `fadeInSeconds`
- `fadeOutSeconds`
- `hitFadeOutSeconds`
- `expireFadeOutSeconds`

淡出只控制 VFX 自身视觉，不控制射弹存在时间。

## 游戏内运行时

核心类型：

`ASTDProjectileVfxRuntime`

职责：

- 绑定 `DamagingProjectileAPI`
- 每帧采样真实 projectile 位置
- 维护真实历史轨迹
- 根据 preset 更新 BoxUtil 实体
- projectile 消失、命中或超射程后进入 VFX fade-out
- fade 完成后删除 BoxUtil 实体

### 真实轨迹原则

运行时只使用真实 projectile 状态：

- `projectile.location`
- `projectile.velocity`
- projectile facing 作为速度不可用时的回退
- CombatEngine 中的真实存在状态

Trail、Glow、Ribbon、HeadTrail 都从同一份历史轨迹派生。

### 非直线路径支持

通过真实历史轨迹支持：

- 导弹转向
- 追踪射弹
- 受力偏转
- 弹道弯曲
- 速度变化
- 停止/再加速

Ribbon 几何：

1. 从历史轨迹按距离采样。
2. 用相邻采样点计算局部切线。
3. 用局部法线计算波形偏移。
4. 生成连续节点链。
5. 以独立 TrailEntity 渲染，避免节点叠色。

## BoxUtil 映射

### TrailLayer

使用 `TrailEntity`。

- 节点来自历史轨迹窗口
- 设置 start/end color
- 设置 start/end emissive
- 设置 start/end width
- 设置 fill/jitter/flick/blend/layer/material

### GlowLayer

使用额外 `TrailEntity`。

- 更宽
- 更低 alpha
- 更高 emissive
- 多层叠加模拟前端 glow strokes

### RibbonLayer

使用独立 `TrailEntity`。

- 节点来自历史轨迹 + wave offset
- 一条 ribbon 对应一条连续节点链
- 首版使用 start/end 或有限拆段近似多 stop gradient

### HeadTrailLayer

使用短 `TrailEntity`。

- 跟随 projectile head
- 使用真实速度方向
- 用短锥形/短带状效果近似前端 projectile head

## Hook 机制

包位置：

`cn.kasuminova.astd.combat.effect.projectile`

通用基类位置：

`cn.kasuminova.astd.combat.effect.base`

接口：

- `onSpawn(context)`
- `onAdvance(context, amount)`
- `onHitOrExpire(context)`
- `onDelete(context)`

Hook 用于武器特例，例如 distortion、retarget pulse、特殊爆闪。

## 分发与注册

保留按 projectileSpecId 分发的概念，重做内部实现：

- `.proj onFireEffect` 统一指向新 dispatcher
- registry 负责 `projectileSpecId -> ASTDProjectileVfxPreset`
- registry 可附加 hook ids
- dispatcher 创建 runtime 并注册到 runtime manager

旧扫描式兜底入口清理。

## 旧体系替换

一次性迁移或删除旧职责：

- `ProjectileVfxRegistry`
- `ProjectileVfxPresets`
- `ProjectileTracerManager`
- `BoxUtilProjectileTrails`
- `TaperedBeamTrailsVfx`
- 扫描式 fallback dispatcher
- 旧 preset handler

必要算法可以迁入新包，例如历史轨迹平滑、TrailEntity builder、fade 管理。

## 前端修正

### UI 分组

新增或明确分组：

- Preview Only
  - timeline
  - previewCamera
  - simulation projectileVelocity
  - simulation loop
  - simulation curve

- Game Export
  - layer 类型
  - length policy
  - fade policy
  - sampling policy
  - BoxUtil layer/blend
  - ribbon/glow/head/trail 视觉参数

### Kotlin 导出

导出新版：

`internal object ASTDProjectileVfxPreset_<Name>`

返回：

`ASTDProjectileVfxPreset(...)`

导出测试应覆盖：

- 包含新版 preset 类型
- 包含 length/sampling/fade policy
- 不包含 `TimelineConfig`
- 不包含 `SimulationConfig`
- 不包含 `PreviewCameraConfig`
- 不包含前端 projectileVelocity/curve/loop

## 验证计划

### 包名重构验证

- 全文检索旧包名
- 检查 `contents/data/**` 脚本类名引用
- 运行 `./gradlew build`
- 运行 `./gradlew :ss-csv:generateSsCsv`

### 前端验证

- `npm run test:run`
- `npm run build`
- 浏览器预览检查直线和曲线路径
- Kotlin 导出检查游戏内字段边界

### 游戏内 VFX 验证

验证类型：

- 直线射弹
- 导弹/追踪射弹
- 高速短射程
- 慢速长拖尾
- 命中淡出
- 超射程淡出
- 暂停/慢速时间
- 多发同屏性能

重点检查：

- 真实射弹速度/射程由游戏控制
- VFX 只跟随真实 projectile 历史
- Ribbon 节点无叠色
- projectile 消失后 VFX 完整淡出
- BoxUtil 实体被正确释放

## Sprite/Flare/实例粒子保留方案

首版不实现。

只有在游戏内验证发现 TrailEntity 系无法表达预期视觉时，才考虑加入：

- `SpriteEntity`
- `FlareEntity`
- 实例粒子
- `MistLayer`

加入前需要提供：

- 预期效果
- 实际差异
- 截图或录屏对比
- 影响范围
- 性能评估

## 核心原则

游戏内预设表达视觉，射弹生命周期由引擎表达。
