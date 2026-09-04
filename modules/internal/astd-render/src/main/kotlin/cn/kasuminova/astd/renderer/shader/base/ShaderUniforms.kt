package cn.kasuminova.astd.renderer.shader.base

/**
 * Uniform value kinds accepted by the public shader VFX contract.
 *
 * Each entry has a matching [ShaderUniformValue] subtype. Runtime upload code
 * should switch on this type instead of inspecting raw Kotlin objects.
 */
enum class ShaderUniformType {
    Float,
    Vec2,
    Vec3,
    Vec4,
    Int,
    Boolean,
}

/**
 * Typed uniform value supplied to a shader effect instance.
 *
 * The sealed shape keeps contract validation explicit and avoids dynamic
 * runtime type probing.
 */
sealed interface ShaderUniformValue {
    val type: ShaderUniformType

    /** Single floating-point uniform value. */
    data class FloatValue(val value: Float) : ShaderUniformValue {
        override val type: ShaderUniformType = ShaderUniformType.Float
    }

    /** Two-component floating-point vector uniform value. */
    data class Vec2(val x: Float, val y: Float) : ShaderUniformValue {
        override val type: ShaderUniformType = ShaderUniformType.Vec2
    }

    /** Three-component floating-point vector uniform value. */
    data class Vec3(val x: Float, val y: Float, val z: Float) : ShaderUniformValue {
        override val type: ShaderUniformType = ShaderUniformType.Vec3
    }

    /** Four-component floating-point vector uniform value. */
    data class Vec4(val x: Float, val y: Float, val z: Float, val w: Float) : ShaderUniformValue {
        override val type: ShaderUniformType = ShaderUniformType.Vec4
    }

    /** Integer uniform value. */
    data class IntValue(val value: Int) : ShaderUniformValue {
        override val type: ShaderUniformType = ShaderUniformType.Int
    }

    /** Boolean uniform value. */
    data class BooleanValue(val value: Boolean) : ShaderUniformValue {
        override val type: ShaderUniformType = ShaderUniformType.Boolean
    }
}

/**
 * Declaration for one shader uniform.
 *
 * [key] is the exact shader-facing uniform key. [type] declares the accepted
 * value kind. [required] marks whether callers must provide a value, and
 * [defaultValue] is permitted only for optional uniforms.
 */
data class ShaderUniformDefinition(
    val key: String,
    val type: ShaderUniformType,
    val required: Boolean = true,
    val defaultValue: ShaderUniformValue? = null,
) {
    init {
        require(key.isNotBlank()) { "Shader uniform key must not be blank" }
        require(defaultValue == null || !required) { "Required shader uniform must not declare a default: $key" }
        require(defaultValue == null || defaultValue.type == type) {
            "Shader uniform default for $key expects $type but received ${defaultValue?.type}"
        }
    }
}

/**
 * Uniform schema for a shader effect.
 *
 * The schema owns key validation and duplicate detection. It is immutable after
 * construction so it can be shared by effect specs and runtime instances.
 */
class ShaderUniformSchema(definitions: List<ShaderUniformDefinition>) {
    val definitions: List<ShaderUniformDefinition> = definitions.toList()

    private val definitionsByKey: Map<String, ShaderUniformDefinition>

    init {
        val byKey = LinkedHashMap<String, ShaderUniformDefinition>()
        for (definition in definitions) {
            val previous = byKey.putIfAbsent(definition.key, definition)
            require(previous == null) { "Shader uniform schema contains duplicate key: ${definition.key}" }
        }
        definitionsByKey = byKey.toMap()
    }

    /** Returns the definition for [key], or null when the schema does not declare it. */
    operator fun get(key: String): ShaderUniformDefinition? = definitionsByKey[key]

    internal fun resolve(suppliedValues: Map<String, ShaderUniformValue>): Map<String, ShaderUniformValue> {
        for (key in suppliedValues.keys) {
            require(key.isNotBlank()) { "Shader uniform key must not be blank" }
            require(definitionsByKey.containsKey(key)) { "Unknown shader uniform key: $key" }
        }

        val resolved = LinkedHashMap<String, ShaderUniformValue>()
        for (definition in definitions) {
            val supplied = suppliedValues[definition.key]
            when {
                supplied != null -> {
                    require(supplied.type == definition.type) {
                        "Shader uniform ${definition.key} expects ${definition.type} but received ${supplied.type}"
                    }
                    resolved[definition.key] = supplied
                }
                definition.required -> throw IllegalArgumentException("Missing required shader uniform: ${definition.key}")
                definition.defaultValue != null -> resolved[definition.key] = definition.defaultValue
            }
        }
        return resolved.toMap()
    }
}

/**
 * Validated uniform values for a shader effect instance.
 *
 * Construction checks the supplied values against [schema], rejects unknown
 * keys, applies optional defaults, and leaves optional uniforms without
 * defaults absent from [values].
 */
class ShaderUniformSet(
    schema: ShaderUniformSchema,
    suppliedValues: Map<String, ShaderUniformValue>,
) {
    val values: Map<String, ShaderUniformValue> = schema.resolve(suppliedValues)

    /** Returns the resolved uniform value for [key], including applied defaults. */
    operator fun get(key: String): ShaderUniformValue? = values[key]
}
