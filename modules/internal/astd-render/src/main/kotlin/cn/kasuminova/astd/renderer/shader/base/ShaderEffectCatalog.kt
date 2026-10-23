package cn.kasuminova.astd.renderer.shader.base

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

    fun spec(id: ShaderEffectKey): ShaderEffectSpec? = specsById[id]

    fun spec(id: String): ShaderEffectSpec? = spec(ShaderEffectKey(id))

    fun ids(): Set<ShaderEffectKey> = specsById.keys
}
