package camerapreview;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Point;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;
import android.opengl.GLES31;
import android.view.Surface;

/**
 * Created by David Tverdota on 7/24/2016.
 */
public class CustomGLRenderer implements GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    public enum ComputeShader {
        ColorScaler,
        ColorQuantizer,
        EdgeDetector
    }

    private int[] textures;
    private int[] texture2D;
    private int[] frameBuffer;
    private int[] renderBuffer;
    private FloatBuffer vertexBuffer;
    private FloatBuffer texCoords;
    private FloatBuffer tempTexCoord;
    private int renderProgram, computeProgram;

    private int colorScale;

    private long lastTime;

    private int frameCounter;

    private ComputeShader computeShaderMode;

    private int[] aligmentsMin = new int[2];

    private int[] aligmentsMax = new int[2];

    private int[] centroidBuffer = new int[1];

    private SurfaceTexture mSTexture;

    private boolean mGLInit = false;
    private boolean mUpdateST = false;

    private CustomGLSurfaceView mView;

    private CameraDevice mCameraDevice;
    private CameraCaptureSession mCaptureSession;
    private CaptureRequest.Builder mPreviewRequestBuilder;
    private String mCameraID;
    private Size mPreviewSize;

    private HandlerThread mBackgroundThread;
    private Handler mBackgroundHandler;
    private Semaphore mCameraOpenCloseLock = new Semaphore(1);

    CustomGLRenderer (CustomGLSurfaceView view) {
        mView = view;
        float[] vtmp = { 1.0f, -1.0f, -1.0f, -1.0f, 1.0f, 1.0f, -1.0f, 1.0f };
        float[] ttmp = { 1.0f, 1.0f, 0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 0.0f };
        float[] ttmp2 = { 1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 0.0f, 1.0f };
        vertexBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        vertexBuffer.put(vtmp);
        vertexBuffer.position(0);
        texCoords = ByteBuffer.allocateDirect(8*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        texCoords.put(ttmp);
        texCoords.position(0);
        tempTexCoord = ByteBuffer.allocateDirect(8*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        tempTexCoord.put(ttmp2);
        tempTexCoord.position(0);
    }

    public void onResume() {
        startBackgroundThread();
    }

    public void onPause() {
        mGLInit = false;
        mUpdateST = false;
        closeCamera();
        stopBackgroundThread();
    }

    public void onSurfaceCreated ( GL10 unused, EGLConfig config ) {

        Point ss = new Point();
        mView.getDisplay().getRealSize(ss);

        mPreviewSize = new Size(ss.x, ss.y);

        initTex();
        mSTexture = new SurfaceTexture (textures[0]);
        mSTexture.setOnFrameAvailableListener(this);

        GLES31.glClearColor(1.0f, 1.0f, 1.0f, 1.0f);

        renderProgram = loadRenderShaders();

        computeProgram = loadEdgeDetectorShader(); //loadQuantizeShader(); //loadColorScaleShader();

        GLES31.glUseProgram(computeProgram);

        aligmentsMin[0] = 0;
        aligmentsMin[1] = 0;

        GLES31.glUniform2i(GLES31.glGetUniformLocation(computeProgram, "alignmentMin"), aligmentsMin[0], aligmentsMin[1]);

        aligmentsMax[0] = mPreviewSize.getWidth();
        aligmentsMax[1] = mPreviewSize.getHeight();

        GLES31.glUniform2i(GLES31.glGetUniformLocation(computeProgram, "alignmentMax"), aligmentsMax[0], aligmentsMax[1]);

        if (computeShaderMode == ComputeShader.ColorScaler) {

            colorScale = 0;

            GLES31.glUniform1i(GLES31.glGetUniformLocation(computeProgram, "colorScale"), colorScale);

        } else if (computeShaderMode == ComputeShader.ColorQuantizer) {

            GLES31.glGenBuffers(1, centroidBuffer, 0);

            GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, centroidBuffer[0]);

            GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, 32 * 4 * 4, null, GLES31.GL_STATIC_DRAW);
        }

        cacPreviewSize(ss.x, ss.y);
        openCamera();

        mGLInit = true;

        lastTime = System.currentTimeMillis();

        frameCounter = 0;
    }

    public void onDrawFrame ( GL10 unused ) {
        if ( !mGLInit ) return;

        //FPS Counter

//        long currentTime = System.currentTimeMillis();
//        ++frameCounter;
//
//        if (currentTime - lastTime >= 1000) {
//            System.out.println("Frames per second: " + frameCounter);
//            frameCounter = 0;
//            lastTime = currentTime;
//        }

        GLES31.glClear(GLES31.GL_COLOR_BUFFER_BIT);

        synchronized(this) {

            if (mUpdateST) {
                mSTexture.updateTexImage();

                mUpdateST = false;
            }
        }

        GLES31.glUseProgram(renderProgram);

        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, frameBuffer[0]);

        int positionHandler = GLES31.glGetAttribLocation(renderProgram, "aPosition");
        int texCoordHandler = GLES31.glGetAttribLocation ( renderProgram, "aTexCoord" );

        GLES31.glVertexAttribPointer(positionHandler, 2, GLES31.GL_FLOAT, false, 4 * 2, vertexBuffer);

        GLES31.glVertexAttribPointer(texCoordHandler, 2, GLES31.GL_FLOAT, false, 4 * 2, tempTexCoord);

        GLES31.glEnableVertexAttribArray(positionHandler);
        GLES31.glEnableVertexAttribArray(texCoordHandler);

        GLES31.glActiveTexture(GLES31.GL_TEXTURE0);
        GLES31.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0]);
        GLES31.glUniform1i(GLES31.glGetUniformLocation(renderProgram, "sTexture"), 0);

        GLES31.glUniform1i(GLES31.glGetUniformLocation(renderProgram, "isOffScreen"), 1);

        GLES31.glDrawArrays(GLES31.GL_TRIANGLE_STRIP, 0, 4);

        GLES31.glFlush();

        GLES31.glFinish();

        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, 0);
        GLES31.glUseProgram(0);

        GLES31.glUseProgram(computeProgram);

        GLES31.glBindImageTexture(1, texture2D[0], 0, false, 0, GLES31.GL_READ_ONLY, GLES31.GL_RGBA8);

        GLES31.glBindImageTexture(2, texture2D[1], 0, false, 0, GLES31.GL_WRITE_ONLY, GLES31.GL_RGBA8);

        GLES31.glUniform2i(GLES31.glGetUniformLocation(computeProgram, "alignmentMin"), aligmentsMin[0], aligmentsMin[1]);

        GLES31.glUniform2i(GLES31.glGetUniformLocation(computeProgram, "alignmentMax"), aligmentsMax[0], aligmentsMax[1]);

        if(computeShaderMode == ComputeShader.ColorScaler) {

            GLES31.glUniform1i(GLES31.glGetUniformLocation(computeProgram, "colorScale"), colorScale);

        } else if (computeShaderMode == ComputeShader.ColorQuantizer) {

            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 5, centroidBuffer[0]);
        }

        GLES31.glDispatchCompute(mPreviewSize.getWidth() / 8, mPreviewSize.getHeight() / 8, 1);

        GLES31.glMemoryBarrier(GLES31.GL_ALL_SHADER_BITS);

        if (computeShaderMode == ComputeShader.ColorQuantizer) {

            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 5, 0);
        }

        GLES31.glUseProgram(renderProgram);

        positionHandler = GLES31.glGetAttribLocation(renderProgram, "aPosition");
        texCoordHandler = GLES31.glGetAttribLocation ( renderProgram, "aTexCoord" );

        GLES31.glVertexAttribPointer(positionHandler, 2, GLES31.GL_FLOAT, false, 4 * 2, vertexBuffer);

        GLES31.glVertexAttribPointer(texCoordHandler, 2, GLES31.GL_FLOAT, false, 4 * 2, texCoords);

        GLES31.glEnableVertexAttribArray(positionHandler);
        GLES31.glEnableVertexAttribArray(texCoordHandler);

        GLES31.glActiveTexture(GLES31.GL_TEXTURE2);
         GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, texture2D[1]);
        GLES31.glUniform1i(GLES31.glGetUniformLocation(renderProgram, "inTexture"), 2);

        GLES31.glUniform1i(GLES31.glGetUniformLocation(renderProgram, "isOffScreen"), 0);

        GLES31.glDrawArrays(GLES31.GL_TRIANGLE_STRIP, 0, 4);

        GLES31.glFlush();
    }

    public void onSurfaceChanged ( GL10 unused, int width, int height) {
        GLES31.glViewport(0, 0, width, height);
    }

    public void setAligments(float centerX, float centerY) {

        aligmentsMin[0] = Math.max((int)centerX - 800, 0);
        aligmentsMin[1] = Math.max((int) centerY - 512, 0);

        aligmentsMax[0] = Math.min((int) centerX + 800, mPreviewSize.getWidth());
        aligmentsMax[1] = Math.min((int) centerY + 512, mPreviewSize.getHeight());

        mView.requestRender();
    }

    public void changeColorScale() {
        colorScale = (colorScale ^ 1);
    }

    private void initTex() {

        textures = new int[1];
        GLES31.glGenTextures(1, textures, 0);
        GLES31.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0]);
        GLES31.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES31.GL_TEXTURE_WRAP_S, GLES31.GL_CLAMP_TO_EDGE);
        GLES31.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES31.GL_TEXTURE_WRAP_T, GLES31.GL_CLAMP_TO_EDGE);
        GLES31.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES31.GL_TEXTURE_MIN_FILTER, GLES31.GL_NEAREST);
        GLES31.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES31.GL_TEXTURE_MAG_FILTER, GLES31.GL_NEAREST);

        texture2D = new int[2];
        GLES31.glGenTextures(2, texture2D, 0);
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, texture2D[0]);
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_WRAP_S, GLES31.GL_CLAMP_TO_EDGE);
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_WRAP_T, GLES31.GL_CLAMP_TO_EDGE);
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MIN_FILTER, GLES31.GL_NEAREST);
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MAG_FILTER, GLES31.GL_NEAREST);

        GLES31.glTexStorage2D(GLES31.GL_TEXTURE_2D, 1, GLES31.GL_RGBA8, mPreviewSize.getWidth(), mPreviewSize.getHeight());

        GLES31.glTexSubImage2D(GLES31.GL_TEXTURE_2D, 0, 0, 0, mPreviewSize.getWidth(), mPreviewSize.getHeight(), GLES31.GL_RGBA, GLES31.GL_UNSIGNED_BYTE, null);

        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, texture2D[1]);
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_WRAP_S, GLES31.GL_CLAMP_TO_EDGE);
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_WRAP_T, GLES31.GL_CLAMP_TO_EDGE);
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MIN_FILTER, GLES31.GL_NEAREST);
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MAG_FILTER, GLES31.GL_NEAREST);

        GLES31.glTexStorage2D(GLES31.GL_TEXTURE_2D, 1, GLES31.GL_RGBA8, mPreviewSize.getWidth(), mPreviewSize.getHeight());

        GLES31.glTexSubImage2D(GLES31.GL_TEXTURE_2D, 0, 0, 0, mPreviewSize.getWidth(), mPreviewSize.getHeight(), GLES31.GL_RGBA, GLES31.GL_UNSIGNED_BYTE, null);


        frameBuffer = new int[1];
        GLES31.glGenFramebuffers(1, frameBuffer, 0);

        renderBuffer = new int[1];
        GLES31.glGenRenderbuffers(1, renderBuffer, 0);

        GLES31.glActiveTexture(GLES31.GL_TEXTURE1);
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, texture2D[0]);

        GLES31.glBindRenderbuffer(GLES31.GL_RENDERBUFFER, renderBuffer[0]);
        GLES31.glRenderbufferStorage(GLES31.GL_RENDERBUFFER, GLES31.GL_DEPTH_COMPONENT16, mPreviewSize.getWidth(), mPreviewSize.getHeight());

        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, frameBuffer[0]);
        GLES31.glFramebufferTexture2D(GLES31.GL_FRAMEBUFFER, GLES31.GL_COLOR_ATTACHMENT0, GLES31.GL_TEXTURE_2D, texture2D[0], 0);

        GLES31.glFramebufferRenderbuffer(GLES31.GL_FRAMEBUFFER, GLES31.GL_DEPTH_ATTACHMENT, GLES31.GL_RENDERBUFFER, renderBuffer[0]);

        GLES20.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, 0);

    }

    public synchronized void onFrameAvailable ( SurfaceTexture st ) {
        mUpdateST = true;
        mView.requestRender();
    }

    private int loadColorScaleShader() {

        computeShaderMode = ComputeShader.ColorScaler;

        int computeShader = GLES31.glCreateShader(GLES31.GL_COMPUTE_SHADER);

        int[] compiled = new int[1];

        AssetManager assetManager = mView.getContext().getAssets();

        String computeShaderSrc = "";

        try {
            BufferedReader computeShaderBuffer = new BufferedReader(new InputStreamReader(assetManager.open("colorScaler.glsl")));
            StringBuilder strBuilder = new StringBuilder();

            String line;
            while ((line = computeShaderBuffer.readLine()) != null) {
                strBuilder.append(line);

                strBuilder.append("\n");
            }
            computeShaderSrc = strBuilder.toString();

        } catch (IOException ex) {
            System.out.println("ColorScaler Source Error: " + ex.getMessage());
        }

        GLES31.glShaderSource(computeShader, computeShaderSrc);
        GLES31.glCompileShader(computeShader);
        GLES31.glGetShaderiv(computeShader, GLES31.GL_COMPILE_STATUS, compiled, 0);

        if (compiled[0] == 0) {
            Log.e("Shader", "Could not compile computeShader");
            Log.v("Shader", "Could not compile computeShader:" + GLES31.glGetShaderInfoLog(computeShader));
            GLES31.glDeleteShader(computeShader);
            computeShader = 0;
        }

        int program = GLES31.glCreateProgram();
        GLES31.glAttachShader(program, computeShader);
        GLES31.glLinkProgram(program);

        return program;
    }

    private int loadQuantizeShader() {

        computeShaderMode = ComputeShader.ColorQuantizer;

        int computeShader = GLES31.glCreateShader(GLES31.GL_COMPUTE_SHADER);

        int[] compiled = new int[1];

        AssetManager assetManager = mView.getContext().getAssets();

        String computeShaderSrc = "";

        try {
            BufferedReader computeShaderBuffer = new BufferedReader(new InputStreamReader(assetManager.open("quantizer.glsl")));
            StringBuilder strBuilder = new StringBuilder();

            String line;
            while ((line = computeShaderBuffer.readLine()) != null) {
                strBuilder.append(line);

                strBuilder.append("\n");
            }
            computeShaderSrc = strBuilder.toString();

        } catch (IOException ex) {
            System.out.println("Quantizer Source Error: " + ex.getMessage());
        }

        GLES31.glShaderSource(computeShader, computeShaderSrc);
        GLES31.glCompileShader(computeShader);
        GLES31.glGetShaderiv(computeShader, GLES31.GL_COMPILE_STATUS, compiled, 0);

        if (compiled[0] == 0) {
            Log.e("Shader", "Could not compile computeShader");
            Log.v("Shader", "Could not compile computeShader:" + GLES31.glGetShaderInfoLog(computeShader));
            GLES31.glDeleteShader(computeShader);
            computeShader = 0;
        }

        int program = GLES31.glCreateProgram();
        GLES31.glAttachShader(program, computeShader);
        GLES31.glLinkProgram(program);

        return program;
    }

    private int loadEdgeDetectorShader() {

        computeShaderMode = ComputeShader.EdgeDetector;

        int computeShader = GLES31.glCreateShader(GLES31.GL_COMPUTE_SHADER);

        int[] compiled = new int[1];

        AssetManager assetManager = mView.getContext().getAssets();

        String computeShaderSrc = "";

        try {
            BufferedReader computeShaderBuffer = new BufferedReader(new InputStreamReader(assetManager.open("edgeDetector.glsl")));
            StringBuilder strBuilder = new StringBuilder();

            String line;
            while ((line = computeShaderBuffer.readLine()) != null) {
                strBuilder.append(line);

                strBuilder.append("\n");
            }
            computeShaderSrc = strBuilder.toString();

        } catch (IOException ex) {
            System.out.println("EdgeDetector Source Error: " + ex.getMessage());
        }

        GLES31.glShaderSource(computeShader, computeShaderSrc);
        GLES31.glCompileShader(computeShader);
        GLES31.glGetShaderiv(computeShader, GLES31.GL_COMPILE_STATUS, compiled, 0);

        if (compiled[0] == 0) {
            Log.e("Shader", "Could not compile computeShader");
            Log.v("Shader", "Could not compile computeShader:" + GLES31.glGetShaderInfoLog(computeShader));
            GLES31.glDeleteShader(computeShader);
            computeShader = 0;
        }

        int program = GLES31.glCreateProgram();
        GLES31.glAttachShader(program, computeShader);
        GLES31.glLinkProgram(program);

        return program;
    }

    private int loadRenderShaders() {
        int vertexShader = GLES31.glCreateShader(GLES31.GL_VERTEX_SHADER);

        AssetManager assetManager = mView.getContext().getAssets();
        String vertexShaderSrc = "";
        try {
            BufferedReader vertexShaderReader = new BufferedReader(new InputStreamReader(assetManager.open("vertexShader.vert")));
            StringBuilder strBuilder = new StringBuilder();

            String line;
            while ((line = vertexShaderReader.readLine()) != null) {
                strBuilder.append(line);

                strBuilder.append("\n");
            }
            vertexShaderSrc = strBuilder.toString();

        } catch (IOException ex) {
            System.out.println("Error: " + ex.getMessage());
        }
        GLES31.glShaderSource(vertexShader, vertexShaderSrc);
        GLES31.glCompileShader(vertexShader);
        int[] compiled = new int[1];
        GLES31.glGetShaderiv(vertexShader, GLES31.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            Log.e("Shader", "Could not compile vertexShader");
            Log.v("Shader", "Could not compile vertexShader:" + GLES31.glGetShaderInfoLog(vertexShader));
            GLES31.glDeleteShader(vertexShader);
            vertexShader = 0;
        }

        int fragmentShader = GLES31.glCreateShader(GLES31.GL_FRAGMENT_SHADER);

        String fragmentShaderSrc = "";
        try {
            BufferedReader fragmentShaderBuffer = new BufferedReader(new InputStreamReader(assetManager.open("fragmentShader.frag")));
            StringBuilder strBuilder = new StringBuilder();

            String line;
            while ((line = fragmentShaderBuffer.readLine()) != null) {
                strBuilder.append(line);

                strBuilder.append("\n");
            }
            fragmentShaderSrc = strBuilder.toString();

        } catch (IOException ex) {
            System.out.println("Error: " + ex.getMessage());
        }

        GLES31.glShaderSource(fragmentShader, fragmentShaderSrc);
        GLES31.glCompileShader(fragmentShader);
        GLES31.glGetShaderiv(fragmentShader, GLES31.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            Log.e("Shader", "Could not compile fshader");
            Log.v("Shader", "Could not compile fshader:" + GLES31.glGetShaderInfoLog(fragmentShader));
            GLES31.glDeleteShader(fragmentShader);
            fragmentShader = 0;
        }

        int program = GLES31.glCreateProgram();
        GLES31.glAttachShader(program, vertexShader);
        GLES31.glAttachShader(program, fragmentShader);
        GLES31.glLinkProgram(program);

        return program;
    }

    void cacPreviewSize( final int width, final int height ) {
        CameraManager manager = (CameraManager)mView.getContext().getSystemService(Context.CAMERA_SERVICE);
        try {
            for (String cameraID : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraID);
                if (characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT)
                    continue;

                mCameraID = cameraID;
                StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                for ( Size psize : map.getOutputSizes(SurfaceTexture.class)) {
                    if ( width == psize.getWidth() && height == psize.getHeight() ) {
                        mPreviewSize = psize;
                        break;
                    }
                }
                break;
            }
        } catch ( CameraAccessException e ) {
            Log.e("mr", "cacPreviewSize - Camera Access Exception");
        } catch ( IllegalArgumentException e ) {
            Log.e("mr", "cacPreviewSize - Illegal Argument Exception");
        } catch ( SecurityException e ) {
            Log.e("mr", "cacPreviewSize - Security Exception");
        }
    }

    void openCamera() {
        CameraManager manager = (CameraManager)mView.getContext().getSystemService(Context.CAMERA_SERVICE);
        try {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(mCameraID);
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            manager.openCamera(mCameraID,mStateCallback,mBackgroundHandler);
        } catch ( CameraAccessException e ) {
            Log.e("mr", "OpenCamera - Camera Access Exception");
        } catch ( IllegalArgumentException e ) {
            Log.e("mr", "OpenCamera - Illegal Argument Exception");
        } catch ( SecurityException e ) {
            Log.e("mr", "OpenCamera - Security Exception");
        } catch ( InterruptedException e ) {
            Log.e("mr", "OpenCamera - Interrupted Exception");
        }
    }

    private void closeCamera() {
        try {
            mCameraOpenCloseLock.acquire();
            if (null != mCaptureSession) {
                mCaptureSession.close();
                mCaptureSession = null;
            }
            if (null != mCameraDevice) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        } finally {
            mCameraOpenCloseLock.release();
        }
    }

    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {

        @Override
        public void onOpened(CameraDevice cameraDevice) {
            mCameraOpenCloseLock.release();
            mCameraDevice = cameraDevice;
            createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(CameraDevice cameraDevice) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(CameraDevice cameraDevice, int error) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
        }

    };

    private void createCameraPreviewSession() {
        try {
            mSTexture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());

            Surface surface = new Surface(mSTexture);



            mPreviewRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mPreviewRequestBuilder.addTarget(surface);

            mCameraDevice.createCaptureSession(Arrays.asList(surface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession cameraCaptureSession) {
                            if (null == mCameraDevice)
                                return;

                            mCaptureSession = cameraCaptureSession;
                            try {
                                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);

                                mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(), null, mBackgroundHandler);
                            } catch (CameraAccessException e) {
                                Log.e("mr", "createCaptureSession");
                            }
                        }
                        @Override
                        public void onConfigureFailed(CameraCaptureSession cameraCaptureSession) {
                        }
                    }, null
            );
        } catch (CameraAccessException e) {
            Log.e("mr", "createCameraPreviewSession");
        }
    }

    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            Log.e("mr", "stopBackgroundThread");
        }
    }
}
