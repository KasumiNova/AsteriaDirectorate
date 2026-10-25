package cn.kasuminova.astd.api.campaign

/**
 * 提供赏金舰队的当前设置。
 *
 * 属性由实现随设置回调更新，调用方不得缓存。
 */
interface BountySettings {

    /**
     * 补齐赏金舰队时是否持续补充至游戏舰队上限。
     *
     * 关闭时仅按赏金预设舰级补充余晖舰船，使舰队总规模翻倍。
     */
    val fillToFleetCap: Boolean
}
