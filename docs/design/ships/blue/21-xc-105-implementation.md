# 贯虹级（XC-105）实现说明

## 舰船数据

舰船条目位于 [Catalog_ShipData_ARC.kt](../../../../modules/internal/astd-csv/src/main/kotlin/cn/kasuminova/astd/sscsv/entries/catalog/shipdata/arc/Catalog_ShipData_ARC.kt)，船体与槽位位于 [astd_xc_105.ship](../../../../contents/data/hulls/astd_xc_105.ship)。

| 参数 | 数值 |
|---|---:|
| 舰体级别 | 巡洋舰 |
| 结构 / 装甲 | 12000 / 1400 |
| 辐能容量 / 耗散 | 12000 / 850 |
| 装配点 | 185 |
| 航速 | 80 |
| 加速度 / 减速度 | 40 / 40 |
| 转向速率 / 转向加速度 | 30 / 30 |
| 护盾 | 全向，240° |
| 护盾维持 | 420，配置比例为 `420.0 / 850.0` |
| 护盾效率 | 0.8 |
| 基准质量 | 1800 |
| 部署点 | 27 |
| 峰值时间 | 560 秒 |

武器槽以船体文件为准：3 个中型导弹槽、3 个中型混合槽、2 个小型混合槽、4 个小型能量槽，以及 1 个装饰槽。必须保留已标定的槽位坐标和射界。

引擎共有 5 个引擎簇，每簇 1 主喷口、2 辅喷口，共 15 个槽。主喷口宽度不超过 10，原长度保留；辅助喷口按主喷口尺寸的 55% 取整，在喷射方向法线两侧各偏移 4 像素，主辅使用相同的 `ASTD_ARC` 样式。

## 矢量增压推进器

实现位于 [ASTDVectorBoostThrusterHullMod.kt](../../../../modules/internal/astd-combat/src/main/kotlin/cn/kasuminova/astd/combat/hullmods/arc/ASTDVectorBoostThrusterHullMod.kt)。

- 航速与最大航速的比值夹到 0～1，线性映射为至多 100% 的质量和机动加成。
- 质量基准保存在当前战斗引擎中。贯虹突进持有质量修改权时，船插跳过质量写入；系统结束后恢复基准，再由船插继续计算。
- 零辐能加速在交火期保持启用。零硬辐能时提供满值 +50 航速，硬辐能达到 80% 时保留满值的 10%，之后维持该比例。
- 航速加成通过原有零辐能加速的乘数控制，避免重复增加原版的 +50。
- 禁止安装不稳定喷射器、辅助推进器和安全协议超驰。
- Tooltip 用命名占位符填充变量。不兼容名称从原版船插配置读取，在“限制”标题与介绍下逐项显示 `# 无法安装 船插名称`，标记和名称使用警示色；完整的硬辐能衰减说明放在表格下方。
- 图标需求见 [船插图标美术需求](../../art/50-船插图标美术需求.md)。

## 贯虹突进

配置位于 [Catalog_ShipSystems_ARC.kt](../../../../modules/internal/astd-csv/src/main/kotlin/cn/kasuminova/astd/sscsv/entries/catalog/shipsystems/arc/Catalog_ShipSystems_ARC.kt)。系统使用 `STAT_MOD`，AI 使用 `CUSTOM`。

蓄能 0.5 秒，冲刺 3 秒，结束过渡 0.5 秒，冷却 8 秒。从启动蓄能到下一次可用的完整周期为 12 秒。碰撞熄火概率为零。

| 难度效果 | v1 | v2 | v5 |
|---|---:|---:|---:|
| 最大航速与加速度加成 | 150% | 200% | 350% |
| 全伤减免 | 40% | 50% | 80% |
| 质量加成 | 100% | 200% | 500% |
| 扇面全角 | 40° | 60° | 140° |
| 冲击距离 | 400 | 450 | 600 |
| 能量伤害 | 600 | 1000 | 2200 |

冲刺期间按舰首方向保持最大航速，转向速率与转向加速度保留 20%。碰撞触发后停止锁速，伤害、质量和机动增益统一释放。一次激活最多释放一次冲击。

### 实际碰撞触发

[GressionCollisionTrigger.kt](../../../../modules/internal/astd-combat/src/main/kotlin/cn/kasuminova/astd/combat/shipsystems/GressionCollisionTrigger.kt) 使用公开伤害监听器。原版物理碰撞的伤害来源为另一碰撞实体，弹体参数为空，船体碰撞为动能伤害、护盾碰撞可为能量伤害。

必须同时满足：正在冲刺、来源或目标为敌对舰船或陨石、瞬时碰撞伤害达到 60 点、伤害点处于双方的碰撞或护盾范围。弹体、持续伤害及贯虹冲击本身的脚本伤害不参与触发。伤害回调只记录触发状态，下一次系统更新负责结算，避免在伤害回调内递归施加冲击。

冲刺正常结束时，若尚未发生冲击，也会在终点释放一次。

### 冲击与推力

[GressionImpactPush.kt](../../../../modules/internal/astd-combat/src/main/kotlin/cn/kasuminova/astd/combat/shipsystems/GressionImpactPush.kt) 按波面推进距离收集目标。必须在波面抵达时先改变目标速度，约 0.12 秒后对适用目标结算能量伤害，禁止先清除目标再尝试推动。

- 推力覆盖敌对舰船、战机、导弹、普通射弹与陨石；相位舰和无碰撞实体跳过，固定空间站及模块只承受伤害。
- 每个目标只推动一次，按对象身份去重。
- 先计算质量基准推速 `d = clamp(260 × sqrt(施力方质量 / max(目标质量, 1)), 140, 450)`，再按目标沿冲击方向的当前速度 `v` 施加速度增量 `0.4 × max(d, d - v)`。静止或向外运动目标获得 56～180 su/s 的增量，迎面目标的减速量也按 40% 缩放。
- 普通射弹的朝向同步到改变后的速度方向；导弹保留自身制导。
- 伤害沿用系统能量伤害与 EMP 参数；原点、方向、半角和范围与冲击视觉共用。延后结算保留原命中记录，避免目标受推离开范围后漏掉已命中的伤害。

### AI

[GressionChargeSystemAI.kt](../../../../modules/internal/astd-combat/src/main/kotlin/cn/kasuminova/astd/combat/shipsystems/GressionChargeSystemAI.kt) 根据机动目的地和前向敌舰选择起手时机。

赶路时要求正在向远处目的地加速、航向稳定、未减速，并留出足够冲刺距离。交战时选择前向可达敌舰，按目标短期运动预测瞄准点，以表面间距判断接敌窗口。避免追逐战机、相位目标、固定空间站和残骸。计算冲刺速度时必须对当前航速属性创建副本加入系统百分比，只把增量加回当前上限，禁止将零辐能加速再次成倍放大。

## 视觉

- 蓄能和冲刺使用双层抛物线流纹包裹，舰首独立光斑已移除。
- 残影每累计 100 单位航程且至少间隔 0.1 秒生成一次，1.1 秒内扩大至 1.85 倍并淡出。
- 尾焰从每个喷口当前强度开始，在 0.35 秒内平滑过渡到满强度。长度、宽度和辉光的目标增量为 100%，染色转为深蓝，并叠加原版接口自身的渐变。强度、扩张和染色共用矢量引擎的渐变进度。
- 结束冲击采用宽幅弧形波面和池化 `SMOOTH_DISC` 碎光。波面持续 0.78 秒。
- 暂停时保持当前画面；舰船死亡、离场及系统结束时清理对应状态。

## 装配与使用入口

| 装配 | 文件 | 电容 / 耗散 |
|---|---|---:|
| Standard | [astd_xc_105_Standard.variant](../../../../contents/data/variants/astd_xc_105_Standard.variant) | 30 / 30 |
| Ranged | [astd_xc_105_Ranged.variant](../../../../contents/data/variants/astd_xc_105_Ranged.variant) | 30 / 15 |
| Strike | [astd_xc_105_Strike.variant](../../../../contents/data/variants/astd_xc_105_Strike.variant) | 30 / 23 |

正式装配设置 `goalVariant: true`，供自动装配与赏金装配发现使用。模拟战副本放在 [sim 目录](../../../../contents/data/variants/sim/)，使用 `_Sim` 后缀并省略 `goalVariant`，通过 [sim_opponents.csv](../../../../contents/data/campaign/sim_opponents.csv) 登记。

三个正式装配同时登记到 [主菜单舰船列表](../../../../contents/data/config/title_screen_variants.csv) 和 [势力配置](../../../../contents/data/world/factions/asteria_directorate.faction) 的 `combatLarge`，权重各为 10；赏金静态巡洋舰池和军官技能表同步登记。

[赏金预设](../../../../contents/data/config/modFiles/magicBounty_data.json) 中，第三、第四、第五个前置赏金各用一艘 Standard、Strike、Ranged 替换一艘熔壁，预设总部署点分别为 243、329、421。默认关闭舰队上限补齐时，也能生成这三个装配。已完成调谐的存量赏金舰队沿用其当前编队。

装配资源必须清除 `rat_controller`、`sc_skill_controller`、`sms_mastery_handler` 和 `shipmastery_*` 存档标签，使用正式 `variantId`；载人模式、模式切换设置和 `ship_recoverable` 保留。

## 验证

运行模块编译、贯虹碰撞状态机与质量推速算法测试、残影采样测试、AI 决策测试，以及矢量增压计算测试。数据通过 `:astd-csv:generateSsCsv` 生成到 `build/generated/ss-csv/`，通过 `modProduction` 组装可加载产物。
