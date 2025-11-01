import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.esotericsoftware.spine.Skeleton;
import java.nio.file.Path;

public final class ImageGenerator {

    private final FrameRenderer frameRenderer;

    ImageGenerator(FrameRenderer frameRenderer) {
        this.frameRenderer = frameRenderer;
    }

    public void generate(Path outputPath, Skeleton skeleton) {
        Pixmap frame = frameRenderer.renderFrame(skeleton);
        try {
            writePixmap(outputPath, frame);
            App.logInfo("Preview image written to " + outputPath);
        } finally {
            frame.dispose();
        }
    }

    private void writePixmap(Path path, Pixmap pixmap) {
        FileHandle handle = Gdx.files.absolute(path.toString());
        if (handle.parent() != null && !handle.parent().exists()) {
            handle.parent().mkdirs();
        }
        PixmapIO.writePNG(handle, pixmap);
    }
}
