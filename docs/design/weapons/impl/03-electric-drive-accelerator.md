# 电驱加速炮 逐件实现规格 v1（待评审）

> 依据：`docs/design/weapons/impl/00-共享基建.md` v1（Buff API / CombatRandom / HUD 通道 / 合并协议）、`docs/design/weapons/90-首批实装计划.md` v6 §3 与全局约定、设计案定稿 `blue/20-production.md`「电驱加速炮」v1.0（2026-07-28）。
> 状态：规划文档，不改动 `src/` 与 `ss-csv/`。
> API 核查时间：2026-07-29，对照 `starfarer.api.jar`（0.98）javap 签名、原版数据文件与现有 `src/` 代码逐条核实（`EveryFrameWeaponEffectPlugin.advance(float, CombatEngineAPI, WeaponAPI)`、`OnHitEffectPlugin.onHit(...)`、`ShipAPI.getFluxLevel()/getHardFluxLevel()`、`MutableShipStatsAPI.getBallisticWeaponRangeBonus()`（`StatBonus.modifyFlat/unmodify` 存在）、`WeaponAPI` 无 `setRange`、`CombatEngineAPI.applyDamage(8 参)/addFloatingDamageText/addHitParticle/maintainStatusForPlayerShip`、`DamagingProjectileAPI.getDamageAmount()/getSource()/getWeapon()/getProjectileSpecId()`、`Misc.getRandom(long, int)`、`WeaponBlueprintItemPlugin.init` 反编译确认 params = 裸武器 id，均存在/属实）。
> 复用承诺：随机结算走共享 `CombatRandom`（确定性序列）；Weapon 级状态走共享 Buff API（`getOrCreateBuffByWeapon`）；HUD 走 §4.2 通道（`maintainStatusForPlayerShip` + 浮字）；弹体特效走 `ProjectileVfxSpecs.simpleProjectileVfx` texTrail 管线。不另起状态表、不自造随机源。

## 0. 核查后对上游文档的三处修正（实装以本规格为准）

1. **「散射 2」没有 `projectileCount` 字段**。原版 `weapon_data.csv` 列头、`WeaponSpecLoader`（混淆 jar strings）、`WeaponSpecAPI` 全方法均无此字段。90 计划 §3.1 表格中的「`projectileCount`（散射）| 2」落实为：**`.wpn` 双炮管 offsets + `barrelMode: "LINKED"`**——原版无 LINKED 双管实弹先例（LINKED 多管仅见于导弹架），每触发 8 弹（双管 × burst 4）的实际弹数/散射角/动画必须烟测目检确认（90 计划 §3.6 风险已登记）。
2. **`special_items.csv` 的 plugin params 是裸武器 id，不是 `weapon:<id>`**。反编译 `WeaponBlueprintItemPlugin.init`：直接 `settings.getWeaponSpec(stack.specialData.data)`，带前缀会拿到 null。**与 01-charge-needle.md §1.5 的 `weapon:<id>` 写法冲突**，收口人须统一（本规格按反编译结论写裸 id，01 需回改）。
3. **`.wpn` 只有一个 `everyFrameEffect` 槽位**。电驱需要自定义 everyFrame 做净空加速，无法再挂 `CombatVfxBootstrapEveryFrameEffect`；故 `ElectricDriveAcceleratorWeaponEffect.advance` 内必须自行调用 `CombatVfxBootstrap.ensureInstalled(engine)`（`internal object`，同模块可达），否则弹体 VFX 管线不启动。

---

## 1. 数据面

### 1.1 ss-csv catalog 条目（`Catalog_WeaponData_ARC.kt` 文件末尾追加一个 object）

number 段位按 00-共享基建 §3 预分配：**9213**。`WeaponDataEntry(), SsProjProjectileOutputs`。

`Wpn_astd_electric_drive_accelerator`（逐列）：

| 字段 | 值 | 备注 |
|---|---|---|
| id | `astd_electric_drive_accelerator` | |
| name | `weaponName(id)` | 走 i18n 键 |
| tier / rarity | 1 / 1 | 量产件（对齐电荷针刺口径） |
| baseValue | 11000 | **提案待裁定**（按 OP15 对标电荷针刺 6000/OP9 的单价带） |
| range | 800 | |
| damagePerSecond | 160 | 持续 2 弹/s × 80（设计案备弹经济口径） |
| damagePerShot | 80 | 动能 |
| emp | 0 | |
| impact | 4 | **提案**（原版动能对照：针刺 50 伤 impact=1、轨道炮 100 伤 impact=10） |
| turnRate | 30 | 对齐 aod7/spc3 |
| ops | 15 | |
| ammo / ammoPerSec / reloadSize | 30 / 2.0 / 8 | 重装 4s/+8 → 8/4=2.0 |
| type | `KINETIC` | weapon_data 的 type 列 = DamageType（`.wpn` 的 type=BALLISTIC 是另一维度，勿混） |
| energyPerShot / energyPerSecond | 88 / 176 | 88 为「每颗子弹 88」裁定口径；176 = 2 弹/s × 88 |
| chargedown / burstSize / burstDelay | 1.0 / 4 / 0.15 | 发射冷却 1s + 连发 4；散射 2 不在此表（走 `.wpn` LINKED 双管，见 §0-1） |
| minSpread / maxSpread / spreadPerShot / spreadDecayPerSec | 1.0 / 8.0 / 0.5 / 4.0 | **提案待裁定**（「中等精确度」；原版重型针刺 1/10 对照） |
| projSpeed | 1000 | **提案待裁定**（设计案未给定；原版轨道炮 1000 对照，射程 800 → 飞行 0.8s） |
| tags | `astd_production` | |
| groupTag / tech | `astd` / `弧光阵列` | |
| primaryRoleStr | `SsI18n.t("weapon.$id.primaryRoleStr")` | |
| customPrimary | `SsI18n.t("weapon.$id.tooltip.customPrimary")` | 无 `{%s}` 占位，不设 customPrimaryHL |
| number | 9213 | |
| projSpec | 见下 | |

`projSpec`（显式 `ProjectileProjSpec` 构造，照 `Wpn_astd_aod7.projSpec` 样板）：

- id = `astd_electric_drive_accelerator_shot`，spawnType = `ProjectileSpawnType.BALLISTIC`
- onFireEffect = `cn.kasuminova.astd.combat.effect.generic.ProjectileSpecOnFireDispatcher`
- onHitEffect = `cn.kasuminova.astd.combat.effect.arc.ElectricDriveAcceleratorOnHitEffect`
- collisionClass = `PROJECTILE_FF`，collisionClassByFighter = `PROJECTILE_FIGHTER`
- 原版弹体视觉隐藏四件套：length = 2.0、width = 2.0、fringeColor = `Rgba(235, 242, 250, 0)`、coreColor = `Rgba(255, 255, 255, 0)`、bulletSprite = `graphics/textures/BUtil_NONE.png`、fadeTime = 0.2、textureScrollSpeed = 0.0、pixelsPerTexel = 1.0

### 1.2 `Catalog_Descriptions.kt`

WEAPON 分组尾部（`Desc_astd_psi_omega` 之后，与其他武器分支的追加行共存，收口人按 id 字典序归位）：

```kotlin
object Desc_astd_electric_drive_accelerator : LocalizedDescription("astd_electric_drive_accelerator", "WEAPON")
```

### 1.3 `.wpn` JSON 骨架（`contents/data/weapons/astd_electric_drive_accelerator.wpn`，手写）

```json
{
    "id": "astd_electric_drive_accelerator",
    "specClass": "projectile",
    "type": "BALLISTIC",
    "size": "MEDIUM",
    "displayArcRadius": 800,
    "turretSprite": "graphics/textures/BUtil_NONE.png",
    "turretGunSprite": "graphics/textures/BUtil_NONE.png",
    "hardpointSprite": "graphics/textures/BUtil_NONE.png",
    "hardpointGunSprite": "graphics/textures/BUtil_NONE.png",
    "visualRecoil": 0.0,
    "turretOffsets": [12, -4, 12, 4],
    "turretAngleOffsets": [0, 0],
    "hardpointOffsets": [17, -4, 17, 4],
    "hardpointAngleOffsets": [0, 0],
    "barrelMode": "LINKED",
    "animationType": "MUZZLE_FLASH",
    "projectileSpecId": "astd_electric_drive_accelerator_shot",
    "everyFrameEffect": "cn.kasuminova.astd.combat.effect.arc.ElectricDriveAcceleratorWeaponEffect",
    "fireSoundTwo": "autocannon_fire"
}
```

挂载点说明：

- **双炮管 ±4su 横向 offsets + `barrelMode: "LINKED"`** = 设计案「散射 2」的落点（§0-1）；连发 4 由 weapon_data 的 burst 列承担，合计每触发 8 弹。offsets 绝对值提案，烟测目检双管出弹点是否穿模。
- `everyFrameEffect` → 净空加速逻辑；其内部兼负 `CombatVfxBootstrap.ensureInstalled`（§0-3）。**不挂** `onFireEffect`——`.proj` 上的 `ProjectileSpecOnFireDispatcher` 已覆盖弹体 VFX 路由（aod7 即此形态）。
- `fireSoundTwo = autocannon_fire`：`astd_spc3.wpn` 已在用的原版音效 id。
- 炮口贴图暂不配（BUtil_NONE 全隐 + MUZZLE_FLASH），美术资源到位后只换贴图路径，不动结构。

### 1.4 i18n 键清单（`ss-csv/src/main/resources/i18n/zh-cn.properties` 文件末尾集中追加）

设计案定稿原文（tip 静态无数值，机制数值隐性缩放不上 tip）：

```properties
# Weapon 名称
weapon.astd_electric_drive_accelerator.name=电驱加速炮

# Weapon tooltip 自定义字段（设计案定稿原文；无 {%s} 占位，不配 customPrimaryHL）
weapon.astd_electric_drive_accelerator.tooltip.customPrimary=射弹命中目标造成额外动能伤害；舰船辐能状态处于较低状态时获得额外基础射程。效果受到难度系数影响。

# Weapon 定位（提案待裁定：设计案未给）
weapon.astd_electric_drive_accelerator.primaryRoleStr=反护盾,弹幕压制

# descriptions.csv（text1 为设计案定稿原文；notes 提案待裁定）
desc.astd_electric_drive_accelerator.text1=弹链供弹的加速实弹炮，以散射连发倾泻动能弹雨。武器使用不稳定装药，命中能够额外释放冲击。
desc.astd_electric_drive_accelerator.notes=弹链不会过问概率的意见——它只负责把下一发推上膛。
```

HUD 文本不进 properties，走 `contents/data/strings/strings.json`（MOD 类，见 §2.4）：

```json
"ui.eda.range_status.title": "净空加速",
"ui.eda.range_status.desc": "低辐能加成：射程 +%bonus% su"
```

### 1.5 `special_items.csv` 条目（`contents/data/campaign/special_items.csv` 文件末尾追加，order 列留空）

量产件 P2 走单件蓝图（90 计划 §14）。plugin params = **裸武器 id**（§0-2）；带 `single_bp` 标签时 params 必填（`ASTDDevContentSelector.SPECIAL_ITEM_PARAM_REQUIRED_TAGS/IDS` 核实），dev 仓储自动投放。ss-csv 无任何 `SPECIAL_ITEMS` 条目（已核实生成器只写有entry的目标），本文件为纯手维护，生成不会覆盖。

```csv
电驱加速炮蓝图,astd_electric_drive_accelerator_bp,"weapon_bp, single_bp, no_drop, no_drop_salvage",阿斯忒里亚遗构局,,11000,1,0,,graphics/icons/cargo/blueprint_weapons.png,ui_chip_pickup,ui_weapon_bp_drop,com.fs.starfarer.api.campaign.impl.items.WeaponBlueprintItemPlugin,astd_electric_drive_accelerator,使重工业设施能够制造「电驱加速炮」。,
```

`no_drop, no_drop_salvage` 为 P6 前口径（P6 接入量产蓝图投放时摘除）。

---

## 2. 代码面

### 2.1 类清单

| 类名 | 形态 | 职责 | 挂载点 | 文件路径 |
|---|---|---|---|---|
| `ElectricDriveAcceleratorDifficulty` | object（纯函数 + ScalingEntry 锚点，无状态） | 不稳定装药上限、净空加速射程锚点取值；辐能衰减系数与最终加成纯计算（难度取值与几何公式唯一出处，单元测试直接打这里） | 被 OnHitEffect / WeaponEffect 调用 | `src/main/kotlin/cn/kasuminova/astd/combat/effect/arc/ElectricDriveAcceleratorDifficulty.kt` |
| `ElectricDriveAcceleratorOnHitEffect` | class : `OnHitEffectPlugin` | 命中结算：按三锚点上限均匀随机追加一次动能伤害 + 浮字 + 白色命中粒子 | `.proj` 的 `onHitEffect` | `.../arc/ElectricDriveAcceleratorOnHitEffect.kt` |
| `ElectricDriveAcceleratorWeaponEffect` | class : `EveryFrameWeaponEffectPlugin` | 每帧：VFX bootstrap → 读 `ship.fluxLevel` 折算射程加成并 `modifyFlat/unmodify` → 玩家船 HUD 状态栏维护 | `.wpn` 的 `everyFrameEffect` | `.../arc/ElectricDriveAcceleratorWeaponEffect.kt` |
| `ElectricDriveChargeState` | class : `Buff`（共享基建接口） | Weapon 级随机序列载体：seed + callIndex（装药随机的确定性来源）；`lifetime = HOST_BOUND` | BuffHost Weapon 级键 `astd_buff:weapon:astd_eda_charge_state:<slotId>`（`getOrCreateBuffByWeapon`） | `.../arc/ElectricDriveChargeState.kt` |

说明：

- 弹体特效**不单设 Vfx 类**（修正 90 计划 §3.2 的 `ElectricDriveAcceleratorVfx` 行）——对齐 spc3 模式，`ProjectileVfxSpecs.builders` 一行登记即可。
- 四个类均按用户规范写齐接口/类注释（类简介、动机、成员作用）；`ElectricDriveChargeState` 作为 `Buff` 实现须写清 `isHostValid` 语义（宿主船在场 且 `weapon.spec.weaponId` 仍为本武器——对齐 00 §6 换装回收验证）。
- 射程/装药逻辑跨两个插件复用的部分全部收进 `ElectricDriveAcceleratorDifficulty` 纯函数，插件只做 API 接线，无重复逻辑。

### 2.2 难度锚点与取值调用点

```kotlin
object ElectricDriveAcceleratorDifficulty {

    /** 不稳定装药上限（面板百分比）：迟暮 25 / 砺刃 56.25 / 破晓 150（设计案显式锚点）。 */
    val CHARGE_MAX_PCT = ScalingEntry(25f, 56.25f, 150f, ScalingMap.LINEAR)

    /** 净空加速射程加成（su）：迟暮 100 / 砺刃 200 / 破晓 400（设计案显式锚点；远征线性=300，与 LINEAR 自洽）。 */
    val RANGE_BONUS_SU = ScalingEntry(100f, 200f, 400f, ScalingMap.LINEAR)

    /** 玩家固定 v2、敌方走轨一：owner==0 → entry.v2，否则 tuning.value(entry)。 */
    fun chargeMaxPct(tuning: DifficultyTuning, owner: Int): Float

    fun rangeBonusBase(tuning: DifficultyTuning, owner: Int): Float

    /**
     * 辐能衰减系数：level ≤ 0.2 → 1.0；level ≥ 0.4 → 0.0；中间线性。
     * level 先 coerceIn(0f,1f)；NaN 按 0 加成处理（返回 0f，调用侧 WARN，不静默）。
     */
    fun fluxDecayFactor(level: Float): Float

    /** 最终射程加成 = rangeBonusBase × fluxDecayFactor。 */
    fun rangeBonus(tuning: DifficultyTuning, owner: Int, level: Float): Float

    /** 装药额外伤害 = 命中当发实际伤害 × rollPct/100（以面板为基准、随修正自然缩放）。 */
    fun extraDamage(baseDamage: Float, rollPct: Float): Float

    /** 追加伤害触发阈值：extra ≥ 1f 才 applyDamage（roll=0 附近不产生 0 伤害事件与浮字）。 */
    fun shouldApplyExtra(extra: Float): Boolean

    /** 随机种子派生：shipId.hashCode() * 31 + slotId.hashCode()（00 §4.1 口径，战斗内稳定）。 */
    fun seedOf(shipId: String, slotId: String): Long
}
```

调用点：`DifficultyTuningImpl`（object 单例）在两插件处注入；`chargeMaxPct` 每次命中取一次，`rangeBonus` 每帧取一次（LunaLib 调档即时生效，无需缓存）。

### 2.3 核心逻辑伪代码

**OnHitEffect（不稳定装药，结算顺序固定）**：

```kotlin
override fun onHit(projectile, target, point, shieldHit, damageResult, engine) {
    // 1. 目标边界：hulk/phased 舰船不结算（对齐 HighFluxShieldPressureOnHitEffect 样板）；
    //    战机/导弹等非 Ship 目标照常结算（设计「射弹击中目标时」不限目标类型）。
    val ship = target as? ShipAPI
    if (ship != null && (ship.isHulk || ship.isPhased)) return
    val hitPoint = point ?: projectile.location   // 陨石等场景 point 可为 null（样板同款兜底）

    // 2. 来源解析：优先 projectile.source → 退化 weapon.ship；两者皆无 → INFO 日志并放弃结算（不得静默）。
    val weapon = projectile.weapon
    val source = projectile.source ?: weapon?.ship
    if (source == null) { log.info("[ASTD] EDA 装药放弃结算：弹体无来源（spec=${projectile.projectileSpecId}）"); return }
    if (weapon == null) { log.info("[ASTD] EDA 装药放弃结算：弹体无武器引用"); return }

    // 3. 难度取值：玩家固定 v2。
    val maxPct = ElectricDriveAcceleratorDifficulty.chargeMaxPct(DifficultyTuningImpl, source.owner)
    if (maxPct <= 0f) { logOnce.warn("[ASTD] EDA 装药上限为 0（k_s 数据异常？），跳过结算"); return }

    // 4. 确定性随机：BuffHost Weapon 级状态取 callIndex；同帧双管两发是两个独立事件，各自取值（00 §4.1）。
    val state = source.getOrCreateBuffByWeapon(STATE_BUFF_ID, weapon) {
        ElectricDriveChargeState(source, weapon, ElectricDriveAcceleratorDifficulty.seedOf(source.id, weapon.slot.id))
    } as ElectricDriveChargeState
    val rollPct = CombatRandom.nextFloatIn(state.seed, state.nextCallIndex(), 0f..maxPct)

    // 5. 结算：以命中当发实际伤害为基准；低于阈值不产生事件。
    val extra = ElectricDriveAcceleratorDifficulty.extraDamage(projectile.damageAmount, rollPct)
    if (!ElectricDriveAcceleratorDifficulty.shouldApplyExtra(extra)) return
    engine.applyDamage(target, hitPoint, extra, DamageType.KINETIC, 0f, false, true, source)

    // 6. 玩家可见反馈：伤害浮字 + 白色命中粒子（克制，2~3 粒）。
    engine.addFloatingDamageText(hitPoint, extra, FLOATY_COLOR, target, source)
    repeat(2) { engine.addHitParticle(hitPoint, randomVel(), 18f, 0.9f, 0.35f, Color.WHITE) }
}
```

`applyDamage` 不触发二次 onHit 回环（00 §2 已核实）。`STATE_BUFF_ID = "astd_eda_charge_state"`。

**WeaponEffect（净空加速，每帧）**：

```kotlin
override fun advance(amount, engine, weapon) {
    if (engine.isPaused) return
    CombatVfxBootstrap.ensureInstalled(engine)   // §0-3：本武器独占 everyFrame 槽，必须代行 bootstrap

    val ship = weapon.ship ?: return
    if (ship.isHulk) return  // hulk 后 stats 不再结算，modifier 随实体终结

    val levelRaw = ship.fluxLevel                // 软+硬合计（getFluxLevel 语义，jar 已核实）
    if (levelRaw.isNaN()) logOnce.warn("[ASTD] EDA 读到 NaN 辐能水平，按 0 加成处理")
    val bonus = ElectricDriveAcceleratorDifficulty.rangeBonus(DifficultyTuningImpl, ship.owner, levelRaw)

    // modifierId 带 slotId：同舰多件电驱互不覆盖
    val modId = RANGE_MOD_PREFIX + weapon.slot.id
    if (bonus > 0f) {
        ship.mutableStats.ballisticWeaponRangeBonus.modifyFlat(modId, bonus)
    } else {
        ship.mutableStats.ballisticWeaponRangeBonus.unmodify(modId)  // 不挂 0 值 flat 污染 stat 明细
    }

    // HUD：仅玩家船可见（maintainStatusForPlayerShip 本就只渲染玩家船，此处显式守门省无效调用）
    if (bonus > 0f && ship === engine.playerShip) {
        engine.maintainStatusForPlayerShip(
            "astd_eda_range_status", ICON,
            I18n[I18n.Categories.MOD, "ui.eda.range_status.title"],
            I18n.t(I18n.Categories.MOD, "ui.eda.range_status.desc", "bonus" to bonus.roundToInt()),
            false,   // 正向收益
        )
    }
}
```

**已知副作用（照 90 计划 §3.6 风险 7 登记，不掩饰）**：`ballisticWeaponRangeBonus` 是舰体乘区，加成作用于**全舰实弹武器**；且 `EveryFrameWeaponEffectPlugin` 无 dispose 钩子，战斗内卸载武器后 modifier 残留至本场结束。无 `WeaponAPI.setRange`（javap 全表核实）；`WeaponSpecAPI.setMaxRange` 存在但改的是加载期共享 spec（全场同型武器一起变），**禁用**。代码注释中写明该限制。

**状态机**：`ElectricDriveChargeState : Buff`——`lifetime = HOST_BOUND`；`isHostValid()` = `engine.isEntityInPlay(ship) && ship.isAlive && weapon.spec.weaponId == WEAPON_ID`（换装回收对齐 00 §6）；`advance/onRemove` 默认空实现（无衰减语义，纯载体）；`nextCallIndex()` 单调递增不复位。

### 2.4 玩家可见反馈（对照全局实现注意事项 2）

| 机制 | 通道 | 内容 | 触发时机 |
|---|---|---|---|
| 不稳定装药追加伤害 | `addFloatingDamageText` | 命中点白色浮字，数值=追加量 | 每次 extra ≥ 1f 的命中（敌我双方均可见） |
| 不稳定装药手感 | `addHitParticle` ×2 | 白色小粒子爆点 | 同上 |
| 净空加速射程加成 | `maintainStatusForPlayerShip` | 左侧状态栏「净空加速 / 低辐能加成：射程 +N su」，正向（negative=false） | 玩家船 bonus > 0 时每帧维护；归零即消失（不再 maintain） |
| 净空加速可观测性 | 原版射程提示圈 | 射程加成直接改变射程圈半径 | 被动，烟测目检确认 |
| 弹体 | texTrail 拖尾 | 白色弹体 + 500su 拖尾（§3） | 常驻 |

图标：`ICON = "graphics/hullmods/astd_arc_loop_interface.png"`（现有 ARC 素材复用，提案，美术确认后替换）。

### 2.5 0 值与边界（对照全局实现注意事项 3）

| 场景 | 行为 |
|---|---|
| `chargeMaxPct ≤ 0`（自定义 k_s 极端值） | WARN 一次 + 跳过结算；禁止静默恒零 |
| `rollPct ≈ 0` / `extra < 1f` | 不产生伤害事件、不飘浮字（阈值函数显式判定，不是静默吞掉） |
| `fluxLevel` NaN | 按 0 加成 + WARN 一次；coerceIn(0f,1f) 防越界 |
| `bonus == 0` | `unmodify(modId)`，不挂 0 值 flat 污染 stat 明细 |
| `point == null` | 回退 `projectile.location` |
| `projectile.source / weapon` 皆空 | INFO 日志 + 放弃结算（不得空 catch、不得静默） |
| 同帧 LINKED 双管两发 onHit | 两个独立事件各自取随机值，callIndex 严格递增（00 §4.1 铁律：同事件不重掷；这里是两个事件） |
| 目标 hulk / phased | 不结算 |
| `weapon.slot` 为空（理论边界） | 退化用 `weapon.id` 拼 seed/modId（弹道装饰武器场景），WARN 一次 |
| 射程加成反向计算 | 本机制无反算（直接 flat 加成）；无需除法防线 |

---

## 3. 特效面

### 3.1 `ProjectileVfxSpecs.kt` 登记项（builders map 字面量末尾追加；白色调色板分支内内联，不收口共享）

```kotlin
"astd_electric_drive_accelerator_shot" to {
    simpleProjectileVfx(
        "astd_electric_drive_accelerator_shot",
        // 美术裁定：白色射弹。内联字面量（合并协议：新调色板只允许收口人沉淀）。
        VfxPalette(ASTDColor(1f, 1f, 1f, 0.9f), TEX_SMOOTH, TEX_ZAPPY),
        width = 9f,     // 电荷针刺箭弹 6f × 1.5（设计裁定「粗细 1.5×」，以小型箭弹为基准）
        length = 500f,  // 设计裁定：长 trail 拖尾 500su
    )
},
```

`length = 500f` 会驱动派生公式：`trailNodes` 达上限 24、`mainTile ≈ 205`、`headRecede = 40`——均为公式内自然结果，无需具名覆盖；目检若观感异常先调公式常量（00 §4.3 口径）。

### 3.2 `smd_projectile_vfx.json` 映射（entries 数组末尾追加）

```json
{
  "projectileSpecId": "astd_electric_drive_accelerator_shot",
  "preset": "electric_drive_accelerator_shot"
}
```

说明：该 JSON 的消费方在当前 `src/` 未检索到（疑为遗留管线/外部链路），按 aod7/spc3 既有约定补齐映射，实装时顺带确认其去向。

### 3.3 命中特效

不稳定装药命中反馈 = 浮字 + 2 粒白色 `addHitParticle`（§2.3），不引入额外 RenderEntity 组件——设计案「少量白色 hitParticle，不抢视觉」。

---

## 4. 测试面

### 4.1 单元测试（`src/test/kotlin/cn/kasuminova/astd/combat/effect/arc/ElectricDriveAcceleratorLogicTest.kt`，全部调用真实逻辑，禁止源码 contain）

测试桩：`FakeTuning(fixedScale) : DifficultyTuning`（接口实现，非反射），`value(entry)` 用 `entry.map` 真算。

1. **装药上限玩家固定 v2**：`chargeMaxPct(FakeTuning(5f), owner=0) == 56.25f`；`owner=1` 时 scale 1/2/3/5 → 25 / 56.25 / 87.5 / 150。
2. **射程锚点玩家固定 v2**：`rangeBonusBase(FakeTuning(5f), owner=0) == 200f`；`owner=1` scale 1/3/5 → 100 / 300 / 400（远征 300 与设计案显式值对表）。
3. **辐能衰减系数分段**：level = 0 / 0.1 / 0.2 → 1.0；0.25 → 0.75；0.3 → 0.5；0.4 / 0.6 / 1.0 → 0；越界 -0.5 → 1.0、1.5 → 0（clamp）；NaN → 0。
4. **最终加成合成**：`rangeBonus(FakeTuning(2f), owner=0, level=0.3f) == 100f`；level=0.1 → 200；level=0.4 → 0。
5. **装药额外伤害换算**：`extraDamage(80f, 56.25f) == 45f`；`extraDamage(80f, 0f) == 0f`；`shouldApplyExtra(0.5f) == false`、`shouldApplyExtra(1f) == true`。
6. **seed 派生**：同 shipId+slotId 两次调用同值；不同 slotId 不同值；不同 shipId 不同值。
7. **随机序列单调**：`ElectricDriveChargeState.nextCallIndex()` 连续调用返回 0,1,2… 不复位（双管同帧两发取值不同的前置保证）；`isHostValid` 在 weaponId 不匹配时返回 false（换装回收路径，桩 weapon/ship 验证）。

烟测外的随机分布断言（均值 ≈ maxPct/2）属共享 `CombatRandom` 职责，由 00 §6 基建测试覆盖，本武器不重复。

### 4.2 烟测检查点（`deployMod` + `launchSmokeTestGame`，到达终态即退出游戏）

1. dev 仓储出现「电驱加速炮蓝图」，学习后可装配中型实弹槽。
2. **每触发 8 弹**：LINKED 双管 × burst 4 的实际弹数目检（§0-1 风险项）；散射角与双管出弹点不穿模。
3. 辐能 0 时射程提示圈 ≈ 1000su（玩家档 800+200）；连射其他武器把辐能堆过 40% 后回到 800；中途（约 30%）加成减半可观测。
4. HUD 左侧出现「净空加速」条目且数值随辐能实时变化，归零消失。
5. 命中出现白色追加伤害浮字，玩家档数值落在 [0, 45]（80 × 56.25%）；附加伤害不触发二次 onHit（无连锁爆字）。
6. 弹体白色 + 500su 拖尾观感目检；超射程后拖尾经 fadeTime=0.2 窗口自然淡出。
7. 敌版三档：LunaLib 切 k_s 后观察敌舰电驱射程与浮字幅度变化（devMode）。

---

## 5. 并行实装注意

### 5.1 触碰的共享文件（按 00 §3 合并协议）

| 共享文件 | 本武器键名空间 | 追加位置 |
|---|---|---|
| `ss-csv/.../i18n/zh-cn.properties` | `weapon.astd_electric_drive_accelerator.*` / `desc.astd_electric_drive_accelerator.*` | 文件末尾集中追加 |
| `ss-csv/.../strings/Catalog_Descriptions.kt` | `Desc_astd_electric_drive_accelerator` 一行 | WEAPON 分组尾部（`Desc_astd_psi_omega` 之后） |
| `ss-csv/.../weapondata/arc/Catalog_WeaponData_ARC.kt` | `Wpn_astd_electric_drive_accelerator`，number **9213**（预分配段位） | 文件末尾 |
| `contents/data/campaign/special_items.csv` | `astd_electric_drive_accelerator_bp` 一行，order 留空 | 文件末尾 |
| `src/.../projectile/driver/ProjectileVfxSpecs.kt` | builders map `"astd_electric_drive_accelerator_shot"` 条目 | map 字面量末尾；白色调色板内联（不留共享函数） |
| `contents/data/config/smd_projectile_vfx.json` | 同 specId 一条 | entries 数组末尾 |
| `contents/data/strings/strings.json` | `ui.eda.*` 两个键 | **合并协议外补充项**：00 §3 未列此文件，HUD 文本必然多组共碰；同样键名空间隔离 + 对象末尾追加，提请收口人并入协议 |

无冲突提示：与 01（电荷针刺）共用 `BUtil_NONE.png`、`autocannon_fire` 音效、`blueprint_weapons.png` 图标——只读引用，不产生合并冲突。**与 01 的实质冲突在 special_items params 格式**（§0-2），收口人须裁决并回改其中一份。

### 5.2 对共享基建的依赖（只依赖、不改签名）

- **Buff API**（`Buff` / `getOrCreateBuffByWeapon`）：`ElectricDriveChargeState` 的载体。未落地，阻塞本武器 OnHit 随机部分。
- **CombatRandom**（`nextFloatIn(seed, callIndex, range)`）：装药随机唯一来源。
- **HUD 通道**（00 §4.2）：状态栏与浮字入口照表使用。
- 已就绪：`ProjectileVfxSpecs` / `ProjectileSpecOnFireDispatcher` / `DifficultyTuning(Impl)` / `CombatVfxBootstrap`。

### 5.3 实现顺序内的位置

90 计划 §12 第 **3** 位（电荷针刺 → 重型离子脉冲之后）。分支内提交顺序（对齐 §3.6 验证手段）：

1. 数据面（catalog + .wpn + i18n + special_items + VFX 登记）→ 编译 + generateSsCsv，先跑「裸武器」烟测（确认 8 弹与弹体观感）。
2. `ElectricDriveAcceleratorDifficulty` 纯函数 + 全部单元测试（TDD 先行）。
3. `WeaponEffect`（射程加成 + HUD）→ 烟测射程随辐能变化。
4. `OnHitEffect` + `ElectricDriveChargeState`（依赖 Buff API/CombatRandom 进 main 后 rebase 接入）→ 烟测浮字与附加伤害。

---

## 6. 验收要点（主代理逐项核对）

**数据面**

- [ ] `Catalog_WeaponData_ARC.kt` 新 object 逐列与本规格 §1.1 一致；number = 9213 不与他组冲突
- [ ] `./gradlew :ss-csv:generateSsCsv` 产物 weapon_data.csv 行列正确（散射 2 **不出现**在 CSV——它走 .wpn LINKED 双管）
- [ ] `.wpn` 双 offsets + `barrelMode: "LINKED"`、`everyFrameEffect` 类名全限定正确
- [ ] zh-cn.properties 键齐全且文案与设计案定稿原文逐字一致（name / customPrimary / text1）
- [ ] special_items.csv params = **裸武器 id**（若收口裁决采用 01 的 `weapon:` 前缀，则两份文档一起改并补实测）
- [ ] strings.json `ui.eda.*` 两键就位

**代码面**

- [ ] 四类落位 `combat/effect/arc/`，注释齐全（类简介/动机/成员作用）；无 XxxService/Manager 命名、无反射、无空 catch
- [ ] 玩家固定 v2（`owner == 0` 分支）在两插件均生效
- [ ] `WeaponEffect` 内含 `CombatVfxBootstrap.ensureInstalled`（漏掉则弹体 VFX 全灭）
- [ ] modifierId 含 slotId；bonus=0 走 unmodify；NaN/0 上限均有 WARN
- [ ] 随机只走 `CombatRandom` + BuffHost callIndex；无 `Misc.random`/无 `Math.random`
- [ ] 射程副作用（全舰实弹共享 + 无 dispose 残留）在代码注释中登记

**特效面**

- [ ] builders 条目白色内联调色板、width 9f / length 500f；smd json 映射就位
- [ ] 原版弹体隐藏四件套（length/width=2、双色 alpha=0、BUtil_NONE、fadeTime=0.2）

**测试面**

- [ ] §4.1 七条用例全绿且均为真实逻辑调用（无 contain 测试）
- [ ] §4.2 烟测 1~7 逐项过；到达终态即退出游戏

**目检**

- [ ] 每触发 8 弹、散射角合理、双管出弹点不穿模
- [ ] 白色弹体 + 500su 拖尾观感；超射程淡出无骤消
- [ ] 射程圈随辐能伸缩、HUD 条目数值同步、浮字幅度随难度档变化
