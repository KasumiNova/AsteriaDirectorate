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

- **几何**：CPU 折线带体。弹体历史中线（时间升序）折进弹头局部系，按带长均匀重采样节点，逐节点给宽度和颜色；渲染节点数按可见带长**动态细分**（目标段长 22 su，下限取 DSL `nodes` 声明值，上限 72）——带长 ×4 后定值采样在导弹急转时折角明显，动态细分后弯道平滑。带长与历史路径同为**世界单位**（2026-08 修复像素/世界错配：带尾不再越出出生点）。
- **图案**：平铺滚动贴图。u = 累计弧长 / `tileLength` − 滚动相位，v ∈ [-1,1] 横向；片元着色器只做「采样 × 顶点色」，additive 混合。
- **贴图规范**：64×N PNG，**X=横向、Y=带长向**（Y 向 REPEAT 平铺，必须可无缝循环）；**形在 alpha 通道**，RGB 近白（染色来自节点色）。
- **逐节点寿命**（2026-08 起）：每个渲染节点携带出生时刻（历史点时间戳沿带长插值），年龄 = 当前时间 + 时间偏移 − 出生时刻；寿命 = 预期带长 / 实测速度（驱动自动估算，DSL `lifetime` 可覆写）。年龄进度驱动时变效果：`dissolveStart`（默认 0.6）前满亮满宽、之后 alpha 线性消散到 0 且**宽度同步收细**（`ageWidthEnvelope`，寿命末期收至 0.15×——带体呈逐渐收窄的消散观感，而非等宽带子淡出）；`twist` 扭转角随年龄累积。
- 与 MagicTrail 的模型差异：MagicTrail 是「独立段落链」（每段独立寿命、fire-and-forget），我们是「节点链整体推进 + 逐节点年龄」。**弹体消亡/命中不再施加全局 alpha**：消亡后停止产新段，各节点按年龄老去——尾部（最老）先消散、头部最后消失；弹头 bloom 层仍走自身 fade。
- **死亡加速消散**（2026-08 二轮）：消亡/命中后驱动按「寿命 / 死亡消散窗口」累加 `trailTimeOffsetSeconds` 时间偏移——整带在窗口内按尾先头后序加速老完（死亡瞬间偏移从 0 起无跳变、消散立即开始），不再等满自然寿命（×4 带长下数秒级，目检呈「停滞感」）。窗口取值：命中 0.45s / 其他移除 0.6s / 超射程 0.75s（三轮目检裁定在初版 0.3/0.4/0.5 上 +50%，即重构前等效时长的 75%）。同时**带头沿消亡前速度前飞**一小段（上限 `primaryTrailLength × 0.15` ≈ recede×1.5），带体亮端流进命中点，弥合原版弹体命中瞬灭的断头缝。dispose 时机 = max(fadeSeconds, 死亡消散窗口)。
- **拖尾锚点 = 弹体视觉头部**（2026-08 三轮）：驱动把带体/弹头树原点与历史路径锚在「弹体中心沿朝向 + `headLead`」处，`headLead` 默认 = 弹体 `spec.length/2`（原版螺栓贴图中心在弹体位置、视觉头部在 +length/2——锚在中心时带体亮头与螺栓头错开半颗弹，暂停时尤其明显）；`lifecycle{ headLead(0f) }` 可锚回中心（aod7 hero 用——其隐藏原版弹体、代码弹头网格锚在弹体中心）。

## DSL 参数面

声明位置：`ProjectileVfxSpecs.kt`（driver 包），经 `projectileVfx(id) { ... }` 构建；`.proj` 的 `onFireEffect` 指向 `ProjectileSpecOnFireDispatcher` 按 projectileSpecId 分发（见 rendering-vfx-guidelines）。

```kotlin
texTrail("层名", "graphics/fx/astd_trails_zappy.png") {
    layer(1)                    // 叠层序号：1 垫底、2 其上，绘制序 = 基线 + layer
    width(30f)                  // 拖尾全宽（世界单位）
    colors(头, 尾)              // 两色渐变（0xRRGGBBAA）
    colors(头, 中段, 尾, midAt = 0.25f)  // 三段渐变（惯例：白热头 → 签名色中段 → 暗尾）
    nodes(24)                   // 节点数下限（短带保底；实际渲染节点按可见带长动态细分，目标段长 22 su、上限 72）
    tile(140f, 50f)             // 平铺周期 su / 滚动速度 su/s（scroll/length ≈ 每秒整图滚动次数）
    recede(40f)                 // 带体整体后退，让弹头尖在带体前露出
    wobble(5f, 110f, 30f, 0.8f) // 横向扰动（dispersion）：振幅/主波长/图案平移 su/s/相位；省略即不扰动
    lifetime(0f, 0.6f)          // 逐节点寿命覆写（0=自动：预期带长/实测速度）/ 消散起点（年龄进度）；省略即默认
    twist(25f, 45f)             // 平面内扭转：弧长平滑噪声 ±25°（桶种子+smoothstep，跨帧不闪）+ 年龄累积 45°/s；第三参 wavelength 默认=平铺周期
}
```

- 可声明多条 `texTrail` 叠层（对标 MagicTrail 的多层叠加）。
- 无 `trail{}` 时锚定宽度 = 最宽一条 texTrail；拖尾采样窗口取 `sampling{ window }`。
- `head{}` 弹头**恒并入 bloom 管线**（与拖尾能量同源、接缝光晕连续），`alpha(0.7f)` 压暗防溢出是惯例；弹体消亡后弹头仍按 `fade{}` 秒数整体淡出（拖尾不参与）。**2026-08 起 head{} 仅 aod7 hero 使用**：简单 spec 的弹头改由原版弹体渲染承担（见「原版弹体渲染参考」），代码弹头能力保留不删。
- `lifecycle{ duration / dissolveAt / headScale / layoutRef }` 中 `duration/dissolveAt` 只影响编辑器/自动化预览（运行期溶解已由逐节点寿命接管）；`layoutRef` 决定带长 cap：`viewportTailCap(锚宽, layoutRef) × 4` 直接折为世界单位（2026-08 美术裁定「修复前观感 ×4」，不再乘 worldUnitsPerPixel，分辨率/缩放无关；1440p 下简单 spec ≈ 2355 su）。

## 三层贴图混合惯例（2026-08 起，简单 spec 统一；aod7 豁免）

`simpleProjectileVfx` 固定产出三条 texTrail（常量与公式在 ProjectileVfxSpecs 底部，守护测试锚定）：

| 层 | 贴图 | layer | 宽度 | alpha | twist |
| --- | --- | --- | --- | --- | --- |
| 外带 | `astd_trails_twin.png` | 1（垫底） | `bandWidth(w, g) × 2`（宽度翻倍裁定） | 0.45 | ±90° |
| 核心 | `astd_trails_smooth.png` | 2 | 外带 ×0.5 | 0.6 | ±30° |
| 装饰 | `astd_trails_zappy.png` | 3 | 外带 ×0.8 | 0.45 | ±30° |

alpha 初版 0.6/0.8/0.6 经烟测目检过曝（三层加色 + 高射速多发拖尾同走廊重叠），×0.75 压暗并保持 3:4:3 比例；后续若单发观感偏暗可回调，但不要回到初版水平。

twist 为沿带长的平滑值噪声（弧长桶种子 + smoothstep 桶间过渡：带体系跨帧稳定不闪，前后段自动衔接；波长默认与贴图平铺周期同频）；三层颜色均为「白热头 → 主色中段 → 暗尾」三段渐变 × 层 alpha。登记新弹体只填 4 旋钮（主色/宽/长/glowScale），不改层结构。

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
| `dispersion`/`drift` 段落横向散开 | drift 由历史路径自然弯曲承担（带体追踪真实弹道，天然捕获机动）；dispersion 由 `wobble(振幅, 波长, scroll, phase)` 承担——逐节点横向正弦扰动（两不可约频率叠加，已实现） |
| 段落自旋（rotation） | `twist(初相幅度°, 累积°/s)`（2026-08 已实现）：平面内扭转角，段种子挂历史点出生时间戳（跨帧稳定），前后段插值自动衔接，随年龄持续扭转 |
| 多层拖带叠加错参 | 多条 texTrail：宽比 1.2~1.5×、scroll 比 1.5~2×、alpha 错开（芯亮边暗） |

惯例锚点（aod7 hero，实机目检通过）：主带 twin `width 30 / tile(140, 50)` 垫底，副带 zappy `width 24 / tile(200, 90) / wobble(5, 110, 30, 0.8)`（振幅 ≤ 带宽 1/4），`recede(40f)`，三段色 `midAt=0.25`。

扰动（wobble）调参要点：振幅建议 ≤ 带宽 1/4（过大会撕开贴图纹样）；波长取带长 1/3~1/5 摆 3~5 个波段最自然；scroll 给 20~40 su/s 慢爬行、与贴图快滚动错出快慢两层动感；多层叠带用 phase 错相。扰动是沿带长位置的确定性函数（相位由逻辑时间推进），帧间无闪烁。

宽度锚点：主带 ≈ 弹体视觉宽 ×2~3；重击弹（贯星 36su）可再放大并配 `glowScale` 大圆弹头。

## 原版弹体渲染参考（基准形）

原版无 `bulletSprite` 的弹体（如脉冲激光、离子脉冲）用内置渲染：`graphics/fx/projbody.png`（32×16，彗星形白图，头亮尾散，core/fringe 双色染色）+ `graphics/fx/projtrail.png`（64×16，波包带）。`.proj` 里 `coreColor/fringeColor/glowColor` 染色、`textureType` 选内置变体。

**2026-08 起简单 spec 的弹头即走该路径**：ss-csv 侧 `ProjectileProjSpec.vanillaBolt(...)`（省略 bulletSprite 键，尺寸对齐原版离子脉冲 length 75 / width 20 / fadeTime 0.25 / scroll -256 / ppt 1，fringe alpha 255 / core alpha 200）。导弹无 projbody 路径，弹头用原版导弹贴图（辉星 MRM = `graphics/missiles/am_srm.png`）。需要全隐弹体的（aod7 hero、七星折跃弹）仍显式 `bulletSprite = BUtil_NONE.png` + 色 alpha=0。

**`hitGlowRadius` 必须显式给值**（vanillaBolt 默认 25，原版高射速武器口径：火神 15 / 重机枪 20 / 重型针刺 25）。缺省时原版取 `length × 2` 作命中光晕基准半径（`DamagingProjectile.setDidDamage` → `Misc.getHitGlowSize` 再按伤害放大、fringeColor 染色，逐次命中叠加 `ImpactVisualEffects.spawnHitParticlesLarge`）：length 75 即 150 基准，高射速武器连续命中会叠成吞没整舰的数百 su 加色巨球（2026-08 烟测实踩）。原版离子脉冲不给值是因为射速低、光晕有窗口衰减。

## 验证

- 纯函数单测：`texTrailNodes` / `texTrailStrip` / `ageAlphaEnvelope` / `segmentTwistBase` 直接调用做完整逻辑验证（禁源码 contain 测试）。
- 烟测：`ASTD_AUTOMATION_SCENARIO=<id> ./gradlew launchSmokeTestGame`（弹体类用 piercing_lance_basic / heavy_ion_pulse_basic），遥测键计数 + 目检；**到终态即退出，别干等超时**。
- 目检流程见 game-vfx-preview-guidelines。

## 禁做

- 不启用 `contents/data/trails/trail_data.csv`（MagicTrail 数据面，P2 已退役，仅表头遗留占位）。
- 不写「BoxUtil + 原版渲染」双实现降级分支（rendering-vfx-guidelines 总原则）。
- 贴图加载失败必须 WARN 并跳过该层（`TexTrailComponent.onAttachSelf` 先例），禁空 catch。
- 弹头与拖尾不拆到两个渲染管线（防接缝色差，head{} 恒入 bloom）。
- 不给 texTrail 加回全局 fade alpha（2026-08 起消亡由逐节点寿命接管：`beginFadeOutSelf` 为空操作是刻意的）；带长相关计算一律世界单位，禁与像素域混算。

## 优化方向（登记，待用户指示）

- ~~MagicTrail `dispersion` 等价物~~ **已实现**（2026-07）：`wobble(振幅, 波长, scroll, phase)` 进 `TexTrailSpec`，`texTrailNodes` 内逐节点横向正弦扰动（两不可约频率叠加、头部锚定、相位由逻辑时间推进保持帧间一致）；drift 不实现——带体追踪真实历史路径，机动天然被捕获。验证案例：aod7 zappy 层。
- ~~MagicTrail 段落自旋（rotation）~~ **已实现**（2026-08）：`twist(初相幅度°, 累积°/s)` 进 `TexTrailSpec`——平面内扭转，段种子挂历史点出生时间戳、前后段插值衔接、随年龄累积；同期落地逐节点寿命/年龄驱动消散（替代全局 fade），并修复带长像素/世界单位错配。
- 锥状冲击重做的连续楔块层可复用本贴图族（见 `docs/design/weapons/impl/00-锥面冲击特效重做计划.md`）。
