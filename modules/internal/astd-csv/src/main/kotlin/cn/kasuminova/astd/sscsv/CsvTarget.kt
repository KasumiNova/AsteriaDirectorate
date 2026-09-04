package cn.kasuminova.astd.sscsv

/**
 * A minimal mapping from Starsector CSV targets to:
 * - output path under a mod root (usually contents/)
 * - header schema file under tools/_schema_headers
 */
enum class CsvTarget(
    /** Relative path under mod root dir (e.g. contents/) */
    val outputPath: String,
    /** Header schema file name under tools/_schema_headers */
    val headerSchemaFile: String,
) {
    WEAPON_DATA("data/weapons/weapon_data.csv", "weapon_data.header.csv"),
    DESCRIPTIONS("data/strings/descriptions.csv", "descriptions.header.csv"),
    SHIP_DATA("data/hulls/ship_data.csv", "ship_data.header.csv"),
    SHIP_SYSTEMS("data/shipsystems/ship_systems.csv", "ship_systems.header.csv"),
    HULL_MODS("data/hullmods/hull_mods.csv", "hull_mods.header.csv"),
    SKILL_DATA(
        "data/characters/skills/skill_data.csv",
        "skill_data.header.csv",
    ),
    SPECIAL_ITEMS(
        "data/campaign/special_items.csv",
        "special_items.header.csv",
    ),
}

