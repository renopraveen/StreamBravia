package com.streambravia.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.SearchManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.streambravia.tv.engine.LocalTorrentServer;
import com.streambravia.tv.engine.TorrentEngine;
import com.streambravia.tv.update.UpdateManager;

public class MainActivity extends Activity {

    private static final String PREFS_NAME = "StreamBraviaPrefs";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String DEFAULT_SERVER_URL = "http://127.0.0.1:7878";

    private LocalTorrentServer mLocalServer;
    private WebView mWebView;
    private FrameLayout mContainer;
    private View mCustomView;
    private WebChromeClient.CustomViewCallback mCustomViewCallback;
    private long mLastBackPress = 0;
    private SharedPreferences mPrefs;
    private boolean mIsPageLoaded = false;
    private String mPendingSearchQuery = null;
    private String mPendingItemId = null;
    private com.streambravia.tv.voice.VoiceSearchManager mVoiceSearchManager;
    private boolean mIsVoiceButtonHeld = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Keep screen on during TV playback
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);

        mPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // Initialize Intelligent Voice Search Manager
        mVoiceSearchManager = new com.streambravia.tv.voice.VoiceSearchManager(this);
        mVoiceSearchManager.setCallback(new com.streambravia.tv.voice.VoiceSearchManager.VoiceSearchCallback() {
            @Override
            public void onListeningStarted() {
                runOnUiThread(() -> {
                    if (mWebView != null) {
                        mWebView.evaluateJavascript("if (typeof window.onVoiceSearchStarted === 'function') { window.onVoiceSearchStarted(); }", null);
                    }
                });
            }

            @Override
            public void onAudioLevel(float level) {
                runOnUiThread(() -> {
                    if (mWebView != null) {
                        mWebView.evaluateJavascript("if (typeof window.onVoiceAudioLevel === 'function') { window.onVoiceAudioLevel(" + level + "); }", null);
                    }
                });
            }

            @Override
            public void onRecognizing() {
                runOnUiThread(() -> {
                    if (mWebView != null) {
                        mWebView.evaluateJavascript("if (typeof window.onVoiceSearchRecognizing === 'function') { window.onVoiceSearchRecognizing(); }", null);
                    }
                });
            }

            @Override
            public void onResult(String text) {
                runOnUiThread(() -> {
                    if (mWebView != null) {
                        String escaped = org.json.JSONObject.quote(text);
                        mWebView.evaluateJavascript("if (typeof window.onVoiceSearchResult === 'function') { window.onVoiceSearchResult(" + escaped + "); }", null);
                    }
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    if (mWebView != null) {
                        String escaped = org.json.JSONObject.quote(message);
                        mWebView.evaluateJavascript("if (typeof window.onVoiceSearchError === 'function') { window.onVoiceSearchError(" + escaped + "); }", null);
                    }
                });
            }
        });

        // Pre-register hardware decoder capabilities post-install in background (0ms latency during playback)
        new Thread(() -> com.streambravia.tv.engine.DeviceCapabilities.getInstance(this)).start();

        // Start embedded Standalone Torrent Server on TV localhost (127.0.0.1:7878)
        try {
            mLocalServer = LocalTorrentServer.getInstance(this);
        } catch (Throwable t) {
            android.util.Log.e("StreamBraviaTV", "Failed to start local standalone server: " + t.getMessage(), t);
        }

        // Root container for fullscreen custom views and webview
        mContainer = new FrameLayout(this);
        mContainer.setBackgroundColor(Color.BLACK);
        setContentView(mContainer);

        setupWebView();
        hideSystemUI();

        // Always prioritize TV localhost (127.0.0.1:7878) for 100% standalone playback
        String url = DEFAULT_SERVER_URL;
        mWebView.loadUrl(url);

        // Silent OTA Update Check 4 seconds after app launch
        mWebView.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    UpdateManager.getInstance(MainActivity.this).checkForUpdates(new UpdateManager.CheckCallback() {
                        @Override
                        public void onSuccess(final UpdateManager.UpdateInfo info) {
                            if (info != null && info.hasUpdate && mWebView != null) {
                                mWebView.evaluateJavascript(
                                        "if (typeof showUpdateModal === 'function') { showUpdateModal(" + info.toJson().toString() + "); }",
                                        null
                                );
                            }
                        }

                        @Override
                        public void onNoUpdate(String currentVersion) {}

                        @Override
                        public void onError(String message) {}
                    });
                } catch (Exception ignored) {}
            }
        }, 4000);

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private String getServerUrl() {
        return mPrefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL);
    }

    private void saveServerUrl(String url) {
        mPrefs.edit().putString(KEY_SERVER_URL, url).apply();
    }

    private void setupWebView() {
        mWebView = new WebView(this);
        mWebView.setBackgroundColor(Color.BLACK);
        mWebView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        WebSettings ws = mWebView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setUserAgentString(ws.getUserAgentString() + " StreamBraviaTV/1.0");

        mWebView.setFocusable(true);
        mWebView.setFocusableInTouchMode(false); // Disable virtual touch/mouse on Android TV
        mWebView.requestFocus();

        mWebView.addJavascriptInterface(new BraviaNativeInterface(), "BraviaNative");

        mWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (mCustomView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                mCustomView = view;
                mCustomViewCallback = callback;
                mWebView.setVisibility(View.GONE);
                mContainer.addView(mCustomView, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                ));
                hideSystemUI();
            }

            @Override
            public void onHideCustomView() {
                if (mCustomView == null) return;
                mCustomView.setVisibility(View.GONE);
                mContainer.removeView(mCustomView);
                mCustomView = null;
                if (mCustomViewCallback != null) {
                    mCustomViewCallback.onCustomViewHidden();
                    mCustomViewCallback = null;
                }
                mWebView.setVisibility(View.VISIBLE);
                mWebView.requestFocus();
                hideSystemUI();
            }
        });

        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    showConnectionErrorDialog();
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                hideSystemUI();
                mWebView.requestFocus();
                mIsPageLoaded = true;
                if (mPendingSearchQuery != null) {
                    executeSearchInWeb(mPendingSearchQuery);
                    mPendingSearchQuery = null;
                } else if (mPendingItemId != null) {
                    openItemInWeb(mPendingItemId);
                    mPendingItemId = null;
                }
            }
        });

        mContainer.addView(mWebView);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_SEARCH.equals(action)) {
            String query = intent.getStringExtra(SearchManager.QUERY);
            if (query != null && !query.trim().isEmpty()) {
                if (mWebView != null && mIsPageLoaded) {
                    executeSearchInWeb(query.trim());
                } else {
                    mPendingSearchQuery = query.trim();
                }
            }
        } else if (Intent.ACTION_VIEW.equals(action)) {
            Uri data = intent.getData();
            if (data != null) {
                String id = data.getLastPathSegment();
                if (id != null && !id.isEmpty()) {
                    if (mWebView != null && mIsPageLoaded) {
                        openItemInWeb(id);
                    } else {
                        mPendingItemId = id;
                    }
                }
            }
        }
    }

    private void executeSearchInWeb(String query) {
        if (mWebView == null) return;
        String escaped = org.json.JSONObject.quote(query);
        String js = "if (typeof window.triggerSearchQuery === 'function') { window.triggerSearchQuery(" + escaped + "); }";
        mWebView.post(() -> mWebView.evaluateJavascript(js, null));
    }

    private void openItemInWeb(String id) {
        if (mWebView == null) return;
        String escaped = org.json.JSONObject.quote(id);
        String js = "if (typeof window.triggerOpenItemById === 'function') { window.triggerOpenItemById(" + escaped + "); }";
        mWebView.post(() -> mWebView.evaluateJavascript(js, null));
    }

    private void hideSystemUI() {
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUI();
        }
    }

    private void showConnectionErrorDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        builder.setTitle("StreamBravia - Connection Failed");
        builder.setMessage("Could not connect to StreamBravia Core at:\n" + getServerUrl() + "\n\nMake sure server.py is running on your PC.");

        final EditText input = new EditText(this);
        input.setText(getServerUrl());
        input.setTextColor(Color.WHITE);
        input.setPadding(30, 20, 30, 20);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 20, 40, 10);
        TextView prompt = new TextView(this);
        prompt.setText("Server URL:");
        prompt.setTextColor(Color.LTGRAY);
        layout.addView(prompt);
        layout.addView(input);
        builder.setView(layout);

        builder.setPositiveButton("Retry / Connect", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String newUrl = input.getText().toString().trim();
                if (!newUrl.isEmpty()) {
                    if (!newUrl.startsWith("http://") && !newUrl.startsWith("https://")) {
                        newUrl = "http://" + newUrl;
                    }
                    saveServerUrl(newUrl);
                    mWebView.loadUrl(newUrl);
                }
            }
        });

        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void showServerSettingsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        builder.setTitle("StreamBravia Settings");
        builder.setMessage("Change StreamBravia PC Server URL:");

        final EditText input = new EditText(this);
        input.setText(getServerUrl());
        input.setTextColor(Color.WHITE);
        input.setPadding(30, 20, 30, 20);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 20, 40, 10);
        TextView prompt = new TextView(this);
        prompt.setText("Server Address:");
        prompt.setTextColor(Color.LTGRAY);
        layout.addView(prompt);
        layout.addView(input);
        builder.setView(layout);

        builder.setPositiveButton("Save & Reload", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String newUrl = input.getText().toString().trim();
                if (!newUrl.isEmpty()) {
                    if (!newUrl.startsWith("http://") && !newUrl.startsWith("https://")) {
                        newUrl = "http://" + newUrl;
                    }
                    saveServerUrl(newUrl);
                    mWebView.loadUrl(newUrl);
                }
            }
        });

        builder.setNeutralButton("Reload Current", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                mWebView.reload();
            }
        });

        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Fire TV & Android TV Voice Search (Mic button press & hold detection)
        if (keyCode == KeyEvent.KEYCODE_SEARCH || 
            keyCode == KeyEvent.KEYCODE_VOICE_ASSIST || 
            keyCode == KeyEvent.KEYCODE_ASSIST || 
            keyCode == 130 /* KEYCODE_MEDIA_RECORD */) {
            if (!mIsVoiceButtonHeld) {
                mIsVoiceButtonHeld = true;
                if (mVoiceSearchManager != null) {
                    mVoiceSearchManager.startListening();
                }
            }
            return true;
        }

        // Remote Menu button triggers context action sheet on focused card
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            mWebView.evaluateJavascript("if (typeof window.triggerContextMenuOnFocused === 'function') { window.triggerContextMenuOnFocused(); }", null);
            return true;
        }

        // Settings key opens server configuration
        if (keyCode == KeyEvent.KEYCODE_SETTINGS) {
            showServerSettingsDialog();
            return true;
        }

        // Media keys forwarding
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) {
            mWebView.evaluateJavascript("if (window.togglePlayPause) window.togglePlayPause();", null);
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
            mWebView.evaluateJavascript("if (window.handleDpadSeek) window.handleDpadSeek(1);", null);
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_MEDIA_REWIND) {
            mWebView.evaluateJavascript("if (window.handleDpadSeek) window.handleDpadSeek(-1);", null);
            return true;
        }

        // Back button navigation
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (mCustomView != null) {
                if (mCustomViewCallback != null) mCustomViewCallback.onCustomViewHidden();
                return true;
            }

            // Check if any modal or drawer is open in WebView
            String jsCheck =
                    "(function() { " +
                    "  var subModal = document.getElementById('subtitle-modal'); " +
                    "  if (subModal && !subModal.classList.contains('hidden')) { " +
                    "    var btn = document.getElementById('sub-close-btn'); " +
                    "    if (btn) btn.click(); else subModal.classList.add('hidden'); " +
                    "    return 'handled'; " +
                    "  } " +
                    "  var audioModal = document.getElementById('audio-modal'); " +
                    "  if (audioModal && !audioModal.classList.contains('hidden')) { " +
                    "    var btn = document.getElementById('audio-close-btn'); " +
                    "    if (btn) btn.click(); else audioModal.classList.add('hidden'); " +
                    "    return 'handled'; " +
                    "  } " +
                    "  var playerModal = document.getElementById('player-modal'); " +
                    "  if (playerModal && !playerModal.classList.contains('hidden')) { " +
                    "    if (window.closePlayer) window.closePlayer(); " +
                    "    else { var btn = document.getElementById('player-close-btn'); if (btn) btn.click(); } " +
                    "    return 'handled'; " +
                    "  } " +
                    "  var detailsModal = document.getElementById('details-modal'); " +
                    "  if (detailsModal && !detailsModal.classList.contains('hidden')) { " +
                    "    var btn = document.getElementById('modal-close-btn'); " +
                    "    if (btn) btn.click(); else detailsModal.classList.add('hidden'); " +
                    "    return 'handled'; " +
                    "  } " +
                    "  var settingsModal = document.getElementById('settings-modal'); " +
                    "  if (settingsModal && !settingsModal.classList.contains('hidden')) { " +
                    "    settingsModal.classList.add('hidden'); " +
                    "    return 'handled'; " +
                    "  } " +
                    "  var logsModal = document.getElementById('logs-modal'); " +
                    "  if (logsModal && !logsModal.classList.contains('hidden')) { " +
                    "    logsModal.classList.add('hidden'); " +
                    "    return 'handled'; " +
                    "  } " +
                    "  return 'none'; " +
                    "})()";

            mWebView.evaluateJavascript(jsCheck, new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    if (value == null || !value.contains("handled")) {
                        if (System.currentTimeMillis() - mLastBackPress < 2500) {
                            finish();
                        } else {
                            mLastBackPress = System.currentTimeMillis();
                            Toast.makeText(MainActivity.this, "Press BACK again to exit StreamBravia", Toast.LENGTH_SHORT).show();
                        }
                    }
                }
            });
            return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_SEARCH || 
            keyCode == KeyEvent.KEYCODE_VOICE_ASSIST || 
            keyCode == KeyEvent.KEYCODE_ASSIST || 
            keyCode == 130 /* KEYCODE_MEDIA_RECORD */) {
            if (mIsVoiceButtonHeld) {
                mIsVoiceButtonHeld = false;
                if (mVoiceSearchManager != null) {
                    mVoiceSearchManager.stopListening();
                }
            }
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    public class BraviaNativeInterface {
        @JavascriptInterface
        public void startVoiceSearch() {
            runOnUiThread(() -> {
                if (mVoiceSearchManager != null) {
                    mVoiceSearchManager.startListening();
                }
            });
        }

        @JavascriptInterface
        public void stopVoiceSearch() {
            runOnUiThread(() -> {
                if (mVoiceSearchManager != null) {
                    mVoiceSearchManager.stopListening();
                }
            });
        }

        @JavascriptInterface
        public void cancelVoiceSearch() {
            runOnUiThread(() -> {
                if (mVoiceSearchManager != null) {
                    mVoiceSearchManager.cancel();
                }
            });
        }

        @JavascriptInterface
        public void setVoiceSearchLanguage(String lang) {
            if (mVoiceSearchManager != null) {
                mVoiceSearchManager.setLanguage(lang);
            }
        }

        @JavascriptInterface
        public void launchNativePlayer(final String streamUrl, final String title, final String subtitlesJson, final long startPosMs) {
            launchNativePlayerEx(streamUrl, title, subtitlesJson, startPosMs, "", "", "movie", 0, 0);
        }

        @JavascriptInterface
        public void launchNativePlayerEx(
                final String streamUrl,
                final String title,
                final String subtitlesJson,
                final long startPosMs,
                final String infoHash,
                final String imdbId,
                final String mediaType,
                final int season,
                final int episode
        ) {
            launchNativePlayerEx(streamUrl, title, subtitlesJson, startPosMs, infoHash, imdbId, mediaType, season, episode, "");
        }

        @JavascriptInterface
        public void launchNativePlayerEx(
                final String streamUrl,
                final String title,
                final String subtitlesJson,
                final long startPosMs,
                final String infoHash,
                final String imdbId,
                final String mediaType,
                final int season,
                final int episode,
                final String originalLanguage
        ) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (mWebView != null) {
                        mWebView.onPause();
                    }
                    Intent intent = new Intent(MainActivity.this, com.streambravia.tv.player.NativePlayerActivity.class);
                    intent.putExtra(com.streambravia.tv.player.NativePlayerActivity.EXTRA_STREAM_URL, streamUrl);
                    intent.putExtra(com.streambravia.tv.player.NativePlayerActivity.EXTRA_TITLE, title);
                    intent.putExtra(com.streambravia.tv.player.NativePlayerActivity.EXTRA_SUBTITLES_JSON, subtitlesJson);
                    intent.putExtra(com.streambravia.tv.player.NativePlayerActivity.EXTRA_START_POS_MS, startPosMs);
                    intent.putExtra(com.streambravia.tv.player.NativePlayerActivity.EXTRA_SERVER_URL, getServerUrl());
                    intent.putExtra(com.streambravia.tv.player.NativePlayerActivity.EXTRA_INFO_HASH, infoHash != null ? infoHash : "");
                    intent.putExtra(com.streambravia.tv.player.NativePlayerActivity.EXTRA_IMDB_ID, imdbId != null ? imdbId : "");
                    intent.putExtra(com.streambravia.tv.player.NativePlayerActivity.EXTRA_MEDIA_TYPE, mediaType != null ? mediaType : "movie");
                    intent.putExtra(com.streambravia.tv.player.NativePlayerActivity.EXTRA_SEASON, season);
                    intent.putExtra(com.streambravia.tv.player.NativePlayerActivity.EXTRA_EPISODE, episode);
                    intent.putExtra(com.streambravia.tv.player.NativePlayerActivity.EXTRA_ORIGINAL_LANGUAGE, originalLanguage != null ? originalLanguage : "");
                    startActivityForResult(intent, 1001);
                }
            });
        }

        @JavascriptInterface
        public String getAppVersion() {
            return UpdateManager.getInstance(MainActivity.this).getCurrentVersion();
        }

        @JavascriptInterface
        public String getGitHubRepo() {
            return UpdateManager.getInstance(MainActivity.this).getTargetRepo();
        }

        @JavascriptInterface
        public void setGitHubRepo(String repo) {
            UpdateManager.getInstance(MainActivity.this).setTargetRepo(repo);
        }

        @JavascriptInterface
        public void checkForUpdates(final boolean userInitiated) {
            UpdateManager.getInstance(MainActivity.this).checkForUpdates(new UpdateManager.CheckCallback() {
                @Override
                public void onSuccess(final UpdateManager.UpdateInfo info) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (mWebView != null && info != null) {
                                mWebView.evaluateJavascript(
                                        "if (typeof onUpdateCheckResult === 'function') { onUpdateCheckResult(" + info.toJson().toString() + ", " + userInitiated + "); }",
                                        null
                                );
                            }
                        }
                    });
                }

                @Override
                public void onNoUpdate(final String currentVersion) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (mWebView != null) {
                                org.json.JSONObject obj = new org.json.JSONObject();
                                try {
                                    obj.put("has_update", false);
                                    obj.put("current_version", currentVersion);
                                    obj.put("message", "StreamBravia v" + currentVersion + " is up to date.");
                                } catch (Exception ignored) {}
                                mWebView.evaluateJavascript(
                                        "if (typeof onUpdateCheckResult === 'function') { onUpdateCheckResult(" + obj.toString() + ", " + userInitiated + "); }",
                                        null
                                );
                            }
                        }
                    });
                }

                @Override
                public void onError(final String message) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (mWebView != null) {
                                org.json.JSONObject obj = new org.json.JSONObject();
                                try {
                                    obj.put("has_update", false);
                                    obj.put("error", message);
                                } catch (Exception ignored) {}
                                mWebView.evaluateJavascript(
                                        "if (typeof onUpdateCheckResult === 'function') { onUpdateCheckResult(" + obj.toString() + ", " + userInitiated + "); }",
                                        null
                                );
                            }
                        }
                    });
                }
            });
        }

        @JavascriptInterface
        public void downloadAndInstallUpdate(final String apkUrl, final String fileName) {
            UpdateManager.getInstance(MainActivity.this).startDownloadAndInstall(apkUrl, fileName, new UpdateManager.DownloadProgressListener() {
                @Override
                public void onProgress(final long bytesRead, final long totalBytes, final int percent) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (mWebView != null) {
                                mWebView.evaluateJavascript(
                                        "if (typeof onUpdateDownloadProgress === 'function') { onUpdateDownloadProgress(" + percent + ", " + bytesRead + ", " + totalBytes + "); }",
                                        null
                                );
                            }
                        }
                    });
                }

                @Override
                public void onCompleted(java.io.File apkFile) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (mWebView != null) {
                                mWebView.evaluateJavascript(
                                        "if (typeof onUpdateDownloadCompleted === 'function') { onUpdateDownloadCompleted(); }",
                                        null
                                );
                            }
                        }
                    });
                }

                @Override
                public void onError(final String error) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (mWebView != null) {
                                mWebView.evaluateJavascript(
                                        "if (typeof onUpdateDownloadError === 'function') { onUpdateDownloadError('" + error.replace("'", "\\'") + "'); }",
                                        null
                                );
                            }
                        }
                    });
                }
            });
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1001 && data != null) {
            long posMs = data.getLongExtra(com.streambravia.tv.player.NativePlayerActivity.RESULT_POSITION_MS, 0);
            long durMs = data.getLongExtra(com.streambravia.tv.player.NativePlayerActivity.RESULT_DURATION_MS, 0);
            final long posSec = posMs / 1000;
            final long durSec = durMs / 1000;
            if (mWebView != null && durSec > 0) {
                mWebView.post(new Runnable() {
                    @Override
                    public void run() {
                        mWebView.evaluateJavascript(
                            "if (typeof onNativePlaybackStopped === 'function') { onNativePlaybackStopped(" + posSec + ", " + durSec + "); }",
                            null
                        );
                    }
                });
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mWebView != null) {
            mWebView.onResume();
        }
        hideSystemUI();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mWebView != null) {
            mWebView.onPause();
        }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (mWebView != null) {
            try {
                mWebView.freeMemory();
            } catch (Throwable ignored) {}
        }
        if (level >= TRIM_MEMORY_MODERATE) {
            TorrentEngine eng = TorrentEngine.getInstance(this);
            if (eng != null && eng.getActiveHash() == null) {
                eng.purgeCache();
            }
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        if (mWebView != null) {
            try {
                mWebView.freeMemory();
            } catch (Throwable ignored) {}
        }
        TorrentEngine eng = TorrentEngine.getInstance(this);
        if (eng != null && eng.getActiveHash() == null) {
            eng.purgeCache();
        }
    }

    @Override
    protected void onDestroy() {
        if (mVoiceSearchManager != null) {
            mVoiceSearchManager.cancel();
            mVoiceSearchManager = null;
        }
        // Retain process-level LocalTorrentServer and TorrentEngine for background playback / NativePlayerActivity
        mLocalServer = null;

        if (mWebView != null) {
            mWebView.destroy();
        }
        super.onDestroy();
    }
}
