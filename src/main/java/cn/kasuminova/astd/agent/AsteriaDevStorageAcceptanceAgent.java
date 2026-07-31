package cn.kasuminova.astd.agent;

import java.lang.instrument.Instrumentation;

public final class AsteriaDevStorageAcceptanceAgent {

    private static final String ENABLED_PROPERTY = "astd.devStorageAcceptance";
    private static final String TARGET_CLASS_NAME = "com.fs.starfarer.title.TitleScreenState";

    private AsteriaDevStorageAcceptanceAgent() {
    }

    public static void premain(final String agentArgs, final Instrumentation instrumentation) {
        if (!Boolean.getBoolean(ENABLED_PROPERTY)) {
            return;
        }
        final AsteriaTitleScreenAdvanceTransformer transformer = new AsteriaTitleScreenAdvanceTransformer();
        instrumentation.addTransformer(transformer, true);
        retransformTitleScreenIfAlreadyLoaded(instrumentation);
        System.out.println("[ASTD-Agent] Dev storage acceptance title-screen hook installed.");
    }

    private static void retransformTitleScreenIfAlreadyLoaded(final Instrumentation instrumentation) {
        for (final Class<?> loadedClass : instrumentation.getAllLoadedClasses()) {
            if (!TARGET_CLASS_NAME.equals(loadedClass.getName())) {
                continue;
            }
            if (!instrumentation.isModifiableClass(loadedClass)) {
                System.err.println("[ASTD-Agent] TitleScreenState was already loaded but is not modifiable.");
                return;
            }
            try {
                instrumentation.retransformClasses(loadedClass);
                System.out.println("[ASTD-Agent] Requested TitleScreenState retransform for already loaded class.");
            } catch (final Exception ex) {
                throw new IllegalStateException("[ASTD-Agent] Failed to retransform already loaded TitleScreenState.", ex);
            }
            return;
        }
    }
}
