---
name: "projectile-trail-guidelines"
description: "弹体拖尾/弹头规范：拖尾统一走 staticTrail DSL（BoxUtil Static Trail 托管，RenderEntity 树挂载），弹头统一走 bolt DSL（Box 螺栓，SpriteEntity projbody）；含素材清单、调参指南与验证流程。"
---

# Skill：弹体拖尾规范（staticTrail DSL / BoxUtil Static Trail）

## 目标

- 弹体拖尾**统一走 `staticTrail{}` DSL**（RenderEntity 树上的 `StaticTrailComponent`，渲染由 BoxUtil Static Trail 系统托管）。
- 弹体弹头**统一走 `bolt{}` DSL**（RenderEntity 树上的 `BoltRenderComponent`，Box SpriteEntity 双层 projbody 螺栓渲染，逐帧消费弹体真值，暂停零跳变）；原版螺栓渲染路径已由 ss-csv `boxBolt` 工厂屏蔽。
- 新弹体配拖尾、旧拖尾调观感，都按本规范的参数面与调参指南执行，不另起渲染路径。
- 贴图素材统一放 `contents/graphics/fx/`，自制素材 `astd_trails_` 前缀。
- **贴图必须注册进 `contents/data/config/settings.json` 的 `graphics` 段**（二级结构 `类别 → {id: 路径}`，现有 `fx` 类别）。Static Trail 直接按 `getSprite().getTextureId()` 绑定裸 GL 纹理，不经过原版渲染路径，未注册的贴图永远不会被上传（textureId=0 → 整层静默不可见）；Box 螺栓的 SpriteEntity 同样走预载纹理。注册后由原版启动期预加载上传；`StaticTrailDataFactory` 在 textureId≤0 时会 WARN 提示。

## 渲染模型（先理解再调参）

- **宿主**：BoxUtil Static Trail 系统。每条拖尾层 = 一份 `StaticTrailData`（风格配置 + 专属环形 vRAM 池，按 `树id/层名` 缓存）+ 一个 tracker 回调。GPU 实例化带体，CPU 侧零折线网格。
- **节点寿命**：系统按三段时长推进每个节点生命——`durFadeIn`（淡入 12%）→ `durFull`（满亮至 60%）→ `durFadeOut`（线性消散到尾）。总寿命 = DSL 声明带长 / 弹体速度（`DamagingProjectileAPI.getMoveSpeed`），钳 [0.15, 10] 秒（`StaticTrailDataFactory`）。淡入 12% 是过曝裁定：带体亮度在弹头后方渐起，避免带体亮头与 Box 螺栓弹头（additive 高亮）同位叠加出彗星状白团。
- **几何**：头宽 `width` → 尾宽 `width × tailWidthRatio`（默认 0.35）随生命线性收细；颜色 `headColor → tailColor` 两段渐变；additive 混合。
- **图案**：平铺滚动贴图。`tileLength` = 一周期世界单位（REPEAT 平铺），`scrollSpeed` su/s；scroll/tile ≈ 每秒整图滚动次数。
- **贴图规范**：**N×64 PNG，X=带长向、Y=横向**（X 向 REPEAT 平铺，必须可无缝循环）；形在 alpha 通道，RGB 近白（染色来自节点色）。
- **消亡语义**：tracker 自查弹体消亡（wasRemoved/isExpired）→ `destroy()`，带体按三段时长自然播完（尾先头后）。**不含 isFading**——超射程/命中淡出期弹体仍在飞，带体继续跟随至弹体移出引擎才开始消散。**没有加速消散窗口、没有带头前飞补偿**（勿加回）。
- **拖尾锚点 = 弹体视觉头部**：tracker 锚点 = 弹体中心沿朝向提前（headLead − recede），headLead 默认 = 弹体 `spec.length/2`（螺栓贴图中心在弹体位置、视觉头部在 +length/2）；`lifecycle{ headLead(0f) }` 可锚回中心。`recede` 让带体亮端退到弹头之后。
- **bloom**：`glow(power)` 进 BoxUtil emissive → bloom G-buffer；emissive 复用 diffuse 贴图并以头部色染色。**默认 0 不发光**（Box 螺栓无辉光；三层 additive 叠在弹头上再叠加 bloom 会过曝成白团）。
- **节点漂移/自旋（DSL 已暴露）**：`angularOut(min,max)`/`angularIn(...)` = 每节点随机自旋角速度（度/秒，绕节点锚点、基于带体朝向，In=最新节点→Out=最老节点按生命插值）；`velocityOut(minX,minY,maxX,maxY)`/`velocityIn(...)` = 每节点随机漂移速度（su/s），带尾漂离原航迹（碎屑/烟雾类消散漂移用）。**着色器语义（实读 BUtil_StaticTrail.vert）：自旋旋转的是漂移偏移矢量——velocity 全 0 时 angular 完全无效**，两者必须成对设置。直线弹体主带不用；**zappy 电弧装饰层默认 `angularOut()`（±45°/s）+ `velocityOut(-16,-16,16,16)`**，带尾卷曲（嫌飘可显式传小值）。

## DSL 参数面

声明位置：`ProjectileVfxSpecs.kt`（driver 包），经 `projectileVfx(id) { ... }` 构建；`.proj` 的 `onFireEffect` 指向 `ProjectileSpecOnFireDispatcher` 按 projectileSpecId 分发（见 rendering-vfx-guidelines）。

```kotlin
staticTrail("层名", "graphics/fx/astd_trails_zappy.png") {
    layer(1)                    // 叠层序号：1 垫底、2 其上（additive 下仅作树内组织语义）
    width(30f)                  // 拖尾头部全宽（世界单位）；尾宽 = 本值 × tailWidthRatio
    tailWidth(0.35f)            // 尾宽比（0..1），默认 0.35
    colors(头, 尾)              // 两段渐变（0xRRGGBBAA）：头部亮端 → 尾部暗端
    length(420f)                // 预期带长（世界单位）：节点总寿命 = 带长 / 弹体速度
    tile(140f, 50f)             // 平铺周期 su / 滚动速度 su/s
    recede(40f)                 // 带体整体后退，让弹头尖在带体前露出
    glow(1f)                    // bloom 发光强度（0..1）；省略即不发光（Box 螺栓无辉光，默认 0）
    angularOut()                // 尾端每节点随机自旋（度/秒，默认 ±45）；angularIn 同理
    velocityOut(-10f,-10f,10f,10f) // 尾端每节点随机漂移速度（su/s，minX,minY,maxX,maxY）；velocityIn 同理
}

bolt {                          // Box 螺栓弹头（默认开启；导弹类弹体 `bolt { off() }` 关闭）
    texture("graphics/fx/astd_bolt_body.png")  // 弹头贴图（默认烘焙版彗形图，见下节）
    color(0xE4F2FFC8)           // 弹头染色（0xRRGGBBAA，原版 coreColor 语义，通常近白）
}
```

- 可声明多条 `staticTrail` 叠层。
- 弹头**统一走 `bolt{}`**（`BoltRenderComponent`：Box SpriteEntity 双趟叠加烘焙彗形贴图，additive；逐帧消费 `getBrightness()`/`getTailEnd()` 真值做几何同步与出生伸入 alpha，见「Box 螺栓渲染参考」）。ss-csv 侧须配 `boxBolt(...)` 屏蔽原版螺栓视觉。
- 驱动策略只剩 `fade{}`（淡出秒数，作用于 boxFlare 等附加层）与 `lifecycle{ headLead }`；拖尾自身的采样/寿命/几何全部由 Static Trail 系统接管。
- **StaticTrailData 按 `树id/层名` 缓存**（vRAM 池配置须 const）：调试期 DSL 字面量热交换对拖尾层不生效（需重启），组件层（bolt/boxFlare/anchorArc）不受影响。

## 三层贴图混合惯例（简单 spec 统一）

`simpleProjectileVfx` 固定产出三条 staticTrail（常量与公式在 ProjectileVfxSpecs 底部，守护测试锚定）：

| 层 | 贴图 | layer | 宽度 | alpha |
| --- | --- | --- | --- | --- |
| 外带 | `astd_trails_twin.png` | 1（垫底） | `bandWidth(w, g) × 2`（宽度翻倍裁定） | 0.45 |
| 核心 | `astd_trails_smooth.png` | 2 | 外带 ×0.5 | 0.6 |
| 装饰 | `astd_trails_zappy.png` | 3 | 外带 ×0.6 | 0.45 |

alpha 0.45/0.6/0.45 是过曝压暗后的裁定（三层加色 + 高射速多发拖尾同走廊重叠），保持 3:4:3 比例；若单发观感偏暗可小幅回调，但不要超过 0.6/0.8/0.6。三层颜色均为「亮头 → 暗尾」两段渐变 × 层 alpha。登记新弹体只填 4 旋钮（主色/宽/长/glowScale），不改层结构。

## 素材清单

### 自制（首选，astd_ 前缀，观感从柔到烈）

| 贴图 | 观感 | 建议用途 |
| --- | --- | --- |
| `astd_trails_flow.png` (256×64) | 柔和波浪流 | 主带/能量弹主体 |
| `astd_trails_surge.png` (256×64) | 涌动波包，能量感强 | 重击/充能弹主带 |
| `astd_trails_twin.png` (128×64) | 两条干净平行线 | 垫底衬带/轨道感 |
| `astd_trails_zappy.png` (256×64) | 暴烈电弧折线 | 电弧副带/芯带 |
| `astd_trails_zappysmooth.png` (256×64) | 温和电弧折线 | 电弧主带（扭曲但不刺眼） |

### 同源素材（内容等同 GC 原图，统一 astd_ 前缀）

| 贴图 | 观感 | 备注 |
| --- | --- | --- |
| `astd_trails_smooth.png` (256×64) | 干净宽软带 | `TEX_SMOOTH` 常量，简单 spec 核心层默认 |
| `astd_trails_contrail.png` (128×64) | 宽云状噪声带 | 锥面楔块底层选型 |

`TEX_TWIN`/`TEX_SMOOTH`/`TEX_ZAPPY` 常量在 ProjectileVfxSpecs 底部。

### 弹头贴图（非拖尾，规则不同）

| 贴图 | 观感 | 备注 |
| --- | --- | --- |
| `astd_bolt_body.png` (128×32) | 彗形螺栓弹头（头亮尾隐） | Box 螺栓默认贴图；projbody + 原版顶点梯度/收窄烘焙，生成脚本 `tools/build_bolt_body_texture.py`，X=飞行向、头在左、不要求平铺 |

### 新素材规则

- 拖尾贴图命名 `astd_trails_<形貌词>.png`，**N×64（X=带长向、N 为 2 的幂）**，形在 alpha、RGB 近白、X 向无缝平铺。
- 旧向素材（64×N）可用 `tools/rotate_trail_textures.py` 转置。
- 做完先在黑底上目检平铺接缝，再进游戏验证。

## 观感调参指南

| 观感机制 | staticTrail DSL 等价物 |
| --- | --- |
| zigzag 贴图自带折线 | 直接选 zappy / zappysmooth 贴图 |
| 花纹爬行 | `tile(length, scroll)`，scroll/tile ≈ 0.3~0.7/秒 读起来最活 |
| 横向散开 | drift 由带体追踪真实弹道承担（tracker 锚点跟弹体，机动天然捕获）；Box Static Trail 无横向扰动语义，不硬造 |
| 多层拖带叠加错参 | 多条 staticTrail：宽比 1.2~1.5×、scroll 比 1.5~2×、alpha 错开（芯亮边暗） |

惯例锚点（aod7 hero）：主带 twin `width 30 / tile(140, 50)` 垫底，副带 zappy `width 24 / tile(200, 90)` + 默认 `angularOut()/velocityOut(±16)` + `glow(0.5f)`，`recede(headRecede(420f))`=35（aod7 螺栓长 138：带体满亮区起在螺栓中段之后、不与螺栓头同位叠加；recede 上限规则见「上游机制备忘」，超过会把带体亮头推出螺栓、留下暗缺口）。bloom 只开单层（简单 spec 核心层 0.45 / aod7 zappy 0.5）：三层全开会过曝成白团。

宽度锚点：主带 ≈ 弹体视觉宽 ×2~3；重击弹（贯星 36su）可再放大并配 boxFlare 附加层。

## Box 螺栓渲染参考（弹头基准形）

弹头由 `BoltRenderComponent` 承担，观感对齐原版 ProjectileRenderer 的 built-in 螺栓（其形塑机制：projbody 贴图 alpha 沿全长几乎不透明，彗形全靠**逐顶点 alpha 梯度**（头全亮 → 中段减半 → 尾透明）与**梯形几何收窄**（头全宽 → 尾半宽）——SpriteEntity 单 quad 无逐顶点色，故两者已烘焙进贴图）：

- **贴图 `graphics/fx/astd_bolt_body.png`**（128×32，X=飞行向、头在左）：projbody 彗形 × 纵向渐隐 ramp（1→0）× 尾部收窄窗，生成脚本 `tools/build_bolt_body_texture.py`（可复跑调形）。
- **双趟叠加**：两颗相同 SpriteEntity（= 原版 body 双 pass），统一染 DSL `color`（原版 coreColor 语义，近白；fringeColor 的 projtrail 外带语义由 Static Trail 接替）。ABOVE_SHIPS_LAYER additive。
- **逐帧同步**：几何 `boltFrame`（贴图跨 [tailEnd → 弹体位置]，X 缩放 = 覆盖长/spec.length，出生伸入同 TrailExtender distanceRatio 语义）；alpha = `getBrightness()`² × 染色 alpha（原版 body 两趟均吃平方亮度）。暂停时 driver 门控冻结，与弹体同步，无 30Hz cadence 跳变。

ss-csv 侧：接入管线的弹体统一用 `ProjectileProjSpec.boxBolt(...)`——发射 `bulletSprite=graphics/textures/BUtil_NONE.png` + core/fringe 色 alpha=0（屏蔽原版螺栓视觉与原版命中光晕）+ scroll=0，但 **length/width/fadeTime(0.25)/hitGlowRadius 保真实值**：length 仍是拖尾 headLead、brightness 伸入距离与 boltFrame 几何的数据源。导弹不走此路径（组件 attach 时 `projectile is MissileAPI` 即禁用自身；辉星 MRM 弹头 = 原版导弹贴图 `graphics/missiles/am_srm.png`）。`vanillaBolt(...)` 工厂保留，仅供不对接管线的弹体。

**命中光晕由组件补发**：原版光晕走 fringeColor（已被屏蔽为 alpha=0），`BoltRenderComponent.didDamage` 按 `hitGlowRadius × 3 × 伤害缩放`（DSL 染色 0.4s）+ 白色芯（×0.5，0.8s）发 hitParticle。因此 **`hitGlowRadius` 必须显式给值**（boxBolt 默认 25，原版高射速武器口径：火神 15 / 重机枪 20 / 重型针刺 25）；缺省时原版取 `length × 2` 作基准半径（`Misc.getHitGlowSize` 再按伤害放大），length 75 即 150 基准，高射速武器连续命中会叠成吞没整舰的数百 su 加色巨球。

## 验证

- 纯函数单测：`trailAnchor`（ASTDProjectileTrailTrackerTest）、`totalDurationSeconds` 与三段比例（StaticTrailDataFactoryTest）、公式锚点（ProjectileVfxSpecsTest）直接调用做完整逻辑验证（禁源码 contain 测试）。
- 烟测：`ASTD_AUTOMATION_SCENARIO=<id> ./gradlew launchSmokeTestGame`（弹体类用 piercing_lance_basic / heavy_ion_pulse_basic），遥测键计数 + 目检；**到终态即退出，别干等超时**。
- 目检流程见 game-vfx-preview-guidelines。

## 上游机制备忘（BoxUtil Static Trail）

- **暂停一致性**：节点时间戳（`computeTrailNode`）与着色器 `u_time` 同源——均为 `BUtil_GLImpl.timer[2]`（`getElapsedTimeWithoutPaused()`，暂停时冻结）；暂停期间逻辑线程 `!isPaused() && doStaticTrailCompute()` 双重门控、不记节点。ASTD 侧 tracker 无任何时间依赖。
- **节点记录 cadence（拖尾「暂停后不同步/跳变」的来源）**：节点记录按 `BUtil_GLImpl.advanceTimer` 的 `getTrailSystemNodesRecordsCycle()` 门控（LunaLib `BUtil_TrailSystemQuality`：NORMAL 30Hz / HIGH 60Hz / ULTRA 144Hz），每个 cycle 只在 `computeData` 写**一个**节点、渲染头=最后记录节点——cycle 间拖尾头停在上一节点、弹体继续飞，**aod7 2880su/s 在 30Hz 下每节点跨 96su**，带头阶梯跳动；暂停把滞后相位定格、恢复时下一 tick 把头前甩一个节点间距，高速弹肉眼可见「跳变」。弹头（Box 螺栓）逐帧渲染、无此现象。ASTD 侧缓解：recede 上限规则 `recede ≤ headLead + 弹体spec.length/2 − speed/30`，保证最坏 cadence 相位下拖尾头仍藏在 Box 螺栓覆盖区（aod7 recede=35）。根治需上游：fill 头节点用 tracker 实时位置外插，或提供逐帧记录模式。
- **弹体出生伸入亮度（探针读图注意）**：Box 螺栓与原版 TrailExtender 消费同一真值——`DamagingProjectileAPI.getBrightness()` = `(1-progress) × distanceRatio`，distanceRatio 要飞满 `proj.length`（aod7=138su）才到 1；几何锚定同理消费 `getTailEnd()`。弹速低时伸入期拉长到肉眼可见，恰好跨暂停窗口时易误读为「暂停导致突变」。实弹速下约 0.05s 内完成，无感知。

## 禁做

- 不启用 `contents/data/trails/trail_data.csv`（MagicTrail 数据面，不再使用）。
- 不写「BoxUtil + 原版渲染」双实现降级分支（rendering-vfx-guidelines 总原则）。
- 不给已接入管线的弹体恢复原版螺栓渲染（vanillaBolt / projtrail 外带），不写「Box 螺栓 + 原版螺栓」双渲染分支。
- 贴图加载失败必须 WARN 并跳过该层（`StaticTrailComponent.onAttachSelf` 先例），禁空 catch。
- 不给 Static Trail 加回全局 fade alpha / 加速消散窗口 / 消亡前飞补偿（destroy() 自然播完）；带长相关计算一律世界单位，禁与像素域混算。
- 不自研 CPU 折线带体/历史采样（Static Trail 系统全权托管）。

## 优化方向（登记，待用户指示）

- 锥状冲击重做的连续楔块层可复用本贴图族（见 `docs/design/weapons/impl/00-锥面冲击特效重做计划.md`）。
