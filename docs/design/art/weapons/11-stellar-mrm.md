# 11 辉星 MRM 发射器/发射舱 美术需求

> 辉星 MRM 发射器 `astd_stellar_mrm_launcher`（小型导弹）/ 发射舱 `astd_stellar_mrm_pod`（中型导弹）
> 实装规格：`docs/design/weapons/impl/08-stellar-mrm.md`；线路：LENS 紫线**稀有规格**（赏金掉落，P6 前 no_drop）

## 1. 定位与读感目标

优先追猎战机的高速导弹，命中绽放十字辉星爆炸并对战机全武器 EMP。「辉星」（十字光芒）是全部视觉核心：弹头是星，发射架是「装星的仪器」。

- 关键词：十字辉星、反战机、追猎、LENS 仪器

## 2. 现状

- 发射架贴图 `astd_stellar_mrm_base.png` 待补（当前 `empty.png`），装配界面无图标
- 弹头 sprite 占用版 `graphics/missiles/am_srm.png`（紫色反物质弹轮廓，与「十字辉星」语义不符），实装文档已登记专用异色弹头贴图为后续美术任务
- 锁定激光占用版红色 `targetinglaser3`；紫色锁定光束为风险登记项 R5，列可选交付
- 弹体 VFX 已实装：信号紫 twin 双拖尾 + 十字 boxFlare 光斑 + 紫色 EMP 电弧，不需要特效贴图

## 3. 资产需求清单

| 资产 | 文件命名 | 画布基准 | 说明 |
|---|---|---|---|
| 小型发射架炮塔 | `astd_stellar_mrm_base.png` | 26×26 | 兼作图标 |
| 小型发射架炮塔发光层 | `astd_stellar_mrm_glow.png` | 26×26 | 开火/工作发光层（加法混合） |
| 小型发射架挂点 | `astd_stellar_mrm_hp.png` | 26×26 纵向可拉长 | |
| 小型发射架挂点发光层 | `astd_stellar_mrm_hp_glow.png` | 26×26 纵向可拉长 | 开火/工作发光层（加法混合） |
| 中型发射舱炮塔 | `astd_stellar_mrm_pod_base.png` | 40×40 | 兼作图标 |
| 中型发射舱炮塔发光层 | `astd_stellar_mrm_pod_glow.png` | 40×40 | 开火/工作发光层（加法混合） |
| 中型发射舱挂点 | `astd_stellar_mrm_pod_hp.png` | 40×40 纵向可拉长 | |
| 中型发射舱挂点发光层 | `astd_stellar_mrm_pod_hp_glow.png` | 40×40 纵向可拉长 | 开火/工作发光层（加法混合） |
| 弹头弹体 | `contents/graphics/missiles/astd_stellar_mrm.png` | 13×17 竖构图 | 两尺寸发射架共用弹头 |
| 紫色锁定光束（可选，R5） | 待开发侧定命名 | — | 替换原版红色 targetinglaser3 |

发射架路径 `contents/graphics/weapons/`，弹头与发射架均朝上。

## 4. 视觉设计需求

- 弹头：**四芒星/十字形轮廓的紫色弹体**，13×17 小尺寸下靠轮廓而非颜色与常规导弹区分（剪影即十字）；主色信号紫 RGB(168,107,255)，星芯白亮
- 发射架：LENS 仪器语汇的挂舱——开放式挂架、可见紫色弹尖、点阵灯与扫描线细节；与 ARC 双子星的军工方舱明确分家（无装甲厚板、无棱角炮塔感）
- 中/小两型共享族语言，中型挂架弹位数更多
- 配色：暗色合金 + 信号紫发光，禁止绯红（那是 LENS 引力光束系的识别色）

## 5. 验收要点

- [ ] 弹头剪影即十字，与原版 am_srm 轮廓零混淆
- [ ] 发射架与 ARC 导弹架（双子星）并置时线路归属一眼可辨
- [ ] 弹体紫色与 twin 拖尾、十字光斑同屏不偏色
