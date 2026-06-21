package cn.kasuminova.astd.combat.hullmods.lens

import cn.kasuminova.astd.combat.hullmods.base.ASTDHullModTooltipRenderer
import cn.kasuminova.astd.combat.lens.marks.LensMarkMath
import cn.kasuminova.astd.combat.lens.marks.LensMarks
import cn.kasuminova.astd.combat.lens.ui.LensMarkStatusBar
import cn.kasuminova.astd.renderer.effect.lens.DeepWaterMarkVisualEffect
import cn.kasuminova.astd.renderer.effect.lens.DriftMarkVisualEffect
import cn.kasuminova.astd.renderer.effect.lens.GhostSignalWaveEffect
import cn.kasuminova.astd.renderer.effect.lens.LensVfxTelemetry
import cn.kasuminova.astd.renderer.shader.runtime.CombatShaderRuntime
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.MissileAIPlugin
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.util.IntervalUtil
import com.fs.starfarer.api.util.Misc
import java.awt.Color

/**
 * 透镜阵列核心主体（基座 hullmod，spec §3）。
 *
 * 动机：双模式分模式效果中“需要运行时遍历战斗对象”的部分集中于此基座，避免散落在
 * 多个 marker hullmod 内重复遍历。模式静态属性（蜂群思维/宏观锚定的纯 stat 修改）由
 * 各自的 [ASTDLensAutomatedModeHullMod] / [ASTDLensCrewedModeHullMod] 承载，此处只做
 * 三件运行时事：
 * - 标记状态栏维护（每帧，玩家船左侧 UI）。
 * - 无人·幽灵信号：范围内敌方导弹随机失制导（剥离制导 AI，导弹直飞）。
 * - 载人·情报中枢：按友军吨位等级累加全队 ECM。
 *
 * **载人·战术链路（标记闭环·收割端）——设计决策（用户拍板）：** 战术链路不引入独立的
 * 友军专属增伤层。误差标记（[cn.kasuminova.astd.combat.lens.marks.LensMarks] 经
 * `hullDamageTakenMult`/`armorDamageTakenMult`）的增伤本就对**任何来源**生效，因而载人
 * 模式下全队对被标记目标的伤害已自动获得提升——「战术链路」即对这一既有闭环的载人收割
 * 语义表述，不再叠第二份数值（避免友军双重增伤过强）。故此处无对应运行时逻辑，亦不创建
 * 独立的 on-hit DamageListener；仅在 tooltip line.6 表述该收割语义。
 *
 * 难度系数 m∈[1.0,2.0]（仅敌对单位）属阶段二接入：此处所有面向敌方的效果保持
 * factor=1（见 [DIFFICULTY_FACTOR]），等待阶段二的难度 provider。
 */
class ASTDLensArrayCoreHullMod : BaseHullMod() {

    /**
     * 一道活动的幽灵信号波的瞬时状态。
     *
     * @property seq 自增唯一序号，与 shipId 拼出 instanceId（同舰相邻波不互相覆盖）。
     * @property elapsed 自起波以来的累计秒数，除以 [GhostSignalWaveEffect.WAVE_DURATION] 得 progress。
     */
    private class GhostWave(val seq: Long, var elapsed: Float)

    /**
     * 标记高光波的每-目标脉冲相位表（drift / deepwater 两条独立流）。
     *
     * 动机（Task 8）：周期扩散波需为每艘被标记敌舰单独累加 elapsed（每帧 += amount），由 elapsed 取模
     * [MARK_PULSE_PERIOD] 得 progress——故需一处稳定存活的状态。存于 engine.customData（按
     * [MARK_PULSE_PHASES_KEY]），随战斗实例生命周期，单局结束随 engine 一并释放。
     *
     * 键为目标的 [System.identityHashCode]（与高光 instanceId 同源，稳定标识一艘舰），值为该流上该舰
     * 的累计 elapsed（s）。每帧 [submitMarkHighlights] 推进所有相位并**剪除本帧不再带对应标记的条目**
     * （[prune] 防 customData 随击毁/失标敌舰无界增长——Fail Fast：不静默泄漏）。
     *
     * @property drift 误差标记流：identityHashCode(target) -> elapsed。
     * @property deepWater 深水标记流：identityHashCode(target) -> elapsed。
     */
    private class MarkPulsePhases {
        val drift: MutableMap<Int, Float> = HashMap()
        val deepWater: MutableMap<Int, Float> = HashMap()

        /**
         * 推进一个流上某目标的相位并返回当前 progress（0~1，周期循环）。
         * 首次出现的目标从 elapsed=0 起（progress=0，新波起手）。
         */
        fun advance(stream: MutableMap<Int, Float>, targetId: Int, amount: Float): Float {
            val elapsed = (stream[targetId] ?: 0f) + amount
            stream[targetId] = elapsed
            return (elapsed % MARK_PULSE_PERIOD) / MARK_PULSE_PERIOD
        }

        /** 剪除本帧未出现（已失标/击毁）目标的相位条目，防 customData 无界增长。 */
        fun prune(stream: MutableMap<Int, Float>, aliveTargetIds: Set<Int>) {
            stream.keys.retainAll(aliveTargetIds)
        }
    }

    /**
     * 幽灵信号波自增序号源（单调递增，仅保证唯一性，不参与时间计算）。
     * 同一 hullmod 实例可被多条透镜船共用，但波 instanceId 含 shipId 前缀，故跨舰不冲突。
     */
    private var nextGhostWaveSeq: Long = 0L

    companion object {
        /** 幽灵信号作用半径（su）：与 tooltip line.4 的 ~2000su 一致。 */
        private const val GHOST_RANGE = 2000f

        /** 单枚敌方导弹进入范围后的失制导判定概率。 */
        private const val GHOST_DEFUSE_CHANCE = 0.5f

        /** 幽灵信号心跳间隔（s）。 */
        private const val TICK = 0.25f

        /** 情报中枢 ECM 重算间隔（s）：友军编成变化不频繁，1s 足够。 */
        private const val ECM_TICK = 1.0f

        /**
         * 难度系数（阶段一恒为 1）。阶段二接入难度 provider 后，仅对敌对单位
         * 的幽灵信号概率/范围做 m∈[1.0,2.0] 缩放。
         */
        private const val DIFFICULTY_FACTOR = 1.0f

        /** ECM dynamic stat key：与 AffixVectorSilenceHullMod 一致（"opad_ecm_rating"）。 */
        private const val ECM_DYNAMIC_KEY = "opad_ecm_rating"

        /** 情报中枢 ECM 修改器 id（自身 dynamic stat 上的稳定句柄）。 */
        private const val INTEL_HUB_MOD_ID = "astd_lens_intel_hub"

        /** 幽灵信号波活动列表存储 key（按 shipId 拼接，存 [MutableList] of [GhostWave]）。 */
        private const val GHOST_WAVE_KEY = "astd_lens_core_ghost_waves"

        /** 幽灵信号 IntervalUtil 存储 key（按 shipId 拼接）。 */
        private const val INTERVAL_KEY = "astd_lens_core_interval"
        /** 情报中枢 IntervalUtil 存储 key（按 shipId 拼接）。 */
        private const val ECM_INTERVAL_KEY = "astd_lens_core_ecm_interval"

        /** 已判定过的导弹标记 key（每枚只判定一次，避免逐帧反复判定）。 */
        private const val DEFUSED_KEY = "astd_lens_ghost_defused"

        /**
         * 标记高光波的每-目标脉冲相位存储 key（engine.customData，存 [MarkPulsePhases]）。
         *
         * 高光由静态环改为「一波接一波」的周期扩散波（Task 8 / 用户反馈）后，每艘被标记敌舰需维护
         * 一个独立的 elapsed 累加器：每帧加 amount，progress = (elapsed % period) / period，progress
         * 归零即下一道波起手——形成连绵的周期波。drift/deepwater 两条流相位独立（同船两标记不同步），
         * 故各用一个 map（见 [MarkPulsePhases]）。drift/deepwater 共用同一 [MarkPulsePhases]、按
         * target identityHashCode 分别 keyed。
         */
        private const val MARK_PULSE_PHASES_KEY = "astd_lens_mark_pulse_phases"

        /**
         * 标记高光波单周期时长（s）：一道波从波心扩张到敌舰边缘并消散、紧接下一道的循环周期。
         * ~1.2s 让波「一波接一波」而不拥挤，符合用户「轻量、周期脉冲」反馈。
         */
        private const val MARK_PULSE_PERIOD = 1.2f

        private val THEME = ASTDHullModTooltipRenderer.Theme(
            nameColor = Color(200, 160, 255),
            borderColor = Color(160, 110, 255),
            headerBackground = Color(40, 18, 70, 185),
            sectionBackground = Color(28, 12, 52, 120),
            accentColor = Color(150, 90, 230),
        )
    }

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused || amount <= 0f || ship.isHulk) return

        // 标记状态栏 + 标记高光：仅玩家船每帧维护一次（全场遍历语义，不依赖具体哪条透镜船；
        // 经玩家船单点驱动避免多条透镜船重复提交同一敌舰的高光）。
        if (ship === engine.playerShip) {
            LensMarkStatusBar.maintain(engine)
            submitMarkHighlights(engine, amount)
        }

        if (!ship.isGravitationalLensShip()) return

        val automated = ship.variant?.hasLensAutomatedMode() == true
        val shipId = System.identityHashCode(ship)

        // 心跳间隔：无人→幽灵信号；载人→ECM 情报中枢。
        if (automated) {
            val key = "$INTERVAL_KEY:$shipId"
            var interval = engine.customData[key] as? IntervalUtil
            if (interval == null) {
                interval = IntervalUtil(TICK, TICK)
                engine.customData[key] = interval
            }
            interval.advance(amount)
            if (interval.intervalElapsed()) ghostSignal(ship, engine, shipId)
            // 幽灵信号波每帧推进（progress 平滑外扩，独立于 0.25s 心跳）。
            advanceGhostWaves(ship, engine, shipId, amount)
        } else {
            val key = "$ECM_INTERVAL_KEY:$shipId"
            var interval = engine.customData[key] as? IntervalUtil
            if (interval == null) {
                interval = IntervalUtil(ECM_TICK, ECM_TICK)
                engine.customData[key] = interval
            }
            interval.advance(amount)
            if (interval.intervalElapsed()) intelligenceHub(ship, engine)
        }
    }

    /**
     * 标记高光（每帧，spec §1.1 + Task 8 + 用户反馈）：遍历全场存活非残骸舰船，对带误差标记者提交
     * 紫罗兰扩散波、带深水标记者提交红色扩散波，亮度按层数。高光由旧静态环改为「一波接一波」的
     * 周期向外扩散波（带轻微扭曲，颜色不变）——每艘被标记敌舰维护一个独立脉冲相位，每帧推进 progress
     * 并取模 [MARK_PULSE_PERIOD] 循环，progress 归零即下一道波起手。
     *
     * 每帧提交（而非随幽灵信号/ECM 的 IntervalUtil 节流）的依据：高光走 keyed upsert，runtime 以
     * staleAfter（0.18s）回收久未刷新的实例——若塞进 0.25s/1s 的 interval，刷新周期会超过 staleAfter
     * 导致实例反复过期/重建而闪烁。故必须每帧 upsert 维持实例存活，且 progress 需每帧平滑推进。
     *
     * per-ship instanceId 前缀（"drift-" / "deepwater-"）区分两类，避免同船两标记串扰；标记只会打在
     * 敌舰上，此处按层数 >0 提交即可，无需额外阵营过滤。脉冲相位存于 engine.customData，并在本帧末
     * 剪除已失标/击毁目标的条目，防无界增长（见 [MarkPulsePhases]）。
     *
     * @param amount 本帧 delta（s），用于推进每-目标脉冲相位。
     */
    private fun submitMarkHighlights(engine: CombatEngineAPI, amount: Float) {
        val sink = CombatShaderRuntime.ensure(engine).sink
        val phases = engine.customData[MARK_PULSE_PHASES_KEY] as? MarkPulsePhases
            ?: MarkPulsePhases().also { engine.customData[MARK_PULSE_PHASES_KEY] = it }

        // 本帧实际带标记的目标 id 集合，用于帧末剪除失标/击毁目标的相位条目。
        val activeDrift = HashSet<Int>()
        val activeDeepWater = HashSet<Int>()

        for (target in engine.ships) {
            if (target == null || target.isHulk || target.isFighter) continue
            val targetId = System.identityHashCode(target)

            val driftStacks = LensMarks.driftStacks(target)
            if (driftStacks > 0) {
                activeDrift += targetId
                val progress = phases.advance(phases.drift, targetId, amount)
                val handle = DriftMarkVisualEffect.submitFrame(
                    sink = sink,
                    instanceId = "drift-$targetId",
                    center = target.location,
                    frame = DriftMarkVisualEffect.frame(
                        target.collisionRadius, driftStacks, LensMarkMath.MAX_STACKS, progress,
                    ),
                )
                // Task 12 实机自动化：误差标记高光真实提交计数（handle 非 null = 已 upsert 一帧）。
                if (handle != null) {
                    LensVfxTelemetry.incrementCounter(engine, LensVfxTelemetry.TELEMETRY_DRIFT_MARK_FRAMES)
                }
            }

            val deepWaterStacks = LensMarks.deepWaterStacks(target)
            if (deepWaterStacks > 0) {
                activeDeepWater += targetId
                val progress = phases.advance(phases.deepWater, targetId, amount)
                val handle = DeepWaterMarkVisualEffect.submitFrame(
                    sink = sink,
                    instanceId = "deepwater-$targetId",
                    center = target.location,
                    frame = DeepWaterMarkVisualEffect.frame(
                        target.collisionRadius, deepWaterStacks, LensMarkMath.MAX_STACKS, progress,
                    ),
                )
                // Task 12 实机自动化：深水标记高光真实提交计数。
                if (handle != null) {
                    LensVfxTelemetry.incrementCounter(engine, LensVfxTelemetry.TELEMETRY_DEEP_WATER_MARK_FRAMES)
                }
            }
        }

        // 帧末剪除已失标/击毁目标的相位条目，防 customData 无界增长。
        phases.prune(phases.drift, activeDrift)
        phases.prune(phases.deepWater, activeDeepWater)
    }

    /**
     * 无人·幽灵信号：范围内每枚敌方导弹进入后做一次失制导判定（剥离制导 AI，导弹直飞）。
     * 每枚导弹只判定一次（DEFUSED_KEY 标记），命中概率 GHOST_DEFUSE_CHANCE。
     *
     * 视觉反馈节奏（Task 9）：本 tick 只要实际剥离了**至少一枚**导弹，就起**一道**波（每 tick 至多一道，
     * 而非每剥离一枚一道）——依据：单 tick 内可能同时命中多枚，逐枚起波会一帧刷屏多道紫波。以「tick
     * 内有剥离 → 一道波」聚合，既保证「幽灵信号生效」可见，又不刷屏。波心 = 本舰，最大半径 = 作用范围。
     */
    private fun ghostSignal(ship: ShipAPI, engine: CombatEngineAPI, shipId: Int) {
        val range = GHOST_RANGE * DIFFICULTY_FACTOR
        val chance = (GHOST_DEFUSE_CHANCE * DIFFICULTY_FACTOR).coerceIn(0f, 1f)
        var defusedThisTick = false
        for (missile in engine.missiles) {
            if (missile == null) continue
            if (missile.owner == ship.owner) continue
            if (missile.customData.containsKey(DEFUSED_KEY)) continue
            if (Misc.getDistance(ship.location, missile.location) > range) continue
            // 已纳入判定，标记避免下次重复（无论是否命中）。
            missile.setCustomData(DEFUSED_KEY, true)
            if (Math.random().toFloat() < chance) {
                defuse(missile)
                defusedThisTick = true
            }
        }
        if (defusedThisTick) spawnGhostWave(engine, shipId)
    }

    /**
     * 本 tick 起一道幽灵信号波：在该舰的活动波列表追加一个 [GhostWave]（progress 从 0 起，
     * 由 [advanceGhostWaves] 每帧推进）。每道波一个自增 seq，避免同舰相邻波 instanceId 相同
     * 互相覆盖（keyed upsert 以 instanceId 去重）。
     */
    private fun spawnGhostWave(engine: CombatEngineAPI, shipId: Int) {
        val key = "$GHOST_WAVE_KEY:$shipId"
        @Suppress("UNCHECKED_CAST")
        val waves = (engine.customData[key] as? MutableList<GhostWave>)
            ?: ArrayList<GhostWave>().also { engine.customData[key] = it }
        waves += GhostWave(seq = nextGhostWaveSeq++, elapsed = 0f)
    }

    /**
     * 每帧推进该舰所有活动幽灵信号波并重新 upsert，progress 达 1 的波退出列表（实例随后经
     * staleAfter 自然退休）。波心始终跟随本舰当前位置（电子战脉冲源是本舰）。
     *
     * keyed upsert + CPU progress 的依据见 [GhostSignalWaveEffect] 类注释（renderer 的 one-shot
     * u_time 恒为 0，无法自播放扩张动画，故走 keyed 通道并由 CPU 驱动 progress）。
     */
    private fun advanceGhostWaves(ship: ShipAPI, engine: CombatEngineAPI, shipId: Int, amount: Float) {
        val key = "$GHOST_WAVE_KEY:$shipId"
        @Suppress("UNCHECKED_CAST")
        val waves = engine.customData[key] as? MutableList<GhostWave> ?: return
        if (waves.isEmpty()) return

        val sink = CombatShaderRuntime.ensure(engine).sink
        val iterator = waves.iterator()
        while (iterator.hasNext()) {
            val wave = iterator.next()
            wave.elapsed += amount
            val progress = wave.elapsed / GhostSignalWaveEffect.WAVE_DURATION
            if (progress >= 1f) {
                iterator.remove()
                continue
            }
            val handle = GhostSignalWaveEffect.submitFrame(
                sink = sink,
                instanceId = "ghost-wave-$shipId-${wave.seq}",
                center = ship.location,
                frame = GhostSignalWaveEffect.frame(
                    range = GHOST_RANGE * DIFFICULTY_FACTOR,
                    progress = progress,
                ),
            )
            // Task 12 实机自动化：幽灵信号波真实提交计数（仅无人模式经此路径）。
            if (handle != null) {
                LensVfxTelemetry.incrementCounter(engine, LensVfxTelemetry.TELEMETRY_GHOST_SIGNAL_WAVE_FRAMES)
            }
        }
    }

    /**
     * 失制导手段：用一个 no-op [MissileAIPlugin] 顶替原制导 AI（信息战语义——幽灵信号
     * 干扰火控电子系统，剥离制导而非熄火坠落）。导弹保留当前速度/推力，但不再被任何
     * AI 转向，因而保持当前航向直飞，符合 tooltip“失去制导”的世界观。
     * try/catch 仅防御单枚导弹此刻被引擎回收导致的 API 不可达，不吞业务错误。
     */
    private fun defuse(missile: MissileAPI) {
        try {
            // no-op：制导被剥离，导弹保持当前航向直飞（不发出任何转向/加速指令）。
            missile.setMissileAI(MissileAIPlugin { /* no-op：制导被剥离 */ })
        } catch (t: Throwable) {
            Global.getLogger(ASTDLensArrayCoreHullMod::class.java)
                .warn("[lens] ghost signal failed to strip missile AI", t)
        }
    }

    /**
     * 载人·情报中枢：统计同阵营存活友军（排除舰载机/残骸）的吨位等级，
     * 经 [LensEcmContribution] 累加为 ECM 分数，写回自身 dynamic stat。
     */
    private fun intelligenceHub(ship: ShipAPI, engine: CombatEngineAPI) {
        var fr = 0
        var de = 0
        var cr = 0
        var ca = 0
        for (other in engine.ships) {
            if (other == null || other.isHulk || other.isFighter) continue
            if (other.owner != ship.owner) continue
            if (other === ship) continue
            when (other.hullSize) {
                ShipAPI.HullSize.FRIGATE -> fr++
                ShipAPI.HullSize.DESTROYER -> de++
                ShipAPI.HullSize.CRUISER -> cr++
                ShipAPI.HullSize.CAPITAL_SHIP -> ca++
                else -> {}
            }
        }
        val ecm = LensEcmContribution.totalEcmFraction(fr, de, cr, ca)
        ship.mutableStats.dynamic.getMod(ECM_DYNAMIC_KEY).modifyFlat(INTEL_HUB_MOD_ID, ecm)
    }

    override fun addPostDescriptionSection(
        tooltip: TooltipMakerAPI,
        hullSize: ShipAPI.HullSize,
        ship: ShipAPI?,
        width: Float,
        isForModSpec: Boolean,
    ) {
        ASTDHullModTooltipRenderer.renderBlocks(
            tooltip = tooltip,
            width = width,
            title = spec?.displayName ?: "",
            theme = THEME,
            blocks = listOf(
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.lens_core.summary"),
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.lens_core.line.1"),
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.lens_core.line.2"),
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.lens_core.line.3"),
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.lens_core.line.4"),
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.lens_core.line.5"),
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.lens_core.line.6"),
            ),
        )
    }

    override fun isApplicableToShip(ship: ShipAPI): Boolean = ship.isGravitationalLensShip()

    override fun showInRefitScreenModPickerFor(ship: ShipAPI): Boolean = false

    override fun getBorderColor(): Color = THEME.borderColor

    override fun getNameColor(): Color = THEME.nameColor
}
