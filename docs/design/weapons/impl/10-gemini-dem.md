# 双子星 DEM 发射器 / 双子星 DEM 发射舱 实现规格 v1（待评审）

> 依据：`docs/design/weapons/90-首批实装计划.md` v6 §10、§11、§13；`docs/design/weapons/blue/20-production.md`「双子星 DEM 发射器 / 双子星 DEM 发射舱」（已定案 v1.0，2026-07-28）；`docs/design/weapons/impl/00-共享基建.md` v1 合并协议。
> 状态：规划文档，不改动 `src/` 与 `ss-csv/`。
> API 核查时间：2026-07-29，对照 `starfarer.api.jar`（0.98）反编译字节码、原版 `dragon*.wpn/.proj/weapon_data.csv` 与本仓库现有代码逐条核实。

---

## 0. API 核查结论与架构裁定（与首批计划 §10.2 的偏差必须先读）

### 0.1 已核实的 API 事实

| # | 事实 | 证据 |
|---|---|---|
| 1 | 原版龙炎 DEM 三件套结构：主武器 `dragon.wpn`（MISSILE 发射架）→ 导弹 `dragon.proj`（`behaviorSpec.behavior=CUSTOM` + `onFireEffect=DEMEffect` + `payloadWeaponId=dragon_payload`）→ payload 光束 `dragon_payload.wpn`（specClass=beam，伤害行走 weapon_data.csv 行） | 原版文件 |
| 2 | `com.fs.starfarer.api.impl.combat.dem.DEMScript` 是 **api jar 公开类**，继承 `BaseEveryFrameCombatPlugin` 并实现 `MissileAIPlugin`；构造签名 `DEMScript(MissileAPI, ShipAPI, WeaponAPI)` | javap |
| 3 | `DEMEffect.onFire` 的全部逻辑 = `new DEMScript(missile, weapon.getShip(), weapon)` + `engine.addPlugin(...)`，无其他副作用 | DEMEffect 字节码 |
| 4 | DEMScript 构造器的行为参数**全部**读自 `missile.getSpec().getBehaviorJSON()`（即弹头 .proj 的 behaviorSpec 块）；构造参数 `weapon` 只存字段，advance 全程未读取（字节码无对应 getfield） | DEMScript 字节码 |
| 5 | DEMScript 的 WAIT 段（追踪→触发判定）通过 `((GuidedMissileAI) missile.getAI()).getTarget()` 取目标——**弹头导弹在触发前必须持有一个 `GuidedMissileAI` 且 target 非空**，否则永不触发（不 NPE，静默不触发）。**观测注意（2026-07-29 实机发现）**：脚本侧 `missile.getAI()`/`getMissileAI()` 读回为引擎包装实例（非 GuidedMissileAI），真实 AI 只在 `getUnwrappedMissileAI()` 读回——验证读数必须走 unwrapped 通道 | DEMScript 字节码（`instanceof GuidedMissileAI` 分支）+ 烟测实机判例 |
| 6 | DEMScript 触发时自行 `missile.setMissileAI(this)` 接管后续（锁定 → 充能 → 光束打击），并把 `maxFlightTime` 拉到 10000（打击段不受 flightTime 限制）；打击完毕/导弹失效时自行 `removePlugin` | DEMScript 字节码 |
| 7 | **不存在**让脚本手动驱动导弹发射「真实结算光束」的公开 API（光束只能由武器/beam 行为产生）。因此「追踪 → 锁定 → 充能 → 光束打击」的打击段**必须复用 DEMScript**，自实现全套 AI 不可行 | jar 全表 |
| 8 | `OnFireEffectPlugin.onFire(DamagingProjectileAPI, WeaponAPI, CombatEngineAPI)`；导弹 .proj 的 onFireEffect 在发射时触发（dragon.proj 挂 DEMEffect 为先例） | javap + 原版 |
| 9 | `BeamEffectPlugin.advance(float, CombatEngineAPI, BeamAPI)`；`BeamAPI.didDamageThisFrame()/getDamageTarget()/getSource()/getTo()/getWeapon()` 均存在 | javap |
| 10 | `CombatEngineAPI.spawnProjectile(ShipAPI, WeaponAPI, String weaponId, Vector2f, float angle, Vector2f vel)` 六参版本存在，返回 `CombatEntityAPI`（导弹武器 id 时实为 `MissileAPI`） | javap + `ASTDVirtualParticleLatticeWebHullMod.spawnPursuitMote` 在用 |
| 11 | `MissileAPI.setMissileAI/getAI/getUnwrappedMissileAI/setArmingTime/setSource/giveCommand/isArmed/getSpec` 存在；`GuidedMissileAI` 接口仅 `getTarget()/setTarget(CombatEntityAPI)`；**观测读数走 `getUnwrappedMissileAI()`**（`getAI()` 读回引擎包装实例，2026-07-29 实机判例） | javap + 烟测实机判例 |
| 12 | `ShipAPI.getShipTarget()/isFighter/isDrone/isHulk/isAlive` 存在；`engine.getTotalElapsedTime(boolean)` 存在 | javap + 共享基建 §0 |
| 13 | ss-csv 条目由 ClassGraph 扫描自动收集（`SsCsvGenerator.scanEntries`），**无集中注册表**，新增 object 即生效 | 生成器源码 |
| 14 | ss-csv `MissileProjSpec` **无 `behaviorSpec` / `explosionSpec` 字段**，需扩展（见 §1.2） | `ProjMissileSpec.kt` 源码 |
| 15 | `spawnEmpArc(ShipAPI, Vector2f, CombatEntityAPI anchor, CombatEntityAPI target, DamageType, dam, emp, maxRange, soundId, thickness, fringe, core)` 存在（原版 EMP 电弧自动索敌武器/引擎） | javap |
| 16 | dev 投放链路把 `weapon_bp` / `single_bp` 视为「params 必填」项（`ASTDDevContentSelector`），params 列即武器 id | 现有代码 |

### 0.2 架构裁定（对首批计划 §10.2 的三处修正）

1. **打击段复用原版 DEMScript，删除 `GeminiDemMissileAI` 全量自定义 AI 方案**（计划中的主方案）。依据事实 #7：无反射路径下自实现 AI 无法让导弹打出真实结算光束；DEMScript 是公开类且行为参数完全数据驱动（事实 #4）。自定义面收缩为**追踪段** `GeminiDemTrackAI`（实现 `MissileAIPlugin + GuidedMissileAI`，为事实 #5 的 WAIT 触发供目标）。计划中的「回退方案」升格为主路径。
2. **齐射批次号不作为同步判定的主键**。同步判定键 = 目标舰身份 + 异种弹头配对 + ≤1s 窗口 + 同源判定（可判时）。理由：12s 开火间隔 ≫ 1s 窗口，同一发射舰不可能有两批齐射落入同一窗口，批次号对判定无增量信息；批次号仍写入 `missile.customData["astd_gemini_salvo"]`，仅作日志/调试关联。
3. **number 段位扩展占用 9225/9226 + 9235/9236**：合并协议为双子星预分配 9221~9224（主×2 + 弹头×2），实际还需两件 payload 光束隐藏武器行（9225/9226）与两件异色锁定激光隐藏武器行（9235/9236，2026-09-26 落地）。均在首批 9210~9229 池外沿（9235/9236 超池，后续无其他组占用），请收口人确认。

### 0.3 机制总览（实现形态）

```
主武器（发射架，spec ammo 4；发射舱 8）──发射──► dummy 导弹（astd_gemini_dem_dummy）
    │ onFireEffect: GeminiDemSalvoOnFireEffect（同帧）
    ├─ 移除 dummy
    ├─ 取目标：ship.shipTarget → 空则 2500su 内最近敌舰 → 仍空则双弹直飞
    ├─ spawnProjectile ×2（动能弹头 + 高爆弹头，±12su 垂直错位、±2° 朝向散布）
    ├─ 每枚弹头：source=发射舰、setArmingTime(0.3)、missileAI=GeminiDemTrackAI(target)
    │            customData["astd_gemini_salvo"]=批次号、addPlugin(DEMScript(missile, ship, weapon))
    ▼
弹头飞行：TrackAI 追踪（GuidedMissileAI.getTarget 供 DEM WAIT 段读取）
    │ 进入触发距离（700~750su）且已 armed
    ▼
DEMScript 接管：转向 → 锁定激光（targetingTime 2s，异色：动能蓝白/高爆共振红）→ payload 光束打击（firingTime 1s）
    │ payload 光束 = astd_gemini_dem_kinetic_payload / astd_gemini_dem_he_payload
    │ 伤害：payload 行 damage/second × burstSize(1s) = 1250 动能 / 1250 高爆（demDrone 挂载结算）
    ▼
GeminiDemPayloadBeamEffect（beamEffect）：
    ├─ 动能光束：照射期每 0.1s 一道 EMP 电弧（spawnEmpArc，索敌武器/引擎；单道 EMP = 面板×10%，合计 1250）
    └─ 两光束均：首伤帧命中登记到 GeminiDemSyncHandler
                    │ 同目标 + 异种弹头 + |Δt| ≤ 1s + 同源（可判时）
                    ▼
              同步共振（2026-09-26 重做：弃一次性爆发伤害）：双弹 demDrone energyWeaponDamageMult ×(1+难度加成)
              （+50%/+100%/+250%）覆盖剩余照射伤害 + 光束渐变转紫（仅视觉）+ 白闪反馈
```

---

## 1. 数据面

### 1.1 ss-csv catalog 条目（8 件，全部落 `Catalog_WeaponData_ARC.kt` 文件末尾；含两件异色锁定激光隐藏行 9235/9236）

| 列 | Wpn_astd_gemini_dem_launcher | Wpn_astd_gemini_dem_pod |
|---|---|---|
| id | `astd_gemini_dem_launcher` | `astd_gemini_dem_pod` |
| tier / rarity | 2 / 1（提案待裁定，对标龙炎 tier 2） | 2 / 1 |
| base value | 6000（提案待裁定） | 14000（提案待裁定） |
| range | 2500 | 2500 |
| damage/second | 0（留空；非持续武器） | 0 |
| damage/shot | 1250（单弹面板；双管齐射面板自动显示 1250 x2） | 1250 |
| emp | 1250（0.1s 电弧 ×10 道合计，展示/AI 口径） | 1250 |
| turn rate | 30 | 30 |
| OPs | 14 | 28 |
| ammo / ammo/sec / reload size | 4 / 0.0334 / 2 | 8 / 0.0667 / 2 |
| type（伤害类型列，展示用） | ENERGY（对齐龙炎显示惯例） | ENERGY |
| chargedown | 12 | 12 |
| proj speed | 225（龙炎 150 的 150%） | 225 |
| flight time | 14（提案，2500su÷225≈11.1s 上浮；烟测校正） | 14 |
| proj hitpoints | 600 | 600 |
| tags | `astd_production` | `astd_production` |
| groupTag | `astd` | `astd` |
| tech/manufacturer | `弧光阵列` | `弧光阵列` |
| primaryRoleStr | `SsI18n.t("weapon.$id.primaryRoleStr")` | 同左 |
| customPrimary | `SsI18n.t("weapon.$id.tooltip.customPrimary")` | 同左 |
| customPrimaryHL | `SsI18n.t("weapon.$id.tooltip.customPrimaryHL")`（高亮数值与"难度系数"） | 同左 |
| number | **9221** | **9222** |
| proj 输出 | 实现 `SsProjMissileOutputs`，输出 dummy 导弹 spec（§1.3） | **不实现**（.wpn 复用同一 dummy projectileSpecId） |

| 列 | Wpn_astd_gemini_dem_kinetic | Wpn_astd_gemini_dem_he |
|---|---|---|
| id | `astd_gemini_dem_kinetic` | `astd_gemini_dem_he` |
| tier / base value | 2 / 0 | 2 / 0 |
| range | 2500（AI/展示） | 2500 |
| damage/shot | 1250（展示口径；真实伤害由 payload 行结算） | 1250 |
| emp | 1250（展示口径） | 0 |
| turn rate | 30 | 30 |
| OPs | 0（永不装配） | 0 |
| type（伤害类型列） | KINETIC | HIGH_EXPLOSIVE |
| proj speed / flight time / proj hitpoints | 225 / 14 / 600 | 225 / 14 / 600 |
| tags | `no_drop, no_drop_salvage` | `no_drop, no_drop_salvage` |
| tech/manufacturer | `弧光阵列` | `弧光阵列` |
| noDPSInTooltip | TRUE | TRUE |
| number | **9223** | **9224** |
| proj 输出 | 实现 `SsProjMissileOutputs`，输出动能弹头导弹 spec（含 behaviorSpec，§1.2/§1.3） | 同左（高爆弹头） |

| 列 | Wpn_astd_gemini_dem_kinetic_payload | Wpn_astd_gemini_dem_he_payload |
|---|---|---|
| id | `astd_gemini_dem_kinetic_payload` | `astd_gemini_dem_he_payload` |
| tier / base value | 2 / 0 | 2 / 0 |
| damage/second | **1250**（结算口径：dps × burstSize 1s = 1250/发） | **1250** |
| damage/shot | 0（留空，beam 行惯例） | 0 |
| type（伤害类型列） | KINETIC | HIGH_EXPLOSIVE |
| burst size / burst delay | 1 / 0（单次 1s 照射） | 1 / 0 |
| beam speed | 1000000（对齐 dragon_payload） | 1000000 |
| range | 1000（对齐 dragon_payload 判例；射程列缺失时光束无法够到触发距离外的目标） | 1000 |
| hints | `SYSTEM, DANGEROUS`（AiHint 枚举两项均已存在） | `SYSTEM, DANGEROUS` |
| tags | `fires_one_burst, no_drop, no_drop_salvage` | `fires_one_burst, no_drop, no_drop_salvage` |
| tech/manufacturer | `弧光阵列` | `弧光阵列` |
| noDPSInTooltip | TRUE | TRUE |
| number | **9225** | **9226** |
| proj 输出 | 不实现（beam 无 .proj） | 不实现 |

注意：`WeaponDataEntry` 无 `size` 字段——槽位尺寸在 .wpn JSON 的 `size` 字段（§1.3），不在 weapon_data.csv。

### 1.2 ss-csv 公共扩展（共享文件，按合并协议在 PR 中单独提出）

`ss-csv/src/main/kotlin/cn/kasuminova/astd/sscsv/outputs/proj/ProjMissileSpec.kt` 的 `MissileProjSpec` 增加：

```kotlin
/** 导弹行为声明块（原版 behaviorSpec 原样透传；DEM/MIRV 等行为必需）。 */
val behaviorSpec: Map<String, Any?>? = null,
```

`toJson()` 在 `missileType` 之后输出 `"behaviorSpec" to behaviorSpec`（null 时滤除）。值含嵌套数组/浮点/布尔/字符串，实现时先确认 `GeneratedJsonFile` 的 JSON writer 支持嵌套 `Map/List/Boolean/Double`（不支持则在同一 PR 内补齐 writer 能力）。`explosionSpec` 本组 v1 不用，不扩展。

### 1.3 .proj 生成要点（ss-csv 输出，3 件）

**dummy（`astd_gemini_dem_dummy`，由 launcher 条目输出一次，pod 复用）：**

```kotlin
MissileProjSpec(
    id = "astd_gemini_dem_dummy",
    missileType = "MISSILE",
    onFireEffect = "cn.kasuminova.astd.combat.effect.arc.GeminiDemSalvoOnFireEffect",
    sprite = "graphics/textures/BUtil_NONE.png",
    size = Vec2i(4, 4), center = Vec2(2, 2),
    collisionRadius = 7, collisionClass = "MISSILE_NO_FF",
    explosionColor = Rgba(0, 0, 0, 0), explosionRadius = 0,
    flameoutTime = 0.5, noEngineGlowTime = 999.0, fadeTime = 0.25,
    engineSpec = MissileEngineSpec(turnAcc = 1800, turnRate = 1440, acc = 1800, dec = 1600),
    engineSlots = emptyList(),
)
```

dummy 在 onFire 同帧被移除，以上数值只保证「发射即拦截」不出异常。

**动能弹头（`astd_gemini_dem_kinetic_msl`）/ 高爆弹头（`astd_gemini_dem_he_msl`）：** 两 spec 结构相同，差异在 payloadWeaponId 与配色（下表以动能为例，高爆差异列在括号内）：

```kotlin
MissileProjSpec(
    id = "astd_gemini_dem_kinetic_msl",          // 高爆：astd_gemini_dem_he_msl
    missileType = "MISSILE",
    onFireEffect = null,                          // 单一路径：DEMScript 由 SalvoOnFireEffect 手动挂载（事实 #3/#4）
    sprite = "graphics/weapons/astd_gemini_dem_missile.png",  // 2026-09-26：改用 ASTD 导弹本体贴图（与 dummy 装填渲染一致），不再引用龙炎
    size = Vec2i(12, 25), center = Vec2(6, 12.5),
    collisionRadius = 7, collisionClass = "MISSILE_NO_FF",
    explosionColor = Rgba(140, 190, 255, 180),    // 高爆：Rgba(255, 180, 110, 180)
    explosionRadius = 50,
    armingTime = 0.3,
    flameoutTime = 0.5, noEngineGlowTime = 0.0, fadeTime = 0.25,
    engineSpec = MissileEngineSpec(turnAcc = 225, turnRate = 75, acc = 600, dec = 105),  // 龙炎 ×1.5
    engineSlots = listOf(
        MissileEngineSlot(
            id = "ES1", loc = Vec2i(-13, 0), style = "CUSTOM",
            styleSpec = MissileEngineSlotStyleSpec(
                mode = "QUAD_STRIP",
                engineColor = Rgba(140, 190, 255, 255),     // 动能冷蓝白；高爆：Rgba(255, 190, 130, 255)
                glowSizeMult = 2.5, contrailDuration = 1.0,
                contrailWidthMult = 1.0, contrailWidthAddedFractionAtEnd = 2.5,
                contrailMinSeg = 5, contrailMaxSpeedMult = 0.5, contrailAngularVelocityMult = 0.5,
                contrailSpawnDistMult = 1.0,
                contrailColor = Rgba(120, 170, 255, 75),    // 高爆：Rgba(255, 60, 70, 75)（2026-09-26 共振红族）
                type = "GLOW",
            ),
            width = 7.0, length = 40.0, angle = 180.0,
        ),
    ),
    behaviorSpec = mapOf(                           // §1.2 扩展字段；键名含原版拼写，逐字照抄
        "behavior" to "CUSTOM",
        "minDelayBeforeTriggering" to 0.5,
        "triggerDistance" to listOf(700, 750),
        "preferredMinFireDistance" to listOf(700, 750),
        "turnRateBoost" to 100,
        "targetingTime" to 2,                       // 提案：龙炎为 3，设计「短暂充能」收紧到 2；烟测目检
        "firingTime" to 1,
        "targetingLaserId" to "astd_gemini_dem_targetinglaser_kinetic",  // 高爆：astd_gemini_dem_targetinglaser_he（异色锁定激光，2026-09-26 落地：结构照原版 targetinglaser3，动能蓝白/高爆共振红）
        "targetingLaserFireOffset" to listOf(8, 0, 8, 0),
        "targetingLaserSweepAngles" to listOf(0, -7, 0, 7),
        "payloadWeaponId" to "astd_gemini_dem_kinetic_payload",  // 高爆：astd_gemini_dem_he_payload
        "targetingLaserRange" to 900,
        "targetingLaserArc" to 10,
        "bombPumped" to true,
        "fadeOutEngineWhenFiring" to false,
        "destroyMissleWhenDoneFiring" to false,     // 原版键名拼写即为 Missle，逐字照抄
        "snapFacingToTargetIfCloseEnough" to false,
    ),
)
```

### 1.4 .wpn JSON 骨架（手写，6 件，落 `contents/data/weapons/`）

**`astd_gemini_dem_launcher.wpn` / `astd_gemini_dem_pod.wpn`（差异：size 与贴图）：**

```json
{
    "id": "astd_gemini_dem_launcher",
    "specClass": "projectile",
    "type": "MISSILE",
    "size": "MEDIUM",                          "pod 为 LARGE",
    "turretSprite": "graphics/weapons/dragonfire_rack_med_turret.png",
    "hardpointSprite": "graphics/weapons/dragonfire_rack_med_hardpoint.png",
    "hardpointOffsets": [8, 0],                "pod: [14, 0]",
    "turretOffsets": [3, 0],                   "pod: [8, 0]",
    "hardpointAngleOffsets": [0],
    "turretAngleOffsets": [0],
    "barrelMode": "LINKED",
    "animationType": "SMOKE",
    "projectileSpecId": "astd_gemini_dem_dummy",
    "fireSoundTwo": "dragonfire_fire"
}
```

发射架贴图为 v1 资源选型（引用原版龙炎发射架；pod 用 `dragonfire_launcher_lrg_*`，已对 dragonpod.wpn 核实）。单管 LINKED：一次触发只出一枚 dummy，双弹由脚本生成。**弹药口径（2026-09-26 裁定）**：一轮齐射消耗 2 弹药——引擎只扣 dummy 的 1 枚，`GeminiDemSalvoOnFireEffect` 在 onFire 回调内补扣第 2 枚（原版 MissileWeapon.fireShot 先扣弹药再回调 onFire，时序已核实）；装填量恒为偶数（发射器 2 / 发射舱 4），一轮回充 2 枚正好补一轮齐射。专用发射架贴图列后续美术任务。

**`astd_gemini_dem_kinetic.wpn` / `astd_gemini_dem_he.wpn`（隐藏弹头武器，永不上架）：**

```json
{
    "id": "astd_gemini_dem_kinetic",           "高爆：astd_gemini_dem_he",
    "specClass": "projectile",
    "type": "MISSILE",
    "size": "MEDIUM",
    "turretSprite": "graphics/textures/BUtil_NONE.png",
    "hardpointSprite": "graphics/textures/BUtil_NONE.png",
    "turretGunSprite": "graphics/textures/BUtil_NONE.png",
    "hardpointGunSprite": "graphics/textures/BUtil_NONE.png",
    "hardpointOffsets": [0, 0],
    "turretOffsets": [0, 0],
    "hardpointAngleOffsets": [0],
    "turretAngleOffsets": [0],
    "barrelMode": "LINKED",
    "projectileSpecId": "astd_gemini_dem_kinetic_msl"   "高爆：astd_gemini_dem_he_msl"
}
```

**`astd_gemini_dem_kinetic_payload.wpn` / `astd_gemini_dem_he_payload.wpn`（payload 光束，结构照 dragon_payload.wpn）：**

```json
{
    "specClass": "beam",
    "id": "astd_gemini_dem_kinetic_payload",   "高爆：astd_gemini_dem_he_payload",
    "type": "ENERGY",
    "size": "LARGE",
    "collisionClass": "RAY_FIGHTER",
    "collisionClassByFighter": "RAY_FIGHTER",
    "turretSprite": "graphics/fx/empty.png",
    "turretGlowSprite": "graphics/fx/empty.png",
    "hardpointSprite": "graphics/fx/empty.png",
    "hardpointGlowSprite": "graphics/fx/empty.png",
    "turretOffsets": [8, 0],
    "turretAngleOffsets": [0],
    "hardpointOffsets": [8, 0],
    "hardpointAngleOffsets": [0],
    "glowColor": [140, 200, 255, 255],         "动能冷蓝白；高爆：[255, 120, 130, 255]",
    "fringeColor": [120, 180, 255, 225],       "高爆：[255, 40, 60, 225]",
    "coreColor": [220, 240, 255, 255],         "高爆：[255, 150, 150, 255]",
    "beamEffect": "cn.kasuminova.astd.combat.effect.arc.GeminiDemPayloadBeamEffect",
    "everyFrameEffect": "cn.kasuminova.astd.combat.effect.arc.GeminiDemPayloadBeamVfx",   "2026-09-26：BoxUtil 光束实体接管渲染",
    "hitGlowBrightenDuration": 0,
    "hitGlowRadius": 350,
    "width": 30.0,
    "textureType": "LASER",
    "textureScrollSpeed": 64.0,
    "pixelsPerTexel": 5.0,
    "pierceSet": ["PROJECTILE_FF", "PROJECTILE_NO_FF", "PROJECTILE_FIGHTER", "MISSILE_FF", "MISSILE_NO_FF", "FIGHTER", "ASTEROID"],
    "playFullFireSoundOne": true,
    "fireSoundOne": "dragonfire_payload_fire"
}
```

插件挂载点汇总：主武器 → dummy .proj 的 `onFireEffect`（唯一入口）；弹头 → 无插件（DEMScript 脚本挂载）；payload 光束 → `.wpn` 的 `beamEffect`（命中逻辑 + 隐藏原版束体渲染）与 `everyFrameEffect`（BoxUtil 光束实体自绘，动能 astd_trails_zappy / 高爆 astd_trails_flow，含出现 ramp-in 与停火消散的透明度/宽度过渡）。

### 1.5 i18n 键清单（`ss-csv/src/main/resources/i18n/zh-cn.properties` 文件末尾集中追加）

```properties
weapon.astd_gemini_dem_launcher.name=双子星 DEM 发射器
weapon.astd_gemini_dem_pod.name=双子星 DEM 发射舱
weapon.astd_gemini_dem_kinetic.name=双子星 DEM 动能弹头（隐藏）
weapon.astd_gemini_dem_he.name=双子星 DEM 高爆弹头（隐藏）
weapon.astd_gemini_dem_kinetic_payload.name=双子星 DEM 动能光束（隐藏）
weapon.astd_gemini_dem_he_payload.name=双子星 DEM 高爆光束（隐藏）
weapon.astd_gemini_dem_targetinglaser_kinetic.name=双子星 DEM 锁定激光 - 蓝（隐藏）
weapon.astd_gemini_dem_targetinglaser_he.name=双子星 DEM 锁定激光 - 红（隐藏）
weapon.astd_gemini_dem_launcher.primaryRoleStr=终结打击
weapon.astd_gemini_dem_pod.primaryRoleStr=终结打击
weapon.astd_gemini_dem_launcher.tooltip.customPrimary=发射两枚异色导弹，导弹接近目标会发射高功率激光，蓝色导弹造成与面板等额的动能伤害和打击武器与引擎的 EMP 电弧伤害；红色导弹造成与面板等额的高爆伤害。当两种类型的导弹同时打击目标时，激光造成的伤害提高 {%s}。效果受到{%s}影响。
weapon.astd_gemini_dem_launcher.tooltip.customPrimaryHL=100% | 难度系数
weapon.astd_gemini_dem_pod.tooltip.customPrimary=发射两枚异色导弹，导弹接近目标会发射高功率激光，蓝色导弹造成与面板等额的动能伤害和打击武器与引擎的 EMP 电弧伤害；红色导弹造成与面板等额的高爆伤害。当两种类型的导弹同时打击目标时，激光造成的伤害提高 {%s}。效果受到{%s}影响。
weapon.astd_gemini_dem_pod.tooltip.customPrimaryHL=100% | 难度系数
desc.astd_gemini_dem_launcher.text1=可以认作是龙炎 DEM 的深化改进型，这座由坠星科研部设计的导弹装载了独特的分装式共振等离子束装药，当两枚导弹同时对目标发起打击时，能够引发共振效果，使其造成更具毁灭性的爆发伤害。改进后的发射架还内置了纳米导弹工厂，使用一定的装配开销来提升战场续航。
desc.astd_gemini_dem_launcher.notes=
desc.astd_gemini_dem_pod.text1=（同 launcher 行）
```

- tip = 2026-09-26 机制重做后用户定稿原文 + v2 数值插入（同步增伤 +100%，难度系数）；desc text1 为用户 2026-09-26 重写定稿（勿动），notes 留空。
- `desc.astd_gemini_dem_pod.notes` **不写**：`Desc_astd_gemini_dem_pod` 用 `notesId = "astd_gemini_dem_launcher"` 复用（对齐 `Desc_astd_gcp8` 先例）。
- `desc.text2~text5` 不写（`LocalizedDescription` 对缺键 fallback 为空串，已核实）。
- tip 含 `%` 数值（100%），按 01/02 已修正先例使用半角 `%`，无需全角转义。

`Catalog_Descriptions.kt` WEAPON 分组尾部（`Desc_astd_psi_omega` 之后）追加两行：

```kotlin
object Desc_astd_gemini_dem_launcher : LocalizedDescription("astd_gemini_dem_launcher", "WEAPON")
object Desc_astd_gemini_dem_pod : LocalizedDescription("astd_gemini_dem_pod", "WEAPON", notesId = "astd_gemini_dem_launcher")
```

隐藏六件（弹头×2、payload×2、锁定激光×2）不登记 Desc（对齐原版 dragon_payload 无 desc 先例）。

### 1.6 special_items.csv 条目（文件末尾追加，order 段位 9205/9206）

**order 列口径（2026-07-29 实机发现）**：原版 CSV 解析强制数字，留空启动即 JSONException；各组按合并协议预分配段位直填。

量产两件主武器各一行单件蓝图（params 列即武器 id，已对 dev 投放链路的 param 必填口径核实）：

```csv
双子星 DEM 发射器蓝图,astd_gemini_dem_launcher_bp,"single_bp, astd",弧光阵列,,2000,1000,1,,graphics/icons/cargo/blueprint_weapons.png,ui_chip_pickup,ui_weapon_bp_drop,com.fs.starfarer.api.campaign.impl.items.WeaponBlueprintItemPlugin,astd_gemini_dem_launcher,使重工业设施能够制造出该蓝图所描述的武器。,9205
双子星 DEM 发射舱蓝图,astd_gemini_dem_pod_bp,"single_bp, astd",弧光阵列,,2000,1000,1,,graphics/icons/cargo/blueprint_weapons.png,ui_chip_pickup,ui_weapon_bp_drop,com.fs.starfarer.api.campaign.impl.items.WeaponBlueprintItemPlugin,astd_gemini_dem_pod,使重工业设施能够制造出该蓝图所描述的武器。,9206
```

隐藏六件不出现在任何蓝图/掉落（tags `no_drop, no_drop_salvage` + hints SYSTEM）。

---

## 2. 代码面

### 2.1 类清单表

包：`cn.kasuminova.astd.combat.effect.arc.geminidem`（ARC 线武器机制包的武器组子包；引擎回调类直接实现引擎接口，不另立项目内接口——与既有 `HighFluxShieldPressureOnHitEffect` 等先例一致）。

| 类名 | 形态 | 职责 | 挂载点 | 文件路径 |
|---|---|---|---|---|
| `GeminiDemSalvoOnFireEffect` | class : OnFireEffectPlugin | 拦截 dummy 并移除；定目标；spawn 双弹头并装配 TrackAI + DEMScript；写批次号 | 主武器 dummy .proj 的 `onFireEffect` | `effect/arc/geminidem/GeminiDemSalvoOnFireEffect.kt` |
| `GeminiDemTrackAI` | class : MissileAIPlugin, GuidedMissileAI | 追踪段 AI：转向/加速指令追踪目标；目标失效重搜索；`getTarget()` 供 DEMScript WAIT 段读取 | SalvoOnFireEffect 中 `missile.setMissileAI(...)` | `effect/arc/geminidem/GeminiDemTrackAI.kt` |
| `GeminiDemPayloadBeamEffect` | class : BeamEffectPlugin | payload 光束首伤帧向 SyncHandler 登记命中；动能光束照射期每 0.1s 一道 EMP 电弧（单道 EMP=面板 10%） | 两件 payload .wpn 的 `beamEffect` | `effect/arc/geminidem/GeminiDemPayloadBeamEffect.kt` |
| `GeminiDemPayloadBeamVfx` | class（特效层，2026-09-26 新增） | payload 光束视觉：束体 trail、发射爆发星云、发射点双 Flare、环绕星云、动能装饰电弧、同步紫色渐变 | 由 PayloadBeamEffect 驱动 | `effect/arc/geminidem/GeminiDemPayloadBeamVfx.kt` |
| `GeminiDemRackEveryFrameEffect` | class : EveryFrameWeaponEffectPlugin | 发射舱挂弹视觉：冷却期双管导弹均不渲染（原版只熄已击发管的补足） | launcher/pod .wpn 的 `everyFrameEffect` | `effect/arc/geminidem/GeminiDemRackEveryFrameEffect.kt` |
| `GeminiDemSyncHandler` | object（无状态结算器，对齐 `ConeImpactHandler` 形态） | 同步窗口登记/判定/增伤乘区/紫色视觉标记/反馈 | 由 PayloadBeamEffect 调用 | `effect/arc/geminidem/GeminiDemSyncHandler.kt` |
| `GeminiDemDifficulty` | object 常量持有者（对齐 `ElectricDriveAcceleratorDifficulty` 先例） | 同步增伤 ScalingEntry、面板常量、id 常量 | 被上述类引用 | `effect/arc/geminidem/GeminiDemDifficulty.kt` |

`GeminiDemDifficulty` 常量（2026-09-26 机制重做后口径）：

```kotlin
const val KINETIC_WEAPON_ID = "astd_gemini_dem_kinetic"
const val HE_WEAPON_ID = "astd_gemini_dem_he"
const val KINETIC_PAYLOAD_ID = "astd_gemini_dem_kinetic_payload"
const val HE_PAYLOAD_ID = "astd_gemini_dem_he_payload"
/** 弹头弹体 spec id（拖尾管线登记键）。 */
const val KINETIC_PROJ_ID = "astd_gemini_dem_kinetic_msl"
const val HE_PROJ_ID = "astd_gemini_dem_he_msl"
/** 单弹头面板伤害 = 1250（双弹合计 1250×2）；面板改动须同步 warhead/payload 行。 */
const val WARHEAD_PANEL_DAMAGE = 1250f
const val SYNC_WINDOW_SECONDS = 1f
/** 动能光束照射期 EMP 电弧节律（秒）与单道 EMP = 面板 × 0.1（合计与单弹面板等额）。 */
const val EMP_ARC_INTERVAL = 0.1f
const val EMP_ARC_EMP_FRACTION = 0.1f
/** 同步增伤加成（作用于后续照射伤害）：迟暮 50% / 砺刃 100% / 破晓 250%。 */
val SYNC_DAMAGE_BONUS = ScalingEntry(0.5f, 1.0f, 2.5f, ScalingMap.LINEAR)
/** 增伤乘区 id（写两侧 demDrone 的 energyWeaponDamageMult）。 */
const val SYNC_STAT_MOD_ID = "astd_gemini_dem_sync"
/** 紫色视觉窗口（秒）与实体 customData 键（写在弹头 demDrone 上，非引擎级表）。 */
const val SYNC_VISUAL_DURATION = 1.2f
const val SYNC_VISUAL_KEY = "astd_gemini_dem_sync_visual"
const val TRACK_TARGET_RANGE = 2500f
const val SALVO_LATERAL_OFFSET = 12f
const val SALVO_FACING_SPREAD_DEG = 2f
const val WARHEAD_ARMING_TIME = 0.3f
/** customData 键：批次号（仅日志/调试用，不参与判定）。 */
const val SALVO_KEY = "astd_gemini_salvo"
/** engine.customData 键：同步登记表。 */
const val SYNC_REGISTRY_KEY = "astd_gemini_sync_registry"
```

### 2.2 核心逻辑伪代码

**GeminiDemSalvoOnFireEffect.onFire(projectile, weapon, engine)**（结算顺序固定）：

```
1. ship = weapon.ship；若 ship == null：记 WARN，放行 dummy 正常飞行（不改变 vanilla 路径外的行为）并 return
   —— 正常发射路径 ship 必非空，此分支只兜住异常调用且必须有日志。
2. engine.removeEntity(projectile)  // dummy 同帧移除
3. target = ship.shipTarget?.takeIf { it.isAlive && !it.isHulk && it.owner != ship.owner }
       ?: 2500su 内最近敌舰（遍历 engine.ships：owner 不同、isAlive、!isHulk、!isFighter、!isDrone）
   target 为 null：仍生成双弹（直飞，定义行为，见 §2.4-1），记 DEBUG
4. salvoId = "astd_gemini_salvo:${ship.id}:${engine.getTotalElapsedTime(false)}"
5. facing = projectile.facing；baseLoc = projectile.location
   for ((weaponId, lateralSign) in [(KINETIC_WEAPON_ID, -1f), (HE_WEAPON_ID, +1f)]):
       loc = baseLoc 沿 facing 垂直方向偏移 lateralSign × 12su
       ang = facing + lateralSign × 2°
       vel = ship.velocity 复制
       spawned = engine.spawnProjectile(ship, null, weaponId, loc, ang, vel)
       missile = spawned as? MissileAPI
       missile 为 null：记 ERROR（理论不可达：weaponId 是导弹武器），跳过本枚
       missile.source = ship                      // 确保归功/AI 仇恨
       missile.setArmingTime(0.3f)
       missile.setMissileAI(GeminiDemTrackAI(missile, target))
       missile.customData[SALVO_KEY] = salvoId
       engine.addPlugin(DEMScript(missile, ship, weapon))   // 等价 DEMEffect 全部逻辑（事实 #3）
       ProjectileVfxDriverPlugin.track(engine, missile, projId)   // 拖尾登记（2026-09-26：脚本 spawn 不触发 onFireEffect）
```

**GeminiDemTrackAI**（状态机：`target: ShipAPI?`）：

```
advance(amount):
    engine = Global.getCombatEngine()；engine.isPaused → return
    missile.isFading / isExpired → return
    flightElapsed += amount
    target 失效（null / !isAlive / isHulk / !engine.isEntityInPlay）→
        target = 2500su 内最近敌舰（规则同 Salvo 第 3 步）
    t = target
    t != null：
        // 摆动飞行（2026-09-26 实机裁定）：瞄准点 = 目标位置 + 垂直弹目连线的正弦横向偏移，
        // 幅度 80~160su / 频率 0.5~1.1Hz / 相位随机（每枚弹头构造期抽取，互不同步）
        offset = sin(flightElapsed×2π×freq + phase) × amp
                 × min(flightElapsed/0.6s, 1)        // 起飞渐强
                 × min(dist/500su, 1)                 // 末端收敛（命中段回归直线）
        aim = offset != 0 ? t.location 沿 (弹目方向+90°) 偏移 offset : t.location
        diff = MathUtils.getShortestRotation(missile.facing, VectorUtils.getAngle(missile.location, aim))
        |diff| > 1° → giveCommand(TURN_LEFT/TURN_RIGHT)
    giveCommand(ACCELERATE)   // 有无目标都加速（无目标直飞，直飞不摆动）
getTarget() = target；setTarget(t) { target = t as? ShipAPI }
```

说明：DEMScript 触发后自行 `setMissileAI(this)` 接管（事实 #6），TrackAI 生命周期自然结束，无清理负担。转向只做指令级（不直写 facing/velocity），把机动手感交给弹体引擎参数——与 DEM 段衔接平滑（DEMScript 的 turnRateBoost 在同一引擎参数上加成）。

**GeminiDemPayloadBeamEffect.advance(amount, engine, beam)**（同一类服务两件 payload，靠 weapon spec id 区分弹头种类；2026-09-26 节律重做）：

```
kind = when (beam.weapon.spec.weaponId) { KINETIC_PAYLOAD_ID → KINETIC; HE_PAYLOAD_ID → HE; 其他 → return }
state = perBeam 状态（IdentityHashMap<BeamAPI, BeamState>：firstHitDone / eligible / lastTarget / lastPoint /
      sinceLastDamage / empInterval(0.1s)）；停火且无伤害帧即移除，新一轮打击可重新登记
每帧：
    sinceLastDamage += amount
    beam.didDamageThisFrame() && beam.damageTarget is ShipAPI 时：
        sinceLastDamage = 0
        首伤帧（!firstHitDone）才执行（防 1s 照射期重复登记）：
            1. target = beam.damageTarget as ShipAPI；point = beam.to
               target.isHulk → eligible=false 不登记（记 DEBUG）；isFighter → EMP/同步跳过（光束伤害照常）
               否则 eligible=true，定格 lastTarget/lastPoint
            2. GeminiDemSyncHandler.recordHit(engine, target, kind, point, beam.source)
    kind == KINETIC 且 eligible 且 sinceLastDamage ≤ EMP_GRACE_SECONDS(0.3s)：
        empInterval.advance(amount)（照射期每帧累积——原版 payload 伤害按 ≈4Hz tick 落账，
            didDamageThisFrame 每 ~15 帧才 true 一次，按伤害帧累积会全程归零，2026-09-26 实机诊断）
        每 0.1s 触发：engine.spawnEmpArc(beam.source, lastPoint, lastTarget, lastTarget,
                                         DamageType.ENERGY, 0f, WARHEAD_PANEL_DAMAGE × 0.1(=125), 10000f,
                                         "tachyon_lance_emp_impact", 20f, 冷蓝白 fringe, 白 core)
            （spawnEmpArc 原版行为自动索敌武器/引擎模块——事实 #15；目标变 hulk/离场（!engine.isEntityInPlay）即断电弧）
    PayloadBeamVfx.advance(...)（束体 trail 节点重提交、发射爆发/双 Flare/环绕星云/装饰电弧/紫色渐变）
```

**GeminiDemSyncHandler.recordHit**（结算顺序与难度取值调用点，2026-09-26 重做：弃一次性爆发伤害）：

```
registry = engine.customData.getOrPut(SYNC_REGISTRY_KEY) { mutableMapOf<String, SyncRecord>() }
// SyncRecord(hitTime: Float, kind: WarheadKind, source: CombatEntityAPI?, point: Vector2f)
now = engine.getTotalElapsedTime(false)
prev = registry[target.id]
prev != null 时先惰性过期：now - prev.hitTime > 1s → 视为无记录
触发条件（全部满足）：
    prev 有效 && prev.kind != kind                       // 异种弹头配对
    && (prev.source == null || curSource == null || prev.source == curSource)  // 同源（可判时严格）
触发（无直接伤害结算）：
    bonus = 难度取值：source（当前 beam.source 链条解析到 ShipAPI 且 owner == 0）→ SYNC_DAMAGE_BONUS.v2
                    否则 → difficultyTuning.value(SYNC_DAMAGE_BONUS)
           source 解析不到 → 记 WARN 并取 v2（不静默）
    对两侧配对记录各自的 source as? ShipAPI（即弹头 demDrone）：
        mutableStats.energyWeaponDamageMult.modifyMult(SYNC_STAT_MOD_ID, 1f + bonus)
        // payload 光束由 demDrone 挂载发射且 type=ENERGY → 后续照射伤害 ×(1+bonus)
        drone.customData[SYNC_VISUAL_KEY] = now + SYNC_VISUAL_DURATION   // 紫色视觉窗口
        // （写在实体自身表而非引擎级表：FX drone id 为空串，引擎级表会按 id 碰撞）
    registry.remove(target.id)                            // 触发即清，不重复触发
    反馈（§2.3）：spawnExplosion 白闪（保留）+ 光束紫色渐变（PayloadBeamVfx 读 SYNC_VISUAL_KEY）
未触发：
    registry[target.id] = SyncRecord(now, kind, curSource, point)   // 覆盖为新首击
```

### 2.3 玩家可见反馈（对照实现注意事项 2，逐机制核对）

| 机制 | 反馈通道 | 落点 |
|---|---|---|
| 同步共振（唯一缩放数值机制，2026-09-26 重做） | 双光束渐变转紫（0.25s 升/降，仅视觉）+ 后续照射伤害 ×(1+难度加成) + `spawnExplosion` 白色闪光（保留原审批裁定） | SyncHandler 触发同帧 / PayloadBeamVfx 读 SYNC_VISUAL_KEY |
| 动能弹头 EMP 电弧 | 照射期每 0.1s 一道 `spawnEmpArc`（单道 EMP=面板 10%，自带电弧视觉 + 武器/引擎瘫痪的原版 UI 反馈） | PayloadBeamEffect 照射期节律 |
| 双弹头异色 | 引擎喷流/尾焰（.proj engineSlots）、爆炸色、payload 光束色（.wpn）、锁定激光双色（两件隐藏 beam .wpn）：动能冷蓝白 / 高爆共振红（2026-09-26 裁定，弃暖橙） | 数据面 §1.3/§1.4 |
| 锁定充能过程 | 异色锁定激光（astd_gemini_dem_targetinglaser_kinetic/_he，结构照原版 targetinglaser3，behaviorSpec `targetingLaserId` 参数化直换） | behaviorSpec |
| 光束发射点特效 | 出现瞬间 10 个同色星云（100~200su、偏移 20~40su、零速度、~1s 消散）+ 存续期双 Flare（SMOOTH 圆斑 + SHARP_DISC 垂直光柱） | PayloadBeamVfx |
| 光束存续氛围 | 每 0.2s 冒 2 个同色星云（50~100su、偏移 ≤40su、~0.5s 消散）；动能光束头尾装饰电弧（端点抖动合计 ≤80su） | PayloadBeamVfx |
| HUD 状态栏 | **不设置**：同步是瞬时事件，紫色渐变 + 白闪已覆盖 | — |

### 2.4 0 值与边界处理（对照实现注意事项 3）

| # | 场景 | 定义行为 |
|---|---|---|
| 1 | 发射瞬间无目标（shipTarget 空且 2500su 无敌舰） | 双弹照常生成直飞；TrackAI 只发 ACCELERATE；DEMScript WAIT 段 target 为 null 永不触发（事实 #5）；flightTime 耗尽自毁。记 DEBUG，无伤害、无异常 |
| 2 | 目标在飞行/锁定途中死亡或离场 | TrackAI 重搜索 2500su 敌舰，找不到则直飞自毁；DEM 段目标失效由原版 DEMScript 自带处理 |
| 3 | 一枚弹头被击落/干扰 | 另一枚照常打击；无配对记录 → 无同步共振（设计明文：「击落一枚，同步共振即告落空」） |
| 4 | 同步记录过期 | 仅访问时惰性比较 `Δt > 1s` 覆盖，无每帧扫描；engine.customData 表随战斗结束自然销毁 |
| 5 | 目标为战机 | EMP 电弧与同步登记均跳过（`isFighter` 排除；drone 按舰船计）；光束本身伤害照常（原版结算） |
| 6 | 目标为 hulk | 不登记、不触发同步；记 DEBUG；照射中途目标变 hulk/离场即断电弧 |
| 7 | 来源解析失败（beam.source 链条无 ShipAPI / null） | 记 WARN，难度取 v2（保守）；同源判定在该记录上降级为「可判缺失」（触发规则 §2.2 已写死，非静默） |
| 8 | spawnProjectile 返回非 MissileAPI | 记 ERROR 并跳过该枚（理论不可达；不空 catch，不静默） |
| 9 | 数值反算 | 同步增伤 = 1 + SYNC_DAMAGE_BONUS（纯乘区修饰，无反算、无除零面）；WARHEAD_PANEL_DAMAGE 在 `GeminiDemDifficulty` 注释中与 warhead/payload 行数值双向绑定 |
| 10 | 同一目标被两艘异源舰的弹头 1s 内配对命中 | 双源均可判且不同 → 不触发（同源严格）；任一不可判 → 按规则可触发（§0.2-2 已论证该误触发窗口的实际概率与无害性，并在此显式登记为已知近似） |
| 11 | 一轮齐射弹药消耗 | 实机判例（2026-09-26）：双管 ALTERNATING 挂点单发 onFire 仅一次，但引擎按双管各耗 1 → 一轮稳定 -2（语义对齐「一轮齐射两枚弹头」）；烟测断言按基线差分 -2 |

---

## 3. 特效面

**不登记 `ProjectileVfxSpecs.builders`**（对照共享基建 §4.3 检查表逐项说明）：

| 检查项 | 结论 | 理由 |
|---|---|---|
| 弹体 VFX 登记 | `astd_gemini_dem_kinetic_msl` / `astd_gemini_dem_he_msl` | 2026-09-26（实机裁定）：双弹头接入 Static Trail 拖尾管线（四层惯例，width 5 / 固定带长 250 / recede 0，动能冷蓝白 140,190,255 / 高爆共振红 255,41,61（2026-09-26 裁定弃暖橙，配色锚 .proj 引擎焰色），两弹头另挂 boxFlare 发光组件（SMOOTH，size 24，glow 2.0/4.0）；脚本 spawn 弹体不触发 onFireEffect，由 `GeminiDemSalvoOnFireEffect` 显式 `ProjectileVfxDriverPlugin.track` 登记（冰晶脚本先例）；bolt 组件对 MissileAPI 自动禁用，弹体本体仍走原版导弹贴图渲染 |
| 光束 VFX 登记 | `GeminiDemPayloadBeamVfx` | 2026-09-26 重做：payload 光束 BoxUtil 光束实体自绘（动能 zappy / 高爆 flow 贴图），原版束体隐藏；出现 ramp-in 0.1s、停火消散 0.45s；发射瞬间 10 个同色星云爆发 + 存续期发射点双 Flare（SMOOTH 圆斑 + SHARP_DISC 垂直光柱）+ 每 0.2s×2 环绕星云 + 动能头尾装饰电弧；同步触发时束体/双 Flare 渐变转紫（SYNC_BLEND 0.25s 升降，读 demDrone.customData SYNC_VISUAL_KEY）。三个实机判例：①节点表必须传可变 ArrayList——`TrailEntity._deleteExc`/`resetNodes` 会 `nodeList.clear()`，定长 list 在 delete 时抛 UnsupportedOperationException；②firing 期间必须逐帧重钉 globalTimer FULL 段（KEEPALIVE 保活），否则创建期长 full 兜底导致光束滞留；③**同尺寸节点表再提交必须 `setNodeRefreshAllFromCurrentIndex()` 后再 `submitNodes()`**——BoxUtil TrailEntity.submitNodes 只上传 setNodeRefresh 圈定区间，默认刷新计数为 0 会静默跳过，光束长度/几何随之写死不跟随受击点（2026-09-26 用户实机报告的根因） |
| 爆炸/冲击 | 复用原版 | 弹头爆炸色 .proj 直配（HE 改共振红族，2026-09-26）；同步共振白闪保留 `spawnExplosion`；不上锥面组件（本组无锥状机制） |
| HUD | N/A | §2.3 已说明 |
| i18n | 见 §1.5 | 键清单齐全 |

烟测目检若判定尾焰/光束表现仍不足（对照设计案「异色 DEM」预期），再评估参数面调整——届时单独提出，不属于本规格范围。

---

## 4. 测试面

### 4.1 单元测试用例清单（`src/test/kotlin/cn/kasuminova/astd/combat/effect/arc/`，kotlin.test + 手写记录桩，禁止纯源码 contain）

逻辑可测性设计：`GeminiDemSyncHandler.recordHit` 的引擎依赖收敛为「registry 读写 / now / applyDamage / 反馈」四个注入点（构造传入或默认实参取真实引擎），测试用 fake 直接驱动真实判定逻辑；`GeminiDemTrackAI` 用记录桩 MissileAPI 驱动真实 `advance`。

1. **同步窗口触发**：首击 KINETIC(t=10.0) → 次击 HE(t=10.8)，同目标同源 → 触发一次；断言两侧 demDrone `energyWeaponDamageMult.modifyMult(SYNC_STAT_MOD_ID, 1+bonus)`（玩家 v2=2.0 / 敌 v1=1.5 / v5=3.5）、紫色视觉键写入 missile.customData、无 applyDamage 调用。
2. **恰界 Δt = 1.0s**：触发（≤1s 含边界）。
3. **越界 Δt = 1.1s**：不触发；且次击覆盖为新首击（断言 registry 内容被替换）。
4. **同种弹头配对**（KINETIC→KINETIC）：不触发，记录覆盖。
5. **不同目标**：两目标各自登记，互不触发。
6. **异源可判**：两 source 均非空且不同 → 不触发。
7. **触发后清零**：触发后第三击不再重复触发（须重新配对）。
8. **难度取值**：source.owner==0 → 恒 v2（与 k_s 无关）；敌方桩 → 走注入的 DifficultyTuning fake（v1/v2/v5 三值）；source 为 null → 取 v2 且 WARN 日志被记录（捕获 logger 断言）。
9. **hulk/战机目标**：`isHulk` 或 `isFighter` 目标不登记不触发。
10. **EMP 电弧节律**：动能光束照射期 5 帧 × 0.06s（didDamageThisFrame 间歇 true）→ spawnEmpArc 恰 2 次（0.1s 节律，自末次伤害帧 0.3s 宽限内仍累积——原版 payload 伤害按 ≈4Hz tick 落账，2026-09-26 实机诊断），单道 EMP=125（面板 10%）；高爆光束 0 次；停火且无伤害帧后状态移除，新一轮打击可重新登记。
11. **TrackAI 追踪**：有目标且偏角 >1° → 断言 giveCommand 序列含正确 TURN 方向 + ACCELERATE；目标置失效 → 下一帧重搜索逻辑被调用（注入搜索函数桩），仍无目标 → 仅 ACCELERATE。
12. **批次号写入**：Salvo 流程（fake engine 记录 spawnProjectile 调用）→ 两枚弹头 weaponId 正确（动能/高爆各一）、customData 写入同一 salvoId、TrackAI 与 DEMScript 均被装配、dummy 收到 removeEntity。
13. **生成失败路径**：spawnProjectile 桩返回非 MissileAPI → ERROR 日志 + 另一枚不受影响。

### 4.2 烟测检查点（`deployMod` + `launchSmokeTestGame`，到达终态即退出）

1. 装配：两件主武器可在中/大导弹槽装配，蓝图进 dev 仓储（`AsteriaTestCampaignBootstrap` 投放）。
2. 齐射：一次开火出两枚异色弹头（动能蓝尾焰 / 高爆橙尾焰），dummy 不可见。
3. **DEM 行为生效（最高优先验证项，unwrapped 读回 + payload 命中双证）**：弹头追踪 → 700~750su 触发锁定激光 → 充能 → 光束打击。证据一：`setMissileAI(GeminiDemTrackAI)` 后 **`missile.getUnwrappedMissileAI()`** 读回的 GuidedMissileAI 目标非空（2026-07-29 实机判例：`getAI()`/`getMissileAI()` 读回为引擎包装实例，真实 AI 只在 unwrapped 通道）；证据二：payload 光束实际命中结算（DEMScript 完成接管并走完 FIRE 段的硬证据，见 §5 风险表 R1）。
4. 伤害读数：动能/高爆光束各 ≈1250（面板等额）+ 动能照射期 EMP 电弧 ≈10 道（0.1s 节律 × 1s 照射，断言区间 8..12；断言须在双弹命中后 1.6s 照射期收尾门控之后执行——实机判例：首伤帧即断言时电弧计数尚未累积误报 0）；**payload 实际结算量与面板口径校准**（原版龙炎存在 8000×0.75 vs tip 4000 的未明差异，本组必须读数核对，不符则调 payload 行 damage/second）。
5. 同步共振：双弹同目标命中 Δt≤1s → 双光束渐变转紫 + 白闪 + 后续照射伤害 ×(1+难度加成)（玩家 v2=×2.0 遥测断言）；击落一枚 → 无同步。
6. 隐藏六件（弹头×2、payload×2、锁定激光×2）不出现在 codex 与掉落（dev 仓储 + codex 检索确认）。
7. `beam.getSource()` 归属：日志打印同步触发时的 source/owner，确认玩家舰固定 v2、敌舰走轨一。
8. 12s 开火间隔、spec ammo 4/8 与装填节奏（一轮一耗 2：双管 ALTERNATING 各耗 1，边界表 #11；runtime 受环境 missileAmmoBonus 加成，断言走基线差分）。

---

## 5. 并行实装注意

### 5.1 共享文件清单（按 00-共享基建.md §3 合并协议）

| 共享文件 | 本组改动 | 键名空间 / 位置约定 |
|---|---|---|
| `ss-csv/.../i18n/zh-cn.properties` | §1.5 全部键 | `weapon.astd_gemini_dem_*` / `desc.astd_gemini_dem_*` 天然隔离；文件末尾集中追加 |
| `ss-csv/.../strings/Catalog_Descriptions.kt` | 2 个 Desc object | WEAPON 分组尾部（`Desc_astd_psi_omega` 之后），收口人字典序归位 |
| `ss-csv/.../weapondata/arc/Catalog_WeaponData_ARC.kt` | 8 个 Wpn object | number **9221~9226 + 9235/9236**（超原协议扩展占用，待收口确认）；文件末尾追加 |
| `contents/data/campaign/special_items.csv` | 2 行 weapon_bp | 文件末尾追加；order = **9205/9206** |
| `ss-csv/.../outputs/proj/ProjMissileSpec.kt` | **公共扩展**（behaviorSpec 字段） | 非武器组私有文件；按协议在 PR 中单独提出、先于武器组合入；首批其他组不触碰此文件 |
| `src/.../ProjectileVfxSpecs.kt` / `BeamVfxSpecs.kt` | **不动** | §3 已说明 N/A |

### 5.2 对共享基建的依赖项

- **不依赖** Buff API（无叠层机制）、**不依赖** ConeImpactHandler（无锥状机制）、**不依赖** CombatRandom（无结算随机）。
- 依赖既有件：`DifficultyTuning`/`ScalingEntry`（已落地）、`engine.addPlugin` 模式（既有）、原版 `DEMScript`（api jar 公开类）。
- 顺序位置：首批计划 §12 第 **10/10**（最后攻坚）。本组可在基建 PR 合入后随时开工；唯一先行需求是 §1.2 的 `MissileProjSpec` 扩展（量小，随本组 PR 前半段落地）。

### 5.3 风险登记表（烟测验证手段见 §4.2）

| # | 风险 | 验证失败时的处置 |
|---|---|---|
| R1 | `setMissileAI(自定义)` 后 `missile.getAI()` 可能被引擎包裹，导致 DEMScript WAIT 段 `instanceof GuidedMissileAI` 失败、永不触发（事实 #5 的前提被破坏） | **已烟测闭环（2026-07-29）**：DEMScript 引擎内部读取路径不受包装影响（payload 光束实际命中结算证实接管成立）；包装只影响脚本侧观测——读数一律走 `getUnwrappedMissileAI()`（`getAI()`/`getMissileAI()` 读回恒为包装实例，三路全扫判例已登记自动化插件）。原备用方案（TrackAI 直设 DEMScript / 自实现充能）无需启用 |
| R2 | payload 光束实际结算量与「damage/second × burstSize」口径不符（原版龙炎 8000×0.75 与 tip 4000 存在未明差异） | 烟测读数校准 payload 行 damage/second；禁止靠猜 |
| R3 | 脚本生成导弹 + 手动 `addPlugin(DEMScript)` 的打击归因（击杀归功/经验）与 `beam.getSource()` 归属 | 烟测日志核对；归属异常时同步难度取值改从登记表首击 source 判定 |
| R4 | 隐藏六件进 codex/掉落 | tags/hints 已按先例配齐；烟测确认，若泄漏补 `SHOW_IN_CODEX` 反向核查与掉落表排查 |
| R5 | ~~锁定激光仅红色（v1 复用 targetinglaser3）~~ | **已落地（2026-09-26）**：自绘双色锁定激光两件隐藏 beam .wpn（动能蓝白/高爆共振红，结构照 targetinglaser3），behaviorSpec `targetingLaserId` 参数化直换；注意隐藏 beam .wpn 必须有 weapon_data.csv 行，否则加载即报错（首轮烟测判例） |

---

## 6. 验收要点（主代理逐项核对）

**数据面**
- [ ] 8 个 `WeaponDataEntry` object 落 ARC catalog 文件末尾，number 9221~9226 + 9235/9236 无撞号，列值与 §1.1 逐列一致
- [ ] `MissileProjSpec` behaviorSpec 扩展已落地且 JSON 输出含嵌套结构（生成物目检）
- [ ] 3 件 .proj 生成物：dummy 挂对 onFireEffect；弹头 behaviorSpec 键名逐字（含 `destroyMissleWhenDoneFiring` 原版拼写）、payloadWeaponId 正确
- [ ] 8 件 .wpn：插件挂载点三处（dummy onFireEffect、payload beamEffect、launcher/pod everyFrameEffect 挂弹视觉），弹头与锁定激光 .wpn 无插件；pod 的 projectileSpecId 复用 dummy
- [ ] i18n 键齐全（name/desc 与设计案一致；tip = 定稿原文 + v2 数值插入，审批通过）；pod notes 走 notesId 复用；launcher/pod HL 键均覆写数值
- [ ] special_items.csv 两行 params 为武器 id，order = 9205/9206

**代码面**
- [ ] 5 个类路径/形态与 §2.1 表一致；无 XxxManager/Service/Runtime 命名；无反射；无空 catch；错误分支均有日志
- [ ] Salvo 流程：dummy 同帧移除、双弹 weaponId/错位/散布/批次号正确、TrackAI + DEMScript 装配顺序正确
- [ ] TrackAI 双接口实现；无目标直飞定义行为
- [ ] PayloadBeamEffect：首伤帧登记一次性（防 1s 照射期重复）；动能照射期 0.1s EMP 电弧节律（帧累积 + 0.3s 伤害帧宽限）、高爆无
- [ ] SyncHandler：目标键 + 异种配对 + ≤1s + 同源规则与 §2.2 逐条一致；触发即清；玩家 v2 取值调用点正确；source 解析失败 WARN + v2；增伤写 demDrone energyWeaponDamageMult（非一次性伤害）
- [ ] 无刻意兼容/兜底；§2.4 十条边界各有定义行为

**特效面**
- [x] ~~ProjectileVfxSpecs / BeamVfxSpecs 两处零改动~~（2026-09-26 实机裁定推翻：双弹头已登记 ProjectileVfxSpecs 拖尾，见 §3 表）
- [ ] 双色配色落点（.proj engineSlots + 爆炸色 + .wpn 光束三色 + Static Trail 拖尾双色）与设计案主色一致

**测试面**
- [ ] §4.1 十三条单测全部存在且为真实逻辑驱动（无源码 contain）；窗口恰界/触发清零/难度三档/WARN 路径断言点齐全
- [ ] §4.2 烟测八项通过；R1（DEM 触发）与 R2（payload 读数）两项有明确日志/截图证据
- [ ] 烟测到达终态即退出，未干等超时

**目检**
- [ ] 双弹异色可区分（尾焰/爆炸/光束/锁定激光：动能蓝白 / 高爆共振红）；同步紫色渐变不遮挡战场；发射点双 Flare 与星云爆发可见
- [ ] 弹体 600 结构可被点防拆解队形（设计克制面成立）；12s 节奏符合窗口武器定位
