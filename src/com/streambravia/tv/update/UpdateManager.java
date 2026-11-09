package com.streambravia.tv.update;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * StreamBravia OTA Update Manager.
 * Checks GitHub Releases API for new releases, parses changelog and APK asset,
 * downloads with real-time progress tracking, and invokes the Android Package Installer.
 */
public class UpdateManager {

    private static final String TAG = "StreamBravia-OTA";
    public static final String DEFAULT_GITHUB_REPO = "renopraveen/StreamBravia";
    private static final String PREFS_NAME = "StreamBraviaUpdatePrefs";
    private static final String KEY_REPO = "github_repo";

    public interface CheckCallback {
        void onSuccess(UpdateInfo info);
        void onNoUpdate(String currentVersion);
        void onError(String message);
    }

    public interface DownloadProgressListener {
        void onProgress(long bytesRead, long totalBytes, int percent);
        void onCompleted(File apkFile);
        void onError(String error);
    }

    public static class UpdateInfo {
        public String currentVersion;
        public String latestVersion;
        public String releaseTitle;
        public String releaseNotes;
        public String apkUrl;
        public String apkFileName;
        public long apkSize;
        public String publishedAt;
        public boolean hasUpdate;

        public JSONObject toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("has_update", hasUpdate);
                json.put("current_version", currentVersion);
                json.put("latest_version", latestVersion);
                json.put("release_title", releaseTitle);
                json.put("release_notes", releaseNotes);
                json.put("apk_url", apkUrl);
                json.put("apk_file_name", apkFileName);
                json.put("apk_size", apkSize);
                json.put("apk_size_mb", String.format("%.2f MB", (apkSize / (1024.0 * 1024.0))));
                json.put("published_at", publishedAt);
            } catch (Exception ignored) {}
            return json;
        }
    }

    private static UpdateManager sInstance;
    private final Context mContext;
    private final Handler mMainHandler;
    private final ExecutorService mExecutor;
    private final AtomicBoolean mIsDownloading = new AtomicBoolean(false);

    public static synchronized UpdateManager getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new UpdateManager(context.getApplicationContext());
        }
        return sInstance;
    }

    private UpdateManager(Context context) {
        mContext = context;
        mMainHandler = new Handler(Looper.getMainLooper());
        mExecutor = Executors.newSingleThreadExecutor();
    }

    public String getCurrentVersion() {
        try {
            PackageInfo pInfo = mContext.getPackageManager().getPackageInfo(mContext.getPackageName(), 0);
            return pInfo.versionName != null ? pInfo.versionName : "1.0.0";
        } catch (Exception e) {
            return "1.0.0";
        }
    }

    public int getCurrentVersionCode() {
        try {
            PackageInfo pInfo = mContext.getPackageManager().getPackageInfo(mContext.getPackageName(), 0);
            return pInfo.versionCode;
        } catch (Exception e) {
            return 1;
        }
    }

    public String getTargetRepo() {
        SharedPreferences prefs = mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_REPO, DEFAULT_GITHUB_REPO);
    }

    public void setTargetRepo(String repo) {
        if (repo != null && !repo.trim().isEmpty()) {
            mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_REPO, repo.trim())
                    .apply();
        }
    }

    /**
     * Asynchronously query GitHub Releases API for the latest release.
     */
    public void checkForUpdates(final CheckCallback callback) {
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final UpdateInfo info = checkLatestReleaseSync();
                    mMainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (info != null) {
                                if (info.hasUpdate) {
                                    callback.onSuccess(info);
                                } else {
                                    callback.onNoUpdate(info.currentVersion);
                                }
                            } else {
                                callback.onError("Failed to check for updates");
                            }
                        }
                    });
                } catch (final Exception e) {
                    Log.w(TAG, "Update check failed: " + e.getMessage());
                    mMainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onError(e.getMessage() != null ? e.getMessage() : "Unknown update check error");
                        }
                    });
                }
            }
        });
    }

    /**
     * Synchronous check against GitHub Releases API.
     */
    public UpdateInfo checkLatestReleaseSync() throws Exception {
        String repo = getTargetRepo();
        String currentVersion = getCurrentVersion();
        String urlStr = "https://api.github.com/repos/" + repo + "/releases/latest";
        Log.i(TAG, "Checking for updates at: " + urlStr + " (Current version: v" + currentVersion + ")");

        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "StreamBraviaTV/" + currentVersion);
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json");

            int responseCode = conn.getResponseCode();
            if (responseCode == 404) {
                Log.i(TAG, "No releases found on GitHub repository: " + repo);
                UpdateInfo info = new UpdateInfo();
                info.currentVersion = currentVersion;
                info.latestVersion = currentVersion;
                info.hasUpdate = false;
                info.releaseTitle = "No Releases Found";
                info.releaseNotes = "No published releases found on " + repo + " yet.";
                return info;
            }

            if (responseCode != 200) {
                throw new Exception("GitHub API HTTP " + responseCode + ": " + conn.getResponseMessage());
            }

            InputStream is = conn.getInputStream();
            InputStreamReader isr = new InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8);
            char[] buffer = new char[8192];
            StringBuilder sb = new StringBuilder();
            int n;
            while ((n = isr.read(buffer, 0, buffer.length)) != -1) {
                sb.append(buffer, 0, n);
            }
            isr.close();

            JSONObject releaseObj = new JSONObject(sb.toString());
            String tagName = releaseObj.optString("tag_name", "");
            String releaseName = releaseObj.optString("name", tagName);
            String body = releaseObj.optString("body", "No changelog provided.");
            String publishedAt = releaseObj.optString("published_at", "");

            // Locate the .apk asset in release assets
            String apkDownloadUrl = "";
            String apkName = "StreamBravia-TV.apk";
            long apkSize = 0;

            JSONArray assets = releaseObj.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.getJSONObject(i);
                    String name = asset.optString("name", "");
                    if (name.toLowerCase().endsWith(".apk")) {
                        apkDownloadUrl = asset.optString("browser_download_url", "");
                        apkName = name;
                        apkSize = asset.optLong("size", 0);
                        break;
                    }
                }
            }

            UpdateInfo info = new UpdateInfo();
            info.currentVersion = currentVersion;
            info.latestVersion = cleanVersion(tagName);
            info.releaseTitle = releaseName;
            info.releaseNotes = body;
            info.apkUrl = apkDownloadUrl;
            info.apkFileName = apkName;
            info.apkSize = apkSize;
            info.publishedAt = publishedAt;
            info.hasUpdate = isNewerVersion(currentVersion, info.latestVersion) && !apkDownloadUrl.isEmpty();

            Log.i(TAG, "Checked release: " + info.latestVersion + ", hasUpdate=" + info.hasUpdate + ", apk=" + apkDownloadUrl);
            return info;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Download the APK from GitHub and immediately trigger the Android installer.
     */
    public void startDownloadAndInstall(final String apkUrl, final String fileName, final DownloadProgressListener listener) {
        if (mIsDownloading.get()) {
            if (listener != null) listener.onError("A download is already in progress.");
            return;
        }

        mIsDownloading.set(true);
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                File updateDir = new File(mContext.getExternalCacheDir() != null ? mContext.getExternalCacheDir() : mContext.getCacheDir(), "updates");
                if (!updateDir.exists()) updateDir.mkdirs();

                // Clear previous downloaded APKs
                File[] oldFiles = updateDir.listFiles();
                if (oldFiles != null) {
                    for (File f : oldFiles) f.delete();
                }

                String safeName = (fileName != null && fileName.endsWith(".apk")) ? fileName : "StreamBravia-update.apk";
                final File targetFile = new File(updateDir, safeName);

                try {
                    downloadFileWithRedirects(apkUrl, targetFile, listener);
                    mIsDownloading.set(false);
                    mMainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (listener != null) listener.onCompleted(targetFile);
                            installApk(mContext, targetFile);
                        }
                    });
                } catch (final Exception e) {
                    mIsDownloading.set(false);
                    Log.e(TAG, "Download error: " + e.getMessage(), e);
                    mMainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (listener != null) listener.onError("Download failed: " + e.getMessage());
                        }
                    });
                }
            }
        });
    }

    private void downloadFileWithRedirects(String urlStr, File destFile, final DownloadProgressListener listener) throws Exception {
        String currentUrl = urlStr;
        HttpURLConnection conn = null;
        int redirects = 0;

        while (redirects < 5) {
            URL url = new URL(currentUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", "StreamBraviaTV/" + getCurrentVersion());
            conn.setInstanceFollowRedirects(true);

            int status = conn.getResponseCode();
            if (status == HttpURLConnection.HTTP_MOVED_TEMP
                    || status == HttpURLConnection.HTTP_MOVED_PERM
                    || status == HttpURLConnection.HTTP_SEE_OTHER
                    || status == 307
                    || status == 308) {
                String newUrl = conn.getHeaderField("Location");
                conn.disconnect();
                if (newUrl == null) throw new Exception("HTTP redirect without Location header");
                currentUrl = newUrl;
                redirects++;
                continue;
            }

            if (status != HttpURLConnection.HTTP_OK) {
                throw new Exception("HTTP " + status + ": " + conn.getResponseMessage());
            }

            break;
        }

        final long totalBytes = conn.getContentLengthLong();
        InputStream is = conn.getInputStream();
        FileOutputStream fos = new FileOutputStream(destFile);
        byte[] buffer = new byte[16384];
        long bytesReadTotal = 0;
        int read;
        long lastProgressReport = 0;

        while ((read = is.read(buffer)) != -1) {
            fos.write(buffer, 0, read);
            bytesReadTotal += read;

            long now = System.currentTimeMillis();
            if (now - lastProgressReport > 200 || bytesReadTotal == totalBytes) {
                lastProgressReport = now;
                final long bRead = bytesReadTotal;
                final int percent = totalBytes > 0 ? (int) ((bytesReadTotal * 100) / totalBytes) : -1;
                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (listener != null) listener.onProgress(bRead, totalBytes, percent);
                    }
                });
            }
        }

        fos.flush();
        fos.close();
        is.close();
        conn.disconnect();
    }

    /**
     * Launch the Android Package Installer to install the downloaded APK.
     */
    public static void installApk(Context context, File apkFile) {
        if (apkFile == null || !apkFile.exists()) {
            Toast.makeText(context, "Error: Downloaded APK file not found", Toast.LENGTH_LONG).show();
            return;
        }

        try {
            Log.i(TAG, "Launching Android Package Installer for: " + apkFile.getAbsolutePath());

            // Android 8.0+ Check for unknown sources installation permission
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.getPackageManager().canRequestPackageInstalls()) {
                    Toast.makeText(context, "Please allow StreamBravia to install updates", Toast.LENGTH_LONG).show();
                    Intent settingsIntent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                    settingsIntent.setData(Uri.parse("package:" + context.getPackageName()));
                    settingsIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(settingsIntent);
                    // Still attempt to launch package installer below, as user might toggle back
                }
            }

            Uri apkUri = FileProvider.getUriForFile(
                    context,
                    context.getPackageName() + ".fileprovider",
                    apkFile
            );

            Intent installIntent = new Intent(Intent.ACTION_VIEW);
            installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            installIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(installIntent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch package installer: " + e.getMessage(), e);
            Toast.makeText(context, "Failed to launch installer: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    public static String cleanVersion(String v) {
        if (v == null) return "0.0.0";
        String clean = v.trim();
        if (clean.startsWith("v") || clean.startsWith("V")) {
            clean = clean.substring(1);
        }
        return clean;
    }

    /**
     * Returns true if remoteVersion > currentVersion.
     * Compares semantic numbers e.g. "1.0.1" vs "1.0.0".
     */
    public static boolean isNewerVersion(String currentVersion, String remoteVersion) {
        try {
            String[] currParts = cleanVersion(currentVersion).split("\\.");
            String[] remoteParts = cleanVersion(remoteVersion).split("\\.");

            int len = Math.max(currParts.length, remoteParts.length);
            for (int i = 0; i < len; i++) {
                int curr = i < currParts.length ? parsePart(currParts[i]) : 0;
                int remote = i < remoteParts.length ? parsePart(remoteParts[i]) : 0;
                if (remote > curr) return true;
                if (remote < curr) return false;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static int parsePart(String s) {
        try {
            String numOnly = s.replaceAll("[^0-9]", "");
            return numOnly.isEmpty() ? 0 : Integer.parseInt(numOnly);
        } catch (Exception e) {
            return 0;
        }
    }
}
