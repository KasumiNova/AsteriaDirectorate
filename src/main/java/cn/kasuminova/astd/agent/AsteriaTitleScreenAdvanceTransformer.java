package cn.kasuminova.astd.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

public final class AsteriaTitleScreenAdvanceTransformer implements ClassFileTransformer {

    private static final String DEV_STORAGE_ENABLED_PROPERTY = "astd.devStorageAcceptance";
    private static final String CAMPAIGN_AUTOMATION_ENABLED_PROPERTY = "astd.campaignAutomation.enabled";
    private static final String TITLE_CLASS = "com/fs/starfarer/title/TitleScreenState";
    private static final String CAMPAIGN_CLASS = "com/fs/starfarer/campaign/CampaignState";
    private static final String TITLE_METHOD = "prepare";
    private static final String TITLE_DESC = "()V";
    private static final String CAMPAIGN_ADVANCE_METHOD = "advance";
    private static final String CAMPAIGN_ADVANCE_DESC = "(FLcom/fs/starfarer/util/InputEventList;)V";
    private static final String CAMPAIGN_RENDER_METHOD = "render";
    private static final String CAMPAIGN_RENDER_DESC = "(F)V";
    private static final String TITLE_HOOK_OWNER = "cn/kasuminova/astd/agent/AsteriaDevStorageAcceptanceTitleHook";
    private static final String TITLE_HOOK_METHOD = "tryLoadFromTitleScreen";
    private static final String CAMPAIGN_HOOK_OWNER = "cn/kasuminova/astd/agent/CampaignAutomationIo";
    private static final String CAMPAIGN_ADVANCE_HOOK = "afterAdvance";
    private static final String CAMPAIGN_RENDER_HOOK = "afterRender";
    private static final String HOOK_DESC = "(Ljava/lang/Object;)V";

    @Override
    public byte[] transform(
            final java.lang.Module module,
            final ClassLoader loader,
            final String className,
            final Class<?> classBeingRedefined,
            final ProtectionDomain protectionDomain,
            final byte[] classfileBuffer
    ) {
        return transformClass(className, classfileBuffer);
    }

    @Override
    public byte[] transform(
            final ClassLoader loader,
            final String className,
            final Class<?> classBeingRedefined,
            final ProtectionDomain protectionDomain,
            final byte[] classfileBuffer
    ) {
        return transformClass(className, classfileBuffer);
    }

    private byte[] transformClass(final String className, final byte[] classfileBuffer) {
        if (classfileBuffer == null || (!TITLE_CLASS.equals(className) && !CAMPAIGN_CLASS.equals(className))) {
            return null;
        }
        try {
            if (TITLE_CLASS.equals(className)) {
                return transformTitleScreen(classfileBuffer);
            }
            if (!Boolean.getBoolean(CAMPAIGN_AUTOMATION_ENABLED_PROPERTY)) {
                return null;
            }
            return transformCampaignState(classfileBuffer);
        } catch (final Throwable ex) {
            System.err.println("[ASTD-Agent] Campaign automation transform failed for " + className + ".");
            ex.printStackTrace(System.err);
            throw ex;
        }
    }

    private byte[] transformTitleScreen(final byte[] classfileBuffer) {
        System.out.println("[ASTD-Agent] Transforming TitleScreenState for campaign save loading.");
        final ClassReader reader = new ClassReader(classfileBuffer);
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        final boolean[] transformed = {false};
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(final int access, final String name, final String descriptor,
                                             final String signature, final String[] exceptions) {
                final MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!TITLE_METHOD.equals(name) || !TITLE_DESC.equals(descriptor)) {
                    return delegate;
                }
                transformed[0] = true;
                return new AdviceAdapter(Opcodes.ASM9, delegate, access, name, descriptor) {
                    @Override
                    protected void onMethodEnter() {
                        loadThis();
                        invokeStatic(Type.getObjectType(TITLE_HOOK_OWNER),
                                new org.objectweb.asm.commons.Method(TITLE_HOOK_METHOD, HOOK_DESC));
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        if (!transformed[0]) {
            System.err.println("[ASTD-Agent] TitleScreenState.prepare hook target was not found.");
            return null;
        }
        return writer.toByteArray();
    }

    private byte[] transformCampaignState(final byte[] classfileBuffer) {
        System.out.println("[ASTD-Agent] Transforming CampaignState for safe checkpoint IO.");
        final ClassReader reader = new ClassReader(classfileBuffer);
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        final boolean[] advanceTransformed = {false};
        final boolean[] renderTransformed = {false};
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(final int access, final String name, final String descriptor,
                                             final String signature, final String[] exceptions) {
                final MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (CAMPAIGN_ADVANCE_METHOD.equals(name) && CAMPAIGN_ADVANCE_DESC.equals(descriptor)) {
                    advanceTransformed[0] = true;
                    return new AdviceAdapter(Opcodes.ASM9, delegate, access, name, descriptor) {
                        @Override
                        protected void onMethodExit(final int opcode) {
                            if (opcode == ATHROW) {
                                return;
                            }
                            loadThis();
                            invokeStatic(Type.getObjectType(CAMPAIGN_HOOK_OWNER),
                                    new org.objectweb.asm.commons.Method(CAMPAIGN_ADVANCE_HOOK, HOOK_DESC));
                        }
                    };
                }
                if (CAMPAIGN_RENDER_METHOD.equals(name) && CAMPAIGN_RENDER_DESC.equals(descriptor)) {
                    renderTransformed[0] = true;
                    return new AdviceAdapter(Opcodes.ASM9, delegate, access, name, descriptor) {
                        @Override
                        protected void onMethodExit(final int opcode) {
                            if (opcode == ATHROW) {
                                return;
                            }
                            loadThis();
                            invokeStatic(Type.getObjectType(CAMPAIGN_HOOK_OWNER),
                                    new org.objectweb.asm.commons.Method(CAMPAIGN_RENDER_HOOK, HOOK_DESC));
                        }
                    };
                }
                return delegate;
            }
        }, ClassReader.EXPAND_FRAMES);
        if (!advanceTransformed[0] || !renderTransformed[0]) {
            System.err.println("[ASTD-Agent] CampaignState safe checkpoint hook target was not found: advance="
                    + advanceTransformed[0] + ", render=" + renderTransformed[0] + ".");
            return null;
        }
        return writer.toByteArray();
    }
}
