package cn.kasuminova.astd.sscsv.entries.catalog.weapondata.joint

import cn.kasuminova.astd.sscsv.entries.AiHint
import cn.kasuminova.astd.sscsv.entries.WeaponDataEntry
import cn.kasuminova.astd.sscsv.entries.catalog.weapondata.weaponName
import cn.kasuminova.astd.sscsv.i18n.SsI18n
import cn.kasuminova.astd.sscsv.outputs.proj.MissileEngineSlot
import cn.kasuminova.astd.sscsv.outputs.proj.MissileEngineSlotStyleSpec
import cn.kasuminova.astd.sscsv.outputs.proj.MissileEngineSpec
import cn.kasuminova.astd.sscsv.outputs.proj.MissileProjSpec
import cn.kasuminova.astd.sscsv.outputs.proj.Rgba
import cn.kasuminova.astd.sscsv.outputs.proj.SsProjMissileOutputs
import cn.kasuminova.astd.sscsv.outputs.proj.Vec2
import cn.kasuminova.astd.sscsv.outputs.proj.Vec2i

/**
 * 联制线（LH）武器：星尘发射器（飞星两线各内置 x2，机制设计见 `docs/design/ships/20-joint.md` §武器）。
 *
 * 光尘机制：弹体为 MOTE 型导弹（环绕源舰 600su 半径 flocking、主动撞击射程内敌目标，
 * 优先级 导弹 > 战机 > 舰船），AI 经 `ModPlugin.pickMissileAI` 钩子指派
 * （StardustMoteAiPicker），命中结算走 .proj onHitEffect（StardustMoteOnHitEffect）。
 * 双线仅配色差异（ARC 蓝 / LENS 紫），面板与机制完全一致。
 */

/** 飞星 (ARC)：整船 bloom 描边层（装配界面/战斗 decorative outline）。 */
object Wpn_astd_lh_001_bloom : WeaponDataEntry() {
    override val id: String = "astd_lh_001_bloom"
    override val name: String = weaponName(id)
    override val tier: Int = 5
    override val baseValue: Int = 0
    override val range: Int = 0
    override val turnRate: Int = 0
    override val type: String = "OTHER"
    override val tags: String = "no_drop, no_drop_salvage"
    override val tech: String = "菀星设计局-星坠"
    override val noDpsInTooltip: Boolean = true
    override val number: Int = 9133
}

/** 飞星 (LENS)：整船 bloom 描边层（装配界面/战斗 decorative outline）。 */
object Wpn_astd_lh_002_bloom : WeaponDataEntry() {
    override val id: String = "astd_lh_002_bloom"
    override val name: String = weaponName(id)
    override val tier: Int = 5
    override val baseValue: Int = 0
    override val range: Int = 0
    override val turnRate: Int = 0
    override val type: String = "OTHER"
    override val tags: String = "no_drop, no_drop_salvage"
    override val tech: String = "菀星设计局-紫菀"
    override val noDpsInTooltip: Boolean = true
    override val number: Int = 9134
}

/** 星尘发射器（ARC 线蓝色光尘）：1s 冷却 / 100 辐能每发 / ammo 8 / 单轮恢复 2 发 / 8s 一轮。 */
object Wpn_astd_lh_stardust_launcher_arc : WeaponDataEntry(), SsProjMissileOutputs {
    override val id: String = "astd_lh_stardust_launcher_arc"
    override val name: String = weaponName("astd_lh_stardust_launcher")
    override val tier: Int = 5
    override val rarity: Int = 1
    override val baseValue: Int = 0
    override val range: Int = 600

    // 冷却口径（原版导弹武器约定）：冷却由 chargedown 驱动（harpoon=1s / annihilator=5s），
    // damage/second 列仅光束武器使用、导弹留 0；chargedown=0 时持续秒伤推导 200/0 溢出 INT_MAX
    override val damagePerShot: Int = 200

    // EMP 电弧为面板等额 × 难度倍率（100%~500%），面板列展示基准倍率
    override val emp: Int = 200
    override val turnRate: Int = 30
    override val ammo: Int = 8
    override val ammoPerSec: Double = 0.25
    override val reloadSize: Int = 2
    override val type: String = "ENERGY"

    // 单发辐能 100（CSV 伤害类型列即 ENERGY，与 .wpn 安装类型 MISSILE 无关）
    override val energyPerShot: Int = 100
    override val chargedown: Double = 1.0

    // burst size 必须为 1（原版空列默认 1）：写 0 会把开火辐能结算与面板 fluxPerDam 推导
    // （energyPerShot × burstSize）乘成 0，辐能产出被静默吞掉、面板恒「不会产生辐能」
    override val burstSize: Number = 1

    // 喷出动能（对齐原版光尘发射器 30° 锥形喷洒 + 200 初速，弹体极限速度仍为 projSpeed 400）
    override val minSpread: Double = 30.0
    override val maxSpread: Double = 30.0
    override val projSpeed: Int = 400
    override val launchSpeed: Int = 200

    // 环绕待命寿命：未接敌光尘 60s 后自然熄灭（设计上光尘常驻环绕，弹药节奏由武器恢复承担）
    override val flightTime: Double = 60.0
    override val projHitpoints: Int = 400
    override val tags: String = "no_drop, no_drop_salvage"
    override val tech: String = "联制"

    // NO_MANUAL_FIRE：原版 AutofireManagerV2 对「导弹 + 弹药 ≤20 + 无本 hint」的武器组判不可用并
    // 关闭自动开火（舰尾 arc 0 永不可瞄 → 永不开火）；本 hint 让武器组保持自动开火，开火决策
    // 由 StardustLauncherAutofireAI 无条件接管（手动开火对本武器无意义，光尘全向自寻的）。
    override val aiHints: Set<AiHint> = setOf(AiHint.NO_MANUAL_FIRE)
    override val primaryRoleStr: String = SsI18n.t("weapon.astd_lh_stardust_launcher.primaryRoleStr")
    override val number: Int = 9240

    override val projSpec: MissileProjSpec = stardustMoteProj(
        id = "astd_lh_stardust_mote_arc",
        engineColor = Rgba(100, 165, 255, 255),
        contrailColor = Rgba(100, 165, 255, 25),
        explosionColor = Rgba(100, 165, 255, 180),
    )
}

/** 星尘发射器（LENS 线紫色光尘）：与 ARC 线仅配色差异。 */
object Wpn_astd_lh_stardust_launcher_lens : WeaponDataEntry(), SsProjMissileOutputs {
    override val id: String = "astd_lh_stardust_launcher_lens"
    override val name: String = weaponName("astd_lh_stardust_launcher")
    override val tier: Int = 5
    override val rarity: Int = 1
    override val baseValue: Int = 0
    override val range: Int = 600
    override val damagePerShot: Int = 200
    override val emp: Int = 200
    override val turnRate: Int = 30
    override val ammo: Int = 8
    override val ammoPerSec: Double = 0.25
    override val reloadSize: Int = 2
    override val type: String = "ENERGY"
    override val energyPerShot: Int = 100
    override val chargedown: Double = 1.0
    override val burstSize: Number = 1
    override val minSpread: Double = 30.0
    override val maxSpread: Double = 30.0
    override val projSpeed: Int = 400
    override val launchSpeed: Int = 200
    override val flightTime: Double = 60.0
    override val projHitpoints: Int = 400
    override val tags: String = "no_drop, no_drop_salvage"
    override val tech: String = "联制"

    // NO_MANUAL_FIRE：同 ARC 线（AutofireManagerV2 弹药 ≤20 判不可用问题，AI 开火无条件接管）
    override val aiHints: Set<AiHint> = setOf(AiHint.NO_MANUAL_FIRE)
    override val primaryRoleStr: String = SsI18n.t("weapon.astd_lh_stardust_launcher.primaryRoleStr")
    override val number: Int = 9241

    override val projSpec: MissileProjSpec = stardustMoteProj(
        id = "astd_lh_stardust_mote_lens",
        engineColor = Rgba(186, 120, 255, 255),
        contrailColor = Rgba(186, 120, 255, 25),
        explosionColor = Rgba(186, 120, 255, 180),
    )
}

/**
 * 光尘弹体 spec 公共骨架（双线仅配色不同）：弹体贴图复用原版光尘（设计案裁定），
 * GLOW 引擎样式对齐原版 mote.proj；onFireEffect 接弹体 VFX 分发器（模组通用 trail），
 * onHitEffect 为命中结算（额外伤害/EMP 电弧/硬辐能穿透概率）。
 */
private fun stardustMoteProj(
    id: String,
    engineColor: Rgba,
    contrailColor: Rgba,
    explosionColor: Rgba,
): MissileProjSpec = MissileProjSpec(
    id = id,
    missileType = "MOTE",
    onFireEffect = "cn.kasuminova.astd.combat.effect.generic.ProjectileSpecOnFireDispatcher",
    onHitEffect = "cn.kasuminova.astd.combat.effect.joint.stardust.StardustMoteOnHitEffect",
    sprite = "graphics/missiles/bomblet0.png",
    size = Vec2i(4, 4),
    center = Vec2(2, 2),
    collisionRadius = 11,
    collisionClass = "MISSILE_NO_FF",
    explosionColor = explosionColor,
    explosionRadius = 36,
    armingTime = 0.05,
    flameoutTime = 1.0,
    noEngineGlowTime = 0.5,
    fadeTime = 0.5,
    engineSpec = MissileEngineSpec(turnAcc = 600, turnRate = 400, acc = 650, dec = 650),
    engineSlots = listOf(
        MissileEngineSlot(
            id = "ES1",
            loc = Vec2i(0, 0),
            style = "CUSTOM",
            styleSpec = MissileEngineSlotStyleSpec(
                mode = "QUAD_STRIP",
                engineColor = engineColor,
                glowSizeMult = 1.0,
                contrailDuration = 2.0,
                contrailWidthMult = 1.0,
                contrailWidthAddedFractionAtEnd = 0.0,
                contrailMinSeg = 5,
                contrailMaxSpeedMult = -0.1,
                contrailAngularVelocityMult = 0.0,
                contrailSpawnDistMult = 1.0,
                contrailColor = contrailColor,
                type = "GLOW",
            ),
            width = 12.0,
            length = 35.0,
            angle = 180.0,
        ),
    ),
)
