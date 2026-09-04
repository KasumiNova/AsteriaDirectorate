package cn.kasuminova.astd.combat.effect.arc.qiongjue

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.combat.listeners.DamageDealtModifier
import org.lwjgl.util.vector.Vector2f

/**
 * “穷距”相位轨道炮叠层伤害乘区的逐命中落地通道（规格 05 §2.1 每层 +x% 伤害）。
 *
 * 动机：初版直接写 `weapon.damage.modifier`，烟测实证（05 验收第三轮，qjDmgStatShared=true）
 * **同舰同 spec 武器的 `WeaponAPI.damage.modifier` 是同一个底层 MutableStat**——
 * 双穷距各自写入会互相乘算（满层实测 1.625²=2.6406），破坏「同舰双穷距独立」机制。
 * 因此伤害乘区改走 [DamageDealtModifier]：每次命中回调拿到的是**该发弹体独立的 DamageAPI**，
 * 按 `projectile.weapon` 解析槽位对应的演算 Buff 层数，逐命中写入本发伤害乘区，天然逐武器隔离。
 *
 * 每舰至多登记一个实例（[ensure] 幂等）；无 Buff / 层数为 0 / 非穷距弹体时返回 null 零开销放行。
 * 难度取值每次命中调用 [QiongjueStackMath.resolve] 一次（不缓存，玩家固定 v2）。
 */
class QiongjueDamageDealtModifier : DamageDealtModifier {

    override fun modifyDamageDealt(
        param: Any?,
        target: CombatEntityAPI?,
        damage: DamageAPI?,
        point: Vector2f?,
        shieldHit: Boolean,
    ): String? {
        val projectile = param as? DamagingProjectileAPI ?: return null
        val weapon = projectile.weapon ?: return null
        if (weapon.spec?.weaponId != QiongjuePhaseRailgunDifficulty.WEAPON_ID) return null
        val ship = projectile.source ?: return null
        val buff = ship.qiongjueCalcStacks(weapon) ?: return null
        if (buff.stacks <= 0) return null

        val perStack = QiongjueStackMath.resolve(DifficultyTuningRef, QiongjuePhaseRailgunDifficulty.PER_STACK_BONUS, ship.owner)
        val mult = QiongjueStackMath.mult(buff.stacks, perStack)
        damage?.modifier?.modifyMult(MOD_ID, mult)

        // dev 自动化烟测证据：每命中记录该武器最近一次伤害乘区（键带 ship.id + slotId，双穷距与敌版互不覆盖）。
        val engine = Global.getCombatEngine()
        if (engine != null) {
            engine.customData[telemetryKey(ship, weapon)] = mult
        }
        return MOD_ID
    }

    companion object {
        /** 伤害乘区写入 id 与监听器返回的 stat 来源 id（逐命中 DamageAPI 实例内唯一即可）。 */
        const val MOD_ID = "astd_qiongjue_dealt"

        /** 逐武器最近伤害乘区遥测键前缀（dev 自动化烟测读取；全键 = 前缀 + ship.id + ":" + slotId）。 */
        const val TELEMETRY_DEALT_MULT_PREFIX = "astd_qiongjue_dealt_mult:"

        /** 难度取值入口（生产固定 DifficultyTuningImpl；与 Buff/OnHit 同源）。 */
        private val DifficultyTuningRef = cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl

        /** 该舰的穷距伤害监听器幂等登记（无则补；监听器本体无状态，实例级即可）。 */
        fun ensure(ship: ShipAPI) {
            if (!ship.hasListenerOfClass(QiongjueDamageDealtModifier::class.java)) {
                ship.addListener(QiongjueDamageDealtModifier())
            }
        }

        /** 逐武器最近伤害乘区遥测键（ship.id 区分玩家/敌版，slotId 区分同舰双穷距；Buff 登记已保证武器带槽位）。 */
        fun telemetryKey(ship: ShipAPI, weapon: WeaponAPI): String =
            "$TELEMETRY_DEALT_MULT_PREFIX${ship.id}:${weapon.slot?.id ?: "unknown"}"

        /** dev 自动化烟测读取：该武器最近一次命中的伤害乘区（无记录返回 -1）。 */
        fun dealtMult(engine: CombatEngineAPI, ship: ShipAPI, weapon: WeaponAPI): Float =
            engine.customData[telemetryKey(ship, weapon)] as? Float ?: -1f
    }
}
