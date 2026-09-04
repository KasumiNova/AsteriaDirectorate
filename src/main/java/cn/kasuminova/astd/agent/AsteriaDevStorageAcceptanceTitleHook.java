package cn.kasuminova.astd.agent;

import com.fs.state.AppDriver;
import com.fs.state.AppState;
import com.fs.starfarer.campaign.CampaignState;
import com.fs.starfarer.campaign.save.CampaignGameManager;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AsteriaDevStorageAcceptanceTitleHook {

    private static final String ENABLED_PROPERTY = "astd.devStorageAcceptance";
    private static final String SAVE_DIR_PROPERTY = "astd.devStorageAcceptanceSaveDir";
    private static final String CAMPAIGN_STATE_ID = "Campaign State";
    private static final String CAMPAIGN_STATE_SESSION_KEY = "campaign state in session";

    private static final AtomicBoolean launched = new AtomicBoolean(false);

    private AsteriaDevStorageAcceptanceTitleHook() {
    }

    public static void tryLoadFromTitleScreen(final Object titleState) {
        if (!Boolean.getBoolean(ENABLED_PROPERTY)) {
            return;
        }
        if (titleState == null || !launched.compareAndSet(false, true)) {
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
