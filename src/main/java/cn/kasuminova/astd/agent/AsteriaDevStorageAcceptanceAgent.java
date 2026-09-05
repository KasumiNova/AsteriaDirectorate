package cn.kasuminova.astd.agent;

import java.lang.instrument.Instrumentation;

public final class AsteriaDevStorageAcceptanceAgent {

    private static final String DEV_STORAGE_ENABLED_PROPERTY = "astd.devStorageAcceptance";
    private static final String CAMPAIGN_AUTOMATION_ENABLED_PROPERTY = "astd.campaignAutomation.enabled";
    private static final String TITLE_CLASS_NAME = "com.fs.starfarer.title.TitleScreenState";
    private static final String CAMPAIGN_CLASS_NAME = "com.fs.starfarer.campaign.CampaignState";

    private AsteriaDevStorageAcceptanceAgent() {
    }

    public static void premain(final String agentArgs, final Instrumentation instrumentation) {
        final boolean devStorageEnabled = Boolean.getBoolean(DEV_STORAGE_ENABLED_PROPERTY);
        final boolean campaignAutomationEnabled = Boolean.getBoolean(CAMPAIGN_AUTOMATION_ENABLED_PROPERTY);
        if (!devStorageEnabled && !campaignAutomationEnabled) {
            return;
        }
        final AsteriaTitleScreenAdvanceTransformer transformer = new AsteriaTitleScreenAdvanceTransformer();
        instrumentation.addTransformer(transformer, true);
        retransformLoadedClasses(instrumentation, devStorageEnabled, campaignAutomationEnabled);
        System.out.println("[ASTD-Agent] Campaign hooks installed: devStorage="
                + devStorageEnabled + ", campaignAutomation=" + campaignAutomationEnabled + ".");
    }

    private static void retransformLoadedClasses(final Instrumentation instrumentation,
                                                  final boolean devStorageEnabled,
                                                  final boolean campaignAutomationEnabled) {
        for (final Class<?> loadedClass : instrumentation.getAllLoadedClasses()) {
            final boolean titleTarget = TITLE_CLASS_NAME.equals(loadedClass.getName());
            final boolean campaignTarget = CAMPAIGN_CLASS_NAME.equals(loadedClass.getName());
            if ((!titleTarget && !campaignTarget) || (campaignTarget && !campaignAutomationEnabled)) {
                continue;
            }
            if (!instrumentation.isModifiableClass(loadedClass)) {
                System.err.println("[ASTD-Agent] Already loaded target is not modifiable: " + loadedClass.getName());
                continue;
            }
            try {
                instrumentation.retransformClasses(loadedClass);
                System.out.println("[ASTD-Agent] Requested retransform: " + loadedClass.getName());
            } catch (final Exception ex) {
                throw new IllegalStateException("[ASTD-Agent] Failed to retransform " + loadedClass.getName() + ".", ex);
            }
        }
    }
}
