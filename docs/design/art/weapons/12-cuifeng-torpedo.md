# 12 摧锋鱼雷 / 摧锋鱼雷发射器 美术需求

> 摧锋鱼雷 `astd_cuifeng_torpedo`（小型导弹）/ 摧锋鱼雷发射器 `astd_cuifeng_launcher`（中型导弹）
> 实装规格：`docs/design/weapons/blue/30-superlative.md` §摧锋鱼雷；线路：ARC 蓝线**稀有掉落**（赏金掉落，P6 前 no_drop）

## 1. 定位与读感目标

坠星科研部的重型反舰鱼雷：大当量反物质装药 + 实验性推进器，单发重锤、命中即全额 AOE，性能空前但成本高到无法量产。读感应是「军工重型鱼雷」——粗、短、钝头，一眼读得出装药舱的分量，而不是纤细导弹。

- 关键词：反物质重锤、钝头装药舱、实验推进器、导轨发射架、ARC 军工

## 2. 现状

- 小型（`astd_cuifeng_torpedo`）已实装**无发射架**形态：炮塔/挂点 sprite 均为 BoxUtil 空白贴图 `graphics/textures/BUtil_NONE.png`，保留 `RENDER_LOADED_MISSILES` 只渲染待发弹体本体；物品图标由原版 WeaponIconRenderer 渲染弹体本体，不需要独立图标贴图
- 中型发射架（`astd_cuifeng_launcher`）已交付独立贴图：36×44 底图（炮塔/挂点共用，兼作图标）+ `WeaponGlowLayer` 派生常驻微光层（`_glow_ambient` / `_hp_glow_ambient`）
- 弹体已用自制 `graphics/weapons/astd_cuifeng_missile.png`（13×35），同图承担挂点待发弹体（RENDER_LOADED_MISSILES）与飞行本体（.proj sprite）渲染；粗壮度仍待美术重画，重画后替换同一路径即可
- 弹体 VFX 已实装：ARC 冷蓝白拖尾（带长 = 射程 50%，亮头直抵弹头）+ 弹头 SMOOTH 光斑 + 命中双叠十字辉星与蓝白爆炸星云，不需要特效贴图
- 弹体走原版渲染路径（`.proj` sprite），弹头朝上

## 3. 资产需求清单

已交付（中型发射架）：

| 资产 | 文件命名 | 画布基准 | 说明 |
|---|---|---|---|
| 中型发射架底图 | `astd_cuifeng_launcher_base.png` | 36×44 | 炮塔/挂点共用，兼作图标 |
| 中型发射架常驻微光 | `astd_cuifeng_launcher_glow_ambient.png` / `astd_cuifeng_launcher_hp_glow_ambient.png` | 36×44 | WeaponGlowLayer AMBIENT 档（冷却期常驻） |

待补：

| 资产 | 文件命名 | 画布基准 | 说明 |
|---|---|---|---|
| 鱼雷弹体重画 | 替换现有 `graphics/weapons/astd_cuifeng_missile.png`（13×35） | 竖构图 | 两槽位共用弹体；比原版鱼雷更粗短，重画后同路径替换 |

小型（`astd_cuifeng_torpedo`）按裁定为无发射架形态：sprite 用空白贴图，只渲染弹体本体，图标渲染弹体，不需要任何发射架贴图。

发射架路径 `contents/graphics/weapons/`，发射架炮口与弹体弹头均朝上。

## 4. 视觉设计需求

- 弹体：**粗短钝头的重型鱼雷轮廓**，装药舱占弹体体量的一半以上；尾部可画实验推进器的喷口结构，但不画尾焰（引擎辉光数据层已关闭，尾迹由 trail 管线承担）；14×24 小尺寸下靠粗壮剪影与普通导弹区分
- 发射架：仅中型有架（小型无架只渲染弹体），单管（单次发射量 1），画**导轨/滑轨式发射轨**而非蜂巢弹巢；`RENDER_LOADED_MISSILES` 已启用，待发弹体会叠加显示在架体上，弹位布局需让弹体可见部分不被架体遮到只剩尖端
- 配色：暗色合金 + ARC 电容蓝通路发光（发光层取 RGB(140,199,255)，亮核 RGB(240,248,255)）；禁止 LENS 紫与引力绯红
- 形体语言：ARC 军工切面、装甲厚实、强对称；与双子星发射舱（同为 ARC 导弹件）同族但更可读出「重型单发」

## 5. 验收要点

- [ ] 弹体剪影粗壮钝头，与原版 torpedo_guided2 及普通导弹轮廓零混淆
- [x] 小型无发射架，战斗与装配界面只渲染弹体本体
- [ ] 中型发射架读得出导轨式单发重管，而非火箭巢
- [ ] 中型待发弹体叠加显示时与架体弹位协调
- [ ] 弹体蓝色与拖尾、弹头光斑、命中辉星同屏不偏色
