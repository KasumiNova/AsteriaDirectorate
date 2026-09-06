package cn.kasuminova.astd.campaign.bounty;

/**
 * BountyState 旧存档迁移测试支撑：Kotlin 侧无法把非空字段置回 null，
 * 这里模拟 XStream 反序列化旧档（缺失后加字段）产出的实例——集合/Map 字段全为 null。
 */
public final class BountyStateTestSupport {

    private BountyStateTestSupport() {
    }

    /** 构造一个「字段缺失的旧实例」：全部集合/Map 字段为 null（等价于 XStream 绕过构造函数的旧档反序列化结果）。 */
    public static BountyState legacyInstanceWithNullCollections() {
        BountyState state = new BountyState();
        state.patchedBountyKeys = null;
        state.concludedBountyKeys = null;
        state.clearedGroups = null;
        state.postedWorkOrders = null;
        state.destroyedWorkOrders = null;
        state.settledWorkOrders = null;
        state.workOrderStageIndex = null;
        state.quotedRewards = null;
        state.grantedGroupBonuses = null;
        state.chapterHooks = null;
        state.chapterClearingOrders = null;
        return state;
    }
}
