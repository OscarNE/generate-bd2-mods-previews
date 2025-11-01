import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.utils.BufferUtils;
import com.esotericsoftware.spine.AnimationState;
import com.esotericsoftware.spine.AnimationStateData;
import com.esotericsoftware.spine.Skeleton;
import com.esotericsoftware.spine.SkeletonBinary;
import com.esotericsoftware.spine.SkeletonData;
import com.esotericsoftware.spine.SkeletonJson;
import com.esotericsoftware.spine.SkeletonRenderer;
import com.esotericsoftware.spine.Skin;
import com.esotericsoftware.spine.Slot;
import com.esotericsoftware.spine.attachments.Attachment;
import com.esotericsoftware.spine.attachments.MeshAttachment;
import com.esotericsoftware.spine.attachments.RegionAttachment;
import com.esotericsoftware.spine.utils.TwoColorPolygonBatch;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.stream.Stream;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Java2DFrameConverter;

public class App extends ApplicationAdapter {

    private static final Method UPDATE_WORLD_TRANSFORM_WITH_PHYSICS;
    private static final Method UPDATE_WORLD_TRANSFORM_NO_ARGS;
    private static final Object PHYSICS_UPDATE_ENUM;
    private static final Method SKELETON_UPDATE_METHOD;

    static {
        Method worldTransformMethod = null;
        Method worldTransformNoArgs = null;
        Object physicsUpdateEnum = null;
        try {
            Class<?> physicsClass = Class.forName(
                "com.esotericsoftware.spine.Skeleton$Physics"
            );
            @SuppressWarnings("unchecked")
            Enum<?> update = Enum.valueOf((Class<Enum>) physicsClass, "Update");
            worldTransformMethod = Skeleton.class.getMethod(
                "updateWorldTransform",
                physicsClass
            );
            physicsUpdateEnum = update;
        } catch (
            ClassNotFoundException
            | NoSuchMethodException
            | IllegalArgumentException ignored
        ) {
            worldTransformMethod = null;
            physicsUpdateEnum = null;
        }

        try {
            worldTransformNoArgs = Skeleton.class.getMethod(
                "updateWorldTransform"
            );
        } catch (NoSuchMethodException ignored) {
            worldTransformNoArgs = null;
        }

        Method updateMethod;
        try {
            updateMethod = Skeleton.class.getMethod("update", float.class);
        } catch (NoSuchMethodException ignored) {
            updateMethod = null;
        }

        UPDATE_WORLD_TRANSFORM_WITH_PHYSICS = worldTransformMethod;
        UPDATE_WORLD_TRANSFORM_NO_ARGS = worldTransformNoArgs;
        PHYSICS_UPDATE_ENUM = physicsUpdateEnum;
        SKELETON_UPDATE_METHOD = updateMethod;
    }

    private static final String LOG_PREFIX = "[create-preview]";
    private static final String SPINE_RUNTIME_VERSION = "4.1.0";

    private static void logInfo(String message) {
        System.out.println(LOG_PREFIX + " INFO  " + message);
    }

    private static void logWarn(String message) {
        System.out.println(LOG_PREFIX + " WARN  " + message);
    }

    private static void logError(String message, Throwable error) {
        System.err.println(LOG_PREFIX + " ERROR " + message);
        if (error != null) {
            error.printStackTrace(System.err);
        }
    }

    private final CliArguments arguments;
    private TextureAtlas atlas;
    private Skeleton skeleton;
    private SkeletonRenderer renderer;
    private TwoColorPolygonBatch batch;
    private OrthographicCamera camera;
    private FrameBuffer frameBuffer;
    private AnimationState animationState;
    private int outputWidth;
    private int outputHeight;
    private boolean exported;
    private float[] worldVerticesBuffer = new float[64];
    private Java2DFrameConverter frameConverter;

    public App(CliArguments arguments) {
        this.arguments = arguments;
    }

    @Override
    public void create() {
        FileHandle atlasHandle = Gdx.files.absolute(
            arguments.atlasPath().toString()
        );
        logInfo("Loading atlas: " + atlasHandle.path());
        FileHandle skeletonHandle = Gdx.files.absolute(
            arguments.skeletonPath().toString()
        );
        logInfo("Loading skeleton data: " + skeletonHandle.path());
        FileHandle texturesDirHandle = Gdx.files.absolute(
            arguments.textureDirectory().toString()
        );
        logInfo("Loading textures from: " + texturesDirHandle.path());
        atlas = new TextureAtlas(atlasHandle, texturesDirHandle);

        SkeletonData skeletonData = readSkeletonData(
            arguments.skeletonPath(),
            arguments.scale()
        );
        if (
            arguments.animationFile() != null &&
            !arguments.animationFile().equals(arguments.skeletonPath())
        ) {
            SkeletonData animationData = readSkeletonData(
                arguments.animationFile(),
                arguments.scale()
            );
            skeletonData.getAnimations().clear();
            skeletonData.getAnimations().addAll(animationData.getAnimations());
        }

        skeleton = new Skeleton(skeletonData);
        applySkin(arguments.skinName(), skeletonData);
        skeleton.setToSetupPose();
        logInfo(
            "Skeleton ready with " +
                skeletonData.getBones().size +
                " bones and " +
                skeletonData.getAnimations().size +
                " animation(s)."
        );

        String selectedAnimation = arguments.animationName();
        if (
            (selectedAnimation == null || selectedAnimation.isEmpty()) &&
            skeletonData.getAnimations().size > 0
        ) {
            selectedAnimation = skeletonData.getAnimations().first().getName();
        }

        if (selectedAnimation != null && !selectedAnimation.isEmpty()) {
            AnimationStateData stateData = new AnimationStateData(skeletonData);
            animationState = new AnimationState(stateData);
            boolean loop = arguments.videoSeconds() > 0f;
            animationState.setAnimation(0, selectedAnimation, loop);
            logInfo(
                "Selected animation: " +
                    selectedAnimation +
                    (loop ? " (looping)" : "")
            );
        }

        float initialTime = Math.max(0f, arguments.animationTime());
        if (initialTime > 0f) {
            advanceAnimation(initialTime);
        } else {
            applyWorldTransform(skeleton);
        }

        GeometryBounds geometryBounds = computeGeometryBounds(skeleton);
        float width = geometryBounds.hasGeometry()
            ? geometryBounds.width()
            : arguments.minOutputSize();
        float height = geometryBounds.hasGeometry()
            ? geometryBounds.height()
            : arguments.minOutputSize();

        if (arguments.overrideWidth() != null) {
            width = arguments.overrideWidth();
        }
        if (arguments.overrideHeight() != null) {
            height = arguments.overrideHeight();
        }

        outputWidth = Math.max(arguments.minOutputSize(), Math.round(width));
        outputHeight = Math.max(arguments.minOutputSize(), Math.round(height));

        if ((outputWidth & 1) == 1) {
            outputWidth++;
        }
        if ((outputHeight & 1) == 1) {
            outputHeight++;
        }

        logInfo("Render resolution set to " + outputWidth + "x" + outputHeight);

        if (geometryBounds.hasGeometry()) {
            float translateX =
                -geometryBounds.minX() +
                (outputWidth - geometryBounds.width()) / 2f;
            float translateY =
                -geometryBounds.minY() +
                (outputHeight - geometryBounds.height()) / 2f;
            skeleton.setPosition(translateX, translateY);
            applyWorldTransform(skeleton);
        }

        camera = new OrthographicCamera();
        camera.setToOrtho(false, outputWidth, outputHeight);
        camera.position.set(outputWidth / 2f, outputHeight / 2f, 0f);
        camera.update();

        batch = new TwoColorPolygonBatch();
        renderer = new SkeletonRenderer();
        renderer.setPremultipliedAlpha(false);
        frameConverter = new Java2DFrameConverter();

        frameBuffer = new FrameBuffer(
            Pixmap.Format.RGBA8888,
            outputWidth,
            outputHeight,
            true
        );

        if (arguments.videoSeconds() > 0f) {
            renderVideoSequence();
        } else {
            renderStillFrame(arguments.outputPath());
        }

        exported = true;
        logInfo("Export completed successfully.");
        Gdx.app.exit();
    }

    @Override
    public void render() {
        // Rendering work is handled synchronously inside create().
    }

    @Override
    public void dispose() {
        if (batch != null) {
            batch.dispose();
        }
        if (atlas != null) {
            atlas.dispose();
        }
        if (frameBuffer != null) {
            frameBuffer.dispose();
        }
    }

    private void renderStillFrame(Path outputPath) {
        Pixmap frame = renderCurrentFrame();
        writePixmap(outputPath, frame);
        frame.dispose();
        logInfo("Preview image written to " + outputPath);
    }

    private void renderVideoSequence() {
        int frames = Math.max(
            1,
            Math.round(arguments.videoSeconds() * arguments.fps())
        );
        float step = 1f / arguments.fps();
        Path framesDir = arguments.framesDir();
        logInfo(
            "Producing video preview (" +
                frames +
                " frame(s) @ " +
                arguments.fps() +
                " fps) -> " +
                arguments.videoOutput()
        );

        if (arguments.outputPath() != null) {
            Pixmap preview = renderCurrentFrame();
            writePixmap(arguments.outputPath(), preview);
            preview.dispose();
        }

        if (arguments.keepFrames()) {
            deleteFrames(framesDir);
            try {
                Files.createDirectories(framesDir);
                logInfo("Saving intermediate frames under " + framesDir);
            } catch (IOException e) {
                throw new IllegalStateException(
                    "Unable to create frames directory: " + framesDir,
                    e
                );
            }
        } else {
            deleteFrames(framesDir);
            logInfo("Intermediate frames will be discarded after encoding.");
        }

        FFmpegFrameRecorder recorder = null;
        Exception encodeError = null;
        try {
            recorder = startRecorder(
                arguments.videoOutput(),
                outputWidth,
                outputHeight,
                arguments.fps()
            );
            for (int i = 0; i < frames; i++) {
                Pixmap framePixmap = renderCurrentFrame();

                if (arguments.keepFrames()) {
                    Path framePath = framesDir.resolve(
                        String.format(Locale.ROOT, "frame_%05d.png", i)
                    );
                    writePixmap(framePath, framePixmap);
                }

                recordFrame(recorder, framePixmap);
                framePixmap.dispose();

                if (i < frames - 1) {
                    advanceAnimation(step);
                }
            }
        } catch (Exception ex) {
            encodeError = ex;
        } finally {
            if (recorder != null) {
                try {
                    recorder.stop();
                } catch (Exception stopEx) {
                    if (encodeError != null) {
                        encodeError.addSuppressed(stopEx);
                    } else {
                        encodeError = stopEx;
                    }
                }
                try {
                    recorder.release();
                } catch (Exception releaseEx) {
                    if (encodeError != null) {
                        encodeError.addSuppressed(releaseEx);
                    } else {
                        encodeError = releaseEx;
                    }
                }
            }
        }

        if (encodeError != null) {
            logError(
                "Failed to encode video: " + encodeError.getMessage(),
                encodeError
            );
            throw new IllegalStateException(
                "Failed to encode video",
                encodeError
            );
        }

        logInfo("Video preview written to " + arguments.videoOutput());

        if (!arguments.keepFrames()) {
            deleteFrames(framesDir);
        }
    }

    private FFmpegFrameRecorder startRecorder(
        Path output,
        int width,
        int height,
        int fps
    ) throws Exception {
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(
            output.toString(),
            width,
            height
        );
        long pixelsPerSecond = (long) width * height * Math.max(1, fps);
        long targetBitrate = Math.max(2_000_000L, (pixelsPerSecond * 3L) / 4L);
        if (targetBitrate > Integer.MAX_VALUE) {
            targetBitrate = Integer.MAX_VALUE;
        }
        recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
        recorder.setFormat("mp4");
        recorder.setFrameRate(fps);
        recorder.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);
        recorder.setVideoBitrate((int) targetBitrate);
        recorder.setVideoOption("crf", "18");
        recorder.setVideoOption("preset", "medium");
        recorder.setVideoOption("profile", "high");
        recorder.setVideoOption("tune", "animation");
        recorder.setVideoOption("movflags", "+faststart");
        logInfo(
            "Video encoder configured at ~" + (targetBitrate / 1000) + " kbps"
        );
        recorder.start();
        return recorder;
    }

    private void recordFrame(FFmpegFrameRecorder recorder, Pixmap pixmap)
        throws Exception {
        if (frameConverter == null) {
            frameConverter = new Java2DFrameConverter();
        }
        recorder.record(frameConverter.convert(pixmapToBufferedImage(pixmap)));
    }

    private BufferedImage pixmapToBufferedImage(Pixmap pixmap) {
        BufferedImage image = new BufferedImage(
            pixmap.getWidth(),
            pixmap.getHeight(),
            BufferedImage.TYPE_3BYTE_BGR
        );
        byte[] bgr = ((DataBufferByte) image
                .getRaster()
                .getDataBuffer()).getData();

        ByteBuffer pixels = pixmap.getPixels().duplicate();
        pixels.rewind();

        int pixelCount = pixmap.getWidth() * pixmap.getHeight();
        int dst = 0;
        for (int i = 0; i < pixelCount; i++) {
            byte r = pixels.get();
            byte g = pixels.get();
            byte b = pixels.get();
            pixels.get(); // alpha, unused

            bgr[dst++] = b;
            bgr[dst++] = g;
            bgr[dst++] = r;
        }

        return image;
    }

    private Pixmap renderCurrentFrame() {
        frameBuffer.begin();

        Gdx.gl.glViewport(0, 0, outputWidth, outputHeight);
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);

        batch.setProjectionMatrix(camera.combined);
        batch.begin();
        renderer.draw(batch, skeleton);
        batch.end();

        Pixmap pixmap = captureFrameBuffer(outputWidth, outputHeight);
        frameBuffer.end();

        Pixmap flipped = flipPixmapVertically(pixmap);
        pixmap.dispose();
        return flipped;
    }

    private void writePixmap(Path path, Pixmap pixmap) {
        FileHandle handle = Gdx.files.absolute(path.toString());
        if (handle.parent() != null && !handle.parent().exists()) {
            handle.parent().mkdirs();
        }
        PixmapIO.writePNG(handle, pixmap);
    }

    private void deleteFrames(Path framesDir) {
        if (!Files.exists(framesDir)) {
            return;
        }

        try {
            Files.walk(framesDir)
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ex) {
                        throw new IllegalStateException(
                            "Failed to delete frame path: " + path,
                            ex
                        );
                    }
                });
        } catch (IOException ex) {
            throw new IllegalStateException(
                "Failed to clean frames directory: " + framesDir,
                ex
            );
        }
    }

    private void applySkin(String requestedSkin, SkeletonData skeletonData) {
        if (requestedSkin == null || requestedSkin.isEmpty()) {
            Skin defaultSkin = skeletonData.getDefaultSkin();
            if (defaultSkin != null) {
                skeleton.setSkin(defaultSkin);
            }
        } else if (requestedSkin.contains(",")) {
            Skin combined = new Skin("combined");
            for (String name : requestedSkin.split(",")) {
                String trimmed = name.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                Skin part = skeletonData.findSkin(trimmed);
                if (part == null) {
                    throw new IllegalArgumentException(
                        "Skin not found: " + trimmed
                    );
                }
                combined.addSkin(part);
            }
            skeleton.setSkin(combined);
        } else {
            Skin skin = skeletonData.findSkin(requestedSkin);
            if (skin == null) {
                throw new IllegalArgumentException(
                    "Skin not found: " + requestedSkin
                );
            }
            skeleton.setSkin(skin);
        }

        skeleton.setSlotsToSetupPose();
    }

    private void advanceAnimation(float delta) {
        if (delta > 0f) {
            advanceSkeleton(skeleton, delta);
            if (animationState != null) {
                animationState.update(delta);
            }
        }
        if (animationState != null) {
            animationState.apply(skeleton);
        }
        applyWorldTransform(skeleton);
    }

    private void advanceSkeleton(Skeleton target, float delta) {
        if (delta <= 0f || SKELETON_UPDATE_METHOD == null) {
            return;
        }
        try {
            SKELETON_UPDATE_METHOD.invoke(target, delta);
        } catch (IllegalAccessException | InvocationTargetException ex) {
            throw new IllegalStateException(
                "Unable to invoke Skeleton.update(float)",
                ex
            );
        }
    }

    private Pixmap flipPixmapVertically(Pixmap pixmap) {
        Pixmap flipped = new Pixmap(
            pixmap.getWidth(),
            pixmap.getHeight(),
            pixmap.getFormat()
        );
        Pixmap.Blending old = flipped.getBlending();
        flipped.setBlending(Pixmap.Blending.None);
        for (int y = 0; y < pixmap.getHeight(); y++) {
            flipped.drawPixmap(
                pixmap,
                0,
                pixmap.getHeight() - y - 1,
                pixmap.getWidth(),
                1,
                0,
                y,
                pixmap.getWidth(),
                1
            );
        }
        flipped.setBlending(old);
        return flipped;
    }

    private GeometryBounds computeGeometryBounds(Skeleton skeleton) {
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        boolean foundGeometry = false;

        for (Slot slot : skeleton.getSlots()) {
            Attachment attachment = slot.getAttachment();
            if (attachment == null) {
                continue;
            }

            if (attachment instanceof RegionAttachment) {
                RegionAttachment region = (RegionAttachment) attachment;
                ensureWorldVerticesCapacity(8);
                region.computeWorldVertices(slot, worldVerticesBuffer, 0, 2);
                for (int i = 0; i < 8; i += 2) {
                    float x = worldVerticesBuffer[i];
                    float y = worldVerticesBuffer[i + 1];
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
                foundGeometry = true;
            } else if (attachment instanceof MeshAttachment) {
                MeshAttachment mesh = (MeshAttachment) attachment;
                int vertexCount = mesh.getWorldVerticesLength();
                if (vertexCount <= 0) {
                    continue;
                }
                ensureWorldVerticesCapacity(vertexCount);
                mesh.computeWorldVertices(
                    slot,
                    0,
                    vertexCount,
                    worldVerticesBuffer,
                    0,
                    2
                );
                for (int i = 0; i < vertexCount; i += 2) {
                    float x = worldVerticesBuffer[i];
                    float y = worldVerticesBuffer[i + 1];
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
                foundGeometry = true;
            }
        }

        if (!foundGeometry) {
            return GeometryBounds.empty();
        }

        return GeometryBounds.of(minX, minY, maxX, maxY);
    }

    private void ensureWorldVerticesCapacity(int required) {
        if (worldVerticesBuffer.length < required) {
            worldVerticesBuffer = Arrays.copyOf(worldVerticesBuffer, required);
        }
    }

    private Pixmap captureFrameBuffer(int width, int height) {
        int amount = width * height * 4;
        ByteBuffer pixels = BufferUtils.newByteBuffer(amount);
        Gdx.gl.glFinish();
        Gdx.gl20.glPixelStorei(GL20.GL_PACK_ALIGNMENT, 1);
        Gdx.gl20.glReadPixels(
            0,
            0,
            width,
            height,
            GL20.GL_RGBA,
            GL20.GL_UNSIGNED_BYTE,
            pixels
        );
        pixels.rewind();
        Pixmap pixmap = new Pixmap(width, height, Pixmap.Format.RGBA8888);
        BufferUtils.copy(pixels, pixmap.getPixels(), amount);
        return pixmap;
    }

    private void applyWorldTransform(Skeleton target) {
        if (
            UPDATE_WORLD_TRANSFORM_WITH_PHYSICS != null &&
            PHYSICS_UPDATE_ENUM != null
        ) {
            try {
                UPDATE_WORLD_TRANSFORM_WITH_PHYSICS.invoke(
                    target,
                    PHYSICS_UPDATE_ENUM
                );
                return;
            } catch (IllegalAccessException | InvocationTargetException ex) {
                throw new IllegalStateException(
                    "Unable to invoke Skeleton.updateWorldTransform(Physics)",
                    ex
                );
            }
        }
        if (UPDATE_WORLD_TRANSFORM_NO_ARGS != null) {
            try {
                UPDATE_WORLD_TRANSFORM_NO_ARGS.invoke(target);
                return;
            } catch (IllegalAccessException | InvocationTargetException ex) {
                throw new IllegalStateException(
                    "Unable to invoke Skeleton.updateWorldTransform()",
                    ex
                );
            }
        }
        throw new IllegalStateException(
            "No compatible Skeleton.updateWorldTransform method found"
        );
    }

    private SkeletonData readSkeletonData(Path path, float scale) {
        FileHandle handle = Gdx.files.absolute(path.toString());
        String lower = path.toString().toLowerCase(Locale.ROOT);
        try {
            if (lower.endsWith(".json")) {
                SkeletonJson json = new SkeletonJson(atlas);
                json.setScale(scale);
                return json.readSkeletonData(handle);
            }
            SkeletonBinary binary = new SkeletonBinary(atlas);
            binary.setScale(scale);
            return binary.readSkeletonData(handle);
        } catch (RuntimeException ex) {
            String message =
                "Failed to read skeleton data from " +
                path +
                ". Ensure the file was exported for Spine runtime " +
                SPINE_RUNTIME_VERSION +
                ".";
            logError(message, ex);
            throw new IllegalStateException(message, ex);
        }
    }

    public boolean exportedSuccessfully() {
        return exported;
    }

    public static void main(String[] args) {
        CliArguments cliArguments;
        try {
            cliArguments = CliArguments.parse(args);
        } catch (IllegalArgumentException ex) {
            logError(ex.getMessage(), null);
            CliArguments.printUsage();
            return;
        }

        Lwjgl3ApplicationConfiguration config =
            new Lwjgl3ApplicationConfiguration();
        config.setTitle("Spine Preview Generator");
        config.useVsync(false);
        config.setForegroundFPS(0);
        config.setIdleFPS(0);
        config.setWindowedMode(
            cliArguments.initialWindowWidth(),
            cliArguments.initialWindowHeight()
        );
        config.setResizable(false);
        config.setDecorated(false);
        config.setInitialVisible(false);
        config.disableAudio(true);

        App app = new App(cliArguments);
        logInfo(
            "Starting preview generation for atlas " + cliArguments.atlasPath()
        );
        try {
            new Lwjgl3Application(app, config);
        } catch (Throwable ex) {
            logError("Preview generation failed: " + ex.getMessage(), ex);
            return;
        }

        if (!app.exportedSuccessfully()) {
            logWarn("Generator exited before producing an output file.");
        } else {
            logInfo("Preview generation finished.");
        }
    }

    public static final class CliArguments {

        private static final int DEFAULT_WINDOW_SIZE = 128;
        private static final int DEFAULT_MIN_OUTPUT_SIZE = 128;

        private final Path atlasPath;
        private final Path skeletonPath;
        private final Path texturePath;
        private final Path textureDirectory;
        private final Path outputPath;
        private final float scale;
        private final Integer overrideWidth;
        private final Integer overrideHeight;
        private final String skinName;
        private final String animationName;
        private final float animationTime;
        private final int initialWindowWidth;
        private final int initialWindowHeight;
        private final int minOutputSize;
        private final float videoSeconds;
        private final int fps;
        private final Path videoOutput;
        private final boolean keepFrames;
        private final Path framesDir;
        private final Path animationFile;
        private final Path folder;

        private CliArguments(
            Path atlasPath,
            Path skeletonPath,
            Path texturePath,
            Path textureDirectory,
            Path outputPath,
            float scale,
            Integer overrideWidth,
            Integer overrideHeight,
            String skinName,
            String animationName,
            float animationTime,
            int initialWindowWidth,
            int initialWindowHeight,
            int minOutputSize,
            float videoSeconds,
            int fps,
            Path videoOutput,
            boolean keepFrames,
            Path framesDir,
            Path animationFile,
            Path folder
        ) {
            this.atlasPath = atlasPath;
            this.skeletonPath = skeletonPath;
            this.texturePath = texturePath;
            this.textureDirectory = textureDirectory;
            this.outputPath = outputPath;
            this.scale = scale;
            this.overrideWidth = overrideWidth;
            this.overrideHeight = overrideHeight;
            this.skinName = skinName;
            this.animationName = animationName;
            this.animationTime = animationTime;
            this.initialWindowWidth = initialWindowWidth;
            this.initialWindowHeight = initialWindowHeight;
            this.minOutputSize = minOutputSize;
            this.videoSeconds = videoSeconds;
            this.fps = fps;
            this.videoOutput = videoOutput;
            this.keepFrames = keepFrames;
            this.framesDir = framesDir;
            this.animationFile = animationFile;
            this.folder = folder;
        }

        public static CliArguments parse(String[] args) {
            Path atlas = null;
            Path skeleton = null;
            Path texture = null;
            Path output = null;
            float scale = 1f;
            Integer forcedWidth = null;
            Integer forcedHeight = null;
            String animation = null;
            String animationCandidate = null;
            String skin = null;
            float animationTime = 0f;
            int windowWidth = DEFAULT_WINDOW_SIZE;
            int windowHeight = DEFAULT_WINDOW_SIZE;
            int minOutputSize = DEFAULT_MIN_OUTPUT_SIZE;
            float videoSeconds = 0f;
            int fps = 30;
            Path videoOutput = null;
            boolean keepFrames = false;
            Path folder = null;
            Path animationFile = null;

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--atlas":
                    case "-a":
                        atlas = nextPath(args, ++i, arg);
                        break;
                    case "--skeleton":
                    case "--skel":
                    case "-s":
                        skeleton = nextPath(args, ++i, arg);
                        break;
                    case "--texture":
                    case "--png":
                    case "-t":
                        texture = nextPath(args, ++i, arg);
                        break;
                    case "--output":
                    case "-o":
                        output = nextPath(args, ++i, arg);
                        break;
                    case "--scale":
                        scale = Float.parseFloat(nextValue(args, ++i, arg));
                        break;
                    case "--width":
                        forcedWidth = Integer.parseInt(
                            nextValue(args, ++i, arg)
                        );
                        break;
                    case "--height":
                        forcedHeight = Integer.parseInt(
                            nextValue(args, ++i, arg)
                        );
                        break;
                    case "--skin":
                        skin = nextValue(args, ++i, arg);
                        break;
                    case "--animation":
                        animationCandidate = nextValue(args, ++i, arg);
                        break;
                    case "--time":
                        animationTime = Float.parseFloat(
                            nextValue(args, ++i, arg)
                        );
                        break;
                    case "--window-width":
                        windowWidth = Integer.parseInt(
                            nextValue(args, ++i, arg)
                        );
                        break;
                    case "--window-height":
                        windowHeight = Integer.parseInt(
                            nextValue(args, ++i, arg)
                        );
                        break;
                    case "--min-output":
                        minOutputSize = Integer.parseInt(
                            nextValue(args, ++i, arg)
                        );
                        break;
                    case "--video-seconds":
                        videoSeconds = Float.parseFloat(
                            nextValue(args, ++i, arg)
                        );
                        break;
                    case "--fps":
                        fps = Integer.parseInt(nextValue(args, ++i, arg));
                        break;
                    case "--video-output":
                        videoOutput = nextPath(args, ++i, arg);
                        break;
                    case "--keep-frames":
                        keepFrames = true;
                        break;
                    case "--folder":
                        folder = nextPath(args, ++i, arg);
                        break;
                    default:
                        throw new IllegalArgumentException(
                            "Unknown argument: " + arg
                        );
                }
            }

            if (folder != null) {
                folder = folder.toAbsolutePath().normalize();
                if (!Files.isDirectory(folder)) {
                    throw new IllegalArgumentException(
                        "Folder is not a directory: " + folder
                    );
                }
                atlas = atlas != null
                    ? atlas
                    : findFirstWithExtension(folder, "atlas", ".atlas");
                skeleton = skeleton != null
                    ? skeleton
                    : findFirstWithExtension(
                          folder,
                          "skeleton",
                          ".skel",
                          ".json"
                      );
                if (texture == null) {
                    Path png = findFirstPng(folder);
                    if (png != null) {
                        texture = png;
                    } else {
                        throw new IllegalArgumentException(
                            "No .png textures found in folder " + folder
                        );
                    }
                }
            }

            if (animationCandidate != null) {
                Path candidatePath = Paths.get(animationCandidate);
                Path resolvedPath = null;
                if (Files.exists(candidatePath)) {
                    resolvedPath = candidatePath;
                } else if (folder != null) {
                    Path folderCandidate = folder.resolve(animationCandidate);
                    if (Files.exists(folderCandidate)) {
                        resolvedPath = folderCandidate;
                    }
                }
                if (resolvedPath != null) {
                    animationFile = toAbsoluteExistingFile(
                        resolvedPath,
                        "--animation",
                        ".skel",
                        ".json"
                    );
                } else {
                    animation = animationCandidate;
                }
            }

            if (skeleton == null && animationFile != null) {
                skeleton = animationFile;
            }

            if (atlas == null || skeleton == null || texture == null) {
                throw new IllegalArgumentException(
                    "Missing required arguments. Expected --atlas, --skeleton, and --texture."
                );
            }

            atlas = toAbsoluteExistingFile(atlas, "--atlas", ".atlas");
            skeleton = toAbsoluteExistingFile(
                skeleton,
                "--skeleton",
                ".skel",
                ".json"
            );
            texture = texture.toAbsolutePath().normalize();
            if (!Files.exists(texture)) {
                throw new IllegalArgumentException(
                    "Texture not found: " + texture
                );
            }

            Path textureDir = Files.isDirectory(texture)
                ? texture
                : texture.getParent();
            if (textureDir == null) {
                textureDir = texture.getRoot();
            }
            if (textureDir == null) {
                textureDir = Paths.get(".");
            }

            if (output == null) {
                output = defaultOutputPath(atlas);
            } else {
                output = output.toAbsolutePath().normalize();
            }

            videoSeconds = Math.max(0f, videoSeconds);
            fps = Math.max(1, fps);

            Path normalizedFramesDir = defaultFramesDir(output)
                .toAbsolutePath()
                .normalize();

            if (videoOutput == null) {
                videoOutput = defaultVideoPath(output);
            }
            videoOutput = videoOutput.toAbsolutePath().normalize();

            return new CliArguments(
                atlas,
                skeleton,
                texture,
                textureDir.toAbsolutePath().normalize(),
                output,
                scale,
                forcedWidth,
                forcedHeight,
                skin,
                animation,
                animationTime,
                Math.max(1, windowWidth),
                Math.max(1, windowHeight),
                Math.max(1, minOutputSize),
                videoSeconds,
                fps,
                videoOutput,
                keepFrames,
                normalizedFramesDir,
                animationFile,
                folder
            );
        }

        private static Path nextPath(String[] args, int index, String flag) {
            String value = nextValue(args, index, flag);
            try {
                return Paths.get(value);
            } catch (InvalidPathException ex) {
                throw new IllegalArgumentException(
                    flag + " must be a valid filesystem path: " + value,
                    ex
                );
            }
        }

        private static String nextValue(String[] args, int index, String flag) {
            if (index >= args.length) {
                throw new IllegalArgumentException(
                    "Expected a value after " + flag
                );
            }
            return args[index];
        }

        private static Path toAbsoluteExistingFile(
            Path path,
            String flag,
            String... expectedExtensions
        ) {
            Path absolute = path.toAbsolutePath().normalize();
            if (!Files.exists(absolute)) {
                throw new IllegalArgumentException(
                    "File not found for " + flag + ": " + absolute
                );
            }
            if (expectedExtensions.length > 0) {
                String lower = absolute.toString().toLowerCase(Locale.ROOT);
                boolean matches = Arrays.stream(expectedExtensions)
                    .map(ext -> ext.toLowerCase(Locale.ROOT))
                    .anyMatch(lower::endsWith);
                if (!matches) {
                    throw new IllegalArgumentException(
                        flag +
                            " must point to a file with one of extensions " +
                            Arrays.toString(expectedExtensions)
                    );
                }
            }
            return absolute;
        }

        private static Path defaultOutputPath(Path atlas) {
            String fileName = atlas.getFileName().toString();
            int lastDot = fileName.lastIndexOf('.');
            String stem = lastDot >= 0
                ? fileName.substring(0, lastDot)
                : fileName;
            Path parent = atlas.getParent();
            if (parent == null) {
                parent = Paths.get(".");
            }
            return parent
                .resolve(stem + "-preview.png")
                .toAbsolutePath()
                .normalize();
        }

        private static Path defaultVideoPath(Path output) {
            String fileName = output.getFileName().toString();
            int lastDot = fileName.lastIndexOf('.');
            String stem = lastDot >= 0
                ? fileName.substring(0, lastDot)
                : fileName;
            Path parent = output.getParent();
            if (parent == null) {
                parent = Paths.get(".");
            }
            return parent.resolve(stem + ".mp4").toAbsolutePath().normalize();
        }

        private static Path defaultFramesDir(Path output) {
            String fileName = output.getFileName().toString();
            int lastDot = fileName.lastIndexOf('.');
            String stem = lastDot >= 0
                ? fileName.substring(0, lastDot)
                : fileName;
            Path parent = output.getParent();
            if (parent == null) {
                parent = Paths.get(".");
            }
            return parent
                .resolve(stem + "_frames")
                .toAbsolutePath()
                .normalize();
        }

        private static Path findFirstWithExtension(
            Path dir,
            String description,
            String... extensions
        ) {
            String[] lowers = Arrays.stream(extensions)
                .map(ext -> ext.toLowerCase(Locale.ROOT))
                .toArray(String[]::new);
            try (Stream<Path> stream = Files.walk(dir, 1)) {
                return stream
                    .filter(Files::isRegularFile)
                    .filter(path ->
                        Arrays.stream(lowers).anyMatch(ext ->
                            path
                                .getFileName()
                                .toString()
                                .toLowerCase(Locale.ROOT)
                                .endsWith(ext)
                        )
                    )
                    .findFirst()
                    .map(path -> path.toAbsolutePath().normalize())
                    .orElseThrow(() ->
                        new IllegalArgumentException(
                            "No " + description + " file found in folder " + dir
                        )
                    );
            } catch (IOException ex) {
                throw new IllegalStateException(
                    "Failed to scan folder " + dir,
                    ex
                );
            }
        }

        private static Path findFirstPng(Path dir) {
            try (Stream<Path> stream = Files.walk(dir)) {
                return stream
                    .filter(Files::isRegularFile)
                    .filter(path ->
                        path
                            .getFileName()
                            .toString()
                            .toLowerCase(Locale.ROOT)
                            .endsWith(".png")
                    )
                    .findFirst()
                    .map(path -> path.toAbsolutePath().normalize())
                    .orElse(null);
            } catch (IOException ex) {
                throw new IllegalStateException(
                    "Failed to scan folder " + dir,
                    ex
                );
            }
        }

        public static void printUsage() {
            System.err.println(
                "Usage: java -jar create_preview.jar (--folder path/to/assets | --atlas path/to/atlas.atlas --skeleton path/to/skeleton.{skel|json} --texture path/to/textures.png) " +
                    "[--output output.png] [--scale value] [--width px] [--height px] [--skin name] [--animation name|path] " +
                    "[--time seconds] [--video-seconds seconds] [--fps value] [--video-output output.mp4] [--keep-frames]\n" +
                    "The --texture argument can point to a single PNG file or a directory containing the atlas images."
            );
        }

        public Path atlasPath() {
            return atlasPath;
        }

        public Path skeletonPath() {
            return skeletonPath;
        }

        public Path texturePath() {
            return texturePath;
        }

        public Path textureDirectory() {
            return textureDirectory;
        }

        public Path outputPath() {
            return outputPath;
        }

        public float scale() {
            return scale;
        }

        public Integer overrideWidth() {
            return overrideWidth;
        }

        public Integer overrideHeight() {
            return overrideHeight;
        }

        public String skinName() {
            return skinName;
        }

        public String animationName() {
            return animationName;
        }

        public float animationTime() {
            return animationTime;
        }

        public Path animationFile() {
            return animationFile;
        }

        public int initialWindowWidth() {
            return initialWindowWidth;
        }

        public int initialWindowHeight() {
            return initialWindowHeight;
        }

        public int minOutputSize() {
            return minOutputSize;
        }

        public float videoSeconds() {
            return videoSeconds;
        }

        public int fps() {
            return fps;
        }

        public Path videoOutput() {
            return videoOutput;
        }

        public boolean keepFrames() {
            return keepFrames;
        }

        public Path framesDir() {
            return framesDir;
        }

        public Path folder() {
            return folder;
        }
    }

    private static final class GeometryBounds {

        private static final GeometryBounds EMPTY = new GeometryBounds(
            0f,
            0f,
            0f,
            0f,
            false
        );

        private final float minX;
        private final float minY;
        private final float maxX;
        private final float maxY;
        private final boolean hasGeometry;

        private GeometryBounds(
            float minX,
            float minY,
            float maxX,
            float maxY,
            boolean hasGeometry
        ) {
            this.minX = minX;
            this.minY = minY;
            this.maxX = maxX;
            this.maxY = maxY;
            this.hasGeometry = hasGeometry;
        }

        static GeometryBounds of(
            float minX,
            float minY,
            float maxX,
            float maxY
        ) {
            return new GeometryBounds(minX, minY, maxX, maxY, true);
        }

        static GeometryBounds empty() {
            return EMPTY;
        }

        boolean hasGeometry() {
            return hasGeometry;
        }

        float minX() {
            return minX;
        }

        float minY() {
            return minY;
        }

        float width() {
            return maxX - minX;
        }

        float height() {
            return maxY - minY;
        }
    }
}
