package cn.kasuminova.astd.campaign.ui

import org.apache.log4j.Logger

/**
 * 分局终端数据源装配点（holder 注入模式，同 api 侧 `BuffBackends`）。
 *
 * campaign 侧在 mod 初始化时 [install] 真实赏金数据源（`StoryBootstrap`）。
 * 本 holder 不设任何默认/兜底实现：[get] 在未注入时记录错误并抛 [IllegalStateException]，
 * 终端拒绝以假数据假成功打开。
 */
object DirectorateTerminalBackends {

    private val log: Logger = Logger.getLogger(DirectorateTerminalBackends::class.java)

    private var installed: DirectorateTerminalDataSource? = null

    /** 是否已注入真实数据源。 */
    val isInstalled: Boolean
        get() = installed != null

    /** 注入 campaign 侧的真实数据源。 */
    fun install(source: DirectorateTerminalDataSource) {
        installed = source
    }

    /**
     * 当前生效的数据源。
     * @throws IllegalStateException 数据源未注入（campaign 侧装配缺失/时序错误）
     */
    fun get(): DirectorateTerminalDataSource = installed ?: run {
        val msg = "DirectorateTerminalBackends: 终端数据源未注入（等待 campaign 侧 install），拒绝打开终端。"
        log.error(msg)
        throw IllegalStateException(msg)
    }
}
