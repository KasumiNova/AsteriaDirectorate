package cn.kasuminova.astd.campaign.dialog.core

/**
 * nodeId 到节点的映射。
 * 节点可以是单例，但可变状态必须放在 [DialogContext.sessionState] 或 MemoryAPI，禁止放在节点字段。
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
