package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import org.boxutil.units.standard.entity.FlareEntity

internal object FlareEntityPool {

    private const val KEY = "astd_flare_entity_pool"

    private const val MAX_ENTITIES = 512

    private const val IDLE_FULL_SECONDS = 1e7f

    private val log = Global.getLogger(FlareEntityPool::class.java)

    private class LayerPool {
        val idle = ArrayDeque<FlareEntity>()
    }

    private class Pool {
        val layers = HashMap<CombatEngineLayers, LayerPool>()
        var created = 0
    }

    fun checkout(engine: CombatEngineAPI, layer: CombatEngineLayers): FlareEntity? {
        val pool = poolOf(engine)
        val layerPool = pool.layers.getOrPut(layer) { LayerPool() }
        while (true) {
            val reused = layerPool.idle.removeLastOrNull() ?: break
            if (reused.hasDelete()) {
                pool.created--
                continue
            }
            return reused
        }
        if (pool.created >= MAX_ENTITIES) {
            log.warn("ASTD flare 池达到上限 $MAX_ENTITIES（layer=$layer）：本弹体光斑层缺失，其余特效层照常")
            return null
        }
        BoxUtilCombatVfx.ensureReady(engine)
        val created = FlareEntity()
        // BoxUtil 注册时读取图层，必须先设置再加入渲染器。
        created.setLayer(layer)
        created.setGlobalTimer(0f, IDLE_FULL_SECONDS, 0f)
        created.setGlobalAlpha(0f)
        val state = BoxUtilCombatVfx.addEntity(engine, created)
        if (state != 0) {
            log.warn("ASTD flare 池注册失败（addEntity 返回 $state，layer=$layer）：本弹体光斑层缺失，其余特效层照常")
            created.delete()
            return null
        }
        pool.created++
        return created
    }

    fun release(engine: CombatEngineAPI, layer: CombatEngineLayers, entity: FlareEntity) {
        if (entity.hasDelete()) return
        entity.setGlobalAlpha(0f)
        poolOf(engine).layers.getOrPut(layer) { LayerPool() }.idle.addLast(entity)
    }

    private fun poolOf(engine: CombatEngineAPI): Pool =
        engine.customData[KEY] as? Pool ?: Pool().also { engine.customData[KEY] = it }
}
