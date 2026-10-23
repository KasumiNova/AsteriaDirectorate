package cn.kasuminova.astd.campaign

import org.apache.log4j.Logger

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
