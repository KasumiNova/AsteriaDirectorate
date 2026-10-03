package cn.kasuminova.astd.sscsv.entries.catalog.weapondata.arc

import cn.kasuminova.astd.sscsv.GeneratedJsonFile
import cn.kasuminova.astd.sscsv.SsJsonOutputs
import cn.kasuminova.astd.sscsv.entries.AiHint
import cn.kasuminova.astd.sscsv.entries.WeaponDataEntry
import cn.kasuminova.astd.sscsv.entries.catalog.weapondata.weaponName
import cn.kasuminova.astd.sscsv.i18n.SsI18n
import cn.kasuminova.astd.sscsv.outputs.proj.MissileEngineSlot
import cn.kasuminova.astd.sscsv.outputs.proj.MissileEngineSlotStyleSpec
import cn.kasuminova.astd.sscsv.outputs.proj.MissileEngineSpec
import cn.kasuminova.astd.sscsv.outputs.proj.MissileProjSpec
import cn.kasuminova.astd.sscsv.outputs.proj.ProjectileProjSpec
import cn.kasuminova.astd.sscsv.outputs.proj.ProjectileSpawnType
import cn.kasuminova.astd.sscsv.outputs.proj.Rgba
import cn.kasuminova.astd.sscsv.outputs.proj.SsProjMissileOutputs
import cn.kasuminova.astd.sscsv.outputs.proj.SsProjProjectileOutputs
import cn.kasuminova.astd.sscsv.outputs.proj.Vec2
import cn.kasuminova.astd.sscsv.outputs.proj.Vec2i

/** ARC 系武器（weapon_data.csv）。 */

/**
 * 坠星残响（XC-001 星坠内置主炮，规格 10-signature 坠星残响节）：5 发连射弹匣炮。
 *
 * 前 4 发命中附加「结构谐振」叠层（装甲/结构易伤），第 5 发大号红色弹体命中恒爆发能量爆炸
 * （半径 150su×(层数+1)，爆炸伤害按消耗层数结算，每层被消耗的谐振使第 5 发伤害 +50%），
 * 随后消耗全部层数；弹匣低于 5 发时无法射击（隐藏机制，脚本侧闸）。
 * 第 5 发的替换/增幅由 onFireEffect（StarfallEchoOnFireEffect）承担，爆炸结算在
 * onHitEffect（StarfallEchoOnHitEffect）。
 */
object Wpn_astd_starfall_echo : WeaponDataEntry(), SsJsonOutputs {
    override val id: String = "astd_starfall_echo"
    override val name: String = weaponName(id)
    override val tier: Int = 3
    override val baseValue: Int = 50000
    override val range: Int = 1000
    override val damagePerShot: Int = 750

    // 一轮循环 = 5 发连射（0.1s × 4 间隔）+ 4.6s 开火间隔 ≈ 5.0s；tooltip 统计按整轮折算
    override val chargedown: Double = 4.6
    override val burstSize: Int = 5
    override val burstDelay: Double = 0.1
    override val turnRate: Int = 30
    override val type: String = "ENERGY"
    override val energyPerShot: Int = 1125
    override val projSpeed: Int = 2000

    // 精度「较差」档（对齐原版 vulcan min0/max15、chaingun max20 的较差散布带）：
    // 上限收敛到 15——内置挂载武器不过度扭曲弹道；5 发连射每发 +1，burst 间 5/s 衰减
    override val minSpread: Double = 2.0
    override val maxSpread: Double = 15.0
    override val spreadPerShot: Double = 1.0
    override val spreadDecayPerSec: Double = 5.0
    override val accuracyStr: String = "较差"

    // 弹匣：10 发，每 10s 恢复 5 发
    override val ammo: Int = 10
    override val ammoPerSec: Double = 0.5
    override val reloadSize: Int = 5

    // autofit 类别标签（CoreAutofitPlugin 按 类别+等级 匹配，缺失会导致装配方案无法装回本武器）
    // 等级对齐原版 LARGE ENERGY 带（18~22）
    override val tags: String = "energy20, astd_signature"
    override val groupTag: String = "astd"
    override val tech: String = "菀星设计局-星坠"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val customPrimary: String = SsI18n.t("weapon.$id.tooltip.customPrimary")
    override val customPrimaryHL: String = SsI18n.t("weapon.$id.tooltip.customPrimaryHL")
    override val number: Int = 9001

    /** 前 4 发：蓝白色锥形射弹（原版螺栓视觉屏蔽，弹头/拖尾由 VFX 管线承担）。 */
    val projSpec: ProjectileProjSpec = ProjectileProjSpec.boxBolt(
        id = "astd_starfall_echo_shot",
        onFireEffect = "cn.kasuminova.astd.combat.effect.arc.starfallecho.StarfallEchoOnFireEffect",
        onHitEffect = "cn.kasuminova.astd.combat.effect.arc.starfallecho.StarfallEchoOnHitEffect",
        fringeColor = Rgba(120, 190, 255, 255),
        coreColor = Rgba(240, 248, 255, 200),
        length = 120.0,
        width = 24.0,
    )

    override fun jsonExtraFiles(): List<GeneratedJsonFile> = listOf(
        GeneratedJsonFile("data/weapons/proj/${projSpec.id}.proj", projSpec.toJson()),
    )
}

/** 星坠：整船静态发光层（装配界面/战斗常驻 decorative lights）。 */
object Wpn_astd_xc_001_lights : WeaponDataEntry() {
    override val id: String = "astd_xc_001_lights"
    override val name: String = weaponName(id)
    override val tier: Int = 5
    override val baseValue: Int = 0
    override val range: Int = 0
    override val turnRate: Int = 0
    override val type: String = "OTHER"
    override val tags: String = "no_drop, no_drop_salvage"
    // 装饰/发光层武器：SYSTEM 隐藏图鉴与模拟战装配列表（原版 lights_buffalo 同口径）
    override val aiHints: Set<AiHint> = setOf(AiHint.SYSTEM)
    override val tech: String = "菀星设计局-星坠"
    override val noDpsInTooltip: Boolean = true
    override val number: Int = 9106
}

/** 星坠：整船 bloom 描边层（装配界面/战斗 decorative outline）。 */
object Wpn_astd_xc_001_lights_bloom : WeaponDataEntry() {
    override val id: String = "astd_xc_001_lights_bloom"
    override val name: String = weaponName(id)
    override val tier: Int = 5
    override val baseValue: Int = 0
    override val range: Int = 0
    override val turnRate: Int = 0
    override val type: String = "OTHER"
    override val tags: String = "no_drop, no_drop_salvage"
    // 装饰/发光层武器：SYSTEM 隐藏图鉴与模拟战装配列表（原版 lights_buffalo 同口径）
    override val aiHints: Set<AiHint> = setOf(AiHint.SYSTEM)
    override val tech: String = "菀星设计局-星坠"
    override val noDpsInTooltip: Boolean = true
    override val number: Int = 9125
}

/** 星翼：整船 bloom 描边层（装配界面/战斗 decorative outline）。 */
object Wpn_astd_xc_002_bloom : WeaponDataEntry() {
    override val id: String = "astd_xc_002_bloom"
    override val name: String = weaponName(id)
    override val tier: Int = 5
    override val baseValue: Int = 0
    override val range: Int = 0
    override val turnRate: Int = 0
    override val type: String = "OTHER"
    override val tags: String = "no_drop, no_drop_salvage"
    // 装饰/发光层武器：SYSTEM 隐藏图鉴与模拟战装配列表（原版 lights_buffalo 同口径）
    override val aiHints: Set<AiHint> = setOf(AiHint.SYSTEM)
    override val tech: String = "菀星设计局-星坠"
    override val noDpsInTooltip: Boolean = true
    override val number: Int = 9126
}

/** 列星：整船 bloom 描边层（装配界面/战斗 decorative outline）。 */
object Wpn_astd_xc_103_bloom : WeaponDataEntry() {
    override val id: String = "astd_xc_103_bloom"
    override val name: String = weaponName(id)
    override val tier: Int = 5
    override val baseValue: Int = 0
    override val range: Int = 0
    override val turnRate: Int = 0
    override val type: String = "OTHER"
    override val tags: String = "no_drop, no_drop_salvage"
    // 装饰/发光层武器：SYSTEM 隐藏图鉴与模拟战装配列表（原版 lights_buffalo 同口径）
    override val aiHints: Set<AiHint> = setOf(AiHint.SYSTEM)
    override val tech: String = "菀星设计局-星坠"
    override val noDpsInTooltip: Boolean = true
    override val number: Int = 9128
}

/** 烽燧：整船 bloom 描边层（装配界面/战斗 decorative outline）。 */
object Wpn_astd_xc_102_bloom : WeaponDataEntry() {
    override val id: String = "astd_xc_102_bloom"
    override val name: String = weaponName(id)
    override val tier: Int = 5
    override val baseValue: Int = 0
    override val range: Int = 0
    override val turnRate: Int = 0
    override val type: String = "OTHER"
    override val tags: String = "no_drop, no_drop_salvage"
    // 装饰/发光层武器：SYSTEM 隐藏图鉴与模拟战装配列表（原版 lights_buffalo 同口径）
    override val aiHints: Set<AiHint> = setOf(AiHint.SYSTEM)
    override val tech: String = "菀星设计局-星坠"
    override val noDpsInTooltip: Boolean = true
    override val number: Int = 9130
}

/** 逐电：整船 bloom 描边层（装配界面/战斗 decorative outline）。 */
object Wpn_astd_xc_104_bloom : WeaponDataEntry() {
    override val id: String = "astd_xc_104_bloom"
    override val name: String = weaponName(id)
    override val tier: Int = 5
    override val baseValue: Int = 0
    override val range: Int = 0
    override val turnRate: Int = 0
    override val type: String = "OTHER"
    override val tags: String = "no_drop, no_drop_salvage"
    // 装饰/发光层武器：SYSTEM 隐藏图鉴与模拟战装配列表（原版 lights_buffalo 同口径）
    override val aiHints: Set<AiHint> = setOf(AiHint.SYSTEM)
    override val tech: String = "菀星设计局-星坠"
    override val noDpsInTooltip: Boolean = true
    override val number: Int = 9131
}

/** 熔壁：整船 bloom 描边层（装配界面/战斗 decorative outline）。 */
object Wpn_astd_xc_101_bloom : WeaponDataEntry() {
    override val id: String = "astd_xc_101_bloom"
    override val name: String = weaponName(id)
    override val tier: Int = 5
    override val baseValue: Int = 0
    override val range: Int = 0
    override val turnRate: Int = 0
    override val type: String = "OTHER"
    override val tags: String = "no_drop, no_drop_salvage"
    // 装饰/发光层武器：SYSTEM 隐藏图鉴与模拟战装配列表（原版 lights_buffalo 同口径）
    override val aiHints: Set<AiHint> = setOf(AiHint.SYSTEM)
    override val tech: String = "菀星设计局-星坠"
    override val noDpsInTooltip: Boolean = true
    override val number: Int = 9129
}

/**
 * 坠星残翼（XC-002 星翼内置主炮，规格 10-signature 坠星残翼节）：固定 1.5s/发直射炮（无弹匣）。
 *
 * 主弹碰撞类别 NONE（原版触碰结算全关）：恒穿盾/穿船体高频伤害全部由脚本承担
 * （StarfallWingOnFireEffect 登记 + StarfallWingWeaponEffect 逐帧判定），onHitEffect 恒不触发；
 * 命中附加「振频适应」叠层（只削弱护盾承伤效率），飞行中每 0.2s 向两侧随机散发一枚追踪子射弹。
 */
object Wpn_astd_starfall_wing : WeaponDataEntry(), SsProjProjectileOutputs {
    override val id: String = "astd_starfall_wing"
    override val name: String = weaponName(id)
    override val tier: Int = 3
    override val baseValue: Int = 50000
    override val range: Int = 1200
    override val damagePerShot: Int = 1000
    override val emp: Int = 500

    // 固定 1.5s/发（chargedown，无弹匣）：tooltip 统计即面板口径
    override val chargedown: Double = 1.5
    override val burstSize: Int = 1
    override val burstDelay: Double = 0.0
    override val turnRate: Int = 30
    override val type: String = "ENERGY"
    override val energyPerShot: Int = 1000
    override val projSpeed: Int = 1500

    // autofit 类别标签（CoreAutofitPlugin 按 类别+等级 匹配，缺失会导致装配方案无法装回本武器）
    // 等级对齐原版 LARGE ENERGY 带（18~22）
    override val tags: String = "energy20, astd_signature"
    override val groupTag: String = "astd"
    override val tech: String = "菀星设计局-星坠"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val customPrimary: String = SsI18n.t("weapon.$id.tooltip.customPrimary")
    override val customPrimaryHL: String = SsI18n.t("weapon.$id.tooltip.customPrimaryHL")
    override val number: Int = 9002

    /** 主弹：紫色锥形射弹（原版螺栓视觉屏蔽，弹头/拖尾由 VFX 管线承担，尺寸对齐坠星残响 hero 体量 138×34）。 */
    override val projSpec: ProjectileProjSpec = ProjectileProjSpec.boxBolt(
        id = "astd_starfall_wing_shot",
        onFireEffect = "cn.kasuminova.astd.combat.effect.arc.starfallwing.StarfallWingOnFireEffect",
        // collisionClass=NONE 永无命中回调：护盾/船体结算全走脚本碰撞判定
        onHitEffect = null,
        collisionClass = "NONE",
        // 原版 ProjectileSpec 加载强制要求该键（缺键 RuntimeException）；与 collisionClass 同写 NONE
        // （七星 astd_seven_stars_shot 同款判例）。
        collisionClassByFighter = "NONE",
        fringeColor = Rgba(170, 110, 255, 255),
        coreColor = Rgba(240, 225, 255, 200),
        length = 138.0,
        width = 34.0,
    )
}

/**
 * 星翼：坠星残翼追踪子射弹真实导弹体（脚本生成，规格 10-signature 坠星残翼节）。
 *
 * 实体碰撞类别 MISSILE_NO_FF（原版导弹口径）：撞盾/撞船体/阻挡消散全走原版结算，
 * 撞盾附加 0.5 层振频适应用 proj 的 onHitEffect（StarfallWingMoteOnHitEffect，shieldHit 参数）；
 * 追踪由脚本指派 MissileAIPlugin（生成时 `missileAI = ...`，追击虚粒子同款范式），
 * 射出后前 1s 惯性直飞不追踪（StarfallWingMoteAi 内闸门）。
 */
object Wpn_astd_starfall_wing_mote_launcher : WeaponDataEntry(), SsProjMissileOutputs {
    override val id: String = "astd_starfall_wing_mote_launcher"
    override val name: String = weaponName(id)
    override val tier: Int = 5
    override val baseValue: Int = 0
    // 与主武器射程列保持一致（脚本 spawn 不消费射程，仅保数据真相不分裂）
    override val range: Int = 1200

    // 主弹面板 20%（1000 × 0.2）；真实结算由主武器脚本覆写 damageAmount
    override val damagePerShot: Int = 200
    override val turnRate: Int = 30
    override val type: String = "ENERGY"
    override val projSpeed: Int = 900
    override val flightTime: Double = 2.0
    override val projHitpoints: Int = 10000
    // 脚本 spawn 内部武器：SYSTEM 隐藏图鉴/装配列表 + 禁掉落（隐藏武器口径对齐双子星隐藏六件）
    override val tags: String = "no_drop, no_drop_salvage, astd_signature"
    override val aiHints: Set<AiHint> = setOf(AiHint.SYSTEM)
    override val groupTag: String = ""
    override val tech: String = "菀星设计局-星坠"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val noDpsInTooltip: Boolean = true
    override val number: Int = 9132

    override val projSpec: MissileProjSpec = MissileProjSpec(
        id = "astd_starfall_wing_mote",
        missileType = "MISSILE",
        // 撞盾附加 0.5 层振频适应（原版 OnHitEffectPlugin.onHit 的 shieldHit 参数口径）；
        // 伤害结算全走原版碰撞，脚本不再插手
        onHitEffect = "cn.kasuminova.astd.combat.effect.arc.starfallwing.StarfallWingMoteOnHitEffect",
        // 原版弹体贴图渲染屏蔽：本体由弹体 VFX 管线接管（追击虚粒子同款判例）
        sprite = "graphics/textures/BUtil_NONE.png",
        size = Vec2i(4, 4),
        center = Vec2(2, 2),
        collisionRadius = 7,
        // 原版导弹碰撞口径（无友伤）：撞盾/撞船体/被拦截全走原版
        collisionClass = "MISSILE_NO_FF",
        explosionColor = Rgba(170, 110, 255, 180),
        explosionRadius = 24,
        armingTime = 0.05,
        flameoutTime = 0.5,
        noEngineGlowTime = 999.0,
        fadeTime = 0.25,
        engineSpec = MissileEngineSpec(
            turnAcc = 1800,
            turnRate = 1440,
            acc = 1800,
            dec = 1600,
        ),
        engineSlots = emptyList(),
    )
}

/**
 * 星翼：裂隙折跃虚空锚雷发射器（隐藏武器，RiftShiftSystemStats 脚本 spawn 专用，不装配舰船）。
 *
 * 沿裂隙路径每 100su 布设一枚 PHASE_MINE 地雷（永不引爆：PROXIMITY_FUSE 引信 range=0 永不触发、
 * 撞不到任何实体——原版会为 PHASE_MINE 自动挂 GuidedProximityFuseAI，behaviorSpec 缺块会在
 * spawn 时 NPE，故引信声明必填、仅将触发半径归零），4000 面板伤害只为让原版 mine AI 规避逻辑
 * 把裂隙路径判定为致命威胁区，驱动敌舰主动绕行；地雷生命周期绑裂隙，闭合收拢完成后由脚本统一回收。
 */
object Wpn_astd_rift_mine_layer : WeaponDataEntry(), SsProjMissileOutputs {
    override val id: String = "astd_rift_mine_layer"
    override val name: String = weaponName(id)
    override val tier: Int = 5
    override val baseValue: Int = 0
    // 脚本 spawn 不消费射程；写 0 保隐藏武器语义（不出现在装配统计）
    override val range: Int = 0

    // 威慑面板（与 RiftShiftTuning.MINE_PANEL_DAMAGE 对齐；真实伤害恒零——地雷永不引爆）
    override val damagePerShot: Int = 4000
    override val type: String = "ENERGY"
    override val projSpeed: Int = 0
    override val flightTime: Double = 10.0
    override val projHitpoints: Int = 10000
    // 脚本 spawn 隐藏武器：SYSTEM 隐藏图鉴/装配列表 + 禁掉落（隐藏武器口径对齐双子星隐藏六件）
    override val tags: String = "no_drop, no_drop_salvage, astd_signature"
    override val aiHints: Set<AiHint> = setOf(AiHint.SYSTEM)
    override val groupTag: String = ""
    override val tech: String = "菀星设计局-星坠"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val noDpsInTooltip: Boolean = true
    override val number: Int = 9133

    override val projSpec: MissileProjSpec = MissileProjSpec(
        id = "astd_rift_mine",
        missileType = "PHASE_MINE",
        // 原版会为 PHASE_MINE 自动挂 GuidedProximityFuseAI（构造即解析 behaviorSpec，缺块 spawn 时
        // NPE——实机判例）；声明块照抄原版 minelayer_mine 结构，仅将触发/告警半径归零使永不引爆
        behaviorSpec = mapOf(
            "behavior" to "PROXIMITY_FUSE",
            "range" to 0,
            "slowToMaxSpeed" to false,
            "delay" to 3,
            "pingSound" to "mine_ping",
            "pingColor" to listOf(170, 110, 255, 0),
            "pingRadius" to 0,
            "pingDuration" to 0.25,
            "windupSound" to "mine_windup_heavy",
            "windupDelay" to 1,
            "explosionSpec" to mapOf(
                "duration" to 0.1,
                "radius" to 0,
                "coreRadius" to 0,
                "collisionClass" to "MISSILE_FF",
                "collisionClassByFighter" to "MISSILE_FF",
                "particleSizeMin" to 3.0,
                "particleSizeRange" to 3.0,
                "particleDuration" to 1,
                "particleCount" to 0,
                "particleColor" to listOf(170, 110, 255, 0),
                "explosionColor" to listOf(170, 110, 255, 0),
                "useDetailedExplosion" to false,
                "sound" to "mine_explosion",
            ),
        ),
        // 原版弹体贴图渲染屏蔽：地雷本体完全不可见，裂隙路径视觉由 VFX 管线承担
        sprite = "graphics/textures/BUtil_NONE.png",
        size = Vec2i(4, 4),
        center = Vec2(2, 2),
        collisionRadius = 20,
        // NONE：地雷永不与任何实体碰撞，永不触发引爆链
        collisionClass = "NONE",
        collisionClassAfterFlameout = "NONE",
        renderTargetIndicator = false,
        explosionColor = Rgba(170, 110, 255, 0),
        explosionRadius = 0,
        armingTime = 0.0,
        flameoutTime = 0.5,
        noEngineGlowTime = 999.0,
        fadeTime = 0.25,
        // 全零推进：锚雷驻留原位不飘移（脚本 spawn 时速度同步清零）
        engineSpec = MissileEngineSpec(
            turnAcc = 0,
            turnRate = 0,
            acc = 0,
            dec = 0,
        ),
        engineSlots = emptyList(),
    )
}

/** 电荷针刺：小型能量弹匣速射（量产）。护盾命中淤积抬维持，船体命中概率泄放 EMP 电弧（机制见 ChargeNeedleOnHitEffect）。 */
object Wpn_astd_charge_needle : WeaponDataEntry(), SsProjProjectileOutputs {
    override val id: String = "astd_charge_needle"
    override val name: String = weaponName(id)
    override val tier: Int = 1
    override val rarity: Int = 1
    override val baseValue: Int = 6000
    override val range: Int = 700
    override val damagePerShot: Int = 50
    override val emp: Int = 200
    override val turnRate: Int = 30
    override val ops: Int = 8

    // 非 Beam：用 chargedown/burst 描述射速（20 发/s），避免 tooltip 统计除 0 溢出
    override val chargedown: Double = 0.05
    override val burstSize: Int = 1
    override val burstDelay: Double = 0.0

    // 弹匣三列：30 发弹匣，2.5 发/s 回复，每次装填 15 发
    override val ammo: Int = 30
    override val ammoPerSec: Double = 2.5
    override val reloadSize: Int = 15
    override val type: String = "ENERGY"
    override val energyPerShot: Int = 40

    override val projSpeed: Int = 1350

    override val minSpread: Double = 5.0
    override val maxSpread: Double = 15.0
    override val spreadPerShot: Double = 0.75
    override val spreadDecayPerSec: Double = 5.0

    // autofit 类别标签（CoreAutofitPlugin 按 类别+等级 匹配，缺失会导致装配方案无法装回本武器）
    override val tags: String = "energy8, astd_production"
    override val groupTag: String = "astd"
    override val tech: String = "菀星设计局-星坠"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val customPrimary: String = SsI18n.t("weapon.$id.tooltip.customPrimary")
    override val customPrimaryHL: String = SsI18n.t("weapon.$id.tooltip.customPrimaryHL")
    override val number: Int = 9210

    override val projSpec: ProjectileProjSpec = ProjectileProjSpec.boxBolt(
        id = "astd_charge_needle_shot",
        spawnType = ProjectileSpawnType.BALLISTIC,
        onHitEffect = "cn.kasuminova.astd.combat.effect.arc.chargeneedle.ChargeNeedleOnHitEffect",
        fringeColor = Rgba(140, 200, 255, 255),
        coreColor = Rgba(225, 242, 255, 200),
        // 细针观感：弹体宽度 −75%
        width = 5.0,
    )
}

/** 重型电荷针刺：中型能量弹匣速射（量产）。机制与小型完全同源（供弹深度与持续火力加倍）。 */
object Wpn_astd_heavy_charge_needle : WeaponDataEntry(), SsProjProjectileOutputs {
    override val id: String = "astd_heavy_charge_needle"
    override val name: String = weaponName(id)
    override val tier: Int = 1
    override val rarity: Int = 1
    override val baseValue: Int = 14000
    override val range: Int = 700
    override val damagePerShot: Int = 50
    override val emp: Int = 200
    override val turnRate: Int = 30
    override val ops: Int = 16

    // 非 Beam：用 chargedown/burst 描述射速（20 发/s），避免 tooltip 统计除 0 溢出
    override val chargedown: Double = 0.05
    override val burstSize: Int = 1
    override val burstDelay: Double = 0.0

    // 弹匣三列：60 发弹匣，5 发/s 回复，每次装填 30 发
    override val ammo: Int = 60
    override val ammoPerSec: Double = 5.0
    override val reloadSize: Int = 30
    override val type: String = "ENERGY"
    override val energyPerShot: Int = 40

    // 持续 5 发/s × 40 折算
    override val projSpeed: Int = 1350

    override val minSpread: Double = 5.0
    override val maxSpread: Double = 15.0
    override val spreadPerShot: Double = 0.75
    override val spreadDecayPerSec: Double = 5.0

    override val extraArcForAI: Int = 25

    // autofit 类别标签（CoreAutofitPlugin 按 类别+等级 匹配，缺失会导致装配方案无法装回本武器）
    override val tags: String = "energy14, astd_production"
    override val groupTag: String = "astd"
    override val tech: String = "菀星设计局-星坠"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val customPrimary: String = SsI18n.t("weapon.$id.tooltip.customPrimary")
    override val customPrimaryHL: String = SsI18n.t("weapon.$id.tooltip.customPrimaryHL")
    override val number: Int = 9211

    override val projSpec: ProjectileProjSpec = ProjectileProjSpec.boxBolt(
        id = "astd_heavy_charge_needle_shot",
        spawnType = ProjectileSpawnType.BALLISTIC,
        onHitEffect = "cn.kasuminova.astd.combat.effect.arc.chargeneedle.ChargeNeedleOnHitEffect",
        fringeColor = Rgba(140, 200, 255, 255),
        coreColor = Rgba(225, 242, 255, 200),
        // 细针观感：弹体宽度 −75%
        width = 5.0,
    )
}

/**
 * 电驱加速炮：中型实弹连发（量产，规格 03 §1.1）。
 *
 * 双管交替射击 × 连发 2（2026-09-19 二轮由 LINKED 齐射 2×4 改为 ALTERNATING 2×2，
 * 每触发 2 弹，由 `.wpn` 双炮管 offsets + barrelMode ALTERNATING 承担）；
 * 不稳定装药随机附加伤害走 `.proj` onHitEffect，净空加速射程加成走 `.wpn` everyFrameEffect。
 */
object Wpn_astd_electric_drive_accelerator : WeaponDataEntry(), SsProjProjectileOutputs {
    override val id: String = "astd_electric_drive_accelerator"
    override val name: String = weaponName(id)
    override val tier: Int = 1
    override val rarity: Int = 1
    override val baseValue: Int = 11000
    override val range: Int = 750

    override val damagePerShot: Int = 120
    override val impact: Int = 4
    override val turnRate: Int = 30
    override val ops: Int = 15

    // 发射冷却 0.3s + 连发 2（连射间隔 0.15s；双管交替走 .wpn ALTERNATING）
    override val chargedown: Double = 0.3
    override val burstSize: Int = 4
    override val burstDelay: Double = 0.15

    // 弹匣三列：20 发弹匣，1.6 发/s 回复（重装 5s/+8），每次装填 8 发
    override val ammo: Int = 20
    override val ammoPerSec: Double = 1.6
    override val reloadSize: Int = 8
    override val type: String = "KINETIC"

    override val energyPerShot: Int = 150
    override val projSpeed: Int = 1000

    override val minSpread: Double = 4.0
    override val maxSpread: Double = 8.0
    override val spreadPerShot: Double = 2.0
    override val spreadDecayPerSec: Double = 4.0

    // autofit 类别标签（CoreAutofitPlugin 按 类别+等级 匹配，缺失会导致装配方案无法装回本武器）
    override val tags: String = "kinetic12, astd_production"
    override val groupTag: String = "astd"
    override val tech: String = "菀星设计局-星坠"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val customPrimary: String = SsI18n.t("weapon.$id.tooltip.customPrimary")
    override val customPrimaryHL: String = SsI18n.t("weapon.$id.tooltip.customPrimaryHL")
    override val number: Int = 9213

    override val projSpec: ProjectileProjSpec = ProjectileProjSpec.boxBolt(
        id = "astd_electric_drive_accelerator_shot",
        spawnType = ProjectileSpawnType.BALLISTIC,
        onHitEffect = "cn.kasuminova.astd.combat.effect.arc.eda.ElectricDriveAcceleratorOnHitEffect",
        fringeColor = Rgba(255, 220, 120, 255),
        coreColor = Rgba(255, 240, 200, 200),
        // 弹体宽度 −50%、长度 −25%（散射弹小型化观感）
        length = 56.25,
        width = 10.0,
    )
}

/**
 * “穷距”相位轨道炮：大型实弹站桩演算主炮（量产，规格 05 §1.1）。
 *
 * 持续演算叠层（同目标命中 +1 层伤害/射速，异目标按保留比例折算，3s 窗口后按速率衰减）
 * 走 `.proj` onHitEffect + Weapon 级叠层 Buff；完美精度 + 非常慢转向走 spec 面板。
 */
object Wpn_astd_qiongjue_phase_railgun : WeaponDataEntry(), SsProjProjectileOutputs {
    override val id: String = "astd_qiongjue_phase_railgun"
    override val name: String = weaponName(id)
    override val tier: Int = 2
    override val baseValue: Int = 25000
    override val range: Int = 1100
    override val damagePerShot: Int = 640
    override val turnRate: Int = 8
    override val ops: Int = 28

    // 定案 2.5s 开火间隔 = 1s 开火充能 + 1.5s 冷却（非 beam 必须走 chargedown/burst，避免 tooltip 统计除 0）
    override val chargeup: Double = 1.0
    override val chargedown: Double = 1.5
    override val burstSize: Int = 1
    override val burstDelay: Double = 0.0

    override val type: String = "KINETIC"

    // 单发伤害；energy/second 置 0——充能武器该列会被 ChargeFireTracker
    // 在 1s 充能期间按秒真实扣辐（原版高斯炮同口径留空），填 450 时每周期实际扣 900+450=1350；
    // tooltip 持续辐能由派生公式 sustainedDps × fluxPerDam 自动算回 450/s
    override val energyPerShot: Int = 960
    override val projSpeed: Int = 1800
    override val turnRateStr: String = "非常慢"
    override val accuracyStr: String = "完美"

    // 完美精度（对齐原版高斯炮口径）
    override val autofireAccBonus: Int = 1

    // autofit 类别标签（CoreAutofitPlugin 按 类别+等级 匹配，缺失会导致装配方案无法装回本武器）
    override val tags: String = "kinetic18, LR, astd_production"
    override val groupTag: String = "astd"
    override val tech: String = "菀星设计局-星坠"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val customPrimary: String = SsI18n.t("weapon.$id.tooltip.customPrimary")
    override val customPrimaryHL: String = SsI18n.t("weapon.$id.tooltip.customPrimaryHL")
    override val number: Int = 9214

    override val projSpec: ProjectileProjSpec = ProjectileProjSpec.boxBolt(
        id = "astd_qiongjue_phase_railgun_shot",
        spawnType = ProjectileSpawnType.BALLISTIC,
        onHitEffect = "cn.kasuminova.astd.combat.effect.arc.qiongjue.QiongjuePhaseRailgunOnHitEffect",
        fringeColor = Rgba(200, 225, 255, 255),
        coreColor = Rgba(255, 255, 255, 200),
        // 弹体宽度 −50%、长度 +25%（细长轨道弹观感）
        length = 93.75,
        width = 10.0,
    )
}

/**
 * 正电子冲击波：小型能量点防御近炸弹（量产，规格 06 §1.1）。
 *
 * 近炸引信（锥程 40% 触发圈，仅导弹/战机/无人机）+ 满射程引爆走 `.proj` onFireEffect 注册引信脚本；
 * 撞舰即时引爆走 `.proj` onHitEffect（2026-09 修订：弹体识别舰船对象，不再穿过，collisionClass
 * 升级为 PROJECTILE_NO_FF 原版高爆同口径）；锥状冲击结算复用基建 ConeImpactHandler。
 * 弹体 VFX 追踪与引信注册组合在同一个 `.proj` onFireEffect（PositronShockwaveOnFireEffect 内委托
 * ProjectileSpecOnFireDispatcher）——原版 WeaponSpecLoader 不读 `.wpn` 的 onFireEffect 键。
 */
object Wpn_astd_positron_shockwave : WeaponDataEntry(), SsProjProjectileOutputs {
    override val id: String = "astd_positron_shockwave"
    override val name: String = weaponName(id)
    override val tier: Int = 1
    override val baseValue: Int = 2500
    override val range: Int = 600

    override val damagePerShot: Int = 200
    override val turnRate: Int = 45
    override val ops: Int = 6

    // 发射间隔 1.5s（非 Beam 用 chargedown 描述射速，避免 tooltip 统计除 0）
    override val chargedown: Double = 1.5
    override val burstSize: Int = 1
    override val burstDelay: Double = 0.0

    override val type: String = "FRAGMENTATION"
    override val energyPerShot: Int = 100

    // 100 ÷ 1.5s 折算
    override val projSpeed: Int = 900

    // 弹体原版寿命：原版会将其钳制为 range ÷ projSpeed（≈0.667s），故取值 ≥ 该值即可（0.75 留余量）。
    // 淡出与满射程同帧发生，引信脚本满射程判定先于淡出兜底执行（第四轮烟测实证钳制机制）。
    override val flightTime: Double = 0.75
    override val aiHints: Set<AiHint> = setOf(AiHint.PD)

    // autofit 类别标签（CoreAutofitPlugin 按 类别+等级 匹配，缺失会导致装配方案无法装回本武器）
    override val tags: String = "pd7, SR, astd_production"
    override val groupTag: String = "astd"
    override val tech: String = "菀星设计局-星坠"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val customPrimary: String = SsI18n.t("weapon.$id.tooltip.customPrimary")
    override val customPrimaryHL: String = SsI18n.t("weapon.$id.tooltip.customPrimaryHL")
    override val number: Int = 9215

    override val projSpec: ProjectileProjSpec = ProjectileProjSpec.boxBolt(
        id = "astd_positron_shockwave_shot",
        spawnType = ProjectileSpawnType.BALLISTIC,
        // 引信脚本注册 + VFX 追踪（PositronShockwaveOnFireEffect 内组合 ProjectileSpecOnFireDispatcher）
        onFireEffect = "cn.kasuminova.astd.combat.effect.arc.positronshockwave.PositronShockwaveOnFireEffect",
        // 撞舰引爆路径（2026-09 修订：弹体识别舰船对象，不再穿过）
        onHitEffect = "cn.kasuminova.astd.combat.effect.arc.positronshockwave.PositronShockwaveOnHitEffect",
        // 识别舰船/战机碰撞（原版高爆同口径 NO_FF 不误伤友军）；导弹仍由近炸引信承担
        collisionClass = "PROJECTILE_NO_FF",
        collisionClassByFighter = "PROJECTILE_NO_FF",
        fringeColor = Rgba(140, 200, 255, 255),
        coreColor = Rgba(240, 248, 255, 200),
    )
}

/**
 * “七星”折跃发射器：大型能量点防御超规格（规格 07 §1.1）。
 *
 * 射弹不做正常飞行：发射即折跃至目标位置并闪光十字爆炸，连跳/对舰终结全部脚本结算
 * （`.proj` onFireEffect 挂 SevenStarsOnFireEffect，collisionClass=NONE 无触碰/无 onHit 路径）。
 * 弹体视觉全程隐藏（Static Trail 管线不登记，规格 §3.1 决策）；P6 前 no_drop 仅 dev 测试。
 */
object Wpn_astd_seven_stars : WeaponDataEntry(), SsProjProjectileOutputs {
    override val id: String = "astd_seven_stars"
    override val name: String = weaponName(id)
    override val tier: Int = 3

    // 超规格对标 aod7（2026-07-29 审批裁定，弃 60000 提案）
    override val baseValue: Int = 150000
    override val range: Int = 800

    // 250 / 2s，tooltip 展示口径
    override val damagePerSecond: Int = 125
    override val damagePerShot: Int = 250

    // 面板 EMP 为 0；v5 终结 EMP 是脚本结算，不进面板
    override val emp: Int = 0
    override val impact: Int = 0
    override val turnRate: Int = 30
    override val ops: Int = 28
    override val type: String = "ENERGY"
    override val energyPerShot: Int = 750

    // 射速 2s/发
    override val chargedown: Double = 2.0
    override val burstSize: Int = 1
    override val burstDelay: Double = 0.0

    // 名义值；弹体由脚本瞬移接管，speed 仅影响 AI 预判与默认寿命（已被 flightTime 覆盖）
    override val projSpeed: Int = 3000

    // 显式寿命上限保险（规格 §0-2）：连跳预算 ≈3.4s，默认 range/projSpeed≈0.27s 会在第 2 跳前被引擎回收
    override val flightTime: Double = 6.0
    override val aiHints: Set<AiHint> = setOf(AiHint.PD)

    // P6 前 no_drop 口径（90-plan §14）；autofit 类别标签对齐原版 Omega 组合（大型能量 PD：guardian=pd19 / 大槽能量带 18~22），
    // restricted + codex_unlockable 对齐原版 Omega 稀有度语义（不进 procgen/定制生产池，图鉴获得后解锁）
    override val tags: String = "pd19, energy20, no_drop, no_drop_salvage, restricted, codex_unlockable"
    override val groupTag: String = "astd"
    override val tech: String = "菀星设计局-星坠"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val customPrimary: String = SsI18n.t("weapon.$id.tooltip.customPrimary")
    override val customPrimaryHL: String = SsI18n.t("weapon.$id.tooltip.customPrimaryHL")
    override val number: Int = 9216

    override val projSpec: ProjectileProjSpec = ProjectileProjSpec(
        id = "astd_seven_stars_shot",
        spawnType = ProjectileSpawnType.BALLISTIC,
        onFireEffect = "cn.kasuminova.astd.combat.effect.arc.sevenstars.SevenStarsOnFireEffect",
        // collisionClass=NONE 永无命中回调
        onHitEffect = null,
        // 射弹发射即折跃，碰撞类别 NONE 杜绝瞬移间隙帧的原版触碰结算（规格 §0-1）
        collisionClass = "NONE",
        // 规格 §1.1“置空不写”与 06 组实机判例冲突：原版 ProjectileSpec 加载强制要求该键
        // （缺键 RuntimeException）；与 collisionClass 同写 NONE（vanilla inimical_emanation_shot.proj 先例，
        // 规格文本待主代理修订）。
        collisionClassByFighter = "NONE",
        // 原版 projectile visual 必须不可见（length/width=2 + 色 alpha=0 + BUtil_NONE）。
        length = 2.0,
        width = 2.0,
        // fadeTime=0.2：脚本以 removeEntity 主动收口，此窗口仅为引擎 fade 回收路径留滑行余量（规格 §0-2）。
        fadeTime = 0.2,
        fringeColor = Rgba(120, 200, 255, 0),
        coreColor = Rgba(220, 245, 255, 0),
        textureScrollSpeed = 0.0,
        pixelsPerTexel = 1.0,
        bulletSprite = "graphics/textures/BUtil_NONE.png",
    )
}

// ============================================================
// 双子星 DEM（规格 10 §1.1）：主武器×2 + 隐藏弹头×2 + 隐藏 payload 光束×2，number 9221~9226。
// 机制：dummy 导弹 onFire 拦截移除，脚本 spawn 动能/高爆双弹头（TrackAI 追踪 + 原版 DEMScript 接管
// 锁定/充能/payload 光束打击）；两弹异种配对 1s 窗口命中同目标追加同步冲击。
// ============================================================

/** 双子星 DEM 发射器（中型导弹架）：一次齐射双异色 DEM 弹头，ammo 2 / 12s 节奏。 */
object Wpn_astd_gemini_dem_launcher : WeaponDataEntry(), SsProjMissileOutputs {
    override val id: String = "astd_gemini_dem_launcher"
    override val name: String = weaponName(id)
    override val tier: Int = 2
    override val rarity: Int = 1
    override val baseValue: Int = 6000
    override val range: Int = 2000

    // 非持续武器：damage/second 留 0（原版约定 beam 行才填 dps）
    override val damagePerSecond: Int = 0

    // 单弹面板：burst=2 驱动 tooltip「1000 x2」显示（原版 squall/locust 先例：burst 列即面板 xN 口径）；
    // 一次触发引擎连发 2 发 dummy 各扣 1 弹药，次发由 SalvoOnFireEffect 回声去重（规格 §2.2）
    override val damagePerShot: Int = 1000
    override val energyPerShot: Int = 500

    // EMP 电弧面板：实际机制为固定 5 道 × 总量 200% 面板（v2），本列仅面板展示口径
    override val emp: Int = 1000
    override val turnRate: Int = 30
    override val ops: Int = 14
    override val ammo: Int = 4
    override val ammoPerSec: Double = 0.0333
    override val reloadSize: Int = 2

    // 对齐龙炎显示惯例（同步冲击为能量伤害）
    override val type: String = "ENERGY"
    override val chargedown: Double = 12.0
    override val burstSize: Int = 2
    override val burstDelay: Double = 0.1
    override val projSpeed: Int = 225

    // 2000su ÷ 225 ≈ 8.9s 上浮（烟测校正面；弹速不变，飞行时长保持 14s）
    override val flightTime: Double = 14.0
    override val projHitpoints: Int = 600

    // autofit 类别标签（CoreAutofitPlugin 按 类别+等级 匹配，缺失会导致装配方案无法装回本武器）
    override val tags: String = "missile12, strike8, astd_production"
    override val groupTag: String = "astd"
    override val tech: String = "菀星设计局-星坠"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val customPrimary: String = SsI18n.t("weapon.$id.tooltip.customPrimary")
    override val customPrimaryHL: String = SsI18n.t("weapon.$id.tooltip.customPrimaryHL")
    // 物品 tooltip 的 DPS 与每秒辐能产出隐藏（noDPSInTooltip 仅作用于物品 tooltip，同闸覆盖两行；图鉴面板不读该标志仍显示；锁定/同步冲击机制不进面板）
    override val noDpsInTooltip: Boolean = true
    // AI 行为对齐原版龙炎 DEM：不瞄准直接发射、定位为打击武器
    override val aiHints: Set<AiHint> = setOf(AiHint.DO_NOT_AIM, AiHint.STRIKE)
    override val number: Int = 9221

    // dummy 导弹：发射同帧被 GeminiDemSalvoOnFireEffect 拦截移除，数值只保证“发射即拦截”不出异常（规格 §1.3）。
    // 弹体贴图即装填渲染贴图（RENDER_LOADED_MISSILES 按炮管渲染本 sprite；双色染色/光效由 GeminiDemRackVisuals 叠加）。
    override val projSpec: MissileProjSpec = MissileProjSpec(
        id = "astd_gemini_dem_dummy",
        missileType = "MISSILE",
        onFireEffect = "cn.kasuminova.astd.combat.effect.arc.geminidem.GeminiDemSalvoOnFireEffect",
        sprite = "graphics/weapons/astd_gemini_dem_missile.png",
        size = Vec2i(12, 25),
        center = Vec2(6, 12.5),
        collisionRadius = 7,
        collisionClass = "MISSILE_NO_FF",
        explosionColor = Rgba(0, 0, 0, 0),
        explosionRadius = 0,
        flameoutTime = 0.5,
        noEngineGlowTime = 999.0,
        fadeTime = 0.25,
        engineSpec = MissileEngineSpec(turnAcc = 1800, turnRate = 1440, acc = 1800, dec = 1600),
        engineSlots = emptyList(),
    )
}

/** 双子星 DEM 发射舱（大型导弹架）：与发射器共用 dummy 弹头（.wpn 侧 projectileSpecId 复用），ammo 4。 */
object Wpn_astd_gemini_dem_pod : WeaponDataEntry() {
    override val id: String = "astd_gemini_dem_pod"
    override val name: String = weaponName(id)
    override val tier: Int = 2
    override val rarity: Int = 1
    override val baseValue: Int = 14000
    override val range: Int = 2000
    override val damagePerSecond: Int = 0
    override val damagePerShot: Int = 1000
    override val energyPerShot: Int = 500
    override val emp: Int = 1000
    override val turnRate: Int = 30
    override val ops: Int = 28
    override val ammo: Int = 8
    override val ammoPerSec: Double = 0.0666
    override val reloadSize: Int = 2
    override val type: String = "ENERGY"
    override val chargedown: Double = 12.0
    override val burstSize: Int = 2
    override val burstDelay: Double = 0.1
    override val projSpeed: Int = 225
    override val flightTime: Double = 14.0
    override val projHitpoints: Int = 600

    // autofit 类别标签（CoreAutofitPlugin 按 类别+等级 匹配，缺失会导致装配方案无法装回本武器）
    override val tags: String = "missile17, strike13, astd_production"
    override val groupTag: String = "astd"
    override val tech: String = "菀星设计局-星坠"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val customPrimary: String = SsI18n.t("weapon.$id.tooltip.customPrimary")
    override val customPrimaryHL: String = SsI18n.t("weapon.$id.tooltip.customPrimaryHL")
    // 物品 tooltip 的 DPS 与每秒辐能产出隐藏（与发射器同口径；仅作用于物品 tooltip，图鉴面板不读该标志仍显示）
    override val noDpsInTooltip: Boolean = true
    // AI 行为对齐原版龙炎 DEM：不瞄准直接发射、定位为打击武器
    override val aiHints: Set<AiHint> = setOf(AiHint.DO_NOT_AIM, AiHint.STRIKE)
    override val number: Int = 9222
}

/** 双子星 DEM 动能弹头（隐藏内部武器，永不装配/掉落）：冷蓝白，附带固定 5 道（0.2s 间隔）EMP 电弧打击。 */
object Wpn_astd_gemini_dem_kinetic : WeaponDataEntry(), SsProjMissileOutputs {
    override val id: String = "astd_gemini_dem_kinetic"
    override val name: String = weaponName(id)
    override val tier: Int = 2
    override val baseValue: Int = 0
    override val range: Int = 2000

    // 展示口径；真实伤害由 payload 行结算（dps × burstSize 1s）
    override val damagePerShot: Int = 1000
    override val emp: Int = 1000
    override val turnRate: Int = 30
    override val type: String = "KINETIC"
    override val projSpeed: Int = 225
    override val flightTime: Double = 14.0
    override val projHitpoints: Int = 600
    // 隐藏内部武器：SYSTEM 隐藏图鉴/装配列表（设计案 10-gemini-dem §349：隐藏件 = no_drop + hints SYSTEM）
    override val tags: String = "no_drop, no_drop_salvage"
    override val aiHints: Set<AiHint> = setOf(AiHint.SYSTEM)
    override val tech: String = "菀星设计局-星坠"
    override val noDpsInTooltip: Boolean = true
    override val number: Int = 9223

    override val projSpec: MissileProjSpec = geminiDemWarheadProjSpec(
        id = "astd_gemini_dem_kinetic_msl",
        payloadWeaponId = "astd_gemini_dem_kinetic_payload",
        targetingLaserId = "astd_gemini_dem_targetinglaser_kinetic",
        explosionColor = Rgba(140, 190, 255, 180),
        engineColor = Rgba(140, 190, 255, 255),
        contrailColor = Rgba(120, 170, 255, 75),
    )
}

/** 双子星 DEM 高爆弹头（隐藏内部武器，永不装配/掉落）：共振红，专职拆甲。 */
object Wpn_astd_gemini_dem_he : WeaponDataEntry(), SsProjMissileOutputs {
    override val id: String = "astd_gemini_dem_he"
    override val name: String = weaponName(id)
    override val tier: Int = 2
    override val baseValue: Int = 0
    override val range: Int = 2000
    override val damagePerShot: Int = 1000
    override val emp: Int = 0
    override val turnRate: Int = 30
    override val type: String = "HIGH_EXPLOSIVE"
    override val projSpeed: Int = 225
    override val flightTime: Double = 14.0
    override val projHitpoints: Int = 600
    // 隐藏内部武器：SYSTEM 隐藏图鉴/装配列表（设计案 10-gemini-dem §349：隐藏件 = no_drop + hints SYSTEM）
    override val tags: String = "no_drop, no_drop_salvage"
    override val aiHints: Set<AiHint> = setOf(AiHint.SYSTEM)
    override val tech: String = "菀星设计局-星坠"
    override val noDpsInTooltip: Boolean = true
    override val number: Int = 9224

    override val projSpec: MissileProjSpec = geminiDemWarheadProjSpec(
        id = "astd_gemini_dem_he_msl",
        payloadWeaponId = "astd_gemini_dem_he_payload",
        targetingLaserId = "astd_gemini_dem_targetinglaser_he",
        explosionColor = Rgba(255, 60, 70, 180),
        engineColor = Rgba(255, 90, 100, 255),
        contrailColor = Rgba(255, 60, 70, 75),
    )
}

/**
 * 双弹头 .proj 公共骨架（规格 10 §1.3）：两 spec 结构相同，差异在 payloadWeaponId 与三色配色。
 * behaviorSpec 键名含原版拼写（`destroyMissleWhenDoneFiring`），逐字照抄。
 * 引擎参数 = 龙炎 ×1.5；targetingTime 2s（提案收紧，烟测目检面）。
 */
private fun geminiDemWarheadProjSpec(
    id: String,
    payloadWeaponId: String,
    targetingLaserId: String,
    explosionColor: Rgba,
    engineColor: Rgba,
    contrailColor: Rgba,
): MissileProjSpec = MissileProjSpec(
    id = id,
    missileType = "MISSILE",
    // 单一路径：DEMScript 由 GeminiDemSalvoOnFireEffect 手动挂载（规格 §0.1 事实 #3/#4）
    onFireEffect = null,
    // 贴图沿用 ASTD 双子星导弹本体（与 dummy 装填渲染一致；异色区分由引擎喷流/爆炸色/payload 光束承担）
    sprite = "graphics/weapons/astd_gemini_dem_missile.png",
    size = Vec2i(12, 25),
    center = Vec2(6, 12.5),
    collisionRadius = 7,
    collisionClass = "MISSILE_NO_FF",
    explosionColor = explosionColor,
    explosionRadius = 50,
    armingTime = 0.3,
    flameoutTime = 0.5,
    noEngineGlowTime = 0.0,
    fadeTime = 0.25,
    engineSpec = MissileEngineSpec(turnAcc = 225, turnRate = 75, acc = 600, dec = 105),
    engineSlots = listOf(
        MissileEngineSlot(
            id = "ES1",
            loc = Vec2i(-13, 0),
            style = "CUSTOM",
            styleSpec = MissileEngineSlotStyleSpec(
                mode = "QUAD_STRIP",
                engineColor = engineColor,
                glowSizeMult = 2.5,
                contrailDuration = 1.0,
                contrailWidthMult = 1.0,
                contrailWidthAddedFractionAtEnd = 2.5,
                contrailMinSeg = 5,
                contrailMaxSpeedMult = 0.5,
                contrailAngularVelocityMult = 0.5,
                contrailSpawnDistMult = 1.0,
                contrailColor = contrailColor,
                type = "GLOW",
            ),
            width = 7.0,
            length = 40.0,
            angle = 180.0,
        ),
    ),
    behaviorSpec = mapOf(
        "behavior" to "CUSTOM",
        "minDelayBeforeTriggering" to 0.5,
        "triggerDistance" to listOf(700, 750),
        "preferredMinFireDistance" to listOf(700, 750),
        "turnRateBoost" to 100,
        // 提案：龙炎为 3，设计“短暂充能”收紧到 2；烟测目检
        "targetingTime" to 2,
        "firingTime" to 1,
        // 异色锁定激光：动能蓝白 / 高爆共振红（隐藏 beam 武器，结构对齐原版 targetinglaser3）
        "targetingLaserId" to targetingLaserId,
        "targetingLaserFireOffset" to listOf(8, 0, 8, 0),
        "targetingLaserSweepAngles" to listOf(0, -7, 0, 7),
        "payloadWeaponId" to payloadWeaponId,
        "targetingLaserRange" to 900,
        "targetingLaserArc" to 10,
        "bombPumped" to true,
        "fadeOutEngineWhenFiring" to false,
        "destroyMissleWhenDoneFiring" to false,
        "snapFacingToTargetIfCloseEnough" to false,
    ),
)

/** 双子星 DEM 动能 payload 光束（隐藏结算武器）：dps 1000 × burstSize 1s = 1000 动能/发（100% 面板）。 */
object Wpn_astd_gemini_dem_kinetic_payload : WeaponDataEntry() {
    override val id: String = "astd_gemini_dem_kinetic_payload"
    override val name: String = weaponName(id)
    override val tier: Int = 2
    override val baseValue: Int = 0

    // 光束射程（原版 dragon_payload=1000 判例；规格 §1.1 未给该列，缺省 0 会令光束长度归零无法命中）
    override val range: Int = 1000

    // 结算口径：damage/second × burstSize(1s)（烟测 R2 读数校准面）
    override val damagePerSecond: Int = 1000

    // beam 行惯例：damage/shot 留空（toRow 已按原版约定留空）
    override val damagePerShot: Int = 0
    override val type: String = "KINETIC"

    // 单次 1s 照射
    override val burstSize: Int = 1
    override val burstDelay: Double = 0.0

    // 对齐 dragon_payload
    override val beamSpeed: Int = 1000000
    override val aiHints: Set<AiHint> = setOf(AiHint.SYSTEM, AiHint.DANGEROUS)
    override val tags: String = "fires_one_burst, no_drop, no_drop_salvage"
    override val tech: String = "菀星设计局-星坠"
    override val noDpsInTooltip: Boolean = true
    override val number: Int = 9225
}

/** 双子星 DEM 高爆 payload 光束（隐藏结算武器）：dps 1500 × burstSize 1s = 1500 高爆/发（150% 面板）。 */
object Wpn_astd_gemini_dem_he_payload : WeaponDataEntry() {    override val id: String = "astd_gemini_dem_he_payload"
    override val name: String = weaponName(id)
    override val tier: Int = 2
    override val baseValue: Int = 0

    // 光束射程（同动能 payload：原版 dragon_payload=1000 判例）
    override val range: Int = 1000
    override val damagePerSecond: Int = 1500
    override val damagePerShot: Int = 0
    override val type: String = "HIGH_EXPLOSIVE"
    override val burstSize: Int = 1
    override val burstDelay: Double = 0.0
    override val beamSpeed: Int = 1000000
    override val aiHints: Set<AiHint> = setOf(AiHint.SYSTEM, AiHint.DANGEROUS)
    override val tags: String = "fires_one_burst, no_drop, no_drop_salvage"
    override val tech: String = "菀星设计局-星坠"
    override val noDpsInTooltip: Boolean = true
    override val number: Int = 9226
}

// ---------- 战机版弹头链（数据驱动 ×0.75 削弱，number 9249~9252） ----------
// 实机判例（2026-09-27）：DEMScript 打击段新建 FX drone 承载 payload 光束，弹头导弹 customData 不传递到
// drone，实体标记通道不成立；削弱只能走 spec 数据通道——独立弹头 spec（damagePerShot 750、behaviorSpec
// payloadWeaponId 指向战机版 payload）+ 独立 payload spec（dps 动能 750 / 高爆 1125 = 750×1.5）。
// EMP 电弧单道为脚本显式数值，由 GeminiDemPayloadBeamEffect 按 payload weaponId 乘算 0.75。

/** 双子星 DEM 动能弹头·战机版（隐藏内部武器）：面板 750（舰装 1000 ×0.75），payload 指向战机版光束。 */
object Wpn_astd_gemini_dem_kinetic_fighter : WeaponDataEntry(), SsProjMissileOutputs {
    override val id: String = "astd_gemini_dem_kinetic_fighter"
    override val name: String = weaponName(id)
    override val tier: Int = 2
    override val baseValue: Int = 0
    override val range: Int = 1500

    // 撞碰面板（战机版实值）；真实打击伤害由战机版 payload 行结算（dps × burstSize 1s）
    override val damagePerShot: Int = 750
    override val emp: Int = 750
    override val turnRate: Int = 30
    override val type: String = "KINETIC"
    override val projSpeed: Int = 225
    override val flightTime: Double = 14.0
    override val projHitpoints: Int = 600
    // 隐藏内部武器：SYSTEM 隐藏图鉴/装配列表（设计案 10-gemini-dem §349：隐藏件 = no_drop + hints SYSTEM）
    override val tags: String = "no_drop, no_drop_salvage"
    override val aiHints: Set<AiHint> = setOf(AiHint.SYSTEM)
    override val tech: String = "菀星设计局-星坠"
    override val noDpsInTooltip: Boolean = true
    override val number: Int = 9249

    override val projSpec: MissileProjSpec = geminiDemWarheadProjSpec(
        id = "astd_gemini_dem_kinetic_fighter_msl",
        payloadWeaponId = "astd_gemini_dem_kinetic_payload_fighter",
        targetingLaserId = "astd_gemini_dem_targetinglaser_kinetic",
        explosionColor = Rgba(140, 190, 255, 180),
        engineColor = Rgba(140, 190, 255, 255),
        contrailColor = Rgba(120, 170, 255, 75),
    )
}

/** 双子星 DEM 高爆弹头·战机版（隐藏内部武器）：面板 750，payload 指向战机版光束。 */
object Wpn_astd_gemini_dem_he_fighter : WeaponDataEntry(), SsProjMissileOutputs {
    override val id: String = "astd_gemini_dem_he_fighter"
    override val name: String = weaponName(id)
    override val tier: Int = 2
    override val baseValue: Int = 0
    override val range: Int = 1500
    override val damagePerShot: Int = 750
    override val emp: Int = 0
    override val turnRate: Int = 30
    override val type: String = "HIGH_EXPLOSIVE"
    override val projSpeed: Int = 225
    override val flightTime: Double = 14.0
    override val projHitpoints: Int = 600
    // 隐藏内部武器：SYSTEM 隐藏图鉴/装配列表（设计案 10-gemini-dem §349：隐藏件 = no_drop + hints SYSTEM）
    override val tags: String = "no_drop, no_drop_salvage"
    override val aiHints: Set<AiHint> = setOf(AiHint.SYSTEM)
    override val tech: String = "菀星设计局-星坠"
    override val noDpsInTooltip: Boolean = true
    override val number: Int = 9250

    override val projSpec: MissileProjSpec = geminiDemWarheadProjSpec(
        id = "astd_gemini_dem_he_fighter_msl",
        payloadWeaponId = "astd_gemini_dem_he_payload_fighter",
        targetingLaserId = "astd_gemini_dem_targetinglaser_he",
        explosionColor = Rgba(255, 60, 70, 180),
        engineColor = Rgba(255, 90, 100, 255),
        contrailColor = Rgba(255, 60, 70, 75),
    )
}

/** 双子星 DEM 动能 payload 光束·战机版（隐藏结算武器）：dps 750 × burstSize 1s = 750 动能/发（750 面板的 100%）。 */
object Wpn_astd_gemini_dem_kinetic_payload_fighter : WeaponDataEntry() {
    override val id: String = "astd_gemini_dem_kinetic_payload_fighter"
    override val name: String = weaponName(id)
    override val tier: Int = 2
    override val baseValue: Int = 0

    // 光束射程（同舰装 payload：原版 dragon_payload=1000 判例）
    override val range: Int = 1000
    override val damagePerSecond: Int = 750

    // beam 行惯例：damage/shot 留空（toRow 已按原版约定留空）
    override val damagePerShot: Int = 0
    override val type: String = "KINETIC"

    // 单次 1s 照射
    override val burstSize: Int = 1
    override val burstDelay: Double = 0.0

    // 对齐 dragon_payload
    override val beamSpeed: Int = 1000000
    override val aiHints: Set<AiHint> = setOf(AiHint.SYSTEM, AiHint.DANGEROUS)
    override val tags: String = "fires_one_burst, no_drop, no_drop_salvage"
    override val tech: String = "菀星设计局-星坠"
    override val noDpsInTooltip: Boolean = true
    override val number: Int = 9251
}

/** 双子星 DEM 高爆 payload 光束·战机版（隐藏结算武器）：dps 1125 × burstSize 1s = 1125 高爆/发（750 面板的 150%）。 */
object Wpn_astd_gemini_dem_he_payload_fighter : WeaponDataEntry() {
    override val id: String = "astd_gemini_dem_he_payload_fighter"
    override val name: String = weaponName(id)
    override val tier: Int = 2
    override val baseValue: Int = 0
    override val range: Int = 1000
    override val damagePerSecond: Int = 1125
    override val damagePerShot: Int = 0
    override val type: String = "HIGH_EXPLOSIVE"
    override val burstSize: Int = 1
    override val burstDelay: Double = 0.0
    override val beamSpeed: Int = 1000000
    override val aiHints: Set<AiHint> = setOf(AiHint.SYSTEM, AiHint.DANGEROUS)
    override val tags: String = "fires_one_burst, no_drop, no_drop_salvage"
    override val tech: String = "菀星设计局-星坠"
    override val noDpsInTooltip: Boolean = true
    override val number: Int = 9252
}

/**
 * 双子星 DEM 异色锁定激光 ×2（隐藏 beam 武器，.wpn 手写）：DEMScript 锁定段扫掠激光。
 * 结构对齐原版 targetinglaser3（纯视觉，pierceSet 全弹体穿透、无伤害结算），
 * 动能蓝白 / 高爆共振红；射程由 behaviorSpec `targetingLaserRange` 给出（900），行内 range 留 0。
 */
object Wpn_astd_gemini_dem_targetinglaser_kinetic : WeaponDataEntry() {
    override val id: String = "astd_gemini_dem_targetinglaser_kinetic"
    override val name: String = weaponName(id)
    override val tier: Int = 0
    override val baseValue: Int = 0
    override val type: String = "ENERGY"
    override val beamSpeed: Int = 1000000
    override val aiHints: Set<AiHint> = setOf(AiHint.SYSTEM)
    override val tags: String = "no_drop, no_drop_salvage"
    override val tech: String = "菀星设计局-星坠"
    override val noDpsInTooltip: Boolean = true
    override val number: Int = 9235
}

/** 高爆侧锁定激光（共振红），参数同上。 */
object Wpn_astd_gemini_dem_targetinglaser_he : WeaponDataEntry() {
    override val id: String = "astd_gemini_dem_targetinglaser_he"
    override val name: String = weaponName(id)
    override val tier: Int = 0
    override val baseValue: Int = 0
    override val type: String = "ENERGY"
    override val beamSpeed: Int = 1000000
    override val aiHints: Set<AiHint> = setOf(AiHint.SYSTEM)
    override val tags: String = "no_drop, no_drop_salvage"
    override val tech: String = "菀星设计局-星坠"
    override val noDpsInTooltip: Boolean = true
    override val number: Int = 9236
}

/**
 * 彗星冲击波（原“重型离子脉冲”，2026-09 更名）：大型能量弹匣 EMP 主炮（量产，规格 02 §1.1）。
 *
 * 离子脉冲炮大型化改进型：船体/装甲命中必叠 EMP 抗性削减层（易伤转化为隐藏机制），
 * 并按概率泄放 EMP 电弧（命中点落点，对齐电针口径），
 * 破晓敌版追加 EMP 贯穿补伤（机制见 HeavyIonPulseOnHitEffect）；
 * 双炮管交替射击（0.1s/发 4 连发、射击冷却 0.4s）走 `.wpn` 双管坐标 + ALTERNATING。
 */
object Wpn_astd_heavy_ion_pulse : WeaponDataEntry(), SsProjProjectileOutputs {
    override val id: String = "astd_heavy_ion_pulse"
    override val name: String = weaponName(id)
    override val tier: Int = 2
    override val rarity: Int = 1
    override val baseValue: Int = 24000
    override val range: Int = 800

    // 持续 1.6 发/s × 250 折算（照 aod7“持续 DPS”口径，弹匣回复速率封顶）
    override val damagePerSecond: Int = 400
    override val damagePerShot: Int = 250
    override val emp: Int = 500
    override val impact: Int = 0
    override val turnRate: Int = 20
    override val ops: Int = 27

    // 4 连发 0.1s/发、射击冷却 0.4s
    override val chargeup: Double = 0.05
    override val chargedown: Double = 0.4
    override val burstSize: Int = 4
    override val burstDelay: Double = 0.1

    // 弹匣三列：24 发弹匣，1.6 发/s 回复（8 发/5s），每次装填 8 发
    override val ammo: Int = 24
    override val ammoPerSec: Double = 1.6
    override val reloadSize: Int = 8
    override val type: String = "ENERGY"
    override val energyPerShot: Int = 275

    override val projSpeed: Int = 1000

    // 对齐原版 ionpulser 散布
    override val minSpread: Double = 3.0
    override val maxSpread: Double = 20.0
    override val spreadPerShot: Double = 1.0
    override val spreadDecayPerSec: Double = 4.0

    // autofit 类别标签（CoreAutofitPlugin 按 类别+等级 匹配，缺失会导致装配方案无法装回本武器）
    // 等级对齐原版 LARGE ENERGY 带（18~22）
    override val tags: String = "energy18, astd_production"
    override val groupTag: String = "astd"
    override val tech: String = "菀星设计局-星坠"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val customPrimary: String = SsI18n.t("weapon.$id.tooltip.customPrimary")
    override val customPrimaryHL: String = SsI18n.t("weapon.$id.tooltip.customPrimaryHL")
    override val number: Int = 9212

    override val projSpec: ProjectileProjSpec = ProjectileProjSpec.boxBolt(
        id = "astd_heavy_ion_pulse_shot",
        spawnType = ProjectileSpawnType.BALLISTIC,
        onHitEffect = "cn.kasuminova.astd.combat.effect.arc.heavyionpulse.HeavyIonPulseOnHitEffect",
        fringeColor = Rgba(140, 200, 255, 255),
        coreColor = Rgba(225, 242, 255, 200),
        // 弹体宽度 −50%
        width = 10.0,
    )
}

/**
 * 贯星之矛：大型 HYBRID 充能重矛（稀有掉落，规格 09 §1.1）。
 *
 * HYBRID 挂载由 `.wpn` 的 `"type":"ENERGY"` + `"mountTypeOverride":"HYBRID"` 承担
 * （实弹/能量槽皆可装配，属性/技能/船插按能量武器结算，vanilla cryoblaster 同款形态）；
 * 本表 `type` 列是 DamageType（ENERGY），与“视作能量武器”口径一致。
 * 命中锥状冲击（破片 + 同锚 EMP）走 `.proj` onHitEffect + 基建 ConeImpactHandler。
 * P6 前 no_drop 仅 dev 测试；P6 后改 T3~T4 支线赏金掉落（90-plan §14）。
 */
object Wpn_astd_piercing_lance : WeaponDataEntry(), SsProjProjectileOutputs {
    override val id: String = "astd_piercing_lance"
    override val name: String = weaponName(id)
    override val tier: Int = 3
    override val baseValue: Int = 60000
    override val range: Int = 1000

    // 2500 ÷ 7s 循环（充能 2s + 冷却 5s）折算 tooltip 统计口径
    override val damagePerSecond: Int = 357
    override val damagePerShot: Int = 2500

    // EMP 是锥状冲击机制产物，不上原生面板列
    override val emp: Int = 0
    override val impact: Int = 0
    override val turnRate: Int = 30
    override val ops: Int = 30

    // 充能 2s + 冷却 5s（非 beam 必须走 chargedown/burst 描述射速，避免 tooltip 统计除 0）
    override val chargeup: Double = 2.0
    override val chargedown: Double = 5.0
    override val burstSize: Int = 1
    override val burstDelay: Double = 0.0

    override val type: String = "ENERGY"
    override val energyPerShot: Int = 3000

    // “极快”：1000su 射程约 0.33s 飞行（提案值，目检面；aod7 为 2400）
    override val projSpeed: Int = 3000

    // P6 前 no_drop 口径；P6 后改赏金掉落（90-plan §14）。autofit 类别标签对齐原版 Omega 组合
    // （大型能量单发重炮：gauss/tachyon 口径 energy20 + LR），restricted + codex_unlockable 对齐 Omega 稀有度语义
    override val tags: String = "energy20, LR, no_drop, no_drop_salvage, restricted, codex_unlockable"
    override val groupTag: String = "astd"
    override val tech: String = "菀星设计局-星坠"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val customPrimary: String = SsI18n.t("weapon.$id.tooltip.customPrimary")
    override val customPrimaryHL: String = SsI18n.t("weapon.$id.tooltip.customPrimaryHL")
    override val number: Int = 9219

    override val projSpec: ProjectileProjSpec = ProjectileProjSpec.boxBolt(
        id = "astd_piercing_lance_shot",
        spawnType = ProjectileSpawnType.BALLISTIC,
        onHitEffect = "cn.kasuminova.astd.combat.effect.arc.piercinglance.PiercingLanceOnHitEffect",
        fringeColor = Rgba(120, 200, 255, 255),
        coreColor = Rgba(220, 245, 255, 200),
        // 圆球状弹体（规格 09 §3.1 修订）：等长等宽取代离子脉冲式长条螺栓
        length = 36.0,
        width = 36.0,
    )
}

/** 摧锋鱼雷公共面板/弹头口径（blue/30-superlative.md 定案 v1.0）：两槽位共用同一弹体 spec。 */
private fun cuifengTorpedoProjSpec(): MissileProjSpec = MissileProjSpec(
    id = "astd_cuifeng_torpedo_shot",
    missileType = "MISSILE",
    onFireEffect = "cn.kasuminova.astd.combat.effect.arc.cuifeng.CuifengTorpedoOnFireEffect",
    onHitEffect = "cn.kasuminova.astd.combat.effect.arc.cuifeng.CuifengTorpedoOnHitEffect",
    // 贴图用自制摧峰导弹（同图承担挂点 RENDER_LOADED_MISSILES 渲染与飞行本体渲染）；引擎辉光/尾焰隐藏，
    // 拖尾由 ProjectileVfxSpecs 贴图拖尾层承担（辉星同款口径）。
    sprite = "graphics/weapons/astd_cuifeng_missile.png",
    size = Vec2i(13, 35),
    center = Vec2(6.5, 17.5),
    collisionRadius = 15,
    collisionClass = "MISSILE_NO_FF",
    explosionColor = Rgba(140, 190, 255, 160),
    explosionRadius = 150,
    armingTime = 0.0,
    flameoutTime = 0.5,
    noEngineGlowTime = 999.0,
    fadeTime = 0.25,
    engineSpec = MissileEngineSpec(turnAcc = 2000, turnRate = 500, acc = 2000, dec = 2000),
    engineSlots = emptyList(),
)

/** 摧锋鱼雷（小型导弹，blue/30-superlative.md）：ARC 稀有反舰终结件，自适应增伤 + 硬辐推进。 */
object Wpn_astd_cuifeng_torpedo : WeaponDataEntry(), SsProjMissileOutputs {
    override val id: String = "astd_cuifeng_torpedo"
    override val name: String = weaponName(id)
    override val tier: Int = 3
    override val baseValue: Int = 20000
    override val range: Int = 1600

    // 导弹武器 DPS 列留空（对照 amsrm csv 行 damage/second 空）；自适应机制走脚本不进面板
    override val damagePerShot: Int = 1500

    override val turnRate: Int = 30
    override val ops: Int = 8

    // 备弹 2 发，60s/+1
    override val ammo: Int = 2
    override val ammoPerSec: Double = 0.0166
    override val reloadSize: Int = 1

    // 发射冷却 5s，单次发射量 1
    override val chargedown: Double = 5.0
    override val burstSize: Number = 1
    override val burstDelay: Double = 0.0

    override val type: String = "ENERGY"
    override val energyPerShot: Int = 500

    // “反物质 SRM 150% 航速”：amsrm projSpeed=1000 × 1.5；launch speed 对齐 amsrm 200
    override val projSpeed: Int = 1500
    override val launchSpeed: Int = 200

    // 1600 射程 / 1500 满速 ≈ 1.4s（含二段式慢速段）+ 追踪冗余（目检微调）
    override val flightTime: Double = 2.0
    override val projHitpoints: Int = 500

    override val trackingStr: String = "优秀"
    override val speedStr: String = "极快"

    // P6 前 no_drop 口径；P6 后改赏金掉落（90-plan §14）。autofit 类别标签对齐原版 Omega 组合
    // （小型打击导弹：atropos=strike9 / harpoon=missile8 口径），restricted + codex_unlockable 对齐 Omega 稀有度语义
    override val tags: String = "missile8, strike9, no_drop, no_drop_salvage, restricted, codex_unlockable"
    override val groupTag: String = "astd"
    override val tech: String = "菀星设计局-星坠"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val customPrimary: String = SsI18n.t("weapon.$id.tooltip.customPrimary")
    override val customPrimaryHL: String = SsI18n.t("weapon.$id.tooltip.customPrimaryHL")
    // 物品 tooltip 的 DPS 与每秒辐能产出隐藏（noDPSInTooltip 仅作用于物品 tooltip，同闸覆盖两行；图鉴面板不读该标志仍显示；自适应增伤机制不进面板）
    override val noDpsInTooltip: Boolean = true
    // AI 行为对齐原版龙炎鱼雷
    override val aiHints: Set<AiHint> = setOf(AiHint.DO_NOT_AIM, AiHint.STRIKE)
    override val number: Int = 9227

    override val projSpec: MissileProjSpec = cuifengTorpedoProjSpec()
}

/** 摧锋鱼雷发射器（中型导弹）：与小型共用弹体 spec，备弹经济 5/40s，冷却 12s。 */
object Wpn_astd_cuifeng_launcher : WeaponDataEntry() {
    override val id: String = "astd_cuifeng_launcher"
    override val name: String = weaponName(id)
    override val tier: Int = 3
    override val baseValue: Int = 40000
    override val range: Int = 1600

    override val damagePerShot: Int = 1500

    override val turnRate: Int = 30
    override val ops: Int = 16

    // 备弹 5 发，40s/+1
    override val ammo: Int = 5
    override val ammoPerSec: Double = 1.0 / 40.0
    override val reloadSize: Int = 1

    // 发射冷却 10s，单次发射量 1
    override val chargedown: Double = 10.0
    override val burstSize: Number = 1
    override val burstDelay: Double = 0.0

    override val type: String = "ENERGY"
    override val energyPerShot: Int = 500

    override val projSpeed: Int = 1500
    override val launchSpeed: Int = 200
    override val flightTime: Double = 2.0
    override val projHitpoints: Int = 500

    override val trackingStr: String = "优秀"
    override val speedStr: String = "极快"

    // 与小型同口径：P6 前 no_drop（90-plan §14）；autofit 类别标签对齐原版中型导弹舱带
    // （harpoonpod=missile12 / typhoon=strike13 口径），restricted + codex_unlockable 对齐 Omega 稀有度语义
    override val tags: String = "missile12, strike13, no_drop, no_drop_salvage, restricted, codex_unlockable"
    override val groupTag: String = "astd"
    override val tech: String = "菀星设计局-星坠"
    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val customPrimary: String = SsI18n.t("weapon.$id.tooltip.customPrimary")
    override val customPrimaryHL: String = SsI18n.t("weapon.$id.tooltip.customPrimaryHL")
    // 物品 tooltip 的 DPS 与每秒辐能产出隐藏（noDPSInTooltip 仅作用于物品 tooltip，同闸覆盖两行；图鉴面板不读该标志仍显示）
    override val noDpsInTooltip: Boolean = true
    // AI 行为对齐原版龙炎鱼雷
    override val aiHints: Set<AiHint> = setOf(AiHint.DO_NOT_AIM, AiHint.STRIKE)
    override val number: Int = 9228
}
