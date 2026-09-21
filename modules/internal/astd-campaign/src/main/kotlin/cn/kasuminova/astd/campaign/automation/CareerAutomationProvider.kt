package cn.kasuminova.astd.campaign.automation

import com.fs.starfarer.api.EveryFrameScript
import com.fs.starfarer.api.Global
import org.apache.log4j.Logger
import java.util.ServiceLoader

/**
 * 生涯集成自动化脚本的提供方接口（astd-automation 模块实现，release 打包时整模块剔除）。
 *
 * 接线方式：astd-automation 通过 `META-INF/services` 注册实现，本包 [CareerAutomationInstall]
 * 在 `ModPlugin.onGameLoad` 经 [ServiceLoader] 发现并注册脚本；release 构建
 * （-Pastd.includeAutomation=false）中实现类与服务声明均不存在，发现结果为空，不产生任何行为。
 */
interface CareerAutomationProvider {

    /**
     * 创建生涯自动化检查脚本实例。
     *
     * 脚本为 transient（不写入存档），每次读档由 [CareerAutomationInstall] 重新注册。
     *
     * @return EveryFrameScript 实例；实现方内部再做系统属性门控之外的初始化
     */
    fun createScript(): EveryFrameScript
}

/**
 * 生涯自动化的装配入口（[cn.kasuminova.astd.AsteriaDirectoratePlugin.onGameLoad] 调用点）。
 *
 * 双重门控：
 * 1. 系统属性 `astd.careerAutomation.enabled=true`（实机自动化驱动脚本显式开启，正常游玩不装配）；
 * 2. [ServiceLoader] 必须发现至少一个 [CareerAutomationProvider] 实现（仅 automation 模块在包内时存在）。
 */
object CareerAutomationInstall {

    /** 启用生涯自动化的系统属性名。 */
    const val ENABLED_PROPERTY: String = "astd.careerAutomation.enabled"

    private val log: Logger = Global.getLogger(CareerAutomationInstall::class.java)

    @JvmStatic
    fun onGameLoad() {
        if (!java.lang.Boolean.getBoolean(ENABLED_PROPERTY)) return
        val sector = Global.getSector()
        if (sector == null) {
            log.error("[ASTD-Career] 生涯自动化装配失败：sector 不可用")
            return
        }

        val providers = try {
            ServiceLoader.load(
                CareerAutomationProvider::class.java,
                CareerAutomationProvider::class.java.classLoader,
            ).toList()
        } catch (t: Throwable) {
            log.error("[ASTD-Career] 生涯自动化 Provider 发现失败", t)
            return
        }
        if (providers.isEmpty()) {
            log.error("[ASTD-Career] 生涯自动化已启用但未发现 CareerAutomationProvider 实现（automation 模块不在包内）")
            return
        }

        for (provider in providers) {
            try {
                sector.addTransientScript(provider.createScript())
                log.info("[ASTD-Career] 生涯自动化脚本已注册：${provider.javaClass.name}")
            } catch (t: Throwable) {
                log.error("[ASTD-Career] 生涯自动化脚本注册失败：${provider.javaClass.name}", t)
            }
        }
    }
}
