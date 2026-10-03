# 13 源生冰晶 MIRV 发射器 / 发射舱 美术需求

> 源生冰晶 MIRV 发射器 `astd_ice_shard_mirv`（小型导弹）/ 发射舱 `astd_ice_shard_mirv_pod`（中型导弹，单次 2 发）
> 实装规格：`docs/design/weapons/purple/30-superlative.md` §源生冰晶；线路：LENS 紫线**稀有规格**（赏金掉落，P6 前 no_drop）——但主题为「深寒」，色族独立为冰蓝，不套 LENS 紫

## 1. 定位与读感目标

紫菀科研部以源生冰晶为装药的集束导弹平台：母弹接近目标后自行解体，向目标泼洒 15 枚冰晶；刺入舰体的冰晶持续释放深寒，侵蚀命中点周围结构。读感核心是「运载一簇活冰的低温集束舱」——母弹是容器，冰晶才是武器。

- 关键词：集束运载舱、霜白壳体、冰晶簇、深寒侵蚀、碎冰泼洒

## 2. 现状

- 小/中发射架已交付独立贴图（`astd_ice_shard_mirv_base.png` 28×26 / `astd_ice_shard_mirv_pod_base.png` 34×27，底部透明边已裁除），配 `WeaponGlowLayer` 常驻微光层（`_glow_ambient` / `_hp_glow_ambient`，冰蓝 RGB(140,217,255) 族）
- 母弹已用自绘 `astd_ice_shard_missile.png`（15×27，底部透明边已裁除），同图承担挂点装填渲染与飞行本体渲染；配冰蓝挂载光效层 `astd_ice_shard_missile_glow.png`（`LoadedMissileGlowLayer` 加法叠加，透明度跟随装填 brightness）
- 子冰晶射弹已有 v1 贴图 `contents/graphics/fx/astd_ice_shard.png`（菱形冰晶），走 spriteBody 渲染路径（BoxUtil SpriteEntity），**尖端朝右**——与原版弹体渲染的朝上约定相差 90°，精修时必须保持朝右
- 弹体 VFX 已实装冰蓝族（色族独立为冰蓝，不套 LENS 紫）：母弹冰蓝白拖尾 + 弹头光斑；分裂冰蓝闪光与星云（音效沿用原版飓风 MIRV）；子冰晶短拖尾；附着后每 1s 在命中点渲染淡蓝星云。全部代码管线，不需要特效贴图

## 3. 资产清单

已交付：

| 资产 | 文件命名 | 画布 | 说明 |
|---|---|---|---|
| 小型发射架底图 | `astd_ice_shard_mirv_base.png` | 28×26 | 炮塔/挂点共用，兼作图标 |
| 小型发射架常驻微光 | `astd_ice_shard_mirv_glow_ambient.png` / `astd_ice_shard_mirv_hp_glow_ambient.png` | 28×26 | WeaponGlowLayer AMBIENT 档（冰蓝） |
| 中型发射舱底图 | `astd_ice_shard_mirv_pod_base.png` | 34×27 | 炮塔/挂点共用，兼作图标；双联弹位（burst 2） |
| 中型发射舱常驻微光 | `astd_ice_shard_mirv_pod_glow_ambient.png` / `astd_ice_shard_mirv_pod_hp_glow_ambient.png` | 34×27 | WeaponGlowLayer AMBIENT 档（冰蓝） |
| 母弹弹体 | `astd_ice_shard_missile.png` | 15×27 | 两槽位共用母弹，弹头朝上；同图承担挂点装填渲染 |
| 母弹挂载光效层 | `astd_ice_shard_missile_glow.png` | 15×27 | LoadedMissileGlowLayer（冰蓝，加法混合） |

待补（可选精修）：

| 资产 | 文件命名 | 画布基准 | 说明 |
|---|---|---|---|
| 子冰晶精修（可选） | `contents/graphics/fx/astd_ice_shard.png` | 256×256 超采样 | **尖端朝右**（spriteBody 约定），保持菱形轮廓不变 |

发射架与母弹路径 `contents/graphics/weapons/`；发射架炮口与母弹弹头朝上，子冰晶朝右（渲染路径不同，勿混淆）。

## 4. 视觉设计需求

- 母弹：**运载舱而非战斗部**——分段式外壳、冰蓝缝隙透光、壳体冷白/浅灰带霜感，读得出「里面装着一簇冰晶」；不画尾焰（尾迹由 trail 管线承担）
- 发射架：低温主题仪器挂舱——霜白覆层、冷气喷口、冰蓝点阵灯；LENS 仪器语汇（开放式结构、节点细节）但禁止紫色发光，发光层用冰蓝 RGB(170,225,255)
- 中型发射舱画双联装弹位（单次 2 发）；`RENDER_LOADED_MISSILES` 已启用，待发母弹叠加显示时弹位需协调
- 子冰晶精修（可选）：增强棱角折射层次与内部裂纹纹理；左右对称的菱形轮廓不变（15 枚成群飞行的可读性依赖轮廓一致性），且钉在舰体上的附着姿态下也要读得成立

## 5. 验收要点

- [ ] 母弹读得出「集束运载舱」而非普通导弹，与原版 missile_MIRV 轮廓零混淆
- [ ] 冰蓝配色与拖尾、分裂闪光、附着星云同屏不偏色；全武器无紫色发光残留
- [ ] 中型发射舱双联弹位可读
- [ ] 子冰晶精修（如交付）尖端朝右、菱形轮廓不变，附着姿态可读
