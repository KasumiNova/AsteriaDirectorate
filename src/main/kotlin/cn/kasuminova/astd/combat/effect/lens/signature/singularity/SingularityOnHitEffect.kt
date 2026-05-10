package cn.kasuminova.astd.combat.effect.lens.signature.singularity

import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.OnHitEffectPlugin
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI
import com.fs.starfarer.api.input.InputEventAPI
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f

/**
 * 奇点投射器命中效果（新星/事件视界共用）：
 * - 目标幅能比例 >= 85% 时穿盾直击舰体（对盾不结算伤害）
 * - 命中后 1s 内持续装甲网格伤害：总计额外 800% 能量伤害
 * - 按质量与船体完整性追加伤害（<=100% 能量）
 * - 造成伤害的 1% 转化为“本战斗永久最大船体下降”
 */
class SingularityOnHitEffect : OnHitEffectPlugin {

    companion object {
        private const val ARMOR_REND_DURATION = 1.0f
        private const val ARMOR_REND_TICK = 0.10f
        private const val ARMOR_REND_TOTAL_MULT = 8.0f

        private const val MAX_EXTRA_DAMAGE_MULT = 1.0f

        private const val PERMA_MAX_HULL_LOSS_FRACTION = 0.01f

        // 质量归一化：达到该质量时质量项取满
        private const val MASS_FULL_AT = 20000f

        // 装甲持续伤害的随机抖动半径（su）
        private const val ARMOR_REND_JITTER_RADIUS_MIN = 12f
        private const val ARMOR_REND_JITTER_RADIUS_MAX = 45f
    }

    override fun onHit(
        projectile: DamagingProjectileAPI,
        target: CombatEntityAPI,
        point: Vector2f,
        shieldHit: Boolean,
        damageResult: ApplyDamageResultAPI,
        engine: CombatEngineAPI,
    ) {
        val ship = target as? ShipAPI ?: return
        if (engine.isPaused) return

        // 标记：已经命中过（用于击落自爆管理器去重）
        try {
            projectile.setCustomData(SingularityKeys.MISSILE_HAS_HIT, true)
        } catch (_: Throwable) {
        }

        val source = (projectile as? MissileAPI)?.source

        // 取“原始面板伤害”（可能在飞行中被 AI 置 0 以实现穿盾不打盾）
        val baseDamage = (projectile.customData[SingularityKeys.MISSILE_ORIGINAL_DAMAGE] as? Float)
            ?: sanitizeNonNegativeFinite(projectile.damageAmount)

        if (baseDamage <= 0f) return

        val canBypass = ship.fluxLevel >= SingularityRetargetMissileAI.SHIELD_BYPASS_FLUX_LEVEL

        // 只有在“真正打到船体”（盾没吃到）或“满足穿盾条件”时，才触发后续的结构破坏。
        val structuralHit = (!shieldHit) || canBypass
        if (!structuralHit) return

        // 命中反馈：播放一次“命中爆炸”VFX（不造成额外 AoE 伤害；伤害机制已在本类完成）。
        try {
            val projId = projectile.projectileSpecId ?: ""
            val variant = when {
                projId.contains("tsm2", ignoreCase = true) -> SingularityDetonationFx.Variant.EVENT_HORIZON
                // 其它奇点系列（含 sgl8 swarm）默认按新星表现
                else -> SingularityDetonationFx.Variant.NOVA
            }
            val baseVel = projectile.velocity?.let { Vector2f(it) }
            SingularityDetonationFx.spawn(engine, Vector2f(point), baseVel, variant, SingularityDetonationFx.DetonationMode.HIT)
        } catch (_: Throwable) {
        }

        // 若本次是“盾命中但满足穿盾条件”，则把直击伤害补到船体（bypassShields=true）。
        // 说明：盾侧伤害已在飞行中被 AI 置 0，因此这里补齐“真实命中”。
        if (shieldHit && canBypass) {
            try {
                engine.applyDamage(
                    ship,
                    point,
                    baseDamage,
                    DamageType.ENERGY,
                    0f,
                    true,
                    false,
                    source,
                )
            } catch (_: Throwable) {
            }
        }

        // 质量 + 船体完整性：追加伤害（<= 100%）
        val extraMult = computeExtraDamageMult(ship).coerceIn(0f, MAX_EXTRA_DAMAGE_MULT)
        val extraDamage = (baseDamage * extraMult).coerceAtLeast(0f)
        if (extraDamage > 0f) {
            try {
                engine.applyDamage(
                    ship,
                    point,
                    extraDamage,
                    DamageType.ENERGY,
                    0f,
                    true,
                    false,
                    source,
                )
            } catch (_: Throwable) {
            }
        }

        // 装甲持续伤害：总计额外 800%（1 秒内分 10 次打完）
        val armorTotal = baseDamage * ARMOR_REND_TOTAL_MULT
        val ticks = (ARMOR_REND_DURATION / ARMOR_REND_TICK).toInt().coerceAtLeast(1)
        val perTick = (armorTotal / ticks.toFloat()).coerceAtLeast(0f)

        if (perTick > 0f) {
            val jitterRadius = (ship.collisionRadius * 0.18f)
                .coerceIn(ARMOR_REND_JITTER_RADIUS_MIN, ARMOR_REND_JITTER_RADIUS_MAX)

            engine.addPlugin(object : BaseEveryFrameCombatPlugin() {
                private var left = ARMOR_REND_DURATION
                private var tick = 0f

                override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
                    if (engine.isPaused) return
                    left -= amount
                    if (left <= 0f || ship.isHulk || !engine.isEntityInPlay(ship)) {
                        engine.removePlugin(this)
                        return
                    }

                    tick += amount
                    if (tick < ARMOR_REND_TICK) return
                    tick -= ARMOR_REND_TICK

                    val p = MathUtils.getRandomPointInCircle(point, jitterRadius)
                    try {
                        engine.applyDamage(
                            ship,
                            p,
                            perTick,
                            DamageType.ENERGY,
                            0f,
                            true,
                            false,
                            source,
                        )
                    } catch (_: Throwable) {
                    }
                }
            })
        }

        // 1% 伤害转为“本战斗永久最大船体下降”
        // 这里以“本次命中确定会造成的结构伤害总量”估算：base + extra + armorTotal。
        // 注：armorTotal 会在 1s 内分批结算，但“最大船体下降”是“不可逆结构损伤”，可以在命中时一次性确定。
        val structuralDamageTotal = baseDamage + extraDamage + armorTotal
        val maxHullLoss = (structuralDamageTotal * PERMA_MAX_HULL_LOSS_FRACTION).coerceAtLeast(0f)
        if (maxHullLoss > 0f) {
            applyPermanentMaxHullLoss(ship, maxHullLoss)

            // 直接扣 max hull 后，若当前船体超过新上限，则同步下压（避免出现“当前 HP > max HP”）。
            try {
                val baseMax = (ship.customData[SingularityKeys.TARGET_BASE_MAX_HULL] as? Float) ?: ship.maxHitpoints
                val lossNow = (ship.customData[SingularityKeys.TARGET_MAX_HULL_LOSS] as? Float) ?: 0f
                val newMax = (baseMax - lossNow).coerceAtLeast(1f)
                if (ship.hitpoints > newMax) {
                    ship.hitpoints = newMax
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun computeExtraDamageMult(ship: ShipAPI): Float {
        val mass = try {
            ship.mass
        } catch (_: Throwable) {
            0f
        }.coerceAtLeast(0f)

        val hp = try {
            ship.hitpoints
        } catch (_: Throwable) {
            0f
        }.coerceAtLeast(0f)

        val maxHp = try {
            ship.maxHitpoints
        } catch (_: Throwable) {
            1f
        }.coerceAtLeast(1f)

        // 质量越大越容易“被撕开”；完整性越高越容易“塌陷传播”。
        val massFactor = (mass / MASS_FULL_AT).coerceIn(0f, 1f)
        val integrity = (hp / maxHp).coerceIn(0f, 1f)

        return (massFactor * integrity) * MAX_EXTRA_DAMAGE_MULT
    }

    private fun applyPermanentMaxHullLoss(ship: ShipAPI, addLoss: Float) {
        val loss = addLoss.coerceAtLeast(0f)
        if (loss <= 0f) return

        val baseMax = (ship.customData[SingularityKeys.TARGET_BASE_MAX_HULL] as? Float)
            ?: run {
                val v = try {
                    ship.maxHitpoints
                } catch (_: Throwable) {
                    1f
                }.coerceAtLeast(1f)
                ship.setCustomData(SingularityKeys.TARGET_BASE_MAX_HULL, v)
                v
            }

        val prevLoss = (ship.customData[SingularityKeys.TARGET_MAX_HULL_LOSS] as? Float) ?: 0f
        val newLoss = (prevLoss + loss).coerceIn(0f, baseMax - 1f)
        ship.setCustomData(SingularityKeys.TARGET_MAX_HULL_LOSS, newLoss)

        val newMax = (baseMax - newLoss).coerceAtLeast(1f)
        val mult = (newMax / baseMax).coerceIn(0.01f, 1f)

        try {
            ship.mutableStats.hullBonus.modifyMult(SingularityKeys.TARGET_MAX_HULL_LOSS, mult)
        } catch (_: Throwable) {
        }
    }

    private fun sanitizeNonNegativeFinite(v: Float): Float {
        if (v.isNaN() || v.isInfinite()) return 0f
        if (v < 0f) return 0f
        return v
    }
}
