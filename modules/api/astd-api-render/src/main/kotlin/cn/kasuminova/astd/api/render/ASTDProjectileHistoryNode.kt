package cn.kasuminova.astd.api.render

import org.lwjgl.util.vector.Vector2f

/**
 * 弹体历史采样节点：弹体拖尾/网格节点依赖的轨迹点（位置 + 朝向 + 采样时刻）。
 */
data class ASTDProjectileHistoryNode(val location: Vector2f, val facing: Float, val elapsed: Float)
