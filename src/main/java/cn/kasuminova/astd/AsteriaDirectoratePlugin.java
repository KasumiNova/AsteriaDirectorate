package cn.kasuminova.astd;

import cn.kasuminova.astd.campaign.AsteriaTestCampaignBootstrap;
import cn.kasuminova.astd.campaign.bounty.StandardCores;
import cn.kasuminova.astd.campaign.world.StoryWorldBootstrap;
import cn.kasuminova.astd.combat.effect.arc.positronshockwave.PositronShockwaveAutofireAiPicker;
import cn.kasuminova.astd.combat.effect.joint.stardust.StardustLauncherAutofireAiPicker;
import cn.kasuminova.astd.combat.effect.joint.stardust.StardustMoteAiPicker;
import cn.kasuminova.astd.combat.hullmods.base.ASTDCampaignPlugin;
import cn.kasuminova.astd.combat.hullmods.base.ASTDDualModeConfigKt;
import cn.kasuminova.astd.combat.hullmods.base.ASTDDualModeMirror;
import cn.kasuminova.astd.combat.hullmods.base.ASTDDualModeMirrorScript;
import cn.kasuminova.astd.combat.hullmods.base.ASTDDualModeRefitListener;
import cn.kasuminova.astd.combat.hullmods.lens.LensArrayCoreModeUtilKt;
import cn.kasuminova.astd.impl.buff.BuffInstall;
import cn.kasuminova.astd.impl.difficulty.DifficultySettingsRegistrar;
import cn.kasuminova.astd.impl.difficulty.LunaLibSupport;
import cn.kasuminova.astd.renderer.effect.system.WeaponGlowLayer;
import cn.kasuminova.astd.renderer.effect.system.GeminiDemRackVisuals;
import cn.kasuminova.astd.renderer.effect.system.GravityPhaseVisualEffect;
import cn.kasuminova.astd.renderer.effect.system.LoadedMissileGlowLayer;
import cn.kasuminova.astd.renderer.effect.system.ShipGlowRenderer;
import cn.kasuminova.astd.renderer.effect.system.Xc002GhostWingsEffect;
import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.PluginPick;
import com.fs.starfarer.api.combat.AutofireAIPlugin;
import com.fs.starfarer.api.combat.MissileAIPlugin;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
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
        // 在任何 refit 渲染前就能 configForShip 反查到对应舰的模式 id 集合。幂等，可多实例多次调用。
        LensArrayCoreModeUtilKt.registerLensDualModeConfig();
        // 注册 Buff 系统后端到 api 侧 BuffBackends（api 不反向依赖 impl，桥接口在此注入）。
        BuffInstall.INSTANCE.install();
        // 安装「双模式切换器自动模式免自动化点数」的热重载钩子（须在 LunaLib 设置注册前装好，
        // 保证注册/回调路径触发变更时钩子已在位）。
        ASTDDualModeConfigKt.installDualModeAutoPointsHook();
        // LunaLib 为可选前置（mod_info 不声明依赖）：不可用时跳过设置注册，
        // 难度档位与双模式开关全部取默认值（敌方砺刃 2.0 / 我方砺刃 2.0 / 免自动化点数开启）。
        // 注意：DifficultySettingsRegistrar 类体触碰 lunalib.* 类型，未安装 LunaLib 时
        // 连方法调用都会 NoClassDefFoundError，必须经 LunaLibSupport 门控后再触碰。
        if (LunaLibSupport.INSTANCE.isAvailable()) {
            // 注册全部 LunaLib 设置项（敌方/我方难度档位 + 双模式免自动化点数开关），并应用当前生效值。
            DifficultySettingsRegistrar.INSTANCE.register();
        } else {
            logger.info("[ASTD] 未检测到 LunaLib：难度档位与双模式免自动化点数开关不可用，全部使用默认值");
        }
        // 预加载武器补档发光贴图（常驻 + 蓄能；同上原因，未被 .wpn 引用的贴图不会上传 GL）。
        WeaponGlowLayer.INSTANCE.preloadTextures();
        // 预加载挂载弹体光效贴图（同上原因）。
        LoadedMissileGlowLayer.INSTANCE.preloadTextures();
        // 预加载双子星 DEM 导轨弹体光效贴图（同上原因）。
        GeminiDemRackVisuals.INSTANCE.preloadTextures();
        // 预加载舰船覆盖发光层（bloom/装饰灯）贴图与引力相位红色变体（同上原因）。
        ShipGlowRenderer.INSTANCE.preloadTextures();
        // 动态生成引力相位舰船的 SDF 描边纹理（同上原因；战斗中加载会损坏上传队列）。
        GravityPhaseVisualEffect.INSTANCE.preloadTextures();
        // 预加载 XC-002 虚数之翼光翼贴图（同上原因）。
        Xc002GhostWingsEffect.INSTANCE.preloadTextures();
        // 预加载制式核心军官头像贴图（同上原因；核心指派界面为裸 getSprite 路径）。
        StandardCores.INSTANCE.preloadPortraits();
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
        // 覆盖发光层贴图恢复脚本（战斗内压制共享 sprite 颜色的战役侧还原，幂等去重）
        ShipGlowRenderer.INSTANCE.ensureRestoreScriptRegistered();
        // 剧情世界：生涯层脚本注册 + 读档补齐
        StoryWorldBootstrap.INSTANCE.onGameLoad(newGame);
        // 战役插件：向原版插件挑选体系暴露 ASTDAutofitPlugin（双模式舰自动装配保护）。
        // 固定 id 去重，重复读档注册互相替换；0.98 ModPlugin 不继承 CampaignPlugin，必须走 registerPlugin。
        Global.getSector().registerPlugin(new ASTDCampaignPlugin());
        // 双模式镜像脚本（transient，读档重注册）：把 refit 工作克隆上的模式翻转镜像到真实 variant，
        // 令头像选择界面立即读到新模式；runWhilePaused，停靠状态同样走帧。注册前清空进程内待镜像队列。
        ASTDDualModeMirror.INSTANCE.clear();
        Global.getSector().addTransientScript(new ASTDDualModeMirrorScript());
        // 装配提交监听（transient）：variant 提交时收敛双模式状态并按最终模式清理不兼容舰长/AI 核心。
        Global.getSector().getListenerManager().addListener(new ASTDDualModeRefitListener(), true);
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

    @Override
    public PluginPick<AutofireAIPlugin> pickWeaponAutofireAI(WeaponAPI weapon) {
        // 星尘发射器（舰尾 arc 0 内置）：原版自动开火要求目标入射界，舰尾武器永不开火，
        // 经本钩子接管为「弹药充足且存活光尘未达上限即无条件开火」。
        PluginPick<AutofireAIPlugin> stardust = StardustLauncherAutofireAiPicker.INSTANCE.pick(weapon);
        if (stardust != null) return stardust;
        // 正电子冲击波（2026-10 裁定）：同舰同类武器协同索敌——导弹优先、射界/密度评分、
        // 按目标耐久饱和分配（高耐久导弹多管齐射），避免原版 PD 扎堆同一目标。
        return PositronShockwaveAutofireAiPicker.INSTANCE.pick(weapon);
    }

    public Logger logger() {
        return logger;
    }
}
