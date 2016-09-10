package camerapreview;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.MotionEvent;
import android.view.SurfaceHolder;


/**
 * Created by David Tverdota on 7/24/2016.
 */
public class CustomGLSurfaceView extends GLSurfaceView {
    CustomGLRenderer mRenderer;

    CustomGLSurfaceView ( Context context ) {
        super ( context );
        mRenderer = new CustomGLRenderer(this);
        setEGLContextClientVersion ( 3 );
        setRenderer ( mRenderer );
        setRenderMode ( GLSurfaceView.RENDERMODE_WHEN_DIRTY );
    }

    public void surfaceCreated ( SurfaceHolder holder ) {
        super.surfaceCreated(holder);
    }

    public void surfaceDestroyed ( SurfaceHolder holder ) {
        super.surfaceDestroyed(holder);
    }

    public void surfaceChanged ( SurfaceHolder holder, int format, int w, int h ) {
        super.surfaceChanged(holder, format, w, h);
    }

    @Override
    public void onResume() {
        super.onResume();
        mRenderer.onResume();
    }

    @Override
    public void onPause() {
        mRenderer.onPause();
        super.onPause();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event)
    {
        float touchedX, touchedY;
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            touchedX = event.getX();
            touchedY = event.getY();

            mRenderer.setAligments(touchedX, touchedY);

            mRenderer.changeColorScale();

        } else if (event.getAction() == MotionEvent.ACTION_MOVE) {
            touchedX = event.getX();
            touchedY = event.getY();

            mRenderer.setAligments(touchedX, touchedY);
        }
        return true;

    }
}
