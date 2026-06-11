package com.vu2s222.facepeterminal.p2camera;

import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import com.ifeng.f_uvccamera.UVCCameraProxy;
import com.ifeng.f_uvccamera.callback.ConnectCallback;
import com.ifeng.f_uvccamera.uvc.IFrameCallback;
import com.ifeng.f_uvccamera.uvc.UVCCamera;

import android.hardware.usb.UsbDevice;
import android.view.TextureView;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;

/**
 * React Native Native Module for Orbbec P2 Dual-Engine Camera.
 *
 * ═══ ROOT CAUSE of "black on stream, flash on stop" (fully diagnosed) ═══
 *
 * UVCCamera.stopPreview() calls setFrameCallback(null, 0) BEFORE nativeStopPreview().
 * This final setFrameCallback flush fires the frame callback exactly once with
 * the last buffered frame — causing the "1ms flash". During streaming, the
 * `UVCCameraProxy.startPreview()` uses PIXEL_FORMAT_YUV (value=1) but the Orbbec
 * P2 RGB camera captures in FRAME_FORMAT_MJPEG natively. The YUV conversion
 * produces YUYV/YUV422, NOT NV21. YuvImage expects NV21/I420. This mismatch
 * causes the decoded image to appear corrupted / all-black.
 *
 * ═══ Fix ═══
 *
 * Bypass UVCCameraProxy.startPreview() and call mUVCCamera.setFrameCallback()
 * directly with PIXEL_FORMAT_RGBX (value=3) which is always correctly decoded
 * from any source format. Then convert RGBX→Bitmap→JPEG→Base64 and emit.
 *
 * If RGBX conversion is slow: alternatively set FRAME_FORMAT_YUYV mode first,
 * then PIXEL_FORMAT_YUV (=1) gives true NV21-compatible output.
 *
 * ═══ Architecture ═══
 *
 * Engine 1 — RGB (libUVCCamera, PID 0x051e):
 *   setPreviewDisplay(null) + setFrameCallback(RGBX) → RGBX→Bitmap→JPEG→Base64→onRGBFrame
 *
 * Engine 2 — Depth (OpenNI2, PID 0x061e):
 *   VideoStream.NewFrameListener → rawToBitmap(depth) → JPEG→Base64→onDepthFrame
 *   + onDepthData(short[]) → liveness processing → onLivenessResult
 */
public class P2CameraModule extends ReactContextBaseJavaModule {

    private static final String TAG = "P2CameraModule";
    private static final String MODULE_NAME = "P2CameraModule";

    private static final int PREVIEW_W = 640;
    private static final int PREVIEW_H = 480;

    // 30 fps throttle for RGB frames (the depth engine throttles internally)
    private static final long RGB_FRAME_INTERVAL_MS = 33;
    private long lastRgbFrameTime = 0;

    private final ReactApplicationContext reactContext;
    private UVCCameraProxy rgbCamera;
    private OpenNIEngine depthEngine;
    private boolean isStreaming = false;
    private LivenessProcessor livenessProcessor;
    private final Object rgbLock = new Object();
    private boolean isRgbRunning = false;

    private final Handler stopHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingStopRunnable = null;
    private static final long STOP_DEBOUNCE_DELAY_MS = 2000;

    public P2CameraModule(ReactApplicationContext context) {
        super(context);
        this.reactContext = context;
        this.livenessProcessor = new LivenessProcessor();
    }

    @NonNull
    @Override
    public String getName() {
        return MODULE_NAME;
    }

    // ─────────────────────────────────────────────────────────────
    // REACT METHODS
    // ─────────────────────────────────────────────────────────────

    @ReactMethod
    public void startStreams(Promise promise) {
        synchronized (this) {
            // Cancel any pending stop scheduled on the debounce timer
            if (pendingStopRunnable != null) {
                Log.d(TAG, "startStreams: Canceling pending stop, keeping streams active");
                stopHandler.removeCallbacks(pendingStopRunnable);
                pendingStopRunnable = null;
            }

            if (isStreaming) {
                Log.d(TAG, "startStreams: already streaming, attaching to active view if present");
                try {
                    RGBCameraView view = RGBCameraViewManager.getActiveView();
                    if (view != null && rgbCamera != null) {
                        Log.d(TAG, "Attaching existing camera to new active view");
                        view.attachCamera(rgbCamera);
                    }
                } catch (Throwable e) {
                    Log.e(TAG, "Failed to attach existing camera to view: " + e.getMessage(), e);
                }
                promise.resolve("RGB and Depth streams already running");
                return;
            }

            try {
                // Ensure any lingering streams are cleared before starting new ones
                stopRGB();
                stopDepth();

                startRGBStream();
                startDepthStream();
                isStreaming = true;
                promise.resolve("RGB and Depth streams started");
            } catch (Exception e) {
                Log.e(TAG, "startStreams error", e);
                promise.reject("START_ERROR", e.getMessage());
            }
        }
    }

    @ReactMethod
    public void stopStreams(Promise promise) {
        synchronized (this) {
            if (pendingStopRunnable != null) {
                promise.resolve("Streams stop already scheduled");
                return;
            }

            if (!isStreaming) {
                promise.resolve("Streams already stopped");
                return;
            }

            // Schedule the stop on the handler with a 2-second debounce
            pendingStopRunnable = new Runnable() {
                @Override
                public void run() {
                    synchronized (P2CameraModule.this) {
                        Log.d(TAG, "Executing scheduled stop of camera streams...");
                        stopRGB();
                        stopDepth();
                        isStreaming = false;
                        pendingStopRunnable = null;
                    }
                }
            };
            
            stopHandler.postDelayed(pendingStopRunnable, STOP_DEBOUNCE_DELAY_MS);
            Log.d(TAG, "Scheduled stop of camera streams in " + STOP_DEBOUNCE_DELAY_MS + "ms");
            promise.resolve("Streams stop scheduled");
        }
    }

    @Override
    public void invalidate() {
        super.invalidate();
        synchronized (this) {
            if (pendingStopRunnable != null) {
                stopHandler.removeCallbacks(pendingStopRunnable);
                pendingStopRunnable = null;
            }
            Log.d(TAG, "invalidate: stopping streams immediately");
            stopRGB();
            stopDepth();
            isStreaming = false;
        }
    }

    @ReactMethod
    public void checkLiveness(Promise promise) {
        if (livenessProcessor == null) {
            promise.reject("NO_PROCESSOR", "Liveness processor not initialized");
            return;
        }
        LivenessProcessor.LivenessResult result = livenessProcessor.getLastResult();
        if (result == null) {
            promise.reject("NO_DATA", "No liveness data available yet");
            return;
        }
        WritableMap map = Arguments.createMap();
        map.putBoolean("isLive", result.isLive);
        map.putDouble("confidence", result.confidence);
        map.putString("reasons", String.join(", ", result.reasons));
        promise.resolve(map);
    }

    @ReactMethod
    public void resetLiveness(Promise promise) {
        if (livenessProcessor != null) {
            livenessProcessor.reset();
            Log.d(TAG, "Liveness processor reset by JS call");
        }
        promise.resolve("Liveness reset");
    }

    @ReactMethod
    public void capturePhoto(Promise promise) {
        try {
            RGBCameraView view = RGBCameraViewManager.getActiveView();
            if (view == null) {
                promise.reject("NO_VIEW", "No active camera view found");
                return;
            }
            TextureView textureView = view.getTextureView();
            if (textureView == null || !textureView.isAvailable()) {
                promise.reject("NOT_AVAILABLE", "Camera view not available or not rendering yet");
                return;
            }
            Bitmap bitmap = textureView.getBitmap();
            if (bitmap == null) {
                promise.reject("CAPTURE_FAILED", "Failed to capture bitmap from TextureView");
                return;
            }

            // Save to temporary file in cache
            File file = new File(reactContext.getCacheDir(), "p2_capture_" + System.currentTimeMillis() + ".jpg");
            try (FileOutputStream out = new FileOutputStream(file)) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
                WritableMap map = Arguments.createMap();
                map.putString("uri", "file://" + file.getAbsolutePath());
                map.putInt("width", bitmap.getWidth());
                map.putInt("height", bitmap.getHeight());
                promise.resolve(map);
            } catch (Exception e) {
                promise.reject("SAVE_FAILED", "Failed to save captured bitmap: " + e.getMessage());
            }
        } catch (Exception e) {
            promise.reject("ERROR", e.getMessage());
        }
    }

    @ReactMethod public void addListener(String eventName) {}
    @ReactMethod public void removeListeners(Integer count) {}

    // ─────────────────────────────────────────────────────────────
    // RGB ENGINE
    // ─────────────────────────────────────────────────────────────

    private void startRGBStream() {
        Log.d(TAG, "startRGBStream()");
        synchronized (rgbLock) {
            if (isRgbRunning) {
                Log.d(TAG, "RGB stream already running/starting, skip");
                return;
            }
            isRgbRunning = true;
        }
        lastRgbFrameTime = 0;

        final UVCCameraProxy camera = new UVCCameraProxy(reactContext);
        camera.getConfig().setVendorId(0x2bc5).setProductId(0x051e);

        // CRITICAL: Do NOT call setPreviewTexture / setPreviewSurface / setPreviewDisplay.
        // Keeping mSurface = null in UVCCameraProxy ensures that in startPreview(),
        // nativeSetPreviewDisplay is NOT called. This keeps the native pipeline in
        // CPU-callback mode, which fires IFrameCallback on every frame.
        //
        // We call mUVCCamera.setFrameCallback(RGBX) DIRECTLY (not via setPreviewCallback)
        // because UVCCameraProxy.startPreview() hardcodes PIXEL_FORMAT_YUV which produces
        // corrupt output from MJPEG cameras (the Orbbec P2 is MJPEG-native).
        // PIXEL_FORMAT_RGBX (=3) is always correctly decoded from any input format.

        camera.setConnectCallback(new ConnectCallback() {

            @Override
            public void onAttached(UsbDevice usbDevice) {
                synchronized (rgbLock) {
                    if (!isRgbRunning) return;
                }
                Log.d(TAG, "RGB: attached");
                sendStatusEvent("onRGBStatus", "RGB: attached, requesting permission");
                camera.requestPermission(usbDevice);
            }

            @Override
            public void onGranted(UsbDevice usbDevice, boolean granted) {
                synchronized (rgbLock) {
                    if (!isRgbRunning) return;
                }
                Log.d(TAG, "RGB: permission granted=" + granted);
                sendStatusEvent("onRGBStatus", "RGB: permission granted=" + granted);
                if (granted) camera.connectDevice(usbDevice);
            }

            @Override
            public void onConnected(UsbDevice usbDevice) {
                synchronized (rgbLock) {
                    if (!isRgbRunning) return;
                }
                Log.d(TAG, "RGB: connected, opening camera");
                sendStatusEvent("onRGBStatus", "RGB: connected");
                new Thread(() -> {
                    synchronized (rgbLock) {
                        if (!isRgbRunning) return;
                    }
                    try {
                        camera.openCamera();
                    } catch (Throwable e) {
                        Log.e(TAG, "openCamera error: " + e.getMessage());
                    }
                }).start();
            }

            @Override
            public void onCameraOpened() {
                synchronized (rgbLock) {
                    if (!isRgbRunning) {
                        try {
                            camera.closeCamera();
                        } catch (Throwable ignored) {}
                        return;
                    }
                }
                Log.d(TAG, "RGB: camera opened");
                sendStatusEvent("onRGBStatus", "RGB: camera opened");

                // Step 1 – set preview size
                try {
                    camera.setPreviewSize(PREVIEW_W, PREVIEW_H);
                } catch (Throwable e) {
                    Log.w(TAG, "setPreviewSize failed: " + e.getMessage());
                }

                // Step 2 – Attach to active native RGBCameraView if it exists
                try {
                    RGBCameraView view = RGBCameraViewManager.getActiveView();
                    if (view != null) {
                        Log.d(TAG, "Attaching camera to active native RGBCameraView");
                        view.attachCamera(camera);
                    } else {
                        Log.w(TAG, "No active RGBCameraView found in manager");
                    }
                } catch (Throwable e) {
                    Log.e(TAG, "Failed to attach camera to view: " + e.getMessage(), e);
                }

                synchronized (rgbLock) {
                    if (!isRgbRunning) {
                        try {
                            camera.closeCamera();
                        } catch (Throwable ignored) {}
                        return;
                    }
                    // Step 3 – call startPreview() to start the camera stream
                    camera.startPreview();
                }
                Log.d(TAG, "RGB: startPreview() called");
                sendStatusEvent("onRGBStatus", "RGB: streaming");
            }

            @Override
            public void onCameraOpenFailed() {
                Log.e(TAG, "RGB: camera OPEN FAILED");
                sendStatusEvent("onRGBStatus", "RGB: camera OPEN FAILED");
                synchronized (rgbLock) {
                    isRgbRunning = false;
                }
            }

            @Override
            public void onDetached(UsbDevice usbDevice) {
                Log.d(TAG, "RGB: detached");
                sendStatusEvent("onRGBStatus", "RGB: detached");
            }
        });

        synchronized (rgbLock) {
            rgbCamera = camera;
        }

        try {
            camera.registerReceiver();
            camera.checkDevice();
            Log.d(TAG, "RGB UVCCameraProxy initialized");
        } catch (Throwable e) {
            Log.e(TAG, "RGB init error: " + e.getMessage());
            sendStatusEvent("onRGBStatus", "RGB init error: " + e.getMessage());
            synchronized (rgbLock) {
                isRgbRunning = false;
            }
        }
    }

    private void stopRGB() {
        synchronized (rgbLock) {
            isRgbRunning = false;
            try {
                if (rgbCamera != null) {
                    // stopPreview() calls setFrameCallback(null,0) first — clears our callback
                    rgbCamera.stopPreview();
                    rgbCamera.closeCamera();
                    rgbCamera = null;
                }
            } catch (Throwable ignored) {}
        }
    }

    // ─────────────────────────────────────────────────────────────
    // DEPTH ENGINE
    // ─────────────────────────────────────────────────────────────

    private void startDepthStream() {
        depthEngine = new OpenNIEngine(reactContext, new OpenNIEngine.Listener() {

            @Override
            public void onStatus(String msg) {
                Log.d(TAG, "[Depth] " + msg);
                sendStatusEvent("onDepthStatus", msg);
            }

            @Override
            public void onDepth(Bitmap bmp) {
                if (bmp == null) return;
                try {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    bmp.compress(Bitmap.CompressFormat.JPEG, 70, baos);
                    String base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);

                    WritableMap params = Arguments.createMap();
                    params.putString("data", base64);
                    reactContext
                        .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                        .emit("onDepthFrame", params);
                } catch (Exception e) {
                    Log.e(TAG, "onDepth error", e);
                }
            }

            @Override
            public void onDepthData(short[] depthData, int width, int height) {
                if (livenessProcessor != null) {
                    livenessProcessor.processDepthFrame(depthData, null, width, height);
                    LivenessProcessor.LivenessResult result = livenessProcessor.getLastResult();
                    if (result != null) {
                        WritableMap map = Arguments.createMap();
                        map.putBoolean("isLive", result.isLive);
                        map.putDouble("confidence", result.confidence);
                        map.putString("reasons", String.join(", ", result.reasons));
                        sendEvent("onLivenessResult", map);
                    }
                }
            }
        });
        depthEngine.start();
    }

    private void stopDepth() {
        if (depthEngine != null) {
            depthEngine.stop();
            depthEngine = null;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // EVENT HELPERS
    // ─────────────────────────────────────────────────────────────

    private void sendStatusEvent(String eventName, String message) {
        WritableMap params = Arguments.createMap();
        params.putString("data", message);
        reactContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
            .emit(eventName, params);
    }

    private void sendEvent(String eventName, WritableMap data) {
        reactContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
            .emit(eventName, data);
    }
}
