package cn.kasuminova.astd.combat.effect.arc.starfallecho

import cn.kasuminova.astd.api.buff.buffHost
import cn.kasuminova.astd.combat.effect.arc.starfallecho.StarfallEchoOnFireEffect.Companion.FINAL_SHOT_MARK_KEY
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.OnHitEffectPlugin
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.combat.CombatUtils
import org.lwjgl.util.vector.Vector2f

/**
 * 坠星残响的命中路由（挂普通弹 `.proj` 的 `onHitEffect`；第 5 发与普通弹同 spec，
 * 由 [FINAL_SHOT_MARK_KEY] 标记区分）：
 *
 * - 普通弹：命中舰船（护盾/船体均可，设计案未区分）叠 1 层「结构谐振」
 *   （[StarfallEchoResonanceStacks]，至多 4 层，不随时间消散）；
 * - 第 5 发：命中恒爆炸（无论目标有无谐振层）——半径 150su×(层数+1)（0 层 150su 纯视觉、
 *   4 层封顶 750su）；范围结算等额能量伤害（伤害 = 提升后第 5 发面板 × 层数 × 难度倍率，
 *   层数 0 即 0，无 AOE），每层被消耗的谐振使第 5 发伤害 +50%（直击额外部分由脚本
 *   applyDamage 补给直击目标），后消耗全部层数，最后播放爆炸特效
 *   （[StarfallEchoVfx.explosion]，十字辉星跟随「目标舰心 → 命中点」方位交叉）。
 *
 * AOE 口径（摧锋同款裁定）：存活直击目标豁免 AOE（直击面板已由引擎原生结算，重复计入会双倍）；
 * 同阵营目标豁免。脚本 `applyDamage` 落点与 bypassShields 走七星/辉星/摧锋实机判例同款口径
 * （盾覆盖 → 盾面落点 + bypass=false；未覆盖 → 舰心落点 + bypass=true）。
 *
 * 难度取值每次命中调用 [StarfallEchoTuning.resolve] 一次（不缓存）。
 */
class StarfallEchoOnHitEffect : OnHitEffectPlugin {

    override fun onHit(
        projectile: DamagingProjectileAPI,
        target: CombatEntityAPI,
        point: Vector2f?,
        shieldHit: Boolean,
        damageResult: ApplyDamageResultAPI,
        engine: CombatEngineAPI,
    ) {
        if (engine.isPaused) return

        val ship = target as? ShipAPI ?: return
        if (ship.isHulk || ship.isPhased) return
        val hitPoint = point ?: projectile.location ?: return

        val values = StarfallEchoTuning.resolve(DifficultyTuningImpl, isPlayer = projectile.source?.owner == 0)

        if (projectile.customData[FINAL_SHOT_MARK_KEY] == true) {
            onFinalHit(projectile, ship, hitPoint, values, engine)
        } else {
            onNormalHit(projectile, ship, values, engine)
        }
    }

    /**
     * 普通弹命中：叠 1 层结构谐振（上限 4），每层易伤按难度查表。
     * 同阵营目标不叠层（与第 5 发 AOE 的同阵营豁免同口径；source 缺失时按 0 处理）。
     */
    private fun onNormalHit(
        projectile: DamagingProjectileAPI,
        ship: ShipAPI,
        values: StarfallEchoTuning.Values,
        engine: CombatEngineAPI,
    ) {
        if (ship.owner == (projectile.source?.owner ?: 0)) return
        val host = ship.buffHost()
        val buff = host.find(StarfallEchoResonanceStacks.BUFF_ID) as? StarfallEchoResonanceStacks
            ?: StarfallEchoResonanceStacks(ship, engine, host).also { host.register(it) }
        buff.perStack = values.vulnPerStack
        buff.addStacks(1)
        if (projectile.source != null && projectile.source == engine.playerShip) buff.showOnPlayerHud = true
    }

    /**
     * 第 5 发命中：恒爆炸（无论目标有无谐振层）——直击补伤 → AOE（层数 0 即 0，跳过）→
     * 消层 → 特效。十字辉星跟随「目标舰心 → 命中点」方位交叉。
     */
    private fun onFinalHit(
        projectile: DamagingProjectileAPI,
        ship: ShipAPI,
        hitPoint: Vector2f,
        values: StarfallEchoTuning.Values,
        engine: CombatEngineAPI,
    ) {
        val buff = ship.starfallEchoResonanceStacks()
        val stacks = buff?.stacks ?: 0

        val panel = projectile.damageAmount
        if (!panel.isFinite() || panel <= 0f) return

        val damage = StarfallEchoTuning.explosionDamage(panel, stacks, values.explosionDamageMult)
        val radius = StarfallEchoTuning.explosionRadius(stacks)
        val source = projectile.source
        val owner = source?.owner ?: 0

        // 直击补伤：每层被消耗的谐振使第 5 发伤害 +50%，提升部分由脚本补给直击目标
        // （盾覆盖 → 盾面落点 + bypass=false；未覆盖 → 舰心落点 + bypass=true，与 AOE 同判例口径）
        val bonus = StarfallEchoTuning.finalShotBonusDamage(panel, stacks)
        if (bonus > 0f && engine.isEntityInPlay(ship)) {
            val covered = shieldCovers(ship, hitPoint)
            engine.applyDamage(
                ship, resolveShipDamagePoint(ship, hitPoint), bonus,
                DamageType.ENERGY, 0f,
                !covered, false, source, true,
            )
        }

        // 范围能量结算（存活直击目标与同阵营豁免，摧锋同款裁定）；0 层伤害为 0 直接跳过
        if (damage > 0f) {
            for (victim in CombatUtils.getEntitiesWithinRange(hitPoint, radius)) {
                if (victim === projectile) continue
                if (victim.owner == owner) continue
                if (victim !is ShipAPI && victim !is MissileAPI) continue
                if (victim is ShipAPI && (victim.isHulk || victim.isPhased)) continue
                if (victim is MissileAPI && victim.isExpired) continue
                if (victim === ship && engine.isEntityInPlay(victim)) continue

                val covered = (victim as? ShipAPI)?.let { shieldCovers(it, hitPoint) } == true
                val dmgPoint = (victim as? ShipAPI)?.let { resolveShipDamagePoint(it, hitPoint) } ?: Vector2f(hitPoint)
                engine.applyDamage(
                    victim, dmgPoint, damage,
                    DamageType.ENERGY, 0f,
                    victim is ShipAPI && !covered, false, source, true,
                )
            }
        }

        // 后消层
        buff?.consume()

        // 特效恒执行（十字辉星跟随受击点方位 + 星云 + 三角碎片 + 径向电弧）
        StarfallEchoVfx.explosion(engine, hitPoint, stacks, radius, hitFacingDeg(ship, hitPoint))
    }

    /**
     * 受击点方位角（度）：目标舰心 → 命中点。不取 Misc.getAngleInDegrees：Misc 类初始化依赖
     * 游戏运行时（无头/单测直接 ExceptionInInitializerError），此处语义等价于 atan2 直出角度。
     * 允许负值（绽放辉星实现侧归一化到 [0,360)）。
     */
    private fun hitFacingDeg(ship: ShipAPI, hitPoint: Vector2f): Float {
        val origin = ship.location ?: return 0f
        return Math.toDegrees(
            kotlin.math.atan2(
                (hitPoint.y - origin.y).toDouble(),
                (hitPoint.x - origin.x).toDouble(),
            ),
        ).toFloat()
    }

    /**
     * 盾覆盖判定（七星/辉星/摧锋同名实现同型注记）：盾开启且爆心在盾弧内。
     * 覆盖时 bypassShields=false（尊重护盾）；未覆盖时必须 true（实机判例：盾关闭的
     * 带盾舰船 bypass=false 全额无伤害）。
     */
    private fun shieldCovers(ship: ShipAPI, explosionPoint: Vector2f): Boolean {
        val shield = ship.shield ?: return false
        return shield.isOn && shield.isWithinArc(explosionPoint)
    }

    /**
     * 舰船伤害落点（七星/辉星/摧锋同名实现同型注记）：盾覆盖 → 盾面落点；未覆盖 → 恒舰心
     * （实机判例：脚本 applyDamage 的界内边缘点恒 0 伤害，舰心点正常；落点仅影响
     * 装甲格选择与浮字位置，不影响伤害量）。
     */
    private fun resolveShipDamagePoint(ship: ShipAPI, explosionPoint: Vector2f): Vector2f {
        val shield = ship.shield
        if (shield != null && shield.isOn && shield.isWithinArc(explosionPoint)) {
            val shieldLoc = shield.location ?: return Vector2f(ship.location)
            val radius = shield.radius
            if (radius <= 0f) return Vector2f(ship.location)
            // 不取 Misc.getAngleInDegrees：Misc 类初始化依赖游戏运行时（无头/单测直接
            // ExceptionInInitializerError），此处语义等价于 atan2 直出角度，就地计算
            val angle = Math.toDegrees(
                kotlin.math.atan2(
                    (explosionPoint.y - shieldLoc.y).toDouble(),
                    (explosionPoint.x - shieldLoc.x).toDouble(),
                ),
            ).toFloat()
            return MathUtils.getPointOnCircumference(shieldLoc, radius, angle)
        }
        return Vector2f(ship.location)
    }
}
