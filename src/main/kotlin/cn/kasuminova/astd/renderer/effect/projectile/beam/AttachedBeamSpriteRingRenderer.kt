package cn.kasuminova.astd.renderer.effect.projectile.beam

import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.CombatLayeredRenderingPlugin
import com.fs.starfarer.api.combat.ViewportAPI
import com.fs.starfarer.api.graphics.SpriteAPI
import org.boxutil.base.api.InstanceDataAPI
import org.boxutil.define.BoxDatabase
import org.boxutil.define.BoxEnum
import org.boxutil.define.InstanceType
import org.boxutil.manager.CombatRenderingManager
import org.boxutil.units.standard.attribute.Instance2Data
import org.boxutil.units.standard.entity.SpriteEntity
import org.lazywizard.lazylib.MathUtils
import java.awt.Color
import java.util.ArrayDeque
import java.util.EnumSet
import kotlin.math.max
import kotlin.math.pow

/**
 * “跟随光束的永久环”（SpriteEntity 版）：
 * - 用一张“空心圆环”贴图（白色 + alpha）作为 diffuse/emissive，避免 FlareEntity 的“圆盘感”；
 * - 仍用 fixed instance data 做批量渲染与渐隐。
 */
internal object AttachedBeamSpriteRingRenderer {

    private const val ENGINE_KEY = "astd_attached_beam_sprite_ring_renderer"
    private const val KEY_LOG_ADD_SPRITE_FAIL_ONCE = "astd_attached_beam_sprite_ring_add_fail_once"

    private val log = Global.getLogger(AttachedBeamSpriteRingRenderer::class.java)

    enum class Mode {
        /** 按距离采样的“永久环”：整条束上持续存在，可沿束方向滚动。 */
        PERMANENT,

        /** 只在束根部生成的“散发环”：不沿束推进，生成后按寿命淡出。 */
        MUZZLE_EMIT,

        /** 炮口单圈“脉冲环”：同一时间仅存在一个环，快速从小变大（类音爆圈），并按寿命淡出。 */
        MUZZLE_PULSE,
    }

    data class Spec(
        val mode: Mode = Mode.PERMANENT,
        val spacing: Float,
        val travelSpeed: Float,
        val aSideHalf: Float,
        val bAlongHalf: Float,
        /** 沿束方向的距离偏移（su）。用于让子光圈相对主光圈做固定 offset。 */
        val distanceOffset: Float = 0f,
        /**
         * 束尾提前淡出距离（su）。
         * 目的：环接近光束命中点（束尾）时开始淡出，而不是“越过命中点后”才淡出/消失。
         * 0 表示自动：按 spacing 推导。
         */
        val endFadeDistance: Float = 0f,
        /** 仅使用 RGB；alpha 会转为 instance alpha（避免与材质 alpha 叠乘造成难控）。 */
        val color: Color,
        /** 束根部 scale。 */
        val headScale: Float = 1.00f,
        /** 束尾 scale（例如 2.0 表示尾部 200%）。 */
        val tailScale: Float = 1.20f,
        /** 缩放插值指数（>1 表示前段变化更慢）。 */
        val scaleExponent: Float = 1.65f,
        /** 束根部额外放大（乘在 headScale~tailScale 之上），用于实现 300%~400% 的“炮口光圈”。 */
        val muzzleExtraScaleMin: Float = 1.50f,
        val muzzleExtraScaleMax: Float = 2.00f,
        /** 束根部额外放大衰减距离（su）。0 表示自动（按 spacing 推导）。 */
        val muzzleExtraDistance: Float = 0f,
        /** 出生淡入距离（su）。0 表示自动（约为 spacing 的一小段）。 */
        val spawnFadeInDistance: Float = 0f,

        /** MUZZLE_EMIT：每秒生成数量。<=0 则自动用 travelSpeed/spacing 推导。 */
        val emitRatePerSec: Float = 0f,

        /** MUZZLE_EMIT：每个环的寿命（秒）。<=0 则自动。 */
        val emitLifetime: Float = 0f,

        /** MUZZLE_EMIT：沿束方向的散布距离（su），在 [0, spread] 之间随机；0 表示固定在束根。 */
        val muzzleSpreadDistance: Float = 0f,

        /** MUZZLE_PULSE：脉冲环寿命（秒）。<=0 则自动。 */
        val pulseLifetime: Float = 0f,

        /** MUZZLE_PULSE：尺寸从 start -> end（乘在常规 head/tail + muzzleExtra 之后）。 */
        val pulseStartScale: Float = 0.40f,
        val pulseEndScale: Float = 2.40f,

        /** MUZZLE_PULSE：缩放曲线指数（<1 代表更“快速变大”）。 */
        val pulseScaleExponent: Float = 0.40f,

        /** Sprite shader 的 glowPower（可 > 1.0） */
        val glowPower: Float = 1.0f,
        val layer: CombatEngineLayers = CombatEngineLayers.ABOVE_PARTICLES,
        val maxInstances: Int = 96,
    )

    private data class Particle(
        var dist: Float,
        var age: Float,
        val life: Float,
        val muzzleExtraScale: Float,
    )

    private data class Attachment(
        var line: BeamLineUtil.BeamLine,
        var spec: Spec,
        // PERMANENT：滚动偏移（su）。
        var scroll: Float,

        // MUZZLE_EMIT：生成累积器（按“个”累计）。
        var emitAcc: Float,
        var lastUpdated: Float,
        var fadeStartedAt: Float?,
        var fadeMul: Float,
        val sprite: SpriteEntity,
        val instances: MutableList<Instance2Data>,
        val particles: ArrayDeque<Particle>,
    )

    fun upsert(engine: CombatEngineAPI, key: String, line: BeamLineUtil.BeamLine, spec: Spec) {
        val r = getOrCreate(engine)
        val now = safeTime(engine)

        val existing = r.map[key]
        if (existing == null) {
            val att = createAttachment(engine, line, spec)
            att.lastUpdated = now
            r.map[key] = att
            try {
                r.updateAttachment(att, amount = 0f, now = now)
            } catch (_: Throwable) {
            }
        } else {
            existing.line = line
            // 若模式切换，清掉对应缓存（避免从“永久环”切到“散发环”后出现残留）
            val oldMode = existing.spec.mode
            existing.spec = spec
            existing.lastUpdated = now
            existing.fadeStartedAt = null
            existing.fadeMul = 1f
            if (oldMode != spec.mode) {
                existing.particles.clear()
                existing.emitAcc = 0f
                existing.scroll = 0f
            }
            try {
                applySpecToSprite(existing.sprite, spec)
            } catch (_: Throwable) {
            }
        }
    }

    fun remove(engine: CombatEngineAPI, key: String) {
        val r = engine.customData[ENGINE_KEY] as? Renderer ?: return
        val att = r.map.remove(key) ?: return
        try {
            att.sprite.delete()
        } catch (_: Throwable) {
        }
    }

    private fun getOrCreate(engine: CombatEngineAPI): Renderer {
        val existing = engine.customData[ENGINE_KEY] as? Renderer
        if (existing != null && !existing.isExpired) return existing

        val r = Renderer()
        r.bindEngine(engine)
        engine.addLayeredRenderingPlugin(r)
        engine.customData[ENGINE_KEY] = r
        return r
    }

    private fun safeTime(engine: CombatEngineAPI): Float {
        return try {
            engine.getTotalElapsedTime(false)
        } catch (_: Throwable) {
            0f
        }
    }

    private fun createAttachment(engine: CombatEngineAPI, line: BeamLineUtil.BeamLine, spec: Spec): Attachment {
        BoxUtilCombatVfx.ensureReady(engine)

        // 先创建实体，再由 applySpecToSprite() 尝试绑定“空心圆环”贴图；生成失败会自然退化到白贴图（方块），但不会崩。
        val spriteEntity = SpriteEntity()
        applySpecToSprite(spriteEntity, spec)

        // 关键：必须初始化实体的变换矩阵（否则可能全零矩阵导致完全不可见）。
        // 这里用“本地坐标系”：entity 负责从(from,facing)建立坐标，instance 只写 (dist,0) 的局部位置。
        try {
            spriteEntity.setStateVanilla(line.from, line.facing)
        } catch (_: Throwable) {
        }

        val list = ArrayList<Instance2Data>(spec.maxInstances)
        repeat(spec.maxInstances) {
            val d = Instance2Data()
            // 参照 BoxUtil mission / 本模组成功用法：用 Instance2Data 的标准 setColor/setEmissiveColor。
            // 颜色具体由 materialData 的 emissiveColor 控制；这里主要保证 alpha 非 0、不会被 discard。
            d.setColor(255, 255, 255, 255)
            d.setEmissiveColor(255, 255, 255, 255)
            // timer 给一个极长的 FULL，避免 instance 侧 timer 异常导致被清理。
            d.setTimer(0f, 99999f, 0f)
            list.add(d)
        }
        @Suppress("UNCHECKED_CAST")
        val apiList = list as MutableList<InstanceDataAPI>
        // 参照成功案例：setInstanceData + submitInstanceData + alwaysRefresh
        spriteEntity.setInstanceData(apiList, 0f, 99999f, 0f)
        spriteEntity.setInstanceDataRefreshIndex(0)
        spriteEntity.setInstanceDataRefreshAllFromCurrentIndex()
        try {
            submitDynamicInstanceData(spriteEntity, apiList.size)
        } catch (_: Throwable) {
        }
        spriteEntity.setRenderingCount(0)
        spriteEntity.setAlwaysRefreshInstanceData(true)

        // 我们用 instanceTimerOverride 来统一控制 alpha（避免依赖 instance timer 细节/版本差异）。
        try {
            spriteEntity.setInstanceTimerOverride(1f, BoxEnum.TIMER_FULL)
        } catch (_: Throwable) {
        }

        val addState = try {
            BoxUtilCombatVfx.addEntity(engine, BoxEnum.ENTITY_SPRITE, spriteEntity)
        } catch (t: Throwable) {
            if (engine.customData[KEY_LOG_ADD_SPRITE_FAIL_ONCE] != true) {
                engine.customData[KEY_LOG_ADD_SPRITE_FAIL_ONCE] = true
                log.warn("BoxUtil addEntity threw exception (target=${BoxEnum.ENTITY_SPRITE})", t)
            }
            -1
        }

        if (addState != 0 && engine.customData[KEY_LOG_ADD_SPRITE_FAIL_ONCE] != true) {
            engine.customData[KEY_LOG_ADD_SPRITE_FAIL_ONCE] = true
            log.warn("BoxUtil addEntity failed (state=$addState, target=${BoxEnum.ENTITY_SPRITE}). Rings may be invisible until CRM is ready.")
        }

        return Attachment(
            line = line,
            spec = spec,
            scroll = 0f,
            emitAcc = 0f,
            lastUpdated = safeTime(engine),
            fadeStartedAt = null,
            fadeMul = 1f,
            sprite = spriteEntity,
            instances = list,
            particles = ArrayDeque(),
        )
    }

    private fun applySpecToSprite(sprite: SpriteEntity, spec: Spec) {
        sprite.setLayer(spec.layer)
        sprite.setAdditiveBlend()

        val s = try {
            GeneratedRingSprite.getOrCreateSprite()
        } catch (_: Throwable) {
            null
        }
        if (s != null) {
            sprite.setDiffuseSprite(s)
            sprite.setEmissiveSprite(s)
        } else {
            // 兜底：至少让它“能画出来”，方便定位资源问题。
            sprite.materialData.setDiffuse(BoxDatabase.BUtil_ONE)
            sprite.materialData.setEmissive(BoxDatabase.BUtil_ONE)
        }

        // 让输出主要走 emissive（更像光圈，不像“贴片实体”）
        val rgb = Color(spec.color.red, spec.color.green, spec.color.blue, 255)
        // BoxUtil 的 sprite shader 在某些路径下会把 entityColor alpha 当作全局 alpha/early-cull。
        // 如果这里为 0，可能导致 emissive 完全不可见（哪怕 alphaToEmissive=0）。
        // 维持 RGB=0（不输出底色），但让 alpha=255，确保 emissive 一定能参与混合。
        sprite.materialData.setColor(Color(0, 0, 0, 255))
        sprite.materialData.setEmissiveColor(rgb)
        // BoxUtil SpriteShader：emissiveState.x=alphaMix, y=colorMix。
        // 之前 alphaMix=1 且 entityColor alpha=0，会导致 emissive alpha 被乘成 0 然后直接 discard。
        // 这里用 alphaMix=0，确保 emissive alpha 不依赖 diffuse/entityColor alpha。
        sprite.materialData.setEmissiveState(0f, 0f, max(0f, spec.glowPower))
        sprite.materialData.setAdditionEmissive(true)
        sprite.materialData.setIgnoreIllumination(true)

        sprite.setBaseSizePerTiles(1f, 1f)
    }

    private class Renderer : CombatLayeredRenderingPlugin {

        private var expired = false
        private var engine: CombatEngineAPI? = null
        val map: MutableMap<String, Attachment> = LinkedHashMap()

        fun bindEngine(engine: CombatEngineAPI) {
            this.engine = engine
        }

        override fun init(entity: CombatEntityAPI) {
            if (entity is CombatEngineAPI) this.engine = entity
        }

        override fun cleanup() {
            map.values.forEach {
                try {
                    it.sprite.delete()
                } catch (_: Throwable) {
                }
            }
            map.clear()
            expired = true
            engine = null
        }

        override fun advance(amount: Float) {
            if (expired) return
            val eng = engine ?: Global.getCombatEngine() ?: return
            if (eng.isPaused) return
            if (map.isEmpty()) return

            val now = safeTime(eng)
            val it = map.entries.iterator()
            while (it.hasNext()) {
                val att = it.next().value

                when (att.spec.mode) {
                    Mode.PERMANENT -> {
                        val spd = att.spec.travelSpeed
                        if (spd > 0.01f) att.scroll += spd * amount
                    }

                    Mode.MUZZLE_EMIT -> {
                        val spacing = att.spec.spacing.coerceAtLeast(1f)
                        val spd = att.spec.travelSpeed
                        val autoRate = if (spacing > 0.01f && spd > 0.01f) (spd / spacing) else 3.0f
                        val rate = (if (att.spec.emitRatePerSec > 0.01f) att.spec.emitRatePerSec else autoRate).coerceAtLeast(0f)

                        val autoLife = (if (rate > 0.01f) (1.25f / rate) else 0.40f).coerceIn(0.18f, 0.75f)
                        val life = (if (att.spec.emitLifetime > 0.01f) att.spec.emitLifetime else autoLife).coerceIn(0.10f, 1.25f)

                        // 年龄推进 + 过期清理
                        run {
                            val pit = att.particles.iterator()
                            while (pit.hasNext()) {
                                val p = pit.next()
                                p.age += amount
                                if (p.age >= p.life) pit.remove()
                            }
                        }

                        // 生成新粒子：不推进，只在束根附近散发
                        att.emitAcc += rate * amount
                        val spawn = att.emitAcc.toInt().coerceAtMost(12)
                        if (spawn > 0) att.emitAcc -= spawn

                        val spread = att.spec.muzzleSpreadDistance.coerceAtLeast(0f)
                        for (iSpawn in 0 until spawn) {
                            val d0 = if (spread > 0.01f) MathUtils.getRandomNumberInRange(0f, spread) else 0f
                            val dist = (d0 + att.spec.distanceOffset).coerceAtLeast(0f)
                            val extra = MathUtils.getRandomNumberInRange(att.spec.muzzleExtraScaleMin, att.spec.muzzleExtraScaleMax)
                            att.particles.addLast(Particle(dist = dist, age = 0f, life = life, muzzleExtraScale = extra))
                        }

                        while (att.particles.size > att.spec.maxInstances) {
                            att.particles.removeFirst()
                        }
                    }

                    Mode.MUZZLE_PULSE -> {
                        // 年龄推进 + 过期清理
                        run {
                            val pit = att.particles.iterator()
                            while (pit.hasNext()) {
                                val p = pit.next()
                                p.age += amount
                                if (p.age >= p.life) pit.remove()
                            }
                        }

                        // 同一时间只保留一个环；若已空则立刻生成下一个
                        if (att.particles.isEmpty()) {
                            val autoLife = 0.22f
                            val life = (if (att.spec.pulseLifetime > 0.01f) att.spec.pulseLifetime else autoLife).coerceIn(0.10f, 0.85f)

                            val spread = att.spec.muzzleSpreadDistance.coerceAtLeast(0f)
                            val d0 = if (spread > 0.01f) MathUtils.getRandomNumberInRange(0f, spread) else 0f
                            val dist = (d0 + att.spec.distanceOffset).coerceAtLeast(0f)
                            val extra = MathUtils.getRandomNumberInRange(att.spec.muzzleExtraScaleMin, att.spec.muzzleExtraScaleMax)
                            att.particles.addLast(Particle(dist = dist, age = 0f, life = life, muzzleExtraScale = extra))
                        } else {
                            // 防御：若外部误配置 maxInstances>1 或残留，强制裁成 1
                            while (att.particles.size > 1) {
                                att.particles.removeFirst()
                            }
                        }
                    }
                }

                val idle = now - att.lastUpdated
                if (idle > 0.09f) {
                    if (att.fadeStartedAt == null) att.fadeStartedAt = now
                    val fadeT = ((now - (att.fadeStartedAt ?: now)) / 0.95f).coerceIn(0f, 1f)
                    val f = (1f - fadeT).coerceIn(0f, 1f)
                    att.fadeMul = (f * f * f).coerceIn(0f, 1f)
                    if (att.fadeMul <= 0.001f) {
                        try {
                            att.sprite.delete()
                        } catch (_: Throwable) {
                        }
                        it.remove()
                        continue
                    }
                } else {
                    att.fadeMul = 1f
                    att.fadeStartedAt = null
                }

                try {
                    updateAttachment(att, amount = amount, now = now)
                } catch (_: Throwable) {
                }
            }
        }

        fun updateAttachment(att: Attachment, amount: Float, now: Float) {
            val eng = engine ?: return
            // 确保 modelMatrix 正确（防止“从未 setStateVanilla 导致矩阵为默认值/不可见”）。
            try {
                att.sprite.setStateVanilla(att.line.from, att.line.facing)
            } catch (_: Throwable) {
            }

            val line = att.line
            val len = line.length
            if (len <= 8f) {
                att.sprite.setRenderingCount(0)
                return
            }

            val spec = att.spec
            val spacing = spec.spacing.coerceAtLeast(1f)
            val endFadeDist = (if (spec.endFadeDistance > 0.01f) spec.endFadeDistance else (spacing * 1.25f)).coerceAtLeast(24f)
            val muzzleExtraDist = (if (spec.muzzleExtraDistance > 0.01f) spec.muzzleExtraDistance else (spacing * 1.75f)).coerceAtLeast(48f)
            val spawnFadeInDist = (if (spec.spawnFadeInDistance > 0.01f) spec.spawnFadeInDistance else (spacing * 0.55f)).coerceAtLeast(18f)
            val maxCount = spec.maxInstances.coerceAtLeast(1)

            val baseAlpha = (spec.color.alpha / 255f).coerceIn(0f, 1f)
            val alphaMul = (baseAlpha * att.fadeMul).coerceIn(0f, 1f)
            if (alphaMul <= 0.001f) {
                att.sprite.setRenderingCount(0)
                return
            }

            // 用 override 控制所有 instance 的 alpha（shader 会把 20+alpha 解码成 alpha）。
            try {
                att.sprite.setInstanceTimerOverride(alphaMul, BoxEnum.TIMER_FULL)
            } catch (_: Throwable) {
            }

            var write = 0
            when (spec.mode) {
                Mode.PERMANENT -> {
                    val offset = if (spacing > 0.01f) (att.scroll % spacing) else 0f
                    val startDist = if (len > 0.01f) (offset % len) else 0f
                    val approxCount = (((len - startDist) / spacing).toInt().coerceAtLeast(0) + 2).coerceAtLeast(1)
                    val targetCount = approxCount.coerceIn(1, maxCount)

                    var i = 0
                    while (i < targetCount) {
                        val dist = startDist + i * spacing + spec.distanceOffset
                        i++
                        if (dist < 0f) continue
                        if (dist >= len) break

                        val remaining = len - dist
                        val tailFade = (remaining / endFadeDist).coerceIn(0f, 1f)
                        if (tailFade <= 0.001f) continue

                        val t0 = (dist / len).coerceIn(0f, 1f)
                        val t = try {
                            t0.toDouble().pow(spec.scaleExponent.toDouble()).toFloat().coerceIn(0f, 1f)
                        } catch (_: Throwable) {
                            t0
                        }
                        val baseScale = (spec.headScale + (spec.tailScale - spec.headScale) * t).coerceAtLeast(0.01f)

                        val muzzleT = (dist / muzzleExtraDist).coerceIn(0f, 1f)
                        val muzzleExtra = (spec.muzzleExtraScaleMin + (1f - spec.muzzleExtraScaleMin) * muzzleT).coerceAtLeast(0.01f)
                        val s = (baseScale * muzzleExtra).coerceAtLeast(0.01f)

                        val inst = att.instances[write]
                        inst.setLocation(dist, 0f)
                        inst.setFacing(0f)

                        val a = (255f * tailFade * alphaMul).toInt().coerceIn(0, 255)
                        inst.setColor(255, 255, 255, a)
                        inst.setEmissiveColor(255, 255, 255, a)

                        val wHalf = max(1f, spec.bAlongHalf) * s
                        val hHalf = max(1f, spec.aSideHalf) * s
                        inst.setScale(wHalf, hHalf)
                        write++
                        if (write >= maxCount) break
                    }
                }

                Mode.MUZZLE_EMIT -> {
                    val pit = att.particles.iterator()
                    while (pit.hasNext()) {
                        val p = pit.next()
                        if (p.dist < 0f) continue
                        if (p.dist >= len) continue

                        val remaining = len - p.dist
                        val endFade = (remaining / endFadeDist).coerceIn(0f, 1f)
                        if (endFade <= 0.001f) continue

                        val lifeT = if (p.life > 0.01f) (p.age / p.life).coerceIn(0f, 1f) else 1f
                        val inT = (lifeT / 0.15f).coerceIn(0f, 1f)
                        val outT = ((1f - lifeT) / 0.55f).coerceIn(0f, 1f)
                        val lifeFade = (inT * outT).coerceIn(0f, 1f)

                        // 出生淡入：按时间即可（因为不推进）
                        val spawnFade = (p.age / (0.10f + (spawnFadeInDist / 300f))).coerceIn(0f, 1f)

                        val t0 = (p.dist / len).coerceIn(0f, 1f)
                        val t = try {
                            t0.toDouble().pow(spec.scaleExponent.toDouble()).toFloat().coerceIn(0f, 1f)
                        } catch (_: Throwable) {
                            t0
                        }
                        val baseScale = (spec.headScale + (spec.tailScale - spec.headScale) * t).coerceAtLeast(0.01f)

                        val muzzleT = (p.dist / muzzleExtraDist).coerceIn(0f, 1f)
                        val muzzleExtra = (p.muzzleExtraScale + (1f - p.muzzleExtraScale) * muzzleT).coerceAtLeast(0.01f)
                        val s = (baseScale * muzzleExtra).coerceAtLeast(0.01f)

                        val aMul = (spawnFade * lifeFade * endFade).coerceIn(0f, 1f)
                        if (aMul <= 0.001f) continue

                        val inst = att.instances[write]
                        inst.setLocation(p.dist, 0f)
                        inst.setFacing(0f)

                        val a = (255f * aMul * alphaMul).toInt().coerceIn(0, 255)
                        inst.setColor(255, 255, 255, a)
                        inst.setEmissiveColor(255, 255, 255, a)

                        val wHalf = max(1f, spec.bAlongHalf) * s
                        val hHalf = max(1f, spec.aSideHalf) * s
                        inst.setScale(wHalf, hHalf)

                        write++
                        if (write >= maxCount) break
                    }
                }

                Mode.MUZZLE_PULSE -> {
                    val p = att.particles.peekFirst() ?: run {
                        att.sprite.setRenderingCount(0)
                        return
                    }
                    if (p.dist < 0f) return
                    if (p.dist >= len) return

                    val remaining = len - p.dist
                    val endFade = (remaining / endFadeDist).coerceIn(0f, 1f)
                    if (endFade <= 0.001f) return

                    val lifeT = if (p.life > 0.01f) (p.age / p.life).coerceIn(0f, 1f) else 1f

                    // alpha：快速出现，随后淡出
                    val inT = (lifeT / 0.05f).coerceIn(0f, 1f)
                    val outT = (1f - lifeT).coerceIn(0f, 1f)
                    val lifeFade = (inT * outT).coerceIn(0f, 1f)

                    // scale：快速从小变大（指数 < 1）
                    val e = spec.pulseScaleExponent.coerceIn(0.05f, 2.5f)
                    val scaleT = try {
                        lifeT.toDouble().pow(e.toDouble()).toFloat().coerceIn(0f, 1f)
                    } catch (_: Throwable) {
                        lifeT
                    }
                    val ageScale = (spec.pulseStartScale + (spec.pulseEndScale - spec.pulseStartScale) * scaleT).coerceAtLeast(0.01f)

                    val t0 = (p.dist / len).coerceIn(0f, 1f)
                    val t = try {
                        t0.toDouble().pow(spec.scaleExponent.toDouble()).toFloat().coerceIn(0f, 1f)
                    } catch (_: Throwable) {
                        t0
                    }
                    val baseScale = (spec.headScale + (spec.tailScale - spec.headScale) * t).coerceAtLeast(0.01f)

                    val muzzleT = (p.dist / muzzleExtraDist).coerceIn(0f, 1f)
                    val muzzleExtra = (p.muzzleExtraScale + (1f - p.muzzleExtraScale) * muzzleT).coerceAtLeast(0.01f)

                    val s = (baseScale * muzzleExtra * ageScale).coerceAtLeast(0.01f)
                    val aMul = (lifeFade * endFade).coerceIn(0f, 1f)
                    if (aMul <= 0.001f) return

                    val inst = att.instances[0]
                    inst.setLocation(p.dist, 0f)
                    inst.setFacing(0f)

                    val a = (255f * aMul * alphaMul).toInt().coerceIn(0, 255)
                    inst.setColor(255, 255, 255, a)
                    inst.setEmissiveColor(255, 255, 255, a)

                    val wHalf = max(1f, spec.bAlongHalf) * s
                    val hHalf = max(1f, spec.aSideHalf) * s
                    inst.setScale(wHalf, hHalf)

                    write = 1
                }
            }

            att.sprite.setRenderingCount(write)
            att.sprite.setInstanceDataRefreshIndex(0)
            att.sprite.setInstanceDataRefreshSize(write)
            if (write > 0) {
                try {
                    // 参照 BoxUtil 成功用法：每帧提交变更到 TBO，然后由 alwaysRefresh 驱动 compute 更新最终矩阵。
                    submitDynamicInstanceData(att.sprite, write)
                } catch (_: Throwable) {
                }
            }
        }

        override fun render(layer: CombatEngineLayers, viewport: ViewportAPI) {
            // SpriteEntity 自身由 BoxUtil 渲染；这里无需额外绘制。
        }

        override fun getActiveLayers(): EnumSet<CombatEngineLayers> {
            return EnumSet.of(
                CombatEngineLayers.ABOVE_PARTICLES_LOWER,
                CombatEngineLayers.ABOVE_PARTICLES,
            )
        }

        override fun getRenderRadius(): Float = 999999f

        override fun isExpired(): Boolean = expired
    }

    private fun submitDynamicInstanceData(entity: org.boxutil.base.api.InstanceRenderAPI, instanceCount: Int): Boolean {
        if (instanceCount < 1) return false
        return try {
            val memory = entity.instanceDataMemory
            if (memory == null || memory.is_type_fixed()) {
                entity.mallocInstance(InstanceType.DYNAMIC_2D, instanceCount)
                entity.setInstanceDataRefreshIndex(0)
                entity.setInstanceDataRefreshOffset(0)
                entity.setInstanceDataRefreshAllFromCurrentIndex()
            }

            val after = entity.instanceDataMemory
            if (after == null || after.is_type_fixed()) return false

            entity.submitInstance()
            true
        } catch (_: Throwable) {
            false
        }
    }
}

/**
 * 运行时生成“空心圆环”贴图，并通过 SettingsAPI 作为 Sprite 加载。
 * 目标：避免 FlareEntity 的 disc 风格导致的“像圆盘/像矩形贴片”问题。
 */
internal object GeneratedRingSprite {
    private const val REL_PATH = "graphics/fx/astd_generated_ring.png"
    @Volatile
    private var attemptedLoad = false

    @Volatile
    private var cached: SpriteAPI? = null

    @Synchronized
    fun getOrCreateSprite(): SpriteAPI? {
        cached?.let { return it }
        // 某些环境下，直接 getSprite(文件路径) 可能拿不到（尤其是纹理在本次运行中新加入/未被 Settings 扫描）。
        // 先显式 loadTexture 一次，确保进贴图缓存。
        if (!attemptedLoad) {
            attemptedLoad = true
            try {
                Global.getSettings().loadTexture(REL_PATH)
            } catch (_: Throwable) {
                // ignore
            }
        }

        val s = try {
            Global.getSettings().getSprite(REL_PATH)
        } catch (_: Throwable) {
            null
        }
        if (s == null) {
            // 只在首次失败时提示，避免刷屏。
            try {
                Global.getLogger(GeneratedRingSprite::class.java)
                    .warn("Failed to load ring sprite: '$REL_PATH'. Make sure it exists under this mod's graphics/ and was copied to production.")
            } catch (_: Throwable) {
            }
        }
        cached = s
        return s
    }
}
