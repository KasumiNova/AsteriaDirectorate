package cn.kasuminova.astd;

import cn.kasuminova.astd.campaign.AsteriaTestCampaignBootstrap;
import cn.kasuminova.astd.campaign.bounty.StandardCores;
import cn.kasuminova.astd.campaign.integration.sic.SicIntegration;
import cn.kasuminova.astd.campaign.world.StoryWorldBootstrap;
import cn.kasuminova.astd.combat.effect.arc.positronshockwave.PositronShockwaveAutofireAiPicker;
import cn.kasuminova.astd.combat.effect.joint.stardust.StardustLauncherAutofireAiPicker;
import cn.kasuminova.astd.combat.effect.joint.stardust.StardustMoteAiPicker;
import cn.kasuminova.astd.combat.hullmods.base.ASTDCampaignPlugin;
import cn.kasuminova.astd.combat.hullmods.base.ASTDDualModeConfigKt;
import cn.kasuminova.astd.combat.hullmods.base.ASTDDualModeMirror;
import cn.kasuminova.astd.combat.hullmods.base.ASTDDualModeMirrorScript;
import cn.kasuminova.astd.combat.hullmods.base.ASTDDualModeRefitListener;
import cn.kasuminova.astd.impl.buff.BuffInstall;
import cn.kasuminova.astd.impl.difficulty.DifficultySettingsRegistrar;
import cn.kasuminova.astd.impl.difficulty.LunaLibSupport;
import cn.kasuminova.astd.renderer.effect.system.WeaponGlowLayer;
import cn.kasuminova.astd.renderer.effect.system.GeminiDemRackVisuals;
import cn.kasuminova.astd.renderer.effect.system.GravityPhaseVisualEffect;
import cn.kasuminova.astd.renderer.effect.system.LoadedMissileGlowLayer;
import cn.kasuminova.astd.renderer.effect.system.ShipGlowRenderer;
import cn.kasuminova.astd.renderer.effect.system.Xc002GhostWingsEffect;
import cn.kasuminova.astd.renderer.effect.system.GravTimeflowLinkVfx;
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

    private static final Logger logger = Logger.getLogger(AsteriaDirectoratePlugin.class);

    /** ModPlugin 可能被重复创建，禁止在构造器中单例校验，否则 ScriptStore.new() 会导致模组加载失败。 */
    public AsteriaDirectoratePlugin() {
        instance = this;
    }

    public static AsteriaDirectoratePlugin instance() {
        return instance;
    }

    @Override
    public void onApplicationLoad() {
        logger.info("[ASTD] Asteria Directorate loaded on Java " + System.getProperty("java.version"));
        BuffInstall.INSTANCE.install();
        ASTDDualModeConfigKt.installDualModeAutoPointsHook();
        // DifficultySettingsRegistrar 引用 lunalib.*，未安装 LunaLib 时必须避免加载该类。
        if (LunaLibSupport.INSTANCE.isAvailable()) {
            DifficultySettingsRegistrar.INSTANCE.register();
        } else {
            logger.info("[ASTD] 未检测到 LunaLib：难度档位与双模式免自动化点数开关不可用，全部使用默认值");
        }
        // 自定义贴图必须在应用加载阶段预加载，战斗或裸 getSprite 路径中加载会导致渲染异常。
        WeaponGlowLayer.INSTANCE.preloadTextures();
        LoadedMissileGlowLayer.INSTANCE.preloadTextures();
        GeminiDemRackVisuals.INSTANCE.preloadTextures();
        ShipGlowRenderer.INSTANCE.preloadTextures();
        GravityPhaseVisualEffect.INSTANCE.preloadTextures();
        Xc002GhostWingsEffect.INSTANCE.preloadTextures();
        GravTimeflowLinkVfx.preloadTextures();
        StandardCores.INSTANCE.preloadPortraits();
        SicIntegration.INSTANCE.onApplicationLoad();
    }

    @Override
    public void onNewGameAfterEconomyLoad() {
        StoryWorldBootstrap.INSTANCE.onNewGameAfterEconomyLoad();
        AsteriaTestCampaignBootstrap.runIfEnabled();
    }

    @Override
    public void onNewGameAfterTimePass() {
        AsteriaTestCampaignBootstrap.runIfEnabled();
        AsteriaTestCampaignBootstrap.finalizeNewGameTeleportIfEnabled();
    }

    @Override
    public void onGameLoad(boolean newGame) {
        ShipGlowRenderer.INSTANCE.ensureRestoreScriptRegistered();
        StoryWorldBootstrap.INSTANCE.onGameLoad(newGame);
        // 0.98 的 ModPlugin 不继承 CampaignPlugin，必须使用固定 id 注册。
        Global.getSector().registerPlugin(new ASTDCampaignPlugin());
        ASTDDualModeMirror.INSTANCE.clear();
        Global.getSector().addTransientScript(new ASTDDualModeMirrorScript());
        Global.getSector().getListenerManager().addListener(new ASTDDualModeRefitListener(), true);
        if (!newGame) {
            AsteriaTestCampaignBootstrap.repairExistingTestStorageIfEnabled();
            AsteriaTestCampaignBootstrap.resumePendingTeleportIfEnabled();
            AsteriaTestCampaignBootstrap.runStorageAcceptanceIfRequested();
        }
    }

    @Override
    public PluginPick<MissileAIPlugin> pickMissileAI(MissileAPI missile, ShipAPI launchingShip) {
        return StardustMoteAiPicker.INSTANCE.pick(missile);
    }

    @Override
    public PluginPick<AutofireAIPlugin> pickWeaponAutofireAI(WeaponAPI weapon) {
        PluginPick<AutofireAIPlugin> stardust = StardustLauncherAutofireAiPicker.INSTANCE.pick(weapon);
        if (stardust != null) return stardust;
        return PositronShockwaveAutofireAiPicker.INSTANCE.pick(weapon);
    }

    public Logger logger() {
        return logger;
    }
}
