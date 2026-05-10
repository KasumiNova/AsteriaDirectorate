package cn.kasuminova.astd.combat.effect.arc.omega

import cn.kasuminova.astd.combat.effect.generic.CombatVfxBootstrap
import cn.kasuminova.astd.renderer.effect.projectile.beam.OglEllipseRingRenderer
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.combat.CollisionClass
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import org.boxutil.manager.CombatRenderingManager
import org.boxutil.units.standard.entity.DistortionEntity
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.VectorUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * DRV-Ω 相对论聚能炮（Omega）：
 * - 终结技慢充能：弹匣最后一发（ammo==1）在充能阶段将 chargeup 变为 2 倍时长。
 *
 * 注意：Starsector API 没有“只修改单个武器 chargeup”的官方 MutableShipStats 字段，
 * 这里使用 `ballisticRoFMult` 在极短窗口内修改（仅当本武器 chargeLevel 在 (0,1) 时生效），
 * 副作用窗口很小，符合技术文档的权衡。
 */
class DrvOmegaEveryFrameEffect : EveryFrameWeaponEffectPlugin {

    companion object {
        private const val MOD_ID = "astd_drv_omega_charge_slower"

        private const val AMMO_PER_SEC = 0.6f
        private const val RELOAD_SIZE = 3f

        // 聚能动画：节流，避免每帧刷实体
        private const val CHARGE_FX_INTERVAL = 0.045f

        // 关键修复：若在“开火帧”仍持有 RoF 修正，武器的 cooldown/重载会按慢速倍率被放大。
        // 因此在 chargeLevel 接近满值时提前释放修正，让最终开火使用正常 cooldown。
        private const val FINISHER_RELEASE_CHARGE_LEVEL = 0.99f

        private val CYAN = DrvOmegaImpactVfx.Theme.CYAN
        private val RED = DrvOmegaImpactVfx.Theme.REDSHIFT

        private data class State(
            var t: Float = 0f,
        )

        private val states: WeakHashMap<WeaponAPI, State> = WeakHashMap()
    }

    override fun advance(amount: Float, engine: CombatEngineAPI, weapon: WeaponAPI) {
        if (engine.isPaused) return

        // 维持扫描式 VFX dispatcher 的兜底安装（与旧 bootstrap effect 行为一致）
        CombatVfxBootstrap.ensureInstalled(engine)

        val ship = weapon.ship ?: return
        val stats = ship.mutableStats

        // ===== 弹匣规则：仅在弹匣打空后才开始“充能/装填” =====
        // 需求：弹匣未打空时不应提前回弹（避免边打边回弹导致节奏与面板预期不一致）。
        try {
            val at = weapon.ammoTracker
            if (at != null && at.usesAmmo()) {
                // 强制保持面板设定一致（chunk reload）
                at.setReloadSize(RELOAD_SIZE)

                if (at.ammo > 0) {
                    // 有弹：禁止累积进度，避免“没打空就回弹”
                    at.setAmmoPerSecond(0f)
                    at.setReloadProgress(0f)
                } else {
                    // 空弹匣：允许按设定速度装填
                    at.setAmmoPerSecond(AMMO_PER_SEC)
                }
            }
        } catch (_: Throwable) {
        }

        // ===== 开火限制：前方无有效目标则禁止开火/充能 =====
        // 需求：前方无有效目标时不允许开火。
        // 实现：若无目标，则强制本帧不允许开火，并停止充能。
        val hasTarget = hasValidTargetAhead(engine, ship, weapon)
        if (!hasTarget) {
            try { weapon.setForceNoFireOneFrame(true) } catch (_: Throwable) {}
            try { weapon.stopFiring() } catch (_: Throwable) {}
            try { stats.ballisticRoFMult.unmodify(MOD_ID) } catch (_: Throwable) {}
            return
        }

        val ammo = try { weapon.ammo } catch (_: Throwable) { 0 }
        val firing = try { weapon.isFiring } catch (_: Throwable) { false }
        val cl = try { weapon.chargeLevel } catch (_: Throwable) { 0f }
        val cd = try { weapon.cooldownRemaining } catch (_: Throwable) { 0f }

        // 冷却中不应出现“充能动画”（玩家按住开火键也一样）
        val canShowChargeFx = cd <= 0.001f

        val finisherCharging = firing && ammo == 1 && cl > 0.001f && cl < 0.999f && canShowChargeFx
        val anyCharging = firing && cl > 0.001f && cl < 0.999f && canShowChargeFx

        // 终结技慢充能：仅在 chargeLevel 的前 99% 过程施加慢速；最后 1% 提前释放，避免影响 cooldown。
        val shouldSlow = finisherCharging && cl < FINISHER_RELEASE_CHARGE_LEVEL
        if (shouldSlow) {
            try {
                stats.ballisticRoFMult.modifyMult(MOD_ID, 0.33333334f)
            } catch (_: Throwable) {
            }
        } else {
            try {
                stats.ballisticRoFMult.unmodify(MOD_ID)
            } catch (_: Throwable) {
            }
        }

        // ===== 聚能动画（充能中） =====
        if (!anyCharging) return

        val st = states.getOrPut(weapon) { State() }
        st.t += amount
        if (st.t < CHARGE_FX_INTERVAL) return
        st.t = 0f

        val theme = if (ammo == 1) RED else CYAN
        spawnChargeFx(engine, weapon, cl.coerceIn(0f, 1f), theme)
    }

    private fun hasValidTargetAhead(engine: CombatEngineAPI, source: ShipAPI, weapon: WeaponAPI): Boolean {
        // 弹匣为空时本来就不能开火；这里不再额外限制，避免“没目标导致装填被打断”的错觉。
        val ammo = try { weapon.ammo } catch (_: Throwable) { 0 }
        if (ammo <= 0) return true

        val from = try { weapon.location } catch (_: Throwable) { null } ?: return true
        val facing = try { weapon.currAngle } catch (_: Throwable) { 0f }
        val arcHalf = try { (weapon.arc * 0.5f).coerceAtLeast(0f) + 0.5f } catch (_: Throwable) { 180f }
        val range = try { weapon.range } catch (_: Throwable) { 0f }

        fun withinArcAndRange(p: Vector2f, collisionRadius: Float = 0f): Boolean {
            val d = MathUtils.getDistance(from, p) - collisionRadius
            if (d > range) return false
            val ang = VectorUtils.getAngle(from, p)
            val diff = abs(angleDiffDeg(facing, ang))
            return diff <= arcHalf
        }

        // 1) 舰船/战机（含友军、残骸，仅过滤相位和无碰撞）
        val ships = try { engine.ships } catch (_: Throwable) { null }
        if (ships != null) {
            for (s in ships) {
                val t = s as? ShipAPI ?: continue
                if (t === source) continue
                if (!engine.isEntityInPlay(t)) continue
                if (t.isPhased) continue
                val cc = try { t.collisionClass } catch (_: Throwable) { null }
                if (cc == CollisionClass.NONE) continue
                if (withinArcAndRange(t.location, t.collisionRadius)) return true
            }
        }

        // 2) 导弹（含友军导弹）
        val missiles = try { engine.missiles } catch (_: Throwable) { null }
        if (missiles != null) {
            for (m in missiles) {
                val t = m as? MissileAPI ?: continue
                if (!engine.isEntityInPlay(t)) continue
                val cc = try { t.collisionClass } catch (_: Throwable) { null }
                if (cc == CollisionClass.NONE) continue
                if (withinArcAndRange(t.location, t.collisionRadius)) return true
            }
        }

        return false
    }

    private fun angleDiffDeg(a: Float, b: Float): Float {
        var d = (b - a) % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }

    private fun spawnChargeFx(engine: CombatEngineAPI, weapon: WeaponAPI, chargeLevel: Float, theme: DrvOmegaImpactVfx.Theme) {
        val p = try { weapon.location } catch (_: Throwable) { null } ?: return
        val facing = try { weapon.currAngle } catch (_: Throwable) { 0f }

        // 1) OGL 环：稳定、便宜。半径随 chargeLevel 收束，表现“聚能”。
        try {
            val baseA = 28f + 26f * (1f - chargeLevel)
            val baseB = 14f + 12f * (1f - chargeLevel)
            val alpha = (55 + 120 * chargeLevel).toInt().coerceIn(0, 255)
            OglEllipseRingRenderer.spawn(
                engine,
                OglEllipseRingRenderer.RingSpec(
                    center = Vector2f(p),
                    facing = facing,
                    aSideHalf = baseA,
                    bAlongHalf = baseB,
                    duration = 0.18f,
                    color = Color(theme.fringe.red, theme.fringe.green, theme.fringe.blue, alpha),
                    lineWidthPx = 1.25f,
                    segments = 72,
                    // 向内收缩：用负 expandSpeed
                    expandSpeed = -120f - 140f * chargeLevel,
                    tangentialSpeed = MathUtils.getRandomNumberInRange(-4f, 4f),
                ),
            )
        } catch (_: Throwable) {
        }

        // 2) 轻量扭曲：红移更强（节流后开销可控）
        try {
            BoxUtilCombatVfx.ensureReady(engine)

            val e = DistortionEntity()
            e.setGlobalTimer(0.02f, 0.03f, 0.10f)
            e.setInnerFull(0.32f, 0.32f)
            e.setInnerHardness(0.80f)
            e.setRingHardness(0.55f)

            val size = (16f + 22f * chargeLevel) * theme.distortionSizeMul
            e.setSizeIn(size * 0.55f, size * 0.55f)
            e.setSizeFull(size, size)
            e.setSizeOut(size * 1.85f, size * 1.85f)

            e.setPowerIn(0f)
            e.setPowerFull((0.16f + 0.28f * chargeLevel) * theme.distortionPowerMul)
            e.setPowerOut(0f)

            e.setLocation(Vector2f(p))

            CombatRenderingManager.addEntity(e)
        } catch (_: Throwable) {
        }
    }
}
