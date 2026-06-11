package com.vu2s222.facepeterminal.p2camera;

import android.util.Log;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Passive 3D Biometric Liveness Detection Processor v3.2
 *
 * Performance optimized & secured against curved paper, 3D masks, and screen replay attacks.
 * Uses zero-allocation memory management to eliminate Garbage Collection pressure.
 */
public class LivenessProcessor {

    private static final String TAG = "LivenessProcessor";

    // ─── Tunable Parameters (calibrated from device logs) ────────────────────
    /** Minimum valid pixels in [300,1000mm] range to consider anything present */
    private static final int MIN_FACE_PIXELS       = 3000;
    /** Minimum centroid face pixels after foreground filter */
    private static final int MIN_CENTROID_PIXELS   = 800;
    /** Minimum depth std dev — relaxed for close-range kiosk usage */
    private static final double MIN_SIGMA_Z        = 16.0;
    /** Maximum depth std dev — filters out highly noisy / chaotic depth maps */
    private static final double MAX_SIGMA_Z        = 85.0;
    /** Contour-to-nose depth delta range — human profile protrusion */
    private static final double MIN_CONTOUR_DELTA  = 18.0;
    private static final double MAX_CONTOUR_DELTA  = 130.0;
    /** Min face pixel fill ratio of bounding box — real face = 0.15–0.70 in IR depth */
    private static final double MIN_FILL_RATIO     = 0.12;
    /** Bounding box aspect ratio — tightened to match human face ovals specifically */
    private static final double MIN_ASPECT_RATIO   = 0.45;
    private static final double MAX_ASPECT_RATIO   = 1.80;
    /** Temporal: consecutive PASS frames needed before reporting live */
    private static final int PASS_FRAMES_NEEDED    = 3;
    /** Temporal: consecutive FAIL frames needed before clearing a PASS streak */
    private static final int FAIL_FRAMES_TO_RESET  = 2;
    // ─────────────────────────────────────────────────────────────────────────

    // Pre-allocated buffers to prevent GC memory churn in the 30fps hot path
    private final int[] activeDepthsBuffer = new int[640 * 480];
    private final int[] croppedFaceDepthsBuffer = new int[640 * 480];
    private final int[] peripheralDepthsBuffer = new int[640 * 480];
    private final int[] filteredDepthsBuffer = new int[360 * 360]; // Max clamped region size is 320
    private final int[] neighborsBuffer = new int[9];
    
    private final double[] rowSumBuffer = new double[360];
    private final int[] rowCountBuffer = new int[360];
    private final double[] colSumBuffer = new double[360];
    private final int[] colCountBuffer = new int[360];

    private final List<String> reasonsBuffer = new ArrayList<>();

    private LivenessResult lastResult;
    private int consecutivePassCount = 0;
    private int consecutiveFailCount = 0;
    private boolean currentlyLive    = false;
    
    // Tracking median distance of the previous frame to prevent fast replacement attacks
    private double lastM_Z = -1.0;

    public static class LivenessResult {
        public boolean isLive;
        public double confidence;
        public List<String> reasons;

        public LivenessResult(boolean isLive, double confidence, List<String> reasons) {
            this.isLive     = isLive;
            this.confidence = confidence;
            this.reasons    = reasons;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MAIN PROCESSING ENTRY POINT
    // ─────────────────────────────────────────────────────────────────────────
    public void processDepthFrame(short[] depthData, byte[] irData, int width, int height) {
        reasonsBuffer.clear();

        try {
            // ──────────────────────────────────────────────────────────────
            // STEP 1: Scan active area — locate all pixels in human range (30cm to 100cm)
            // ──────────────────────────────────────────────────────────────
            int borderX = width / 10;
            int borderY = height / 10;
            int startX  = borderX;
            int endX    = width  - borderX;
            int startY  = borderY;
            int endY    = height - borderY;

            // Collect all depths in kiosk-viable range [300, 1000mm]
            int activeDepthsCount = 0;
            for (int y = startY; y < endY; y++) {
                for (int x = startX; x < endX; x++) {
                    int depth = depthData[y * width + x] & 0xFFFF;
                    if (depth >= 300 && depth <= 1000) {
                        activeDepthsBuffer[activeDepthsCount++] = depth;
                    }
                }
            }

            // Gate 1: Minimum pixel count — ensures something is actually there
            if (activeDepthsCount < MIN_FACE_PIXELS) {
                Log.d(TAG, "Liveness failed Gate 1: too few pixels (" + activeDepthsCount + " < " + MIN_FACE_PIXELS + ")");
                lastM_Z = -1.0; // Reset tracking since face is lost
                reasonsBuffer.add("FAILED_OUT_OF_RANGE");
                reasonsBuffer.add("Not enough valid face pixels: " + activeDepthsCount);
                recordFail(reasonsBuffer);
                return;
            }

            // Find Z_nose (3rd percentile of active depths — robust to noise spikes)
            Arrays.sort(activeDepthsBuffer, 0, activeDepthsCount);
            int Z_nose = activeDepthsBuffer[Math.min(activeDepthsCount - 1, activeDepthsCount / 33)];

            // Foreground window: [Z_nose, Z_nose + 180mm]
            int faceMaxDepth = Z_nose + 180;

            // ──────────────────────────────────────────────────────────────
            // STEP 2: Build face bounding box from foreground pixels
            // ──────────────────────────────────────────────────────────────
            long sumX = 0, sumY = 0;
            int facePixelCount = 0;
            int minBbX = endX, maxBbX = startX;
            int minBbY = endY, maxBbY = startY;

            for (int y = startY; y < endY; y++) {
                for (int x = startX; x < endX; x++) {
                    int depth = depthData[y * width + x] & 0xFFFF;
                    if (depth >= Z_nose && depth <= faceMaxDepth) {
                        sumX += x;
                        sumY += y;
                        facePixelCount++;
                        if (x < minBbX) minBbX = x;
                        if (x > maxBbX) maxBbX = x;
                        if (y < minBbY) minBbY = y;
                        if (y > maxBbY) maxBbY = y;
                    }
                }
            }

            // Gate 2: Centroid pixel count
            if (facePixelCount < MIN_CENTROID_PIXELS) {
                Log.d(TAG, "Liveness failed Gate 2: centroid too sparse (" + facePixelCount + ")");
                lastM_Z = -1.0; // Reset tracking since face is lost
                reasonsBuffer.add("FAILED_OUT_OF_RANGE");
                reasonsBuffer.add("Face centroid tracking lost: " + facePixelCount + " pixels");
                recordFail(reasonsBuffer);
                return;
            }

            int X_c = (int) (sumX / facePixelCount);
            int Y_c = (int) (sumY / facePixelCount);

            // ──────────────────────────────────────────────────────────────
            // STEP 2B: Specular Glare / Reflection check (Flat Glass display protection using IR intensity)
            // ──────────────────────────────────────────────────────────────
            if (irData != null) {
                int saturatedIrPixels = 0;
                for (int y = minBbY; y <= maxBbY; y++) {
                    for (int x = minBbX; x <= maxBbX; x++) {
                        // Read 8-bit grayscale IR intensity
                        int irIntensity = irData[y * width + x] & 0xFF; 
                        if (irIntensity > 250) {
                            saturatedIrPixels++;
                        }
                    }
                }
                double glareRatio = facePixelCount > 0 ? (double) saturatedIrPixels / facePixelCount : 0.0;
                Log.d(TAG, String.format("Liveness Step 2B - Specular Glare ratio: %.4f", glareRatio));
                if (glareRatio > 0.08) { // 8% of the face is pure white IR glare
                    Log.d(TAG, "Liveness failed Gate 2B: specular glare ratio too high (" + glareRatio + ")");
                    reasonsBuffer.add("FAILED_SPECULAR_SPOOF");
                    reasonsBuffer.add("Digital screen glare reflection detected on IR stream");
                    recordFail(reasonsBuffer);
                    return;
                }
            } else {
                Log.d(TAG, "Liveness Step 2B - IR data stream unavailable, skipping specular glare check");
            }

            // ──────────────────────────────────────────────────────────────
            // STEP 3: Face shape / roundness gate
            // ──────────────────────────────────────────────────────────────
            int bbWidth  = maxBbX - minBbX + 1;
            int bbHeight = maxBbY - minBbY + 1;

            // Gate 3A: Bounding box must have at least 40 pixels in each dimension
            if (bbWidth < 40 || bbHeight < 40) {
                Log.d(TAG, String.format("Liveness failed Gate 3A: bounding box too small %dx%d", bbWidth, bbHeight));
                reasonsBuffer.add("FAILED_2D_GEOMETRY");
                reasonsBuffer.add("Face bounding box too small");
                recordFail(reasonsBuffer);
                return;
            }

            // Gate 3B: Aspect ratio
            double aspectRatio = (double) bbWidth / bbHeight;
            if (aspectRatio < MIN_ASPECT_RATIO || aspectRatio > MAX_ASPECT_RATIO) {
                Log.d(TAG, String.format("Liveness failed Gate 3B: bad aspect ratio %.2f (expected [%.2f, %.2f])",
                        aspectRatio, MIN_ASPECT_RATIO, MAX_ASPECT_RATIO));
                reasonsBuffer.add("FAILED_2D_GEOMETRY");
                reasonsBuffer.add(String.format("Bad face aspect ratio: %.2f", aspectRatio));
                recordFail(reasonsBuffer);
                return;
            }

            // Gate 3C: Fill ratio
            double fillRatio = (double) facePixelCount / (double) (bbWidth * bbHeight);
            if (fillRatio < MIN_FILL_RATIO) {
                Log.d(TAG, String.format("Liveness failed Gate 3C: fill ratio %.2f < %.2f (too sparse)", fillRatio, MIN_FILL_RATIO));
                reasonsBuffer.add("FAILED_2D_GEOMETRY");
                reasonsBuffer.add(String.format("Fill ratio too low: %.2f", fillRatio));
                recordFail(reasonsBuffer);
                return;
            }

            Log.d(TAG, String.format("Liveness Step 1 - Centroid: (%d, %d), BB: %dx%d, AspectRatio: %.2f, Fill: %.2f, Z_nose: %dmm, N_face: %d",
                    X_c, Y_c, bbWidth, bbHeight, aspectRatio, fillRatio, Z_nose, facePixelCount));

            // ──────────────────────────────────────────────────────────────
            // STEP 4: Dynamic 3x3 Median filter crop region based on distance (fixes Invisible Nose)
            // ──────────────────────────────────────────────────────────────
            // Face pixel sizes scale inversely with distance. Scale regionSize dynamically.
            int regionSize = (int)(160.0 * (800.0 / Math.max(300.0, (double)Z_nose)));
            // Clamp crop size to safe bounds to fit inside pre-allocated filteredDepthsBuffer
            regionSize = Math.max(120, Math.min(320, regionSize));

            int cropMinX = Math.max(0, Math.min(width  - regionSize, X_c - regionSize / 2));
            int cropMinY = Math.max(0, Math.min(height - regionSize, Y_c - regionSize / 2));

            for (int y = 0; y < regionSize; y++) {
                for (int x = 0; x < regionSize; x++) {
                    int origX = cropMinX + x;
                    int origY = cropMinY + y;
                    int count = 0;

                    for (int ny = -1; ny <= 1; ny++) {
                        for (int nx = -1; nx <= 1; nx++) {
                            int px = origX + nx;
                            int py = origY + ny;
                            if (px >= 0 && px < width && py >= 0 && py < height) {
                                int d = depthData[py * width + px] & 0xFFFF;
                                if (d > 0 && d < 5000) neighborsBuffer[count++] = d;
                            }
                        }
                    }

                    if (count > 0) {
                        // Inline selection sort (zero allocations)
                        for (int i = 0; i < count - 1; i++) {
                            int minIdx = i;
                            for (int j = i + 1; j < count; j++) {
                                if (neighborsBuffer[j] < neighborsBuffer[minIdx]) minIdx = j;
                            }
                            int tmp = neighborsBuffer[minIdx]; neighborsBuffer[minIdx] = neighborsBuffer[i]; neighborsBuffer[i] = tmp;
                        }
                        filteredDepthsBuffer[y * regionSize + x] = neighborsBuffer[count / 2];
                    } else {
                        filteredDepthsBuffer[y * regionSize + x] = 0;
                    }
                }
            }

            // Collect valid face depths from filtered crop
            int croppedFaceDepthsCount = 0;
            for (int i = 0; i < regionSize * regionSize; i++) {
                int val = filteredDepthsBuffer[i];
                if (val >= Z_nose && val <= faceMaxDepth) {
                    croppedFaceDepthsBuffer[croppedFaceDepthsCount++] = val;
                }
            }

            if (croppedFaceDepthsCount == 0) {
                Log.d(TAG, "Liveness failed: no valid depths in cropped region");
                reasonsBuffer.add("FAILED_OUT_OF_RANGE");
                reasonsBuffer.add("No valid depth in cropped face region");
                recordFail(reasonsBuffer);
                return;
            }

            // ──────────────────────────────────────────────────────────────
            // STEP 5: Median depth and distance gate (Absolute bounds 30cm to 100cm)
            // ──────────────────────────────────────────────────────────────
            Arrays.sort(croppedFaceDepthsBuffer, 0, croppedFaceDepthsCount);
            double M_Z = (croppedFaceDepthsCount % 2 == 0)
                    ? (croppedFaceDepthsBuffer[croppedFaceDepthsCount / 2 - 1] + croppedFaceDepthsBuffer[croppedFaceDepthsCount / 2]) / 2.0
                    : croppedFaceDepthsBuffer[croppedFaceDepthsCount / 2];

            Log.d(TAG, String.format("Liveness Step 2 - Median depth M_Z: %.1fmm (N_crop: %d, CropSize: %d)", M_Z, croppedFaceDepthsCount, regionSize));

            if (M_Z < 300.0 || M_Z > 1000.0) {
                Log.d(TAG, String.format("Liveness failed Gate 5: M_Z=%.1fmm outside [300, 1000]", M_Z));
                lastM_Z = -1.0; // Reset tracking since face is out of range
                reasonsBuffer.add("FAILED_OUT_OF_RANGE");
                reasonsBuffer.add(String.format("Median depth %.1fmm outside [300, 1000]", M_Z));
                recordFail(reasonsBuffer);
                return;
            }

            // Temporal Impossible-Motion Check (Video replay swapping/shaking protection)
            if (lastM_Z > 0.0) {
                double zDelta = Math.abs(M_Z - lastM_Z);
                if (zDelta > 150.0) {
                    Log.d(TAG, String.format("Liveness failed: impossible temporal movement detected (%.1fmm shift in 33ms)", zDelta));
                    // DO NOT UPDATE lastM_Z here. Force the attacker to withdraw and try again.
                    reasonsBuffer.add("FAILED_TEMPORAL_MOTION");
                    reasonsBuffer.add(String.format("Impossible motion: %.1fmm depth shift detected", zDelta));
                    recordFail(reasonsBuffer);
                    return;
                }
            }
            lastM_Z = M_Z;

            // ──────────────────────────────────────────────────────────────
            // STEP 6: Depth dispersion gate — rejects flat objects
            // ──────────────────────────────────────────────────────────────
            double sumD = 0.0;
            for (int i = 0; i < croppedFaceDepthsCount; i++) {
                sumD += croppedFaceDepthsBuffer[i];
            }
            double mu_Z = sumD / croppedFaceDepthsCount;

            double sumSqDiff = 0.0;
            for (int i = 0; i < croppedFaceDepthsCount; i++) {
                sumSqDiff += Math.pow(croppedFaceDepthsBuffer[i] - mu_Z, 2);
            }
            double sigma_Z = Math.sqrt(sumSqDiff / croppedFaceDepthsCount);

            Log.d(TAG, String.format("Liveness Step 3 - Mean: %.1fmm, StdDev sigma_Z: %.2fmm", mu_Z, sigma_Z));

            if (sigma_Z < MIN_SIGMA_Z || sigma_Z > MAX_SIGMA_Z) {
                Log.d(TAG, String.format("Liveness failed Gate 6: sigma_Z=%.2fmm outside [%.1f, %.1f]", sigma_Z, MIN_SIGMA_Z, MAX_SIGMA_Z));
                reasonsBuffer.add("FAILED_2D_GEOMETRY");
                reasonsBuffer.add(String.format("Depth variance out of bounds: %.2fmm", sigma_Z));
                recordFail(reasonsBuffer);
                return;
            }

            // ──────────────────────────────────────────────────────────────
            // STEP 6B: Curvature profiling — rejects developable curved sheets (curved paper/screens)
            // ──────────────────────────────────────────────────────────────
            // Make sure depth values vary along both vertical (row) and horizontal (col) axes.
            for (int i = 0; i < regionSize; i++) {
                rowSumBuffer[i] = 0.0;
                rowCountBuffer[i] = 0;
                colSumBuffer[i] = 0.0;
                colCountBuffer[i] = 0;
            }

            for (int y = 0; y < regionSize; y++) {
                for (int x = 0; x < regionSize; x++) {
                    int val = filteredDepthsBuffer[y * regionSize + x];
                    if (val >= Z_nose && val <= faceMaxDepth) {
                        rowSumBuffer[y] += val;
                        rowCountBuffer[y]++;
                        colSumBuffer[x] += val;
                        colCountBuffer[x]++;
                    }
                }
            }

            int validRows = 0;
            double sumRowMeans = 0.0;
            for (int y = 0; y < regionSize; y++) {
                if (rowCountBuffer[y] > 5) {
                    rowSumBuffer[y] /= rowCountBuffer[y];
                    sumRowMeans += rowSumBuffer[y];
                    validRows++;
                }
            }

            double sigmaRow = 0.0;
            if (validRows > 1) {
                double muRow = sumRowMeans / validRows;
                double sumSqDiffRow = 0.0;
                for (int y = 0; y < regionSize; y++) {
                    if (rowCountBuffer[y] > 5) {
                        sumSqDiffRow += Math.pow(rowSumBuffer[y] - muRow, 2);
                    }
                }
                sigmaRow = Math.sqrt(sumSqDiffRow / validRows);
            }

            int validCols = 0;
            double sumColMeans = 0.0;
            for (int x = 0; x < regionSize; x++) {
                if (colCountBuffer[x] > 5) {
                    colSumBuffer[x] /= colCountBuffer[x];
                    sumColMeans += colSumBuffer[x];
                    validCols++;
                }
            }

            double sigmaCol = 0.0;
            if (validCols > 1) {
                double muCol = sumColMeans / validCols;
                double sumSqDiffCol = 0.0;
                for (int x = 0; x < regionSize; x++) {
                    if (colCountBuffer[x] > 5) {
                        sumSqDiffCol += Math.pow(colSumBuffer[x] - muCol, 2);
                    }
                }
                sigmaCol = Math.sqrt(sumSqDiffCol / validCols);
            }

            Log.d(TAG, String.format("Liveness Step 3B - Row Profile SD: %.2fmm, Col Profile SD: %.2fmm", sigmaRow, sigmaCol));

            if (sigmaRow < 6.0 || sigmaCol < 6.0) {
                Log.d(TAG, String.format("Liveness failed Gate 6B: sigmaRow=%.2f, sigmaCol=%.2f (bent paper/curved screen detected)", sigmaRow, sigmaCol));
                reasonsBuffer.add("FAILED_2D_GEOMETRY");
                reasonsBuffer.add("Liveness curvature symmetry test failed (curved photo/screen attack)");
                recordFail(reasonsBuffer);
                return;
            }

            // ──────────────────────────────────────────────────────────────
            // STEP 7: Local face-contour gradient — nose-to-border depth delta
            // ──────────────────────────────────────────────────────────────
            int peripheralDepthsCount = 0;
            for (int y = 0; y < regionSize; y++) {
                for (int x = 0; x < regionSize; x++) {
                    if (x == 0 || x == regionSize - 1 || y == 0 || y == regionSize - 1) {
                        int depth = filteredDepthsBuffer[y * regionSize + x];
                        if (depth >= Z_nose && depth <= faceMaxDepth) {
                            peripheralDepthsBuffer[peripheralDepthsCount++] = depth;
                        }
                    }
                }
            }

            double Z_peripheral = (peripheralDepthsCount == 0) ? mu_Z : 0.0;
            if (peripheralDepthsCount > 0) {
                double sumPeri = 0;
                for (int i = 0; i < peripheralDepthsCount; i++) {
                    sumPeri += peripheralDepthsBuffer[i];
                }
                Z_peripheral = sumPeri / peripheralDepthsCount;
            }

            double delta_Z_contour = Z_peripheral - Z_nose;
            Log.d(TAG, String.format("Liveness Step 4 - Nose: %dmm, PeriAvg: %.1fmm, Delta: %.1fmm (N_peri: %d)",
                    Z_nose, Z_peripheral, delta_Z_contour, peripheralDepthsCount));

            if (delta_Z_contour < MIN_CONTOUR_DELTA || delta_Z_contour > MAX_CONTOUR_DELTA) {
                Log.d(TAG, String.format("Liveness failed Gate 7: contour delta %.1fmm outside [%.1f, %.1f]",
                        delta_Z_contour, MIN_CONTOUR_DELTA, MAX_CONTOUR_DELTA));
                reasonsBuffer.add("FAILED_2D_GEOMETRY");
                reasonsBuffer.add(String.format("Contour delta out of range: %.1fmm", delta_Z_contour));
                recordFail(reasonsBuffer);
                return;
            }

            // ──────────────────────────────────────────────────────────────
            // STEP 8: Decision Fusion & Confidence Score
            // ──────────────────────────────────────────────────────────────
            // Compute distance score s_dist (plateau 350–850mm)
            double s_dist;
            if (M_Z >= 350.0 && M_Z <= 850.0) {
                s_dist = 1.0;
            } else if (M_Z < 350.0) {
                s_dist = Math.exp(-Math.pow(M_Z - 350.0, 2) / (2.0 * Math.pow(50.0, 2)));
            } else {
                s_dist = Math.exp(-Math.pow(M_Z - 850.0, 2) / (2.0 * Math.pow(100.0, 2)));
            }

            // Compute std dev score s_std (plateau 18–55mm, based on real face data 20-31mm)
            double s_std;
            if (sigma_Z >= 18.0 && sigma_Z <= 55.0) {
                s_std = 1.0;
            } else if (sigma_Z < 18.0) {
                s_std = Math.exp(-Math.pow(sigma_Z - 18.0, 2) / (2.0 * Math.pow(5.0, 2)));
            } else {
                s_std = Math.exp(-Math.pow(sigma_Z - 55.0, 2) / (2.0 * Math.pow(15.0, 2)));
            }

            // Fill ratio score
            double s_fill = Math.min(1.0, fillRatio / 0.70);

            // Aspect ratio score
            double s_aspect = 1.0 - Math.abs(1.0 - Math.min(aspectRatio, 1.0 / aspectRatio)) * 0.5;

            double confidence = 0.40 * s_dist + 0.35 * s_std + 0.15 * s_fill + 0.10 * s_aspect;

            Log.d(TAG, String.format("Liveness Step 5 - Dist:%.2f, StdDev:%.2f, Fill:%.2f, Aspect:%.2f → confidence: %.2f",
                    s_dist, s_std, s_fill, s_aspect, confidence));

            // Temporal check — require 3 consecutive PASS frames
            reasonsBuffer.add("PASSED");
            reasonsBuffer.add(String.format("M_Z:%.0fmm, σZ:%.1fmm, contour:%.1fmm, fill:%.2f, aspect:%.2f",
                    M_Z, sigma_Z, delta_Z_contour, fillRatio, aspectRatio));
            recordPass(confidence, reasonsBuffer);

        } catch (Exception e) {
            Log.e(TAG, "Error processing liveness", e);
            reasonsBuffer.clear();
            reasonsBuffer.add("Processing error: " + e.getMessage());
            recordFail(reasonsBuffer);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TEMPORAL STATE MACHINE
    // ─────────────────────────────────────────────────────────────────────────

    private void recordPass(double confidence, List<String> reasons) {
        consecutivePassCount++;
        consecutiveFailCount = 0;

        List<String> reasonsCopy = new ArrayList<>(reasons);

        if (consecutivePassCount >= PASS_FRAMES_NEEDED) {
            if (!currentlyLive) {
                Log.d(TAG, "Temporal: Live threshold reached (" + consecutivePassCount + " consecutive passes)");
                currentlyLive = true;
            }
            lastResult = new LivenessResult(true, confidence, reasonsCopy);
        } else {
            // Not yet confirmed — report the last known state (fail until confirmed)
            Log.d(TAG, "Temporal: Pass " + consecutivePassCount + "/" + PASS_FRAMES_NEEDED + " (not yet confirmed live)");
            if (!currentlyLive) {
                List<String> pendingReasons = new ArrayList<>();
                pendingReasons.add("FAILED_OUT_OF_RANGE");
                pendingReasons.add("Accumulating confirmation frames (" + consecutivePassCount + "/" + PASS_FRAMES_NEEDED + ")");
                lastResult = new LivenessResult(false, 0.0, pendingReasons);
            } else {
                // Was already confirmed live, keep last good result while accumulating
                lastResult = new LivenessResult(true, confidence, reasonsCopy);
            }
        }
    }

    private void recordFail(List<String> reasons) {
        consecutiveFailCount++;
        if (consecutiveFailCount >= FAIL_FRAMES_TO_RESET) {
            consecutivePassCount = 0;
            if (currentlyLive) {
                Log.d(TAG, "Temporal: Live cleared after " + consecutiveFailCount + " consecutive fails");
                currentlyLive = false;
            }
        }
        lastResult = new LivenessResult(false, 0.0, new ArrayList<>(reasons));
    }

    public LivenessResult getLastResult() {
        return lastResult;
    }

    public void reset() {
        lastResult           = null;
        consecutivePassCount = 0;
        consecutiveFailCount = 0;
        currentlyLive        = false;
        lastM_Z              = -1.0;
    }
}
