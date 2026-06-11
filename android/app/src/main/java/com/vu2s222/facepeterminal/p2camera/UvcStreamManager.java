package com.vu2s222.facepeterminal.p2camera;

import android.content.Context;
import android.graphics.Bitmap;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Direct UVC streaming via Android USB host API for RGB camera.
 * Sends UVC Probe/Commit control transfers to negotiate a video format,
 * then claims the isochronous/bulk video interface and reads MJPEG frames.
 *
 * The Orbbec P2 "USB 2.0 Camera" (PID=0x051e) exposes MJPEG via UVC.
 */
public class UvcStreamManager {

    private static final String TAG = "UvcStreamManager";

    // UVC control interface constants
    private static final int UVC_SET_CUR     = 0x01;
    private static final int UVC_GET_CUR     = 0x81;
    private static final int UVC_GET_MIN     = 0x82;
    private static final int UVC_GET_MAX     = 0x83;
    private static final int UVC_GET_DEF     = 0x87;

    private static final int VS_PROBE_CONTROL  = 0x0100;
    private static final int VS_COMMIT_CONTROL = 0x0200;

    // UVC bmRequestType for video streaming controls
    private static final int RT_CLASS_INTERFACE_SET = 0x21;
    private static final int RT_CLASS_INTERFACE_GET = 0xA1;

    public interface FrameCallback {
        void onFrame(byte[] jpegOrRaw, int width, int height, String type);
    }

    private final Context context;
    private UsbDeviceConnection rgbConnection;
    private volatile boolean running = false;
    private Thread rgbThread;
    private FrameCallback callback;

    public UvcStreamManager(Context context, FrameCallback callback) {
        this.context = context;
        this.callback = callback;
    }

    /**
     * Start RGB stream from P2 RGB camera (PID 0x051e)
     */
    public void startRGB() {
        Log.d(TAG, "startRGB() called");
        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        HashMap<String, UsbDevice> devices = usbManager.getDeviceList();
        running = true;
        
        Log.d(TAG, "Total USB devices: " + devices.size());

        for (UsbDevice dev : devices.values()) {
            Log.d(TAG, "Checking device: VID=0x" + Integer.toHexString(dev.getVendorId()) 
                + " PID=0x" + Integer.toHexString(dev.getProductId()));
            
            if (dev.getVendorId() != 0x2bc5) continue;
            if (dev.getProductId() != 0x051e) continue; // P2 RGB camera
            
            boolean hasPerm = usbManager.hasPermission(dev);
            Log.d(TAG, "RGB device FOUND: " + dev.getProductName()
                + " PID=0x" + Integer.toHexString(dev.getProductId())
                + " perm=" + hasPerm
                + " ifaces=" + dev.getInterfaceCount());

            if (!hasPerm) {
                Log.w(TAG, "No permission for RGB device — skipping");
                continue;
            }

            Log.d(TAG, "Starting RGB stream...");
            startRGBStream(dev, usbManager);
            break;
        }
        
        Log.d(TAG, "startRGB() completed - RGB device not found or started");
    }

    // ─────────────────────────────────────────────────────────────
    // RGB STREAM — MJPEG via UVC
    // ─────────────────────────────────────────────────────────────

    private void startRGBStream(UsbDevice dev, UsbManager usbManager) {
        Log.d(TAG, "startRGBStream() called for device: " + dev.getDeviceName());
        UsbDeviceConnection conn = usbManager.openDevice(dev);
        if (conn == null) { 
            Log.e(TAG, "Cannot open RGB USB device - conn is null"); 
            return; 
        }
        rgbConnection = conn;
        Log.d(TAG, "RGB USB connection opened successfully");

        Log.d(TAG, "RGB device opened. Interfaces: " + dev.getInterfaceCount());
        logInterfaces(dev);

        // UVC structure: iface[0]=control, iface[1]=zero-bw alt, iface[2+]=alt settings
        // Pick the highest-bandwidth isochronous alt interface that has an endpoint
        UsbInterface controlIface = null;
        UsbInterface bestStreamIface = null;
        int bestMaxPkt = 0;
        
        Log.d(TAG, "Scanning interfaces for UVC control and streaming...");
        for (int i = 0; i < dev.getInterfaceCount(); i++) {
            UsbInterface iface = dev.getInterface(i);
            Log.d(TAG, "  Iface[" + i + "] class=" + iface.getInterfaceClass() 
                + " subclass=" + iface.getInterfaceSubclass());
            
            if (iface.getInterfaceClass() == 14 && iface.getInterfaceSubclass() == 1) {
                controlIface = iface;
                Log.d(TAG, "  -> Found UVC VideoControl interface");
            }
            if (iface.getInterfaceClass() == 14 && iface.getInterfaceSubclass() == 2
                    && iface.getEndpointCount() > 0) {
                UsbEndpoint ep = iface.getEndpoint(0);
                Log.d(TAG, "  -> Found UVC VideoStreaming interface, ep type=" + ep.getType() 
                    + " maxPkt=" + ep.getMaxPacketSize());
                // Check for bulk endpoint (type=2)
                if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK
                        && ep.getMaxPacketSize() > bestMaxPkt) {
                    bestMaxPkt = ep.getMaxPacketSize();
                    bestStreamIface = iface;
                    Log.d(TAG, "  -> Selected as best streaming interface (BULK)");
                }
                // Check for isochronous endpoint (type=1) - only if no bulk found
                else if (bestStreamIface == null 
                        && ep.getType() == UsbConstants.USB_ENDPOINT_XFER_ISOC
                        && ep.getMaxPacketSize() > bestMaxPkt) {
                    bestMaxPkt = ep.getMaxPacketSize();
                    bestStreamIface = iface;
                    Log.d(TAG, "  -> Selected as best streaming interface (ISO - no bulk found)");
                }
            }
        }

        if (controlIface != null) {
            boolean claimed = conn.claimInterface(controlIface, true);
            Log.d(TAG, "Control interface claimed: " + claimed);
        } else {
            Log.w(TAG, "No control interface found");
        }

        if (bestStreamIface == null) {
            Log.e(TAG, "No iso streaming interface found on RGB device");
            return;
        }

        // Use alt interface with maxPkt ~2848 (interface 5) for MJPEG at decent resolution
        // Or pick best available
        UsbInterface streamIface = bestStreamIface;
        UsbEndpoint ep = streamIface.getEndpoint(0);
        boolean claimed = conn.claimInterface(streamIface, true);
        Log.d(TAG, "RGB iface[" + streamIface.getId() + "] claimed=" + claimed
            + " ep=0x" + Integer.toHexString(ep.getAddress())
            + " type=" + ep.getType()
            + " maxPkt=" + ep.getMaxPacketSize());

        // Send UVC Probe/Commit on control interface
        Log.d(TAG, "Starting UVC format negotiation...");
        boolean negotiated = negotiateUVCFormat(conn, streamIface, dev, 640, 480, 333333);
        Log.d(TAG, "RGB UVC negotiation result: " + negotiated);

        final UsbEndpoint finalEp = ep;
        final int pktSize = ep.getMaxPacketSize();
        final boolean isBulk = (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK);

        rgbThread = new Thread(() -> {
            Log.d(TAG, "RGB stream thread started, ep type=" + ep.getType() + " (bulk=" + isBulk + "), maxPkt=" + pktSize);
            
            // Skip UsbRequest entirely - it doesn't work with isochronous endpoints
            // Use bulkTransfer directly for both bulk and isochronous
            final int BUF_SIZE = Math.max(pktSize * 16, 65536);
            ByteArrayOutputStream acc = new ByteArrayOutputStream();
            int frameCount = 0;
            byte[] buf = new byte[BUF_SIZE];
            
            Log.d(TAG, "Starting direct bulkTransfer read loop with BUF_SIZE=" + BUF_SIZE);
            
            while (running) {
                int n = conn.bulkTransfer(finalEp, buf, buf.length, 1000);
                if (n < 0) {
                    if (frameCount == 0) {
                        Log.w(TAG, "RGB bulkTransfer no data: " + n + " (frameCount=" + frameCount + ")");
                    }
                    try { Thread.sleep(10); } catch (InterruptedException e) { break; }
                    continue;
                }
                if (n == 0) continue;
                
                if (frameCount == 0) {
                    Log.d(TAG, "RGB FIRST DATA: " + n + " bytes, head=0x"
                        + Integer.toHexString(buf[0] & 0xFF)
                        + " 0x" + Integer.toHexString(buf[1] & 0xFF));
                }
                
                parseUVCPayload(buf, n, acc, "RGB");
                frameCount++;
                
                if (frameCount % 30 == 0) {
                    Log.d(TAG, "RGB frames: " + frameCount);
                }
            }
            Log.d(TAG, "RGB stream thread stopped, frames=" + frameCount);
        });
        rgbThread.setName("UVC-RGB");
        rgbThread.start();
        Log.d(TAG, "RGB thread started");
    }

    // ─────────────────────────────────────────────────────────────
    // UVC FRAME PARSER
    // ─────────────────────────────────────────────────────────────

    private void parseUVCPayload(byte[] data, int len, ByteArrayOutputStream acc, String type) {
        if (len < 2) return;

        int hdrLen = data[0] & 0xFF;
        int hdrInfo = data[1] & 0xFF;
        boolean eof = (hdrInfo & 0x02) != 0; // End of frame bit

        if (hdrLen > len) return;
        int payloadOffset = hdrLen;
        int payloadLen = len - payloadOffset;

        if (payloadLen > 0) {
            acc.write(data, payloadOffset, payloadLen);
        }

        if (eof && acc.size() > 0) {
            byte[] frameData = acc.toByteArray();
            acc.reset();

            // Check if it's a JPEG (starts with FFD8)
            if (frameData.length >= 2
                && (frameData[0] & 0xFF) == 0xFF
                && (frameData[1] & 0xFF) == 0xD8) {
                Log.d(TAG, type + " JPEG frame: " + frameData.length + " bytes");
                callback.onFrame(frameData, 640, 480, type);
            } else if (frameData.length >= 4) {
                Log.d(TAG, type + " raw frame: " + frameData.length + " bytes");
                callback.onFrame(frameData, 640, 480, type);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // UVC NEGOTIATION
    // ─────────────────────────────────────────────────────────────

    private boolean negotiateUVCFormat(UsbDeviceConnection conn, UsbInterface streamIface,
                                        UsbDevice dev, int width, int height, int interval) {
        try {
            int vsIface = streamIface.getId();

            // Step 1: GET_DEF Probe — device reports its default/preferred format+frame
            byte[] defProbe = new byte[34];
            int ret = conn.controlTransfer(
                RT_CLASS_INTERFACE_GET, UVC_GET_DEF,
                VS_PROBE_CONTROL, vsIface,
                defProbe, defProbe.length, 1000);
            Log.d(TAG, "iface[" + vsIface + "] GET_DEF Probe: " + ret
                + " fmtIdx=" + (defProbe[2] & 0xFF)
                + " frameIdx=" + (defProbe[3] & 0xFF));

            // Step 2: GET_MIN Probe
            byte[] minProbe = new byte[34];
            conn.controlTransfer(RT_CLASS_INTERFACE_GET, UVC_GET_MIN,
                VS_PROBE_CONTROL, vsIface, minProbe, minProbe.length, 1000);

            // Step 3: Use device's default values (device-led negotiation)
            // Override frame interval if desired but keep format/frame from device default
            byte[] proposeProbe;
            if (ret >= 26 && (defProbe[2] & 0xFF) != 0) {
                // Device gave us a valid default — use it
                proposeProbe = defProbe.clone();
                Log.d(TAG, "Using device default: fmtIdx=" + (proposeProbe[2] & 0xFF)
                    + " frameIdx=" + (proposeProbe[3] & 0xFF));
            } else {
                // Fallback: build our own probe with format=1 frame=1
                proposeProbe = buildProbeControl(1, 1, interval, width, height);
                Log.d(TAG, "Using fallback probe: fmt=1 frame=1 " + width + "x" + height);
            }

            // Step 4: SET_CUR Probe with proposed values
            ret = conn.controlTransfer(
                RT_CLASS_INTERFACE_SET, UVC_SET_CUR,
                VS_PROBE_CONTROL, vsIface,
                proposeProbe, proposeProbe.length, 1000);
            Log.d(TAG, "iface[" + vsIface + "] SET_CUR Probe: " + ret);

            // Step 5: GET_CUR Probe — read back what device accepted
            byte[] curProbe = new byte[34];
            ret = conn.controlTransfer(
                RT_CLASS_INTERFACE_GET, UVC_GET_CUR,
                VS_PROBE_CONTROL, vsIface,
                curProbe, curProbe.length, 1000);
            if (ret >= 4) {
                int fmtIdx   = curProbe[2] & 0xFF;
                int frameIdx = curProbe[3] & 0xFF;
                java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(curProbe, 4, 4)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN);
                int frameInterval = bb.getInt();
                Log.d(TAG, "iface[" + vsIface + "] GET_CUR result: fmtIdx=" + fmtIdx
                    + " frameIdx=" + frameIdx
                    + " interval=" + frameInterval + " (" + (10000000/Math.max(1,frameInterval)) + "fps)");
            }

            // Step 6: SET_CUR Commit with accepted values
            ret = conn.controlTransfer(
                RT_CLASS_INTERFACE_SET, UVC_SET_CUR,
                VS_COMMIT_CONTROL, vsIface,
                curProbe, curProbe.length, 1000);
            Log.d(TAG, "iface[" + vsIface + "] SET_CUR Commit: " + ret);

            return ret >= 0;
        } catch (Exception e) {
            Log.e(TAG, "UVC negotiation error on iface[" + streamIface.getId() + "]", e);
            return false;
        }
    }

    private byte[] buildProbeControl(int formatIndex, int frameIndex, int frameInterval,
                                      int width, int height) {
        ByteBuffer buf = ByteBuffer.allocate(34);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) 0x0000);    // bmHint
        buf.put((byte) formatIndex);     // bFormatIndex (1=MJPEG)
        buf.put((byte) frameIndex);      // bFrameIndex
        buf.putInt(frameInterval);       // dwFrameInterval (100ns units, 333333=30fps)
        buf.putShort((short) 0);         // wKeyFrameRate
        buf.putShort((short) 0);         // wPFrameRate
        buf.putShort((short) 0);         // wCompQuality
        buf.putShort((short) 0);         // wCompWindowSize
        buf.putShort((short) 0);         // wDelay
        buf.putInt(width * height * 2);  // dwMaxVideoFrameSize
        buf.putInt(0x00000000);          // dwMaxPayloadTransferSize
        buf.putInt(0);                   // dwClockFrequency
        buf.put((byte) 0);              // bmFramingInfo
        buf.put((byte) 0);              // bPreferredVersion
        buf.put((byte) 0);              // bMinVersion
        buf.put((byte) 0);              // bMaxVersion
        return buf.array();
    }

    // ─────────────────────────────────────────────────────────────
    // USB INTERFACE HELPERS
    // ─────────────────────────────────────────────────────────────

    private void logInterfaces(UsbDevice dev) {
        for (int i = 0; i < dev.getInterfaceCount(); i++) {
            UsbInterface iface = dev.getInterface(i);
            Log.d(TAG, "  Interface[" + i + "] class=" + iface.getInterfaceClass()
                + " subclass=" + iface.getInterfaceSubclass()
                + " protocol=" + iface.getInterfaceProtocol()
                + " endpoints=" + iface.getEndpointCount());
            for (int j = 0; j < iface.getEndpointCount(); j++) {
                UsbEndpoint ep = iface.getEndpoint(j);
                Log.d(TAG, "    EP[" + j + "] addr=0x" + Integer.toHexString(ep.getAddress())
                    + " type=" + ep.getType()
                    + " dir=" + ep.getDirection()
                    + " maxPkt=" + ep.getMaxPacketSize());
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // STOP
    // ─────────────────────────────────────────────────────────────

    public void stop() {
        running = false;
        if (rgbThread != null) { rgbThread.interrupt(); rgbThread = null; }
        if (rgbConnection != null) { rgbConnection.close(); rgbConnection = null; }
        Log.d(TAG, "UvcStreamManager stopped");
    }
}
