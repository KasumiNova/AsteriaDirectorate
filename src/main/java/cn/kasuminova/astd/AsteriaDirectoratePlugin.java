package cn.kasuminova.astd;

import cn.kasuminova.astd.campaign.AsteriaTestCampaignBootstrap;
import cn.kasuminova.astd.campaign.automation.CareerAutomationInstall;
import cn.kasuminova.astd.campaign.bounty.BountyBootstrapper;
import cn.kasuminova.astd.campaign.bounty.StandardCores;
import cn.kasuminova.astd.campaign.story.StoryDialogInstall;
import cn.kasuminova.astd.campaign.world.StoryWorldBootstrap;
import cn.kasuminova.astd.combat.effect.joint.stardust.StardustMoteAiPicker;
import cn.kasuminova.astd.combat.hullmods.arc.ASTDXc001HullModUtilKt;
import cn.kasuminova.astd.combat.hullmods.lens.LensArrayCoreModeUtilKt;
import cn.kasuminova.astd.impl.buff.BuffInstall;
import cn.kasuminova.astd.impl.difficulty.DifficultySettingsRegistrar;
import cn.kasuminova.astd.renderer.effect.system.WeaponAmbientGlowManager;
import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.PluginPick;
import com.fs.starfarer.api.combat.MissileAIPlugin;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
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
        // 注册 lens / arc 双模式配置到通用注册表（ASTDDualModeRegistry），保证通用切换器 tooltip
        // 在任何 refit 渲染前就能 configForShip 反查到对应舰的模式 id 集合。幂等，可多实例多次调用。
        LensArrayCoreModeUtilKt.registerLensDualModeConfig();
        ASTDXc001HullModUtilKt.registerXc001DualModeConfig();
        // 注册 Buff 系统后端到 api 侧 BuffBackends（api 不反向依赖 impl，桥接口在此注入）。
        BuffInstall.INSTANCE.install();
        // 注入剧情对话后端到 ui 侧 StoryDialogBackends（ui 不反向依赖 campaign，桥接口在此注入）。
        StoryDialogInstall.INSTANCE.install();
        // 注册难度设置（轨一：固有缩放系数）到 LunaLib 设置界面，并应用当前生效档位。
        DifficultySettingsRegistrar.INSTANCE.register();
        // 预加载制式核心军官头像（SSOptimizer 延迟加载下裸 getSprite 是 textureID=0 黑壳）。
        StandardCores.INSTANCE.preloadPortraits();
        // 预加载武器常驻发光贴图（同上原因，未被 .wpn 引用的贴图不会上传 GL）。
        WeaponAmbientGlowManager.INSTANCE.preloadTextures();
    }

    @Override
    public void onNewGameAfterEconomyLoad() {
        // 剧情主星系（生涯开局生成，幂等）
        StoryWorldBootstrap.INSTANCE.onNewGameAfterEconomyLoad();
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
        // 注册赏金动态生成/词缀管理脚本（主线内容重做中，框架先行）。
        // 注意：允许多实例；脚本添加由 sector memory key 去重。
        BountyBootstrapper.onGameLoad();
        // 剧情世界：生涯层脚本/战役插件注册 + 读档补齐（含第二章钩子补齐路径）
        StoryWorldBootstrap.INSTANCE.onGameLoad(newGame);
        // 生涯集成自动化（astd.careerAutomation.enabled 属性门控 + automation 模块在包内才生效）
        CareerAutomationInstall.onGameLoad();
        if (!newGame) {
            AsteriaTestCampaignBootstrap.repairExistingTestStorageIfEnabled();
            AsteriaTestCampaignBootstrap.resumePendingTeleportIfEnabled();
            AsteriaTestCampaignBootstrap.runStorageAcceptanceIfRequested();
        }
    }

    @Override
    public PluginPick<MissileAIPlugin> pickMissileAI(MissileAPI missile, ShipAPI launchingShip) {
        // 星尘光尘（联制线内置导弹）：原版引擎不为 MOTE 型弹体自动指派 AI，经本钩子接管。
        return StardustMoteAiPicker.INSTANCE.pick(missile);
    }

    public Logger logger() {
        return logger;
    }
}
