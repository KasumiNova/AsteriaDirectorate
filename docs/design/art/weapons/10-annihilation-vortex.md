# 10 湮灭涡旋 美术需求

> 湮灭涡旋 `astd_annihilation_vortex`（大型能量光束，协同槽）
> 实装规格：`docs/design/weapons/impl/04-annihilation-vortex.md`；线路：LENS 紫线**准超规格**（T3~T4 支线赏金，P6 前 no_drop）

## 1. 定位与读感目标

爆发光束终点展开引力涡旋，吞噬敌方弹药并在坍缩时反打。读感应为「奇点发生器」——比 GCP 族更大型、更禁忌的聚焦仪器，炮口即是「会吃掉你火力的东西」。

- 关键词：奇点、涡旋、吞噬、坍缩、禁忌感

## 2. 现状

- 炮塔/挂点/枪身暂借 GCP 同族 `astd_ftb_omega_base.png` / `astd_ftb_omega_gun.png`，与 GCP 量产族零区分，实装文档已登记 **TODO：替换为 LENS 深红系专属炮塔**
- 光束色已实装：core RGB(255,60,70)、fringe RGB(180,20,40)、glow RGB(220,30,50)，深绯红系
- 涡旋本体为 Shader 材质 + 扭曲实体 + 吸收 flare，全部代码实现，不需要特效贴图

## 3. 资产需求清单

| 资产 | 文件命名 | 画布基准 | 说明 |
|---|---|---|---|
| 炮塔底图 | `astd_annihilation_vortex_base.png` | 72×72 | 兼作图标，大型聚焦阵列 |
| 炮塔发光层 | `astd_annihilation_vortex_glow.png` | 72×72 | 开火/工作发光层（加法混合） |
| 炮塔枪身覆层 | `astd_annihilation_vortex_gun.png` | 72×72 | 聚焦环前段 |
| 挂点底图 | `astd_annihilation_vortex_hp.png` | 48×72 纵向 | |
| 挂点发光层 | `astd_annihilation_vortex_hp_glow.png` | 48×72 纵向 | 开火/工作发光层（加法混合） |
| 挂点枪身覆层 | `astd_annihilation_vortex_hp_gun.png` | 48×72 | |

路径：`contents/graphics/weapons/`。环轴朝上，转轴居中。

## 4. 视觉设计需求

- 在 GCP 族「同心透镜环」母题上升格：**大口径主聚焦环 + 内部涡旋纹腔体**，环体分段悬浮、间距更大，腔体深处做暗（奇点黑的暗示）
- 发光为**深绯红** fringe RGB(180,20,40) / 芯 RGB(255,60,70)，明度压低于 GCP 亮绯红，呈现「更危险、更深」的层级差
- 非对称程度可超过 GCP（准超规格的破格感），但保持 LENS 精密仪器底色，不做生物质/尖刺
- 与 GCP 并置时：同族语汇（环、仪器感）可辨，但体量与深度明确宣告「这不是量产货」

## 5. 验收要点

- [ ] 与 GCP 族同屏时「同族但更高位」的关系成立
- [ ] 深绯红与 GCP 亮绯红可区分（实机截图对比）
- [ ] 腔体暗部与奇点黑暗示在深色太空背景下不糊成一团
