package cn.kasuminova.astd.combat.lens.system

import cn.kasuminova.astd.combat.hullmods.affix.AffixUtil
import cn.kasuminova.astd.combat.lens.marks.LensMarks
import cn.kasuminova.astd.renderer.effect.lens.EchoFixationAfterimageRenderer
import cn.kasuminova.astd.renderer.effect.lens.EchoFixationFieldVisualEffect
import cn.kasuminova.astd.renderer.shader.runtime.CombatShaderRuntime
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.input.InputEventAPI
import org.lwjgl.util.vector.Vector2f
import java.util.concurrent.atomic.AtomicLong

/**
 * 回声定影（Echo Fixation）定影场 —— 舰船系统 D 的「定影 → 回放」状态机（spec §2）。
 *
 * 动机（spec §2.1）：施放时在落点建一个圆形「定影场」，定影期内按固定间隔记录场内敌舰
 * 位置快照；定影结束瞬间「回放」——对每个记录敌舰的「过去坐标」附近的当前敌舰施加
 * 「认知撕裂」（叠 1 层误差标记 + 承伤 debuff）。站桩重合度越高（当前位置离过去坐标越近）
 * 增伤越强。是「时间信息战」而非引力物理场。
 *
 * 数值/语义全部以 docs/design/ships/purple/10-unique.md §1（权威源）与
 * docs/superpowers/specs/2026-06-20-gravitational-lens-redesign-design.md §2 为准。
 *
 * 设计决策（见各处注释）：
 * - 过去坐标：取该敌舰**最早一条**快照（定影期开始时的位置）。语义上「我把这一刻拓下来，
 *   它会替你们再死一次」——浮现的残影是本舰记录的「最初的过去」，因此用最早快照体现
 *   「现在被拽回过去」。站桩不动者其「现在」与「最早过去」几乎重合 → 撕裂最强。
 * - 承伤 debuff 计时：**自管 customData 计时**而非复用 StackingShipBuffs。原因：认知撕裂的
 *   承伤倍率是由「当前位置离过去坐标的距离」算出的**连续值**（1 + base × standstill），
 *   不是整数层叠加；StackingShipBuffs 以整数 stacks + 固定 magnitudeMult 为模型，承载连续
 *   倍率会丢失每目标差异。故本场用 EchoTearState 自管到期，于推进插件每帧检查、到期 unapply。
 * - fighter：**不纳入**。spec §2 的威慑目标是「正经舰船」（站桩/走位博弈），战机是
 *   「铺标记的载具」（插件②）而非定影场威慑对象，且战机寿命极短、对其挂 4s 承伤无意义。
 * - 难度系数 m：源自 source 所在赏金舰队的 k（AffixUtil.getK ∈ [0,1]），m = 1 + k ∈ [1,2]，
 *   呼应核心插件「敌方难度缩放（仅敌对单位）」。玩家放场时 source 无赏金 memory → k=0 → m=1。
 *   阶段二若引入统一难度 provider 可在此接缝替换（见 difficultyFactorFor）。
 */
object EchoFixationField {

    private val log = Global.getLogger(EchoFixationField::class.java)

    /**
     * 全局自增场实例 id 源：用于给 shader 边界效果一个稳定的 per-field instanceId
     * （"echo-field-{id}"）。场不绑定单船，故无法复用 ShipAPI identityHashCode；自增 id
     * 保证同一场跨帧 keyed upsert 命中同一实例、不同场互不串扰。
     */
    private val FIELD_ID_SOURCE = AtomicLong(0L)

    /** 单例推进插件挂 engine.customData 的 key（范式同 StackingShipBuffs.ensurePlugin）。 */
    private const val ENGINE_PLUGIN_KEY = "astd_echo_fixation_field_plugin"

    /** 承伤 debuff 写 stat 用的稳定 modifierId（hull + armor 承伤倍率）。 */
    private const val TEAR_MODIFIER_ID = "astd_echo_cognitive_tear"

    /** 承伤 debuff 自管计时挂 ship.customData 的 key 前缀（每船一条 EchoTearState）。 */
    private const val TEAR_STATE_KEY = "astd_echo_tear_state"

    // ---- 数值锚点（spec §2.3，v0 待平衡）----

    /** 定影时长（s）。 */
    private const val FIXATE_DURATION_SEC = 4f

    /** 认知撕裂承伤持续（s）。 */
    private const val TEAR_DURATION_SEC = 4f

    /** 快照记录间隔（s）：定影期按此间隔遍历场内敌舰记录位置。 */
    private const val RECORD_INTERVAL_SEC = 0.25f

    /** 场半径基础值（su，受系统射程影响，spec §2.1：~700su）。 */
    private const val BASE_FIELD_RADIUS = 700f

    /** 站桩判定基础范围（su，受系统射程影响，spec §2.1：500su）。 */
    private const val STANDSTILL_BASE_RANGE = 500f

    // ---- 上限（spec §2.4 风险：必须设上限并 log 超限丢弃，不静默截断）----

    /** 每船历史快照上限：超限丢弃最旧/最新需记录，见 record()。 */
    private const val MAX_SNAPSHOTS_PER_SHIP = 32

    /** 场内被记录目标上限：超限新目标不再纳入记录。 */
    private const val MAX_TARGETS = 24

    /** 单个时间快照：场内某敌舰在时刻 t 的位置。 */
    private data class Snapshot(val x: Float, val y: Float)

    /**
     * 单个被记录敌舰的记录条目（Task 7 残影所需）。
     *
     * 动机：Task 7 回放残影要绘制「被记录敌舰过去时刻的样子」，需要该敌舰的 hullSprite 与
     * 「过去 facing」。原快照只存 x/y，无法取 sprite/facing，故扩展为 TargetRecord：
     * - [ship]：被记录敌舰引用。该敌舰回放时仍在场（record/replay 同一定影周期，≤4s），
     *   引用有效；回放时用其 hullSpec.spriteName 取舰体贴图作残影。条目随场销毁释放，
     *   生命周期 ≤4s，不影响 GC 语义（区别于 Map key 上的长期强引用）。
     * - [pastFacing]：首条快照（「过去坐标」对应时刻）记录的 facing——残影朝向用「过去时刻」
     *   的朝向而非当前朝向，呼应「拓印下来的最初的过去」语义。
     * - [snapshots]：位置历史（时间升序），「过去坐标」= firstOrNull()。
     *
     * Task 4 上限仍生效：本结构仍以 identityHashCode 为 key 入 snapshots 表，MAX_TARGETS /
     * MAX_SNAPSHOTS_PER_SHIP 检查不变（见 record）。
     */
    private class TargetRecord(val ship: ShipAPI, val pastFacing: Float) {
        val snapshots: MutableList<Snapshot> = mutableListOf()
    }

    /**
     * 承伤 debuff 自管计时状态：挂在被撕裂目标 ship.customData 上。
     *
     * 动机：承伤倍率为连续值（按距离算），需自管到期；推进插件每帧对全场 ship 检查，
     * 到期则 unapply stat 并清状态。takenMult 为最终承伤倍率（>1）。
     */
    private data class EchoTearState(val takenMult: Float, val expiresAt: Float)

    /**
     * 单个活跃定影场实例（状态机：定影期累积快照 → 结束瞬间回放 → 标记销毁）。
     *
     * @property centerX 场心 X（世界坐标）。
     * @property centerY 场心 Y（世界坐标）。
     * @property radius 场半径（已按系统射程缩放，记录场内敌舰用）。
     * @property fixateDuration 定影时长（s）。
     * @property difficultyFactor 难度系数 m∈[1,2]（决定认知撕裂基础增伤）。
     * @property systemRangeMult 系统射程倍率（站桩判定范围缩放用）。
     * @property sourceOwner 施放方 owner（用于敌对判定：target.owner != sourceOwner）。
     */
    private class Field(
        val fieldId: Long,
        val centerX: Float,
        val centerY: Float,
        val radius: Float,
        val fixateDuration: Float,
        val difficultyFactor: Float,
        val systemRangeMult: Float,
        val sourceOwner: Int,
    ) {
        /** shader 边界效果的 per-field instanceId（跨帧稳定，keyed upsert 命中同一实例）。 */
        private val visualInstanceId: String = "echo-field-$fieldId"

        /** 场心（世界坐标）缓存为 Vector2f，供 shader 提交复用，避免每帧建对象。 */
        private val centerVec: Vector2f = Vector2f(centerX, centerY)

        /** 已逝时间（s），>= fixateDuration 时触发回放。 */
        var elapsed: Float = 0f

        /** 距上次记录已过时间，>= RECORD_INTERVAL_SEC 时记录一次。 */
        private var recordAcc: Float = 0f

        /** 是否已回放（保证回放只触发一次）。 */
        var replayed: Boolean = false

        /**
         * 记录快照：key = 敌舰 identityHashCode，value = 该舰记录条目（含位置历史 + 残影所需）。
         * 用 identityHashCode 作 key 与项目内 attachment 范式（ASTDVectorThrustEngineManager）一致，
         * 避免持有 ShipAPI 强引用在 Map key 上影响回收语义；回放时再从 engine.ships 解析当前位置。
         * value（TargetRecord）持有的 ShipAPI 引用生命周期 ≤4s（随场销毁），不影响回收。
         */
        val snapshots: LinkedHashMap<Int, TargetRecord> = LinkedHashMap()

        /** 是否已对「目标上限超限」打过日志，避免每帧刷屏。 */
        private var targetCapLogged: Boolean = false

        /**
         * 定影期推进：累积时间，按 RECORD_INTERVAL_SEC 记录场内敌舰快照。
         * Task 6 shader 边界 / Task 7 残影：定影期场的可视化在此驱动（onFieldTick 接缝）。
         */
        fun advanceFixate(engine: CombatEngineAPI, amount: Float) {
            elapsed += amount
            // Task 6 shader 边界：每帧推进场边界可视化（紫罗兰圆环 + 定影进度脉动）。
            submitBoundaryVisual(engine)
            recordAcc += amount
            if (recordAcc < RECORD_INTERVAL_SEC) return
            recordAcc = 0f
            record(engine)
        }

        /**
         * 提交本场边界 shader 效果（keyed upsert，per-field instanceId）。
         * progress = 定影进度（elapsed / fixateDuration），驱动环宽脉动与渐显。
         * Fail Fast：直接调用，不包兜底 catch——shader runtime 自身负责其内部健壮性。
         */
        private fun submitBoundaryVisual(engine: CombatEngineAPI) {
            val progress = (elapsed / fixateDuration).coerceIn(0f, 1f)
            val frame = EchoFixationFieldVisualEffect.frame(fieldRadius = radius, progress = progress)
            EchoFixationFieldVisualEffect.submitFrame(
                sink = CombatShaderRuntime.ensure(engine).sink,
                instanceId = visualInstanceId,
                center = centerVec,
                frame = frame,
            )
        }

        /** 遍历 engine.ships，把场内敌舰当前位置追加进快照表（带上限保护）。 */
        private fun record(engine: CombatEngineAPI) {
            val ships = engine.ships ?: return
            val r2 = radius * radius

            for (ship in ships) {
                if (ship == null) continue
                if (!isFieldTarget(ship)) continue

                val loc = ship.location
                val dx = loc.x - centerX
                val dy = loc.y - centerY
                if (dx * dx + dy * dy > r2) continue

                val key = System.identityHashCode(ship)
                val record = snapshots[key]
                if (record == null) {
                    // 新目标：检查目标数上限。
                    if (snapshots.size >= MAX_TARGETS) {
                        if (!targetCapLogged) {
                            targetCapLogged = true
                            log.warn(
                                "[ASTD] EchoFixationField target cap reached ($MAX_TARGETS), " +
                                    "dropping further in-field targets this fixation"
                            )
                        }
                        continue
                    }
                    // 首条快照同时定格「过去 facing」（Task 7 残影朝向用此）。
                    snapshots[key] = TargetRecord(ship, ship.facing).apply {
                        snapshots.add(Snapshot(loc.x, loc.y))
                    }
                    continue
                }

                val history = record.snapshots
                // 已记录目标：追加快照，检查每船历史上限。
                if (history.size >= MAX_SNAPSHOTS_PER_SHIP) {
                    // 丢弃最旧快照以维持上限，但「过去坐标」取最早快照——
                    // 若直接丢最旧会改变「过去」语义，故此处选择**丢弃最新追加**（不再增长）
                    // 并 log，保证「最早过去」稳定。32 条 × 0.25s = 8s 覆盖远超 4s 定影期，
                    // 正常不会触顶；触顶意味异常长定影或时间缩放，必须告警。
                    log.warn(
                        "[ASTD] EchoFixationField snapshot cap reached " +
                            "($MAX_SNAPSHOTS_PER_SHIP) for a target, dropping newest snapshot"
                    )
                    continue
                }
                history.add(Snapshot(loc.x, loc.y))
            }
        }

        /**
         * 回放：场结束瞬间触发一次。对每个记录敌舰的「过去坐标」（最早快照），
         * 找该坐标 STANDSTILL 范围内的当前敌舰施加认知撕裂。
         *
         * Task 6 shader 边界 / Task 7 残影：每个过去坐标处浮现的残影在此驱动（onReplay 接缝）。
         */
        fun replay(engine: CombatEngineAPI) {
            if (replayed) return
            replayed = true

            val ships = engine.ships ?: return
            val now = engine.getTotalElapsedTime(false)
            // 站桩判定范围（受系统射程缩放）——既作残影命中半径，也作衰减归一基准。
            val standstillRange =
                (STANDSTILL_BASE_RANGE * systemRangeMult).coerceAtLeast(1f)
            val standstillR2 = standstillRange * standstillRange
            val tearBonus = EchoFixationMath.cognitiveTearBonus(difficultyFactor)

            // Task 7 残影：本场回放为一次性同步循环，起始重置渲染器的防御性预算（按场封顶）。
            EchoFixationAfterimageRenderer.resetReplayBudget()

            for ((_, record) in snapshots) {
                val history = record.snapshots
                val past = history.firstOrNull() ?: continue // 「过去坐标」= 最早快照

                // Task 7 残影：在该敌舰「过去坐标 + 过去 facing」处浮现一帧紫罗兰舰体残影
                // （「它会替你们再死一次」）。受 Field 的 MAX_TARGETS 自然封顶，渲染器另设防御性上限。
                if (!record.ship.isHulk) {
                    EchoFixationAfterimageRenderer.spawn(
                        engine = engine,
                        ship = record.ship,
                        pastX = past.x,
                        pastY = past.y,
                        facing = record.pastFacing,
                    )
                }

                for (ship in ships) {
                    if (ship == null) continue
                    if (!isFieldTarget(ship)) continue

                    val loc = ship.location
                    val dx = loc.x - past.x
                    val dy = loc.y - past.y
                    val dist2 = dx * dx + dy * dy
                    if (dist2 > standstillR2) continue

                    val dist = kotlin.math.sqrt(dist2)
                    applyCognitiveTear(engine, ship, dist, standstillRange, tearBonus, now)
                }
            }
        }

        /**
         * 对单个当前敌舰施加认知撕裂：叠 1 层误差标记 + 连续值承伤 debuff。
         *
         * 承伤倍率组合（spec §2，按设计意图「重合越高伤害越高」）：
         *   finalTakenMult = 1 + cognitiveTearBonus(m) × standstillBonus(dist, 500×rangeMult)
         * - standstillBonus 重合时 2.0、边缘 0.25（EchoFixationMath）。
         * - 故重合：1 + base×2.0（base=0.5~1.0 → 承伤 2.0~3.0）；边缘：1 + base×0.25（≈1.125~1.25）。
         * 承伤通过 hullDamageTakenMult + armorDamageTakenMult 写入（护盾不额外加——承伤系数
         * 本身已作用于护盾，spec §2.1）。
         */
        private fun applyCognitiveTear(
            engine: CombatEngineAPI,
            target: ShipAPI,
            distFromPast: Float,
            standstillRange: Float,
            tearBonus: Float,
            now: Float,
        ) {
            // 站桩重合系数：注意 standstillBonus 的 baseRange 须与命中半径一致，
            // 故传 standstillRange 已缩放值、systemRangeMult=1（避免二次缩放）。
            val standstill =
                EchoFixationMath.standstillBonus(distFromPast, standstillRange, 1f)
            val takenMult = 1f + tearBonus * standstill

            // 1) 叠 1 层误差标记（接入标记闭环，spec §2.2）。source=null：
            //    定影场无单一 source 实体引用（场为抽象时间快照），误差标记的难度缩放由
            //    标记自身 provider 决定，与本场承伤难度独立。
            LensMarks.applyDriftMark(engine, null, target, addStacks = 1)

            // 2) 写入承伤 debuff（连续值）+ 自管计时状态。幂等：modifyMult 覆盖同 id。
            target.mutableStats.hullDamageTakenMult.modifyMult(TEAR_MODIFIER_ID, takenMult)
            target.mutableStats.armorDamageTakenMult.modifyMult(TEAR_MODIFIER_ID, takenMult)
            target.setCustomData(
                TEAR_STATE_KEY,
                EchoTearState(takenMult = takenMult, expiresAt = now + TEAR_DURATION_SEC),
            )
        }

        /** 场内/回放目标判定：敌对（owner != sourceOwner）、非 hulk、非 fighter。 */
        private fun isFieldTarget(ship: ShipAPI): Boolean {
            if (ship.isHulk) return false
            if (ship.isFighter) return false
            return ship.owner != sourceOwner
        }
    }

    /**
     * 施放入口（供 Task 5 的舰船系统调用）：在落点建场，挂单例推进插件。
     *
     * @param engine 战斗引擎。
     * @param source 施放舰（引力透镜级）；用于敌对判定与难度系数。
     * @param centerX 落点 X（世界坐标，由系统按鼠标落点给出）。
     * @param centerY 落点 Y（世界坐标）。
     * @param systemRangeMult 系统射程倍率（场半径 / 站桩范围缩放）；由系统从自身射程属性算出后传入。
     *        默认 1f（无加成）。
     */
    fun spawn(
        engine: CombatEngineAPI,
        source: ShipAPI,
        centerX: Float,
        centerY: Float,
        systemRangeMult: Float = 1f,
    ) {
        val rangeMult = systemRangeMult.coerceAtLeast(0.01f)
        val radius = EchoFixationMath.fieldRadius(BASE_FIELD_RADIUS, rangeMult)
        val field = Field(
            fieldId = FIELD_ID_SOURCE.getAndIncrement(),
            centerX = centerX,
            centerY = centerY,
            radius = radius,
            fixateDuration = FIXATE_DURATION_SEC,
            difficultyFactor = difficultyFactorFor(engine, source),
            systemRangeMult = rangeMult,
            sourceOwner = source.owner,
        )
        ensurePlugin(engine).add(field)
        log.info(
            "[ASTD] EchoFixationField spawned center=($centerX,$centerY) radius=$radius " +
                "m=${field.difficultyFactor} rangeMult=$rangeMult"
        )
    }

    /**
     * 难度系数 m∈[1,2]：source 所在赏金舰队 k（AffixUtil.getK）→ m = 1 + k。
     * 玩家放场（无赏金 memory）k=0 → m=1。阶段二统一难度 provider 接缝在此替换。
     */
    private fun difficultyFactorFor(engine: CombatEngineAPI, source: ShipAPI): Float {
        // 玩家本舰放场：明确 m=1（玩家不受敌方难度缩放）。
        // playerShip（可空，无玩家舰返回 null）与 owner（int 字段）在战斗内为安全访问，
        // 不会抛异常，故不包 try——规范禁止刻意兜底。
        val playerShip = engine.playerShip
        if (playerShip != null && source.owner == playerShip.owner) return 1f
        return (1f + AffixUtil.getK(source)).coerceIn(1f, 2f)
    }

    /** 取/建单例推进插件（范式同 StackingShipBuffs.ensurePlugin）。 */
    private fun ensurePlugin(engine: CombatEngineAPI): Plugin {
        val existing = engine.customData[ENGINE_PLUGIN_KEY] as? Plugin
        if (existing != null) return existing

        val plugin = Plugin()
        engine.customData[ENGINE_PLUGIN_KEY] = plugin
        engine.addPlugin(plugin)
        return plugin
    }

    /**
     * 单例推进插件：每帧推进所有活跃定影场（定影 → 回放 → 销毁），
     * 并维护全场承伤 debuff 的自管到期清理。
     */
    private class Plugin : BaseEveryFrameCombatPlugin() {
        /** 当前活跃定影场列表（多场并存，回放后移除）。 */
        private val fields: MutableList<Field> = ArrayList()

        fun add(field: Field) {
            fields.add(field)
        }

        override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
            val engine = Global.getCombatEngine() ?: return
            if (engine.isPaused) return

            advanceFields(engine, amount)
            expireTears(engine)
        }

        /** 推进所有活跃场：定影期累积；到期触发回放并移除。 */
        private fun advanceFields(engine: CombatEngineAPI, amount: Float) {
            if (fields.isEmpty()) return
            val it = fields.iterator()
            while (it.hasNext()) {
                val field = it.next()
                // 不变量：列表内的场始终处于定影期（elapsed < fixateDuration）——
                // 一旦推进跨过阈值即在本帧回放并移除，回放后的场不会留在列表里。
                // 故进入循环时 elapsed 必 < fixateDuration；若不成立即不变量被破坏，Fail Fast。
                if (field.elapsed >= field.fixateDuration) {
                    error("echo fixation field reached unreachable post-replay state")
                }
                field.advanceFixate(engine, amount)
                // 推进后可能刚跨过定影时长——本帧内立即结算回放，避免拖一帧。
                if (field.elapsed >= field.fixateDuration) {
                    field.replay(engine)
                    it.remove()
                }
            }
        }

        /**
         * 承伤 debuff 自管到期清理：遍历全场 ship，读 EchoTearState，
         * 到期或 hulk 则 unapply stat 并清状态。
         */
        private fun expireTears(engine: CombatEngineAPI) {
            val now = engine.getTotalElapsedTime(false)
            val ships = engine.ships ?: return
            for (ship in ships) {
                if (ship == null) continue
                val state = ship.customData[TEAR_STATE_KEY] as? EchoTearState ?: continue
                if (ship.isHulk || now >= state.expiresAt) {
                    ship.mutableStats.hullDamageTakenMult.unmodify(TEAR_MODIFIER_ID)
                    ship.mutableStats.armorDamageTakenMult.unmodify(TEAR_MODIFIER_ID)
                    ship.setCustomData(TEAR_STATE_KEY, null)
                }
            }
        }
    }
}
