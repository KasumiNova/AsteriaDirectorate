package cn.kasuminova.astd.campaign.world

/**
 * 剧情世界生成的统一 id 契约（对外定稿，后续阶段直接消费）。
 *
 * 覆盖：三个剧情星系（主星系 / 星坠 / 紫菀）、全部固定实体与市场、
 * 引力节点实体标签、4 个剧情市场状况 id。
 *
 * 规格真相来源：docs/story/03（主星系）、docs/story/07（两遗址星系）。
 */
object StoryWorldIds {

    // ─── 星系 ───

    /** 剧情主星系（生涯开局生成；菀星设计总局英仙座第七分局驻地）。 */
    const val SYSTEM_MAIN: String = "astd_story_main"

    /** 星坠遗址星系（第二章，第一章结清钩子触发生成）。 */
    const val SYSTEM_STARFALL: String = "astd_story_starfall"

    /** 紫菀遗址星系（第二章，第一章结清钩子触发生成）。 */
    const val SYSTEM_ASTER: String = "astd_story_aster"

    // ─── 主星系实体（03 文档「剧情主星系规格」） ───

    /** 主星：蓝巨星。 */
    const val MAIN_STAR: String = "astd_story_main_star"

    /** 兰台：terran 宜居链行星（菀星行政部遗址 condition-only 市场）。 */
    const val MAIN_PLANET_LANTAI: String = "astd_story_main_lantai"

    /** 洪炉：固定荒芜行星（丰饶矿物链，无大气层/极度炎热）。 */
    const val MAIN_PLANET_HONGLU: String = "astd_story_main_honglu"

    /** 淬池：固定荒芜行星（丰饶矿物链，无大气层/极度炎热）。 */
    const val MAIN_PLANET_CUICHI: String = "astd_story_main_cuichi"

    /** 分局空间站：剧情主基地，FULL 市场 size 4。 */
    const val MAIN_STATION_BRANCH: String = "astd_story_main_station"

    /** 预留轨道站（占位，后续内容扩展）。 */
    // TODO（后续内容扩展）：预留站功能
    const val MAIN_STATION_RESERVE_A: String = "astd_story_main_reserve_a"

    /** 预留轨道船坞（占位，本体阶段仅提供描述文本）。 */
    // TODO（后续内容扩展）：轨道船坞功能（03 文档：可能以轨道船坞形式提供新功能）
    const val MAIN_STATION_RESERVE_B: String = "astd_story_main_reserve_b"

    const val MAIN_COMM_RELAY: String = "astd_story_main_comm_relay"
    const val MAIN_SENSOR_ARRAY: String = "astd_story_main_sensor_array"
    const val MAIN_NAV_BUOY: String = "astd_story_main_nav_buoy"

    /** 休眠星门：保留实体，功能留待后续内容。 */
    // TODO（后续内容）：星门激活/剧情联动
    const val MAIN_GATE: String = "astd_story_main_gate"

    // ─── 星坠遗址星系实体（07 文档） ───

    /** 主星：蓝超巨星。 */
    const val STARFALL_STAR: String = "astd_story_starfall_star"

    /** 锻原：jungle 行星（星坠工程部遗址，derelict 阵营 condition-only 市场）。 */
    const val STARFALL_PLANET_DUANYUAN: String = "astd_story_starfall_duanyuan"

    /** 星坠设计局主空间站（derelict 遗址站）。 */
    const val STARFALL_STATION_MAIN: String = "astd_story_starfall_station"

    /** 星坠动力船坞（derelict 遗址站）。 */
    const val STARFALL_STATION_DOCKYARD: String = "astd_story_starfall_dockyard"

    /** 预留单位（占位，后续内容扩展）。 */
    // TODO（后续内容扩展）：星坠预留站功能
    const val STARFALL_STATION_RESERVE: String = "astd_story_starfall_reserve"

    const val STARFALL_COMM_RELAY: String = "astd_story_starfall_comm_relay"
    const val STARFALL_SENSOR_ARRAY: String = "astd_story_starfall_sensor_array"
    const val STARFALL_NAV_BUOY: String = "astd_story_starfall_nav_buoy"
    const val STARFALL_GATE: String = "astd_story_starfall_gate"

    // ─── 紫菀遗址星系实体（07 文档） ───

    /** 主星：黑洞（带事件视界地形）。 */
    const val ASTER_STAR: String = "astd_story_aster_star"

    /** 紫菀设计局主空间站（derelict 遗址站）。 */
    const val ASTER_STATION_MAIN: String = "astd_story_aster_station"

    /** 紫菀引力船坞（derelict 遗址站）。 */
    const val ASTER_STATION_DOCKYARD: String = "astd_story_aster_dockyard"

    /** 奇点跃迁器（占位，后续内容扩展）。 */
    // TODO（后续内容扩展）：奇点跃迁器功能
    const val ASTER_STATION_SINGULARITY: String = "astd_story_aster_singularity"

    /** 预留位（占位，力场防御系统使用）。 */
    // TODO（后续内容）：力场防御系统（07 文档预留位）
    const val ASTER_STATION_DEFENSE: String = "astd_story_aster_defense"

    /** 轨道生活空间站「拾光」：FULL 市场 size 4 + 视界动力/紫菀科研部遗址两状况。 */
    const val ASTER_STATION_SHIGUANG: String = "astd_story_aster_shiguang"

    /** 核心数据舱：未拔除全部引力节点前交互被拒（力场排斥）。 */
    const val ASTER_CORE_VAULT: String = "astd_story_aster_core_vault"

    /** 引力节点站 ×3（等边三角布局，station_research 类型实体 + condition-only 市场）。 */
    const val ASTER_NODE_1: String = "astd_story_aster_node_1"
    const val ASTER_NODE_2: String = "astd_story_aster_node_2"
    const val ASTER_NODE_3: String = "astd_story_aster_node_3"

    /** 三个引力节点实体 id（剧情顺序：外环测距 / 引力校准 / 权限剥离）。 */
    val ASTER_NODE_IDS: List<String> = listOf(ASTER_NODE_1, ASTER_NODE_2, ASTER_NODE_3)

    const val ASTER_COMM_RELAY: String = "astd_story_aster_comm_relay"
    const val ASTER_SENSOR_ARRAY: String = "astd_story_aster_sensor_array"
    const val ASTER_NAV_BUOY: String = "astd_story_aster_nav_buoy"
    const val ASTER_GATE: String = "astd_story_aster_gate"

    /** 引力节点实体标签：生涯层识别（接触触发护卫舰队 / 摧毁状态追踪）。 */
    const val TAG_GRAVITY_NODE: String = "astd_gravity_node"

    // ─── 市场 id ───

    const val MARKET_LANTAI: String = "astd_story_market_lantai"
    const val MARKET_HONGLU: String = "astd_story_market_honglu"
    const val MARKET_CUICHI: String = "astd_story_market_cuichi"
    const val MARKET_MAIN_STATION: String = "astd_story_market_main_station"
    const val MARKET_DUANYUAN: String = "astd_story_market_duanyuan"
    const val MARKET_SHIGUANG: String = "astd_story_market_shiguang"
    const val MARKET_NODE_1: String = "astd_story_market_node_1"
    const val MARKET_NODE_2: String = "astd_story_market_node_2"
    const val MARKET_NODE_3: String = "astd_story_market_node_3"

    // ─── 剧情市场状况 id（market_conditions.csv order 700~703 段） ───

    /** 菀星行政部遗址：流通/收入/稳定/舰队规模（兰台）。 */
    const val CONDITION_WANXING_ADMIN_RUINS: String = "astd_wanxing_admin_ruins"

    /** 星坠工程部遗址：流通/重工业产量/舰队规模/地面防御/设施上限（锻原）。 */
    const val CONDITION_STARFALL_ENGINEERING_RUINS: String = "astd_starfall_engineering_ruins"

    /** 视界动力：设施上限/维护费/危险度 + 1500su 事件视界免疫（拾光）。 */
    const val CONDITION_EVENT_HORIZON_POWER: String = "astd_event_horizon_power"

    /** 紫菀科研部遗址：流通/舰队规模/移民权重（拾光）。 */
    const val CONDITION_ASTER_RESEARCH_RUINS: String = "astd_aster_research_ruins"

    /** 视界动力事件视界免疫半径（su）。 */
    const val EVENT_HORIZON_IMMUNITY_RADIUS: Float = 1500f

    // ─── 触发与持久化 ───

    /** Sector persistentData key：剧情世界生成状态（[StoryWorldState]）。 */
    const val PERSISTENT_STATE_KEY: String = "astd_story_world_state"

    /** IndEvo（工业革命）mod id（软依赖检测用）。 */
    const val INDEVO_MOD_ID: String = "IndEvo"

    /** IndEvo 观锚站实体标签（幂等去重用）。 */
    const val TAG_INDEVO_WATCHTOWER: String = "astd_indevo_watchtower"
}
