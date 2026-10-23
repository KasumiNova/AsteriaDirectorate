package cn.kasuminova.astd.combat.shipsystems

import org.lwjgl.util.vector.Vector2f

object ReplicaConvergenceMath {

    fun terminalPoint(spawn: Vector2f, direction: Vector2f, range: Float): Vector2f {
        val dir = Vector2f(direction)
        dir.normalise(dir)
        return Vector2f(spawn.x + dir.x * range, spawn.y + dir.y * range)
    }

    fun convergingVelocity(terminal: Vector2f, copySpawn: Vector2f, speed: Float): Vector2f? {
        val dir = Vector2f.sub(terminal, copySpawn, null)
        if (dir.lengthSquared() <= 0f) return null
        dir.normalise(dir)
        return Vector2f(dir.x * speed, dir.y * speed)
    }
}
