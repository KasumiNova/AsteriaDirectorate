# 「辉星 MRM 发射器 / 辉星 MRM 发射舱」实现规格 v1（待评审）

> 依据：`docs/design/weapons/impl/00-共享基建.md`（合并协议 / HUD 通道 / 特效检查表）、`docs/design/weapons/90-首批实装计划.md` v6 §8、`docs/design/weapons/purple/30-superlative.md`「辉星 MRM 发射器 / 辉星 MRM 发射舱」（定案 v1.0，2026-07-28）。
> 状态：规划文档，不改动 `src/` 与 `ss-csv/`。
> 代码核查时间：2026-07-29。全部落点已对照 `starfarer.api.jar`（/mnt/store/Games/Starsector098-linux）与现有 `src/`、`ss-csv/` 代码逐条核实。
> 武器 id：`astd_stellar_mrm_launcher`（小型）/ `astd_stellar_mrm_pod`（中型），90-计划 ID 表已定。
> **本武器无叠层机制，不依赖 Buff API**；不使用 `ConeImpactHandler`（爆炸为球形 50su，非锥面）。

---

## 0. 与 90-计划 §8.2 的差异说明

| 90-计划条目 | 本规格落位 | 原因 |
|---|---|---|
| `StellarMrmMissileAI` 挂载「弹头 `.proj` 的 `onFireEffect` 通过 `missile.setMissileAI(...)`」 | 确认可行，但 `.proj` 的 onFireEffect 同时要做 VFX 登记：新增 `StellarMrmOnFireEffect`，内部先委托现成 `ProjectileSpecOnFireDispatcher`（实例化持有并调用），再 `setMissileAI` | 单一 onFireEffect 挂载点，VFX 与 AI 两件事一次办完；组合而非复制 dispatcher 逻辑 |
| `StellarMrmDamageHandler`「对战机全部武器 EMP：`engine.spawnEmpArc` 或 `applyDamage`」 | `spawnEmpArc` 的 `targetTo` 参数类型是 `CombatEntityAPI`，而 `WeaponAPI` **不是** `CombatEntityAPI`（jar 已核实）——电弧无法锚到单个武器实体。落位：**EMP 伤害**走一次 `engine.applyDamage(fighter, point, 0, ENERGY, empTotal, ...)`；**逐武器视觉**走 `spawnEmpArcVisual(point, fighter, weapon.location, fighter, ...)`（该重载端点为 `Vector2f`，可锚到每个武器槽位，jar 已核实）。`ShipAPI.getAllWeapons()` 与 `WeaponAPI.getLocation()` jar 已核实存在，90-计划风险 #3 的 fallback 路径不需要 | 消除 90-计划待验证项「对战机全部武器 EMP 的 API 路径」 |
| `StellarMrmVfx` | 弹体走 `ProjectileVfxSpecs` builders（复用现有 `violet()` 调色板 + `ribbon=true` 双带）；十字爆炸依赖七星组件（§3.3，含 07 未合并时的分支内应急方案） | 对齐 P2 观感翻译后的 texTrail 管线 |
| 类全挂 `combat.effect.lens` 直下 | 子包 `combat.effect.lens.stellar`（对齐 05 穷距 `arc.qiongjue` 子包先例）；接口落 `api/combat/StellarMrm.kt`（对齐 04 湮灭涡旋接口落 `api/combat/` 先例） | 避免 lens 包被十组武器塞爆 |

---

## 1. 数据面

### 1.1 ss-csv catalog 条目（`Catalog_WeaponData_LENS.kt`，LENS 线）

新增两个 `object : WeaponDataEntry(), SsProjMissileOutputs`，追加在**文件末尾**（收口人按 number 升序归位）。参照样板：`Wpn_astd_virtual_particle_mote_launcher`（ARC catalog 唯一的 SsProjMissileOutputs 先例）+ GCP 族的 LENS 列值惯例。

**`Wpn_astd_stellar_mrm_launcher`** 逐列取值：

| 字段（Kotlin 属性） | 值 | 依据 |
|---|---|---|
| `id` | `"astd_stellar_mrm_launcher"` | 90-计划 ID 表 |
| `name` | `weaponName(id)` | 走 i18n |
| `tier` | `3`（提案） | 稀有掉落对齐 GCP-12 / amsrm（tier 3）；收口人对齐稀有件口径 |
| `baseValue` | `15000`（提案） | 小型稀有导弹（amsrm 40000 为超规格原型，本件为逆向降级版）；收口人统一 |
| `range` | `2500` | 定案 |
| `damagePerSecond` | `0`（不覆写） | 导弹武器 DPS 列留空（对照 amsrm csv 行 damage/second 空） |
| `damagePerShot` | `100` | 定案直击 100 能量 |
| `emp` / `impact` | `0`（不覆写） | EMP 机制走脚本，不进面板 |
| `turnRate` | `30`（提案） | 武器挂架转速，常规值 |
| `ops` | `4` | 定案 |
| `ammo` / `ammoPerSec` / `reloadSize` | `8` / `0.2` / `1` | 定案（5s/+1） |
| `chargedown` | `1.0` | 定案发射冷却 1s |
| `burstSize` / `burstDelay` | `1` / `0.0` | 小型单次发射量 1 |
| `type` | `"ENERGY"` | 定案直击能量 |
| `energyPerShot` | `60` | 定案发射辐能 60 |
| `energyPerSecond` | `60` | 60 / 1s |
| `projSpeed` | `750` | 定案「反物质 SRM 75% 航速」：amsrm csv projSpeed=1000 × 0.75 |
| `launchSpeed` | `200`（提案） | 对齐 amsrm launch speed 200 |
| `flightTime` | `4.0`（提案，目检微调） | 2500 射程 / 750 航速 ≈ 3.3s 直线 + 追踪冗余 |
| `projHitpoints` | `200` | 定案弹体结构值 200 |
| `trackingStr` | `"优秀"` | 定案追踪优秀（amsrm csv 同文案） |
| `speedStr` | `"快"`（提案） | amsrm 为「极快」，75% 航速降一档 |
| `aiHints` / `hints` | 不覆写 | 不挂 PD hint——本武器反战机而非反导弹，PD hint 会诱导 AI 拿去拦导弹（与设计「从不主动拦截导弹」冲突）；AI 开火时机目检后再评估 |
| `tags` | `"no_drop, no_drop_salvage"` | P6 前口径（90-计划 §14）；P6 后改稀有赏金掉落，另立任务 |
| `groupTag` | `"astd"`；`tech` | `"透镜矩阵"` | LENS 族惯例 |
| `primaryRoleStr` | `SsI18n.t("weapon.$id.primaryRoleStr")` | §1.3 |
| `customPrimary` | `SsI18n.t("weapon.$id.tooltip.customPrimary")` | §1.3（机制文案 + v2 数值） |
| `customPrimaryHL` | `SsI18n.t("weapon.$id.tooltip.customPrimaryHL")` | §1.3（高亮数值与"难度系数"；缺 key 会落键名进 CSV——04 已踩点确认） |
| `number` | **`9217`** | 00-共享基建 §3 预分配段（辉星 9217/9218） |

**`Wpn_astd_stellar_mrm_pod`** 差异列（其余与发射器完全相同）：

| 字段 | 值 | 依据 |
|---|---|---|
| `id` | `"astd_stellar_mrm_pod"` | 90-计划 ID 表 |
| `baseValue` | `30000`（提案） | 中型位 |
| `ops` | `10` | 定案 |
| `ammo` / `ammoPerSec` / `reloadSize` | `20` / `0.5` / `2` | 定案（4s/+2） |
| `burstSize` | `2` | 定案单次发射量 2 |
| `energyPerSecond` | `120` | 60 × 2 发 / 1s |
| `number` | **`9218`** | 预分配段 |

**`projSpec`（两 entry 各一份，仅 id 不同，其余值完全一致）**：`MissileProjSpec`（`SsProjMissileOutputs`；字段表见 `ss-csv/outputs/proj/ProjMissileSpec.kt`，已核实含 `onFireEffect`/`onHitEffect` 可空字段）。两件武器不共享同一 proj id——`SsProjMissileOutputs` 每个 entry 生成一个 `.proj` 文件，同 id 会在生成链路撞名，故拆两个：

```kotlin
override val projSpec: MissileProjSpec = MissileProjSpec(
    id = "astd_stellar_mrm_launcher_shot",   // pod 侧为 astd_stellar_mrm_pod_shot
    missileType = "MISSILE",
    onFireEffect = "cn.kasuminova.astd.combat.effect.lens.stellar.StellarMrmOnFireEffect",
    onHitEffect = "cn.kasuminova.astd.combat.effect.lens.stellar.StellarMrmOnHitEffect",
    sprite = "graphics/textures/BUtil_NONE.png",   // 原版弹体隐藏（mote 先例）；视觉由 ProjectileVfxSpecs 接管
    size = Vec2i(20, 26),                          // amsrm(13,17) × 1.5，sprite 隐藏下仅作记录
    center = Vec2(10, 14),
    collisionRadius = 12,                          // 定案 1.5×：amsrm 8 × 1.5
    collisionClass = "MISSILE_NO_FF",
    explosionColor = Rgba(170, 110, 255, 160),     // 纯视觉：LENS 紫
    explosionRadius = 75,                          // 纯视觉：机制半径 50su 附近的紫闪底
    armingTime = 0.0,
    flameoutTime = 0.5,
    noEngineGlowTime = 999.0,                      // 隐藏原版引擎光晕（mote 先例）
    fadeTime = 0.25,
    engineSpec = MissileEngineSpec(
        turnAcc = 2000, turnRate = 500,            // amsrm 同款高转向（定案「优秀追踪」）
        acc = 2000, dec = 2000,
    ),
    engineSlots = emptyList(),                     // 无原生尾焰槽位（mote 先例），拖尾由 texTrail 双带承担
)
```

- 注意：`MissileProjSpec` 无 `length/width/fringeColor/coreColor/bulletSprite` 字段（那是 `ProjectileProjSpec` 的隐藏四件套）；导弹隐藏路径 = `sprite=BUtil_NONE` + `noEngineGlowTime=999` + `engineSlots=emptyList`，mote 已验证。
- 弹体视觉同步放大 1.5×（90-计划 §8.2 说明）由 §3.1 的 width/length 旋钮表达，与 collisionRadius=12 对应。

### 1.2 `.wpn` JSON 骨架（两个手写文件）

`contents/data/weapons/astd_stellar_mrm_launcher.wpn`：

```json
{
    "id": "astd_stellar_mrm_launcher",
    "specClass": "projectile",
    "type": "MISSILE",
    "size": "SMALL",
    "turretSprite": "graphics/fx/empty.png",
    "hardpointSprite": "graphics/fx/empty.png",
    "hardpointOffsets": [10, 0],
    "turretOffsets": [8, 0],
    "hardpointAngleOffsets": [0],
    "turretAngleOffsets": [0],
    "barrelMode": "LINKED",
    "animationType": "MUZZLE_FLASH",
    "projectileSpecId": "astd_stellar_mrm_launcher_shot",
    "everyFrameEffect": "cn.kasuminova.astd.combat.effect.generic.CombatVfxBootstrapEveryFrameEffect",
    "fireSoundTwo": "amsrm_fire"
}
```

`contents/data/weapons/astd_stellar_mrm_pod.wpn`：`id`/`size: "MEDIUM"`/`projectileSpecId: "astd_stellar_mrm_pod_shot"` 三处不同，其余相同。

- 插件挂载点：`everyFrameEffect` = 现成 VFX 安全网（aod7 同款，onFire 未触发时兜底装 dispatcher）；`onFireEffect`/`onHitEffect` 在 `.proj`（ss-csv 生成）。
- **美术资产待补**：发射架贴图 `graphics/weapons/astd_stellar_mrm_base.png`（命名对齐 aod7 惯例）。机制分支阶段允许先填 `graphics/fx/empty.png` 跑烟测（aod7 现役同款状态），**贴图到位前不算完工**（列入验收目检项）。挂架贴图到位后按实尺寸校正 offsets。
- `fireSoundTwo = amsrm_fire`：2026-07-29 审批裁定（弃 harpoon_fire 提案，用反物质 SRM 发射音效；音效 id 已在原版 `amsrm.wpn` 核实）。
- 不挂 `renderHints: [RENDER_LOADED_MISSILES]`（harpoon 先例）——弹体 sprite 已隐藏，挂载渲染无意义。

### 1.3 i18n 键清单（`ss-csv/src/main/resources/i18n/zh-cn.properties`，集中追加文件末尾）

键值（tip = 定稿原文 + v2 数值插入，审批通过；name/desc 取自设计案定稿文案逐字）：

```properties
weapon.astd_stellar_mrm_launcher.name=辉星 MRM 发射器
weapon.astd_stellar_mrm_launcher.tooltip.customPrimary=优先追猎战机的高速导弹。命中战机机体时对其全部武器释放 {%s} 的 EMP；无论撞上什么，撞击处都会绽放十字辉星，造成 {%s} 的范围能量伤害。效果受到{%s}影响。
weapon.astd_stellar_mrm_launcher.tooltip.customPrimaryHL=400% | 100% | 难度系数
weapon.astd_stellar_mrm_launcher.primaryRoleStr=反战机,区域防空
desc.astd_stellar_mrm_launcher.text1=上古遗产「反物质 SRM」的逆向工程产物。原物的装药原理至今无法解析，工程组转而复现它的飞行性能与猎杀逻辑，以常规能量装药补上最后一块拼图——紫色辉星掠过之处，战机联队的武器系统将集体沉默。
desc.astd_stellar_mrm_launcher.notes=逆向工程记录："拆解报告第 41 版，结论与第 1 版相同：不可分析。我们甚至无法确定装药舱里是不是反物质。"工程组最终放弃了理解原物，转而复现它的飞行——三十二架无人靶机在三秒内接连熄火，像被同一根手指依次捻灭的蜡烛。评审委员会沉默了很久，然后在量产许可上签了字。

weapon.astd_stellar_mrm_pod.name=辉星 MRM 发射舱
weapon.astd_stellar_mrm_pod.tooltip.customPrimary=优先追猎战机的高速导弹。命中战机机体时对其全部武器释放 {%s} 的 EMP；无论撞上什么，撞击处都会绽放十字辉星，造成 {%s} 的范围能量伤害。效果受到{%s}影响。
weapon.astd_stellar_mrm_pod.tooltip.customPrimaryHL=400% | 100% | 难度系数
weapon.astd_stellar_mrm_pod.primaryRoleStr=反战机,区域防空
desc.astd_stellar_mrm_pod.text1=上古遗产「反物质 SRM」的逆向工程产物。原物的装药原理至今无法解析，工程组转而复现它的飞行性能与猎杀逻辑，以常规能量装药补上最后一块拼图——紫色辉星掠过之处，战机联队的武器系统将集体沉默。
```

- `customPrimary` 为设计案定稿原文 + v2 数值插入（EMP 400%、辉星爆炸 100%；2026-07-29 字段分工铁律，审批通过：显示值统一以 v2 为准，取代设计案"tip 不展示数值"旧口径）；`customPrimaryHL` 高亮数值与"难度系数"（缺 key 会把键名写进 CSV）。
- `primaryRoleStr = 反战机,区域防空`：gcp 先例逗号分隔双角色，评审保留。
- `desc.text2~text5` 不提供（`LocalizedDescription` 读不到键 fallback 空串）。
- 逆向工程记录段落入 `notes` 列（04 同口径）；「深灰斜体」样式由 UI 层既有规则处理，文本内不夹带样式标记。
- pod 的 `notes` **不登记**：`Catalog_Descriptions` 用 `notesId` 共享（GCP 族先例，见下）。
- 撞线者死为隐性机制，**不进任何文案**（设计案裁定）。
- 逻辑代码零硬编码文本；本武器无常驻 HUD/浮字文案（§2.3），无需运行时 I18n 键。

`Catalog_Descriptions.kt` 追加（**WEAPON 分组尾部**，`Desc_astd_psi_omega` 之后；收口人按 id 字典序重排）：

```kotlin
object Desc_astd_stellar_mrm_launcher : LocalizedDescription("astd_stellar_mrm_launcher", "WEAPON")
object Desc_astd_stellar_mrm_pod : LocalizedDescription("astd_stellar_mrm_pod", "WEAPON", notesId = "astd_stellar_mrm_launcher")
```

### 1.4 `contents/data/campaign/special_items.csv` 条目

**不新增**。辉星两件为稀有掉落：P6 前 `no_drop, no_drop_salvage`，仅经 dev 仓储/控制台测试（90-计划 §14，与 04 湮灭涡旋同口径）；P6 后接 T3~T4 支线赏金掉落，另立任务。本文件零改动，验收时核对无 diff。

---

## 2. 代码面

接口落 `src/main/kotlin/cn/kasuminova/astd/api/combat/StellarMrm.kt`（新文件，与 00-共享基建 `ConeImpact.kt` 同包位）；实现落新包 `cn.kasuminova.astd.combat.effect.lens.stellar`（04 首开 `lens` 包后的第一个子包）。

### 2.1 类清单

| 类名 | 接口/实现 | 职责 | 挂载点 | 文件路径 |
|---|---|---|---|---|
| `StellarMrmTargeting` | 接口（fun interface） | 导弹目标选择策略：输入候选目标清单与弹体位置，输出选中目标（战机优先、排除导弹）。注释含：类简介「辉星导弹的猎杀目标筛选策略」、动机「把目标权重规则从 AI 转向循环中剥离供单测」、成员作用 | 被 `StellarMrmMissileAI` 持有 | `src/main/kotlin/cn/kasuminova/astd/api/combat/StellarMrm.kt` |
| `StellarMrmStrike` | 接口 | 命中结算总入口：撞线者死判定、战机增伤 + 全部武器 EMP、十字辉星爆炸 AOE。注释含：类简介「辉星命中三大机制的一次性结算」、动机「OnHitEffect 保持薄入口，结算可注入测试桩引擎驱动」、每个方法作用与单位 | 被 `StellarMrmOnHitEffect` 持有 | 同文件 `StellarMrm.kt` |
| `StellarMrmTargetingImpl` | 实现（object，无状态） | 战机优先/最近舰兜底/排除友军-hulk-导弹的筛选 | — | `src/main/kotlin/cn/kasuminova/astd/combat/effect/lens/stellar/StellarMrmTargetingImpl.kt` |
| `StellarMrmStrikeImpl` | 实现（object，无状态） | §2.2 结算顺序的执行体；数值全部经 `StellarMrmStrikeMath` 与 `StellarMrmDifficulty` | — | 同包 `StellarMrmStrikeImpl.kt` |
| `StellarMrmOnFireEffect` | 实现（`OnFireEffectPlugin`） | 持有 `ProjectileSpecOnFireDispatcher` 实例先委托 VFX 登记，再对 `projectile as? MissileAPI` 调 `setMissileAI(StellarMrmMissileAI(missile))` | `.proj` 的 `onFireEffect` | 同包 `StellarMrmOnFireEffect.kt` |
| `StellarMrmMissileAI` | 实现（`MissileAIPlugin`，jar 签名已核实仅 `advance(float)`） | 目标有效性校验 → 定期重选（0.25s 节流）→ 领先瞄准 → `giveCommand(TURN_LEFT/RIGHT + ACCELERATE)` 转向加速 | 由 `StellarMrmOnFireEffect` 经 `missile.setMissileAI(...)` 挂载 | 同包 `StellarMrmMissileAI.kt` |
| `StellarMrmOnHitEffect` | 实现（`OnHitEffectPlugin`，jar 签名已核实） | 薄入口：基础校验（面板值 sanitize、source/owner 提取）→ 难度取值 → 调 `StellarMrmStrike` | `.proj` 的 `onHitEffect` | 同包 `StellarMrmOnHitEffect.kt` |
| `StellarMrmStrikeMath` | 纯函数 object（不依赖 Starsector API） | 战机增伤/武器 EMP/爆炸伤害/撞线阈值/领先瞄准点的纯计算，供单测完整驱动 | 被 Strike/AI 调用 | 同包 `StellarMrmStrikeMath.kt` |
| `StellarMrmDifficulty` | object（纯声明） | 四条 `ScalingEntry` 常量 + 玩家固定 v2 取值入口 `resolve(entry, owner)`（`owner == 0 → entry.v2`，否则 `DifficultyTuningImpl.value(entry)`；对齐 04/05 就地实现口径） | 被 Strike/AI 调用 | 同包 `StellarMrmDifficulty.kt` |

难度四锚点（`ScalingEntry`，均 LINEAR）：

```kotlin
val FIGHTER_BONUS  = ScalingEntry(0.5f, 1.0f, 2.5f)   // 对战机额外伤害（面板倍率）
val WEAPON_EMP     = ScalingEntry(2f, 4f, 10f)        // 战机全部武器 EMP（面板倍率）
val EXPLOSION_MULT = ScalingEntry(0.5f, 1.0f, 2.5f)   // 辉星爆炸倍率（面板倍率）
val LINE_CROSS_H   = ScalingEntry(1.5f, 3.0f, 7.5f)   // 撞线阈值 h（自身 HP 倍数）
const val EXPLOSION_RADIUS = 50f                       // 爆炸范围 50su，固定不缩放
const val RETARGET_INTERVAL = 0.25f                    // AI 重选节流（提案）
```

常量（不缩放）：爆炸范围 50su、射程 2500、面板 100 能量、弹体 HP 200、碰撞半径 1.5×。

### 2.2 核心逻辑伪代码

**发射（`StellarMrmOnFireEffect.onFire`）**：

```
1. vfxDispatcher.onFire(projectile, weapon, engine)   // 委托现成 dispatcher，VFX 登记（去重由 dispatcher 内部保证）
2. val missile = projectile as? MissileAPI
   ?: { log WARN（onFire 拿到非导弹实体，属配置错误）; return }
3. missile.setMissileAI(StellarMrmMissileAI(missile, StellarMrmTargetingImpl))
```

**导弹 AI（`StellarMrmMissileAI.advance(amount)`）**——形态对齐 `ASTDPursuitVirtualParticleAI` 先例（`ASTDVirtualParticleLatticeWebHullMod.kt` L434，已核实 `giveCommand`/`TURN_LEFT/RIGHT/ACCELERATE` 可用）：

```
1. engine = Global.getCombatEngine() ?: return；engine.isPaused → return
   missile.isFading || missile.isExpired → return
2. 目标维护（reselectTimer -= amount，≤0 时重置为 RETARGET_INTERVAL 并执行）：
   - 当前目标仍有效（inPlay && isAlive && !isHulk && 敌方）→ 保留
   - 否则 candidates = engine.getShips()（jar+先例已核实该列表含战机，先例 L425 在同表上按 isFighter 过滤）
     target = targeting.select(candidates, missile.location, missile.owner, ACQUIRE_RANGE=2500f)
     select 语义：最近敌方战机优先；无战机时最近敌方舰船（含无人机？——裁定：isDrone 舰船同普通舰船纳入兜底）；永不返回 MissileAPI
3. 转向（有目标时）：
   lead = StrikeMath.leadPoint(target.location, target.velocity, dist, missile.maxSpeed)
   angleTo = VectorUtils.getAngle(missile.location, lead)
   diff = MathUtils.getShortestRotation(missile.facing, angleTo)
   |diff| > 1° → giveCommand(TURN_LEFT/RIGHT)
   giveCommand(ACCELERATE)   // 每帧
```

**命中结算（`StellarMrmOnHitEffect.onHit` → `StellarMrmStrikeImpl.strike`）**——结算顺序（撞线者死 → 猎机本能 → 辉星爆炸，爆炸恒执行）：

```
0. panel = projectile.damageAmount
   !panel.isFinite() || panel <= 0 → log WARN（面板值异常）+ return（直击已由引擎原生结算，附加机制全部跳过，不静默）
   source = projectile.source（可为 null，归功允许 null）；owner = projectile.owner
   fBonus / wEmp / expMult / h = StellarMrmDifficulty.resolve(entry, owner)  // 玩家 owner==0 恒 v2
1. 【撞线者死】target is MissileAPI && target.owner != owner：
   threshold = StrikeMath.lineCrossThreshold(projectile.maxHitpoints, h)   // 200×h%
   if (target.hitpoints < threshold) {
       engine.removeEntity(target)          // 必定摧毁
       // 自身同归于尽：碰撞已触发，导弹进入引擎死亡流程；辉星爆炸在步骤 3 照常执行。
       // 目检项：若发现辉星穿透敌导弹存活继续飞，补 projectile.hitpoints = 0f（列入验收）。
   }
2. 【猎机本能】target is ShipAPI && target.isFighter && !shieldHit && !target.isHulk：
   a. 增伤：engine.applyDamage(target, point, panel × fBonus, DamageType.ENERGY, 0f,
        false, false, source, true)                         // showDamageFloaty=true，玩家可见
   b. 全部武器 EMP（一次结算）：engine.applyDamage(target, point, 0f, DamageType.ENERGY,
        panel × wEmp, false, false, source, true)
   c. 逐武器电弧视觉：for (w in target.allWeapons) { if (w.isDisabled) continue
        engine.spawnEmpArcVisual(point, target, w.location, target, 6f, 紫色fringe, 白色core) }
      （WeaponAPI 非 CombatEntityAPI 不能作电弧锚点实体，用 Vector2f 端点重载——§0 已核实）
3. 【辉星爆炸】任意撞击恒触发（战机/舰船/导弹/残骸/护盾命中均含）：
   victims = CombatUtils.getEntitiesWithinRange(point, EXPLOSION_RADIUS)
       .filter { it.owner != owner && !isHulkOrPhased(it) && it !== projectile && it !== target 已死体 }
   for (v in victims) engine.applyDamage(v, point, panel × expMult, DamageType.ENERGY, 0f,
       false, false, source, true)
   VFX：十字爆炸组件（§3.3，scale=0.6、LENS 紫）+ engine.spawnExplosion(point, ZERO, 紫色, 40f, 0.4f) 底闪
```

**难度取值调用点**：仅 `StellarMrmDifficulty.resolve(entry, owner)` 一个入口，命中时取值（LunaLib 热变更对后续命中即时生效，与 04「开火起点缓存」口径差异属刻意：本武器无开火态状态机，命中回调是唯一结算点）。

### 2.3 玩家可见反馈（对照实现注意事项 2）

| 机制 | 通道 | 内容 |
|---|---|---|
| 对战机额外伤害 | 原版伤害浮字（`showDamageFloaty=true`） | 数字自然变大，不额外加浮字防噪音 |
| 战机全部武器 EMP | `spawnEmpArcVisual` 逐武器一道紫色电弧（视觉锚点=武器槽位）+ 原版武器熄火表现 | 电弧为玩家可见核心反馈；EMP 伤害数字同帧浮出 |
| 辉星爆炸 AOE | 十字星爆炸 VFX（§3.3）+ `spawnExplosion` 紫闪底 + AOE 浮字 | 任意撞击恒触发，50su 范围可读 |
| 撞线者死 | 敌方导弹消失 + 同帧十字爆炸（隐性机制不进文案，视觉即爆炸本身；设计案裁定） | 无额外浮字 |
| 弹体飞行 | 紫色十字辉星本体 + 长 twin trail 双拖尾（texTrail 双带，§3.1） | LENS 紫主色 |
| 常驻 HUD / 自定义浮字 | **不需要**（无叠层/无状态数值机制，机制全部在一次命中内自解释） | 对齐 04 湮灭涡旋「无常驻机制不挂状态栏」口径 |

### 2.4 0 值与边界处理（对照实现注意事项 3）

| 边界 | 行为 |
|---|---|
| `projectile.maxHitpoints <= 0` | `lineCrossThreshold` 返回 0 → 撞线者死恒不触发；**每引擎 WARN 一次**（`engine.customData` 去重键），机制失效必须可见 |
| 撞线判定严格小于 | `target.hitpoints < threshold`：hp=600 / 阈值 600（v2）→ **不**触发；hp=599 → 触发。边界列入单测 |
| 敌导弹已 hulk/移除 | `removeEntity` 幂等；命中已死体时步骤 1 跳过（`!engine.isEntityInPlay(target)` 前置） |
| `panel <= 0` 或 NaN | WARN + return（§2.2 步骤 0）；禁止静默产出恒零附加伤害 |
| `fighter.allWeapons` 为空 | EMP 伤害照结算，电弧循环零次；合法场景不 WARN |
| 爆炸半径内无有效目标 | 仅 VFX，无伤害循环；合法 |
| AOE 目标与命中点重合（dist=0） | `applyDamage` 的 point 直接复用命中点，无几何除零点 |
| `missile.maxSpeed <= 0`（领先瞄准） | `StrikeMath.leadPoint` 退回当前位置直瞄 + 一次性 WARN（数据异常可见） |
| 难度倍率 = 0（极端自定义 k_s） | 对应附加伤害为 0，等价无机制；配置合法域内，不 WARN |
| `projectile.source == null` | 归功传 null（`applyDamage` 签名允许）；owner 取 `projectile.owner` 不受 source 缺失影响 |
| 无反算场景 | 全部数值为面板 × 倍率正算，无从终值反推修正量的除零点 |

---

## 3. 特效面

### 3.1 `ProjectileVfxSpecs.kt` 登记（builders map 末尾追加两条）

```kotlin
"astd_stellar_mrm_launcher_shot" to {
    simpleProjectileVfx("astd_stellar_mrm_launcher_shot", violet(), width = 10f, length = 420f, ribbon = true)
},
"astd_stellar_mrm_pod_shot" to {
    simpleProjectileVfx("astd_stellar_mrm_pod_shot", violet(), width = 10f, length = 420f, ribbon = true)
},
```

- 形态对齐 spc3 lambda 内联先例；5 高层旋钮足够表达「紫色十字辉星本体 + 很长 twin trail 双拖尾」：`ribbon=true` 启用双带（主带垫底 alpha 0.8 + 电弧副带，P2 观感翻译已内建 twin 观感），弹头由 `head{}` 网格恒在承担「十字辉星本体」的近球亮核，目检确认是否需加大弹头表达「十字」锐度（如需，走 `glowScale` 旋钮，不新增调色板函数）。
- `violet()`：现有调色板（ASTDColor(0.66, 0.42, 1.0, 0.9)，TEX_SMOOTH + TEX_ZAPPY），LENS 紫主色直接复用；合并协议禁止分支内新增调色板函数。
- width=10：1.5× 弹体体量（对照 spc3 中型 6、穷距大型 12）；length=420：定案「很长的双拖尾」（对齐 aod7 hero 420，2500 射程长航迹），目检微调。
- 两 spec 值完全一致属刻意（同一弹头两种发射器）；若目检要求中型体量更大，仅调 pod 行 width。

### 3.2 十字辉星爆炸（依赖七星组件）

- 90-计划 §11 裁定：十字闪光爆炸组件**七星首发，辉星按 60% 缩放复用**；§12 顺序辉星（8）在七星（7）之后。
- **硬依赖**：七星交付的十字爆炸组件（接口名以 07 规格为准，预期形态：参数化 `scale` + `color` 的一次性 RenderEntity 特效——十字星 + 星云烟雾）。本武器调用：`scale = 0.6f`，主色 LENS 紫（ASTDColor(0.66, 0.42, 1.0)）。
- **应急方案**（07 未合 main 时本分支开工）：分支内实现 `StellarMrmCrossExplosion`（object，同族十字星 + 星云烟雾，`spawn(scale, color)` 单入口）落地于 `combat/effect/lens/stellar/`，PR 描述显式标记「收口人去重：与 07 十字爆炸组件合并」；禁止静默双份共存进 main。

---

## 4. 测试面

### 4.1 单元测试清单

`src/test/kotlin/cn/kasuminova/astd/combat/effect/lens/stellar/`，全部驱动 `StellarMrmStrikeMath` 纯函数 / `StellarMrmTargetingImpl.select` / `StellarMrmDifficulty.resolve` 真实逻辑，**禁止纯源码 contain 测试**。Starsector API 类型按项目 `testutil` 惯例以测试桩实现接口（09 同口径）。

| # | 用例名 | 断言点 |
|---|---|---|
| 1 | 撞线阈值三档 | `lineCrossThreshold(200, h)`：v1 → 300；v2 → 600；v5 → 1500 |
| 2 | 撞线严格小于边界 | v2 阈值 600：`shouldCross(599) == true`；`shouldCross(600) == false`；`shouldCross(601) == false` |
| 3 | maxHitpoints=0 防线 | 阈值 0、恒不触发、恰好一次 WARN（捕获 logger 断言） |
| 4 | 战机增伤三档 | panel=100：v1 → 50；v2 → 100；v5 → 250 |
| 5 | 武器 EMP 三档 | panel=100：v1 → 200；v2 → 400；v5 → 1000 |
| 6 | 爆炸倍率与固定半径 | panel=100 v2 → 100；`EXPLOSION_RADIUS == 50f`（常量守护） |
| 7 | 目标选择：战机优先 | 近处舰船 + 远处战机同场 → 选中战机 |
| 8 | 目标选择：无战机兜底 | 仅舰船 → 最近舰船；含无人机（isDrone）→ 纳入兜底 |
| 9 | 目标选择：排除项 | 友军/hulk/不在场目标被排除；输入含 MissileAPI → 永不入选；全空 → null |
| 10 | 目标选择：射程门 | 战机在 2500 外 → 不入选（落空或兜底近舰） |
| 11 | 领先瞄准点 | `leadPoint(loc, vel, dist, maxSpeed)`：vel≠0 → loc + vel × (dist/maxSpeed)；maxSpeed=0 → 退 loc + 一次 WARN |
| 12 | 玩家固定 v2 | `resolve(entry, owner=0) == entry.v2`；owner=1 走 `DifficultyTuningImpl`（testOverride 固定 k_s 断言 v1/v2/v5 三档） |

爆炸 AOE 逐目标结算与 `spawnEmpArcVisual` 循环：以测试桩引擎注入 `StellarMrmStrikeImpl.strike` 完整驱动——断言 applyDamage 调用次数/数值/伤害类型/EMP 量（用例 13：战机命中机体 → 增伤 1 次 + EMP 1 次 + 电弧 N=存活武器数；用例 14：命中导弹低于阈值 → removeEntity 被调用 + 爆炸照常；用例 15：护盾命中战机 → 猎机本能不触发、爆炸触发）。

### 4.2 烟测检查点（`deployMod` + `launchSmokeTestGame`，到达终态即退出）

1. 装配界面：两件武器 2500 射程、4/10 OP、名称/tip/角色文案正确、稀有掉落 tags（codex 无蓝图获取途径）。
2. 发射观感：紫色辉星 + 长双拖尾；发射舱一次两发。
3. 优先追猎：场上有航母放战机 + 近距舰船 → 导弹航线先咬战机（devMode 观察 + 日志目标类型）。
4. 命中战机机体：EMP 电弧逐武器可见、战机武器熄火、增伤浮字。
5. 撞击舰船/护盾：十字辉星爆炸触发、50su AOE 浮字。
6. 导弹相撞：与低 HP 敌导弹相撞同归于尽 + 爆炸；与高 HP 导弹相撞仅爆炸不移除（v2 阈值 600 上下各找一型，如 atropos 350 vs 重型鱼雷）。
7. 被点防：1.5× 碰撞体积下被点防击落率目检（90-计划风险项，记录体感）。
8. 不主动拦导弹：敌导弹海经过不转向追咬（AI 目标日志无 MissileAPI）。
9. 敌版 AI 装配三档（迟暮/砺刃/破晓）：增伤/EMP/爆炸/撞线差异可观测（devMode 日志）。
10. devMode FPS：多发齐射下 AI + VFX 开销无异常。

---

## 5. 并行实装注意

### 5.1 触碰的共享文件清单（按 00-共享基建 §3 合并协议）

| 共享文件 | 本分支动作 | 键名空间/位置约定 |
|---|---|---|
| `ss-csv/.../weapondata/lens/Catalog_WeaponData_LENS.kt` | 追加 `Wpn_astd_stellar_mrm_launcher` / `Wpn_astd_stellar_mrm_pod` | **number=9217/9218**（预分配段）；object 追加文件末尾，收口人按 number 升序归位 |
| `ss-csv/.../strings/Catalog_Descriptions.kt` | 追加两行 Desc | WEAPON 分组尾部（`Desc_astd_psi_omega` 之后），收口人按 id 字典序重排 |
| `ss-csv/.../i18n/zh-cn.properties` | 追加 §1.3 十一个键 | `weapon.astd_stellar_mrm_*.*` / `desc.astd_stellar_mrm_*.*`，集中插文件末尾 |
| `src/.../renderer/projectile/driver/ProjectileVfxSpecs.kt` | builders map 末尾追加两条（§3.1） | 复用现有 `violet()`，**不新增调色板函数** |
| `contents/data/weapons/astd_stellar_mrm_launcher.wpn` / `astd_stellar_mrm_pod.wpn` | 新文件 | 无冲突 |
| `src/.../api/combat/StellarMrm.kt` | 新文件 | 无冲突（与 `ConeImpact.kt` 同包不同文件） |
| `src/.../combat/effect/lens/stellar/*.kt` + 测试同名包 | 新文件 | 无冲突（子包本组独占） |

**禁止改动**：Buff API 签名、`ConeImpactHandler`、`ProjectileSpecOnFireDispatcher`/`CombatVfxBootstrapEveryFrameEffect`、其他武器的键/行/条目；`special_items.csv` 零改动（§1.4）。

### 5.2 对共享基建的依赖项

| 依赖 | 状态 | 阻塞关系 |
|---|---|---|
| `ProjectileVfxSpecs` / `ProjectileSpecOnFireDispatcher` / `CombatVfxBootstrapEveryFrameEffect` | 已落地 main | 就绪 |
| `DifficultyTuning` / `ScalingEntry`（`DifficultyTuningImpl`） | 已落地 main | 就绪 |
| LazyLib `CombatUtils.getEntitiesWithinRange` | 依赖已在（GCP/湮灭涡旋路径在用） | 就绪 |
| 自定义导弹 AI 先例（`ASTDPursuitVirtualParticleAI`） | 已落地 main（形态参照，非代码依赖） | 就绪 |
| Buff API | 基建 PR #1 | **不依赖**（无叠层机制） |
| `ConeImpactHandler` | 正电子组落地 | **不依赖**（球形爆炸非锥面） |
| **七星十字爆炸组件** | 07 组交付（90-计划 §12 第 7 位） | **软阻塞**：七星先合 main 则直接复用；否则走 §3.3 应急方案并在 PR 标记收口人去重 |

### 5.3 实现顺序内位置

90-计划 §12 第 **8** 位（七星之后、贯星之前）。分支内实现顺序建议：

1. `StellarMrmStrikeMath` + `StellarMrmTargetingImpl` + 单测（纯逻辑先行，TDD）。
2. `StellarMrmDifficulty` + `StellarMrmStrikeImpl` + 桩引擎结算单测（用例 13~15）。
3. `StellarMrmMissileAI` + `StellarMrmOnFireEffect` + `StellarMrmOnHitEffect` 胶水层。
4. 数据面（catalog ×2 / .wpn ×2 / i18n / Desc）→ `generateSsCsv` → 烟测最小 AI 原型（只追战机 + 直线结算，验证 `setMissileAI` 生效与目标选择）。
5. VFX 登记（texTrail 双带）+ 十字爆炸（07 组件或应急实现）+ 目检收口。

---

## 6. 验收要点（主代理逐项核对）

**数据面**

- [ ] `Catalog_WeaponData_LENS.kt`：两 entry 列值与 §1.1 一致（重点：number=9217/9218、ops=4/10、ammo 三列 8/0.2/1 与 20/0.5/2、projSpeed=750、flightTime=4.0、projHitpoints=200、chargedown=1.0、pod burstSize=2、tags=no_drop 两件套、customPrimaryHL 已覆写数值键）
- [ ] `.proj` 生成物 ×2：导弹隐藏三件套齐全（sprite=BUtil_NONE、noEngineGlowTime=999、engineSlots 空）、collisionRadius=12、onFireEffect/onHitEffect 类名字符串与 §1.1 一致、两 proj id 不撞名
- [ ] `.wpn` ×2：插件挂载点齐全（everyFrameEffect=CombatVfxBootstrap、projectileSpecId 各自正确）；发射架贴图到位（empty.png 状态视为未完工）
- [ ] zh-cn.properties 十一键齐全（name 两件、desc 原文、notes 仅 launcher 有；tip = 定稿原文 + v2 数值插入，审批通过）
- [ ] `Catalog_Descriptions.kt` 两行在 WEAPON 分组尾部，pod 的 notesId 指向 launcher
- [ ] `special_items.csv` **无 diff**

**代码面**

- [ ] 类清单一一对应 §2.1，包路径 `combat/effect/lens/stellar/` + `api/combat/StellarMrm.kt`
- [ ] 两接口注释含类简介/动机/成员作用；实现命名无 Service/Manager/Controller/Runtime；零反射
- [ ] AI 目标选择：战机优先、舰船兜底、导弹永不入选；重选节流 0.25s；`setMissileAI` 在 onFire 内且 VFX dispatcher 先委托
- [ ] 结算顺序：撞线者死 → 猎机本能 → 爆炸恒触发；EMP 伤害一次 `applyDamage`（emp 参数）+ 逐武器 `spawnEmpArcVisual`（Vector2f 端点重载，未误用 spawnEmpArc 锚 WeaponAPI）
- [ ] 玩家固定 v2：`resolve(entry, owner)` 唯一入口；无散落取值
- [ ] 0 值防线：maxHitpoints=0 与 maxSpeed=0 各有一次性 WARN；panel 异常 WARN + return；无空 catch
- [ ] 玩家可见反馈按 §2.3 全部接入（机制可视化铁律，缺一项视为未完工）
- [ ] 撞线者死目检项结论已记录（穿透存活则补 `projectile.hitpoints = 0f` 并在 PR 写明）

**特效面**

- [ ] builders 两条追加在 map 末尾、复用 `violet()`（未新增调色板函数）
- [ ] 弹体观感：紫色辉星本体 + 很长双拖尾；撞击十字星为七星 60% 缩放紫色版
- [ ] 十字爆炸：07 组件复用或应急实现已按 §3.2 口径标记收口人去重（无双份静默共存）

**测试面**

- [ ] §4.1 用例 1~15 全绿，全部驱动真实逻辑（无 contain 测试）
- [ ] 烟测十个检查点过录屏/截图；automation 到终态即退出（无干等超时）

**目检**

- [ ] 导弹先咬战机后咬舰（日志目标类型佐证）；不追导弹
- [ ] 命中战机武器集体熄火 + 紫色电弧可读；AOE 50su 范围与设计一致
- [ ] v2 撞线阈值 600：atropos（350）必死、高于 600 的鱼雷存活（边界目检）
- [ ] 1.5× 碰撞体积被点防击落率体感记录（90-计划风险项，供平衡复核）
- [ ] 敌版三档难度差异可观测；发射舱单次两发、备弹经济符合面板
