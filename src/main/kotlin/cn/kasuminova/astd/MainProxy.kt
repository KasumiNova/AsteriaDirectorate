package cn.kasuminova.astd

import org.apache.log4j.Logger

internal fun pluginOrNull(): AsteriaDirectoratePlugin? = AsteriaDirectoratePlugin.instance()

internal fun requirePlugin(): AsteriaDirectoratePlugin =
    pluginOrNull() ?: throw IllegalStateException("AsteriaDirectoratePlugin has not been constructed yet")

/**
 * 允许在加载早期（ModPlugin 尚未构造）也能打日志。
 */
internal val logger: Logger
    get() = pluginOrNull()?.logger() ?: Logger.getLogger("cn.kasuminova.astd")
