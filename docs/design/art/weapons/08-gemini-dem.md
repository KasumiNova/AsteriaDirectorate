# 08 双子星 DEM 发射器/发射舱 美术需求

> 双子星 DEM 发射器 `astd_gemini_dem_launcher`（中型导弹）/ 发射舱 `astd_gemini_dem_pod`（大型导弹）
> 实装规格：`docs/design/weapons/impl/10-gemini-dem.md`；线路：ARC 蓝线量产（蓝图）

## 1. 定位与读感目标

一轮双弹异色齐射：动能弹头（冷蓝白）剥盾附带 EMP，高爆弹头（暖橙白）拆甲，同步命中触发终结冲击。读感应为「成对出击的双联发射架」——**双联与双色**是全部视觉核心。

- 关键词：双联、异色双弹、同步终结

## 2. 现状

- 中型发射架占用版 `dragonfire_rack_med_*`、大型占用版 `dragonfire_launcher_lrg_*` 贴图
- 弹头 sprite 占用版 `graphics/missiles/dragonfire.png`（动能/高爆两弹头共用同一灰色弹体，**异色语义目前只能靠尾焰区分**）
- 锁定激光占用版红色 `targetinglaser3`；双色锁定光束（蓝/橙区分）为风险登记项 R5，列可选交付
- payload 光束色已实装（动能 [120,180,255] 系 / 高爆 [255,170,110] 系），不需要光束贴图

## 3. 资产需求清单

| 资产 | 文件命名 | 画布基准 | 说明 |
|---|---|---|---|
| 中型发射架炮塔 | `astd_gemini_dem_launcher_base.png` | 40×40 | 兼作图标，双联挂舱 |
| 中型发射架炮塔发光层 | `astd_gemini_dem_launcher_glow.png` | 40×40 | 开火/工作发光层（加法混合） |
| 中型发射架挂点 | `astd_gemini_dem_launcher_hp.png` | 40×40 纵向可拉长 | |
| 中型发射架挂点发光层 | `astd_gemini_dem_launcher_hp_glow.png` | 40×40 纵向可拉长 | 开火/工作发光层（加法混合） |
| 大型发射舱炮塔 | `astd_gemini_dem_pod_base.png` | 64×64 | 兼作图标，双联挂舱加大版 |
| 大型发射舱炮塔发光层 | `astd_gemini_dem_pod_glow.png` | 64×64 | 开火/工作发光层（加法混合） |
| 大型发射舱挂点 | `astd_gemini_dem_pod_hp.png` | 48×64 纵向 | |
| 大型发射舱挂点发光层 | `astd_gemini_dem_pod_hp_glow.png` | 48×64 纵向 | 开火/工作发光层（加法混合） |
| 动能弹头弹体 | `contents/graphics/missiles/astd_gemini_dem_kinetic.png` | 13×20 竖构图 | 冷蓝白识别 |
| 高爆弹头弹体 | `contents/graphics/missiles/astd_gemini_dem_he.png` | 13×20 竖构图 | 暖橙白识别 |
| 双色锁定光束（可选，R5） | 待开发侧定命名 | — | 蓝/橙两色锁定激光贴图 |

发射架路径 `contents/graphics/weapons/`，弹头与发射架均朝上。

## 4. 视觉设计需求

- 发射架：**双联挂舱并列**，舱内可见两枚弹头的弹尖——左/上为冷蓝白尖、右/下为暖橙白尖（或上下排布，保持中/大型一致），「双子」语义直接画在挂舱上
- 挂舱为 ARC 军工方舱语汇：棱角装甲、发射导轨、冷蓝白发光缝；与原版龙炎架的圆筒蜂巢明显区分
- 弹头贴图：同一弹体轮廓、两种涂装——动能冷蓝白 RGB(140,190,255) 主色 + 白尖；高爆暖橙白 RGB(255,190,130) 主色 + 白尖；13×20 小尺寸下靠整体色块区分，不依赖细节
- 中/大型发射架共享族语言，大型挂舱更宽、弹尖更大

## 5. 验收要点

- [ ] 挂舱上双色弹尖在图标尺寸下可辨
- [ ] 两枚弹头贴图飞行中颜色区分明确（与尾焰色不冲突）
- [ ] 与原版龙炎发射架轮廓不撞型
