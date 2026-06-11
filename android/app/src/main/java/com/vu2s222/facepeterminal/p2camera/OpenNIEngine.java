package com.vu2s222.facepeterminal.p2camera;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.util.Log;

import org.openni.Device;
import org.openni.DeviceInfo;
import org.openni.OpenNI;
import org.openni.SensorType;
import org.openni.VideoFrameRef;
import org.openni.VideoMode;
import org.openni.VideoStream;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Engine 1 of the Dual-Engine: Depth + IR via OpenNI2 on Orbbec P2 (PID 0x061e).
 *
 * Critical sequence (proven on Sunmi Pad 3):
 *   1. UsbManager permission for 0x061e
 *   2. UsbManager.openDevice() FIRST  -> keeps the fd alive so native
 *      liborbbecusb2.so can find it via /proc/self/fd
 *   3. OpenNI.initialize() + enumerateDevices() + Device.open()
 *   4. VideoStream.create(DEPTH/IR) + addNewFrameListener (async)
 */
public class OpenNIEngine {

    public interface Listener {
        void onStatus(String msg);
        void onDepth(Bitmap bmp);
        void onDepthData(short[] depthData, int width, int height);
    }

    private static final String TAG = "OpenNIEngine";
    private static final String ACTION_PERM = "ai.facepe.merchantapp.USB_PERM_DEPTH";
    private static final int VID = 0x2bc5;
    private static final int PID_DEPTH = 0x061e;

    private Context ctx;
    private Listener listener;
    private UsbManager usbManager;
    private UsbDevice depthDevice;
    private UsbDeviceConnection depthConn;

    private Device oniDevice;
    private VideoStream depthStream;
    private volatile boolean isRunning = false;

    private final BroadcastReceiver permReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_PERM.equals(intent.getAction())) {
                UsbDevice dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                synchronized (OpenNIEngine.this) {
                    if (!isRunning) {
                        log("Depth permission broadcast received but engine is not running");
                        return;
                    }
                    if (granted && dev != null) {
                        log("Depth USB permission granted");
                        startOpenNI();
                    } else {
                        log("Depth USB permission denied");
                        isRunning = false;
                    }
                }
            }
        }
    };

    public OpenNIEngine(Context ctx, Listener listener) {
        this.ctx = ctx;
        this.listener = listener;
        this.usbManager = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
    }

    public synchronized void start() {
        if (isRunning) {
            log("OpenNI engine already running/starting");
            return;
        }
        isRunning = true;
        log("Searching for Depth device (0x061e)...");
        for (UsbDevice dev : usbManager.getDeviceList().values()) {
            if (dev.getVendorId() == VID && dev.getProductId() == PID_DEPTH) {
                depthDevice = dev;
                log("Found Depth device: " + dev.getDeviceName());
                break;
            }
        }
        if (depthDevice == null) {
            log("Depth device not found!");
            isRunning = false;
            return;
        }

        if (usbManager.hasPermission(depthDevice)) {
            log("Already have Depth permission");
            startOpenNI();
        } else {
            log("Requesting Depth permission...");
            IntentFilter filter = new IntentFilter(ACTION_PERM);
            ctx.registerReceiver(permReceiver, filter);
            
            PendingIntent pi;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                pi = PendingIntent.getBroadcast(ctx, 0, new Intent(ACTION_PERM), 
                    PendingIntent.FLAG_MUTABLE);
            } else {
                pi = PendingIntent.getBroadcast(ctx, 0, new Intent(ACTION_PERM), 
                    PendingIntent.FLAG_IMMUTABLE);
            }
            usbManager.requestPermission(depthDevice, pi);
        }
    }

    private void startOpenNI() {
        new Thread(() -> {
            try {
                synchronized (this) {
                    if (!isRunning) {
                        log("startOpenNI canceled before start");
                        return;
                    }
                }
                
                // CRITICAL: Open USB connection FIRST
                UsbDeviceConnection conn = usbManager.openDevice(depthDevice);
                synchronized (this) {
                    if (!isRunning) {
                        if (conn != null) conn.close();
                        log("startOpenNI canceled after openDevice");
                        return;
                    }
                    depthConn = conn;
                }
                if (depthConn == null) {
                    log("Failed to open Depth USB connection");
                    return;
                }
                log("Depth USB connection opened (fd alive)");

                // Initialize OpenNI2
                OpenNI.initialize();
                synchronized (this) {
                    if (!isRunning) {
                        cleanupOpenNIResources();
                        return;
                    }
                }
                log("OpenNI initialized");

                List<DeviceInfo> devices = OpenNI.enumerateDevices();
                log("Found " + devices.size() + " OpenNI devices");

                if (devices.isEmpty()) {
                    log("No OpenNI devices found!");
                    return;
                }

                DeviceInfo info = devices.get(0);
                log("Opening device: " + info.getName() + " (" + info.getUri() + ")");

                Device dev = Device.open(info.getUri());
                synchronized (this) {
                    if (!isRunning) {
                        if (dev != null) dev.close();
                        cleanupOpenNIResources();
                        return;
                    }
                    oniDevice = dev;
                }
                log("Device opened successfully");

                log("Device opened, skipping IR emitter property");

                // Create depth stream
                VideoStream stream = VideoStream.create(oniDevice, SensorType.DEPTH);
                synchronized (this) {
                    if (!isRunning) {
                        if (stream != null) {
                            stream.destroy();
                        }
                        cleanupOpenNIResources();
                        return;
                    }
                    depthStream = stream;
                }
                
                // Get supported video modes from sensor info and find the best one
                List<VideoMode> modes = depthStream.getSensorInfo().getSupportedVideoModes();
                VideoMode mode = null;
                for (VideoMode m : modes) {
                    if (m.getResolutionX() == 480 && m.getResolutionY() == 640 && m.getFps() == 30) {
                        mode = m;
                        break;
                    }
                }
                
                if (mode == null && !modes.isEmpty()) {
                    mode = modes.get(0); // Use first available mode
                }
                
                if (mode != null) {
                    depthStream.setVideoMode(mode);
                    log("Video mode set: " + mode.getResolutionX() + "x" + mode.getResolutionY() + "@" + mode.getFps() + "fps");
                } else {
                    log("No supported video modes found!");
                    return;
                }

                depthStream.addNewFrameListener(new VideoStream.NewFrameListener() {
                    @Override
                    public void onFrameReady(VideoStream stream) {
                        if (stream == null) {
                            Log.e(TAG, "onFrameReady: stream is null");
                            return;
                        }
                        VideoFrameRef frame = stream.readFrame();
                        if (frame == null) {
                            Log.e(TAG, "onFrameReady: frame is null");
                            return;
                        }
                        try {
                            depthFrameCount++;
                            long now = System.currentTimeMillis();
                            if (now - lastDepthEmit < 33) return; // 30fps throttle
                            lastDepthEmit = now;
                            
                            Log.d(TAG, "onFrameReady: Processing frame #" + depthFrameCount);
                            
                            // Process frame
                            int width = frame.getWidth();
                            int height = frame.getHeight();
                            ByteBuffer data = frame.getData();
                            
                            // Convert to short array for liveness
                            short[] depthArray = new short[width * height];
                            data.rewind();
                            data.asShortBuffer().get(depthArray);
                            
                            // Create bitmap for visualization
                            Bitmap bmp = rawToBitmap(data, width, height);
                            
                            Log.d(TAG, "onFrameReady: Bitmap created: " + (bmp != null) + ", listener: " + (listener != null));
                            
                            if (listener != null) {
                                if (bmp != null) {
                                    Log.d(TAG, "onFrameReady: Calling listener.onDepth()");
                                    listener.onDepth(bmp);
                                    Log.d(TAG, "onFrameReady: listener.onDepth() completed");
                                }
                                listener.onDepthData(depthArray, width, height);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error in depthListener", e);
                        } finally {
                            frame.release();
                        }
                    }
                });

                depthStream.start();
                log("Depth stream started (640x480@30fps)");

            } catch (Exception e) {
                log("OpenNI error: " + e.getMessage());
                e.printStackTrace();
            }
        }).start();
    }

    private long lastDepthEmit = 0;
    private int depthFrameCount = 0;

    private final VideoStream.NewFrameListener depthListener = stream -> {
        if (stream == null) return;
        VideoFrameRef frame = stream.readFrame();
        if (frame == null) return;
        try {
            depthFrameCount++;
            long now = System.currentTimeMillis();
            if (now - lastDepthEmit < 33) return; // 30fps throttle
            lastDepthEmit = now;
            
            // Process frame
            int width = frame.getWidth();
            int height = frame.getHeight();
            ByteBuffer data = frame.getData();
            
            // Convert to short array for liveness
            short[] depthArray = new short[width * height];
            data.rewind();
            data.asShortBuffer().get(depthArray);
            
            // Create bitmap for visualization
            Bitmap bmp = rawToBitmap(data, width, height);
            
            if (listener != null) {
                if (bmp != null) {
                    listener.onDepth(bmp);
                }
                listener.onDepthData(depthArray, width, height);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in depthListener", e);
        } finally {
            frame.release();
        }
    };

    private Bitmap rawToBitmap(ByteBuffer data, int w, int h) {
        try {
            data.rewind();
            int[] px = new int[w * h];
            final int FIXED_MAX = 10000; // fixed range -> stable brightness, no flicker
            
            for (int i = 0; i < w * h && data.remaining() >= 2; i++) {
                int v = data.getShort() & 0xFFFF;
                int g = (v > FIXED_MAX) ? 255 : ((v * 255) / FIXED_MAX);
                px[i] = 0xFF000000 | (g << 16) | (g << 8) | g;
            }
            
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bmp.setPixels(px, 0, w, 0, 0, w, h);
            
            // Rotate 270° to match orientation
            return rotateBitmap(bmp, 270);
        } catch (Exception e) {
            Log.e(TAG, "rawToBitmap error", e);
            return null;
        }
    }

    private Bitmap rotateBitmap(Bitmap source, int angle) {
        android.graphics.Matrix matrix = new android.graphics.Matrix();
        matrix.postRotate(angle);
        return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
    }

    private synchronized void cleanupOpenNIResources() {
        try {
            if (depthStream != null) {
                depthStream.stop();
                depthStream.destroy();
                depthStream = null;
            }
            if (oniDevice != null) {
                oniDevice.close();
                oniDevice = null;
            }
            OpenNI.shutdown();
            if (depthConn != null) {
                depthConn.close();
                depthConn = null;
            }
            log("OpenNI engine resource cleanup completed");
        } catch (Exception e) {
            Log.e(TAG, "Error in cleanupOpenNIResources", e);
        }
    }

    public synchronized void stop() {
        isRunning = false;
        cleanupOpenNIResources();
        try {
            ctx.unregisterReceiver(permReceiver);
        } catch (Exception ignored) {}
        log("OpenNI engine stopped");
    }

    private void log(String msg) {
        Log.d(TAG, msg);
        if (listener != null) {
            listener.onStatus("[Depth] " + msg);
        }
    }
}
