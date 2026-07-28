# 电荷针刺 / 重型电荷针刺 逐件实现规格 v1（待评审）

> 依据：`docs/design/weapons/impl/00-共享基建.md` v1（Buff API / 合并协议 / HUD 通道）、`docs/design/weapons/90-首批实装计划.md` v6 §1 与全局约定、设计案定稿 `blue/20-production.md`「电荷针刺 / 重型电荷针刺」v1.0。
> 状态：规划文档，不改动 `src/` 与 `ss-csv/`。
> API 核查时间：2026-07-29，对照 `starfarer.api.jar`（0.98）javap 签名与现有 `src/` 代码逐条核实（`MutableShipStatsAPI.getShieldUpkeepMult()/getFluxDissipation()`、`ShipHullSpecAPI.ShieldSpecAPI.getUpkeepCost()`、`CombatEngineAPI.spawnEmpArc(...)/maintainStatusForPlayerShip(...)`、`CombatEntityAPI.getOwner()/getCustomData()`、`MutableStat.modifyMult/unmodifyMult/getModifiedValue` 均存在）。
> 复用承诺：叠层机制走共享 Buff API（`StackableBuff : Buff`，Ship 级、CONTINUOUS 衰减），不另起状态表；结算随机走共享 `CombatRandom`；HUD 走 §4.2 通道。

---

## 1. 数据面

### 1.1 ss-csv catalog 条目（`Catalog_WeaponData_ARC.kt` 文件末尾追加两个 object）

number 段位按 00-共享基建 §3 预分配：电荷针刺 **9210**、重型电荷针刺 **9211**。两者均 `WeaponDataEntry(), SsProjProjectileOutputs`。

`Wpn_astd_charge_needle`（逐列）：

| 字段 | 值 | 备注 |
|---|---|---|
| id | `astd_charge_needle` | |
| name | `weaponName(id)` | 走 i18n 键 `weapon.astd_charge_needle.name` |
| tier / rarity | 1 / 1 | 量产件 |
| baseValue | 6000 | 对照原版轻型针刺价位档 |
| range | 700 | |
| damagePerSecond | 1000 | 20 发/s 爆发折算（设计案 §面板） |
| damagePerShot | 50 | 能量 |
| emp | 100 | 单发 EMP |
| impact | 0 | 能量武器无冲击 |
| turnRate | 30 | 对齐 aod7/spc3 |
| ops | 9 | |
| ammo / ammoPerSec / reloadSize | 30 / 2.5 / 15 | 弹匣三列原生表达 |
| type | `ENERGY` | |
| energyPerShot / energyPerSecond | 50 / 125 | 持续射速 2.5 发/s × 50 |
| chargedown / burstSize / burstDelay | 0.05 / 1 / 0.0 | 20 发/s，非 beam 走 chargedown 防 tooltip 除 0 |
| projSpeed | 1350 | 对标针刺 |
| tags | `astd_production` | |
| groupTag / tech | `astd` / `弧光阵列` | |
| primaryRoleStr | `SsI18n.t("weapon.$id.primaryRoleStr")` | |
| customPrimary | `SsI18n.t("weapon.$id.tooltip.customPrimary")` | 无 `{%s}` 占位，不设 customPrimaryHL |
| number | 9210 | |
| projSpec | 见下 | |

`projSpec`（显式 `ProjectileProjSpec` 构造，照 `Wpn_astd_aod7.projSpec` 样板）：

- id = `astd_charge_needle_shot`，spawnType = `ProjectileSpawnType.BALLISTIC`
- onFireEffect = `cn.kasuminova.astd.combat.effect.generic.ProjectileSpecOnFireDispatcher`
- onHitEffect = `cn.kasuminova.astd.combat.effect.arc.ChargeNeedleOnHitEffect`
- collisionClass = `PROJECTILE_FF`，collisionClassByFighter = `PROJECTILE_FIGHTER`
- 原版弹体视觉隐藏四件套：length = 2.0、width = 2.0、fringeColor = `Rgba(140, 200, 255, 0)`、coreColor = `Rgba(225, 242, 255, 0)`、bulletSprite = `graphics/textures/BUtil_NONE.png`、fadeTime = 0.2、textureScrollSpeed = 0.0、pixelsPerTexel = 1.0

`Wpn_astd_heavy_charge_needle` 差异列：id `astd_heavy_charge_needle`、ops 17、ammo 60 / ammoPerSec 5 / reloadSize 30、energyPerSecond 250、baseValue 14000、number 9211、projSpec.id `astd_heavy_charge_needle_shot`（其余逐列相同）。

### 1.2 `Catalog_Descriptions.kt`

WEAPON 分组尾部（`Desc_astd_psi_omega` 之后）追加：

```kotlin
object Desc_astd_charge_needle : LocalizedDescription("astd_charge_needle", "WEAPON")
object Desc_astd_heavy_charge_needle : LocalizedDescription("astd_heavy_charge_needle", "WEAPON", notesId = "astd_charge_needle")
```

重型 notes 复用小型（`notesId` 机制为现成能力，GCP 系列已这么用）。

### 1.3 `.wpn` JSON 骨架（`contents/data/weapons/`，手写两份，照 `astd_spc3.wpn` 样板）

`astd_charge_needle.wpn`：

```json
{
    "id": "astd_charge_needle",
    "specClass": "projectile",
    "type": "ENERGY",
    "size": "SMALL",
    "displayArcRadius": 700,
    "turretSprite": "graphics/textures/BUtil_NONE.png",
    "turretGunSprite": "graphics/textures/BUtil_NONE.png",
    "hardpointSprite": "graphics/textures/BUtil_NONE.png",
    "hardpointGunSprite": "graphics/textures/BUtil_NONE.png",
    "visualRecoil": 0.0,
    "turretOffsets": [0, 0],
    "turretAngleOffsets": [0],
    "hardpointOffsets": [0, 0],
    "hardpointAngleOffsets": [0],
    "barrelMode": "LINKED",
    "animationType": "MUZZLE_FLASH",
    "projectileSpecId": "astd_charge_needle_shot",
    "onFireEffect": "cn.kasuminova.astd.combat.effect.generic.ProjectileSpecOnFireDispatcher",
    "everyFrameEffect": "cn.kasuminova.astd.combat.effect.generic.CombatVfxBootstrapEveryFrameEffect",
    "fireSoundTwo": "needler_fire"
}
```

挂载点说明：

- `projectileSpecId` → 弹体数据与 VFX 路由键。
- `onFireEffect` → 弹体 VFX 管线（与 `.proj` 上的同名效果由 dispatcher 内部 customData 去重，双挂是现成惯例）。
- `everyFrameEffect = CombatVfxBootstrapEveryFrameEffect` → RenderEntity 战斗层引导（spc3/aod7 同款）。
- `fireSoundTwo = needler_fire` → 原版音效 id 已在 `/data/data/config/sounds.json` 核实存在。
- `astd_heavy_charge_needle.wpn` 仅改 `id` / `size: "MEDIUM"` / `projectileSpecId: "astd_heavy_charge_needle_shot"`。
- 炮口贴图暂不配（BUtil_NONE 全隐 + MUZZLE_FLASH），美术资源到位后只换贴图路径，不动结构。

### 1.4 i18n 键清单（`ss-csv/src/main/resources/i18n/zh-cn.properties` 文件末尾集中追加）

设计案定稿原文（tip 静态无数值，机制数值隐性缩放不上 tip）：

```properties
# Weapon 名称
weapon.astd_charge_needle.name=电荷针刺
weapon.astd_heavy_charge_needle.name=重型电荷针刺

# Weapon tooltip 自定义字段（设计案定稿原文；无 {%s} 占位，不配 customPrimaryHL）
weapon.astd_charge_needle.tooltip.customPrimary=以弹匣供弹的电荷箭弹速射武器，短时间内可倾泻密集火力。命中护盾会使电荷在目标护盾矩阵中淤积，持续抬高其维持开销；命中船体则可能将电荷泄放为电弧，打击武器与引擎。效果受到难度系数影响。
weapon.astd_heavy_charge_needle.tooltip.customPrimary=电荷针刺的中型规格，更沉稳的供弹机构带来加倍的持续火力。命中护盾会使电荷在目标护盾矩阵中淤积，持续抬高其维持开销；命中船体则可能将电荷泄放为电弧，打击武器与引擎。效果受到难度系数影响。

# Weapon 定位
weapon.astd_charge_needle.primaryRoleStr=弹幕压制,护盾消耗
weapon.astd_heavy_charge_needle.primaryRoleStr=弹幕压制,护盾消耗

# descriptions.csv（提案文案，评审时确认；百分号用全角 ％）
desc.astd_charge_needle.text1=弹匣供弹的电荷箭弹速射武器，命中护盾会使电荷在目标护盾矩阵中淤积，持续抬高其维持开销；命中船体则可能将电荷泄放为电弧，打击武器与引擎。
desc.astd_heavy_charge_needle.text1=电荷针刺的中型规格，供弹深度与持续火力加倍；淤积与泄放机制与小型完全同源。
desc.astd_charge_needle.notes=靶场上的针刺弹道像一场蓝色的雨——雨停之后，目标的护盾仍在为每一滴水付费。
```

HUD 文本不进 properties，走 `contents/data/strings/strings.json`（MOD 类，见 §2.3）。

### 1.5 `special_items.csv` 条目（`contents/data/campaign/special_items.csv` 文件末尾追加，order 列留空）

量产件 P2 走单件蓝图（90 计划 §14），plugin params 用**裸武器 id**（2026-07-29 主代理裁决：反编译 `WeaponBlueprintItemPlugin.init` 证实 params 直接传 `getWeaponSpec(data)`，`weapon:` 前缀会导致 spec 为 null；原写 `weapon:<id>` 系误引蓝图包格式，已更正）；带 `single_bp` 标签时 params 必填（`ASTDDevContentSelector.SPECIAL_ITEM_PARAM_REQUIRED_TAGS/IDS` 核实），dev 仓储会自动投放：

```csv
电荷针刺蓝图,astd_charge_needle_bp,"weapon_bp, single_bp, no_drop, no_drop_salvage",阿斯忒里亚遗构局,,8000,1,0,,graphics/icons/cargo/blueprint_weapons.png,ui_chip_pickup,ui_weapon_bp_drop,com.fs.starfarer.api.campaign.impl.items.WeaponBlueprintItemPlugin,astd_charge_needle,使重工业设施能够制造「电荷针刺」。,
重型电荷针刺蓝图,astd_heavy_charge_needle_bp,"weapon_bp, single_bp, no_drop, no_drop_salvage",阿斯忒里亚遗构局,,15000,1,0,,graphics/icons/cargo/blueprint_weapons.png,ui_chip_pickup,ui_weapon_bp_drop,com.fs.starfarer.api.campaign.impl.items.WeaponBlueprintItemPlugin,astd_heavy_charge_needle,使重工业设施能够制造「重型电荷针刺」。,
```

`no_drop, no_drop_salvage` 为 P6 前口径（P6 接入量产蓝图投放时摘除）。

---

## 2. 代码面

包：`cn.kasuminova.astd.combat.effect.arc`（ARC 线武器机制包，90 计划全局约定）。两槽位机制完全同源（设计案：中槽变体机制完全复用无差异化），**一套代码服务两个 id**，不存在重型专属类。

### 2.1 类清单

| 类名 | 接口/实现 | 职责 | 挂载点 | 文件路径 |
|---|---|---|---|---|
| `ChargeNeedleOnHitEffect` | 实现 `OnHitEffectPlugin` | 命中路由：护盾命中 → 淤积叠层 + 轻粒子；船体/装甲命中 → 概率泄放 EMP 电弧 | 两个 `.proj` 的 `onHitEffect` | `src/main/kotlin/cn/kasuminova/astd/combat/effect/arc/ChargeNeedleOnHitEffect.kt` |
| `ChargeNeedleStacks` | 实现共享 `StackableBuff`（api/buff） | 目标舰淤积层数（浮点累加器）、耗散安全闸 clamp、`shieldUpkeepMult` 幂等刷新、HUD 维护、CONTINUOUS 衰减 10 层/s | 经 `ShipAPI.buffHost()` 注册（Ship 级，id `astd_charge_needle_stacks`） | 同包 `ChargeNeedleStacks.kt` |
| `ChargeNeedleShots` | 实现共享 `Buff`（非叠层，纯标记） | 记录泄放概率结算随机的 `callIndex`（00 §4.1 口径：Weapon 级复合键，seed 派生 `source.id*31 + slot.id`） | Weapon 级 Buff，id `astd_charge_needle_shots` | 同包 `ChargeNeedleStacks.kt` 内附属类 |
| `ChargeNeedleTuning` | object（数值声明，对齐 `DifficultyTuningImpl` object 先例） | 三锚点 `ScalingEntry` 常量 + `resolve(tuning, isPlayer)` 取值 + 纯函数 `dissipationCapStacks(...)` | 被 OnHit / Stacks 调用 | 同包 `ChargeNeedleTuning.kt` |
| `ChargeNeedleVfx` | object（视觉静态入口，对齐 `ImpactStrikeFx` 惯例） | 护盾命中粒子、泄放电弧落点选取与 `spawnEmpArc` 调用 | 被 OnHit 调用 | 同包 `ChargeNeedleVfx.kt` |

便捷扩展（一行，放 `ChargeNeedleStacks.kt` 底部，不沉淀进公共 API——00 §1.3 约定）：

```kotlin
fun ShipAPI.chargeNeedleStacks(): ChargeNeedleStacks? = getBuff(ChargeNeedleStacks.BUFF_ID) as? ChargeNeedleStacks
```

### 2.2 难度锚点与取值调用点

`ChargeNeedleTuning`（三锚点即设计案数值，ScalingMap 全 LINEAR）：

| 常量 | v1 | v2 | v5 |
|---|---|---|---|
| `PER_STACK`（每层护盾维持加成） | 0.01 | 0.02 | 0.05 |
| `DISCHARGE_CHANCE`（船体泄放概率） | 0.25 | 0.40 | 1.00 |
| `DISCHARGE_EMP_MULT`（泄放 EMP 倍率） | 1.00 | 1.75 | 4.00 |

固定不缩放：层数上限 200、衰减 10 层/s、安全闸 50%、泄放基准 EMP 100。

调用点：`ChargeNeedleOnHitEffect.onHit` 内每次命中调用一次

```kotlin
val values = ChargeNeedleTuning.resolve(DifficultyTuningImpl, isPlayer = projectile.source?.owner == 0)
```

`resolve`：玩家（owner == 0，`CombatEntityAPI.getOwner()` 已核实）固定 `entry.v2`；否则 `tuning.value(entry)`。共享 `valueFor` 助手尚未沉淀（90 §11 列为候选），本组就地实现三行，不在公共包新增。

### 2.3 核心逻辑伪代码

**命中路由（ChargeNeedleOnHitEffect.onHit）**：

```
if (engine.isPaused) return
ship = target as? ShipAPI ?: return                 // 只淤积舰船；战机/陨石豁免
if (ship.isHulk || ship.isPhased) return
point ?: projectile.location ?: return              // 对齐 HighFluxShieldPressure 样板
values = Tuning.resolve(...)

if (shieldHit) {
    baseUpkeep = ship.hullSpec.shieldSpec?.upkeepCost ?: 0f
    if (baseUpkeep <= 0f) return                    // 无盾/零耗盾目标不淤积（日志 DEBUG 一次/船）
    host = ship.buffHost()                          // 共享 BuffAccess 扩展，惰性注册 BuffTickPlugin
    buff = host.find(BUFF_ID) as? ChargeNeedleStacks
        ?: ChargeNeedleStacks(ship, engine, host).also { host.register(it) }
    buff.perStack = values.perStack                 // 多攻击者时后命中者口径覆盖（已文档化）
    buff.addStacks(1)                               // 内部走安全闸 clamp（见下）
    if (projectile.source == engine.playerShip) buff.showOnPlayerHud = true
    Vfx.shieldHitParticles(engine, point, ship)     // 少量冷蓝白 hitParticle，克制量级
} else {
    // 电荷泄放：结算随机走共享 CombatRandom（同帧同事件不二次取值）
    shots = sourceShip.getOrCreateBuffByWeapon(SHOTS_ID, weapon) { ChargeNeedleShots(sourceShip, weapon) }
    roll = CombatRandom.nextFloatIn(shots.seed, shots.callIndex++, 0f..1f)
    if (roll < values.dischargeChance) {
        Vfx.discharge(engine, source = projectile.source, from = point, target = ship,
                      emp = 100f * values.dischargeEmpMult)
    }
}
```

**淤积 Buff（ChargeNeedleStacks，StackableBuff 契约）**：

```
成员：ship / engine / host 引用（创建时捕获）、stacksFloat: Float、perStack: Float、showOnPlayerHud: Boolean
id = "astd_charge_needle_stacks"；lifetime = HOST_BOUND；decayMode = CONTINUOUS
stacks = floor(stacksFloat)；maxStacks = min(200, dissipationCapStacks(...))  // 动态闸

addStacks(n): 新值 = (stacksFloat + n).coerceIn(0, maxStacks)；返回实际增量

advance(amount):
    stacksFloat = max(0, stacksFloat - 10f * amount)
    if (stacksFloat <= 0f) { host.remove(this); return }
    cap 重算后超上限部分直接裁掉（耗散被压制时闸门收紧）
    refreshUpkeep(ship.mutableStats.shieldUpkeepMult, stacks, perStack)
        → stat.modifyMult(MOD_ID, 1 + stacks * perStack)   // 幂等，modifierId 固定
    HUD：
      if (showOnPlayerHud && engine.playerShip != null)
          engine.maintainStatusForPlayerShip(HUD_KEY, ICON, 标题, "目标 N 层，护盾维持 +X％", negative = false)
      if (ship == engine.playerShip)
          engine.maintainStatusForPlayerShip(HUD_VICTIM_KEY, ICON, 受害标题, "本舰护盾维持 +X％（N 层）", negative = true)

isHostValid(): ship.isAlive && !ship.isHulk && engine.isEntityInPlay(ship)   // 轻量无副作用
onRemove(): ship.mutableStats.shieldUpkeepMult.unmodifyMult(MOD_ID)
```

**泄放（ChargeNeedleVfx.discharge）**：

```
落点 = ship.allWeapons 中随机一件非装饰武器的位置（Misc.random——纯视觉选取，00 §4.1 允许）；
       无武器时退 ship.location
engine.spawnEmpArc(source, from, ship, anchorEntity, DamageType.ENERGY,
                   dam = 0f, emp = emp, maxRange = 1000f, soundId = null,
                   thickness = 20f, fringe = 冷蓝白, core = 白)
// spawnEmpArc 伤害/视觉一体；锚定实体为 ship 本体保证电弧追踪
```

结算顺序：先淤积分支/泄放分支互斥（`shieldHit` 二分），无交叉结算；`applyDamage` 不经手（EMP 电弧自带结算），无二次 onHit 回环。

### 2.4 玩家可见反馈（对照全局实现注意事项 2）

| 机制 | 通道 | 说明 |
|---|---|---|
| 淤积层数（攻击方=玩家） | 左侧状态栏 `maintainStatusForPlayerShip`，negative=false，icon `graphics/hullmods/astd_arc_loop_interface.png` | 层数与维持 +% 实时刷新；目标舰不可见时不显示 |
| 淤积层数（受击方=玩家） | 同上，独立 key，negative=true | 玩家被敌版电针命中时感知自身护盾维持被抬升 |
| 护盾命中 | 冷蓝白 hitParticle（`addHitParticle`/`addSmoothParticle`，量级对齐 HighFluxShieldPressure 克制档） | 每发最多 2 粒，20 发/s 不糊屏 |
| 电荷泄放 | `spawnEmpArc` 真实电弧（视觉+结算一体） | **不加浮字**：v2 40% × 20 发/s ≈ 8 次/s，浮字必然糊屏；电弧本身即最强反馈。此取舍在此登记 |

### 2.5 0 值与边界（对照全局实现注意事项 3）

`ChargeNeedleTuning.dissipationCapStacks(dissipation, baseUpkeep, perStack): Int`（纯函数，安全闸唯一入口）：

- 语义：追加维持量 = baseUpkeep × stacks × perStack ≤ 0.5 × dissipation → `floor(0.5 × dissipation / (baseUpkeep × perStack))`，再与 200 取小。
- `baseUpkeep ≤ 0`（无盾舰/零耗盾）：返回 200——命中护盾分支已在上游 return，此分支仅为防御，**记 WARN 一次/船**（配置异常不静默）。
- `dissipation ≤ 0`：返回 0——耗散为 0 属异常态（被特殊机制压没），层数立即裁到 0，**记 WARN 一次/船**；禁止静默恒零无日志。
- `perStack ≤ 0`：返回 200 并 **记 ERROR**——难度配置错误，闸失效但机制不退化。
- 恰整除边界（如 0.5×800 / (400×0.025) = 40）按 floor 取 40，含端。
- 闸动态收紧（目标耗散被压制/船插移除）时超上限部分**直接裁层**，不做缓降（设计未要求，裁层与衰减同向）。
- `projectile.source == null`（脚本生成的游离弹）：按非玩家口径取值（`source?.owner == 0` 为 false），泄放 `spawnEmpArc` 的 source 形参传 null 由原版兜底（原版 API 允许 null source——若实机异常则以 `projectile.weapon?.ship` 再兜底并记 WARN，不做静默吞异常）。
- Buff 宿主换装/死亡：HOST_BOUND + `isHostValid` 心跳回收（共享 BuffTickPlugin），`onRemove` 恰一次 unmodify，无 stat 残留。

---

## 3. 特效面

### 3.1 `ProjectileVfxSpecs.kt` 登记项（builders map 字面量末尾追加两条 + 私有构建函数追加在调色板函数之前）

```kotlin
"astd_charge_needle_shot" to { simpleProjectileVfx("astd_charge_needle_shot", chargeNeedlePalette(), width = 6f, length = 135f) },
"astd_heavy_charge_needle_shot" to { simpleProjectileVfx("astd_heavy_charge_needle_shot", chargeNeedlePalette(), width = 9f, length = 165f) },
```

- 主色：ARC 冷蓝白（全局美术约定）。按 00 §3 合并协议，**新共享调色板只允许收口人添加**——本组分支内内联私有函数：
  `private fun chargeNeedlePalette() = VfxPalette(ASTDColor(0.55f, 0.78f, 1f, 0.9f), TEX_SMOOTH, TEX_ZAPPY)`
- 尺寸：小型 width≈6 / length≈135（对齐 spc3 现有档），中型 width≈9 / length≈165（90 计划 §1.4）。
- 拖尾主体走 texTrail（smooth 主带），弹头 bloom 网格恒在，ribbon=false（密集箭弹不挂电弧副带，避免 20 发/s 下副带噪声）。
- 派生公式（bandWidth/headWidth 等）全部吃共享纯函数，登记行只填 5 旋钮。

### 3.2 `smd_projectile_vfx.json` 映射（entries 数组末尾追加）

```json
{ "projectileSpecId": "astd_charge_needle_shot", "preset": "charge_needle_shot" },
{ "projectileSpecId": "astd_heavy_charge_needle_shot", "preset": "heavy_charge_needle_shot" }
```

preset 命名沿用 `aod7_shot`/`spc3_shot` 去前缀惯例。

### 3.3 命中/泄放特效

不新增 RenderEntity 组件：护盾命中粒子走 `engine.addHitParticle/addSmoothParticle`；泄放走 `spawnEmpArc`（原版 EMP 电弧视觉，冷蓝白双色参数化）。均不构成共享文件触碰。

---

## 4. 测试面

### 4.1 单元测试（`src/test/kotlin/cn/kasuminova/astd/combat/effect/arc/`，全部调用真实逻辑，禁止源码 contain）

`ChargeNeedleTuningTest`（经 `DifficultyTuningImpl.installScaleForTests` 走完整映射链路，对齐 `BountyScalingHullModTest` 先例， `@AfterTest` 清注入）：

1. **三锚点精确命中**：k_s=1/2/5 下 resolve（非玩家）→ perStack = 0.01/0.02/0.05、chance = 0.25/0.40/1.00、empMult = 1.00/1.75/4.00（±1e-6）。
2. **玩家固定 v2**：k_s=1 与 k_s=5 下 resolve(isPlayer=true) → 三项恒为 v2。
3. **k_s=3 线性插值**：perStack = 0.03（LINEAR 语义），断言与 `ScalingMap.LINEAR.value` 直算一致。

`ChargeNeedleCapTest`（纯函数 `dissipationCapStacks`）：

4. **常规闸**：dissipation=800、upkeep=400、perStack=0.02 → 50。
5. **200 层 clamp**：高耗散输入（dissipation=100000、upkeep=100、perStack=0.01）→ 200。
6. **upkeep=0 → 200**（闸豁免分支）。
7. **dissipation=0 → 0**（异常收紧分支，不静默恒零——返回值断言 + 日志路径由调用侧 WARN 承担）。
8. **perStack=0 → 200**（配置错误防御分支）。
9. **恰整除含端**：0.5×800/(400×0.025) → 40。

`ChargeNeedleStacksMathTest`（浮点累加器 + `MutableStat` 真对象——`MutableStat` 为具体类，直接 `MutableStat(1f)` 构造，无反射无 fake）：

10. **叠层 clamp**：连续 addStacks 超闸后返回值 = 实际增量（00 Buff API 契约语义在本件的具体化）。
11. **CONTINUOUS 衰减**：10 层/s——`decay(0.1f)` 恰 -1 层；`decay(0.05f)×3` 累计 -1.5 → 层数视图 floor 序列 [8, 8, 7]（从 9 起）；衰减不穿 0。
12. **维持倍率刷新与回收**：`refreshUpkeep(MutableStat(1f), stacks=50, perStack=0.02)` → modifiedValue = 2.0；同一 modifierId 二次幂等刷新不叠乘；unmodifyMult 后回 1.0。

`ChargeNeedleDischargeTest`：

13. **泄放判定边界**：`shouldDischarge(roll, chance)`——chance=0 恒 false；chance=1 时 roll=0.999 true；roll == chance 边界 false（`<` 口径）；roll 由固定 seed 的 `CombatRandom` 序列喂入（共享基建件，此处只断言映射，不重复测基建）。

`ChargeNeedleVfxRegistrationTest`（真实调用管线入口）：

14. **VfxSpec 登记**：`ProjectileVfxSpecs.has/build("astd_charge_needle_shot"/"astd_heavy_charge_needle_shot")` 非空，build 执行 DSL 不抛异常；smd_projectile_vfx.json 含两条映射（数据文件读取断言，对齐现有 data 测试族，非源码 contain）。

### 4.2 烟测检查点（`deployMod` + `launchSmokeTestGame`，到达终态即退出游戏）

1. dev 仓储出现两件武器 + 两张蓝图；学习蓝图后 refit 可装配；名称/tip/定位字符串全中文无键名泄漏。
2. 命中敌舰护盾：HUD 出现「电荷淤积」条目，层数与 +% 随命中上升；停火后约 10 层/s 回落到 0，条目消失。
3. 目标辐能面板/实测：淤积期间护盾维持显著抬升（目检目标开盾时幅能上涨加快）。
4. 命中船体：可见 EMP 电弧连向武器/引擎部位，武器被瘫痪火花；v2 档触发频率符合 40% 体感。
5. 弹匣节奏：满匣倾泻 1.5s（小）/ 3s（重），之后进入约 12s 满充周期；射速 20 发/s 无卡壳。
6. 安全闸：对高耗散主力舰叠层可上高位但维持追加不超其耗散 50%；对低耗散护卫快速触顶（devMode 日志核对 cap 值）。
7. 难度隔离：LunaLib k_s=5 下玩家持有仍 +2%/层、40%/175%；敌版 +5%/层、100%/400%。
8. 弹体 VFX：冷蓝白 texTrail 箭弹，小型细/中型粗；无原版弹体残留、无拖尾错位；泄放电弧冷蓝白。
9. 玩家被敌版命中：出现 negative 状态条目；hulk 化后条目与 stat 修改同时消失（无残留）。
10. devMode FPS：20 发/s 持续命中下无明显掉帧（BuffTickPlugin 遍历成本登记观察）。

---

## 5. 并行实装注意

### 5.1 触碰的共享文件（按 00 §3 合并协议）

| 共享文件 | 本组键名空间 / 追加位置 |
|---|---|
| `ss-csv/.../i18n/zh-cn.properties` | `weapon.astd_charge_needle.*`、`weapon.astd_heavy_charge_needle.*`、`desc.astd_charge_needle.*`、`desc.astd_heavy_charge_needle.*`；全部集中在文件末尾，不插中间、不动其他武器键 |
| `ss-csv/.../strings/Catalog_Descriptions.kt` | WEAPON 分组尾部（`Desc_astd_psi_omega` 之后）两行 |
| `ss-csv/.../weapondata/arc/Catalog_WeaponData_ARC.kt` | 文件末尾两个 object；number 段位 **9210/9211**（预分配，不与他组冲突） |
| `contents/data/campaign/special_items.csv` | 文件末尾两行；`order` 列留空待收口人统一编号 |
| `src/.../renderer/projectile/driver/ProjectileVfxSpecs.kt` | builders map 末尾两条；`chargeNeedlePalette()` 内联字面量（不新增共享调色板）；构建函数追加在调色板函数前 |
| `contents/data/config/smd_projectile_vfx.json` | entries 数组末尾两条 |
| `contents/data/strings/strings.json` | **00 协议未覆盖，本组补充登记**：HUD 键 `ui.charge_needle.status.*`（title/desc/victim_title/victim_desc 四键），MOD 类对象内末尾追加；收口人按 ui.<武器> 字典序归位。建议在 00 §3 表格补行 |

新增无冲突文件：`contents/data/weapons/astd_charge_needle.wpn`、`astd_heavy_charge_needle.wpn`；`src/.../combat/effect/arc/ChargeNeedle*.kt` ×4；测试目录同包。

### 5.2 对共享基建的依赖（只依赖、不改签名）

- **Buff API**（00 §1）：`Buff` / `StackableBuff` / `BuffHost` / `BuffAccess` / `BuffTickPlugin`——硬阻塞，必须先进 main。
- **CombatRandom**（00 §4.1）：泄放概率结算随机——量小，随 Buff API 同 PR 落地即可。
- **HUD 通道**（00 §4.2）：`maintainStatusForPlayerShip` 样板参照 `LensMarkStatusBar`，无代码依赖。
- 不需要 ConeImpactHandler。

### 5.3 实现顺序内的位置

90 计划 §12 第 1 位（建立 OnHitEffect + 目标舰 Buff + shieldUpkeepMult 链路，为重型离子脉冲提供 OnHit 模式样板）；但受 Buff API 基建 PR 阻塞——**基建合入 main 后第一个开工的武器组**。组内顺序：Tuning/纯函数 + 单测 → Stacks + OnHit + Vfx → 数据面（ss-csv 生成 + .wpn + i18n + 蓝图）→ VfxSpec 登记 → 烟测。

---

## 6. 验收要点（主代理逐项核对）

**数据面**

- [ ] `Catalog_WeaponData_ARC.kt` 两个 object 逐列与本规格 §1.1 一致；number = 9210/9211；`generateSsCsv` 后 `weapon_data.csv` 出现两行（ammo 30/2.5/15 与 60/5/30、chargedown 0.05、emp 100、tags `astd_production`）。
- [ ] 两个 `.proj`：`onHitEffect` 指向 `ChargeNeedleOnHitEffect`，`onFireEffect` 指向 dispatcher，原版弹体隐藏四件套齐全（length/width=2、双色 alpha=0、BUtil_NONE、fadeTime=0.2）。
- [ ] 两个 `.wpn`：size SMALL/MEDIUM、projectileSpecId 正确、双 onFire/everyFrame 挂载、sound `needler_fire`。
- [ ] zh-cn.properties 键齐全且 tip 为设计案定稿原文；desc.text1/notes 已评审确认；重型 notes 复用小型。
- [ ] special_items.csv 两行 params = 裸武器 id（§1.5 裁决口径），order 留空。

**代码面**

- [ ] 无 `XxxService/Manager/Controller/Runtime` 类名；无反射；无空 catch；无占位实现。
- [ ] 叠层走 `StackableBuff` 接口与 `ShipAPI.buffHost()`，未自建 customData 状态表；Weapon 级 callIndex 走复合键 Buff。
- [ ] 玩家固定 v2 取值在 OnHit 每次命中处调用（非缓存）；泄放随机走 `CombatRandom`，同帧不二次取值。
- [ ] 安全闸纯函数四分支（常规/200 clamp/upkeep=0/dissipation=0/perStack=0）与 WARN/ERROR 日志落地。
- [ ] `onRemove` unmodify 与心跳回收链路完整；HUD 双向（攻击方/受击方）维护。

**特效面**

- [ ] builders 两条登记 + 内联调色板（未新增共享 palette）；smd json 两条映射；texTrail 冷蓝白，小 6/135、中 9/165。
- [ ] 泄放 `spawnEmpArc` 冷蓝白参数；护盾命中粒子克制量级。

**测试面**

- [ ] §4.1 十四条用例全部存在且调用真实逻辑（无源码 contain）；`MutableStat` 用真对象。
- [ ] §4.2 烟测十项全部过检并留目检记录；烟测到达终态即退出游戏。

**合并面**

- [ ] 共享文件全部末尾追加、键名空间内自闭合；number/preset/order 无越界；strings.json 补行已同步登记回 00 §3（或单独提出）。
