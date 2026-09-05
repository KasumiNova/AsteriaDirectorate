package cn.kasuminova.astd.campaign.world

/**
 * 剧情世界生成的全部稳定 ID 常量。
 *
 * 所有剧情星系、星体、空间站、稳定点、市场均使用本文件中的固定 ID 生成，
 * 保证读档恢复与重入时的 canonical 去重（同 ID 已存在即跳过）。
 */
object StoryWorldIds {

    /** 模组统一 ID 前缀。 */
    const val ID_PREFIX = "astd_story"

    // ------------------------------------------------------------------
    // 星系 ID
    // ------------------------------------------------------------------

    /** 剧情主星系（菀星第七分局驻在星系），序章开局生成。 */
    const val SYSTEM_MAIN = "astd_story_main"

    /** 星坠遗址星系（第二章解锁后生成）。 */
    const val SYSTEM_STARFALL = "astd_story_starfall"

    /** 紫菀遗址星系（第二章解锁后生成）。 */
    const val SYSTEM_ASTER = "astd_story_aster"

    // ------------------------------------------------------------------
    // 主星系实体 ID
    // ------------------------------------------------------------------

    const val MAIN_STAR = "astd_main_star"
    const val MAIN_PLANET_LANTAI = "astd_main_planet_lantai"
    const val MAIN_PLANET_HONGLU = "astd_main_planet_honglu"
    const val MAIN_PLANET_CUICHI = "astd_main_planet_cuichi"

    const val MAIN_STATION_BRANCH = "astd_main_station_branch"
    const val MAIN_STATION_RESERVED = "astd_main_station_reserved"
    const val MAIN_STATION_DOCKYARD = "astd_main_station_dockyard"

    const val MAIN_OBJ_COMM_RELAY = "astd_main_obj_comm_relay"
    const val MAIN_OBJ_SENSOR_ARRAY = "astd_main_obj_sensor_array"
    const val MAIN_OBJ_NAV_BUOY = "astd_main_obj_nav_buoy"
    const val MAIN_OBJ_GATE = "astd_main_obj_gate"

    const val MAIN_BELT_1 = "astd_main_belt_1"

    // ------------------------------------------------------------------
    // 星坠遗址星系实体 ID
    // ------------------------------------------------------------------

    const val STARFALL_STAR = "astd_starfall_star"
    const val STARFALL_PLANET_DUANYUAN = "astd_starfall_planet_duanyuan"

    const val STARFALL_STATION_MAIN = "astd_starfall_station_main"
    const val STARFALL_STATION_DOCKYARD = "astd_starfall_station_dockyard"
    const val STARFALL_STATION_RESERVED = "astd_starfall_station_reserved"

    const val STARFALL_OBJ_COMM_RELAY = "astd_starfall_obj_comm_relay"
    const val STARFALL_OBJ_SENSOR_ARRAY = "astd_starfall_obj_sensor_array"
    const val STARFALL_OBJ_NAV_BUOY = "astd_starfall_obj_nav_buoy"
    const val STARFALL_OBJ_GATE = "astd_starfall_obj_gate"

    const val STARFALL_BELT_1 = "astd_starfall_belt_1"

    // ------------------------------------------------------------------
    // 紫菀遗址星系实体 ID
    // ------------------------------------------------------------------

    const val ASTER_STAR = "astd_aster_star"

    const val ASTER_STATION_MAIN = "astd_aster_station_main"
    const val ASTER_STATION_GRAVITY_DOCKYARD = "astd_aster_station_gravity_dockyard"
    const val ASTER_STATION_SINGULARITY = "astd_aster_station_singularity"
    const val ASTER_STATION_FORCEFIELD_RESERVED = "astd_aster_station_forcefield_reserved"
    const val ASTER_STATION_SHIGUANG = "astd_aster_station_shiguang"

    const val ASTER_GRAVITY_NODE_1 = "astd_aster_gravity_node_1"
    const val ASTER_GRAVITY_NODE_2 = "astd_aster_gravity_node_2"
    const val ASTER_GRAVITY_NODE_3 = "astd_aster_gravity_node_3"

    const val ASTER_OBJ_COMM_RELAY = "astd_aster_obj_comm_relay"
    const val ASTER_OBJ_SENSOR_ARRAY = "astd_aster_obj_sensor_array"
    const val ASTER_OBJ_NAV_BUOY = "astd_aster_obj_nav_buoy"
    const val ASTER_OBJ_GATE = "astd_aster_obj_gate"

    const val ASTER_BELT_1 = "astd_aster_belt_1"
    const val ASTER_BELT_2 = "astd_aster_belt_2"

    // ------------------------------------------------------------------
    // 实体标签与 Memory 标记
    // ------------------------------------------------------------------

    /** 所有剧情世界实体的统一标签。 */
    const val TAG_STORY_ENTITY = "astd_story_entity"

    /** 紫菀引力节点实体标签（赏金层用于识别节点阵地）。 */
    const val TAG_GRAVITY_NODE = "astd_gravity_node"

    /** 实体 memory 键：剧情角色标记（branch_office / dockyard / gravity_node 等）。 */
    const val MEM_STORY_ROLE = "\$astd_story_role"

    // ------------------------------------------------------------------
    // 剧情角色标记值
    // ------------------------------------------------------------------

    const val ROLE_BRANCH_OFFICE = "branch_office"
    const val ROLE_RESERVED = "reserved"
    const val ROLE_DOCKYARD = "dockyard"
    const val ROLE_RUIN_MAIN_STATION = "ruin_main_station"
    const val ROLE_RUIN_DOCKYARD = "ruin_dockyard"
    const val ROLE_RUIN_RESERVED = "ruin_reserved"
    const val ROLE_SINGULARITY_DRIVE = "singularity_drive"
    const val ROLE_FORCEFIELD_RESERVED = "forcefield_reserved"
    const val ROLE_HABITAT = "habitat"
    const val ROLE_GRAVITY_NODE = "gravity_node"

    // ------------------------------------------------------------------
    // 本模组自定义星球状况（condition）ID
    //
    // 这些状况由独立的 condition 内容任务实装；生成器仅在状况规格已注册时附加，
    // 未注册时记录警告日志（不静默跳过）。
    // ------------------------------------------------------------------

    /** 菀星行政部遗址（兰台）。 */
    const val COND_ADMIN_RUINS = "astd_cond_admin_ruins"

    /** 星坠工程部遗址（锻原）。 */
    const val COND_STARFALL_ENG_RUINS = "astd_cond_starfall_eng_ruins"

    /** 视界动力（拾光）。 */
    const val COND_EVENT_HORIZON_POWER = "astd_cond_event_horizon_power"

    /** 紫菀科研部遗址（拾光）。 */
    const val COND_ASTER_RESEARCH_RUINS = "astd_cond_aster_research_ruins"

    // ------------------------------------------------------------------
    // IndEvo 联动
    // ------------------------------------------------------------------

    /** IndEvo mod id（mod_info.json）。 */
    const val INDEVO_MOD_ID = "IndEvo"

    /** IndEvo 磁轨炮类型（ArtilleryStationScript.TYPE_KEY 的取值）。 */
    const val INDEVO_ARTILLERY_TYPE_RAILGUN = "railgun"

    /** IndEvo 联动观锚站锚点（稳定点）实体 ID。 */
    const val MAIN_INDEVO_STABLE_1 = "astd_main_indevo_stable_1"
    const val MAIN_INDEVO_STABLE_2 = "astd_main_indevo_stable_2"
    const val MAIN_INDEVO_STABLE_3 = "astd_main_indevo_stable_3"
    const val MAIN_INDEVO_STABLE_4 = "astd_main_indevo_stable_4"

    const val STARFALL_INDEVO_STABLE_1 = "astd_starfall_indevo_stable_1"
    const val STARFALL_INDEVO_STABLE_2 = "astd_starfall_indevo_stable_2"
    const val STARFALL_INDEVO_STABLE_3 = "astd_starfall_indevo_stable_3"
    const val STARFALL_INDEVO_STABLE_4 = "astd_starfall_indevo_stable_4"

    // ------------------------------------------------------------------
    // 持久化
    // ------------------------------------------------------------------

    /** sector persistentData 中保存 [StoryWorldGenState] 的键。 */
    const val PERSISTENT_STATE_KEY = "\$astd_story_world_gen_state"

    /** 市场 ID 规范：astd_market_<实体ID>。 */
    fun marketIdFor(entityId: String): String = "astd_market_$entityId"

    /** 稳定点锚点 ID 规范：<目标ID>_anchor（观瞄站/目标实体挂靠的稳定点）。 */
    fun stableAnchorIdFor(objectiveId: String): String = "${objectiveId}_anchor"
}
