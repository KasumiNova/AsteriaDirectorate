package cn.kasuminova.astd.campaign

import org.apache.log4j.Logger

/**
 * Dev-only campaign acceptance launcher.
 *
 * Campaign acceptance smoke tests use a small javaagent to trigger the real campaign
 * load from the title screen. Keeping this class script-safe prevents accidental
 * reintroduction of save loading during ResourceLoaderState.init, which is too early
 * for BoxUtil campaign rendering state.
 */
object AsteriaDevStorageAcceptanceAutoload {

    private const val ENABLED_PROPERTY = "astd.devStorageAcceptance"
    private val log: Logger = Logger.getLogger(AsteriaDevStorageAcceptanceAutoload::class.java)

    @JvmStatic
    fun installIfRequested() {
        if (!java.lang.Boolean.getBoolean(ENABLED_PROPERTY)) return

        log.info(
            "[AsteriaDevStorageAcceptanceAutoload] Dev storage acceptance requested; " +
                "waiting for title-screen javaagent hook."
        )
    }
}
