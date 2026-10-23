package cn.kasuminova.astd.renderer.effect.projectile.beam

import cn.kasuminova.astd.renderer.boxutil.pool.PooledCombatVfx
import cn.kasuminova.astd.renderer.boxutil.pool.SpriteLease
import cn.kasuminova.astd.renderer.boxutil.pool.SpriteLeaseKey
import cn.kasuminova.astd.renderer.boxutil.pool.SpriteLeaseSpec

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.CombatLayeredRenderingPlugin
import com.fs.starfarer.api.combat.ViewportAPI
import com.fs.starfarer.api.graphics.SpriteAPI
import org.boxutil.define.BoxDatabase
import org.boxutil.define.BoxEnum
import org.boxutil.define.InstanceType
import org.boxutil.units.standard.attribute.Instance2Data
import org.boxutil.units.standard.entity.SpriteEntity
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector3f
import java.awt.Color
import java.util.ArrayDeque
import java.util.EnumSet
import kotlin.math.max
import kotlin.math.pow

internal object AttachedBeamSpriteRingRenderer {

    private const val ENGINE_KEY = "astd_attached_beam_sprite_ring_renderer"

    private const val RING_TEXTURE_KEY = "astd_generated_ring"

    private val log = Global.getLogger(AttachedBeamSpriteRingRenderer::class.java)

    private var warnedRingSpriteFail = false

    enum class Mode {
        PERMANENT,

        MUZZLE_EMIT,

        MUZZLE_PULSE,
    }

    data class Spec(
        val mode: Mode = Mode.PERMANENT,
        val spacing: Float,
        val travelSpeed: Float,
        val aSideHalf: Float,
        val bAlongHalf: Float,
        val distanceOffset: Float = 0f,
        val endFadeDistance: Float = 0f,
        val color: Color,
        val headScale: Float = 1.00f,
        val tailScale: Float = 1.20f,
        val scaleExponent: Float = 1.65f,
        val muzzleExtraScaleMin: Float = 1.50f,
        val muzzleExtraScaleMax: Float = 2.00f,
        val muzzleExtraDistance: Float = 0f,
        val spawnFadeInDistance: Float = 0f,

        val emitRatePerSec: Float = 0f,

        val emitLifetime: Float = 0f,

        val muzzleSpreadDistance: Float = 0f,

        val pulseLifetime: Float = 0f,

        val pulseStartScale: Float = 0.40f,
        val pulseEndScale: Float = 2.40f,

        val pulseScaleExponent: Float = 0.40f,

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
        var scroll: Float,

        var emitAcc: Float,
        var lastUpdated: Float,
        var fadeStartedAt: Float?,
        var fadeMul: Float,
        val lease: SpriteLease,
        val sprite: SpriteEntity,
        val instances: MutableList<Instance2Data>,
        val particles: ArrayDeque<Particle>,
    )

    fun upsert(engine: CombatEngineAPI, key: String, line: BeamLineUtil.BeamLine, spec: Spec) {
        val r = getOrCreate(engine)
        val now = safeTime(engine)

        val existing = r.map[key]
        if (existing == null) {
            val att = createAttachment(engine, line, spec) ?: return
            att.lastUpdated = now
            r.map[key] = att
            try {
                r.updateAttachment(att, amount = 0f, now = now)
            } catch (_: Throwable) {
            }
        } else {
            existing.line = line
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
        att.lease.release(0f)
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

    private fun createAttachment(engine: CombatEngineAPI, line: BeamLineUtil.BeamLine, spec: Spec): Attachment? {
        val ringSprite = try {
            GeneratedRingSprite.getOrCreateSprite()
        } catch (t: Throwable) {
            if (!warnedRingSpriteFail) {
                warnedRingSpriteFail = true
                log.warn("BoxUtil sprite ring 生成环贴图获取异常，兜底使用 BUtil_ONE（去重后仅记一次）", t)
            }
            null
        }
        val diffuse = ringSprite ?: BoxDatabase.BUtil_ONE
        val rgb = Color(spec.color.red, spec.color.green, spec.color.blue, 255)
        val lease = PooledCombatVfx.checkoutSprite(
            engine,
            SpriteLeaseKey(
                layer = spec.layer,
                textureKey = RING_TEXTURE_KEY,
                additive = true,
                instanced = true,
                capacity = 8,
            ),
            SpriteLeaseSpec(
                location = line.from,
                facingDeg = line.facing,
                sprite = diffuse,
                bindEmissive = true,
                baseSizeHalfWidth = 1f,
                baseSizeHalfHeight = 1f,
                color = Color(0, 0, 0, 255),
                emissiveColor = rgb,
                emissiveState = Vector3f(0f, 0f, max(0f, spec.glowPower)),
                isAdditionEmissive = true,
                isIgnoreIllumination = true,
                maxInstances = spec.maxInstances,
            ),
        ) ?: return null
        val instances = lease.instances
        if (instances == null) {
            lease.release(0f)
            log.warn("BoxUtil sprite ring 租约缺失实例表（layer=${spec.layer}，maxInstances=${spec.maxInstances}），本次环视觉缺席")
            return null
        }
        return Attachment(
            line = line,
            spec = spec,
            scroll = 0f,
            emitAcc = 0f,
            lastUpdated = safeTime(engine),
            fadeStartedAt = null,
            fadeMul = 1f,
            lease = lease,
            sprite = lease.entity,
            instances = instances,
            particles = ArrayDeque(),
        )
    }

    private fun applySpecToSprite(sprite: SpriteEntity, spec: Spec) {
        // 层位/混合模式由租约键在检出时固定，检出后只允许重涂材质与贴图，改写会污染池内其他租约
        val s = try {
            GeneratedRingSprite.getOrCreateSprite()
        } catch (_: Throwable) {
            null
        }
        if (s != null) {
            sprite.setDiffuseSprite(s)
            sprite.setEmissiveSprite(s)
        } else {
            sprite.materialData.setDiffuse(BoxDatabase.BUtil_ONE)
            sprite.materialData.setEmissive(BoxDatabase.BUtil_ONE)
        }

        val rgb = Color(spec.color.red, spec.color.green, spec.color.blue, 255)
        // BoxUtil sprite shader 会把 entityColor alpha 当全局 alpha 早退，为 0 会导致 emissive 不可见，必须维持 255
        sprite.materialData.setColor(Color(0, 0, 0, 255))
        sprite.materialData.setEmissiveColor(rgb)
        // alphaMix=1 时 emissive alpha 会被乘上 diffuse/entityColor alpha 后 discard，必须为 0
        sprite.materialData.setEmissiveState(0f, 0f, max(0f, spec.glowPower))
        sprite.materialData.isAdditionEmissive = true
        sprite.materialData.isIgnoreIllumination = true

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
            map.values.forEach { it.lease.release(0f) }
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

                        run {
                            val pit = att.particles.iterator()
                            while (pit.hasNext()) {
                                val p = pit.next()
                                p.age += amount
                                if (p.age >= p.life) pit.remove()
                            }
                        }

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
                        run {
                            val pit = att.particles.iterator()
                            while (pit.hasNext()) {
                                val p = pit.next()
                                p.age += amount
                                if (p.age >= p.life) pit.remove()
                            }
                        }

                        if (att.particles.isEmpty()) {
                            val autoLife = 0.22f
                            val life = (if (att.spec.pulseLifetime > 0.01f) att.spec.pulseLifetime else autoLife).coerceIn(0.10f, 0.85f)

                            val spread = att.spec.muzzleSpreadDistance.coerceAtLeast(0f)
                            val d0 = if (spread > 0.01f) MathUtils.getRandomNumberInRange(0f, spread) else 0f
                            val dist = (d0 + att.spec.distanceOffset).coerceAtLeast(0f)
                            val extra = MathUtils.getRandomNumberInRange(att.spec.muzzleExtraScaleMin, att.spec.muzzleExtraScaleMax)
                            att.particles.addLast(Particle(dist = dist, age = 0f, life = life, muzzleExtraScale = extra))
                        } else {
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
                        att.lease.release(0f)
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
            engine ?: return
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

            // BoxUtil shader 把 instanceTimerOverride 的 20+alpha 解码为实例 alpha，用它统一控制实例透明度
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

                    val inT = (lifeT / 0.05f).coerceIn(0f, 1f)
                    val outT = (1f - lifeT).coerceIn(0f, 1f)
                    val lifeFade = (inT * outT).coerceIn(0f, 1f)

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
                    submitDynamicInstanceData(att.sprite, write)
                } catch (_: Throwable) {
                }
            }
        }

        override fun render(layer: CombatEngineLayers, viewport: ViewportAPI) {
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
            if (memory == null || memory.is_type_fixed) {
                entity.mallocInstance(InstanceType.DYNAMIC_2D, instanceCount)
                entity.instanceDataRefreshIndex = 0
                entity.instanceDataRefreshOffset = 0
                entity.setInstanceDataRefreshAllFromCurrentIndex()
            }

            val after = entity.instanceDataMemory
            if (after == null || after.is_type_fixed) return false

            entity.submitInstance()
            true
        } catch (_: Throwable) {
            false
        }
    }
}

internal object GeneratedRingSprite {
    private const val REL_PATH = "graphics/fx/astd_generated_ring.png"

    @Volatile
    private var cached: SpriteAPI? = null

    @Synchronized
    fun getOrCreateSprite(): SpriteAPI? {
        cached?.let { return it }
        val s = try {
            // 未被 Settings 扫描的贴图直接 getSprite 会静默返回 texId=0 的空精灵，必须先 loadTexture
            Global.getSettings().apply { loadTexture(REL_PATH) }.getSprite(REL_PATH)
        } catch (t: Throwable) {
            Global.getLogger(GeneratedRingSprite::class.java)
                .warn("Failed to load ring sprite: '$REL_PATH'. Make sure it exists under this mod's graphics/ and was copied to production.", t)
            null
        }
        if (s != null && s.textureId <= 0) {
            Global.getLogger(GeneratedRingSprite::class.java)
                .warn("Ring sprite loaded but empty (texId=0): '$REL_PATH'. Make sure it exists under this mod's graphics/ and was copied to production.")
        }
        cached = s
        return s
    }
}
