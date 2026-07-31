# 「"穷距"相位轨道炮」实现规格 v1（待评审）

> 依据：`docs/design/weapons/impl/00-共享基建.md`（Buff API / 合并协议 / HUD 通道）、`docs/design/weapons/90-首批实装计划.md` v6 §5、`docs/design/weapons/blue/20-production.md`「"穷距"相位轨道炮」（定案 v1.0，2026-07-29）。
> 状态：规划文档，不改动 `src/` 与 `ss-csv/`。
> 代码核查时间：2026-07-29。全部落点已对照 `starfarer.api.jar`（/mnt/store/Games/Starsector098-linux）与现有 `src/`、`ss-csv/` 代码逐条核实。
> 武器 id：`astd_qiongjue_phase_railgun`（90-计划提案，本规格按此落地；收口前再确认一次）。

---

## 0. 与 90-计划 §5.2 的差异说明（Buff API 落位）

90-计划 §5.2 的类表是在 Buff API 定稿前写的，按 00-共享基建核实结论修正如下：

| 90-计划条目 | 本规格落位 | 原因 |
|---|---|---|
| `QiongjuePhaseRailgunState`（挂 `weapon.customData`） | `QiongjueCalcStacks : StackableBuff`，挂 **Weapon 级复合键** `ship.customData["astd_buff:weapon:astd_qiongjue_stacks:<slotId>"]` | `WeaponAPI` 无 customData（jar 已核实），武器级状态统一走基建复合键 |
| `QiongjuePhaseRailgunWeaponEffect`（`.wpn` everyFrameEffect） | 衰减/数值刷新/HUD 全部并入 `QiongjueCalcStacks.advance()`，由基建 `BuffTickPlugin` 心跳驱动 | 避免每帧双通道；`.wpn` 的 everyFrameEffect 改挂现成的 `CombatVfxBootstrapEveryFrameEffect` 作 VFX 安全网（aod7/spc3 同款） |
| `QiongjuePhaseRailgunDifficulty` | 保留（object + ScalingEntry 常量，对标 `ASTDVirtualParticleLatticeWebHullMod` 的 `DEFENSIVE_CAP` 模式） | — |

---

## 1. 数据面

### 1.1 ss-csv catalog 条目（`Catalog_WeaponData_ARC.kt`）

新增 `object Wpn_astd_qiongjue_phase_railgun : WeaponDataEntry(), SsProjProjectileOutputs`，追加在**文件末尾**（收口人按 number 升序归位）。逐列取值：

| 字段（Kotlin 属性） | 值 | 依据 |
|---|---|---|
| `id` | `"astd_qiongjue_phase_railgun"` | 90-计划提案 |
| `name` | `weaponName(id)` | 走 i18n |
| `tier` | `2`（提案） | aod7/spc3 signature 为 3；量产大主炮提案 2，收口人对齐量产件口径 |
| `baseValue` | `25000`（提案） | 低于 signature 件（50000），收口人统一 |
| `range` | `1200` | 定案固定值 |
| `damagePerSecond` | `300` | 600 / 2s |
| `damagePerShot` | `600` | 定案 |
| `chargedown` | `2.0` | 定案 2s 间隔（非 beam 必须走 chargedown/burst，避免 tooltip 除 0） |
| `burstSize` | `1`；`burstDelay` | `0.0` |
| `turnRate` | `8`（提案，目检微调） | 定案「非常慢」 |
| `turnRateStr` | `"非常慢"` | tooltip 展示 |
| `accuracyStr` | `"完美"` | 定案完美精度 |
| `autofireAccBonus` | `1` | 完美精度（对齐原版高斯炮口径） |
| `minSpread` / `maxSpread` / `spreadPerShot` / `spreadDecayPerSec` | 默认 `0` 不覆写 | 完美精度 |
| `type` | `"KINETIC"` | 定案动能 |
| `energyPerShot` | `900` | 定案（辐伤比 1.5） |
| `energyPerSecond` | `450` | 900 / 2s |
| `projSpeed` | `1200`（2026-07-29 审批裁定，弃 1500 提案） | 与高斯炮同速 |
| `ops` | `27` | 定案 |
| `ammo` / `ammoPerSec` / `reloadSize` | 默认 `0` 不覆写（无限弹药） | 定案无弹匣 |
| `aiHints` | 默认空（不覆写） | 非 PD |
| `tags` | `"astd_production"` | 量产 |
| `groupTag` | `"astd"`；`tech` | `"弧光阵列"` |
| `primaryRoleStr` | `SsI18n.t("weapon.$id.primaryRoleStr")` | gcp/spc3 同款 |
| `customPrimary` | `SsI18n.t("weapon.$id.tooltip.customPrimary")` | gcp 同款；机制文案含 v2 数值，**3 个 `{%s}` 占位**与 HL 三段一一对应 |
| `customPrimaryHL` | `SsI18n.t("weapon.$id.tooltip.customPrimaryHL")` | 高亮数值与"难度系数"（2026-07-29 字段分工铁律） |
| `number` | **`9214`** | 00-共享基建 §3 预分配段（穷距 9214） |

`projSpec`：**不用** `ProjectileProjSpec.standard()`（其 length=24/width=8 不符合弹体隐藏四件套），显式构造，照 `Wpn_astd_aod7` 样板：

```kotlin
override val projSpec: ProjectileProjSpec = ProjectileProjSpec(
    id = "astd_qiongjue_phase_railgun_shot",
    spawnType = ProjectileSpawnType.BALLISTIC,
    onFireEffect = "cn.kasuminova.astd.combat.effect.generic.ProjectileSpecOnFireDispatcher",
    onHitEffect = "cn.kasuminova.astd.combat.effect.arc.qiongjue.QiongjuePhaseRailgunOnHitEffect",
    collisionClass = "PROJECTILE_FF",
    collisionClassByFighter = "PROJECTILE_FIGHTER",
    length = 2.0, width = 2.0,          // 隐藏四件套之一
    fadeTime = 0.2,                     // 超射程滑行窗口，代码拖尾跟随淡出（aod7 注释同理）
    fringeColor = Rgba(200, 225, 255, 0),
    coreColor = Rgba(255, 255, 255, 0),
    textureScrollSpeed = 0.0,
    pixelsPerTexel = 1.0,
    bulletSprite = "graphics/textures/BUtil_NONE.png",
)
```

### 1.2 `contents/data/weapons/astd_qiongjue_phase_railgun.wpn`（手写 JSON 骨架）

```json
{
    "id": "astd_qiongjue_phase_railgun",
    "specClass": "projectile",
    "type": "BALLISTIC",
    "size": "LARGE",
    "displayArcRadius": 700,
    "turretSprite": "graphics/weapons/astd_qiongjue_base.png",
    "turretGunSprite": "graphics/weapons/astd_qiongjue_gun.png",
    "hardpointSprite": "graphics/weapons/astd_qiongjue_base.png",
    "hardpointGunSprite": "graphics/weapons/astd_qiongjue_gun.png",
    "visualRecoil": 6.0,
    "turretOffsets": [28, 0],
    "turretAngleOffsets": [0],
    "hardpointOffsets": [28, 0],
    "hardpointAngleOffsets": [0],
    "barrelMode": "LINKED",
    "animationType": "MUZZLE_FLASH",
    "projectileSpecId": "astd_qiongjue_phase_railgun_shot",
    "everyFrameEffect": "cn.kasuminova.astd.combat.effect.generic.CombatVfxBootstrapEveryFrameEffect",
    "fireSoundTwo": "gauss_fire"
}
```

- 插件挂载点：`everyFrameEffect` = 现成 VFX 安全网（机制逻辑不在这里，见 §0）；`onHitEffect` / `onFireEffect` 在 `.proj`（由 ss-csv 生成）。
- **美术资产待补**：`graphics/weapons/astd_qiongjue_base.png` / `astd_qiongjue_gun.png`（命名对齐 `astd_aod7_base.png` 现有惯例）。机制分支阶段允许先填 `graphics/fx/empty.png` 跑烟测，但**本武器在贴图到位前不算完工**（列入验收目检项）。
- `fireSoundTwo = gauss_fire`：提案（1200 射程动能主炮定位对齐高斯听觉），耳检可换。
- `turretOffsets`/`visualRecoil` 数值为提案，随贴图到位后按实尺寸校正。

### 1.3 i18n 键清单（`ss-csv/src/main/resources/i18n/zh-cn.properties`，集中追加文件末尾）

| 键 | 值 | 来源 |
|---|---|---|
| `weapon.astd_qiongjue_phase_railgun.name` | `“穷距”相位轨道炮` | 定案名（弯引号，对齐七星命名口径；「」为原版字体无效符号，禁用） |
| `weapon.astd_qiongjue_phase_railgun.tooltip.customPrimary` | `连续命中同一目标会逐步提高本武器的伤害与射速（每层 +{%s}，至多 {%s} 层）；切换目标会损失大部分加成，长时间未命中也会逐渐衰减。效果受到{%s}影响。` | 设计案 tip 原文 + v2 数值插入（2026-07-29 字段分工铁律，审批通过） |
| `weapon.astd_qiongjue_phase_railgun.tooltip.customPrimaryHL` | `6.25% | 10 | 难度系数` | 字段分工铁律：高亮数值与"难度系数" |
| `weapon.astd_qiongjue_phase_railgun.primaryRoleStr` | `压制` | 2026-07-29 审批修正（弃「远程压制,持续打击」提案） |
| `desc.astd_qiongjue_phase_railgun.text1` | `弧光科研部的长程动能主炮。使用独特的相位弹头来干扰敌方护盾稳定性，持续施加动能压力，并在击中后持续校准武器自身的相位谐波。` | 设计案文案原文（2026-07-29 用户换稿） |
| `desc.astd_qiongjue_phase_railgun.notes` | **不添加** | 设计案未提供 notes；可选字段留空，禁止自创（字段分工铁律） |

- `desc.*.text2~text5`：不登记（见 §1.1）。
- HUD 状态条与浮字文案走 `I18n[I18n.Categories.MOD, ...]`（`LensMarkStatusBar.kt` 同款），键 `ui.qiongjue.status.calc`（`持续演算`）、`ui.qiongjue.status.stacks`（`层数 {stacks}/10 · 伤害 +{dmg}% · 射速 +{rof}%`）、`ui.qiongjue.float.transfer`（`演算转移`）、`ui.qiongjue.float.full`（`演算完成`），登记在 MOD 类字符串表（非本 properties 文件，按 localization-guidelines）。

`Catalog_Descriptions.kt` 追加（WEAPON 分组尾部、`Desc_astd_psi_omega` 之后）：

```kotlin
object Desc_astd_qiongjue_phase_railgun : LocalizedDescription("astd_qiongjue_phase_railgun", "WEAPON")
```

### 1.4 `contents/data/campaign/special_items.csv` 条目（文件末尾追加，order 段位 9203）

**order 列口径（2026-07-29 实机发现）**：原版 CSV 解析强制数字，留空启动即 JSONException；各组按合并协议预分配段位直填。

```
武器蓝图：“穷距”相位轨道炮,astd_qiongjue_railgun_bp,"single_bp, astd",弧光阵列,,2000,1000,1,,graphics/icons/cargo/blueprint_hightech.png,ui_chip_pickup,ui_weapon_bp_drop,com.fs.starfarer.api.campaign.impl.items.WeaponBlueprintItemPlugin,astd_qiongjue_phase_railgun,使重工业设施能够制造出该蓝图所描述的武器。,9203
```

- `plugin params` = 武器 id（`WeaponBlueprintItemPlugin` 从 stack special data 读 params 取 weapon，jar 已核实类存在且持 `WeaponSpecAPI weapon` 字段）。
- `base price = 2000` 对齐原版 `weapon_bp` 行；量产件统一定价由收口人对齐。
- P2 阶段由 `AsteriaTestCampaignBootstrap` dev 仓储自动投放（90-计划 §14）。

---

## 2. 代码面

包：`cn.kasuminova.astd.combat.effect.arc.qiongjue`（6 个类一组子包化，避免 `arc/` 直下被十组武器塞爆；与 90-计划 §5.2 的 `arc.` 直下类名差异见 §0，`.proj`/`.wpn` 挂载字符串以本规格为准）。

### 2.1 类清单

| 类名 | 接口/实现 | 职责 | 挂载点 | 文件路径 |
|---|---|---|---|---|
| `QiongjuePhaseRailgunOnHitEffect` | `OnHitEffectPlugin` 实现（jar 签名已核实：`onHit(projectile, target, point, shieldHit, damageResult, engine)`） | 命中结算：目标类型过滤 → 同/异目标叠层/折算 → 刷新命中时间 → 触发浮字/命中锥面特效 | `.proj` 的 `onHitEffect` | `src/main/kotlin/cn/kasuminova/astd/combat/effect/arc/qiongjue/QiongjuePhaseRailgunOnHitEffect.kt` |
| `QiongjueCalcStacks` | `StackableBuff : Buff` 实现（基建接口，decayMode = `WINDOWED`） | 单武器实例叠层状态（层数/当前目标/最后命中时间/衰减累加器）；`advance` 内做 3s 窗口衰减 + 射速 spike（`setRemainingCooldownTo` 跳沿扣减，§2.4 收口结论）+ 玩家 HUD；伤害乘区不在此类落地（见下行） | 基建 `BuffHost` Weapon 级复合键，由 `BuffTickPlugin` 心跳 | 同包 `QiongjueCalcStacks.kt` |
| `QiongjueDamageDealtModifier` | `DamageDealtModifier` 实现（combat.listeners） | **伤害乘区逐命中落地通道**（2026-07-29 实机修订）：烟测实证同舰同 spec 武器共享 `weapon.damage.modifier` 底层 MutableStat（双穷距互乘 1.625²），故每次命中回调按 `projectile.weapon` 解析槽位 Buff 层数，向该发弹体独立的 `DamageAPI` 写入乘区，天然逐武器隔离；每舰一个实例（`ensure` 幂等），无 Buff/层数 0/非穷距弹体时返回 null 零开销放行 | `ShipAPI.addListener`（OnHit 首次命中时 `ensure`） | 同包 `QiongjueDamageDealtModifier.kt` |
| `QiongjueStackMath` | 纯函数 object（不依赖 Starsector API） | 叠层/折算/衰减/倍率/难度取值的纯逻辑核心，供单测完整驱动 | 被上述两类调用 | 同包 `QiongjueStackMath.kt` |
| `QiongjuePhaseRailgunDifficulty` | object | 三条 `ScalingEntry` 常量（每层加成/切换保留/衰减速率） | 被 `QiongjueStackMath` 调用 | 同包 `QiongjuePhaseRailgunDifficulty.kt` |
| `ShipAPI.qiongjueCalcStacks()` 扩展 | 顶层扩展函数 | 本包内强类型入口（`getBuffByWeapon(...) as? QiongjueCalcStacks`），不沉淀公共 API（00-共享基建 §1.3 口径） | 被 OnHit/烟测脚本调用 | 同包 `QiongjueCalcStacks.kt` 内 |

难度三锚点（`ScalingEntry`，LINEAR 即可，无超线性收益）：

```kotlin
val PER_STACK_BONUS = ScalingEntry(0.05f, 0.0625f, 0.10f)   // 每层伤害/射速加成
val SWITCH_RETAIN   = ScalingEntry(0.25f, 0.3125f, 0.50f)   // 切换目标保留比例
val DECAY_RATE      = ScalingEntry(2f, 1.75f, 1f)           // 衰减速率（层/s；v1>v5 属反向语义，LINEAR 插值天然支持）
const val MAX_STACKS = 10
const val DECAY_WINDOW_SECONDS = 3f
```

常量字段（不缩放）：层数上限 10、衰减窗口 3s、射程 1200、面板 600。

### 2.2 核心逻辑伪代码

**OnHit（`QiongjuePhaseRailgunOnHitEffect.onHit`）**——结算顺序：

```kotlin
1. target !is ShipAPI 或 target.isFighter → return（战机/导弹不叠层、不触发异目标折算）
2. ship = projectile.source；weapon = projectile.weapon
   ship == null → return；weapon == null → log WARN（无法定位武器级 buff，属异常路径）+ return
3. buff = ship.getOrCreateBuffByWeapon(BUFF_ID, weapon) { QiongjueCalcStacks(ship, weapon) }
   （BUFF_ID = "astd_qiongjue_stacks"，astd_ 前缀满足基建 id 约束；复合键由基建内部生成）
4. hitTime = engine.getTotalElapsedTime(false)
5. val targetShip = target as ShipAPI
   when {
       // 旧目标失效（hulk/不在场）视为"演算已完成"，不折算
       !buff.isTargetAlive() -> { buff.target = targetShip; buff.addStacks(+1) }
       buff.target === targetShip -> buff.addStacks(+1)              // 同目标
       else -> {                                                      // 异目标：先折算后 +1
           val lost = buff.applySwitchRetain(retainPct)               // stacks = floor(stacks * retainPct)
           buff.target = targetShip
           buff.addStacks(+1)                                         // 规格裁定：折算后本次命中仍计 1 层
           if (ship == engine.playerShip) 浮字「演算转移」(point)
       }
   }
6. buff.lastHitTime = hitTime
7. 命中小号锥面特效（§3.2）+ 满层边沿触发浮字「演算完成」（仅 stacks 首次到 10 时，玩家船）
```

**状态机（`QiongjueCalcStacks.advance(amount)`，BuffTickPlugin 每帧驱动；暂停由基建跳过）**：

```kotlin
1. now = engine.getTotalElapsedTime(false)
2. 难度取值：perStack/retainPct/decayRate = QiongjueStackMath.resolve(entry, ship.owner) 每帧重取
   （玩家 owner==0 恒 v2；AI 走 DifficultyTuningImpl.value(entry)——LunaLib 热变更即时生效，成本可忽略）
3. 衰减（WINDOWED 语义由本类自行实现）：
   if (now - lastHitTime > DECAY_WINDOW_SECONDS) {
       pendingDecay += decayRate * amount
       while (pendingDecay >= 1f && stacks > 0) { addStacks(-1); pendingDecay -= 1f }
   } else pendingDecay = 0f
4. 数值落地（2026-07-29 实机修订，取代初版双 stat 乘区伪码）：
   // 伤害乘区：不写 weapon.damage.modifier——烟测实证同舰同 spec 武器共享底层
   // MutableStat（双穷距互乘污染），改由 QiongjueDamageDealtModifier 逐命中写入
   // 该发弹体独立 DamageAPI，本 Buff 层数为其唯一数据源
   // 射速：spike 采用冷却上跳沿一次性扣减（§2.4 收口结论）：
   val mult = QiongjueStackMath.mult(stacks, perStack)
   if (weapon.cooldownRemaining - lastCooldown > REFIRE_EDGE_EPS && stacks > 0)
       weapon.setRemainingCooldownTo(weapon.cooldownRemaining / mult)
   lastCooldown = weapon.cooldownRemaining
   // REFIRE_EDGE_EPS = 0.5f：新周期起点冷却读数上跳超过该值才写一次，
   // 规避 01 判例「每帧 setRemainingCooldownTo 反复重置开火周期」
5. 玩家 HUD（仅 ship == engine.playerShip && stacks > 0）：
   engine.maintainStatusForPlayerShip("astd_qiongjue_status", 图标, "穷距",
       "层数 x/10 · 伤害 +a% · 射速 +b%", negative = false)
```

**生命周期钩子**：

- `isHostValid()`：`ship` 在场 && `!ship.isHulk` && `ship.allWeapons.any { it.slot.id == slotId && it.spec?.weaponId == WEAPON_ID }`（换装/拆卸 → false → 心跳回收；`WeaponSlotAPI.getId()` 与 `ShipAPI.getAllWeapons()` jar 已核实）。
- `onRemove()`：**无持久 stat 写入，无需清理**（伤害乘区走逐命中 DamageAPI 通道随伤害事件消亡；射速 spike 的冷却读数为瞬态，不写任何 modifier）。

**难度取值调用点（玩家固定 v2 口径）**——`QiongjueStackMath.resolve(entry, owner)` 纯函数：

```kotlin
fun resolve(entry: ScalingEntry, owner: Int): Float =
    if (owner == 0) entry.v2 else DifficultyTuningImpl.value(entry)
```

（对齐 `ASTDVirtualParticleLatticeWebHullMod.defensiveCap` 现有模式；`ShipAPI.getOwner()` 继承自 `CombatEntityAPI`，jar 已核实。）

### 2.3 玩家可见反馈（对照实现注意事项 2）

| 机制 | 通道 | 内容 |
|---|---|---|
| 叠层层数/增伤/增速 | 左侧状态栏 `maintainStatusForPlayerShip`（每帧刷新，样板 `LensMarkStatusBar.kt`） | 「持续演算」层数 x/10 · 伤害 +a% · 射速 +b%；negative=false；图标 = `graphics/weapons/astd_qiongjue_base.png`（美术待补前用 `graphics/hullmods/astd_lens_array_core.png` 之外的 ARC 侧现有图标，实装时挑定） |
| 异目标折算 | 自定义浮字 `engine.addFloatingText(point, "演算转移", ...)` | 仅命中来源为玩家船时显示（避免满屏 AI 浮字），白色 |
| 满层达成 | 自定义浮字「演算完成」 | 边沿触发（9→10 瞬间一次，不重复） |
| 叠层生效本体 | 伤害数字自然变大 + 射速自然加快（原版伤害浮字自带） | 不额外加浮字，避免噪音 |
| 命中反馈 | 小号锥面冲击特效（纯视觉，无结算） | 基建 `ConeImpactVfx` 小号参数（§3.2） |
| 弹体 | texTrail 管线 | §3 |

### 2.4 0 值与边界处理（对照实现注意事项 3）

| 边界 | 行为 |
|---|---|
| `stacks = 0` | 两个乘区走 `unmodifyMult`（不留 `modifyMult(id, 1.0)` 残留条目污染 stat 显示） |
| 折算 floor 归零 | `stacks=1, retain=25%` → `floor(0.25)=0`，本次命中后 = 1；明确合法，无 WARN |
| `retainPct = 0`（极端自定义 k_s） | `floor(stacks * 0) = 0`，无除零；合法 |
| `decayRate = 0`（极端自定义 k_s） | `pendingDecay` 恒不累积到 1，层数不衰减；**每个 buff 实例 WARN 一次**（难度配置异常必须可见），继续运行 |
| 目标失效（hulk/移除） | 命中判定时检查 `isTargetAlive()`（`target != null && !target.isHulk && engine.isEntityInPlay(target)`）；失效视为「无旧目标」→ 不折算直接 +1（规格裁定：打死目标后转火属正常行为，不吃切换惩罚；层数从最后命中时间起自然衰减）。此裁定列入验收确认项 |
| `projectile.weapon == null` | WARN + return，不静默叠到错误键上 |
| 同舰双穷距 | 复合键含 slotId 天然隔离；伤害乘区走逐命中通道按 `projectile.weapon` 解析槽位（2026-07-29 实机修订：初版 stat 乘区在双穷距场景共享底层 MutableStat 互乘污染，已废弃） |
| 伤害/射速为乘区正算（`1 + stacks*x` 直接乘） | 不涉及从终值反推修正量，无除零点 |
| 衰减累加器 | `pendingDecay` 上限 clamp 到 `stacks + 1f`（长时间挂机恢复后不会一次性狂扣，帧率抖动下扣层速率不失真） |

**射速修正方案已 spike 收口（2026-07-29，91 §4.3 C2 消缺）**：Starsector 无 per-weapon RoF API。初版候选 `ship.mutableStats.ballisticRoFMult` 全舰乘区会污染同舰其他实弹武器，**已弃用**；实装采用 **`weapon.setRemainingCooldownTo(cooldown / mult)` 开火周期起点一次性扣减**——跳沿判定（`cooldownRemaining - lastCooldown > 0.5f`）保证每周期只写一次，规避 01 判例的每帧重置问题；烟测遥测（`astd_qiongjue_spike_applied` 计数）+ 目检确认冷却压缩生效且不抖动。

### 2.5 测试面

单元测试（`src/test/kotlin/cn/kasuminova/astd/combat/effect/arc/qiongjue/`，全部驱动 `QiongjueStackMath` 纯函数，禁纯源码 contain）：

| # | 用例名 | 断言点 |
|---|---|---|
| 1 | 同目标连续命中叠至上限 | `hit(cur, same=true)` 连调 12 次 → 1,2,…,10,10,10（clamp） |
| 2 | 异目标折算三档 | stacks=10 切换：v1 → `floor(10*0.25)+1=3`；v2 → `floor(3.125)+1=4`；v5 → `floor(5)+1=6` |
| 3 | 折算归零边界 | stacks=1，v1 retain=25% → `floor(0.25)+1=1` |
| 4 | 衰减窗口边界 | `lastHit` 起恰 3.0s 不衰减；3s+ε 后按速率扣（v2 1.75/s：0.5714s 后 -1 层；v1 2/s：0.5s；v5 1/s：1s） |
| 5 | 衰减清零即止 | stacks=2 持续 advance → 扣到 0 后不再扣，`pendingDecay` 被 clamp 不溢出 |
| 6 | 难度取值玩家固定 v2 | `resolve(entry, owner=0)=v2`；owner=1 走 `DifficultyTuningImpl.value`（用 `testOverride` 固定 k_s 断言三档） |
| 7 | 倍率正算 | `mult(stacks, perStack)`：stacks=0 → 1.0；stacks=10 v2 → 1.625；v5 → 2.0 |
| 8 | 目标失效不折算 | `resolveHit(oldTarget=hulk, …)` → 视为无旧目标，stacks+1 不走 retain |
| 9 | decayRate=0 防线 | `advance` 不衰减且触发恰好一次 WARN（捕获 logger 断言） |

烟测检查点（`deployMod` + `launchSmokeTestGame`，到达终态即退出）：

1. 装配界面：1200 射程、27 OP、tooltip 名称/tip/角色文案正确、非常慢转向手感。
2. 连续命中同一目标：HUD 层数递增、伤害浮字增大、射速加快可感知（2s → 满层约 1.23s）。
3. 切换目标：浮字「演算转移」+ 层数折算下降。
4. 停火 3s 后：层数按速率流失，HUD 同步。
5. 打死目标后转火新目标：不折算（裁定项，目检确认）。
6. 同舰双穷距：两件独立叠层（复合键隔离）。
7. 敌版 AI 装配：三档难度下敌舰叠层速度差异可观测（devMode）。
8. devMode FPS：BuffTickPlugin + 本 buff 每帧开销无异常。

---

## 3. 特效面

### 3.1 `ProjectileVfxSpecs.kt` 登记（builders map 末尾追加）

```kotlin
"astd_qiongjue_phase_railgun_shot" to {
    simpleProjectileVfx(
        "astd_qiongjue_phase_railgun_shot",
        // 冷白主色内联字面量（合并协议：新调色板只允许收口人沉淀，分支内不新增 white() 函数）
        VfxPalette(ASTDColor(0.92f, 0.95f, 1f, 1f), TEX_SMOOTH, TEX_ZAPPY),
        width = 12f,
        length = 300f,
    )
},
```

- 形态对齐 spc3 lambda 内联先例；5 高层旋钮足够表达「高亮白色细长射弹 + 长距离明亮拖尾」（`simpleProjectileVfx` 已含 texTrail 主带 + bloom 网格弹头恒在 + fade 三件套，无需独立构建函数）。
- width=12/length=300：大型主炮体量（对照 spc3 中型 6/135、电荷针刺规划 9/165；length=300 表达「长距离明亮拖尾」，目检微调）。
- ribbon=false：单带细长光矛观感，不上电弧副带（克制，不抢贯星大光柱）。
- 主色：美术主色口径「穷距用白色弹体与明亮拖尾」（90-计划全局约定），ASTDColor(0.92, 0.95, 1.0) 为微冷白。

### 3.2 命中特效

命中反馈（2026-07-29 审批裁定：弃 `addHitParticle` 白闪，改用小号锥状冲击特效）：

- 命中瞬间沿射弹矢量触发**基建锥面 VFX 小号版**（`ConeImpactVfx`，第 0 波 PR#2 产物，与正电子/贯星同族）：纯视觉调用，锥角/锥长按"约贯星 25% 规模"起始（起始提案 halfAngle≈12°、range≈90su、冷白主色），无伤害结算——穷距没有锥状冲击机制，此处仅借用锥面视觉表达相位弹头的命中质构。
- 参数目检微调；依赖第 0 波基建先合并（本组在第 1 波，时序天然满足，编排册依赖表无需新增条目——视觉复用非代码阻塞项）。

---

## 4. 并行实装注意

### 4.1 触碰的共享文件清单（按 00-共享基建 §3 合并协议）

| 共享文件 | 本分支动作 | 键名空间/位置约定 |
|---|---|---|
| `ss-csv/.../weapondata/arc/Catalog_WeaponData_ARC.kt` | 追加 `Wpn_astd_qiongjue_phase_railgun` | **number=9214**（预分配段）；object 追加文件末尾，收口人按 number 升序归位 |
| `ss-csv/.../strings/Catalog_Descriptions.kt` | 追加 `Desc_astd_qiongjue_phase_railgun` | WEAPON 分组尾部（`Desc_astd_psi_omega` 之后） |
| `ss-csv/.../i18n/zh-cn.properties` | 追加 §1.3 五个键 | `weapon.astd_qiongjue_phase_railgun.*` / `desc.astd_qiongjue_phase_railgun.*`，集中插文件末尾 |
| `contents/data/campaign/special_items.csv` | 追加蓝图行（§1.4） | 文件末尾；order = **9203** |
| `src/.../renderer/projectile/driver/ProjectileVfxSpecs.kt` | builders map 末尾追加条目（§3.1） | 内联 `VfxPalette` 字面量，**不新增调色板函数**（white() 等只允许收口人加） |
| `contents/data/weapons/astd_qiongjue_phase_railgun.wpn` | 新文件 | 无冲突 |
| `src/.../combat/effect/arc/qiongjue/*.kt` + 测试 | 新文件 | 无冲突（子包本组独占） |

禁止改动：Buff API 签名（`api/buff/*`）、`CombatVfxBootstrapEveryFrameEffect`、其他武器的键/行/条目。

### 4.2 对共享基建的依赖项

| 依赖 | 状态 | 阻塞关系 |
|---|---|---|
| Buff API（`api/buff/*` + `impl/buff/*`：Buff/StackableBuff/WINDOWED/BuffHost/BuffAccess/BuffTickPlugin） | 基建 PR #1，**必须先合 main** | 硬阻塞：本武器叠层承载全部依赖 |
| `DifficultyTuning` / `ScalingEntry` | 已落地 main（`DifficultyTuningImpl`） | 就绪 |
| `ProjectileVfxSpecs` / `ProjectileSpecOnFireDispatcher` / `CombatVfxBootstrapEveryFrameEffect` | 已落地 main | 就绪 |
| `ConeImpactHandler` | 正电子组落地 | **不依赖** |
| HUD 通道（`maintainStatusForPlayerShip` / `addFloatingText`） | jar + 现有样板 | 就绪 |

### 4.3 实现顺序内位置

90-计划 §12 第 **5** 位（湮灭涡旋之后、正电子之前）；实际开工以 Buff API 合并进 main 为前提。分支内实现顺序建议：

1. `QiongjueStackMath` + 单测（纯逻辑先行，TDD）。
2. `QiongjueCalcStacks` + `OnHitEffect` 胶水层。
3. 数据面（catalog/.wpn/i18n/special_items）→ `generateSsCsv` → 烟测层数逻辑。
4. 射速 spike（§2.4，已收口为 `setRemainingCooldownTo` 跳沿扣减）+ 伤害乘区目检。
5. VFX 登记 + HUD/浮字 + 目检收口。

---

## 5. 验收要点（主代理逐项核对）

**数据面**

- [ ] `Catalog_WeaponData_ARC.kt`：`Wpn_astd_qiongjue_phase_railgun` 列值与 §1.1 一致（重点：number=9214、ops=27、range=1200、chargedown=2.0、type=KINETIC、customPrimaryHL 已覆写）
- [ ] `.proj` 生成物：隐藏四件套齐全（length/width=2、双色 alpha=0、BUtil_NONE、fadeTime=0.2）、`onHitEffect`/`onFireEffect` 类名字符串与 §1.1 一致
- [ ] `.wpn`：插件挂载点齐全（everyFrameEffect=CombatVfxBootstrap、projectileSpecId 正确）；美术贴图到位（empty.png 状态视为未完工）
- [ ] zh-cn.properties 五键齐全且值与设计案原文一致（name 弯引号、tip/文案原文）
- [ ] `Catalog_Descriptions.kt` 条目在 WEAPON 分组尾部
- [ ] `special_items.csv` 蓝图行 params=`astd_qiongjue_phase_railgun`、order = 9203

**代码面**

- [ ] 类清单一一对应 §2.1，包路径 `combat/effect/arc/qiongjue/`
- [ ] 叠层状态走 Buff API Weapon 级复合键，**未**出现 `weapon.customData` 用法
- [ ] 战机/导弹命中不叠层不折算；同/异/失效目标三分支语义与 §2.2 一致
- [ ] 玩家固定 v2：`resolve(entry, owner)` 唯一入口；无散落取值
- [ ] 射速采用 `setRemainingCooldownTo` 跳沿扣减（§2.4 收口结论）；初版 RoF 全舰乘区已废弃，无残留写入
- [ ] modifierId 带 slotId 后缀；stacks=0 时 unmodifyMult
- [ ] 无空 catch、无反射、无 XxxManager/Service 命名；decayRate=0 有一次性 WARN
- [ ] HUD/浮字通道按 §2.3 全部接入（机制可视化铁律，缺一项视为未完工）

**特效面**

- [ ] builders 条目追加在 map 末尾、内联调色板字面量（未新增 white() 函数）
- [ ] 弹体观感：白色细长 + 长亮拖尾；命中小号锥面特效克制不抢戏

**测试面**

- [ ] §2.5 九条单测全绿，全部驱动 `QiongjueStackMath` 真实逻辑（无 contain 测试）
- [ ] 烟测八个检查点过录屏/截图；automation 到终态即退出（无干等超时）

**目检**

- [ ] 满层 v2 DPS 约 790（975 伤 / 约 1.23s 间隔）可感知
- [ ] 切换目标浮字 + 层数下降；停火 3s 衰减开始；HUD 层数/百分比与日志一致
- [ ] 同舰双穷距独立叠层；敌版 AI 正常使用（无「追不上目标不开火」僵持，90-计划风险项）
- [ ] 目标死亡转火不折算（规格裁定项，用户确认）
