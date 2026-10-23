package cn.kasuminova.astd.renderer.shader.base

enum class ShaderUniformType {
    Float,
    Vec2,
    Vec3,
    Vec4,
    Int,
    Boolean,
}

sealed interface ShaderUniformValue {
    val type: ShaderUniformType

    data class FloatValue(val value: Float) : ShaderUniformValue {
        override val type: ShaderUniformType = ShaderUniformType.Float
    }

    data class Vec2(val x: Float, val y: Float) : ShaderUniformValue {
        override val type: ShaderUniformType = ShaderUniformType.Vec2
    }

    data class Vec3(val x: Float, val y: Float, val z: Float) : ShaderUniformValue {
        override val type: ShaderUniformType = ShaderUniformType.Vec3
    }

    data class Vec4(val x: Float, val y: Float, val z: Float, val w: Float) : ShaderUniformValue {
        override val type: ShaderUniformType = ShaderUniformType.Vec4
    }

    data class IntValue(val value: Int) : ShaderUniformValue {
        override val type: ShaderUniformType = ShaderUniformType.Int
    }

    data class BooleanValue(val value: Boolean) : ShaderUniformValue {
        override val type: ShaderUniformType = ShaderUniformType.Boolean
    }
}

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

class ShaderUniformSet(
    schema: ShaderUniformSchema,
    suppliedValues: Map<String, ShaderUniformValue>,
) {
    val values: Map<String, ShaderUniformValue> = schema.resolve(suppliedValues)

    operator fun get(key: String): ShaderUniformValue? = values[key]
}
