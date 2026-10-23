package cn.kasuminova.astd.combat.effect.arc.starfallwing

import java.util.Collections
import java.util.IdentityHashMap

class PiercePassTracker {

    // 键按引用相等：CombatEntityAPI 不保证 equals 语义。
    private val settled = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())

    private var adaptationLatched = false

    fun touch(target: Any) {
        settled.add(target)
    }

    fun isFirstContact(target: Any): Boolean = target !in settled

    fun trySettleOnce(target: Any): Boolean = settled.add(target)

    fun tryLatchAdaptation(): Boolean {
        if (adaptationLatched) return false
        adaptationLatched = true
        return true
    }

    fun retainContacts(contacts: Set<Any>) {
        settled.retainAll(contacts)
    }
}
