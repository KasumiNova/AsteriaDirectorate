package cn.kasuminova.astd.api

import org.apache.log4j.Logger

/**
 * 模组共享日志器：全模块统一 log4j category，加载早期（ModPlugin 尚未构造）即可用。
 * 替代原根包 MainProxy 的 pluginOrNull/requirePlugin/logger（插件反向引用已在模块化拆分中移除）。
 */
object AstdLog {
    val logger: Logger = Logger.getLogger("cn.kasuminova.astd")
}
