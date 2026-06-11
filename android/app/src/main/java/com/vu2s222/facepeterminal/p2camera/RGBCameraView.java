package com.vu2s222.facepeterminal.p2camera;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.ifeng.f_uvccamera.UVCCameraProxy;
import com.ifeng.f_uvccamera.callback.ConnectCallback;

import android.hardware.usb.UsbDevice;

/**
 * Native FrameLayout that contains a TextureView for direct UVC RGB rendering.
 *
 * The standalone app (OrbbecSDK-Android-Wrapper-main) renders RGB directly via
 *   rgbCamera.setPreviewTexture(textureView) + rgbCamera.startPreview()
 * which lets libUVCCamera push frames straight to GPU without any CPU/bridge overhead.
 *
 * This view replicates that exact approach inside React Native as a NativeView.
 *
 * Usage: Controlled by P2CameraModule - call attachCamera(proxy) to start rendering.
 */
public class RGBCameraView extends FrameLayout {

    private static final String TAG = "RGBCameraView";

    private TextureView textureView;
    private UVCCameraProxy attachedCamera;
    private boolean surfaceReady = false;

    public RGBCameraView(Context context) {
        super(context);
        setupView(context);
    }

    private void setupView(Context context) {
        textureView = new TextureView(context);
        textureView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                Log.d(TAG, "SurfaceTexture available: " + width + "x" + height);
                surfaceReady = true;
                // If camera is already attached and waiting, connect it now
                if (attachedCamera != null) {
                    connectCameraToTexture(attachedCamera);
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                Log.d(TAG, "SurfaceTexture size changed: " + width + "x" + height);
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                Log.d(TAG, "SurfaceTexture destroyed");
                surfaceReady = false;
                return false;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {
                // Called every frame — no-op needed
            }
        });

        addView(textureView);
    }

    /**
     * Attach a UVCCameraProxy and start rendering to this TextureView.
     * Called by P2CameraModule when surface is not yet ready.
     * Once onSurfaceTextureAvailable fires, it will call camera.setPreviewTexture().
     */
    public void attachCamera(UVCCameraProxy camera) {
        this.attachedCamera = camera;
        if (surfaceReady) {
            connectCameraToTexture(camera);
        }
        // else: onSurfaceTextureAvailable will call connectCameraToTexture
    }

    /**
     * Detach the camera and release the surface.
     */
    public void detachCamera() {
        this.attachedCamera = null;
    }

    private void connectCameraToTexture(UVCCameraProxy camera) {
        Log.d(TAG, "Connecting camera to TextureView surface");
        try {
            camera.setPreviewTexture(textureView);
            // Fix: If the TextureView's surface texture is already available,
            // set the surface on the proxy immediately because the listener's
            // onSurfaceTextureAvailable callback won't be triggered.
            if (textureView.isAvailable()) {
                SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();
                if (surfaceTexture != null) {
                    Log.d(TAG, "TextureView is already available, setting surface immediately");
                    camera.setPreviewSurface(new Surface(surfaceTexture));
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "Error connecting camera to texture: " + e.getMessage());
        }
    }

    public TextureView getTextureView() {
        return textureView;
    }

    public boolean isSurfaceReady() {
        return surfaceReady;
    }
}
