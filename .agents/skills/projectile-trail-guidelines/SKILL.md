---
name: "projectile-trail-guidelines"
description: "弹体拖尾规范：统一走 staticTrail DSL（BoxUtil 1.6.0 Static Trail 托管，RenderEntity 树挂载）；含素材清单、调参指南与验证流程。"
---

# Skill：弹体拖尾规范（staticTrail DSL / BoxUtil Static Trail）

## 目标

- 弹体拖尾**统一走 `staticTrail{}` DSL**（RenderEntity 树上的 `StaticTrailComponent`，渲染由 BoxUtil 1.6.0 Static Trail 系统托管）。
- 新弹体配拖尾、旧拖尾调观感，都按本规范的参数面与调参指南执行，不另起渲染路径（自研 texTrail 栈已于 2026-09 删除）。
- 贴图素材统一放 `contents/graphics/fx/`，自制素材 `astd_trails_` 前缀。
- **贴图必须注册进 `contents/data/config/settings.json` 的 `graphics` 段**（二级结构 `类别 → {id: 路径}`，现有 `fx` 类别）。Static Trail 直接按 `getSprite().getTextureId()` 绑定裸 GL 纹理，不经过原版渲染路径，未注册的贴图永远不会被上传（textureId=0 → 整层静默不可见，2026-09 实机踩坑）。注册后由原版启动期预加载上传；`StaticTrailDataFactory` 在 textureId≤0 时会 WARN 提示。

## 渲染模型（先理解再调参）

- **宿主**：BoxUtil Static Trail 系统。每条拖尾层 = 一份 `StaticTrailData`（风格配置 + 专属环形 vRAM 池，按 `树id/层名` 缓存）+ 一个 tracker 回调。GPU 实例化带体，CPU 侧零折线网格。
- **节点寿命**：系统按三段时长推进每个节点生命——`durFadeIn`（淡入 12%）→ `durFull`（满亮至 60%）→ `durFadeOut`（线性消散到尾）。总寿命 = DSL 声明带长 / 弹体速度（`DamagingProjectileAPI.getMoveSpeed`），钳 [0.15, 10] 秒（`StaticTrailDataFactory`）。淡入 12%（原 5%）是过曝裁定：带体亮度在弹头后方渐起，避免带体亮头与原版螺栓弹头（additive 高亮）同位叠加出彗星状白团。
- **几何**：头宽 `width` → 尾宽 `width × tailWidthRatio`（默认 0.35）随生命线性收细；颜色 `headColor → tailColor` 两段渐变；additive 混合。
- **图案**：平铺滚动贴图。`tileLength` = 一周期世界单位（REPEAT 平铺），`scrollSpeed` su/s；scroll/tile ≈ 每秒整图滚动次数。
- **贴图规范**（2026-09 转置）：**N×64 PNG，X=带长向、Y=横向**（X 向 REPEAT 平铺，必须可无缝循环）；形在 alpha 通道，RGB 近白（染色来自节点色）。旧 64×N 素材已程序转置（`tools/rotate_trail_textures.py`）。
- **消亡语义**：tracker 自查弹体消亡（wasRemoved/isExpired）→ `destroy()`，带体按三段时长自然播完（尾先头后）。**不含 isFading**——超射程/命中淡出期弹体仍在飞，带体继续跟随至弹体移出引擎才开始消散（2026-09 实机裁定）。**没有加速消散窗口、没有带头前飞补偿**（迁移裁定，勿加回）。
- **拖尾锚点 = 弹体视觉头部**：tracker 锚点 = 弹体中心沿朝向提前（headLead − recede），headLead 默认 = 弹体 `spec.length/2`（原版螺栓贴图中心在弹体位置、视觉头部在 +length/2）；`lifecycle{ headLead(0f) }` 可锚回中心。`recede` 让带体亮端退到弹头之后。
- **bloom**：`glow(power)` 进 BoxUtil emissive → bloom G-buffer；emissive 复用 diffuse 贴图并以头部色染色。**默认 0 不发光**（原版螺栓无辉光；三层 additive 叠在弹头上再叠加 bloom 会过曝成白团，2026-09 实机实踩）。
- **节点漂移/自旋（DSL 已暴露）**：`angularOut(min,max)`/`angularIn(...)` = 每节点随机自旋角速度（度/秒，绕节点锚点、基于带体朝向，In=最新节点→Out=最老节点按生命插值）；`velocityOut(minX,minY,maxX,maxY)`/`velocityIn(...)` = 每节点随机漂移速度（su/s），带尾漂离原航迹（碎屑/烟雾类消散漂移用）。**着色器语义（实读 BUtil_StaticTrail.vert）：自旋旋转的是漂移偏移矢量——velocity 全 0 时 angular 完全无效**，两者必须成对设置。直线弹体主带不用；**zappy 电弧装饰层默认 `angularOut()`（±45°/s）+ `velocityOut(-16,-16,16,16)`**，带尾卷曲（2026-09 裁定，嫌飘可显式传小值）。

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
    glow(1f)                    // bloom 发光强度（0..1）；省略即不发光（原版螺栓无辉光，默认 0）
    angularOut()                // 尾端每节点随机自旋（度/秒，默认 ±45）；angularIn 同理
    velocityOut(-10f,-10f,10f,10f) // 尾端每节点随机漂移速度（su/s，minX,minY,maxX,maxY）；velocityIn 同理
}
```

- 可声明多条 `staticTrail` 叠层。
- 弹头**全部由原版弹体渲染承担**（`.proj` 走 `vanillaBolt` projbody/projtrail 螺栓，见「原版弹体渲染参考」）；代码弹头（head{} DSL）已随自研渲染栈删除，aod7 亦不例外。
- 驱动策略只剩 `fade{}`（淡出秒数，作用于 boxFlare 等附加层）与 `lifecycle{ headLead }`；拖尾自身的采样/寿命/几何全部由 Static Trail 系统接管。
- **StaticTrailData 按 `树id/层名` 缓存**（vRAM 池配置须 const）：调试期 DSL 字面量热交换对拖尾层不生效（需重启），附加层（boxFlare/anchorArc）不受影响。

## 三层贴图混合惯例（简单 spec 统一）

`simpleProjectileVfx` 固定产出三条 staticTrail（常量与公式在 ProjectileVfxSpecs 底部，守护测试锚定）：

| 层 | 贴图 | layer | 宽度 | alpha |
| --- | --- | --- | --- | --- |
| 外带 | `astd_trails_twin.png` | 1（垫底） | `bandWidth(w, g) × 2`（宽度翻倍裁定） | 0.45 |
| 核心 | `astd_trails_smooth.png` | 2 | 外带 ×0.5 | 0.6 |
| 装饰 | `astd_trails_zappy.png` | 3 | 外带 ×0.6 | 0.45 |

alpha 0.45/0.6/0.45 是过曝压暗后的裁定（三层加色 + 高射速多发拖尾同走廊重叠），保持 3:4:3 比例；后续若单发观感偏暗可回调，但不要回到 0.6/0.8/0.6 初版水平。三层颜色均为「亮头 → 暗尾」两段渐变 × 层 alpha。登记新弹体只填 4 旋钮（主色/宽/长/glowScale），不改层结构。

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

### 新素材规则

- 命名 `astd_trails_<形貌词>.png`，**N×64（X=带长向、N 为 2 的幂）**，形在 alpha、RGB 近白、X 向无缝平铺。
- 旧向素材（64×N）可用 `tools/rotate_trail_textures.py` 转置。
- 做完先在黑底上目检平铺接缝，再进游戏验证。

## 观感调参指南

| 观感机制 | staticTrail DSL 等价物 |
| --- | --- |
| zigzag 贴图自带折线 | 直接选 zappy / zappysmooth 贴图 |
| 花纹爬行 | `tile(length, scroll)`，scroll/tile ≈ 0.3~0.7/秒 读起来最活 |
| 横向散开 | drift 由带体追踪真实弹道承担（tracker 锚点跟弹体，机动天然捕获）；Box Static Trail 无横向扰动语义，不硬造 |
| 多层拖带叠加错参 | 多条 staticTrail：宽比 1.2~1.5×、scroll 比 1.5~2×、alpha 错开（芯亮边暗） |

惯例锚点（aod7 hero）：主带 twin `width 30 / tile(140, 50)` 垫底，副带 zappy `width 24 / tile(200, 90)` + 默认 `angularOut()/velocityOut(±16)` + `glow(0.5f)`，`recede(90f)`（aod7 螺栓长 138：锚点退到螺栓后段，带体满亮区起在螺栓尾后、不与螺栓头同位叠加；recede 138 实机会把带体亮头整个推出螺栓、留下暗缺口，勿用）。bloom 只开单层（简单 spec 核心层 0.45 / aod7 zappy 0.5）：三层全开曾过曝成白团。

宽度锚点：主带 ≈ 弹体视觉宽 ×2~3；重击弹（贯星 36su）可再放大并配 boxFlare 附加层。

## 原版弹体渲染参考（基准形）

原版无 `bulletSprite` 的弹体（如脉冲激光、离子脉冲）用内置渲染：`graphics/fx/projbody.png`（32×16，彗星形白图，头亮尾散，core/fringe 双色染色）+ `graphics/fx/projtrail.png`（64×16，波包带）。`.proj` 里 `coreColor/fringeColor/glowColor` 染色、`textureType` 选内置变体。

**简单 spec 的弹头即走该路径**：ss-csv 侧 `ProjectileProjSpec.vanillaBolt(...)`（省略 bulletSprite 键，尺寸对齐原版离子脉冲 length 75 / width 20 / fadeTime 0.25 / scroll -256 / ppt 1，fringe alpha 255 / core alpha 200）。导弹无 projbody 路径，弹头用原版导弹贴图（辉星 MRM = `graphics/missiles/am_srm.png`）。需要全隐弹体的（七星折跃弹）仍显式 `bulletSprite = BUtil_NONE.png` + 色 alpha=0。

**`hitGlowRadius` 必须显式给值**（vanillaBolt 默认 25，原版高射速武器口径：火神 15 / 重机枪 20 / 重型针刺 25）。缺省时原版取 `length × 2` 作命中光晕基准半径（`DamagingProjectile.setDidDamage` → `Misc.getHitGlowSize` 再按伤害放大、fringeColor 染色，逐次命中叠加 `ImpactVisualEffects.spawnHitParticlesLarge`）：length 75 即 150 基准，高射速武器连续命中会叠成吞没整舰的数百 su 加色巨球（2026-08 烟测实踩）。原版离子脉冲不给值是因为射速低、光晕有窗口衰减。

## 验证

- 纯函数单测：`trailAnchor`（ASTDProjectileTrailTrackerTest）、`totalDurationSeconds` 与三段比例（StaticTrailDataFactoryTest）、公式锚点（ProjectileVfxSpecsTest）直接调用做完整逻辑验证（禁源码 contain 测试）。
- 烟测：`ASTD_AUTOMATION_SCENARIO=<id> ./gradlew launchSmokeTestGame`（弹体类用 piercing_lance_basic / heavy_ion_pulse_basic），遥测键计数 + 目检；**到终态即退出，别干等超时**。
- 目检流程见 game-vfx-preview-guidelines。

## 已知上游问题（BoxUtil 1.6.0）

- **生涯战斗中战斗层 Static Trail 不计算**：`BUtil_StaticTrailMemoryPool.computeTrailNode` 在 `isInCampaignSector()=true` 时旁路全部战斗层拖尾，而该标志在整个生涯期间（含生涯实战）恒为 true、仅回标题复位——表现为生涯实战里拖尾注册成功但完全不渲染，任务/模拟场景正常（2026-09 实机定位，已反馈 BoxUtil 作者；建议上游修法：战役判定追加 `Global.getCombatEngine() == null`）。修复落地前生涯里看不到拖尾属预期。
- **暂停一致性（已排查，Static Trail 自身自洽）**：节点时间戳（`computeTrailNode`）与着色器 `u_time` 同源——均为 `BUtil_GLImpl.timer[2]`（`getElapsedTimeWithoutPaused()`，暂停时冻结）；暂停期间逻辑线程 `!isPaused() && doStaticTrailCompute()` 双重门控、不记节点。ASTD 侧 tracker 已无任何时间依赖。
- **带体头离散落后（「暂停后不同步」的真相，上游设计）**：节点记录按 `BUtil_StaticTrailSystemRecordsCycle` 批量进行（LunaLib 可调 `BUtil_TrailSystemQuality`：NORMAL 30Hz / HIGH 60Hz / ULTRA 144Hz），记录周期间带体头停在上一节点、弹体继续飞——800su/s 弹体在 30Hz 下带头恒落后 0~27su 并抖动；暂停/恢复会把该落后定格/跳变，肉眼可见「贴图不同步」。原版逐帧渲染与 MagicTrail 逐帧采样无此现象，故只有走 Static Trail 的 ASTD 弹体出现。缓解：LunaLib 设置里调高 TrailSystemQuality；根治需上游逐帧记录模式。

## 禁做

- 不启用 `contents/data/trails/trail_data.csv`（MagicTrail 数据面，已退役）。
- 不写「BoxUtil + 原版渲染」双实现降级分支（rendering-vfx-guidelines 总原则）。
- 贴图加载失败必须 WARN 并跳过该层（`StaticTrailComponent.onAttachSelf` 先例），禁空 catch。
- 不给 Static Trail 加回全局 fade alpha / 加速消散窗口 / 消亡前飞补偿（2026-09 迁移裁定：destroy() 自然播完）；带长相关计算一律世界单位，禁与像素域混算。
- 不自研 CPU 折线带体/历史采样（旧 texTrail 栈已删，Static Trail 系统全权托管）。

## 优化方向（登记，待用户指示）

- 锥状冲击重做的连续楔块层可复用本贴图族（见 `docs/design/weapons/impl/00-锥面冲击特效重做计划.md`）。
