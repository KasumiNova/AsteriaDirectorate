package cn.kasuminova.astd.combat.shipsystems

import org.lwjgl.util.vector.Vector2f

/**
 * 引力空间复制器复制弹的收敛弹道几何（纯函数，单元测试直接驱动）。
 *
 * 几何模型（定稿口径）：复制弹永远收敛到主射弹射程终点
 * （terminal = 主射弹出生点 + 弹道单位方向 × 射程）；每个复制弹从自己的随机出生点
 * 指向同一终点，出现点不同则射向天然不同——任何路径都不退化为与主射弹平行。
 */
object ReplicaConvergenceMath {

    /**
     * 射程终点：出生点 + 弹道方向 × 射程（[direction] 内部归一，调用侧保证非零向量）。
     */
    fun terminalPoint(spawn: Vector2f, direction: Vector2f, range: Float): Vector2f {
        val dir = Vector2f(direction)
        dir.normalise(dir)
        return Vector2f(spawn.x + dir.x * range, spawn.y + dir.y * range)
    }

    /**
     * 复制弹收敛速度：方向 = ([terminal] - [copySpawn]) 归一，模长 = [speed]；
     * 出生点与终点重合（方向退化）返回 null，替代射向由调用侧决定。
     */
    fun convergingVelocity(terminal: Vector2f, copySpawn: Vector2f, speed: Float): Vector2f? {
        val dir = Vector2f.sub(terminal, copySpawn, null)
        if (dir.lengthSquared() <= 0f) return null
        dir.normalise(dir)
        return Vector2f(dir.x * speed, dir.y * speed)
    }
}
