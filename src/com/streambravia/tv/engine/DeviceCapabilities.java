package com.streambravia.tv.engine;

import android.content.Context;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import org.json.JSONObject;

/**
 * Dynamically detects hardware decoding and physical display capabilities
 * of the host TV or mobile device (4K, 8K, 1080p, 720p).
 * Unlocks 4K/8K on high-end hardware while keeping Fire TV Stick Lite 1080p-capped.
 */
public class DeviceCapabilities {

    private static final String TAG = "StreamBravia-Caps";
    private static DeviceCapabilities sInstance;

    private static final String PREFS_NAME = "StreamBraviaDeviceCaps";
    private static final String KEY_FINGERPRINT = "build_fingerprint";
    private static final String KEY_MAX_WIDTH = "max_width";
    private static final String KEY_MAX_HEIGHT = "max_height";
    private static final String KEY_SUPPORTS_4K = "supports_4k";
    private static final String KEY_SUPPORTS_8K = "supports_8k";
    private static final String KEY_MAX_LABEL = "max_label";

    private int mMaxDecodeWidth = 1920;
    private int mMaxDecodeHeight = 1080;
    private int mDisplayWidth = 1920;
    private int mDisplayHeight = 1080;
    private boolean mSupports4K = false;
    private boolean mSupports8K = false;
    private String mMaxResolutionLabel = "1080p";

    public static synchronized DeviceCapabilities getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new DeviceCapabilities(context.getApplicationContext());
        }
        return sInstance;
    }

    private DeviceCapabilities(Context context) {
        detectCapabilities(context);
    }

    private void detectCapabilities(Context context) {
        // 1. Instant Cache Check: If registered post-installation, load in 0.00ms from disk
        android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String savedFingerprint = prefs.getString(KEY_FINGERPRINT, "");
        if (Build.FINGERPRINT.equals(savedFingerprint) && prefs.contains(KEY_MAX_WIDTH)) {
            mMaxDecodeWidth = prefs.getInt(KEY_MAX_WIDTH, 1920);
            mMaxDecodeHeight = prefs.getInt(KEY_MAX_HEIGHT, 1080);
            mSupports4K = prefs.getBoolean(KEY_SUPPORTS_4K, false);
            mSupports8K = prefs.getBoolean(KEY_SUPPORTS_8K, false);
            mMaxResolutionLabel = prefs.getString(KEY_MAX_LABEL, "1080p");

            Log.i(TAG, "⚡ Loaded registered post-install capabilities: " + Build.MANUFACTURER + " " + Build.MODEL +
                    " -> Max: " + mMaxResolutionLabel + " (" + mMaxDecodeWidth + "x" + mMaxDecodeHeight +
                    "), 4K=" + mSupports4K + ", 8K=" + mSupports8K + " (0ms cache hit)");
            return;
        }

        // 2. First Run Post-Installation / System Update: One-time hardware probe
        try {
            int maxW = 1920;
            int maxH = 1080;

            // Inspect hardware video decoders (HEVC, AVC, VP9, AV1)
            MediaCodecList codecList = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            MediaCodecInfo[] codecInfos = codecList.getCodecInfos();

            for (MediaCodecInfo info : codecInfos) {
                if (info.isEncoder()) continue;
                for (String type : info.getSupportedTypes()) {
                    if (type.equalsIgnoreCase("video/hevc") ||
                        type.equalsIgnoreCase("video/avc") ||
                        type.equalsIgnoreCase("video/av01") ||
                        type.equalsIgnoreCase("video/x-vnd.on2.vp9")) {
                        try {
                            MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(type);
                            if (caps != null && caps.getVideoCapabilities() != null) {
                                int w = caps.getVideoCapabilities().getSupportedWidths().getUpper();
                                int h = caps.getVideoCapabilities().getSupportedHeights().getUpper();
                                if (w > maxW) maxW = w;
                                if (h > maxH) maxH = h;
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            }

            // Also inspect physical display modes separately
            int dispW = 1920;
            int dispH = 1080;
            try {
                WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
                if (wm != null) {
                    Display display = wm.getDefaultDisplay();
                    Display.Mode[] modes = display.getSupportedModes();
                    if (modes != null) {
                        for (Display.Mode m : modes) {
                            if (m.getPhysicalWidth() > dispW) dispW = m.getPhysicalWidth();
                            if (m.getPhysicalHeight() > dispH) dispH = m.getPhysicalHeight();
                        }
                    }
                }
            } catch (Throwable ignored) {}

            mDisplayWidth = dispW;
            mDisplayHeight = dispH;
            mMaxDecodeWidth = maxW;
            mMaxDecodeHeight = maxH;

            // Determine capability tier based strictly on actual hardware decoders
            if (maxW >= 7680 || maxH >= 4320) {
                mSupports8K = true;
                mSupports4K = true;
                mMaxResolutionLabel = "8K";
            } else if (maxW >= 3840 || maxH >= 2160) {
                mSupports4K = true;
                mSupports8K = false;
                mMaxResolutionLabel = "4K";
            } else {
                mSupports4K = false;
                mSupports8K = false;
                mMaxResolutionLabel = "1080p";
            }

            // 3. Register permanently in SharedPreferences so it NEVER has to be re-scanned!
            prefs.edit()
                    .putString(KEY_FINGERPRINT, Build.FINGERPRINT)
                    .putInt(KEY_MAX_WIDTH, mMaxDecodeWidth)
                    .putInt(KEY_MAX_HEIGHT, mMaxDecodeHeight)
                    .putBoolean(KEY_SUPPORTS_4K, mSupports4K)
                    .putBoolean(KEY_SUPPORTS_8K, mSupports8K)
                    .putString(KEY_MAX_LABEL, mMaxResolutionLabel)
                    .apply();

            Log.i(TAG, "⚡ Registered hardware video capabilities post-install: " + Build.MANUFACTURER + " " + Build.MODEL +
                    " -> Max: " + mMaxResolutionLabel + " (" + mMaxDecodeWidth + "x" + mMaxDecodeHeight +
                    "), 4K=" + mSupports4K + ", 8K=" + mSupports8K);
        } catch (Throwable t) {
            Log.w(TAG, "Error detecting device capabilities: " + t.getMessage());
            mMaxResolutionLabel = "1080p";
            mSupports4K = false;
            mSupports8K = false;
        }
    }

    public boolean supports4K() {
        return mSupports4K;
    }

    public boolean supports8K() {
        return mSupports8K;
    }

    public int getMaxDecodeWidth() {
        return mMaxDecodeWidth;
    }

    public int getMaxDecodeHeight() {
        return mMaxDecodeHeight;
    }

    public String getMaxResolutionLabel() {
        return mMaxResolutionLabel;
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("status", "ok");
            json.put("max_resolution", mMaxResolutionLabel);
            json.put("max_width", mMaxDecodeWidth);
            json.put("max_height", mMaxDecodeHeight);
            json.put("display_width", mDisplayWidth);
            json.put("display_height", mDisplayHeight);
            json.put("supports_4k", mSupports4K);
            json.put("supports_8k", mSupports8K);
            json.put("model", Build.MODEL);
            json.put("brand", Build.BRAND);
            json.put("manufacturer", Build.MANUFACTURER);
            json.put("android_version", Build.VERSION.RELEASE);
            json.put("sdk_int", Build.VERSION.SDK_INT);
        } catch (Exception ignored) {}
        return json;
    }
}
