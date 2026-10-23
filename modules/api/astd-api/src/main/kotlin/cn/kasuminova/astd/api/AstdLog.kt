package cn.kasuminova.astd.api

import org.apache.log4j.Logger

/** 模组共享的 log4j 日志器，可在 ModPlugin 构造前使用。 */
object AstdLog {
    val logger: Logger = Logger.getLogger("cn.kasuminova.astd")
}
