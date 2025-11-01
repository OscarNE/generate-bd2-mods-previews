import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.utils.BufferUtils;
import com.esotericsoftware.spine.Skeleton;
import com.esotericsoftware.spine.SkeletonRenderer;
import com.esotericsoftware.spine.utils.TwoColorPolygonBatch;
import java.nio.ByteBuffer;

public final class FrameRenderer {

    private final SkeletonRenderer renderer;
    private final TwoColorPolygonBatch batch;
    private final OrthographicCamera camera;
    private final FrameBuffer frameBuffer;
    private final int outputWidth;
    private final int outputHeight;

    public FrameRenderer(
        SkeletonRenderer renderer,
        TwoColorPolygonBatch batch,
        OrthographicCamera camera,
        FrameBuffer frameBuffer,
        int outputWidth,
        int outputHeight
    ) {
        this.renderer = renderer;
        this.batch = batch;
        this.camera = camera;
        this.frameBuffer = frameBuffer;
        this.outputWidth = outputWidth;
        this.outputHeight = outputHeight;
    }

    public Pixmap renderFrame(Skeleton skeleton) {
        frameBuffer.begin();

        Gdx.gl.glViewport(0, 0, outputWidth, outputHeight);
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);

        batch.setProjectionMatrix(camera.combined);
        batch.begin();
        renderer.draw(batch, skeleton);
        batch.end();

        Pixmap pixmap = captureFrameBuffer();
        frameBuffer.end();

        Pixmap flipped = flipPixmapVertically(pixmap);
        pixmap.dispose();
        return flipped;
    }

    public int getOutputWidth() {
        return outputWidth;
    }

    public int getOutputHeight() {
        return outputHeight;
    }

    private Pixmap captureFrameBuffer() {
        int amount = outputWidth * outputHeight * 4;
        ByteBuffer pixels = BufferUtils.newByteBuffer(amount);
        Gdx.gl.glFinish();
        Gdx.gl20.glPixelStorei(GL20.GL_PACK_ALIGNMENT, 1);
        Gdx.gl20.glReadPixels(
            0,
            0,
            outputWidth,
            outputHeight,
            GL20.GL_RGBA,
            GL20.GL_UNSIGNED_BYTE,
            pixels
        );
        pixels.rewind();
        Pixmap pixmap = new Pixmap(
            outputWidth,
            outputHeight,
            Pixmap.Format.RGBA8888
        );
        BufferUtils.copy(pixels, pixmap.getPixels(), amount);
        return pixmap;
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
}
