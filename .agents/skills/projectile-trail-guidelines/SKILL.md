---
name: "projectile-trail-guidelines"
description: "弹体拖尾规范：统一走 texTrail DSL（RenderEntity 树），复刻 MagicTrail 观感语义；含素材清单、调参指南与验证流程。"
---

# Skill：弹体拖尾规范（texTrail DSL）

## 目标

- 弹体拖尾**统一走 `texTrail{}` DSL**（RenderEntity 渲染树 + TexTrailRenderer 批渲染），复刻 MagicTrail 的观感语义。
- 新弹体配拖尾、旧拖尾调观感，都按本规范的参数面与调参指南执行，不另起渲染路径。
- 贴图素材统一放 `contents/graphics/fx/`，自制素材 `astd_trails_` 前缀。

## 渲染模型（先理解再调参）

- **几何**：CPU 折线带体。弹体历史中线（时间升序）折进弹头局部系，按带长均匀重采样 `nodes` 个点，逐节点给宽度和颜色；弯道平滑度由历史路径 + 节点数保证。
- **图案**：平铺滚动贴图。u = 累计弧长 / `tileLength` − 滚动相位，v ∈ [-1,1] 横向；片元着色器只做「采样 × 顶点色」，additive 混合。
- **贴图规范**：64×N PNG，**X=横向、Y=带长向**（Y 向 REPEAT 平铺，必须可无缝循环）；**形在 alpha 通道**，RGB 近白（染色来自节点色）。
- 与 MagicTrail 的模型差异：MagicTrail 是「独立段落链」（每段独立寿命、fire-and-forget），我们是「节点链整体推进」（整条带随弹体生命周期，淡出由 fade 层统一控制）。弹体死亡时整条带按 fade 配置溶解，不存在段落残留。

## DSL 参数面

声明位置：`ProjectileVfxSpecs.kt`（driver 包），经 `projectileVfx(id) { ... }` 构建；`.proj` 的 `onFireEffect` 指向 `ProjectileSpecOnFireDispatcher` 按 projectileSpecId 分发（见 rendering-vfx-guidelines）。

```kotlin
texTrail("层名", "graphics/fx/astd_trails_zappy.png") {
    layer(1)                    // 叠层序号：1 垫底、2 其上，绘制序 = 基线 + layer
    width(30f)                  // 拖尾全宽（世界单位）
    colors(头, 尾)              // 两色渐变（0xRRGGBBAA）
    colors(头, 中段, 尾, midAt = 0.25f)  // 三段渐变（惯例：白热头 → 签名色中段 → 暗尾）
    nodes(24)                   // 节点数（弯道平滑度，24 为惯例）
    tile(140f, 50f)             // 平铺周期 su / 滚动速度 su/s（scroll/length ≈ 每秒整图滚动次数）
    recede(40f)                 // 带体整体后退，让弹头尖在带体前露出
}
```

- 可声明多条 `texTrail` 叠层（对标 MagicTrail 的多层叠加）。
- 无 `trail{}` 时锚定宽度 = 最宽一条 texTrail；拖尾采样窗口取 `sampling{ window }`。
- `head{}` 弹头**恒并入 bloom 管线**（与拖尾能量同源、接缝光晕连续），`alpha(0.7f)` 压暗防溢出是惯例。
- `lifecycle{ duration / dissolveAt / headScale / layoutRef }` 控制整条带寿命与溶解起点。

## 素材清单

### 自制（首选，astd_ 前缀，观感从柔到烈）

| 贴图 | 观感 | 建议用途 |
| --- | --- | --- |
| `astd_trails_flow.png` (64×256) | 柔和波浪流 | 主带/能量弹主体 |
| `astd_trails_surge.png` (64×256) | 涌动波包，能量感强 | 重击/充能弹主带 |
| `astd_trails_twin.png` (64×128) | 两条干净平行线 | 垫底衬带/轨道感 |
| `astd_trails_zappy.png` (64×256) | 暴烈电弧折线 | 电弧副带/芯带 |
| `astd_trails_zappysmooth.png` (64×256) | 温和电弧折线 | 电弧主带（扭曲但不刺眼） |

### 同源素材（内容等同 GC 原图，2026-07-30 统一 astd_ 前缀后入库）

| 贴图 | 观感 | 备注 |
| --- | --- | --- |
| `astd_trails_smooth.png` (64×256) | 干净宽软带 | `TEX_SMOOTH` 常量，简单 spec 主带默认 |
| `astd_trails_contrail.png` (64×128) | 宽云状噪声带 | 锥面楔块底层选型（见重做计划） |

`TEX_SMOOTH`/`TEX_ZAPPY` 常量在 ProjectileVfxSpecs 顶部。gr_trails_ 前缀文件已全部清理（twin/zappy 内容与 astd_ 版逐字节相同直接换引用，smooth/contrail 改名，lightning/clean/circle 无引用删除）。

### 新素材规则

- 命名 `astd_trails_<形貌词>.png`，64×N（N 为 2 的幂），形在 alpha、RGB 近白、Y 向无缝平铺。
- 做完先在黑底上目检平铺接缝，再进游戏验证。

## 观感调参指南（MagicTrail 四件套对照）

MagicTrail 的"自然扭曲"由四个机制复合而成，对照我们的等价物：

| MagicTrail 机制 | texTrail DSL 等价物 |
| --- | --- |
| zigzag 贴图自带折线 | 直接选 zappy / zappysmooth 贴图 |
| `textScroll` 花纹爬行 | `tile(length, scroll)`，scroll/length ≈ 0.3~0.7/秒 读起来最活 |
| `dispersion`/`drift` 段落横向散开 | 历史路径自然弯曲 + 贴图扭曲承担；逐节点横向噪声**尚未实现**（见「优化方向」） |
| 多层拖带叠加错参 | 多条 texTrail：宽比 1.2~1.5×、scroll 比 1.5~2×、alpha 错开（芯亮边暗） |

惯例锚点（aod7 hero，实机目检通过）：主带 twin `width 30 / tile(140, 50)` 垫底，副带 zappy `width 24 / tile(200, 90)`，`recede(40f)`，三段色 `midAt=0.25`。

宽度锚点：主带 ≈ 弹体视觉宽 ×2~3；重击弹（贯星 36su）可再放大并配 `glowScale` 大圆弹头。

## 原版弹体渲染参考（基准形）

原版无 `bulletSprite` 的弹体（如脉冲激光）用内置渲染：`graphics/fx/projbody.png`（32×16，彗星形白图，头亮尾散，core/fringe 双色染色）+ `graphics/fx/projtrail.png`（64×16，波包带）。`.proj` 里 `coreColor/fringeColor/glowColor` 染色、`textureType` 选内置变体。我们 head{} 弹头的"彗星形、头亮尾散"基准观感对标 projbody。

## 验证

- 纯函数单测：`texTrailNodes` / `texTrailStrip` 直接调用做完整逻辑验证（禁源码 contain 测试）。
- 烟测：`ASTD_AUTOMATION_SCENARIO=<id> ./gradlew launchSmokeTestGame`（弹体类用 piercing_lance_basic / heavy_ion_pulse_basic），遥测键计数 + 目检；**到终态即退出，别干等超时**。
- 目检流程见 game-vfx-preview-guidelines。

## 禁做

- 不启用 `contents/data/trails/trail_data.csv`（MagicTrail 数据面，P2 已退役，仅表头遗留占位）。
- 不写「BoxUtil + 原版渲染」双实现降级分支（rendering-vfx-guidelines 总原则）。
- 贴图加载失败必须 WARN 并跳过该层（`TexTrailComponent.onAttachSelf` 先例），禁空 catch。
- 弹头与拖尾不拆到两个渲染管线（防接缝色差，head{} 恒入 bloom）。

## 优化方向（登记，待用户指示）

- MagicTrail `dispersion`/`drift` 等价物：逐节点横向噪声 + 弹体横向分速传递，需扩 `TexTrailSpec`（扰动幅度/频率/相移参数），实现时保持 `texTrailNodes` 纯函数可测。
- 锥状冲击重做的连续楔块层可复用本贴图族（见 `docs/design/weapons/impl/00-锥面冲击特效重做计划.md`）。
