package cn.kasuminova.astd.combat.effect.arc

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import org.lwjgl.util.vector.Vector2f

/**
 * “七星”折跃发射器的目标收集与排序（规格 07 §2.2）：
 * PD 候选（导弹/战机）按「本轮可摧毁优先、最近次优」排序；对舰终结取最近敌舰。
 *
 * 动机：候选收集与排序判定全部落在「普通列表 + 值对象」的纯函数重载上
 * （[collectPdCandidates] / [sortPdCandidates] / [selectNearestShip]），
 * 单测用手工构造的候选列表直接驱动，不 mock 引擎（规格 §4.1 用例 7~10）；
 * 触引擎的只有 [select] / [nearestHostileShip] 两行薄封装（列表来源与在役谓词注入）。
 *
 * 已知简化（规格 §2.2 / §4.1 用例 10）：可摧毁预估只看剩余结构值 <= 本轮爆炸伤害，
 * 护盾吸收不预估——带盾目标可能被高估为「可摧毁」，属设计案认可的近似。
 */
object SevenStarsTargetSelector {

    /**
     * 一个候选的排序视图（值对象，纯函数的输入单元）。
     *
     * @property entity 候选实体（导弹、战机或舰船）。
     * @property hitpoints 预估剩余耐久（结构值；护盾不预估）。
     * @property distSq 到折跃起点的距离平方（不开方，距离比较全程平方域）。
     */
    data class Candidate(
        val entity: CombatEntityAPI,
        val hitpoints: Float,
        val distSq: Float,
    )

    /**
     * PD 候选收集（纯函数，规格 §2.2 过滤矩阵）：
     * 敌方导弹（[inPlay] 在役且未过期）+ 敌方战机（非 hulk 且存活），剔除距离 > [jumpRange] 者
     * （恰等值纳入，平方域比较；distSq = 0 重叠目标纳入，无除零路径）；非战机舰船永不入选。
     * 候选 hitpoints 下限 clamp 到 0（负数耐久不参与可摧毁预估，规格 §2.4）。
     */
    fun collectPdCandidates(
        entities: List<CombatEntityAPI>,
        from: Vector2f,
        jumpRange: Float,
        owner: Int,
        inPlay: (CombatEntityAPI) -> Boolean,
    ): List<Candidate> {
        val rangeSq = jumpRange * jumpRange
        val candidates = ArrayList<Candidate>()
        for (entity in entities) {
            val eligible = when (entity) {
                is MissileAPI -> entity.owner != owner && inPlay(entity) && !entity.isExpired
                is ShipAPI -> entity.isFighter && !entity.isHulk && entity.isAlive && entity.owner != owner
                else -> false
            }
            if (!eligible) continue
            val distSq = distSq(from, entity.location)
            if (distSq > rangeSq) continue
            candidates += Candidate(entity, entity.hitpoints.coerceAtLeast(0f), distSq)
        }
        return candidates
    }

    /**
     * PD 候选排序（纯函数）：可摧毁（剩余耐久 <= [aoeDamage]）优先，距离近者次优；
     * 无可摧毁判定时自然退化为最近优先（设计案原文）。
     */
    fun sortPdCandidates(candidates: List<Candidate>, aoeDamage: Float): List<Candidate> =
        candidates.sortedWith(
            compareByDescending<Candidate> { it.hitpoints <= aoeDamage }
                .thenBy { it.distSq },
        )

    /**
     * 对舰终结目标（纯函数，规格 §2.2）：最近敌舰——非战机、非 hulk、存活的敌对舰船，
     * 无范围限制（设计案：最近敌舰，不设距离闸）；无候选返回 null（射弹消散）。
     */
    fun selectNearestShip(entities: List<CombatEntityAPI>, from: Vector2f, owner: Int): ShipAPI? =
        entities
            .asSequence()
            .filterIsInstance<ShipAPI>()
            .filter { !it.isFighter && !it.isHulk && it.isAlive && it.owner != owner }
            .minByOrNull { distSq(from, it.location) }

    /**
     * 选定下一次折跃的 PD 目标（触引擎薄层）：集合来源 = 全表导弹 + 全表舰船，
     * 在役谓词 = [CombatEngineAPI.isEntityInPlay]；无可摧毁判定时排序自然退化为最近优先。
     *
     * @param aoeDamage 本轮闪光爆炸伤害（可摧毁预估基准）。
     * @return 排序首位候选实体；无候选返回 null（调用方进终结判定）。
     */
    fun select(
        engine: CombatEngineAPI,
        from: Vector2f,
        jumpRange: Float,
        owner: Int,
        aoeDamage: Float,
    ): CombatEntityAPI? {
        val entities = ArrayList<CombatEntityAPI>(engine.missiles.size + engine.ships.size)
        entities += engine.missiles
        entities += engine.ships
        val candidates = collectPdCandidates(entities, from, jumpRange, owner) { engine.isEntityInPlay(it) }
        return sortPdCandidates(candidates, aoeDamage).firstOrNull()?.entity
    }

    /** 对舰终结目标（触引擎薄层）：见 [selectNearestShip]。 */
    fun nearestHostileShip(engine: CombatEngineAPI, from: Vector2f, owner: Int): ShipAPI? =
        selectNearestShip(engine.ships, from, owner)

    /** 距离平方（不开方）：两点坐标差的平方和。 */
    private fun distSq(a: Vector2f, b: Vector2f): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return dx * dx + dy * dy
    }
}
