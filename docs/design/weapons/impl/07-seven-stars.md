# “七星”折跃发射器 实现规格 v1（待评审）

> 依据：`docs/design/weapons/90-首批实装计划.md` v6 §7 与全局约定、`docs/design/weapons/blue/30-superlative.md`「"七星"折跃发射器」已定案 v1.0、`impl/00-共享基建.md` v1。
> 状态：规划文档，不改动 `src/` 与 `ss-csv/`。
> API 核查时间：2026-07-29，对照 `starfarer.api.jar`（0.98）与本仓库现有代码逐条核实，未凭记忆书写。
> 定位：ARC 线超规格大型能量点防御（P6 前 `no_drop, no_drop_salvage`，仅 dev 仓储/控制台测试）。
> 2026-08 裂隙改版：固定 7 跳（移除击杀续跳门槛）、裂隙延迟爆炸（`EXPLOSION_DELAY = 0.5f` pending 队列）、爆炸特效换代 `RiftExplosionVfx`（本文档已同步）。

## 0. 与首批计划 §7 的三处实现层裁定（必须先读）

1. **弹体碰撞类别取 `NONE`**。设计案要求射弹不做正常飞行、发射即折跃；若保留 `PROJECTILE_FF`，瞬移间隙帧可能触发原版触碰结算（未经脚本倍率的裸面板伤害 + 弹体提前消失）。vanilla `.proj` 已证实 `collisionClass: "NONE"` 合法（`realitydisruptor_shot.proj` 等 8 处在用）。代价：原版命中反馈全失，所有伤害/特效由脚本自结算——正合设计意图。
2. **弹体寿命走等效锚点方案（2026-07-29 实机修订，原 `flightTime = 6.0` 裁定不成立）**。实机证实：BALLISTIC 弹体寿命被引擎钳制为 range÷projSpeed（800/3000 ≈ 0.27s 即 fade），weapon_data.csv 的 flight time 列**仅对 MISSILE 生效**，6.0 写入对 BALLISTIC 无寿命作用；弹体实体无论如何活不到 7 跳链跑完。故落地改为：**首发结算后立即 `engine.removeEntity(projectile)`**（弹体不可见且无碰撞，移除无可观测差异），连跳锚点移交 `SevenStarsChainScript` 的纯位置 `Vector2f anchor`，状态机全程由脚本驱动、生命周期随引擎回收——§2.4 弹体引用防线守护的故障面在锚点设计下不存在。catalog 中 `flightTime = 6.0` 保留为名义登记（对 BALLISTIC 无实际作用，仅维持列口径一致）。
3. **裂隙爆炸直击与 AOE 同额**。设计案原文「直击目标吃面板伤害，区域内其他所有目标造成面板 x% 的范围伤害」，难度缩放栏标注「hit，爆炸面板倍率」——按"hit 爆炸"整体缩放解读：直击目标与区域目标吃**同一次** `面板 × 当前倍率` 结算，不存在"直击固定 100%、仅 AOE 缩放"的双轨。若主代理按字面裁定只缩放 AOE，改动点为 `SevenStarsDamageHandler.flashExplosion` 一处参数，不影响其余结构。

## 1. 数据面

### 1.1 ss-csv catalog 条目（`Catalog_WeaponData_ARC.kt` 末尾追加）

样板对照：`Wpn_astd_aod7`（同文件 20~69 行，投射体 + i18n + 隐藏四件套）。逐列声明：

```kotlin
object Wpn_astd_seven_stars : WeaponDataEntry(), SsProjProjectileOutputs {
    override val id: String = "astd_seven_stars"
    override val name: String = weaponName(id)            // weapon.astd_seven_stars.name
    override val tier: Int = 3                            // 超规格对标 aod7
    override val baseValue: Int = 150000                  // 2026-07-29 审批裁定（弃 60000 提案）
    override val range: Int = 800
    override val damagePerSecond: Int = 125               // 250 / 2s，tooltip 展示口径
    override val damagePerShot: Int = 250
    override val emp: Int = 0                             // 面板 EMP 为 0；v5 终结 EMP 是脚本结算，不进面板
    override val impact: Int = 0
    override val turnRate: Int = 30
    override val ops: Int = 28
    override val type: String = "ENERGY"
    override val energyPerShot: Int = 750
    override val energyPerSecond: Int = 375               // 750 / 2s
    override val chargedown: Double = 2.0                 // 射速 2s/发
    override val burstSize: Int = 1
    override val burstDelay: Double = 0.0
    override val projSpeed: Int = 3000                    // 名义值；弹体由脚本瞬移接管，speed 仅影响 AI 预判（BALLISTIC 寿命被钳制为 range÷projSpeed，见 §0-2）
    override val flightTime: Double = 6.0                 // 名义登记：flight time 列仅 MISSILE 生效，对 BALLISTIC 无寿命作用（§0-2 实机修订）
    override val aiHints: Set<AiHint> = setOf(AiHint.PD)  // 输出 weapon_data.csv hints 列 = PD
    override val tags: String = "no_drop, no_drop_salvage" // P6 前口径；P6 后改特定赏金/主线限定（90-plan §14）
    override val groupTag: String = "astd"
    override val tech: String = "弧光阵列"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val customPrimary: String = SsI18n.t("weapon.$id.tooltip.customPrimary")
    override val customPrimaryHL: String = SsI18n.t("weapon.$id.tooltip.customPrimaryHL")
    override val number: Int = 9216                       // 合并协议预分配段位
    override val projSpec: ProjectileProjSpec = ProjectileProjSpec(
        id = "astd_seven_stars_shot",
        spawnType = ProjectileSpawnType.BALLISTIC,
        onFireEffect = "cn.kasuminova.astd.combat.effect.arc.SevenStarsOnFireEffect",
        onHitEffect = null,                               // collisionClass=NONE 永无命中回调
        collisionClass = "NONE",                          // 见 §0-1
        collisionClassByFighter = "NONE",                 // 原版加载强制要求该键，缺键 RuntimeException（2026-07-29 实机发现，vanilla inimical_emanation_shot.proj 先例）
        // 原版弹体视觉隐藏四件套（照 Wpn_astd_aod7.projSpec 样板）
        length = 2.0,
        width = 2.0,
        fadeTime = 0.2,
        fringeColor = Rgba(120, 200, 255, 0),
        coreColor = Rgba(220, 245, 255, 0),
        textureScrollSpeed = 0.0,
        pixelsPerTexel = 1.0,
        bulletSprite = "graphics/textures/BUtil_NONE.png",
    )
}
```

已核实事实：`ProjectileProjSpec` 构造签名与上表一致（`ss-csv/.../outputs/proj/ProjProjectileSpec.kt` 27~42 行）；`onHitEffect` 可空且 null 时 toJson 自动省略；**`collisionClassByFighter` 虽可空省略，但原版 ProjectileSpec 加载强制要求该键（2026-07-29 实机发现，缺键 RuntimeException），必须显式写 `"NONE"`**；`AiHint.PD` 存在（LENS catalog 175 行在用）；`number` 段位 9216 为合并协议分配给本组。

### 1.2 `.wpn` JSON 骨架（`contents/data/weapons/astd_seven_stars.wpn`，手写）

样板对照：`contents/data/weapons/astd_aod7.wpn`。插件挂载点写全：

```json
{
    "id": "astd_seven_stars",
    "specClass": "projectile",
    "type": "ENERGY",
    "size": "LARGE",
    "displayArcRadius": 800,
    "turretSprite": "graphics/weapons/astd_seven_stars_turret.png",
    "turretGunSprite": "graphics/fx/empty.png",
    "hardpointSprite": "graphics/weapons/astd_seven_stars_hp.png",
    "hardpointGunSprite": "graphics/fx/empty.png",
    "visualRecoil": 4.0,
    "turretOffsets": [0, 0],
    "turretAngleOffsets": [0],
    "hardpointOffsets": [0, 0],
    "hardpointAngleOffsets": [0],
    "barrelMode": "LINKED",
    "animationType": "MUZZLE_FLASH",
    "projectileSpecId": "astd_seven_stars_shot",
    "fireSoundTwo": "disintegrator_fire"
}
```

说明：

- **不挂 `everyFrameEffect`**：本武器无武器级每帧逻辑（机制全部挂在弹体脚本上）；aod7 的 `CombatVfxBootstrapEveryFrameEffect` 是其专属 VFX 引导，七星不经过该管线（§3.1）。
- 炮塔/挂点贴图路径为最终命名，素材由美术任务并行交付；未到位前以 `graphics/fx/empty.png` 顶替可跑通烟测（同 aod7 先例），交付时仅换路径字符串。
- `fireSoundTwo` 取原版能量重炮音，目检可调。
- `hints = PD` 不在 `.wpn`，由 weapon_data.csv hints 列输出（§1.1 `aiHints`）。

### 1.3 i18n 键清单（`ss-csv/src/main/resources/i18n/zh-cn.properties` 文件末尾集中追加）

键名空间 `weapon.astd_seven_stars.*` / `desc.astd_seven_stars.*`，文案原文取自设计案定稿：

```properties
weapon.astd_seven_stars.name=“七星”折跃发射器
weapon.astd_seven_stars.tooltip.customPrimary=发射时立即折跃射弹至目标位置，短暂延迟后产生一次裂隙爆炸，造成等额面板伤害。随后射弹会以短暂间隔持续折跃至下一个可打击目标，并再次引发裂隙爆炸。\n\n折跃后的射弹如果无法找到点防御目标，或者折跃次数已达 {%s} 次，则选择最近的舰船单位引发一次裂隙爆炸。效果受到{%s}影响。
weapon.astd_seven_stars.tooltip.customPrimaryHL=7 | 难度系数
weapon.astd_seven_stars.primaryRoleStr=折跃点防
desc.astd_seven_stars.text1=定向折跃拦截系统。它将整枚射弹作为可投掷的裂隙弹头使用——发射瞬间即完成空间跃迁，在目标坐标撕开一道短暂蓄积后爆裂的空间裂隙。它不知疲倦地跃向下一处火光，直至七次跃迁耗尽，或天空再无可燃之物。若半途再无小型目标可供追猎，弹头会把剩余的跃迁次数尽数倾泻到最近的敌舰上——多段裂隙沿舰体次第绽开，直至最后一道闭合。
desc.astd_seven_stars.notes=观测档案摘录：首次遭遇它的舰长坚称记录仪出了故障——弹道摄像里没有任何飞行物，只有七道幽蓝裂隙几乎同时亮起，宛如北斗横陈于战场之上。七道裂隙之后，整波导弹群在同一秒内熄灭。这个名字由此流传开来。
```

口径说明：

- 名称用弯引号 `“七星”`（90-plan 命名说明：`「」` 会被原版字体识别为无效符号）。
- tip 为玩家版单段终结口径（2026-07-28 微调：多段终结为破晓敌版限定，玩家 tip 不描述）；按 2026-07-29 字段分工铁律补"效果受到难度系数影响。"收尾，HL 追加"难度系数"高亮（折跃次数 7 为恒定值不缩放，伤害倍率隐性缩放口径与设计案一致）。
- 2026-08 裂隙改版：文案口径由「成功摧毁后充能续跳」改为「固定 7 跳 + 短暂延迟（0.5s）裂隙爆炸」；tip 中「短暂延迟」对应 `SevenStarsDifficulty.EXPLOSION_DELAY`，折跃次数恒 7 不缩放（`MAX_JUMPS`）。
- `{%s}` 占位 2 个，与 HL 两段一一对应：首段 `7` 填折跃次数（恒定值不缩放），尾段"难度系数"填收尾句；高亮字一律 {%s} 占位、原文只在 HL（2026-07-29 用户裁定：直接写原文会导致游戏看 tip 报错）；多行用 `\n\n`（csv-text 规范）。
- 观测档案摘录落 `notes`（descriptions.csv notes 列在游戏中以深灰小字呈现，对应设计案"深灰斜体"诉求；`text2~text5` 留空不生成键）。

### 1.4 `Catalog_Descriptions.kt` 条目

WEAPON 分组尾部（`Desc_astd_psi_omega` 之后）追加一行：

```kotlin
object Desc_astd_seven_stars : LocalizedDescription("astd_seven_stars", "WEAPON")
```

### 1.5 `contents/data/campaign/special_items.csv` 条目

**本组不动该文件**。七星为超规格武器，90-plan §14 裁定 P6 前 `no_drop, no_drop_salvage` 仅经 dev 仓储/控制台测试，P6 后接特定赏金/主线限定事件；合并协议中 `special_items.csv` 的追加约定仅服务量产件与单件蓝图。本组因此少一处共享文件冲突面。

## 2. 代码面

### 2.1 类清单表

落包：`src/main/kotlin/cn/kasuminova/astd/combat/effect/arc/`（与 90-plan §7.2 路径一致）。形态说明：以下为武器内单一实现的机制件，按既有 `combat/effect` 风格以 class / object 承载，**不另立接口族**——Buff API / ConeImpactHandler 两个跨武器基建已接口化，本武器均不消费（§5.2），对内纯逻辑件接口化属过度设计（全局规范 §5）；可测性由"纯逻辑 object 与引擎交互薄层分离"保证。

| 类名 | 形态 | 职责 | 挂载点 | 文件路径 |
|---|---|---|---|---|
| `SevenStarsOnFireEffect` | class : `OnFireEffectPlugin` | 发射瞬间：弹体速度清零、快照难度取值、执行首发折跃+裂隙征兆（或直接进入终结判定）、向引擎注册连跳脚本 | `.proj` 的 `onFireEffect`（数据面 §1.1） | `combat/effect/arc/SevenStarsOnFireEffect.kt` |
| `SevenStarsChainScript` | class : `BaseEveryFrameCombatPlugin` | 单发弹体的连跳状态机：0.3s 冷却计时、续跳折跃、裂隙延迟爆炸 pending 队列、推进判定、对舰终结（单段/多段调度）、无舰消散收口 | 由 `OnFireEffect` 内 `engine.addPlugin`（`BaseEveryFrameCombatPlugin` + `engine.addPlugin` 已核实） | `combat/effect/arc/SevenStarsChainScript.kt` |
| `SevenStarsChainMath` | object（纯函数） | 倍率链/段数表/折跃范围的纯计算：`flashMult(hitIndex)`、`terminalDamageFractions(multi, jumps)`、`jumpRange(weaponRange)`、推进判定 `nextChainStep(jumps, hasPdCandidates)` | 被 OnFire/Script/单测调用 | `combat/effect/arc/SevenStarsChainMath.kt` |
| `SevenStarsTargetSelector` | object（纯函数） | 候选收集与排序：导弹/战机按「本轮可摧毁优先、最近次优」排序；终结取最近敌舰。输入输出均为普通列表与值对象，不触引擎 | 被 OnFire/Script/单测调用 | `combat/effect/arc/SevenStarsTargetSelector.kt` |
| `SevenStarsDamageHandler` | object（引擎交互薄层） | 一次裂隙爆炸的区域结算（粗筛→逐目标 `applyDamage`→摧毁数仅作遥测）；终结单段/多段的 `applyDamage` + 沿碰撞箱取点（`sampleRiftCascadePoints`） | 被 OnFire/Script 调用 | `combat/effect/arc/SevenStarsDamageHandler.kt` |
| `SevenStarsVfx` | object（引擎交互薄层） | 折跃起止 EMP 电弧、路径星云、裂隙征兆/爆炸视觉（`RiftExplosionVfx` 薄层见 §3.3）、终结多段依次引爆的视觉编排 | 被 OnFire/Script 调用 | `combat/effect/arc/SevenStarsVfx.kt` |
| `SevenStarsDifficulty` | object | 三锚点登记 + 玩家/敌方取值口径（§2.2 取值调用点） | 被 ChainMath/DamageHandler 调用 | `combat/effect/arc/SevenStarsDifficulty.kt` |

已核实签名（javap 对照 0.98 jar）：

- `OnFireEffectPlugin.onFire(DamagingProjectileAPI, WeaponAPI, CombatEngineAPI)`。
- `DamagingProjectileAPI`：`getDamageAmount()`、`getSource()`、`getWeapon()`（onFire 首发结算用）；锚点方案下脚本只持纯位置 `Vector2f anchor`（§0-2 实机修订），不持有弹体实体、无瞬移/失效面；`CombatEntityAPI`：`getLocation()`、`getOwner()`、`getHitpoints()`、`getCollisionRadius()`。
- `CombatEngineAPI`：`isEntityInPlay(entity)`、`removeEntity(entity)`、`addPlugin(EveryFrameCombatPlugin)`、`getMissiles()`、`getShips()`、`getTotalElapsedTime(boolean)`、`isPaused`、`applyDamage(target, point, damage, damageType, emp, bypassShields, dealsSoftFlux, source, showDamageNumbers)`（9 参重载，`GravityCollapseOnHitHandler` 153 行在用同款）、`spawnEmpArcVisual(from, fromEntity, to, toEntity, thickness, color, coreColor)`（另有带 `EmpArcParams` 重载，本仓库 5 处在用）、`spawnExplosion(...)`。
- `ShipAPI`：`isFighter()`、`isHulk()`、`isAlive()`（终结取点改走碰撞箱几何，不消费 `isPointInBounds` 拒绝采样，见 §2.2 `sampleRiftCascadePoints`）。
- `WeaponAPI.getRange()`（折跃范围 = 其 50%，吃射程修正）、`WeaponAPI.getSlot()`（本组未用，Buff 复合键基建用）。
- LazyLib `CombatUtils.getEntitiesWithinRange(point, radius)`（`GravityCollapseOnHitHandler` 112 行在用）。

### 2.2 核心逻辑伪代码

**状态机**（单发弹体生命周期；`FIRST_STRIKE` 在 `onFire` 内同步完成，其余在 `SevenStarsChainScript.advance(amount)`）：

```
FIRST_STRIKE ──有PD目标──> CHAIN_COOLDOWN(0.3s) ──> NEXT_JUMP ──> CHAIN_COOLDOWN …（至多 7 跳）
      │                        ▲
      │无PD目标                │nextChainStep=JUMP（击杀不参与推进）
      ▼                        │
   TERMINAL ──────────────────┘（jumps>=7 或 无 PD 候选亦进 TERMINAL）
      │（无舰 → DISSIPATE）
      ▼
   DISSIPATE（排空 pending 延迟爆炸队列后置 DONE 收口；末跳爆炸晚于消散判定属预期）
```

**难度取值调用点**（`SevenStarsDifficulty`，发射时刻快照一次，战斗中途改设置不影响在飞弹体）：

```kotlin
object SevenStarsDifficulty {
    val FIRST_HIT_MULT = ScalingEntry(1.00f, 1.25f, 2.00f)   // 首发裂隙爆炸倍率
    val PER_JUMP_BONUS = ScalingEntry(0.50f, 0.625f, 1.00f)  // 每跳伤害提升（加算于 (1+…) 区）
    val BONUS_CAP      = ScalingEntry(1.00f, 1.75f, 4.00f)   // 累计提升上限
    // 常量（不缩放）：MAX_JUMPS = 7、CHAIN_COOLDOWN = 0.3s、EXPLOSION_DELAY = 0.5s、
    // 折跃范围 = weapon.getRange() * 0.5、AOE_RADIUS = 100su（设计案待裁定提案值）、
    // 终结基础 50%、v5 终结每段 +25% / 段上限 200% / 每段 EMP = 面板等值
}

fun snapshot(source: ShipAPI?): SevenStarsTuning {
    val playerOwned = source?.owner == 0
    return SevenStarsTuning(
        firstHitMult = if (playerOwned) FIRST_HIT_MULT.v2 else DifficultyTuningImpl.value(FIRST_HIT_MULT),
        perJumpBonus = if (playerOwned) PER_JUMP_BONUS.v2 else DifficultyTuningImpl.value(PER_JUMP_BONUS),
        bonusCap     = if (playerOwned) BONUS_CAP.v2      else DifficultyTuningImpl.value(BONUS_CAP),
        // 多段终结：破晓（k_s = 5）敌版限定；玩家恒单段（设计案 2026-07-28 微调 + 90-plan §7.2）
        multiSegmentTerminal = !playerOwned && DifficultyTuningImpl.fixedScale >= 5f,
    )
}
```

玩家固定 v2 的口径参照既有实现 `ASTDVirtualParticleLatticeWebHullMod` 252 行（`ship.owner == 0 → entry.v2 else DifficultyTuningImpl.value(entry)`）。

**`SevenStarsChainMath`（纯函数，单测直接调用）**：

```kotlin
// 第 hitIndex 跳（1 起）的裂隙爆炸倍率 = 首发倍率 × (1 + min((hitIndex-1) × perJumpBonus, bonusCap))
fun flashMult(tuning: SevenStarsTuning, hitIndex: Int): Float

// 终结段数与逐段伤害表：multi=false → [0.5f]；multi=true → max(1, jumps) 段，第 i 段 = min(0.5 + 0.25*i, 2.0)
fun terminalDamageFractions(multi: Boolean, jumps: Int): List<Float>

fun jumpRange(weaponRange: Float): Float   // = weaponRange * 0.5f；weaponRange <= 0 → 记 WARN 返回 0（见 §2.4）

// 推进判定：jumps >= MAX_JUMPS 或 无 PD 候选 → TERMINAL，其余 → JUMP；击杀数不参与（仅作遥测）
enum class ChainStep { JUMP, TERMINAL }
fun nextChainStep(jumps: Int, hasPdCandidates: Boolean): ChainStep =
    if (jumps >= MAX_JUMPS || !hasPdCandidates) TERMINAL else JUMP
```

**`SevenStarsTargetSelector.select(engine, from, jumpRange, owner, aoeDamage)`**：

```
候选 = engine.missiles.filter { it.owner != owner && engine.isEntityInPlay(it) && !it.isExpired }
     + engine.ships.filter  { it.isFighter && !it.isHulk && it.isAlive && it.owner != owner }
     剔除 dist(from, it) > jumpRange（距离用平方比较，不开方）
排序键 = (可摧毁 desc, 距离 asc)
  可摧毁 = it.hitpoints <= aoeDamage（预估剩余耐久 ≤ 本轮爆炸伤害；护盾吸收不预估，记为已知简化）
终结目标 = engine.ships.filter { !it.isFighter && !it.isHulk && it.isAlive && it.owner != owner }
           .minByOrNull { 距离²(from, it.location) }   // 无范围限制（设计案：最近敌舰，不设距离闸）
```

**`SevenStarsDamageHandler.flashExplosion(engine, at, direct, source, owner, panelDamage, mult, aoeRadius)`**（裂隙爆炸伤害结算；时点由调用方 pending 队列控制）：

```
1. 粗筛：CombatUtils.getEntitiesWithinRange(at, aoeRadius)（GCP 已验证的空间网格路径）
2. 逐目标：owner 过滤、isHulk/isExpired 剔除、source 自身剔除
3. 结算：engine.applyDamage(target, point, panelDamage × mult, DamageType.ENERGY, 0f, false, false, source, true)
   - direct 目标与区域内目标同额（§0-3）；point 对带盾舰船吸附盾面（复用 GCP resolveShieldedDamagePoint 同款思路，
     实装时若该函数可见性允许则直接复用，否则在本类内落同名私有实现并注明出处）
4. 摧毁统计：结算后同步判定 target.isHulk || !engine.isEntityInPlay(target) ||
   (target as? MissileAPI) 已移除——applyDamage 为同步结算；kills 仅作遥测，不参与推进判定
```

**延迟爆炸结算（pending 队列）**：每跳落点登记一项（位置、倍率、scale），`EXPLOSION_DELAY = 0.5f` 后到期，由脚本 `advance` 逐项执行：

```
1. SevenStarsDamageHandler.flashExplosion(engine, at, …)   // 结算伤害（量与 AOE_RADIUS 同旧口径，仅时点延后；摧毁数仅作遥测）
2. SevenStarsVfx.riftBlast(engine, at, scale)              // 生成裂隙爆炸视觉（RiftExplosionVfx）+ 播放 riftcascade_rift 音
3. TELEMETRY_FLASH 自增                                    // 延迟爆炸结算时计数（遥测，见 §4.2 烟测）
```

**`SevenStarsChainScript.advance(amount)`**：

```kotlin
if (engine.isPaused) return
// 锚点方案下脚本不持有弹体实体（首发后已 removeEntity，§0-2 实机修订），无引用失效面
timer += amount
pending.advance(amount)   // 裂隙延迟爆炸队列：到期项执行结算（见上「延迟爆炸结算」）
when (state) {
    CHAIN_COOLDOWN -> if (timer >= CHAIN_COOLDOWN) { timer=0; doNextJump() }
    TERMINAL_MULTI -> 按 segments 表逐段计时引爆（段间隔 0.12s），末段后 DISSIPATE
    DISSIPATE -> { if (pending.isEmpty()) { SevenStarsVfx.dissipate(engine, anchor); isDone=true } }
        // 末跳爆炸晚于消散判定属预期：pending 排空后才收口（脚本随引擎回收，无实体可 remove，§0-2）
}

fun doNextJump() {
    val target = SevenStarsTargetSelector.select(...) ?: return enterTerminal()   // 无 PD 候选 → 终结
    SevenStarsVfx.teleport(engine, anchor, target.location)                        // 起点电弧+路径星云
    anchor.set(target.location)                                                    // 纯位置锚点跟进（§0-2 锚点方案）
    jumps++
    SevenStarsVfx.riftWindup(engine, anchor, scale = 1f + 0.1f*(jumps-1))          // 落点立即裂隙征兆（ping 光圈 + riftcascade_windup 音）
    pending.enqueue(at = anchor, mult = SevenStarsChainMath.flashMult(tuning, jumps),
                    scale = 1f + 0.1f*(jumps-1))                                    // 爆炸登记入队列，EXPLOSION_DELAY 后到期结算
    when (SevenStarsChainMath.nextChainStep(jumps, hasPdCandidates=true)) {
        JUMP -> state = CHAIN_COOLDOWN
        TERMINAL -> enterTerminal()
    }
}

fun enterTerminal() {
    val ship = SevenStarsTargetSelector.nearestHostileShip(...) ?: run { state = DISSIPATE; return }
    if (!tuning.multiSegmentTerminal) {
        // 单段（玩家恒此）：折跃至舰体，50% 面板一段，无 EMP
        SevenStarsVfx.teleport(engine, anchor, ship.location); anchor.set(ship.location)
        SevenStarsDamageHandler.terminalStrike(engine, ship, at=ship.location, panelDamage × 0.5f, emp=0f)
        SevenStarsVfx.riftBlast(engine, ship.location, scale = 1.2f)
        state = DISSIPATE
    } else {
        // v5 多段：沿碰撞箱走位取点（sampleRiftCascadePoints），段表 50%→…→200%，每段 EMP = panelDamage
        segments = ChainMath.terminalDamageFractions(true, jumps)
        points   = sampleRiftCascadePoints(ship, segments.size, anchor)   // 见下
        state = TERMINAL_MULTI   // advance 内按 0.12s 间隔逐段：applyDamage(frac×panel, emp=panel) + riftBlast(scale 1.2+0.1×段序)
    }
}
```

**沿舰体取点 `sampleRiftCascadePoints(ship, count, anchor)`**（直接移植原版 `RiftCascadeEffect.getNextArcLoc` 的碰撞箱贴边走位；原版算法已由反编译确认并直接移植，非自研近似）：

```
1. 首点：舰心→锚点（anchor）来向与碰撞箱圆周的交点（首点取碰撞圆周点）；
2. spawnDir：锚点视角下首点方位→舰心方位的最近转向（恒 0 取 +1）；
3. 逐点绕舰心角步进 360°×spacing/(2π×碰撞半径)，单点角步进 cap 90°；
   spacing = 175×0.7 = 122.5su；目标盾开启时 ×0.67；
4. 落点先取 getTargetingRadius 半径，再外扩 30~80su 抖动（视觉散布，非结算随机）；
5. 盾未开启且 exactBounds 可用时：吸附最近顶点，并沿顶点→落点方向回拉 50×0.9 su。
```

**结算顺序总表**：onFire（快照 tuning → 首发 select → teleport → 裂隙征兆 + 登记 pending 爆炸 → nextChainStep 判定 → 注册 script）→ 每帧 advance（冷却 → 续跳 select → teleport → 征兆 + 登记 pending → 判定；pending 到期逐项结算爆炸）→ 终结（单段一次 / 多段逐段）→ 消散（无舰；排空 pending 后置 DONE 收口）。所有 `applyDamage` 均为一次性调用（不触发 onHitEffect，无回环）；弹体本体 `collisionClass = NONE`，无第二伤害源。

### 2.3 玩家可见反馈（对照实现注意事项 2）

本武器为**单发瞬态机制**（无跨射叠层/常驻状态），`maintainStatusForPlayerShip` HUD 状态栏不适用；反馈逐机制映射如下（命中特效通道，00-共享基建 §4.2）：

| 机制 | 可见表现 | 通道 |
|---|---|---|
| 折跃（每跳） | 起点/终点 EMP 电弧 + 路径小星云 + 少量 flare | `spawnEmpArcVisual` + 粒子 |
| 裂隙延迟爆炸（每跳） | 落点立即 ping 光圈征兆 + `riftcascade_windup` 音；0.5s 后裂隙爆炸视觉 + `riftcascade_rift` 音 + 伤害数字；连跳第 N 跳 scale 递增（+10%/跳） | RenderEntity 裂隙原语（§3.3）+ 音效 |
| 连跳伤害提升 | 伤害数字（`applyDamage(..., showDamageNumbers=true)`）+ 上述裂隙爆炸递增 | 伤害浮字 + 特效 |
| 对舰终结（单段） | 大号裂隙爆炸（scale 1.2）+ 伤害数字 | 特效 + 伤害浮字 |
| 对舰终结（v5 多段） | 沿舰体 0.12s 间隔次第绽开的递增裂隙爆炸 + 每段 EMP 电弧连向武器/引擎槽 + 每段伤害数字 | 特效 + `spawnEmpArcVisual` + 伤害浮字 |
| 消散（无处可去） | 弹着点小星云淡出，无爆炸；已登记的延迟爆炸照常排空结算（末跳爆炸晚于消散判定属预期） | 粒子 |

每跳「折跃电弧 + 裂隙征兆」同帧触发，满足"机制变化同帧至少一个反馈通道"的铁律；伤害数字延后 0.5s 随爆炸结算出现。无数值文本（伤害倍率隐性缩放口径与设计案一致）。伤害浮字全部由 `applyDamage(showDamageNumbers = true)` 原生弹出（2026-07-29 审批确认：原生已弹字，不另绘自定义浮字）。

### 2.4 0 值与边界处理（对照实现注意事项 3）

| 输入 | 防线 |
|---|---|
| `weapon.getRange() <= 0`（异常装配/hullmod 归零） | `jumpRange` 记 WARN 并返回 0：select 候选恒空 → 首发直接进终结判定；不静默产出恒零折跃，也不除零（距离比较用平方，无除法） |
| `projectile.getDamageAmount() <= 0` | onFire 记 WARN，跳过全部结算直接 DISSIPATE（面板归零时"摧毁判定/段数表"全部以 0 伤害走通但不产生任何 applyDamage 调用） |
| 首发/续跳无 PD 候选 | 进 TERMINAL；TERMINAL 无敌舰 → DISSIPATE（设计案原文：连舰船目标也不存在，射弹直接消散） |
| 击杀数（各跳） | 仅作遥测，不参与推进判定（`nextChainStep` 不看 kills，固定 7 跳跑满） |
| 候选 hitpoints ≤ 0 / isHulk / isExpired | select 阶段剔除，不参与可摧毁预估 |
| 弹体引用失效 | 锚点方案下不存在（§0-2 实机修订：脚本只持纯位置 anchor，不持有弹体实体）；脚本生命周期随引擎回收，无残留插件（90-plan §7.6 风险项的等效消解） |
| `terminalDamageFractions(multi=true, jumps=0)`（首发即终结） | 段数 `max(1, jumps)` 保底 1 段 50%，不出现空段表 |
| 多段终结目标中途死亡/变 hulk | 逐段引爆前检查 `ship.isHulk || !engine.isEntityInPlay(ship)`：中止剩余段直接 DISSIPATE，剩余段表不结算 |
| 无舰消散时 pending 队列未排空 | DISSIPATE 状态下继续排空队列，全部结算后才置 DONE 收口（末跳爆炸晚于消散判定属预期，非竞态） |
| `bonusCap = 0`（自定义难度 k 插值出 0 的理论可能） | `flashMult` 中 `min((hitIndex-1)×perJump, cap)` 天然 clamp 到 0，倍率恒等于首发倍率；链式计算无除零路径（无除法） |

### 2.5 每帧成本说明

- 常驻成本为 0：无弹体时无插件注册（script 随单发注册、随收口移除）。
- 在场弹体至多 1 发/武器（2s 充能 + 链寿命 ≈3.4s 峰值 2 发）；每跳一次 `engine.missiles + engine.ships` 全表线性扫（战斗规模数百实体，每跳一次而非每帧），0.3s 冷却帧只做计时加法；pending 队列为到期项一次性批处理，无每帧新增扫描。
- 裂隙爆炸粗筛走 LazyLib 空间网格（GCP 已验证路径）。

## 3. 特效面

### 3.1 弹体 VFX：不登记 `ProjectileVfxSpecs`（决策记录）

设计案：射弹不做正常飞行、无常规飞行拖尾。texTrail 拖尾管线（`simpleProjectileVfx` 等）语义是"沿速度向采样轨迹"，对瞬移弹体只会画出乱线。故：

- `ProjectileVfxSpecs.builders` **不加条目**；
- `.proj` 隐藏四件套仍保留（§1.1），原版弹体全程不可见；
- 合并协议 §4.3 检查表第 1 项（弹体通道）逐项过检后结论为"不适用"，第 3 项（爆炸/冲击通道）为本武器的实际落点。

此决策消除本组对 `ProjectileVfxSpecs.kt` 的共享文件冲突面。

### 3.2 折跃 VFX（`SevenStarsVfx` 内，无登记项）

- 起止电弧：`spawnEmpArcVisual(from, source, to, target, thickness≈4f, fringe=#6FB4FF, core=#F0F8FF, params)`（`EmpArcParams` 字段已核实：`flickerRateMult`/`glowSizeMult` 等，参用 `ASTDIonizedRecoilAccumulatorHullMod` 的 params 构建样板）。
- 路径星云：沿 from→to 线段取 3~5 点 `addNebulaParticle` 同款冷蓝白烟雾（视觉散布用 `Misc.random`，非结算随机，符合 00-共享基建 §4.1-2）。

### 3.3 裂隙爆炸 VFX（`RiftExplosionVfx`，新共享渲染原语，辉星复用）

2026-08 裂隙改版：原「十字闪光爆炸」（`CrossFlashVfx`，文件已删除）全部替换为下述新通用组件；原首批计划 §11「十字闪光爆炸七星首发、辉星 60% 缩放复用」裁定由「裂隙爆炸组件七星首发、辉星半径 ×0.6 复用」替代。

| 项 | 值 |
|---|---|
| 文件 | `src/main/kotlin/cn/kasuminova/astd/renderer/effect/explosion/RiftExplosionVfx.kt` |
| API | `riftExplosion(engine, at, radius = DEFAULT_RADIUS, fadeOut = 1.0f, palette = BLUE): Int`（每次 2 枚裂隙 `NUM_RIFTS = 2`）；`spawnWindup(engine, at, radius, palette)`；`buildRiftParams(...)`（internal 纯函数） |
| 观感 | 复刻原版 `RiftCascadeMineExplosion → NegativeExplosionVisual`：黑色噪声圆盘 + corona_hard 扰动大气环 + 首裂隙命中闪光 + 次裂隙回漂 |
| 主色 | 蓝色族：border (70,130,255)、underglow (10,40,140,100)、windup (90,150,255,60) |
| 尺寸 | 总尺寸 -30%（`SIZE_SCALE = 0.7f`，`DEFAULT_RADIUS = 25×0.7 = 17.5su`，原版基准 25su） |
| 音效 | `SOUND_EXPLOSION` / `SOUND_WINDUP` 由调用方播放（组件本体只触 engine API，保证桩引擎单测可驱动） |
| 随机 | 组件内随机走 LazyLib `MathUtils` 而非 `Misc`（`Misc` 在单测 JVM 静态初始化失败） |

`SevenStarsVfx` 薄层：`riftWindup(engine, at, scale)` / `riftBlast(engine, at, scale)`（换算 scale/位置后调 `RiftExplosionVfx`）；scale 口径不变（连跳第 N 跳 `1+0.1×(N-1)`、终结 1.2、多段 `1.2+0.1×段序`）。辉星组实装时直接调 `riftExplosion` 传 `radius = DEFAULT_RADIUS × 0.6f`（`StellarMrmStrikeImpl.RIFT_RADIUS_SCALE`）。

## 4. 测试面

### 4.1 单元测试用例清单（`src/test/kotlin/cn/kasuminova/astd/combat/effect/arc/`）

全部调用 `SevenStarsChainMath` / `SevenStarsTargetSelector` / `SevenStarsDifficulty.snapshot` 的真实逻辑断言输出（selector 测试用手工构造的候选值对象列表驱动纯函数重载，不 mock 引擎；禁止纯源码 contain 测试）：

1. **`flashMult` 三锚点链**：v1 快照下 hitIndex=1 → 1.00、hitIndex=2 → 1.00×1.5、hitIndex=3 → 1.00×2.0（达 cap +100% 后 hitIndex=4~7 恒 2.0）；v2 快照 hitIndex=2 → 1.25×1.625、hitIndex=4 起恒 1.25×2.75；v5 快照 hitIndex=3 → 2.0×3.0、hitIndex=5 起恒 2.0×5.0（cap +400%）。逐档逐跳断言到浮点 1e-6。
2. **玩家固定 v2**：构造 `owner == 0` 的 source 快照，断言三个倍率恒等于 `entry.v2`，与 `DifficultyTuningImpl.fixedScale` 当前值无关；`multiSegmentTerminal == false`。
3. **多段终结解锁口径**：`owner != 0` 且 `fixedScale >= 5f` → true；`fixedScale` 为 1/2/3/4.99 → false（破晓限定，远征不解锁）。
4. **`terminalDamageFractions`**：multi=false → `[0.5f]`；multi=true 且 jumps=0/1 → `[0.5f]`；jumps=3 → `[0.5, 0.75, 1.0]`；jumps=7 → `[0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0]`；jumps=8（越界输入）→ 第 8 段起 clamp 在 2.0 且段数 = jumps（防御性断言，不静默截断）。
5. **`jumpRange`**：800 → 400；1600（吃射程修正后）→ 800；0 → 返回 0 且捕获 logger 有 WARN（断言日志输出，不静默恒零）。
6. **`nextChainStep` 推进判定矩阵**：jumps=7（kills 任意）→ TERMINAL；jumps<7 且 hasPdCandidates=false → TERMINAL；jumps<7 且 hasPdCandidates=true → JUMP；jumps=0（首发空爆，无候选）→ TERMINAL；击杀数不参与——任何 kills 输入不改变判定结果。
7. **目标排序（可摧毁优先）**：候选 A（hitpoints=100，距离 300）、B（hitpoints=500，距离 100），aoeDamage=200 → A 在前；两者皆可摧毁 → 距离近者在前（B 在前场景对照）。
8. **目标过滤矩阵**：自方 owner、敌方 hulk 战机、isExpired 导弹、距离 > jumpRange（恰等值纳入 / +1su 剔除）、非战机舰船不出现在 PD 候选；dist=0 重叠目标纳入排序首位（无除零）。
9. **终结最近敌舰**：纯舰船集合取距离最小者；含战机时战机不参与；空集 → null（→ DISSIPATE 路径）。
10. **护盾不预估的已知简化**：带盾战机 hitpoints=50、aelDamage=200 仍判"可摧毁"——断言当前行为并注释"护盾吸收不预估"，防后续误改语义。

### 4.2 烟测检查点（`deployMod` + `launchSmokeTestGame`，到达终态即退出，不干等超时）

1. dev 控制台 `addweapon astd_seven_stars` 装配大型能量槽；装配界面名称/tip/描述/主角色（折跃点防）显示正确，弯引号渲染正常。
2. 对导弹齐射目标开火：射弹**不可见飞行**（无弹体拖尾、无直线弹道），目标点立即 ping 光圈征兆 + 起止 EMP 电弧，约 0.5s 后裂隙爆炸。
3. 集群导弹场景：观察到连跳（裂隙征兆逐跳出现，裂隙爆炸间隔约 0.3s、逐跳变大），最多 7 次后停；7 次延迟爆炸全部结算。
4. 无 PD 目标空域开火：直接折跃最近敌舰，单段 50% 裂隙爆炸；四周无敌舰时弹着点小星云消散、无伤害数字。
5. NOKILL 相位（对增压不可摧毁鱼叉 HP=1e6）：零击杀链仍跑满 7 跳且 7 次延迟爆炸全结算（chainJumpsMax>=7 && flash delta>=7 && kills delta==0 && rift delta>=7）；随后移除靶舰 A 进 CHAIN。
6. LunaLib 切破晓档用敌版（AI 携带）观察对舰多段终结：沿舰体次第爆炸 + 每段 EMP 电弧。
7. 弹体碰撞验证：射弹"穿过"舰船/战机不产生任何触碰伤害数字（`collisionClass=NONE` 生效）。
8. 日志：无 NPE/异常；devMode FPS 在连跳峰值无可见掉帧。

## 5. 并行实装注意

### 5.1 本武器触碰的共享文件清单（按合并协议标注）

| 共享文件 | 动作 | 键名空间 / 追加位置 |
|---|---|---|
| `ss-csv/src/main/resources/i18n/zh-cn.properties` | 追加 | `weapon.astd_seven_stars.*` / `desc.astd_seven_stars.*`，文件末尾集中插入，不动他组键 |
| `ss-csv/.../strings/Catalog_Descriptions.kt` | 追加 1 行 | WEAPON 分组尾部（`Desc_astd_psi_omega` 之后），收口人按 id 字典序归位 |
| `ss-csv/.../weapondata/arc/Catalog_WeaponData_ARC.kt` | 追加 `Wpn_astd_seven_stars` | `number = 9216`（预分配段位），object 追加在文件末尾，收口人按 number 升序归位 |
| `contents/data/weapons/astd_seven_stars.wpn` | 新文件 | 无冲突 |
| `src/.../combat/effect/arc/SevenStars*.kt`（7 个） | 新文件 | 无冲突 |
| `src/.../renderer/effect/explosion/RiftExplosionVfx.kt` | 新文件 | 新子包，无冲突；辉星组按 §3.3 复用（半径 ×0.6） |
| `src/test/.../combat/effect/arc/SevenStars*Test.kt` | 新文件 | 无冲突 |
| `contents/data/campaign/special_items.csv` | **不动** | 超规格 P6 前 `no_drop`（§1.5） |
| `src/.../renderer/projectile/driver/ProjectileVfxSpecs.kt` | **不动** | 无飞行拖尾（§3.1） |

### 5.2 对共享基建的依赖项

- **Buff API：不消费**。连跳状态是单发弹体内的瞬态（寿命 ≈3.4s、随 `removeEntity` 终结），既非 Ship 级也非 Weapon 级持久叠层；强塞进 `StackableBuff` 反而要为空转的 `advance`/`onRemove` 语义付税。状态由 `SevenStarsChainScript` 实例字段自持。
- **ConeImpactHandler：不消费**。裂隙爆炸是圆形 AoE（半径 100su），非锥形筛选；若后续摧锋/辉星出现"圆形爆炸结算"复用诉求，再评审是否从 `SevenStarsDamageHandler` 上提公共件（当前单一调用方，不提前抽象）。
- **CombatRandom：不消费**。本武器无结算随机（伤害确定值）；裂隙组件内随机走 LazyLib `MathUtils`（`Misc` 在单测 JVM 静态初始化失败，见 §3.3），取点抖动为纯视觉随机不参与结算。
- **HUD 通道（§4.2）：不消费** `maintainStatusForPlayerShip`（单发瞬态，§2.3 已说明）；浮字/电弧/爆炸通道用引擎原生入口。
- **依赖已落地件**：`DifficultyTuning` / `ScalingEntry` / `ScalingMap.LINEAR`（api/difficulty，已核实签名）；`engine.addPlugin(BaseEveryFrameCombatPlugin)` 模式（`StackingShipBuffs.ensurePlugin` 同款）；LazyLib `CombatUtils.getEntitiesWithinRange`；`spawnEmpArcVisual`（含 `EmpArcParams` 重载）。

### 5.3 预估实现顺序内的位置

首批计划 §12 第 7 位（正电子之后、辉星之前）。关键约束：**裂隙爆炸原语（§3.3）必须先于辉星沉淀**（辉星按半径 ×0.6 复用），故本组交付物中 `RiftExplosionVfx` 属对后续组的阻塞件，PR 评审应优先看该件。不依赖正电子/穷距的任何产物，可在 Buff API / ConeImpactHandler 进 main 后与它们并行开工。

## 6. 验收要点（主代理逐项核对）

**数据面**

- [ ] `Wpn_astd_seven_stars` 各列与 §1.1 一致：`ops=28`、`chargedown=2.0`、`flightTime=6.0`（名义登记，§0-2 口径）、`aiHints=PD`、`tags=no_drop, no_drop_salvage`、`number=9216`
- [ ] `.proj` 生成物：`collisionClass=NONE`、无 `onHitEffect`、隐藏四件套齐全（length/width=2、双色 alpha=0、`BUtil_NONE.png`、`fadeTime=0.2`）
- [ ] `.wpn`：`projectileSpecId=astd_seven_stars_shot`，未挂 `everyFrameEffect`
- [ ] zh-cn.properties 8 个键齐全，名称为弯引号 `“七星”`，tip 为单段终结口径，`{%s}` 与 HL 数量一致
- [ ] `Desc_astd_seven_stars` 在 WEAPON 分组尾部；`special_items.csv` 无本组行
- [ ] `./gradlew :ss-csv:generateSsCsv` 无错，生成物 `weapon_data.csv` 本行 hints 列 = `PD`（无列错位）

**代码面**

- [ ] 7 个类落位 `combat/effect/arc/`，类名无 Service/Manager/Controller/Runtime，无接口族虚设
- [ ] 难度三锚点全部经 `ScalingEntry` + `DifficultyTuningImpl.value`，玩家 `owner == 0` 固定 v2；发射时刻快照
- [ ] 多段终结仅 `owner != 0 && fixedScale >= 5f`；玩家版恒单段 50% 无 EMP
- [ ] 状态机：固定 7 跳（击杀不参与推进，仅遥测）/ 达上限或无 PD 候选终结 / 无舰消散；裂隙延迟爆炸由 pending 队列排空结算，DISSIPATE 后置 DONE 收口
- [ ] 无空 catch、无静默兜底；`getRange()<=0`、`damageAmount<=0` 两处 WARN 日志在
- [ ] 首发结算后立即 `removeEntity`、连跳锚点为纯位置 anchor（§0-2 锚点方案）；脚本收口 `isDone` 后随引擎回收

**特效面**

- [ ] `ProjectileVfxSpecs.kt` 零改动（本组决策）
- [ ] `RiftExplosionVfx`：`riftExplosion(engine, at, radius, fadeOut, palette)` API、蓝色族 palette（border/underglow/windup）、`SIZE_SCALE = 0.7f`、`NUM_RIFTS = 2`；`SOUND_EXPLOSION`/`SOUND_WINDUP` 由调用方播放
- [ ] 折跃起止 EMP 电弧 + 路径星云；连跳裂隙爆炸逐跳递增；bloom 不过曝（目检）

**测试面**

- [ ] §4.1 十条用例全绿；无纯源码 contain 测试、无反射
- [ ] §4.2 烟测 8 项通过；烟测结束游戏进程已关闭

**目检**

- [ ] 弹体全程不可见飞行；裂隙爆炸构图（黑色噪声圆盘 + corona_hard 大气环 + 命中闪光）成立
- [ ] 七连爆节奏 0.3s 间隔 + 0.5s 延迟观感清晰不糊屏；v5 对舰多段"沿舰体次第绽开"成立
- [ ] 装配界面 / codex 文案显示完整（tip 两段、text1 + notes 深灰摘录）
