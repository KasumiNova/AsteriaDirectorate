package cn.kasuminova.astd.renderer.effect.system

import cn.kasuminova.astd.api.AstdLog
import cn.kasuminova.astd.combat.hullmods.arc.ASTDXc001HullModIds
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin
import com.fs.starfarer.api.combat.WeaponAPI
import java.awt.Color

/**
 * ASTD 舰船覆盖发光层（bloom / 装饰灯）装饰武器的 everyFrameEffect：
 * 战斗内禁用原版装饰武器渲染（sprite 颜色 alpha 压 0——非动画武器的 render 每帧
 * 覆写 alphaMult，压颜色才有效；animation alphaMult 同步压 0，暂停帧也压住——
 * 定格取景依赖），画面由 [ShipGlowRenderer] 的 BoxUtil 实体完全接管；
 * 装配界面不运行本 effect，仍走原版静态渲染。
 */
class ASTDShipGlowEffect : EveryFrameWeaponEffectPlugin {

    companion object {
        private const val XC001_BLOOM_WEAPON_ID = ASTDXc001HullModIds.WEAPON_LIGHTS_BLOOM

        private val log = AstdLog.logger

        /** 每帧回调里的异常告警按调用点只记一次，避免刷屏。 */
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
        // 渲染器未安装（初始化失败）时不压制原版渲染，保留装饰层可见
        if (!ShipGlowRenderer.isReady(engine)) return

        // 禁用原版装饰武器渲染：非动画武器的 render 每帧以舰船 alpha 覆写
        // sprite.alphaMult（obf MissileWeapon.render），压 alphaMult 无效，
        // 必须压颜色 alpha（render 不触碰 color）
        try {
            val sprite = weapon.sprite
            if (sprite != null && sprite.color.alpha != 0) {
                sprite.color = Color(sprite.color.red, sprite.color.green, sprite.color.blue, 0)
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

    /** 覆盖层基底颜色：xc_001 bloom 始终冷态蓝（不随战术系统过载变色），其余白色原样。 */
    private fun baseColorOf(weaponId: String): Color {
        if (weaponId != XC001_BLOOM_WEAPON_ID) return Color.WHITE
        return XC001_COLD_BLUE
    }

    /** xc_001 bloom 冷态蓝基底（常量，避免每帧构造）。 */
    private val XC001_COLD_BLUE: Color = run {
        val from = Xc001OverdriveVisualState.lerpColor(
            Xc001OverdriveVisualState.coldFringe,
            Color(255, 236, 228, 255),
            0.22f,
            255,
        )
        Color(from.red, from.green, from.blue, 255)
    }
}
