package cn.kasuminova.astd.renderer.projectile.component

class ASTDProjectileVfxComponentContext(
    components: List<ASTDProjectileVfxComponentSpec>,
) {
    private val trails = components
        .filterIsInstance<ASTDProjectileVfxComponentSpec.Trail>()
        .associateBy { it.id }

    private val bodyTrailIds = components
        .filterIsInstance<ASTDProjectileVfxComponentSpec.Body>()
        .filter { it.enabled }
        .map { it.trailId }
        .toSet()

    init {
        val duplicateIds = components.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        require(duplicateIds.isEmpty()) {
            "Projectile VFX preset contains duplicate component ids: ${duplicateIds.joinToString()}"
        }
        components.filter { it.enabled }.forEach { component ->
            when (component) {
                is ASTDProjectileVfxComponentSpec.Body -> trail(component.trailId)
                is ASTDProjectileVfxComponentSpec.Glow -> trail(component.trailId)
                is ASTDProjectileVfxComponentSpec.Head -> trail(component.trailId)
                is ASTDProjectileVfxComponentSpec.Mist -> trail(component.trailId)
                is ASTDProjectileVfxComponentSpec.Ribbon -> trail(component.trailId)
                is ASTDProjectileVfxComponentSpec.SideWisp -> trail(component.trailId)
                is ASTDProjectileVfxComponentSpec.Extra,
                is ASTDProjectileVfxComponentSpec.Trail,
                -> Unit
            }
        }
    }

    fun trail(id: String): ASTDProjectileVfxComponentSpec.Trail =
        requireNotNull(trails[id]) { "Projectile VFX component references missing trailId=$id" }

    fun hasBodyForTrail(id: String): Boolean = id in bodyTrailIds
}
