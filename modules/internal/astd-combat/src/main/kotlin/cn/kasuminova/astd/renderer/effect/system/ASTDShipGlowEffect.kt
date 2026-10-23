package cn.kasuminova.astd.renderer.effect.system

import cn.kasuminova.astd.api.AstdLog
import cn.kasuminova.astd.combat.hullmods.arc.ASTDXc001HullModIds
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin
import com.fs.starfarer.api.combat.WeaponAPI
import java.awt.Color

class ASTDShipGlowEffect : EveryFrameWeaponEffectPlugin {

    companion object {
        private const val XC001_BLOOM_WEAPON_ID = ASTDXc001HullModIds.WEAPON_LIGHTS_BLOOM

        private val log = AstdLog.logger

        private val warnedSites = HashSet<String>()

        private fun warnOnce(site: String, t: Throwable) {
            if (warnedSites.add(site)) {
                log.warn("[ASTD] 舰船覆盖发光层：$site 调用异常，跳过本帧（后续同类异常不再重复记录）", t)
            }
        }
    }

    override fun advance(amount: Float, engine: CombatEngineAPI, weapon: WeaponAPI) {
        val weaponId = try {
            weapon.spec?.weaponId
        } catch (t: Throwable) {
            warnOnce("weapon.spec", t)
            null
        } ?: return
        if (!ShipGlowRenderer.isOverlayWeapon(weaponId)) return
        if (!ShipGlowRenderer.isReady(engine)) return

        // 原版每帧覆写 alphaMult，必须修改 color.alpha 才能隐藏装饰武器；共享颜色由 ShipGlowRenderer 恢复。
        try {
            val sprite = weapon.sprite
            if (sprite != null) {
                ShipGlowRenderer.suppressVanillaSprite(sprite)
            }
        } catch (t: Throwable) {
            warnOnce("sprite.color", t)
        }
        try {
            weapon.animation?.alphaMult = 0f
        } catch (t: Throwable) {
            warnOnce("animation.alphaMult", t)
        }

        if (engine.isPaused) return
        try {
            ShipGlowRenderer.onWeaponFrame(engine, weapon, baseColorOf(weaponId))
        } catch (t: Throwable) {
            warnOnce("onWeaponFrame", t)
        }
    }

    private fun baseColorOf(weaponId: String): Color {
        if (weaponId != XC001_BLOOM_WEAPON_ID) return Color.WHITE
        return XC001_COLD_BLUE
    }

    private val XC001_COLD_BLUE: Color = Xc001ColdPalette.lerpColor(
        Xc001ColdPalette.coldFringe,
        Color(255, 236, 228, 255),
        0.22f,
        255,
    )
}
