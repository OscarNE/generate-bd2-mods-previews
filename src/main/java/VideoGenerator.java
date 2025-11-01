import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.esotericsoftware.spine.AnimationState;
import com.esotericsoftware.spine.Skeleton;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Java2DFrameConverter;

public final class VideoGenerator {

    private final App app;
    private final App.CliArguments arguments;
    private final FrameRenderer frameRenderer;
    private final Skeleton skeleton;
    private final AnimationState animationState;
    private final Java2DFrameConverter frameConverter = new Java2DFrameConverter();

    VideoGenerator(
        App app,
        App.CliArguments arguments,
        FrameRenderer frameRenderer,
        Skeleton skeleton,
        AnimationState animationState
    ) {
        this.app = app;
        this.arguments = arguments;
        this.frameRenderer = frameRenderer;
        this.skeleton = skeleton;
        this.animationState = animationState;
    }

    public void generate() {
        int fps = arguments.fps();
        if (fps <= 0) {
            throw new IllegalArgumentException("FPS must be greater than zero.");
        }

        float requestedSeconds = resolveRequestedSeconds(fps);
        if (requestedSeconds <= 0f) {
            requestedSeconds = 1f / fps;
        }

        int frames = Math.max(1, Math.round(requestedSeconds * fps));
        float totalSeconds = frames / (float) fps;
        float step = 1f / fps;
        Path framesDir = arguments.framesDir();

        App.logInfo(
            String.format(
                Locale.ROOT,
                "Producing video preview (%d frame(s) over %.3fs @ %d fps) -> %s",
                frames,
                totalSeconds,
                fps,
                arguments.videoOutput()
            )
        );

        if (arguments.outputPath() != null) {
            Pixmap preview = frameRenderer.renderFrame(skeleton);
            try {
                writePixmap(arguments.outputPath(), preview);
                App.logInfo("Preview image written to " + arguments.outputPath());
            } finally {
                preview.dispose();
            }
        }

        if (arguments.keepFrames()) {
            deleteFrames(framesDir);
            try {
                Files.createDirectories(framesDir);
                App.logInfo("Saving intermediate frames under " + framesDir);
            } catch (IOException e) {
                throw new IllegalStateException(
                    "Unable to create frames directory: " + framesDir,
                    e
                );
            }
        } else {
            deleteFrames(framesDir);
            App.logInfo("Intermediate frames will be discarded after encoding.");
        }

        FFmpegFrameRecorder recorder = null;
        Exception encodeError = null;
        try {
            recorder =
                startRecorder(
                    arguments.videoOutput(),
                    frameRenderer.getOutputWidth(),
                    frameRenderer.getOutputHeight(),
                    fps
                );

            for (int i = 0; i < frames; i++) {
                Pixmap framePixmap = frameRenderer.renderFrame(skeleton);
                try {
                    if (arguments.keepFrames()) {
                        Path framePath = framesDir.resolve(
                            String.format(Locale.ROOT, "frame_%05d.png", i)
                        );
                        writePixmap(framePath, framePixmap);
                    }
                    recordFrame(recorder, framePixmap);
                } finally {
                    framePixmap.dispose();
                }

                if (i < frames - 1) {
                    app.advanceAnimation(step);
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
            App.logError(
                "Failed to encode video: " + encodeError.getMessage(),
                encodeError
            );
            throw new IllegalStateException(
                "Failed to encode video",
                encodeError
            );
        }

        App.logInfo("Video preview written to " + arguments.videoOutput());

        if (!arguments.keepFrames()) {
            deleteFrames(framesDir);
        }
    }

    private float resolveRequestedSeconds(int fps) {
        App.CliArguments.LoopMode loopMode = arguments.videoLoopMode();
        if (loopMode == App.CliArguments.LoopMode.OFF) {
            return Math.max(arguments.videoSeconds(), 0f);
        }

        if (animationState == null) {
            throw new IllegalStateException(
                "Cannot compute loop length without an active animation."
            );
        }

        float loopSeconds = computeLoopSeconds();
        if (loopSeconds <= 0f) {
            throw new IllegalStateException(
                "Animation duration is zero; cannot compute loop cycle."
            );
        }

        switch (loopMode) {
            case AUTO:
                App.logInfo(
                    String.format(
                        Locale.ROOT,
                        "Loop mode: auto (cycle %.3fs)",
                        loopSeconds
                    )
                );
                return loopSeconds;
            case CYCLES:
                App.logInfo(
                    String.format(
                        Locale.ROOT,
                        "Loop mode: %d cycle(s) (cycle %.3fs)",
                        arguments.videoLoopCycles(),
                        loopSeconds
                    )
                );
                return loopSeconds * arguments.videoLoopCycles();
            case OFF:
            default:
                return Math.max(arguments.videoSeconds(), 0f);
        }
    }

    private float computeLoopSeconds() {
        AnimationState.TrackEntry entry = animationState.getCurrent(0);
        if (entry == null) {
            throw new IllegalStateException("No animation set on track 0.");
        }
        float duration = entry.getAnimation().getDuration();
        float timeScale = entry.getTimeScale();
        if (timeScale == 0f) {
            timeScale = 1f;
        }
        return duration / timeScale;
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
        App.logInfo(
            "Video encoder configured at ~" + (targetBitrate / 1000) + " kbps"
        );
        recorder.start();
        return recorder;
    }

    private void recordFrame(FFmpegFrameRecorder recorder, Pixmap pixmap)
        throws Exception {
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
}
