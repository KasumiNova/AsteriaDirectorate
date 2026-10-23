package cn.kasuminova.astd.impl.combat

import cn.kasuminova.astd.api.combat.CombatFeedback
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

object CombatFeedbackImpl : CombatFeedback {
    private val log = Global.getLogger(CombatFeedbackImpl::class.java)

    override fun maintainPlayerStatus(
        engine: CombatEngineAPI,
        key: Any,
        icon: String,
        title: String,
        desc: String,
        negative: Boolean,
    ) {
        engine.maintainStatusForPlayerShip(key, icon, title, desc, negative)
    }

    override fun floatingDamage(
        engine: CombatEngineAPI,
        point: Vector2f,
        amount: Float,
        color: Color,
        target: CombatEntityAPI,
        source: CombatEntityAPI?,
    ) {
        if (amount.isNaN() || amount <= 0f) {
            log.warn("伤害浮字 amount 非法（$amount），属调用方错误，跳过: targetType=${target.javaClass.simpleName}")
            return
        }
        engine.addFloatingDamageText(point, amount, color, target, source)
    }

    override fun floatingText(
        engine: CombatEngineAPI,
        point: Vector2f,
        text: String,
        size: Float,
        color: Color,
        anchor: CombatEntityAPI?,
        flashFrequency: Float,
        flashDuration: Float,
    ) {
        if (text.isBlank()) {
            log.warn("自定义浮字 text 空白，属调用方错误，跳过: anchorType=${anchor?.javaClass?.simpleName ?: "null"}")
            return
        }
        engine.addFloatingText(point, text, size, color, anchor, flashFrequency, flashDuration)
    }
}
