package cn.kasuminova.astd.combat.effect.generic.projectile

import cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxRuntimeManager
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxDriverPlugin
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.input.InputEventAPI

/**
 * “按弹体(.proj id) 分发”的 on-spawn VFX。
 *
 * 目的：把视觉特效的绑定点从 .wpn（weaponSpec）迁移为“以 projectileSpecId 为中心”的分发，
 * 这样同一个弹体不管被哪把武器发射，都能复用同一套模板。
 */
internal class ProjectileSpawnVfxDispatcher : BaseEveryFrameCombatPlugin() {

    companion object {
        /** 用于 engine.customData 的 key：避免重复 addPlugin。 */
        const val ENGINE_KEY: String = "astd_projectile_spawn_vfx_dispatcher"

        private const val FAIL_COUNT_KEY = "astd_vfx_scan_dispatch_fail_count"

        private val log = Global.getLogger(ProjectileSpawnVfxDispatcher::class.java)
    }

    private var engine: CombatEngineAPI? = null

    private var elapsed = 0f
    private var maxProjectilesSeen = 0
    private var maxMissilesSeen = 0
    private var dispatchedCount = 0
    private val dispatchedSpecIds: MutableSet<String> = HashSet()
    private var loggedSummary = false

    // --- 诊断统计（用于解释 dispatched=0 的原因） ---
    private var skippedBecauseScanMarked = 0
    private var skippedBecauseScanLocked = 0
    private var skippedBecauseOnFireMarked = 0
    private var skippedBecauseOnFireLocked = 0
    private var markedAsNoHandlers = 0
    private var markedAsFailBackoff = 0
    private var sawBlankSpecId = 0
    private val seenSpecIds: MutableSet<String> = LinkedHashSet()
    private val noHandlerSpecIdsLogged: MutableSet<String> = HashSet()

    private var loggedFirstNonEmpty = false
    private var loggedFirstDispatch = false

    override fun init(engine: CombatEngineAPI) {
        this.engine = engine

        // 不依赖“扫描到弹体”即可确认插件已被安装。
        if (engine.customData[ProjectileVfxKeys.ENGINE_LOG_SCAN_ONCE] != true) {
            engine.customData[ProjectileVfxKeys.ENGINE_LOG_SCAN_ONCE] = true
            val src = try {
                ProjectileSpawnVfxDispatcher::class.java.protectionDomain?.codeSource?.location?.toString()
            } catch (_: Throwable) {
                null
            }
            log.info("[ASTD] ProjectileSpawnVfxDispatcher installed (src=$src)")
        }
    }

    override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
        val eng = engine ?: return
        if (eng.isPaused) return

        elapsed += amount

        // devMode 下：在战斗中给一次性浮字确认该插件确实在 advance。
        if (isDevModeSafe() && eng.customData[ProjectileVfxKeys.ENGINE_DEV_SCAN_ONCE] != true) {
            try {
                val anchor = eng.playerShip
                if (anchor != null) {
                    eng.addFloatingText(anchor.location, "[ASTD] VFX scanner running", 16f, java.awt.Color(180, 255, 180, 255), anchor, 0f, 0f)
                    eng.customData[ProjectileVfxKeys.ENGINE_DEV_SCAN_ONCE] = true
                }
            } catch (_: Throwable) {
            }
        }

        ProjectileVfxRegistry.ensureLoaded()

        // 注意：Starsector 会把“普通弹丸(projectiles)”与“导弹(missiles)”分开维护。
        // 由于本模组大量弹体贴图是透明的，因此必须同时扫描两类列表。
        val projectiles = eng.projectiles
        if (projectiles != null) {
            maxProjectilesSeen = maxOf(maxProjectilesSeen, projectiles.size)
        }
        if (!projectiles.isNullOrEmpty()) {
            for (p in projectiles) {
                try {
                    val id = p.projectileSpecId
                    if (id.isNullOrBlank()) {
                        sawBlankSpecId++
                    } else {
                        // 记录“见过的 specId”，即使最后没有成功 dispatch（否则 dispatched=0 时看不到任何线索）。
                        if (seenSpecIds.size < 16) seenSpecIds.add(id)
                    }
                } catch (_: Throwable) {
                }

                if (dispatchOneIfNeeded(eng, p)) {
                    dispatchedCount++
                    p.projectileSpecId?.let { if (it.isNotBlank()) dispatchedSpecIds.add(it) }

                    if (!loggedFirstDispatch) {
                        loggedFirstDispatch = true
                        val projId = p.projectileSpecId
                        log.info("[ASTD] VFX scanner first dispatch at t=${"%.2f".format(elapsed)}s: projectileSpecId=$projId, class=${p.javaClass.name}")
                    }
                }
            }
        }

        val missiles: List<MissileAPI>? = try {
            eng.missiles
        } catch (_: Throwable) {
            null
        }
        if (missiles != null) {
            maxMissilesSeen = maxOf(maxMissilesSeen, missiles.size)
        }

        // 不要只看“开场前2秒”：很多时候玩家/AI 是 2 秒后才真正开火。
        // 这里在首次看到列表非空时记一条日志，便于判断“弹体是否真正生成”。
        if (!loggedFirstNonEmpty) {
            val pCount = projectiles?.size ?: 0
            val mCount = missiles?.size ?: 0
            if (pCount > 0 || mCount > 0) {
                loggedFirstNonEmpty = true
                log.info("[ASTD] VFX scanner first seen non-empty lists at t=${"%.2f".format(elapsed)}s: projectiles=$pCount missiles=$mCount")
            }
        }
        if (!missiles.isNullOrEmpty()) {
            for (m in missiles) {
                try {
                    val id = m.projectileSpecId
                    if (id.isNullOrBlank()) {
                        sawBlankSpecId++
                    } else {
                        if (seenSpecIds.size < 16) seenSpecIds.add(id)
                    }
                } catch (_: Throwable) {
                }

                if (dispatchOneIfNeeded(eng, m)) {
                    dispatchedCount++
                    m.projectileSpecId?.let { if (it.isNotBlank()) dispatchedSpecIds.add(it) }

                    if (!loggedFirstDispatch) {
                        loggedFirstDispatch = true
                        val projId = m.projectileSpecId
                        log.info("[ASTD] VFX scanner first dispatch at t=${"%.2f".format(elapsed)}s: projectileSpecId=$projId, class=${m.javaClass.name}")
                    }
                }
            }
        }

        // 2 秒后给一条“战斗内总结”日志：
        // - 如果完全没看到任何 projectile/missile：优先怀疑武器不是发射类、或数据没真正生成弹体
        // - 如果看到了但没分发：优先怀疑 projectileSpecId 空/异常、或 handler 全部抛异常
        if (!loggedSummary && elapsed >= 2f) {
            loggedSummary = true
            if (maxProjectilesSeen == 0 && maxMissilesSeen == 0) {
                log.warn("[ASTD] VFX scanner summary: saw 0 projectiles & 0 missiles in first ${"%.2f".format(elapsed)}s (weapon may be beam / projectile not spawning)")
            } else if (dispatchedCount == 0) {
                val msg =
                    "[ASTD] VFX scanner summary: saw projectiles=$maxProjectilesSeen missiles=$maxMissilesSeen, but dispatched=0 " +
                        "(scanMarked=$skippedBecauseScanMarked scanLocked=$skippedBecauseScanLocked onFireMarked=$skippedBecauseOnFireMarked onFireLocked=$skippedBecauseOnFireLocked blankSpecId=$sawBlankSpecId " +
                        "markedNoHandlers=$markedAsNoHandlers markedFailBackoff=$markedAsFailBackoff seenSpecIds=$seenSpecIds)"

                // 如果大量 onFireMarked，通常说明弹体已在 onFire 路径被处理；扫描器作为兜底选择跳过。
                // 这种情况下用 info，避免误导成“完全没分发”。
                if (skippedBecauseOnFireMarked > 0) {
                    log.info(msg + " (note: onFireMarked>0 usually means onFire already handled/marked)")
                } else {
                    log.warn(msg)
                }
            } else {
                log.info("[ASTD] VFX scanner summary: saw projectiles=$maxProjectilesSeen missiles=$maxMissilesSeen, dispatched=$dispatchedCount, uniqueSpecIds=${dispatchedSpecIds.size}")
            }
        }
    }

    private fun dispatchOneIfNeeded(engine: CombatEngineAPI, projectile: DamagingProjectileAPI): Boolean {
        // scan 自己的“只处理一次”标记（不应影响 onFire 链路）。
        if (ProjectileVfxDispatchState.isMarked(engine, projectile, ProjectileVfxKeys.PROJECTILE_VFX_SCAN_MARK)) {
            skippedBecauseScanMarked++
            return false
        }

        // onFire 已处理过：scan 作为兜底不应抢戏。
        if (ProjectileVfxDispatchState.isMarked(engine, projectile, ProjectileVfxKeys.PROJECTILE_VFX_ONFIRE_MARK)) {
            skippedBecauseOnFireMarked++
            return false
        }

        // scan 自己的短期锁：避免扫描插件被重复安装/同帧重入。
        if (ProjectileVfxDispatchState.isLocked(engine, projectile, ProjectileVfxKeys.PROJECTILE_VFX_SCAN_LOCK)) {
            skippedBecauseScanLocked++
            return false
        }

        // onFire 正在处理：scan 让路。
        if (ProjectileVfxDispatchState.isLocked(engine, projectile, ProjectileVfxKeys.PROJECTILE_VFX_ONFIRE_LOCK)) {
            skippedBecauseOnFireLocked++
            return false
        }

        ProjectileVfxDispatchState.lock(engine, projectile, ProjectileVfxKeys.PROJECTILE_VFX_SCAN_LOCK)
        val (ok, fail) = try {
            dispatchOne(engine, projectile)
        } finally {
            ProjectileVfxDispatchState.unlock(projectile, ProjectileVfxKeys.PROJECTILE_VFX_SCAN_LOCK)
        }
        if (ok > 0) {
            ProjectileVfxDispatchState.mark(engine, projectile, ProjectileVfxKeys.PROJECTILE_VFX_SCAN_MARK)
            return true
        }

        // 没有任何 handler（通常表示未配置/不需要 VFX）：直接 MARK，避免后续每帧重试。
        if (ok == 0 && fail == 0) {
            ProjectileVfxDispatchState.mark(engine, projectile, ProjectileVfxKeys.PROJECTILE_VFX_SCAN_MARK)
            markedAsNoHandlers++
            return false
        }

        // 若连续失败（例如缺失依赖导致一直抛异常），避免每帧重试刷屏。
        // 这里用 projectile.customData 做轻量 backoff。
        val prev = ProjectileVfxDispatchState.getFailCount(engine, projectile, FAIL_COUNT_KEY)
        val now = prev + maxOf(1, fail)
        ProjectileVfxDispatchState.setFailCount(engine, projectile, FAIL_COUNT_KEY, now)
        if (now >= 5) {
            ProjectileVfxDispatchState.mark(engine, projectile, ProjectileVfxKeys.PROJECTILE_VFX_SCAN_MARK)
            markedAsFailBackoff++
        }
        return false
    }

    private fun isDevModeSafe(): Boolean {
        return try {
            Global.getSettings().isDevMode
        } catch (_: Throwable) {
            false
        }
    }

    private fun dispatchOne(engine: CombatEngineAPI, projectile: DamagingProjectileAPI): Pair<Int, Int> {
        var ok = 0
        var fail = 0

        val projId = projectile.projectileSpecId
        if (projId.isNullOrBlank()) {
            return ok to fail
        }

        // scan 路径也尝试注入导弹 AI（带去重标记），避免 onFire 未触发/被短路时 AI 永久不生效。
        try {
            ProjectileMissileAiInjector.ensureInstalled(engine, projectile)
        } catch (_: Throwable) {
        }

        val preset = ProjectileVfxRegistry.presetFor(projId)
        if (preset == null) {
            // 只在本场战斗里对同一个 specId 打一次“无 handler”日志：
            // 用于确认 dispatched=0 到底是“没配置”还是“逻辑没跑到”。
            if (noHandlerSpecIdsLogged.add(projId)) {
                log.info("[ASTD] VFX scanner: no runtime preset for projectileSpecId=$projId")
            }
            return ok to fail
        }

        // 切片：与 onFire 派发一致，已迁移 spec 走新 RenderEntity 管线（手写 DSL），其余仍走旧 Runtime。迁移完成后移除此分支。
        val tracked = if (ProjectileVfxDriverPlugin.isMigrated(projId)) {
            ProjectileVfxDriverPlugin.track(engine, projectile, projId)
        } else {
            ASTDProjectileVfxRuntimeManager.track(engine, projectile, preset)
        }
        if (tracked) {
            ok++
        }
        return ok to fail
    }
}
