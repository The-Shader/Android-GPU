package imagemanipulator;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Point;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.ByteOrder;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;
import android.opengl.GLES31;

import com.david.tverdota.imagemanipulator.R;

/**
 * Created by David Tverdota on 7/24/2016.
 */
public class CustomGLRenderer implements GLSurfaceView.Renderer {

    private int[] textures;
    private int[] alignmentsMin = new int[2];

    private int[] alignmentsMax = new int[2];

    private FloatBuffer vertexBuffer;
    private FloatBuffer textureCoords;
    private int renderProgram, computeProgram;

    private boolean glInit = false;

    private CustomGLSurfaceView surfaceView;

    private Size displaySize;

    private int bitmapWidth;

    private int bitmapHeight;

    private HandlerThread backgroundThread;


    CustomGLRenderer (CustomGLSurfaceView view) {

        surfaceView = view;

        float[] vertices = { 1.0f, -1.0f, -1.0f, -1.0f, 1.0f, 1.0f, -1.0f, 1.0f };

        float[] texCoords = { 1.0f, 1.0f, 0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 0.0f };

        vertexBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        vertexBuffer.put(vertices);
        vertexBuffer.position(0);

        textureCoords = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        textureCoords.put(texCoords);
        textureCoords.position(0);
    }

    public void onResume() {
        startBackgroundThread();
    }

    public void onPause() {
        glInit = false;
        stopBackgroundThread();
    }

    public void onSurfaceCreated ( GL10 unused, EGLConfig config ) {

        Point ss = new Point();
        surfaceView.getDisplay().getRealSize(ss);

        displaySize = new Size(ss.x, ss.y);

        initTex();

        GLES31.glClearColor(1.0f, 1.0f, 1.0f, 1.0f);

        renderProgram = loadShader();

        computeProgram = loadComputeShader();

        GLES31.glUseProgram(computeProgram);

        alignmentsMin[0] = 0;
        alignmentsMin[1] = 0;

        GLES31.glUniform2i(GLES31.glGetUniformLocation(computeProgram, "alignmentMin"), alignmentsMin[0], alignmentsMin[1]);

        alignmentsMax[0] = displaySize.getWidth();
        alignmentsMax[1] = displaySize.getHeight();

        GLES31.glUniform2i(GLES31.glGetUniformLocation(computeProgram, "alignmentMax"), alignmentsMax[0], alignmentsMax[1]);

        glInit = true;
    }

    public void onDrawFrame ( GL10 unused ) {
        if ( !glInit) return;
        GLES31.glClear(GLES31.GL_COLOR_BUFFER_BIT);

        GLES31.glUseProgram(computeProgram);

        GLES31.glUniform2i(GLES31.glGetUniformLocation(computeProgram, "alignmentMin"), alignmentsMin[0], alignmentsMin[1]);

        GLES31.glUniform2i(GLES31.glGetUniformLocation(computeProgram, "alignmentMax"), alignmentsMax[0], alignmentsMax[1]);

        GLES31.glBindImageTexture(0, textures[0], 0, false, 0, GLES31.GL_READ_ONLY, GLES31.GL_RGBA8);

        GLES31.glBindImageTexture(1, textures[1], 0, false, 0, GLES31.GL_WRITE_ONLY, GLES31.GL_RGBA8);

        GLES31.glDispatchCompute(bitmapWidth / 8, bitmapHeight / 8, 1);

        GLES31.glMemoryBarrier(GLES31.GL_TEXTURE_UPDATE_BARRIER_BIT);

        GLES31.glMemoryBarrier(GLES31.GL_ALL_SHADER_BITS);

        GLES31.glUseProgram(renderProgram);

        int positionHandler = GLES31.glGetAttribLocation(renderProgram, "aPosition");
        int texCoordHandler = GLES31.glGetAttribLocation (renderProgram, "aTexCoord" );

        GLES31.glVertexAttribPointer(positionHandler, 2, GLES31.GL_FLOAT, false, 4 * 2, vertexBuffer);

        GLES31.glVertexAttribPointer(texCoordHandler, 2, GLES31.GL_FLOAT, false, 4 * 2, textureCoords);

        GLES31.glEnableVertexAttribArray(positionHandler);
        GLES31.glEnableVertexAttribArray(texCoordHandler);

        GLES31.glActiveTexture(GLES31.GL_TEXTURE1);
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, textures[1]);
        GLES31.glUniform1i(GLES31.glGetUniformLocation(renderProgram, "sTexture"), 1);

        GLES31.glDrawArrays(GLES31.GL_TRIANGLE_STRIP, 0, 4);

        GLES31.glBindBuffer(GLES31.GL_ARRAY_BUFFER, 0);

        GLES31.glFlush();
    }

    public void onSurfaceChanged ( GL10 unused, int width, int height) {
        GLES31.glViewport(0, 0, width, height);
    }

    public void setAligments(float centerX, float centerY) {

        centerX *= (float)bitmapWidth / (float) displaySize.getWidth();
        centerY *= (float)bitmapHeight / (float) displaySize.getHeight();

        alignmentsMin[0] = Math.max((int)centerX - displaySize.getWidth() / 2, 0);
        alignmentsMin[1] = Math.max((int) centerY - displaySize.getHeight() / 2, 0);

        alignmentsMax[0] = Math.min((int) centerX + displaySize.getWidth() / 2, bitmapWidth);
        alignmentsMax[1] = Math.min((int) centerY + displaySize.getHeight() / 2, bitmapHeight);

        surfaceView.requestRender();
    }


    private void initTex() {

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inSampleSize = 4;

        Bitmap bitmap = BitmapFactory.decodeResource(surfaceView.getResources(), R.drawable.uhd_image, options);

        bitmapWidth = bitmap.getWidth();

        bitmapHeight = bitmap.getHeight();

        textures = new int[2];
        GLES31.glGenTextures(2, textures, 0);
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, textures[0]);
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_WRAP_S, GLES31.GL_CLAMP_TO_EDGE);
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_WRAP_T, GLES31.GL_CLAMP_TO_EDGE);
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MIN_FILTER, GLES31.GL_NEAREST);
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MAG_FILTER, GLES31.GL_NEAREST);

        GLES31.glTexStorage2D(GLES31.GL_TEXTURE_2D, 1, GLES31.GL_RGBA8, bitmapWidth, bitmapHeight);

        GLUtils.texSubImage2D(GLES31.GL_TEXTURE_2D, 0, 0, 0, bitmap);

        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, textures[1]);
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_WRAP_S, GLES31.GL_CLAMP_TO_EDGE);
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_WRAP_T, GLES31.GL_CLAMP_TO_EDGE);
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MIN_FILTER, GLES31.GL_NEAREST);
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MAG_FILTER, GLES31.GL_NEAREST);

        GLES31.glTexStorage2D(GLES31.GL_TEXTURE_2D, 1, GLES31.GL_RGBA8, bitmapWidth, bitmapHeight);

        GLUtils.texSubImage2D(GLES31.GL_TEXTURE_2D, 0, 0, 0, bitmap);

        bitmap.recycle();
    }


    private int loadComputeShader() {
        int compShader = GLES31.glCreateShader(GLES31.GL_COMPUTE_SHADER);

        int[] compiled = new int[1];

        AssetManager assetManager = surfaceView.getContext().getAssets();

        String computeShaderStr = "";

        try {
            BufferedReader computeShaderBuffer = new BufferedReader(new InputStreamReader(assetManager.open("computeShader.glsl")));
            StringBuilder strBuilder = new StringBuilder();

            String line;
            while ((line = computeShaderBuffer.readLine()) != null) {
                strBuilder.append(line);

                strBuilder.append("\n");
            }
            computeShaderStr = strBuilder.toString();

        } catch (IOException ex) {
            System.out.println("Compute Source Error: " + ex.getMessage());
        }

        GLES31.glShaderSource(compShader, computeShaderStr);
        GLES31.glCompileShader(compShader);
        GLES31.glGetShaderiv(compShader, GLES31.GL_COMPILE_STATUS, compiled, 0);

        if (compiled[0] == 0) {
            Log.e("Shader", "Could not compile compShader");
            Log.v("Shader", "Could not compile compShader:"+GLES31.glGetShaderInfoLog(compShader));
            GLES31.glDeleteShader(compShader);
            compShader = 0;
        }

        int program = GLES31.glCreateProgram();
        GLES31.glAttachShader(program, compShader);
        GLES31.glLinkProgram(program);

        return program;
    }

    private int loadShader () {
        int vShader = GLES31.glCreateShader(GLES31.GL_VERTEX_SHADER);

        AssetManager assetManager = surfaceView.getContext().getAssets();
        String vertexShaderStr = "";
        try {
            BufferedReader vertexShaderReader = new BufferedReader(new InputStreamReader(assetManager.open("vertexShader.vert")));
            StringBuilder strBuilder = new StringBuilder();

            String line;
            while ((line = vertexShaderReader.readLine()) != null) {
                strBuilder.append(line);

                strBuilder.append("\n");
            }
            vertexShaderStr = strBuilder.toString();

        } catch (IOException ex) {
            System.out.println("Error: " + ex.getMessage());
        }
        GLES31.glShaderSource(vShader, vertexShaderStr);
        GLES31.glCompileShader(vShader);
        int[] compiled = new int[1];
        GLES31.glGetShaderiv(vShader, GLES31.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            Log.e("Shader", "Could not compile vShader");
            Log.v("Shader", "Could not compile vShader:" + GLES31.glGetShaderInfoLog(vShader));
            GLES31.glDeleteShader(vShader);
            vShader = 0;
        }

        int fShader = GLES31.glCreateShader(GLES31.GL_FRAGMENT_SHADER);

        String fragmentShaderStr = "";
        try {
            BufferedReader fragmentShaderBuffer = new BufferedReader(new InputStreamReader(assetManager.open("fragmentShader.frag")));
            StringBuilder strBuilder = new StringBuilder();

            String line;
            while ((line = fragmentShaderBuffer.readLine()) != null) {
                strBuilder.append(line);

                strBuilder.append("\n");
            }
            fragmentShaderStr = strBuilder.toString();

        } catch (IOException ex) {
            System.out.println("Err: " + ex.getMessage());
        }

        GLES31.glShaderSource(fShader, fragmentShaderStr);
        GLES31.glCompileShader(fShader);
        GLES31.glGetShaderiv(fShader, GLES31.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            Log.e("Shader", "Could not compile fShader");
            Log.v("Shader", "Could not compile fShader:" + GLES31.glGetShaderInfoLog(fShader));
            GLES31.glDeleteShader(fShader);
            fShader = 0;
        }

        int program = GLES31.glCreateProgram();
        GLES31.glAttachShader(program, vShader);
        GLES31.glAttachShader(program, fShader);
        GLES31.glLinkProgram(program);

        return program;
    }


    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
    }

    private void stopBackgroundThread() {
        backgroundThread.quitSafely();
        try {
            backgroundThread.join();
            backgroundThread = null;
        } catch (InterruptedException e) {
            Log.e("mr", "stopBackgroundThread");
        }
    }
}
