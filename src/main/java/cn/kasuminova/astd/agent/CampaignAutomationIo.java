package cn.kasuminova.astd.agent;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.campaign.CampaignEngine;
import com.fs.starfarer.campaign.CampaignState;
import com.fs.starfarer.campaign.save.CampaignGameManager;
import com.fs.starfarer.settings.StarfarerSettings;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/** Trusted agent-side campaign IO; scripts request work through run-scoped JVM properties only. */
public final class CampaignAutomationIo {
    public static final String PREFIX = "astd.campaignAutomation.";
    public static final String CHECKPOINT_REQUEST = PREFIX + "checkpointRequested";
    public static final String CHECKPOINT_STATUS = PREFIX + "checkpointStatus";
    public static final String CAPTURE_REQUEST = PREFIX + "captureRequested";
    public static final String CAPTURE_STATUS = PREFIX + "captureStatus";
    private static String runId;
    private static String scenario;
    private static String phase;
    private static Path saveDir;
    private static Path outputDir;
    private static boolean saving;
    private static boolean checkpointCaptured;
    private static boolean checkpointSaved;
    private static boolean captured;
    private static boolean failed;

    private CampaignAutomationIo() {
    }

    public static boolean isEnabled() {
        return Boolean.getBoolean(PREFIX + "enabled");
    }

    public static String initialize() throws IOException {
        if (Boolean.getBoolean("ssoptimizer.automation.enabled") || Boolean.getBoolean("astd.devStorageAcceptance")) {
            throw new IllegalStateException("Campaign automation cannot share a mission/storage automation driver");
        }
        runId = required("runId");
        if (!UUID.fromString(runId).toString().equals(runId)) {
            throw new IllegalArgumentException("runId must be a canonical UUID");
        }
        scenario = required("scenario");
        phase = required("phase");
        if (!phase.equals("run") && !phase.equals("reload")) {
            throw new IllegalArgumentException("phase must be run or reload");
        }
        saveDir = Path.of(required("saveDir"));
        outputDir = Path.of(required("outputDir"));
        final Path runRoot = saveDir.getParent();
        if (!saveDir.isAbsolute() || !outputDir.isAbsolute() || runRoot == null
                || !saveDir.toRealPath().equals(saveDir) || !outputDir.toRealPath().equals(outputDir)
                || !saveDir.getFileName().toString().equals("save")
                || !runRoot.getFileName().toString().equals(runId)
                || !outputDir.equals(runRoot.resolve(phase))
                || !Path.of(StarfarerSettings.getSavesPath()).toRealPath().equals(runRoot)) {
            throw new IllegalArgumentException("Refusing campaign IO outside the isolated runId workspace");
        }
        if (!Files.isRegularFile(saveDir.resolve("descriptor.xml"))) {
            throw new IllegalArgumentException("Campaign descriptor is missing in the isolated save");
        }
        if (scenario.equals("campaign_world_indevo") && !Global.getSettings().getModManager().isModEnabled("IndEvo")) {
            throw new IllegalStateException("IndEvo must actually be loaded for campaign_world_indevo; enabled_mods is not changed");
        }
        StarfarerSettings.setAutosaveEnabled(false);
        System.clearProperty(CHECKPOINT_REQUEST);
        System.clearProperty(CAPTURE_REQUEST);
        if (phase.equals("reload")) {
            System.setProperty(CAPTURE_REQUEST, runId);
        }
        System.setProperty(CHECKPOINT_STATUS, "idle");
        System.setProperty(CAPTURE_STATUS, "idle");
        return saveDir.toString();
    }

    public static void afterLoad() {
        CampaignEngine.getInstance().setSaveDirName(saveDir.getFileName().toString());
        System.out.println(identity() + " loaded saveDir=" + saveDir);
    }

    /** Request a screenshot followed by a real save on the next safe campaign frame. */
    public static void requestCheckpoint() {
        System.setProperty(CHECKPOINT_REQUEST, required("runId"));
    }

    /** Request a screenshot without saving; reload verification uses this signal. */
    public static void capture() {
        System.setProperty(CAPTURE_REQUEST, required("runId"));
    }

    public static void afterRender(final Object state) {
        if (!isEnabled() || runId == null || failed || saving || !(state instanceof CampaignState campaignState)
                || campaignState.isTransitioningToNextState()) {
            return;
        }
        try {
            if (!checkpointCaptured && !checkpointSaved && runId.equals(System.getProperty(CHECKPOINT_REQUEST))) {
                if (!phase.equals("run")) {
                    throw new IllegalStateException("Checkpoint save is only valid in phase=run");
                }
                writeScreenshot(outputDir.resolve("checkpoint.png"));
                checkpointCaptured = true;
                System.setProperty(CHECKPOINT_STATUS, "captured");
            }
            if (!captured && runId.equals(System.getProperty(CAPTURE_REQUEST))) {
                writeScreenshot(outputDir.resolve("capture.png"));
                captured = true;
                System.setProperty(CAPTURE_STATUS, "captured");
                System.out.println(identity() + " capture completed");
            }
        } catch (final Exception ex) {
            fail(ex);
        }
    }

    public static void afterAdvance(final Object state) {
        if (!isEnabled() || runId == null || failed || saving || !checkpointCaptured || checkpointSaved
                || !(state instanceof CampaignState campaignState) || campaignState.isTransitioningToNextState()
                || campaignState.isShowingDialog() || campaignState.isShowingMenu()) {
            return;
        }
        try {
            saving = true;
            final Path target = Path.of(StarfarerSettings.getSavesPath())
                    .resolve(CampaignEngine.getInstance().getSaveDirName()).toRealPath();
            if (!saveDir.equals(target)) {
                throw new IllegalStateException("Refusing save to unexpected directory: " + target);
            }
            System.setProperty(CHECKPOINT_STATUS, "saving");
            final String error = CampaignGameManager.saveGame(campaignState);
            if (error != null) {
                throw new IllegalStateException("CampaignGameManager.saveGame failed: " + error);
            }
            if (CampaignGameManager.getLastSaveTime() <= 0
                    || !Files.isRegularFile(saveDir.resolve("descriptor.xml"))
                    || (!Files.isRegularFile(saveDir.resolve("campaign.xml"))
                    && !Files.isRegularFile(saveDir.resolve("campaign.zip")))) {
                throw new IllegalStateException("Save returned without a valid campaign file");
            }
            checkpointSaved = true;
            System.setProperty(CHECKPOINT_STATUS, "saved");
            System.out.println(identity() + " checkpoint saved");
        } catch (final Exception ex) {
            fail(ex);
        } finally {
            saving = false;
        }
    }

    private static void writeScreenshot(final Path path) throws Exception {
        if (!Display.isCreated() || !Display.isCurrent()) {
            throw new IllegalStateException("Campaign render callback does not own the display GL context");
        }
        final int width = Display.getWidth();
        final int height = Display.getHeight();
        final ByteBuffer pixels = BufferUtils.createByteBuffer(Math.multiplyExact(Math.multiplyExact(width, height), 4));
        final int readBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
        GL11.glPushClientAttrib(GL11.GL_CLIENT_PIXEL_STORE_BIT);
        try {
            GL11.glReadBuffer(GL11.GL_BACK);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
            GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            final int error = GL11.glGetError();
            if (error != GL11.GL_NO_ERROR) {
                throw new IllegalStateException("GL screenshot failed with error " + error);
            }
        } finally {
            GL11.glReadBuffer(readBuffer);
            GL11.glPopClientAttrib();
        }
        final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final int index = (y * width + x) * 4;
                image.setRGB(x, height - y - 1, (pixels.get(index) & 255) << 16
                        | (pixels.get(index + 1) & 255) << 8 | pixels.get(index + 2) & 255);
            }
        }
        try (OutputStream stream = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW)) {
            if (!ImageIO.write(image, "png", stream)) {
                throw new IOException("No PNG writer is available");
            }
        }
    }

    private static void fail(final Exception ex) {
        failed = true;
        System.setProperty(CHECKPOINT_STATUS, "failed");
        System.setProperty(CAPTURE_STATUS, "failed");
        System.setProperty(PREFIX + "io.error", ex.toString());
        System.err.println(identity() + " io failed: " + ex);
        ex.printStackTrace(System.err);
    }

    private static String identity() {
        return "[ASTD-CampaignAutomation-IO] runId=" + runId + " scenario=" + scenario + " phase=" + phase;
    }

    private static String required(final String key) {
        final String value = System.getProperty(PREFIX + key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing -D" + PREFIX + key);
        }
        return value;
    }
}
