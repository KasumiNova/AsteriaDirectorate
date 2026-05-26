package cn.kasuminova.astd.sscsv.entries

import cn.kasuminova.astd.sscsv.GeneratedFile
import cn.kasuminova.astd.sscsv.SsExtraOutputs

/**
 * 在生成 `ship_systems.csv` 的同时，额外生成 `data/shipsystems/<id>.system` 文件的标准条目基类。
 *
 * 适用场景：Starsector 的“舰船系统”数据通常是 CSV + `.system` 文件混用。
 * 继承该类即可做到：
 * - 自动输出 `ship_systems.csv` 的一行（继承自 [ShipSystemEntry]）
 * - 自动输出对应的 `.system` JSON 文件（实现 [SsExtraOutputs]）
 */
abstract class ShipSystemWithSystemFileEntry : ShipSystemEntry(), SsExtraOutputs {

    /** `.system` 的 type 字段（例如 STAT_MOD）。 */
    open val systemType: String = "STAT_MOD"

    /** `.system` 的 aiType 字段。 */
    open val aiType: String = "WEAPON_BOOST"

    /** `.system` 的 aiScript 字段：自定义系统 AI 脚本类（完整限定名；为空则不写入该字段）。 */
    open val aiScript: String? = null

    /** `.system` 的 statsScript 字段：系统效果脚本类（完整限定名）。 */
    open val statsScript: String = "cn.kasuminova.astd.combat.shipsystems.PlaceholderShipSystemStats"

    /** `.system` 的 useSound 字段：激活音效 id；为 null 时不写入。 */
    open val useSound: String? = "system_ammo_feeder"

    /** `.system` 的 loopSound 字段：持续音效 id；为 null 时不写入。 */
    open val loopSound: String? = null

    /** `.system` 的 deactivateSound 字段：关闭音效 id；为 null 时不写入。 */
    open val deactivateSound: String? = null

    /** `.system` 的 outOfUsesSound 字段：不可用音效 id；为 null 时不写入。 */
    open val outOfUsesSound: String? = null

    override fun extraFiles(): List<GeneratedFile> {
        val fields = linkedMapOf<String, String>(
            "id" to id,
            "type" to systemType,
            "aiType" to aiType,
        )

        aiScript?.trim()?.takeIf { it.isNotEmpty() }?.let {
            fields["aiScript"] = it
        }

        fields["statsScript"] = statsScript
        useSound?.let { fields["useSound"] = it }
        loopSound?.let { fields["loopSound"] = it }
        deactivateSound?.let { fields["deactivateSound"] = it }
        outOfUsesSound?.let { fields["outOfUsesSound"] = it }

        val body = fields.entries.joinToString(",\n") { (k, v) ->
            "    \"$k\": \"$v\""
        }
        val json = "{\n$body\n}\n"

        return listOf(
            GeneratedFile(
                relativePath = "data/shipsystems/$id.system",
                content = json,
            )
        )
    }
}
