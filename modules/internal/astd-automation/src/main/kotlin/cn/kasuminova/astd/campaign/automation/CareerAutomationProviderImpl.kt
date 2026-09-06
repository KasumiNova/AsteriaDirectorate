package cn.kasuminova.astd.campaign.automation

import com.fs.starfarer.api.EveryFrameScript

/**
 * 生涯自动化 Provider 实现（ServiceLoader 注册，声明见
 * `META-INF/services/cn.kasuminova.astd.campaign.automation.CareerAutomationProvider`）。
 */
class CareerAutomationProviderImpl : CareerAutomationProvider {
    override fun createScript(): EveryFrameScript = CareerAutomationScript()
}
