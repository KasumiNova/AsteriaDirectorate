package cn.kasuminova.astd.combat.effect.arc

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.OnFireEffectPlugin
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import org.lwjgl.util.vector.Vector2f

/**
 * “七星”折跃发射器 `.proj` 侧发射回调（规格 07 §2.1，2026-08 裂隙改版）：
 * 发射瞬间——弹体速度清零、快照难度取值、执行首发折跃并在落点挂裂隙征兆 +
 * 延迟爆炸（[SevenStarsDifficulty.EXPLOSION_DELAY] 后结算），随后弹体即移除，
 * 连跳锚点移交纯位置（[SevenStarsChainScript] 锚点设计，规格 §1 flightTime 6.0
 * 意图的等效落地；首发无 PD 目标时以 TERMINAL_PENDING 注册，首帧进终结判定）。
 *
 * 动机：难度取值调用点唯一（[SevenStarsDifficulty.snapshot] 发射时刻一次）；
 * FIRST_STRIKE 的折跃与裂隙挂点在 onFire 内同步完成，爆炸伤害与后续状态机推进
 * 全部交给 [SevenStarsChainScript]（规格 §2.2 状态机）。固定 7 跳定案：首发不再
 * 以击杀数决定断链，脚本恒注册（有 PD 候选 CHAIN_COOLDOWN / 无候选 TERMINAL_PENDING）。
 *
 * 0 值防线（规格 §2.4）：面板伤害 <= 0 时记 WARN，跳过全部结算直接消散
 * （摧毁判定/段数表全部以 0 伤害走通但不产生任何 applyDamage 调用）。
 */
class SevenStarsOnFireEffect : OnFireEffectPlugin {

    override fun onFire(projectile: DamagingProjectileAPI, weapon: WeaponAPI, engine: CombatEngineAPI) {
        if (engine.isPaused) return
        val source = weapon.ship
        val owner = source?.owner ?: projectile.owner

        val panelDamage = projectile.damageAmount
        if (panelDamage.isNaN() || panelDamage <= 0f) {
            log.warn("“七星”弹体面板伤害非法（$panelDamage），跳过全部结算直接消散（不产生任何 applyDamage 调用）")
            SevenStarsVfx.dissipate(engine, projectile.location)
            engine.removeEntity(projectile)
            return
        }

        val tuning = SevenStarsDifficulty.snapshot(source)
        val jumpRange = SevenStarsChainMath.jumpRange(weapon.range)
        // 弹体由脚本瞬移接管：发射即停速（规格 §2.1），collisionClass=NONE 无触碰路径。
        projectile.velocity.set(0f, 0f)

        SevenStarsChainScript.bumpTelemetry(engine, SevenStarsChainScript.TELEMETRY_ONFIRE)

        val firstMult = SevenStarsChainMath.flashMult(tuning, 1)
        val target = SevenStarsTargetSelector.select(
            engine = engine,
            from = projectile.location,
            jumpRange = jumpRange,
            owner = owner,
            aoeDamage = panelDamage * firstMult,
        )

        if (target == null) {
            // 首发无 PD 候选：注册脚本首帧进对舰终结判定（规格 §2.2 FIRST_STRIKE --无PD目标--> TERMINAL）。
            engine.addPlugin(
                SevenStarsChainScript(
                    anchor = Vector2f(projectile.location),
                    source = source,
                    tuning = tuning,
                    panelDamage = panelDamage,
                    jumpRange = jumpRange,
                    owner = owner,
                    initialJumps = 0,
                    initialState = SevenStarsChainScript.State.TERMINAL_PENDING,
                ),
            )
            engine.removeEntity(projectile)
            return
        }

        // 首发折跃 + 落点裂隙征兆（爆炸延迟结算，由脚本 pending 队列推进）。
        SevenStarsVfx.teleport(engine, projectile.location, target.location, source, target as? ShipAPI)
        SevenStarsChainScript.bumpTelemetry(engine, SevenStarsChainScript.TELEMETRY_TELEPORT_ARC)
        projectile.location.set(target.location)
        projectile.velocity.set(0f, 0f)
        SevenStarsVfx.riftWindup(engine, projectile.location, scale = 1f)

        val script = SevenStarsChainScript(
            anchor = Vector2f(projectile.location),
            source = source,
            tuning = tuning,
            panelDamage = panelDamage,
            jumpRange = jumpRange,
            owner = owner,
            initialJumps = 1,
            initialState = SevenStarsChainScript.State.CHAIN_COOLDOWN,
        )
        script.queueFlash(projectile.location, target, firstMult, jumpIndex = 1)
        engine.addPlugin(script)
        SevenStarsChainScript.trackChainJumps(engine, 1)
        engine.removeEntity(projectile)
    }

    private companion object {
        private val log = Global.getLogger(SevenStarsOnFireEffect::class.java)
    }
}
