package cn.kasuminova.astd.combat.effect.arc.signature.stellarjet

import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import cn.kasuminova.astd.combat.effect.generic.projectile.ProjectileVfxPresets

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.input.InputEventAPI

/**
 * 兜底修复：用于处理“能量弹看起来命中但不结算伤害”的边界情况。
 *
 * 机制：
 * - 跟踪我们生成的能量弹；
 * - 若弹体从战斗中移除/expired 时，发现 didDamage=false 但 damageTarget!=null，
 *   则手动 applyDamage 一次（只兜底，不会重复伤害）。
 */
internal object StellarJetBoltDamageFixer {

    private const val ENGINE_KEY = "astd_stellar_jet_bolt_damage_fixer"

    // 每场战斗最多打印几次 debug，避免刷屏
    private const val DEV_LOG_MAX = 10

    fun track(engine: CombatEngineAPI, projectile: DamagingProjectileAPI) {
        if (engine.isPaused) return
        getOrCreate(engine).track(projectile)
    }

    private fun getOrCreate(engine: CombatEngineAPI): Manager {
        val existing = engine.customData[ENGINE_KEY] as? Manager
        if (existing != null) return existing

        val mgr = Manager()
        mgr.init(engine)
        engine.addPlugin(mgr)
        engine.customData[ENGINE_KEY] = mgr
        return mgr
    }

    private class Manager : BaseEveryFrameCombatPlugin() {

        private var engine: CombatEngineAPI? = null

        private data class Entry(
            val projectile: DamagingProjectileAPI,
        )

        private val entries = ArrayList<Entry>(256)

        override fun init(engine: CombatEngineAPI) {
            this.engine = engine
        }

        fun track(projectile: DamagingProjectileAPI) {
            if (projectile.isExpired) return
            if (entries.any { it.projectile === projectile }) return
            entries.add(Entry(projectile))
        }

        override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
            val eng = engine ?: return
            if (eng.isPaused) return
            if (entries.isEmpty()) return

            for (i in entries.size - 1 downTo 0) {
                val p = entries[i].projectile

                val inPlay = eng.isEntityInPlay(p)
                val expired = p.isExpired

                if (inPlay && !expired) {
                    continue
                }

                // 弹体离开 play：尝试兜底
                val didDamage = try {
                    p.didDamage()
                } catch (_: Throwable) {
                    false
                }
                val target: CombatEntityAPI? = try {
                    p.damageTarget
                } catch (_: Throwable) {
                    null
                }

                if (!didDamage && target != null && eng.isEntityInPlay(target)) {
                    val dmg = try {
                        p.damageAmount
                    } catch (_: Throwable) {
                        0f
                    }
                    val emp = try {
                        p.empAmount
                    } catch (_: Throwable) {
                        0f
                    }
                    val type = try {
                        p.damageType
                    } catch (_: Throwable) {
                        null
                    }
                    val src = try {
                        p.source
                    } catch (_: Throwable) {
                        null
                    }

                    if (dmg > 0f && type != null) {
                        try {
                            eng.applyDamage(
                                target,
                                p.location,
                                dmg,
                                type,
                                emp,
                                false,
                                false,
                                src,
                                true,
                            )
                        } catch (_: Throwable) {
                        }

                        // devMode：提示一次，帮助确认兜底是否触发
                        if (isDevModeSafe()) {
                            tryLogOnce(eng, "[StellarJetBolt] fallbackDamage dmg=${"%.1f".format(dmg)} emp=${"%.1f".format(emp)}")
                        }
                    }
                }

                entries.removeAt(i)
            }
        }

        private fun isDevModeSafe(): Boolean {
            return try {
                Global.getSettings().isDevMode
            } catch (_: Throwable) {
                false
            }
        }

        private fun tryLogOnce(engine: CombatEngineAPI, msg: String) {
            val key = "astd_stellar_jet_bolt_damage_fixer_log_count"
            val prev = (engine.customData[key] as? Int) ?: 0
            if (prev >= DEV_LOG_MAX) return
            engine.customData[key] = prev + 1
            Global.getLogger(StellarJetBoltDamageFixer::class.java).info(msg)
        }
    }
}
