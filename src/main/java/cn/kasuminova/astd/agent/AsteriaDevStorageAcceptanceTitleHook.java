package cn.kasuminova.astd.agent;

import com.fs.state.AppDriver;
import com.fs.state.AppState;
import com.fs.starfarer.api.impl.campaign.procgen.StarAge;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.campaign.CampaignState;
import com.fs.starfarer.campaign.save.CampaignGameManager;
import com.fs.starfarer.campaign.save.CharacterCreationDataImpl;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AsteriaDevStorageAcceptanceTitleHook {

    private static final String ENABLED_PROPERTY = "astd.devStorageAcceptance";
    private static final String SAVE_DIR_PROPERTY = "astd.devStorageAcceptanceSaveDir";
    private static final String CAREER_AUTOMATION_PROPERTY = "astd.careerAutomation";
    private static final String CAREER_SAVE_DIR_PROPERTY = "astd.careerAutomation.saveDir";
    private static final String CAREER_NEW_GAME_PROPERTY = "astd.careerAutomation.newGame";
    private static final String CAMPAIGN_STATE_ID = "Campaign State";
    private static final String CAMPAIGN_STATE_SESSION_KEY = "campaign state in session";

    private static final AtomicBoolean launched = new AtomicBoolean(false);

    private AsteriaDevStorageAcceptanceTitleHook() {
    }

    public static void tryLoadFromTitleScreen(final Object titleState) {
        if (titleState == null || !launched.compareAndSet(false, true)) {
            return;
        }
        if (Boolean.getBoolean(CAREER_AUTOMATION_PROPERTY)) {
            enterCareerAutomation((AppState) titleState);
            return;
        }
        if (!Boolean.getBoolean(ENABLED_PROPERTY)) {
            return;
        }

        final String targetSaveDir = resolveSaveDir();
        final AppDriver driver = AppDriver.getInstance();
        final CampaignState campaignState = resolveCampaignState(driver);

        System.out.println("[ASTD-Agent] Loading campaign save for dev storage acceptance: " + targetSaveDir);
        final Object error = CampaignGameManager.loadGame(targetSaveDir, campaignState, campaignState);
        if (error != null) {
            throw new IllegalStateException(
                    "[ASTD-Agent] Dev storage acceptance failed: campaign save load returned error: " + error
            );
        }

        ((AppState) titleState).goToState(CAMPAIGN_STATE_ID);
        System.out.println("[ASTD-Agent] Campaign save loaded for dev storage acceptance.");
    }

    /**
     * 生涯自动化入口：按属性读档（astd.careerAutomation.saveDir）或程序化新开档
     * （astd.careerAutomation.newGame=true，固定种子便于复现）。
     */
    private static void enterCareerAutomation(final AppState titleState) {
        final AppDriver driver = AppDriver.getInstance();
        final CampaignState campaignState = resolveCampaignState(driver);

        if (Boolean.getBoolean(CAREER_NEW_GAME_PROPERTY)) {
            System.out.println("[ASTD-Agent] Starting new campaign for career automation.");
            final CharacterCreationDataImpl data = new CharacterCreationDataImpl();
            data.setDifficulty("normal");
            data.setSectorAge(StarAge.ANY);
            data.setCampaignHelpEnabled(false);
            // CampaignLocationMapCanvas 会对种子串做 Long.parseLong，必须为纯数字
            data.setSeedString("2026090601");
            // 跳过开局时间流逝：初始舰队无补给会在流逝中直接触发事故
            data.setWithTimePass(false);
            data.addStartingFleetMember(data.getStartingShip(), FleetMemberType.SHIP);
            data.setDone(true);
            final Object error = CampaignGameManager.newGame(data, campaignState);
            if (error != null) {
                throw new IllegalStateException(
                        "[ASTD-Agent] Career automation failed: new game returned error: " + error
                );
            }
            // 程序化建舰不带整备：补 CR 与基础补给，避免 0% CR 事故报告对话框卡住自动化
            final CampaignFleetAPI playerFleet = Global.getSector().getPlayerFleet();
            for (final FleetMemberAPI member : playerFleet.getFleetData().getMembersListCopy()) {
                member.getRepairTracker().setCR(0.7f);
            }
            playerFleet.getCargo().addFuel(100f);
            playerFleet.getCargo().addSupplies(100f);
            playerFleet.getCargo().addCrew(100);
            titleState.goToState(CAMPAIGN_STATE_ID);
            System.out.println("[ASTD-Agent] New campaign created for career automation.");
            return;
        }

        final String saveDir = System.getProperty(CAREER_SAVE_DIR_PROPERTY);
        if (saveDir == null || saveDir.trim().isEmpty()) {
            throw new IllegalStateException(
                    "[ASTD-Agent] Career automation failed: missing -D" + CAREER_SAVE_DIR_PROPERTY
                            + " (or set -D" + CAREER_NEW_GAME_PROPERTY + "=true)."
            );
        }
        System.out.println("[ASTD-Agent] Loading campaign save for career automation: " + saveDir.trim());
        final Object error = CampaignGameManager.loadGame(saveDir.trim(), campaignState, campaignState);
        if (error != null) {
            throw new IllegalStateException(
                    "[ASTD-Agent] Career automation failed: campaign save load returned error: " + error
            );
        }
        titleState.goToState(CAMPAIGN_STATE_ID);
        System.out.println("[ASTD-Agent] Campaign save loaded for career automation.");
    }

    private static CampaignState resolveCampaignState(final AppDriver driver) {
        final Map session = driver.getSession();
        final Object fromSession = session.get(CAMPAIGN_STATE_SESSION_KEY);
        final Object state = fromSession != null ? fromSession : driver.getState(CAMPAIGN_STATE_ID);
        if (!(state instanceof CampaignState)) {
            throw new IllegalStateException("[ASTD-Agent] Dev storage acceptance failed: campaign state is unavailable.");
        }
        return (CampaignState) state;
    }

    private static String resolveSaveDir() {
        final String saveDir = System.getProperty(SAVE_DIR_PROPERTY);
        if (saveDir == null || saveDir.trim().isEmpty()) {
            throw new IllegalStateException(
                    "[ASTD-Agent] Dev storage acceptance failed: missing -D" + SAVE_DIR_PROPERTY + "."
            );
        }
        return saveDir.trim();
    }
}
