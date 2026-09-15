# 01 电荷针刺族 美术需求

> 电荷针刺 `astd_charge_needle`（小型能量）/ 重型电荷针刺 `astd_heavy_charge_needle`（中型能量）
> 实装规格：`docs/design/weapons/impl/01-charge-needle.md`；线路：ARC 蓝线量产（tier1 蓝图）

## 1. 定位与读感目标

弹匣供弹的电荷箭弹速射武器，职能是护盾压制（电荷淤积抬耗散）与概率 EMP 泄放。原型语义是原版「针刺（Needler）」的能量化延展——玩家看到它应当读出「ARC 制式的速射实弹枪」，而不是光束炮。

- 关键词：弹匣、速射、供弹机构、电容组、散热片
- 族内区分：小型单供弹链路，中型（重型）供弹链路加倍、散热结构更厚重，一眼可辨级别差

## 2. 现状

- 两件武器四件套贴图全为 `BUtil_NONE.png`（全隐），装配界面无图标
- 弹体视觉已由 texTrail 管线承担（冷蓝白箭弹，width 6/9），**不需要弹体贴图**
- HUD 状态图标复用 `astd_arc_loop_interface.png`，不在本期范围

## 3. 资产需求清单

| 资产 | 文件命名 | 画布基准 | 说明 |
|---|---|---|---|
| 小型炮塔底图 | `astd_charge_needle_base.png` | 26×26 | 兼作图标 |
| 小型炮塔发光层 | `astd_charge_needle_glow.png` | 26×26 | 开火/工作发光层（加法混合） |
| 小型挂点底图 | `astd_charge_needle_hp.png` | 26×26 纵向可拉长 | |
| 小型挂点发光层 | `astd_charge_needle_hp_glow.png` | 26×26 纵向可拉长 | 开火/工作发光层（加法混合） |
| 中型炮塔底图 | `astd_heavy_charge_needle_base.png` | 40×40 | 兼作图标 |
| 中型炮塔发光层 | `astd_heavy_charge_needle_glow.png` | 40×40 | 开火/工作发光层（加法混合） |
| 中型挂点底图 | `astd_heavy_charge_needle_hp.png` | 40×40 纵向可拉长 | |
| 中型挂点发光层 | `astd_heavy_charge_needle_hp_glow.png` | 40×40 纵向可拉长 | 开火/工作发光层（加法混合） |

路径：`contents/graphics/weapons/`。炮口朝上，转轴居中。

## 4. 视觉设计需求

- 主体为短粗炮管 + 侧挂弹匣/电容块，供弹链路用冷蓝白发光缝标示 RGB(140,199,255)
- 炮口为针状收口（对应「针刺」箭弹语义），不要喇叭口
- 散热片外露于炮管两侧，中型版本散热片面积明显加大
- 配色：深枪灰/炭灰装甲 + 冷蓝白发光，禁用电驱黄（那是电驱加速炮的识别色）

## 5. 验收要点

- [ ] 小/中两型并排放时级别差一眼可读
- [ ] 26×26 图标下「弹匣速射枪」轮廓不糊
- [ ] 发光色与弹体 texTrail 冷蓝白同屏不偏色
