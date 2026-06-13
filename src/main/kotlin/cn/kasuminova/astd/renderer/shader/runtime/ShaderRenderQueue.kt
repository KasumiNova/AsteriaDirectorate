package cn.kasuminova.astd.renderer.shader.runtime

import cn.kasuminova.astd.renderer.shader.base.ShaderEffectLayer
import cn.kasuminova.astd.renderer.shader.base.ShaderHandle

/**
 * Runtime-owned collection of live shader submissions.
 *
 * The queue separates keyed persistent effects from one-shot effects and owns
 * lifetime/stale cleanup. Renderers consume immutable per-layer snapshots.
 */
class ShaderRenderQueue {
    private val oneShots = ArrayList<ShaderSubmission>()
    private val keyed = LinkedHashMap<ShaderHandle, ShaderSubmission>()
    private var elapsedSeconds = 0f

    fun emit(submission: ShaderSubmission) {
        require(submission.handle == null) { "One-shot shader submission must not carry a handle: effectId=${submission.spec.id.value}" }
        oneShots += submission.asOneShotSubmittedAt(elapsedSeconds)
    }

    fun upsert(instanceId: String, submission: ShaderSubmission): ShaderHandle {
        val handle = submission.handle
            ?: throw IllegalArgumentException("Keyed shader submission requires a handle: effectId=${submission.spec.id.value}")
        require(handle.instanceId == instanceId) {
            "Shader submission handle mismatch: expected instanceId=$instanceId but received ${handle.instanceId}"
        }
        val previous = keyed[handle]
        val startedAt = previous?.startedAt ?: elapsedSeconds
        keyed[handle] = submission.copy(submittedAt = elapsedSeconds, startedAt = startedAt)
        return handle
    }

    fun remove(handle: ShaderHandle) {
        keyed.remove(handle)
    }

    fun clear() {
        oneShots.clear()
        keyed.clear()
        elapsedSeconds = 0f
    }

    fun advance(amount: Float) {
        require(amount.isFinite()) { "Shader queue advance amount must be finite: amount=$amount" }
        elapsedSeconds += amount.coerceAtLeast(0f)
        oneShots.removeAll { ShaderLifecycle.isOneShotExpired(it.spec, elapsedSeconds - it.startedAt) }
        val iterator = keyed.iterator()
        while (iterator.hasNext()) {
            val submission = iterator.next().value
            if (ShaderLifecycle.isKeyedStale(submission.spec, elapsedSeconds - submission.submittedAt)) {
                iterator.remove()
            }
        }
    }

    fun snapshotsForLayer(layer: ShaderEffectLayer): List<ShaderSubmission> {
        return (oneShots.asSequence() + keyed.values.asSequence())
            .filter { it.spec.layer == layer }
            .sortedWith(SUBMISSION_ORDER)
            .toList()
    }

    companion object {
        private val SUBMISSION_ORDER = compareBy<ShaderSubmission> { it.renderOrder }
            .thenBy { it.spec.program.id }
            .thenBy { it.spec.material.blendMode.name }
            .thenBy { it.spec.geometry::class.qualifiedName ?: it.spec.geometry::class.simpleName.orEmpty() }
            .thenBy { it.spec.id.value }
    }
}
