# 弹体 VFX 贴图化 + Shader 升级设计

> 状态：设计评审中（2026-07-26）
> 背景：RenderEntity+DSL 迁移与旧管线删除（P5）已完成，逐顶点 parity 复刻的使命结束。
> 当前网格层（body/glow/head/ribbon/sideWisp/shadow）用「逐顶点着色三角网格」手搓模糊与渐变，
> 表现天花板低（只能线性插值）、顶点量大。转向「程序化烘焙贴图 + BoxUtil 原语 + shader 附加」，
> 目标是**更好的视觉效果**，性能改善是附带收益。

## 1. 目标与原则

1. **表现第一**：追求更自然的发光体（任意衰减曲线、噪声颗粒、流动感、色散），不再以 TS 预览逐顶点对齐为目标。
2. **能用 BoxUtil 原语就不自绘几何**：原语吃 BoxUtil 的 VBO/实例化/辉光管线，消灭自研网格烘焙。
3. **目检建立新基线**：每阶段进游戏对比验收，不保留 golden/parity 式逐位断言（几何数学测试随被删层一并退役）。
4. **作者面收敛**：DSL 块向「视觉语义」合并（见 §6），不再暴露实现层的层数/模糊带参数。

## 2. BoxUtil 能力调研结论（2026-07-26，javap 实测）

| 原语 | 能力面 | 对本设计的意义 |
|---|---|---|
| `CurveEntity` | 节点曲线带（VBO），**逐节点位置/切线/颜色+emissive**（NodeData），贴图（texturePixels/textureSpeed），插值模式，`initLineStrip/initSmoothLine` 等 | **关键发现**：glow 的逐段渐变 → 逐节点颜色；软边模糊 → 带宽方向的软边贴图。glow/ribbon/sideWisp 可整体迁移，**免切片、免接缝** |
| `SpriteEntity` + `Instance2Data` | 实例化 quad，逐实例位置/朝向/缩放/多组颜色 | 弹头贴图单实例；切片方案兜底；mist 已在用 |
| `FlareEntity` | 径向辉光（smooth/sharp disc、noisePower、flick/syncFlick） | 弹头点辉光、命中闪光，原生「引擎味」光效 |
| `DistortionEntity` | 热扭曲 | 弹芯周围空气扰动，shader 附加期的低成本选项 |
| `TrailEntity`（现用） | 双色线带 | 保持，或换烘焙贴图提升质感 |

自有 `ShaderLayerPlugin` 现状：队列式 shader 提交，但几何只有 `WorldQuad`（居中矩形），且渲染走立即模式 quad。
做「沿拖尾条带的自定义 shader」需要扩展条带几何，工作量单列（§7）。

## 3. 视觉资产管线：程序化烘焙贴图

启动时（ModPlugin onApplicationLoad）按调色板程序化生成，内存缓存，不进 graphics/ 目录：

| 贴图 | 内容 | 用途 |
|---|---|---|
| `coreStrip` | 横向：头亮尾暗的渐变（任意衰减曲线）；纵向：芯亮边软的径向衰减（含原 glow/shadow 外晕） | body+glow+shadow 合并后的 CurveEntity 带 |
| `headShell` | 尖头光壳（肩部曲线、尖端高光、外晕烘进 alpha） | head |
| `ribbon` | 软边细带 + 噪声颗粒 | ribbon/sideWisp |
| `glowDot` | 径向软光斑 | 备用（切片兜底/点缀） |

- 烘焙参数化：以调色板主色 + 少数旋钮（衰减指数、噪声强度、头尾比）为键缓存；24 个 spec 共享同一批烘焙器，只是参数不同。
- 噪声/颗粒烘进贴图即可得到「非线性插值」的质感，这是纯顶点叠加给不了的第一层提升。
- 烘焙器输出 `SpriteAPI`（走 `Global.getSettings().loadTexture` 同等路径或直接 GL 纹理id 包装，实现时定）。

## 4. 分层迁移映射

| 现状层 | 目标形态 | 随之删除的代码 |
|---|---|---|
| body（弹芯渐变体） | **CurveEntity**：中线节点 + coreStrip 贴图 + 逐节点头尾色 | `ASTDProjectileVfxBodyRenderer` 网格数学、MeshComponent 的 body 分支 |
| glow ×4 层 | 合并进 coreStrip 的纵向衰减；需要额外辉光时加 1 条更宽更淡的 CurveEntity | `ASTDProjectileVfxGlowRenderer` 全部 |
| shadow（外晕带） | 烘进 coreStrip alpha 通道 | SoftMesh 的 shadow 部分 |
| head（弹头壳） | **SpriteEntity 单实例**（headShell 贴图，随 facing 旋转）+ **FlareEntity** 点辉光 | `ASTDProjectileVfxHeadRenderer` 全部 |
| ribbon（飘带） | **CurveEntity**：波浪节点采样沿用现有数学，ribbon 贴图 | `ASTDProjectileVfxRibbonRenderer` 网格部分 |
| sideWisp（侧丝） | **CurveEntity** ×offsets（或合并为一条多节点带） | `ASTDProjectileVfxSideWispRenderer` 全部 |
| mist（雾团） | 保持 SpriteEntity 实例化，换烘焙噪声贴图 | 无（贴图资产替换） |
| trail（主曳光） | 保持 TrailEntity，可选换烘焙贴图 | 无 |

收尾后 `ASTDProjectileVfxBodyRenderManager`（自研网格渲染器）与 `MeshComponent` 整体退役，
`ASTDProjectileVfxSoftMesh`/`ASTDProjectileVfxCenterline` 仅保留中线采样（CurveEntity 节点要用）。

## 5. 关键方案细节

### 5.1 弯曲轨迹：CurveEntity 而非切片

调研前预设「切片 SpriteEntity 拼曲线」，CurveEntity 的逐节点颜色+贴图能力使其降级为兜底方案：
- CurveEntity 直接吃中线节点序列（现 `ASTDProjectileHistory` 采样不变），GPU 侧插值成带，无接缝问题；
- 逐节点颜色表达「头亮尾暗 + gradient stop」，贴图表达「带宽方向软边模糊」，二者相乘 = 原 body+glow+shadow 三层效果；
- 若实测 CurveEntity 的纹理/色彩语义不符（风险 §8-1），退回切片 SpriteEntity（沿中线 N 实例，重叠+软边防接缝）。

### 5.2 长度与生命周期策略

沿用现 DSL policy（visibleLength / viewportTailCap / 存活不溶解 / 消亡后 fade 接管），节点每帧从 history 采样刷新——与现管线一致，只是写入对象从「网格顶点缓冲」换成「CurveEntity 节点」。

### 5.3 弹体头部

head 壳贴图 + FlareEntity 的组合替代现在的尖头网格：FlareEntity 的 noisePower/flick 直接给出「能量汇聚」的活感，是原网格给不了的第二层提升。

### 5.4 性能模型

- 每弹实体：1 Trail + 1~2 Curve + 1 Sprite + 1 Flare + 1 Mist ≈ 6 个 BoxUtil 实体，全部 VBO/实例化；
  现状是每弹数十~数百三角形的逐帧 CPU 烘焙（已优化为顶点数组，但数据量仍在）。
- 风险反向：CurveEntity 每帧全量刷新节点也有一定 CPU 成本，但只是写 float 数组，无 GL 调用。
- fill-rate 持平或略降（外晕贴图比模糊带顶点略省 overdraw，FlareEntity 增加了局部 overdraw）。

## 6. DSL 作者面变化

实现层合并后，作者面向视觉语义收敛（示例）：

```kotlin
projectileVfx("astd_aod7_shot") {
    core {                      // 原 trail+body+glow+shadow 合并
        color(0x478FEBEB); width(96f); length(420f)
        halo(0.35f)             // 外晕强度（原 glow/shadow 角色）
        grain(0.2f)             // 贴图噪声颗粒
    }
    head { size(1.14f); flare(0.8f) }   // 壳贴图 + FlareEntity 强度
    ribbon { ... }              // 不变
    mist { ... }                // 不变
    sampling/fade/lifecycle     // 不变
}
```

24 个 spec 的数值需要一轮「观感翻译」（旧 widthScale/blur 层叠 → 新 halo/grain 参数），这是各阶段目检的主要工作。

## 7. Shader 附加路线（后续阶段，不在本设计实施范围）

短期用贴图动画拿到大部分「活」感：UV 流动（CurveEntity textureSpeed 原生支持）、flicker（FlareEntity 原生）、噪声烘焙。
更进一步的自定义 shader（条带几何上的流动/湍流/色散）需要扩展 `ShaderLayerPlugin` 的条带几何与顶点格式，
单独立项评估；`DistortionEntity` 可作为低成本的弹芯热扭曲先行尝试。

## 8. 风险与开放问题

1. **CurveEntity 语义未验证**：texturePixels 的贴图方向（沿长/沿宽）、逐节点色与贴图的相乘关系、急弯自交表现——P0 阶段先用一个最小 PoC 在 aod7 上验证。
2. **贴图缓存键**：调色板 × 旋钮组合的缓存粒度，避免为每个 spec 各烘一张大图；倾向「共享灰度衰减图 × 实例着色」，烘焙只产灰度包络，颜色由节点/实例颜色承载——这样 24 个 spec 共享 1~2 张贴图。
3. **目检基线重建**：parity 参照物废弃后，验收标准是「比现状更好看且风格一致」，需要逐阶段截图对比。
4. **Beam 管线**：三个光束已迁 RenderEntity，本设计只覆盖弹体；光束是否跟进贴图化，弹体验证后再议。

## 9. 阶段划分

| 阶段 | 内容 | 验收 |
|---|---|---|
| P0 | CurveEntity PoC：aod7 的 core（trail+body+glow+shadow 合并）单线打通，验证 §8-1 | 进游戏对比截图，语义符合预期 |
| P1 | head/ribbon/sideWisp 迁移 + headShell/ribbon 烘焙 | 同上 |
| P2 | DSL 作者面合并（§6）+ 24 spec 观感翻译 + 删退役几何代码与测试 | 全 spec 目检 + 全量测试绿 |
| P3 | mist/trail 贴图质感升级 + FlareEntity/DistortionEntity 点缀 | 目检 |
| P4+ | shader 条带立项评估（§7） | 单独立项 |

## 10. 附：参考案例——Galactic_Constellate「驱雷」（gr_Hyades_Executioner）拆解

用户指认的观感标杆（红色光束 + 沿束周期圆环）。2026-07-26 静态拆解结论：

**该武器没有一行自定义弹体渲染代码**，效果全部来自数据面 + 原版渲染：

- `.proj`：`BALLISTIC_AS_BEAM` + RAY，`length=100 / width=60`，fringe 纯红满 alpha、core 白，
  `textureType: ROUGH`（原版 128px 噪声贴图）+ `textureScrollSpeed: 0`。
  高速连发下短弹丸段首尾相接成连续光束；**周期"圆环"来自贴图按段平铺的周期纹理与弹丸 fringe 端帽的叠加**，非自定义几何。
- `.wpn` 的 `EveryFrameEffect.onFire`：muzzle 处 `spawnExplosion` 红雾 + GraphicsLib `RippleDistortion` 涟漪扭曲
  + 100 粒 muzzle flash——炮口感官冲击主要来源。
- 遗志：`.proj` 内有被注释的 `"textureType":["graphics/weapons/gr_circleproj.png",...]`（自定义圆环贴图），
  贴图文件未打进发布包，是死实验。

**对本设计的启示**：

1. 原版 `.proj` 的 `textureType` 支持**自定义贴图数组 + `textureScrollSpeed` 滚动**——烘焙一张圆环条纹贴图挂上去，
   vanilla 渲染器零代码画出滚动圆环光束。这是一条"数据面先行"的低成本增强路径，可纳入 P3（弹丸本体贴图）。
2. 该案例证明「贴图 + 原生渲染」已足够撑起优秀观感；我们的烘焙管线（任意衰减/噪声/滚动）严格强于其静态贴图。
3. GraphicsLib `RippleDistortion` 是低成本炮口强化件，与我方 §7 的 `DistortionEntity` 方案同类，可择一。
