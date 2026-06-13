package cn.kasuminova.astd.renderer.shader.base

/**
 * Immutable lookup table for shader effect specs.
 *
 * Catalog construction rejects duplicate [ShaderEffectSpec.id] values so
 * runtime lookup remains deterministic and registration errors fail fast.
 */
class ShaderEffectCatalog(specs: Iterable<ShaderEffectSpec>) {
    private val specsById: Map<ShaderEffectKey, ShaderEffectSpec>

    init {
        val byId = LinkedHashMap<ShaderEffectKey, ShaderEffectSpec>()
        for (spec in specs) {
            val previous = byId.putIfAbsent(spec.id, spec)
            require(previous == null) { "Duplicate shader effect id: ${spec.id.value}" }
        }
        specsById = byId.toMap()
    }

    /** Returns the registered spec for [id], or null when the catalog does not contain it. */
    fun spec(id: ShaderEffectKey): ShaderEffectSpec? = specsById[id]

    /** Convenience lookup for callers that hold a raw id string. */
    fun spec(id: String): ShaderEffectSpec? = spec(ShaderEffectKey(id))

    /** The registered effect ids in insertion order. */
    fun ids(): Set<ShaderEffectKey> = specsById.keys
}
