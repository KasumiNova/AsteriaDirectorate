package cn.kasuminova.astd.campaign.dialog.core

/**
 * “对话图”：nodeId -> node。
 *
 * 注意：node 本身可以是 object/singleton，但如果 node 内部持有可变状态，
 * 你需要把状态放到 [DialogContext.sessionState] / MemoryAPI，而不是放在 node 字段里。
 */
data class DialogGraph(
    val startNodeId: String,
    val nodes: Map<String, DialogNode>
) {

    fun requireNode(nodeId: String): DialogNode =
        requireNotNull(nodes[nodeId]) { "DialogGraph: missing node '$nodeId'" }
}

class DialogGraphBuilder(private val startNodeId: String) {

    private val nodes = LinkedHashMap<String, DialogNode>()

    fun node(id: String, node: DialogNode) {
        require(id.isNotBlank()) { "node id is blank" }
        require(!nodes.containsKey(id)) { "duplicate node id: '$id'" }
        nodes[id] = node
    }

    fun build(): DialogGraph = DialogGraph(startNodeId = startNodeId, nodes = nodes.toMap())
}

fun dialogGraph(start: String, build: DialogGraphBuilder.() -> Unit): DialogGraph {
    val b = DialogGraphBuilder(startNodeId = start)
    b.apply(build)
    return b.build()
}
