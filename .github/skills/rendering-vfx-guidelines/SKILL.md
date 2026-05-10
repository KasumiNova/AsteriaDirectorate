---
name: "rendering-vfx-guidelines"
description: "渲染/特效规范：优先使用 BoxUtil 的高性能实现，统一接入本项目 VFX 管线，避免滥用原版渲染 API。"
---

# Skill：渲染 / 特效规范（优先 BoxUtil）

## 目标

- **优先使用 BoxUtil 的高性能实现**（实例化渲染 / TrailEntity / DistortionEntity 等）。
- **尽可能不使用原版的渲染 API**：不编写“BoxUtil + 原版渲染”的双实现降级分支，避免代码冗余与维护负担。
- 把 VFX 统一接入本项目的“弹体 VFX 管线”，减少重复触发与到处挂脚本。

## 适用范围

- 战斗内：弹体曳光、束体、命中/爆炸、扭曲、屏幕空间提示等
- 战役内：若涉及 BoxUtil 战役渲染能力，也遵循“优先 BoxUtil”原则

## 总原则（强制）

1) **能用 BoxUtil 就不用原版粒子/渲染 API**
- 例如：大量拖尾/粒子 → 用 `InstanceRenderAPI` / `SimpleParticleControlData`
- 束体/光刺/线段 → 用 `TrailEntity` / `SegmentEntity`
- 扭曲/透镜 → 用 `DistortionEntity`

2) **所有 BoxUtil 调用前先确保就绪**
- 本项目封装：`BoxUtilCombatVfx.ensureReady(engine)`
- 添加实体使用封装的 addEntity 并检查返回码，失败要 `delete()` 并尽快定位/报告问题

3) **统一接入弹体 VFX 管线**（按 projectileSpecId 分发）
- `.proj` 的 `onFireEffect` 推荐指向 `ProjectileSpecOnFireDispatcher`
- 由 Dispatcher 通过 `projectile.projectileSpecId` 查 `ProjectileVfxRegistry` 去重触发
- 映射配置（可选）：`contents/data/config/astd_projectile_vfx.json`

> 目的：避免 `.wpn` 与 `.proj` 双挂导致重复触发、以及扫描式插件造成噪音与性能浪费。

## “优先 BoxUtil”的落地建议

### 1) 大量粒子/曳光：实例化优先

- 如果同屏可能出现几十到几百个实例（曳光、碎片光点、薄雾粒子）：
  - 优先 `InstanceRenderAPI` 或 `SimpleParticleControlData`
  - 避免每次 `engine.addHitParticle/addNebulaParticle` 创建大量短寿命对象

### 2) 束体/拖尾：TrailEntity 优先

- 需要“带 taper 的束体/拖尾”时，优先复用本项目封装：
  - `weapons/common/boxutil/BoxUtilCombatVfx.kt`
  - `weapons/common/projectile/TaperedBeamTrailsVfx.kt`

### 3) 扭曲/透镜：DistortionEntity 优先

- 扭曲属于后处理实体：
  - 频率要控（节流/合并脉冲），避免蜂群刷屏
  - 注意 layer：**最高层不会应用后处理（Bloom/AA）**，不要无脑塞到最高层

### 4) 不做降级：BoxUtil 有问题就及时报告

本项目渲染/VFX 的默认立场是：**BoxUtil 优先且为唯一实现路径**。

- 不要为了“兼容性探测/偶发失败”额外编写原版渲染降级分支（会带来双倍代码与长期维护成本）。
- 当 BoxUtil 未初始化/不支持/添加实体失败时：
  - 立即在日志中报告关键信息（tag/weaponId/projectileSpecId/调用点）
  - 尽快复现并修复（或提示玩家启用 BoxUtil 所需能力/设置）

## 管线规范：弹体 VFX（projectileSpecId）

### 1) 配置优先于代码散落

- 优先把“哪个 projectileSpecId 用哪个 preset”写进：
  - `contents/data/config/astd_projectile_vfx.json`
- preset 实现在代码侧集中维护（`ProjectileVfxRegistry.kt` / `ProjectileVfxPresets`）

### 2) 不要给未配置弹体加默认 VFX

`ProjectileVfxRegistry` 的原则是：

- 未配置的弹体默认 **不附加任何 VFX**
- 配置缺失/损坏时才会回退到一小份内置 defaults（避免“完全不可见”）

目的：避免扫描式“给所有弹体加曳光”造成噪音与性能浪费。

## Debug / Profiling 建议

- **先确认触发源**：
  - 你挂的是 `.proj` 的 onFireEffect 还是 `.wpn` 的 onFireEffect？是否重复？
  - 优先统一到 `ProjectileSpecOnFireDispatcher`

- **确认 BoxUtil 就绪与 addEntity 结果**：
  - 调用 `BoxUtilCombatVfx.ensureReady(engine)`
  - `addEntity` 返回非 0 视为失败，必须 `delete()`，并记录日志以便及时定位问题

- **节流**：
  - 对“脉冲类效果”务必加最小间隔（例如 `minInterval`）
  - 参考：`SingularityRetargetPulseVisual` 对 retarget 脉冲的节流逻辑

## 常见坑

- 同时在 `.wpn` 与 `.proj` 挂脚本 → 重复触发、双倍粒子
- 对所有弹体默认加曳光 → 噪音 + 性能灾难
- Distortion 高频刷屏 → 画面“糊”且 GPU 压力大
- 未检查 addEntity 返回码 → 隐性泄漏/空对象残留

## 相关链接（仓库内）

- BoxUtil 指南：`.github/skills/boxutil-guidelines/SKILL.md`
- 源码阅读优先级：`.github/skills/docs-and-source-reading/SKILL.md`

## 参考文件（本项目实现）

- 弹体 VFX 分发：`src/main/kotlin/.../weapons/common/ProjectileSpecOnFireDispatcher.kt`
- 注册表：`src/main/kotlin/.../weapons/common/projectile/ProjectileVfxRegistry.kt`
- 配置：`contents/data/config/astd_projectile_vfx.json`
- BoxUtil 封装：`src/main/kotlin/.../weapons/common/boxutil/BoxUtilCombatVfx.kt`
- 示例（纯 BoxUtil 约束）：`src/main/kotlin/.../SingularityRetargetPulseVisual.kt`
