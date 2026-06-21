package cn.kasuminova.astd;

import cn.kasuminova.astd.campaign.AsteriaTestCampaignBootstrap;
import cn.kasuminova.astd.campaign.bounty.BountyBootstrapper;
import cn.kasuminova.astd.combat.hullmods.lens.LensArrayCoreModeUtilKt;
import com.fs.starfarer.api.BaseModPlugin;
import org.apache.log4j.Logger;

public final class AsteriaDirectoratePlugin extends BaseModPlugin {

    private static AsteriaDirectoratePlugin instance = null;

    /**
     * 注意：ModPlugin 可能在加载流程中被反射创建不止一次（例如热加载/重载/某些启动流程）。
     * 这里不要做"单例强约束"，否则会导致 ScriptStore.new() 直接失败并让模组无法加载。
     */
    private static final Logger logger = Logger.getLogger(AsteriaDirectoratePlugin.class);

    public AsteriaDirectoratePlugin() {
        // 放宽：永远允许实例化；只保留一个最近创建的引用用于调试/日志。
        instance = this;
    }

    public static AsteriaDirectoratePlugin instance() {
        return instance;
    }

    @Override
    public void onApplicationLoad() {
        logger.info("[ASTD] Asteria Directorate loaded on Java " + System.getProperty("java.version"));
        // 注册 lens 双模式配置到通用注册表（ASTDDualModeRegistry），保证通用切换器 tooltip
        // 在任何 refit 渲染前就能 configForShip 反查到 lens 的模式 id 集合。幂等，可多实例多次调用。
        LensArrayCoreModeUtilKt.registerLensDualModeConfig();
    }

    @Override
    public void onNewGameAfterEconomyLoad() {
        // 测试用：在 devMode 新开档后生成一个测试市场，并把本模组的船/武器塞进仓储。
        // 方便快速在战役里验证数据与脚本效果。
        AsteriaTestCampaignBootstrap.runIfEnabled();
    }

    @Override
    public void onNewGameAfterTimePass() {
        AsteriaTestCampaignBootstrap.runIfEnabled();
        AsteriaTestCampaignBootstrap.finalizeNewGameTeleportIfEnabled();
    }

    @Override
    public void onGameLoad(boolean newGame) {
        // 注册赏金动态生成/词缀/支线管理脚本。
        // 注意：允许多实例；脚本添加由 sector memory key 去重。
        BountyBootstrapper.onGameLoad();
        if (!newGame) {
            AsteriaTestCampaignBootstrap.repairExistingTestStorageIfEnabled();
            AsteriaTestCampaignBootstrap.resumePendingTeleportIfEnabled();
            AsteriaTestCampaignBootstrap.runStorageAcceptanceIfRequested();
        }
    }

    public Logger logger() {
        return logger;
    }
}
