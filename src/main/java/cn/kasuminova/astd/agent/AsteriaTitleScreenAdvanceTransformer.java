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

    private static final String TARGET_CLASS = "com/fs/starfarer/title/TitleScreenState";
    private static final String TARGET_METHOD = "prepare";
    private static final String TARGET_DESC = "()V";
    private static final String HOOK_OWNER = "cn/kasuminova/astd/agent/AsteriaDevStorageAcceptanceTitleHook";
    private static final String HOOK_METHOD = "tryLoadFromTitleScreen";
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
        if (!TARGET_CLASS.equals(className)) {
            return null;
        }

        try {
            return transformTitleScreen(classfileBuffer);
        } catch (final Throwable ex) {
            System.err.println("[ASTD-Agent] TitleScreenState hook transform failed.");
            ex.printStackTrace(System.err);
            throw ex;
        }
    }

    private byte[] transformTitleScreen(final byte[] classfileBuffer) {
        System.out.println("[ASTD-Agent] Transforming TitleScreenState for dev storage acceptance.");

        final ClassReader reader = new ClassReader(classfileBuffer);
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        final boolean[] transformed = {false};

        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(
                    final int access,
                    final String name,
                    final String descriptor,
                    final String signature,
                    final String[] exceptions
            ) {
                final MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!TARGET_METHOD.equals(name) || !TARGET_DESC.equals(descriptor)) {
                    return delegate;
                }

                transformed[0] = true;
                return new AdviceAdapter(Opcodes.ASM9, delegate, access, name, descriptor) {
                    @Override
                    protected void onMethodEnter() {
                        loadThis();
                        invokeStatic(Type.getObjectType(HOOK_OWNER), new org.objectweb.asm.commons.Method(HOOK_METHOD, HOOK_DESC));
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);

        if (!transformed[0]) {
            System.err.println("[ASTD-Agent] TitleScreenState.prepare hook target was not found.");
            return null;
        }

        System.out.println("[ASTD-Agent] TitleScreenState.prepare hook applied.");
        return writer.toByteArray();
    }
}
