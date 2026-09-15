# 03 电驱加速炮 美术需求

> 电驱加速炮 `astd_electric_drive_accelerator`（中型实弹，动能伤害）
> 实装规格：`docs/design/weapons/impl/03-electric-drive-accelerator.md`；线路：ARC 蓝线量产（tier1 蓝图）

## 1. 定位与读感目标

散射连发动能弹雨（每触发 2×4 共 8 弹），不稳定装药随机附伤，低辐能时获得「净空加速」射程加成。原型参考 Omega「不稳定粒子投射器」，但**禁止撞型 Omega 生物质感**——要做成 ARC 军工语汇的「电磁加速排枪」。

- 关键词：电磁导轨、连发排枪、动能弹雨、不稳定装药

## 2. 现状

- 四件套贴图全为 `BUtil_NONE.png`（全隐），装配界面无图标
- 弹体视觉已由 texTrail 管线承担（电驱黄 w9 长拖尾），**不需要弹体贴图**
- 炮口坐标为 (12,±4)/(17,±4) 前后两组双管提案值，**贴图到位后校正**，交付时需标注炮口中心像素坐标

## 3. 资产需求清单

| 资产 | 文件命名 | 画布基准 | 说明 |
|---|---|---|---|
| 炮塔底图 | `astd_electric_drive_accelerator_base.png` | 42×42 | 兼作图标 |
| 炮塔发光层 | `astd_electric_drive_accelerator_glow.png` | 42×42 | 开火/工作发光层（加法混合） |
| 炮塔枪身覆层 | `astd_electric_drive_accelerator_gun.png` | 42×42 | 配合连发后坐 |
| 挂点底图 | `astd_electric_drive_accelerator_hp.png` | 30×48 纵向 | |
| 挂点发光层 | `astd_electric_drive_accelerator_hp_glow.png` | 30×48 纵向 | 开火/工作发光层（加法混合） |
| 挂点枪身覆层 | `astd_electric_drive_accelerator_hp_gun.png` | 30×48 | |

路径：`contents/graphics/weapons/`。炮口朝上，转轴居中。

## 4. 视觉设计需求

- 双管（或双导轨）前后错层排布，对应 (12,±4)/(17,±4) 的两组炮口；导轨线圈沿身管分段外露
- **识别色为电驱黄 RGB(255,209,64)**：发光缝与线圈充能段使用电驱黄，与 ARC 其他武器的冷蓝白形成该武器专属识别（与弹体黄色长拖尾一致）
- 装药舱/弹鼓结构外显，呼应「不稳定装药」语义（可做点睛的警示色小块，但主体仍是军工灰）
- 配色：深枪灰装甲 + 电驱黄发光，可少量冷蓝白次级点缀

## 5. 验收要点

- [ ] 电驱黄识别在图标尺寸下成立，且与电荷针刺族一眼可分
- [ ] 交付时附两组炮口中心坐标标注
- [ ] 轮廓与 Omega 系武器无生物质撞型
