# 09 引力坍缩炮族（GCP）美术需求

> 大型引力坍缩炮 `astd_gcp12` / 引力坍缩炮 `astd_gcp8` / 小型引力坍缩炮 `astd_gcp4` / 小型引力坍缩炮 PD `astd_gcp2`
> 设计案：`docs/design/weapons/purple/20-production.md`；线路：LENS 紫线量产

## 1. 定位与读感目标

LENS 的引力光束基线武器族：坍缩脉冲 AOE + 低装甲点引力撕裂。读感应为「引力透镜聚焦器」——不是炮塔，是环形的观测/聚焦仪器，绯红光束从环心射出。四件尺寸共享族语言，体量与环数分级。

- 关键词：透镜环、引力聚焦、绯红光束、仪器感

## 2. 现状

- 大型（`astd_gcp12`，48×61）与中型（`astd_gcp8`，36×57）已交付独立贴图：炮塔/挂点共用同一底图，配 GLOW 动画发光层（`_glow` / `_hp_glow`），另由 `WeaponGlowLayer` 派生常驻微光层（`_glow_ambient` / `_hp_glow_ambient`）；紫色发光变体（`*_purple.png`）留档备用
- PD（`astd_gcp2`，26×38）已交付独立贴图：底图 + 开火绯红发光层（`_glow` / `_hp_glow`）+ 常驻紫微光层（`_glow_ambient` / `_hp_glow_ambient`，紫菀主题）
- 小型（`astd_gcp4`）仍用占位贴图 `astd_gcp4_base.png`（24×42，与旧 gcp2 底图同图），待补独立贴图
- 光束色已实装：core RGB(255,70,70)、fringe RGB(255,25,25)、glow RGB(255,45,45)，束体暂用原版 `beamcoreb/beamfringeb`
- 光圈环特效已用自有 `astd_generated_ring.png`，不需要新特效贴图

## 3. 资产清单

已交付（大型/中型/PD）：

| 资产 | 文件命名 | 画布 | 说明 |
|---|---|---|---|
| 大型底图 | `astd_gcp12_base.png` | 48×61 | 炮塔/挂点共用，兼作图标 |
| 大型发光层 | `astd_gcp12_glow.png` / `astd_gcp12_hp_glow.png` | 48×61 | GLOW 动画发光层（加法混合） |
| 大型常驻微光 | `astd_gcp12_glow_ambient.png` / `astd_gcp12_hp_glow_ambient.png` | 48×61 | WeaponGlowLayer AMBIENT 档（冷却期常驻） |
| 中型底图 | `astd_gcp8_base.png` | 36×57 | 炮塔/挂点共用，兼作图标 |
| 中型发光层 | `astd_gcp8_glow.png` / `astd_gcp8_hp_glow.png` | 36×57 | GLOW 动画发光层（加法混合） |
| 中型常驻微光 | `astd_gcp8_glow_ambient.png` / `astd_gcp8_hp_glow_ambient.png` | 36×57 | WeaponGlowLayer AMBIENT 档（冷却期常驻） |
| PD 底图 | `astd_gcp2_base.png` | 26×38 | 炮塔/挂点共用，兼作图标 |
| PD 开火发光层 | `astd_gcp2_glow.png` / `astd_gcp2_hp_glow.png` | 26×38 | GLOW 动画发光层（绯红，与光束一致） |
| PD 常驻微光 | `astd_gcp2_glow_ambient.png` / `astd_gcp2_hp_glow_ambient.png` | 26×38 | WeaponGlowLayer AMBIENT 档（紫菀主题紫） |

待补（小型，沿用同结构）：

| 资产 | 文件命名 | 说明 |
|---|---|---|
| 小型底图 + 发光层 + 常驻微光 | `astd_gcp4_base.png` 等 5 张 | 单环结构，替换现有 24×42 占位底图 |

路径：`contents/graphics/weapons/`。环轴朝上，转轴居中。光束武器无后坐层（gun sprite 为空白占位）。

## 4. 视觉设计需求

- 族母题：**同心透镜环 + 环心聚焦腔**，环数即级别（大型三环、中型双环、小型/PD 单环），并置时级别一眼可读
- 非对称支架 + 开放式结构，环体可局部断裂/悬浮分段，保留精密仪器感；禁止炮管语汇
- 发光：环缘与聚焦腔为引力绯红 fringe RGB(255,25,25) / 芯 RGB(255,70,70)；与湮灭涡旋的深绯红 RGB(180,20,40) 拉开明度差（GCP 更亮更「量产」，涡旋更深更「禁忌」）
- 材质：暗色合金 + 点阵灯细节，参考风格圣经 LENS 的「数据中心/观测设备」感

## 5. 验收要点

- [ ] 四件尺寸并置时环数分级可读
- [ ] 与 ARC 火炮语汇零混淆（无炮管、无散热鳍片）
- [ ] 发光绯红与光束特效同屏不偏色，且与湮灭涡旋深红可区分
