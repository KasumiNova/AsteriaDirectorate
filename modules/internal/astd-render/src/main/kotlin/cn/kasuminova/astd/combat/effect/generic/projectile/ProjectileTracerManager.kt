package cn.kasuminova.astd.combat.effect.generic.projectile

import cn.kasuminova.astd.internal.debug.VfxDebug

import cn.kasuminova.astd.api.AstdLog
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.input.InputEventAPI

/**
 * 通用“弹体挂视觉特效”管理器。
 *
 * 目标：把“追踪弹体生命周期/处理 pending 创建/淡出回收”的逻辑做成可复用，
 * 具体渲染样式由 [ProjectileVisualFactory] 提供（可以是 BoxUtil、原版 Sprite、或你自己的实现）。
 */
object ProjectileTracerManager {

    private const val ENGINE_KEY = "astd_projectile_tracer_manager"

    /** 生命周期/回收策略。 */
    data class Options(
        /** 弹体进入 isFading（通常是超射程淡出）时，视觉也开始淡出。 */
        val fadeOutOnProjectileFadingSeconds: Float = 0.22f,
        /** 弹体被移除/不在 play 时，视觉使用更短淡出并回收。 */
        val fadeOutOnProjectileRemovedSeconds: Float = 0.08f,
        /** 创建 visual 失败时的重试窗口（例如 BoxUtil 尚未 ready）。 */
        val pendingTimeoutSeconds: Float = 0.75f,
        /**
         * 弹体 rawInPlay=false + wasRemoved=true 时的容错窗口（秒）。
         * 弹道弹体命中后可设为很小值（如 0.05f）以立即触发淡出；导弹/对象池弹体建议保持默认 0.30f。
         */
        val removedGraceSeconds: Float = 0.30f,
    )

    enum class FadeReason {
        /** 弹体进入 isFading（通常是超射程淡出），但仍可能继续在 play 中存在一小段时间。 */
        PROJECTILE_FADING,

        /** 弹体不在 play / expired / wasRemoved：认为其已被引擎移除（命中/被清理/强制删除等）。 */
        PROJECTILE_REMOVED,
    }

    fun track(
        engine: CombatEngineAPI,
        projectile: DamagingProjectileAPI,
        options: Options = Options(),
        factory: ProjectileVisualFactory,
    ) {
        if (engine.isPaused) return
        val mgr = getOrCreate(engine)
        mgr.track(projectile, options, factory)
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
            val visual: ProjectileVisual,
            val options: Options,
            var detached: Boolean = false,
            /** engine.isEntityInPlay(proj) 可能出现瞬时 false（尤其是导弹/对象池回收边界）。给一个容错窗口避免误判为 REMOVED。 */
            var notInPlayAge: Float = 0f,
        )

        private data class Pending(
            val projectile: DamagingProjectileAPI,
            val options: Options,
            val factory: ProjectileVisualFactory,
            var age: Float = 0f,
        )

        private val entries = ArrayList<Entry>(128)
        private val pending = ArrayList<Pending>(128)

        override fun init(engine: CombatEngineAPI) {
            this.engine = engine
        }

        fun track(projectile: DamagingProjectileAPI, options: Options, factory: ProjectileVisualFactory) {
            val eng = engine ?: return
            // NOTE：避免默认刷屏；需要时用 -Dsmd.debugVfx=true 启用精细日志。
            try {
                VfxDebug.logEvery(eng, "tracer_track", 0.25f) {
                    "[ASTD] ProjectileTracerManager.track: projectile=$projectile specId=${projectile.projectileSpecId} " +
                        "isExpired=${projectile.isExpired} wasRemoved=${projectile.wasRemoved()} isInPlay=${eng.isEntityInPlay(projectile)}"
                }
            } catch (_: Throwable) {
            }
            if (projectile.isExpired) return
            // 注意：某些弹体实现会出现 wasRemoved=true 但 engine.isEntityInPlay=true 的情况（疑似对象池/标记未重置）。
            // 因此这里不使用 wasRemoved 做硬判定，改为依赖 isExpired + isEntityInPlay。

            if (entries.any { it.projectile === projectile }) return
            if (pending.any { it.projectile === projectile }) return

            // 弹体刚生成的同一帧，某些情况下 engine.isEntityInPlay(projectile) 可能仍为 false。
            // 这种情况下不要直接丢弃，而是进入 pending 等待一小段时间。
            if (!eng.isEntityInPlay(projectile)) {
                pending.add(Pending(projectile, options, factory))
                return
            }

            val visual = try {
                factory.create(eng, projectile)
            } catch (_: Throwable) {
                null
            }
            if (visual != null) {
                val decorated = ProjectileVfxEnhancer.decorate(eng, projectile, visual)
                // 如果弹体已经处于淡化阶段（例如我们是通过扫描弹体列表“补绑定”的），视觉也应该立刻进入淡出。
                if (projectile.isFading && options.fadeOutOnProjectileFadingSeconds > 0f) {
                    decorated.beginFadeOut(FadeReason.PROJECTILE_FADING, options.fadeOutOnProjectileFadingSeconds)
                }
                entries.add(Entry(projectile, decorated, options))
            } else {
                pending.add(Pending(projectile, options, factory))
            }
        }

        override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
            val eng = engine ?: return
            if (eng.isPaused) return

            if (pending.isNotEmpty()) {
                for (i in pending.size - 1 downTo 0) {
                    val p = pending[i]
                    val proj = p.projectile

                    // wasRemoved 在部分实现里不可靠；pending 阶段只用 isExpired 做硬终止。
                    if (proj.isExpired) {
                        // 生命周期结束：清理 onFire/scan/AI 标签，避免同一场战斗内对象复用导致“新弹体被旧标记跳过”。
                        ProjectileVfxDispatchState.clearAll(proj)
                        pending.removeAt(i)
                        continue
                    }

                    p.age += amount

                    // 等待弹体真正进入 play 后再尝试创建 visual。
                    if (!eng.isEntityInPlay(proj)) {
                        if (p.age > p.options.pendingTimeoutSeconds) {
                            // pending 超时：说明一直没进入 play，或依赖始终未就绪。
                            // 对透明弹体来说这会导致“永久不可见”，因此必须清除 MARK 允许扫描式分发后续重试。
                            ProjectileVfxDispatchState.clearAll(proj)
                            pending.removeAt(i)
                        }
                        continue
                    }

                    val visual = try {
                        p.factory.create(eng, proj)
                    } catch (_: Throwable) {
                        null
                    }
                    if (visual != null) {
                        val decorated = ProjectileVfxEnhancer.decorate(eng, proj, visual)
                        if (proj.isFading && p.options.fadeOutOnProjectileFadingSeconds > 0f) {
                            decorated.beginFadeOut(FadeReason.PROJECTILE_FADING, p.options.fadeOutOnProjectileFadingSeconds)
                        }
                        entries.add(Entry(proj, decorated, p.options))
                        pending.removeAt(i)
                        continue
                    }

                    if (p.age > p.options.pendingTimeoutSeconds) {
                        // visual 创建窗口耗尽：允许扫描式分发再次尝试（例如 BoxUtil 在战斗较晚时才 ready）。
                        ProjectileVfxDispatchState.clearAll(proj)
                        pending.removeAt(i)
                    }
                }
            }

            if (entries.isEmpty()) return

            // 说明：engine.isEntityInPlay(proj) 在少数情况下会出现不稳定（导弹冲刺/对象池边界/渲染队列抖动等）。
            // 这里的策略：
            // - “确实移除”以 isExpired 为硬条件；
            // - rawInPlay=false 时，不要立刻信任 wasRemoved=true（部分实现会出现“对象池标记未清/瞬时抖动”的假阳性）。
            //   仅当 rawInPlay=false 持续一小段时间后，才把 wasRemoved 作为“辅助确认”。
            // - 否则继续驱动 visual（避免在导弹仍在飞行时 VFX 突然消失）。
            // 同时保留一个很宽松的 notInPlayAge 上限，防止极端情况下永久泄漏。
            val notInPlaySoftTimeoutSeconds = 8.0f

            for (i in entries.size - 1 downTo 0) {
                val e = entries[i]
                val proj = e.projectile
                val opts = e.options

                val rawInPlay = try {
                    eng.isEntityInPlay(proj)
                } catch (_: Throwable) {
                    true
                }

                val expired = try {
                    proj.isExpired
                } catch (_: Throwable) {
                    false
                }

                val wasRemoved = try {
                    proj.wasRemoved()
                } catch (_: Throwable) {
                    false
                }

                if (rawInPlay) {
                    e.notInPlayAge = 0f
                } else if (!expired) {
                    e.notInPlayAge += amount
                }

                // 确认移除：expired 永远优先。
                // rawInPlay=false 时，wasRemoved 仅作为“延迟确认”（避免一帧抖动导致 VFX 立刻消失）。
                // soft timeout：rawInPlay=false 持续很久且仍未 expired 时，认为引擎已回收该对象。
                val removed = expired ||
                    (!rawInPlay && wasRemoved && e.notInPlayAge > opts.removedGraceSeconds) ||
                    (!rawInPlay && e.notInPlayAge > notInPlaySoftTimeoutSeconds)

                val advanceNormally = !removed

                if (advanceNormally) {
                    // 正常飞行（即使 rawInPlay=false 但对象仍可访问）：继续跟随。
                    try {
                        e.visual.advance(proj, amount)
                    } catch (_: Throwable) {
                    }

                    if (proj.isFading && opts.fadeOutOnProjectileFadingSeconds > 0f) {
                        e.visual.beginFadeOut(FadeReason.PROJECTILE_FADING, opts.fadeOutOnProjectileFadingSeconds)
                    }
                } else {
                    // 不在 play / 已移除：定格到最后一帧位置，并短淡出。
                    if (!e.detached) {
                        try {
                            e.visual.advance(proj, 0f)
                        } catch (_: Throwable) {
                            // 弹体被引擎移除后，某些字段访问可能异常；定格失败也不影响淡出回收。
                        }
                        e.detached = true
                    } else {
                        // 重要：淡出计时等通常发生在 visual.advance() 内部。
                        // 弹体已不在 play 时继续推进 visual 的内部计时（并用 try/catch 防止访问弹体字段异常）。
                        try {
                            e.visual.advance(proj, amount)
                        } catch (_: Throwable) {
                            // ignore
                        }
                    }

                    if (opts.fadeOutOnProjectileRemovedSeconds > 0f) {
                        e.visual.beginFadeOut(FadeReason.PROJECTILE_REMOVED, opts.fadeOutOnProjectileRemovedSeconds)
                    }
                }

                if (e.visual.isFadeOutOver()) {
                    e.visual.delete()
                    // 生命周期结束：清理 onFire/scan/AI 标签，避免同一场战斗内对象复用导致“新弹体被旧标记跳过”。
                    ProjectileVfxDispatchState.clearAll(proj)
                    entries.removeAt(i)
                    continue
                }
            }
        }
    }
}

/** 通用视觉对象接口：由管理器驱动生命周期。 */
interface ProjectileVisual {
    fun advance(projectile: DamagingProjectileAPI, amount: Float)

    /**
     * 启动淡出。要求实现为“幂等”：同一个 visual 多次调用不应重置为更亮/更长。
     */
    fun beginFadeOut(reason: ProjectileTracerManager.FadeReason, fadeOutSeconds: Float)

    /** 淡出是否结束（结束后管理器会 delete+移除）。 */
    fun isFadeOutOver(): Boolean

    /** 立即回收。 */
    fun delete()
}

/** 创建视觉对象的工厂；返回 null 表示暂时无法创建（例如依赖未就绪），管理器会进入 pending 并重试。 */
fun interface ProjectileVisualFactory {
    fun create(engine: CombatEngineAPI, projectile: DamagingProjectileAPI): ProjectileVisual?
}
