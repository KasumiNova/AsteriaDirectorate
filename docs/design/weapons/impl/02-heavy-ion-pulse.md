# 重型离子脉冲 逐件实现规格 v1（待评审）

> 依据：`docs/design/weapons/impl/00-共享基建.md` v1（Buff API / CombatRandom / HUD 通道 / 合并协议）、`docs/design/weapons/90-首批实装计划.md` v6 §2 与全局约定、设计案定稿 `blue/20-production.md`「重型离子脉冲」v1.0（2026-07-28，含补充裁定：射速 1.5×、OP 26、v2 锚点线性默认）。
> 状态：规划文档，不改动 `src/` 与 `ss-csv/`。
> API 核查时间：2026-07-29，对照 `starfarer.api.jar`（0.98）javap 签名与现有 `src/` 代码逐条核实（`OnHitEffectPlugin`、`CombatEngineAPI.spawnEmpArc(...)/applyDamage(...)/addFloatingDamageText(...)/spawnEmpArcVisual(...)`、`MutableShipStatsAPI.getEmpDamageTakenMult()` + `MutableStat.getModifiedValue()`、`DamagingProjectileAPI.getEmpAmount()`、`CombatEntityAPI.getOwner()`、`DifficultyTuningImpl.installScaleForTests`、ss-csv `WeaponDataEntry`/`ProjectileProjSpec` 字段、`I18n.t` 均存在；音效 `ion_pulser_fire` 已在原版 `sounds.json` 核实）。
> 复用承诺：泄放概率结算随机走共享 `CombatRandom`；Weapon 级 callIndex 走共享 Buff API 复合键标记 Buff；反馈通道走 00 §4.2；OnHit 模式对齐 `01-charge-needle.md`（90 计划 §2.6「复用电荷针刺 OnHit 模式」）。**2026-09 修订**：本件新增目标侧叠层状态（EMP 抗性削减，Ship 级 `StackableBuff`，`HeavyIonPulseEmpResistStacks`），原「无叠层机制、不使用 StackableBuff」口径作废。
> **2026-09 机制修订摘要**：更名「彗星冲击波」（id 不变）；面板改 800 射程 / 250 伤害 / 500 EMP / OP 28 / 弹匣 24 / 4 连发 0.1s + 冷却 0.4s；泄放概率与 EMP 倍率改五档精确查表；新增船体/装甲命中必叠 EMP 抗性削减（40 层上限、1 层/s 消散、隐藏的易伤转化）；电弧基准 EMP 改为弹体面板值；EMP 贯穿（破晓敌版）机制不变。

---

## 1. 数据面

### 1.1 ss-csv catalog 条目（`Catalog_WeaponData_ARC.kt` 文件末尾追加一个 object）

number 段位按 00-共享基建 §3 预分配：重型离子脉冲 **9212**。`WeaponDataEntry(), SsProjProjectileOutputs`。

`Wpn_astd_heavy_ion_pulse`（逐列）：

| 字段 | 值 | 备注 |
|---|---|---|
| id | `astd_heavy_ion_pulse` | |
| name | `weaponName(id)` | 走 i18n 键 `weapon.astd_heavy_ion_pulse.name` |
| tier / rarity | 2 / 1 | 量产件；tier 对齐原版离子脉冲（2） |
| baseValue | 24000 | 提案：重型电荷针刺 14000（16 OP）→ 28 OP 大能量档；评审确认 |
| range | 800 | 2026-09 修订：700 → 800 |
| damagePerSecond | 400 | 持续 1.6 发/s × 250 折算（照 aod7「持续 DPS」口径填；2026-09 修订：360 → 400） |
| damagePerShot | 250 | 能量（2026-09 修订：135 → 250） |
| emp | 500 | 单发 EMP（2026-09 修订：600 → 500；电弧基准改为弹体面板值，见 §2.2） |
| impact | 0 | 能量武器无冲击 |
| turnRate | 20 | 对齐原版 ionpulser |
| ops | 28 | 2026-09 修订：26 → 28 |
| ammo / ammoPerSec / reloadSize | 24 / 1.6 / 8 | 弹匣三列原生表达（2026-09 修订：40 / 2.67 / 8 → 24 / 1.6 / 8） |
| type | `ENERGY` | |
| energyPerShot / energyPerSecond | 275 / 0 | 单发 275；`energy/second` 置 0——充能武器该列会被 `ChargeFireTracker` 按秒真实扣辐（原版 ionpulser 同口径留空），持续辐能由派生公式算回（2026-09 修订：150 / 400 → 275 / 0） |
| chargeup | 0.05 | 对齐原版 ionpulser（计划未提，提案值） |
| chargedown / burstSize / burstDelay | 0.4 / 4 / 0.1 | 4 连发 0.1s/发、射击冷却 0.4s（2026-09 修订：0.175 / 4 / 0.067 → 0.4 / 4 / 0.1） |
| minSpread / maxSpread / spreadPerShot / spreadDecayPerSec | 3 / 20 / 1 / 4 | 对齐原版 ionpulser（计划未提，提案值） |
| projSpeed | 1000 | 对齐原版 ionpulser |
| tags | `astd_production` | |
| groupTag / tech | `astd` / `弧光阵列` | |
| primaryRoleStr | `SsI18n.t("weapon.$id.primaryRoleStr")` | |
| customPrimary | `SsI18n.t("weapon.$id.tooltip.customPrimary")` | 3 个 `{%s}` 占位与 HL 三段一一对应（2026-07-29 字段分工铁律 + 占位规则） |
| number | 9212 | |
| projSpec | 见下 | |

`projSpec`（显式 `ProjectileProjSpec` 构造，照 `Wpn_astd_aod7.projSpec` 样板；不用 `standard()` 以便固定四件套字面量）：

- id = `astd_heavy_ion_pulse_shot`，spawnType = `ProjectileSpawnType.BALLISTIC`
- onFireEffect = `cn.kasuminova.astd.combat.effect.generic.ProjectileSpecOnFireDispatcher`
- onHitEffect = `cn.kasuminova.astd.combat.effect.arc.HeavyIonPulseOnHitEffect`
- collisionClass = `PROJECTILE_FF`，collisionClassByFighter = `PROJECTILE_FIGHTER`
- 原版弹体视觉隐藏四件套：length = 2.0、width = 2.0、fringeColor = `Rgba(140, 200, 255, 0)`、coreColor = `Rgba(225, 242, 255, 0)`、bulletSprite = `graphics/textures/BUtil_NONE.png`、fadeTime = 0.2、textureScrollSpeed = 0.0、pixelsPerTexel = 1.0

### 1.2 `Catalog_Descriptions.kt`

WEAPON 分组尾部（本组与电荷针刺同批时，插在其后保持 id 字典序）追加：

```kotlin
object Desc_astd_heavy_ion_pulse : LocalizedDescription("astd_heavy_ion_pulse", "WEAPON")
```

### 1.3 `.wpn` JSON 骨架（`contents/data/weapons/astd_heavy_ion_pulse.wpn`，手写，照 `astd_spc3.wpn` 样板）

```json
{
    "id": "astd_heavy_ion_pulse",
    "specClass": "projectile",
    "type": "ENERGY",
    "size": "LARGE",
    "displayArcRadius": 700,
    "turretSprite": "graphics/textures/BUtil_NONE.png",
    "turretGunSprite": "graphics/textures/BUtil_NONE.png",
    "hardpointSprite": "graphics/textures/BUtil_NONE.png",
    "hardpointGunSprite": "graphics/textures/BUtil_NONE.png",
    "visualRecoil": 0.0,
    "turretOffsets": [18, -7, 18, 7],
    "turretAngleOffsets": [0, 0],
    "hardpointOffsets": [18, -7, 18, 7],
    "hardpointAngleOffsets": [0, 0],
    "barrelMode": "ALTERNATING",
    "animationType": "MUZZLE_FLASH",
    "projectileSpecId": "astd_heavy_ion_pulse_shot",
    "onFireEffect": "cn.kasuminova.astd.combat.effect.generic.ProjectileSpecOnFireDispatcher",
    "everyFrameEffect": "cn.kasuminova.astd.combat.effect.generic.CombatVfxBootstrapEveryFrameEffect",
    "fireSoundTwo": "ion_pulser_fire"
}
```

挂载点说明：

- **双炮管交替射击**（设计案定稿：对应 1.5× 射速，区别于原版单管）：`turretOffsets`/`hardpointOffsets` 各给两根炮管坐标（±7 横向间隔，提案值）+ `barrelMode: "ALTERNATING"`——每轮 4 发连发在两管间交替出膛，muzzle flash 随之交替。原版 ionpulser 虽写 `ALTERNATING` 但只有单管坐标（交替退化），本件必须给双坐标才成立。
- `projectileSpecId` → 弹体数据与 VFX 路由键。
- `onFireEffect` → 弹体 VFX 管线（与 `.proj` 上的同名效果由 dispatcher 内部 customData 去重，双挂是现成惯例；注意 `WeaponSpecAPI` 无 `getOnFireEffect()`——`.wpn` 层该键原版加载器不消费，仅为与 spc3 现状对齐保留，真正生效的是 `.proj` 上的 `onFireEffect`）。
- `everyFrameEffect = CombatVfxBootstrapEveryFrameEffect` → RenderEntity 战斗层引导（spc3/aod7 同款）。
- `fireSoundTwo = ion_pulser_fire` → 原版音效 id 已在原版 `data/config/sounds.json` 核实存在。
- 炮口贴图暂不配（BUtil_NONE 全隐 + MUZZLE_FLASH），美术资源到位后只换贴图路径与炮管坐标，不动结构（与 01 同口径）。

### 1.4 i18n 键清单（`ss-csv/src/main/resources/i18n/zh-cn.properties` 文件末尾集中追加）

机制文案含 v2 写死数值（2026-07-29 字段分工铁律；01/02 块经用户亲自修正，数值已按用户裁定统一为 v2 显示值）：

```properties
# Weapon 名称（2026-09 更名：重型离子脉冲 → 彗星冲击波）
weapon.astd_heavy_ion_pulse.name=彗星冲击波

# Weapon tooltip 自定义字段（数值以 v2 为准：抗性削减 2%/层、至多 40 层、1 层/s 消散；泄放概率 30%、EMP 100%；高亮字一律 {%s} 占位，原文只在 HL）
weapon.astd_heavy_ion_pulse.tooltip.customPrimary=命中船体或装甲时降低目标 {%s} 的 EMP 抗性，至多叠加 {%s} 层、每秒消散 {%s} 层；同时有 {%s} 的概率产生打击武器与引擎的电弧，造成该武器命中目标时 {%s} 的额外伤害。效果受到{%s}影响。
weapon.astd_heavy_ion_pulse.tooltip.customPrimaryHL=2% | 40 | 1 | 30% | 100% | 难度系数

# Weapon 定位（2026-07-29 审批修正：与原版 ionpulser 同用「瘫痪」）
weapon.astd_heavy_ion_pulse.primaryRoleStr=瘫痪

# descriptions.csv（提案文案，评审时确认）
desc.astd_heavy_ion_pulse.text1=离子脉冲炮的大型化改进型，拥有足以在交战时迅速瘫痪敌舰系统与引擎的巨量 EMP 损害，并产生严重损伤着弹点附近模块的电弧。
desc.astd_heavy_ion_pulse.notes=
# 不加 notes
```

2026-09 修订：本件**新增目标侧叠层状态**（EMP 抗性削减，见 §2.1 `HeavyIonPulseEmpResistStacks`），因此 HUD 文案改为登记在 `contents/data/strings/strings.json`（MOD 类）四键：`ui.heavy_ion_pulse.status.title` / `.desc` / `.victim_title` / `.victim_desc`——原「本件无 HUD 状态条目、不需要 `strings.json` 键」的口径作废。EMP 贯穿补伤浮字仍为纯数字（`addFloatingDamageText`）。

### 1.5 `special_items.csv` 条目（`contents/data/campaign/special_items.csv` 文件末尾追加，order 段位 9207）

**order 列口径（2026-07-29 实机发现）**：原版 CSV 解析强制数字，留空启动即 JSONException；各组按合并协议预分配段位直填。蓝图描述内的引号用弯引号（原版字体不渲染「」）。

量产件 P2 走单件蓝图（90 计划 §14），plugin params 用**裸武器 id**（2026-07-29 主代理裁决：反编译 `WeaponBlueprintItemPlugin.init` 证实 params 直接传 `getWeaponSpec(data)`，`weapon:` 前缀会导致 spec 为 null）；带 `single_bp` 标签时 params 必填（`ASTDDevContentSelector.SPECIAL_ITEM_PARAM_REQUIRED_TAGS/IDS` 核实），dev 仓储会自动投放：

```csv
重型离子脉冲蓝图,astd_heavy_ion_pulse_bp,"weapon_bp, single_bp, no_drop, no_drop_salvage",菀星设计局-星坠,,24000,1,0,,graphics/icons/cargo/blueprint_weapons.png,ui_chip_pickup,ui_weapon_bp_drop,com.fs.starfarer.api.campaign.impl.items.WeaponBlueprintItemPlugin,astd_heavy_ion_pulse,使重工业设施能够制造“重型离子脉冲”。,9207
```

`no_drop, no_drop_salvage` 为 P6 前口径（P6 接入量产蓝图投放时摘除）。

---

## 2. 代码面

包：`cn.kasuminova.astd.combat.effect.arc`（ARC 线武器机制包，90 计划全局约定；现有内容仅 `signature/aod7`，本件为该包首个量产件）。

### 2.1 类清单

| 类名 | 接口/实现 | 职责 | 挂载点 | 文件路径 |
|---|---|---|---|---|
| `HeavyIonPulseOnHitEffect` | 实现 `OnHitEffectPlugin` | 命中路由：船体/装甲命中 → 必叠 1 层 EMP 抗性削减 + 概率泄放 EMP 电弧 +（v5）EMP 贯穿补伤；护盾命中直接返回 | `.proj` 的 `onHitEffect` | `src/main/kotlin/cn/kasuminova/astd/combat/effect/arc/HeavyIonPulseOnHitEffect.kt` |
| `HeavyIonPulseEmpResistStacks` | 实现共享 `StackableBuff`（CONTINUOUS 衰减） | **目标侧状态（2026-09 新增）**：EMP 抗性削减层数（浮点累加器、上限 40、1 层/s 消散）、`empDamageTakenMult` 绝对位移幂等刷新、HUD 维护、层数归零自移除 | 经 `ShipAPI.buffHost()` 注册（Ship 级，id `astd_heavy_ion_pulse_emp_resist`） | 同包 `HeavyIonPulseEmpResistStacks.kt` |
| `HeavyIonPulseShots` | 实现共享 `Buff`（非叠层，纯标记） | 记录泄放概率结算随机的 `callIndex`（00 §4.1 口径：Weapon 级复合键，seed 派生 `source.id*31 + slot.id`） | Weapon 级 Buff，id `astd_heavy_ion_pulse_shots` | 同包 `HeavyIonPulseShots.kt` |
| `HeavyIonPulseTuning` | object（数值声明，对齐 `DifficultyTuningImpl` object 先例） | 查表 `ScalingTable` / 三锚点 `ScalingEntry` 常量 + `resolve(tuning, isPlayer)` 取值 + 纯函数 `empPierceExtra(...)` / `empPierceApplied(...)` / `shouldDischarge(...)` | 被 OnHit 调用 | 同包 `HeavyIonPulseTuning.kt` |
| `HeavyIonPulseVfx` | object（视觉静态入口，对齐 `ImpactStrikeFx` 惯例） | 泄放电弧落点选取与 `spawnEmpArc` 调用、贯穿补伤浮字与火花 | 被 OnHit 调用 | 同包 `HeavyIonPulseVfx.kt` |

与 01 电荷针刺的结构对应（OnHit / Shots / Tuning / Vfx 四件 + 目标侧叠层 Buff 一件），评审可并排对照。

### 2.2 难度锚点与取值调用点

`HeavyIonPulseTuning`（2026-09 修订：泄放概率 / EMP 倍率 / 抗性削减改为五档精确查表 `ScalingTable`，逐档不插值）：

| 常量 | k1 | k2（v2 / 玩家固定档） | k3 | k4 | k5 |
|---|---|---|---|---|---|
| `DISCHARGE_CHANCE`（泄放电弧触发概率） | 20% | 30% | 40% | 50% | 60% |
| `DISCHARGE_EMP_MULT`（泄放 EMP 倍率） | 75% | 100% | 125% | 150% | 200% |
| `EMP_RESIST_PER_STACK`（每层 EMP 抗性削减） | 1% | 2% | 3% | 4% | 5% |

固定不缩放：EMP 抗性削减层数上限 `RESIST_MAX_STACKS = 40`、消散速率 `RESIST_DECAY_PER_SECOND = 1` 层/s、EMP 贯穿减免下限 `PIERCE_FLOOR = 0.1`。
**电弧基准 EMP 直取弹体面板值**（`projectile.empAmount`，2026-09 修订：旧常量 `BASE_DISCHARGE_EMP = 600` 已删除）。
初始化锚点历史：旧三锚点 `DISCHARGE_CHANCE = (0.25, 0.3125, 0.50)` / `DISCHARGE_EMP_MULT = (1.00, 1.25, 2.00)` 作废。
**EMP 贯穿为逐项映射式解锁**：不入查表——激活条件为 `DifficultyTuningImpl.fixedScale >= 5f`（破晓档），玩家版本 `owner == 0` 固定 v2 口径天然排除（设计案：玩家永远不会获得此特效）。

调用点：`HeavyIonPulseOnHitEffect.onHit` 内每次船体/装甲命中调用一次

```kotlin
val values = HeavyIonPulseTuning.resolve(DifficultyTuningImpl, isPlayer = projectile.source?.owner == 0)
val pierceActive = !values.isPlayer && DifficultyTuningImpl.fixedScale >= 5f
```

`resolve`：玩家（owner == 0，`CombatEntityAPI.getOwner()` 已核实）固定 `table.v2`；否则 `tuning.value(table)`。

### 2.3 核心逻辑伪代码

**命中路由（HeavyIonPulseOnHitEffect.onHit）**：

```
if (engine.isPaused) return
if (shieldHit) return                              // EMP 对盾无效（原版特性），面板 EMP 亦不产生贯穿话题
ship = target as? ShipAPI ?: return                // 只对舰船泄放；战机/陨石豁免
if (ship.isHulk || ship.isPhased) return
point ?: projectile.location ?: return             // 对齐 HighFluxShieldPressure 样板
values = Tuning.resolve(...)

baseEmp = projectile.empAmount                     // 面板 500 × 武器侧修正（getEmpAmount 已核实）
arcEmp = 0f

// EMP 抗性削减（2026-09 新增）：船体/装甲命中必叠 1 层（与泄放判定独立）
host = ship.buffHost()
resistBuff = host.find(ResistStacks.BUFF_ID) as? ResistStacks
    ?: ResistStacks(ship, engine, host).also { host.register(it) }
resistBuff.perStack = values.empResistPerStack     // 多攻击者时后命中者口径覆盖（已文档化）
resistBuff.addStacks(1)
if (projectile.source === engine.playerShip) resistBuff.showOnPlayerHud = true

// 瘫痪电弧：结算随机走共享 CombatRandom（同帧同事件不二次取值）
shots = sourceShip.getOrCreateBuffByWeapon(SHOTS_ID, weapon) { HeavyIonPulseShots(sourceShip, weapon) }
roll = CombatRandom.nextFloatIn(shots.seed, shots.callIndex++, 0f..1f)
if (Tuning.shouldDischarge(roll, values.dischargeChance)) {
    arcEmp = baseEmp * values.dischargeEmpMult   // 基准 = 弹体面板 EMP（2026-09 修订：原 600f 常量）
    Vfx.discharge(engine, source = projectile.source, from = point, target = ship, emp = arcEmp)
}

// EMP 贯穿（破晓敌版限定）：面板命中 EMP 与电弧 EMP 一起补
if (pierceActive) {
    mult = ship.mutableStats.empDamageTakenMult.modifiedValue
    extra = Tuning.empPierceExtra(baseEmp + arcEmp, mult)
    applied = Tuning.empPierceApplied(extra, mult)       // A9 折算补偿（2026-07-29 裁定方案 a）
    if (applied > 0f) Vfx.pierce(engine, ship, point, extra, applied, source = projectile.source)
}
```

结算顺序：泄放判定与电弧结算在前，贯穿补伤在后一次性覆盖「面板 EMP + 本次电弧 EMP」两笔；贯穿走 `engine.applyDamage`（applyDamage 不触发 onHitEffect），无二次 onHit 回环。

**泄放（HeavyIonPulseVfx.discharge）**：

```
落点 = ship.allWeapons 中随机一件非装饰武器的位置（Misc.random——纯视觉选取，00 §4.1 允许）；
       无武器时退 ship.engineController 引擎挂载点，再无退 ship.location
engine.spawnEmpArc(source, from, ship, ship, DamageType.ENERGY,
                   dam = 0f, emp = emp, maxRange = 1000f, soundId = null,
                   thickness = 24f, fringe = 冷蓝白, core = 白)
// spawnEmpArc 伤害/视觉一体；签名 (ShipAPI, Vector2f, CombatEntityAPI, CombatEntityAPI,
//   DamageType, float, float, float, String, float, Color, Color) 已核实
```

**贯穿补伤（HeavyIonPulseVfx.pierce，A9 折算补偿后）**：

```
engine.applyDamage(ship, point, 0f, DamageType.ENERGY, applied, false, false, source)
// 7 参重载 (entity, point, damage, damageType, empDamage, dealsSoftFlux, bypassShield, source) 已核实
// applied = extra / max(mult, 0.01)：引擎对 empDamage 会再乘一次目标 empDamageTakenMult，
// 折算后实际结算回补到 extra（A9 裁定方案 a，2026-07-29）
engine.addFloatingDamageText(point, extra, 冷蓝白, ship, source)   // 显示值 = 实际结算量
engine.addHitParticle(point, zero, 30f, 1f, 0.2f, 冷蓝白)           // 克制火花 1~2 粒
```

**Tuning 纯函数**：

```
shouldDischarge(roll, chance) = roll < chance            // 边界口径：roll == chance 不触发（与 01 一致）

empPierceExtra(emp, mult): Float =
    if (mult >= PIERCE_FLOOR /* 0.1f */) 0f              // 减免未超 90%，不补
    else emp * (PIERCE_FLOOR - mult) / PIERCE_FLOOR      // 设计案定稿口径：empDamage × (0.1 - mult) / 0.1

empPierceApplied(extra, mult): Float =                    // A9 裁定方案 a（2026-07-29）
    if (mult <= 0f) 0f                                   // 完全 EMP 免疫：补偿无法突破 0 乘区，整体跳过不弹假浮字
    else extra / max(mult, PIERCE_COMPENSATION_FLOOR /* 0.01f */)  // 折算补偿；< 0.01 按下限折算，少量欠补属防爆炸钳制
```

### 2.4 玩家可见反馈（对照全局实现注意事项 2）

| 机制 | 通道 | 说明 |
|---|---|---|
| 瘫痪电弧（泄放触发） | `spawnEmpArc` 真实电弧（视觉+结算一体） | 电弧连向武器/引擎部位，武器瘫痪火花为原版原生反馈。**不加浮字**：v2 30% × 5.7 发/s ≈ 1.7 次/s，电弧本身即最强反馈（沿用 01 取舍口径） |
| EMP 抗性削减（2026-09 新增） | 左侧状态栏 `maintainPlayerStatus`（每帧刷新） + 目标侧 stat 位移 | 攻击方为玩家船时显示目标削减层数与抗性 −%；受击方为玩家船时独立键 negative=true 显示本舰被削减的抗性。文案走 `strings.json` 四键 `ui.heavy_ion_pulse.status.*`（2026-09 修订：原「无 HUD 条目」口径作废） |
| EMP 贯穿补伤 | `addFloatingDamageText`（00 §4.2 表已登记该用途）+ 命中点火花 | 破晓敌版限定、仅对高 EMP 抗性目标触发，频率天然极低；玩家作为**受击方**时浮字在其屏上可见，满足「不得有机制无反馈」 |
| 弹体命中（未触发泄放） | texTrail 弹体 + 原版 EMP 命中火花（面板 EMP 原生反馈） | 无需额外粒子，克制处理 |

### 2.5 0 值与边界（对照全局实现注意事项 3）

- `empPierceExtra` 常量除数为 `PIERCE_FLOOR`（0.1f 编译期常量），**无除零路径**；`mult = 0`（目标 EMP 免疫 100%）时公式自然退化为 `emp × 1.0`（追加整发等值 EMP），不静默恒零。
- `mult` 恰等于 0.1：不补（`<` 口径，与 §2.5 测试用例钉死）。
- `baseEmp = projectile.empAmount ≤ 0`（配置错误：emp 列被清/被其他 mod 清零）：泄放与贯穿全部跳过并**记 WARN 一次/武器 id**——面板 EMP 是本件存在意义，归零属配置异常，不静默。
- ~~**贯穿追加量是否被目标 `empDamageTakenMult` 二次减免**~~ **已证实并修复（2026-07-29，A9）**：烟测证实 `applyDamage(empDamage)` 被目标 mult 二次折算（mult≈0 目标实际结算≈0、浮字却显示全额）。审批裁定**方案 a 折算补偿**：施加量 `extra / max(mult, 0.01f)`，引擎二次乘算后实际结算回补到 extra，浮字显示 extra（显示值 = 实际结算量）；mult ≤ 0 完全免疫时整体跳过。纯函数 `empPierceApplied` 已钉死（单测用例 9~12），烟测相位机加 applied 遥测硬断言。
- **EMP 抗性削减叠层（2026-09 新增）的 0 值防线**：目标 `empDamageTakenMult ≤ 0`（完全 EMP 免疫，0 乘区无法被乘算突破）时机制不产生效果——层数照常累积/消散但不写 stat（与贯穿 mult ≤ 0 整体跳过同一口径，已文档化）；`current ≤ 0` 分支已先 `unmodifyMult` 自身 modifier，无残留。层数上限 40、1 层/s 消散、`onRemove` 恰一次 unmodify。
- `projectile.source == null`（脚本生成的游离弹）：按非玩家口径取值（`source?.owner == 0` 为 false）；泄放/贯穿的 source 形参传 null 由原版兜底（原版 API 允许 null source——若实机异常则以 `projectile.weapon?.ship` 再兜底并记 WARN，不做静默吞异常）。
- 目标 hulk 化/相位态：命中路由直接 return；目标侧抗性削减状态为 HOST_BOUND Buff，随宿主 hulk/死亡由心跳回收并 unmodify（2026-09 修订：原「本件不挂任何目标侧 stat 修改、天然无回收负担」的口径作废）。
- 敌版泄放随机序列：`HeavyIonPulseShots` 为 Weapon 级 Buff，宿主换装/死亡由共享 BuffTickPlugin 心跳回收（HOST_BOUND），callIndex 无泄漏。

---

## 3. 特效面

### 3.1 `ProjectileVfxSpecs.kt` 登记项（builders map 字面量末尾追加一条 + 私有构建函数追加在调色板函数之前）

```kotlin
"astd_heavy_ion_pulse_shot" to { simpleProjectileVfx("astd_heavy_ion_pulse_shot", heavyIonPulsePalette(), width = 12f, length = 220f, ribbon = true) },
```

- 主色：ARC 冷蓝白（全局美术约定）。按 00 §3 合并协议，**新共享调色板只允许收口人添加**——本组分支内内联私有函数：
  `private fun heavyIonPulsePalette() = VfxPalette(ASTDColor(0.55f, 0.78f, 1f, 0.9f), TEX_SMOOTH, TEX_ZAPPY)`
  （与 01 电荷针刺同色系；收口人统一「冷蓝白」共享调色板时两处一并归位。）
- 尺寸：width≈12 / length≈220（90 计划 §2.4）。
- `ribbon = true`：电弧副带（zappy 贴图）表达「标准弹头附加 EMP 电弧特效」的设计案性格，与电荷针刺（ribbon=false 的干净箭弹）形成观感区分；目检确认副带噪声在 2.67 发/s 下可接受。
- 拖尾主体走 texTrail（smooth 主带），弹头 bloom 网格恒在；派生公式（bandWidth/headWidth 等）全部吃共享纯函数，登记行只填 5 旋钮。

### 3.2 命中/泄放特效

不新增 RenderEntity 组件：泄放走 `spawnEmpArc`（原版 EMP 电弧视觉，冷蓝白双色参数化，thickness 24f 略粗于电荷针刺的 20f 以匹配大槽体量）；贯穿补伤走 `addFloatingDamageText` + 克制火花。均不构成共享文件触碰。

---

## 4. 测试面

### 4.1 单元测试（`src/test/kotlin/cn/kasuminova/astd/combat/effect/arc/`，全部调用真实逻辑，禁止源码 contain）

`HeavyIonPulseTuningTest`（经 `DifficultyTuningImpl.installScaleForTests` 走完整映射链路，对齐 `BountyScalingHullModTest` 先例，`@AfterTest` 清注入）：

1. **五档精确取档**：k_s=1/2/5 下 resolve（非玩家）→ chance = 0.20/0.30/0.60、empMult = 0.75/1.00/2.00、empResistPerStack = 0.01/0.02/0.05（±1e-6）。
2. **玩家固定 v2**：k_s=1 与 k_s=5 下 resolve(isPlayer=true) → 三项恒为 v2。
3. **非整数 k_s 就近取档**：k_s=3.4 → 取 v3（不插值）；k_s=3.5 → half-up 进位取 v4。

`HeavyIonPulsePierceTest`（纯函数 `empPierceExtra`，贯穿补伤三档——90 计划 §2.5 指定）：

4. **mult < 0.1**：emp=750、mult=0.05 → 375（= 750 × (0.1−0.05)/0.1，±1e-6）。
5. **mult = 0.1**：→ 0（边界不补）。
6. **mult > 0.1**（如 0.6、1.0）：→ 0。
7. **mult = 0（0 值防线）**：emp=750 → 750（公式自然退化 ×1.0，无除零、不静默恒零）。
8. **mult 无限接近 0.1 下方**（0.099f）：> 0 且 ≈ emp×0.01。

`HeavyIonPulseEmpResistStacksMathTest`（2026-09 新增，`MutableStat` 真对象 + mock Ship/引擎）：

9. **绝对位移幂等**：注入他源 modifier 后连调 `advance` → `empDamageTakenMult.modifiedValue` 恒为 `他源终值 + 层数 × perStack`（不叠乘、不累加）；`onRemove` 后回他源终值。
10. **层数上限与消散**：40 层 clamp；`advance(0.5f)` 恰 -0.5 层；归零经 host 移除且无 stat 残留。
11. **0 乘区防线**：他源终值 ≤ 0（完全 EMP 免疫）时不写 stat，层数照常累积/消散。

`HeavyIonPulseDischargeTest`：

12. **泄放判定边界**：`shouldDischarge(roll, chance)`——chance=0 恒 false；chance=0.5 时 roll=0.499 true；roll == chance 边界 false（`<` 口径）；roll 由固定 seed 的 `CombatRandom` 序列喂入（共享基建件，此处只断言映射，不重复测基建）。

`HeavyIonPulseVfxRegistrationTest`（真实调用管线入口）：

13. **VfxSpec 登记**：`ProjectileVfxSpecs.has/build("astd_heavy_ion_pulse_shot")` 非空，build 执行 DSL 不抛异常。

### 4.2 烟测检查点（`deployMod` + `launchSmokeTestGame`，到达终态即退出游戏）

1. dev 仓储出现武器 + 蓝图；学习蓝图后 refit 大能量槽可装配；名称/tip/定位字符串全中文无键名泄漏。
2. 弹匣节奏（90 计划 §2.5 指定项）：满匣 24 发可持续倾泻约 4.2s（4 连发 0.1s/发 + 射击冷却 0.4s，约 5.7 发/s）；双炮管交替出膛（muzzle flash 左右交替）。
3. 命中船体：可见冷蓝白 EMP 电弧连向武器/引擎部位、武器瘫痪火花；v2 档触发频率符合 30% 体感；命中护盾无电弧（EMP 对盾无效）。
4. 难度隔离：LunaLib k_s=5 下玩家持有仍 30%/100%；敌版 60%/200%（2026-09 修订：五档查表，旧 31.25%/125% 与 50%/200% 作废）。
5. EMP 贯穿：k_s=5 下对高 EMP 抗性敌舰（dev 手段造 mult < 0.1 目标）命中出现补伤浮字与火花；k_s=2 下同目标无浮字；玩家受击时（敌版破晓武器打玩家高抗性舰）浮字可见。
6. **待验证项现场核对**：对 mult ≈ 0 目标观察补伤浮字数值是否被二次减免（§2.5 待验证项结论记录）。
7. 弹体 VFX：冷蓝白 texTrail 弹体 + zappy 电弧副带，无原版弹体残留、无拖尾错位；泄放电弧冷蓝白。
8. devMode FPS：持续命中下无明显掉帧。

---

## 5. 并行实装注意

### 5.1 触碰的共享文件（按 00 §3 合并协议）

| 共享文件 | 本组键名空间 / 追加位置 |
|---|---|
| `ss-csv/.../i18n/zh-cn.properties` | `weapon.astd_heavy_ion_pulse.*`、`desc.astd_heavy_ion_pulse.*`；全部集中在文件末尾，不插中间、不动其他武器键 |
| `ss-csv/.../strings/Catalog_Descriptions.kt` | WEAPON 分组尾部一行；与 01 同批合入时保持 id 字典序（`Desc_astd_charge_needle`… 之后、`Desc_astd_psi_omega` 位置由收口人归位） |
| `ss-csv/.../weapondata/arc/Catalog_WeaponData_ARC.kt` | 文件末尾一个 object；number 段位 **9212**（预分配，不与他组冲突） |
| `contents/data/campaign/special_items.csv` | 文件末尾一行；`order` 段位 **9207**（原版强制数字，§1.5 口径） |
| `src/.../renderer/projectile/driver/ProjectileVfxSpecs.kt` | builders map 末尾一条；`heavyIonPulsePalette()` 内联字面量（不新增共享调色板）；构建函数追加在调色板函数前 |

不触碰 `contents/data/strings/strings.json` 的原口径已作废（2026-09 修订）：本件新增目标侧 EMP 抗性削减叠层，HUD 文案登记 `ui.heavy_ion_pulse.status.*` 四键（title / desc / victim_title / victim_desc），按 00 §3 归位。

新增无冲突文件：`contents/data/weapons/astd_heavy_ion_pulse.wpn`；`src/.../combat/effect/arc/HeavyIonPulse*.kt` ×5（含 `HeavyIonPulseEmpResistStacks.kt`）；测试目录同包。

### 5.2 对共享基建的依赖（只依赖、不改签名）

- **CombatRandom**（00 §4.1）：泄放概率结算随机——硬阻塞，随 Buff API 同 PR 落地即可。
- **Buff API**（00 §1）：`Buff` / `BuffHost` / `BuffAccess`（Weapon 级复合键标记 Buff 承载 callIndex）＋ **`StackableBuff` / `BuffTickPlugin`**（2026-09 新增：目标侧抗性削减叠层走 Ship 级 HOST_BOUND Buff，含 CONTINUOUS 衰减与心跳回收）——硬阻塞。
- **HUD 通道**（00 §4.2）：`addFloatingDamageText`（贯穿浮字）＋ `maintainPlayerStatus`（抗性削减状态条目）已核实签名，无代码依赖。
- 不需要 ConeImpactHandler。

### 5.3 实现顺序内的位置

90 计划 §12 第 2 位（复用电荷针刺 OnHit 模式，新增 EMP 贯穿补伤）——**01 电荷针刺合入 main 后开工**，四件类结构直接对照移植，差异仅在数值表、贯穿分支与 VFX 旋钮。组内顺序：Tuning/纯函数 + 单测 → OnHit + Shots + Vfx → 数据面（ss-csv 生成 + .wpn + i18n + 蓝图）→ VfxSpec 登记 → 烟测（含 §2.5 待验证项核对）。

---

## 6. 验收要点（主代理逐项核对）

**数据面**

- [ ] `Catalog_WeaponData_ARC.kt` object 逐列与本规格 §1.1 一致；number = 9212；`generateSsCsv` 后 `weapon_data.csv` 出现该行（ammo 24/1.6/8、burst 4/0.1、chargedown 0.4、emp 500、energy 275/0、OPs 28、range 800、tags `astd_production`）。
- [ ] `.proj`：`onHitEffect` 指向 `HeavyIonPulseOnHitEffect`，`onFireEffect` 指向 dispatcher，原版弹体隐藏四件套齐全（length/width=2、双色 alpha=0、BUtil_NONE、fadeTime=0.2）。
- [ ] `.wpn`：size LARGE、**双炮管坐标 + ALTERNATING**、projectileSpecId 正确、everyFrameEffect 挂载、sound `ion_pulser_fire`。
- [ ] zh-cn.properties 键齐全且 tip 为设计案定稿原文；desc.text1/notes 已评审确认。
- [ ] special_items.csv 一行 params = `astd_heavy_ion_pulse`（裸 id，无前缀），order = 9207；描述内引号为弯引号。

**代码面**

- [ ] 无 `XxxService/Manager/Controller/Runtime` 类名；无反射；无空 catch；无占位实现。
- [ ] 泄放随机走 `CombatRandom`，同帧不二次取值；callIndex 走 Weapon 级复合键标记 Buff，未自建 customData 状态表。
- [ ] 玩家固定 v2 取值在 OnHit 每次命中处调用（非缓存）；EMP 贯穿激活条件为 `fixedScale >= 5f && !isPlayer`，v1/v2 无此特效。
- [ ] 贯穿补伤走 `applyDamage`（无二次 onHit 回环）+ `addFloatingDamageText`（反馈铁律落点）；`empPierceExtra` 三档与 mult=0 防线落地。
- [ ] `baseEmp ≤ 0` 配置异常 WARN 一次/武器 id；抗性削减走 Ship 级 `StackableBuff`（幂等绝对位移、上限 40、1 层/s 消散、`onRemove` unmodify、完全 EMP 免疫时跳过写 stat）。
- [ ] 玩家固定 v2：查表项取 `v2`、`ScaleEntry` 项走 `tuning.value(entry)`；`hullSize` 分档只在减针族使用（本件不涉及）。

**特效面**

- [ ] builders 一条登记 + 内联调色板（未新增共享 palette）；texTrail 冷蓝白 width=12/length=220、ribbon 电弧副带。
- [ ] 泄放 `spawnEmpArc` 冷蓝白 thickness=24f；贯穿火花克制量级。

**测试面**

- [ ] §4.1 十三条用例全部存在且调用真实逻辑（无源码 contain）；贯穿三档（<0.1 / =0.1 / >0.1）与 mult=0 防线为必查项；抗性削减叠层 math test（幂等/上限/0 乘区）必查。
- [ ] §4.2 烟测八项全部过检并留目检记录；弹匣节奏与 EMP 触发为 90 计划指定核对项；§2.5 待验证项（追加量二次减免）结论已记录；烟测到达终态即退出游戏。

**合并面**

- [ ] 共享文件全部末尾追加、键名空间内自闭合；number/preset/order 无越界；与 01 同批时 Catalog_Descriptions / zh-cn.properties 按 id 字典序交由收口人归位。
