package cn.kasuminova.astd.combat.effect.arc.signature.stellarjet

import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx

import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.input.InputEventAPI
import org.lazywizard.lazylib.MathUtils

/**
 * 为“恒星喷射”系统额外生成的能量弹做射程限制：
 * - 由于我们会在脚本里随机设置速度（2000~4000），引擎侧用 weapon range/speed 推导的淡出距离可能会漂移。
 * - 这里按真实位移（spawnLocation -> current location）强制移除，确保射程与 beam 一致。
 */
internal object StellarJetBoltRangeLimiter {

    private const val ENGINE_KEY = "astd_stellar_jet_bolt_range_limiter"

    fun track(engine: CombatEngineAPI, projectile: DamagingProjectileAPI, maxRange: Float) {
        if (engine.isPaused) return
        if (maxRange <= 0f) return
        getOrCreate(engine).track(projectile, maxRange)
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
            val maxRange: Float,
        )

        private val entries = ArrayList<Entry>(256)

        override fun init(engine: CombatEngineAPI) {
            this.engine = engine
        }

        fun track(projectile: DamagingProjectileAPI, maxRange: Float) {
            val eng = engine ?: return
            if (projectile.isExpired) return
            if (entries.any { it.projectile === projectile }) return
            // 同一帧可能还未进入 play；先照样登记，advance 时会等它进入 play。
            entries.add(Entry(projectile, maxRange))
        }

        override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
            val eng = engine ?: return
            if (eng.isPaused) return
            if (entries.isEmpty()) return

            for (i in entries.size - 1 downTo 0) {
                val e = entries[i]
                val p = e.projectile

                if (p.isExpired) {
                    entries.removeAt(i)
                    continue
                }

                if (!eng.isEntityInPlay(p)) {
                    // 还没进 play：继续等
                    continue
                }

                val dist = try {
                    MathUtils.getDistance(p.location, p.spawnLocation)
                } catch (_: Throwable) {
                    0f
                }

                if (dist >= e.maxRange) {
                    try {
                        eng.removeEntity(p)
                    } catch (_: Throwable) {
                    }
                    entries.removeAt(i)
                    continue
                }
            }
        }
    }
}
