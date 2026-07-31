package cn.kasuminova.astd.campaign

internal object AsteriaTestCampaignBootstrapState {

    const val LEGACY_DONE_KEY = "asteria_test_campaign_bootstrap_done"
    const val CONTENT_DONE_KEY = "asteria_test_campaign_bootstrap_content_done"

    private const val TELEPORT_DONE_KEY = "asteria_test_campaign_bootstrap_teleport_done"
    private const val CONTENT_DONE_VALUE = "v2_storage_verified"

    fun isContentDone(persistentData: Map<String, *>): Boolean {
        return persistentData[CONTENT_DONE_KEY] == CONTENT_DONE_VALUE
    }

    fun isTeleportDone(persistentData: Map<String, *>): Boolean {
        return persistentData.containsKey(TELEPORT_DONE_KEY)
    }

    fun shouldAttemptTeleport(persistentData: Map<String, *>): Boolean {
        return !isTeleportDone(persistentData)
    }

    fun shouldAttemptContentFill(persistentData: Map<String, *>): Boolean {
        return !isContentDone(persistentData)
    }

    fun shouldAcceptExistingContent(persistentData: Map<String, *>, storageHasPayload: Boolean): Boolean {
        return shouldAttemptContentFill(persistentData) && storageHasPayload
    }

    fun shouldFillContent(persistentData: Map<String, *>, storageHasPayload: Boolean): Boolean {
        return !storageHasPayload
    }

    fun markContentDone(persistentData: MutableMap<String, Any?>) {
        persistentData[CONTENT_DONE_KEY] = CONTENT_DONE_VALUE
    }

    fun markTeleportDone(persistentData: MutableMap<String, Any?>) {
        persistentData[TELEPORT_DONE_KEY] = true
    }
}
