package com.streambravia.tv.player;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.Cue;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.SubtitleView;

import com.streambravia.tv.engine.DeviceCapabilities;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Pure GPU Zero-Copy Hardware-Accelerated Native Media3 Video Player.
 * Complete with interactive Netflix-style player controls, focusable scrubber seekbar,
 * and professional OpenSubtitles / VLSub subtitle engine with hash and title search.
 */
public class NativePlayerActivity extends Activity implements SurfaceHolder.Callback {

    private static final String TAG = "NativePlayerActivity";

    public static final String EXTRA_STREAM_URL = "extra_stream_url";
    public static final String EXTRA_TITLE = "extra_title";
    public static final String EXTRA_SUBTITLES_JSON = "extra_subtitles_json";
    public static final String EXTRA_START_POS_MS = "extra_start_pos_ms";
    public static final String EXTRA_SERVER_URL = "extra_server_url";
    public static final String EXTRA_INFO_HASH = "extra_info_hash";
    public static final String EXTRA_IMDB_ID = "extra_imdb_id";
    public static final String EXTRA_MEDIA_TYPE = "extra_media_type";
    public static final String EXTRA_SEASON = "extra_season";
    public static final String EXTRA_EPISODE = "extra_episode";
    public static final String EXTRA_ORIGINAL_LANGUAGE = "extra_original_language";

    public static final String RESULT_POSITION_MS = "result_position_ms";
    public static final String RESULT_DURATION_MS = "result_duration_ms";

    private AspectRatioFrameLayout mAspectRatioLayout;
    private SurfaceView mSurfaceView;
    private SubtitleView mSubtitleView;
    private ExoPlayer mExoPlayer;
    private DefaultTrackSelector mTrackSelector;

    // HUD / OSD
    private FrameLayout mRootLayout;
    private FrameLayout mOsdLayout;
    private TextView mTitleText;
    private TextView mTimePosText;
    private TextView mTimeDurText;
    private TextView mClockText;
    private SeekBar mSeekBar;
    private Button mBtnPlayPause;
    private Button mBtnRewind;
    private Button mBtnForward;
    private Button mBtnSubtitles;
    private Button mBtnAudio;
    private Button mBtnAspect;
    private Button mBtnBack;
    private ProgressBar mLoadingSpinner;
    private TextView mBufferingStatusText;

    // Subtitle Drawer
    private FrameLayout mSubtitleDrawerLayout;
    private LinearLayout mSubActiveContainer;
    private LinearLayout mSubResultsContainer;
    private EditText mSubSearchInput;
    private TextView mSubSyncDisplay;
    private Button mBtnCloseSubDrawer;
    private String mSelectedSubLang = "all";

    // Intent parameters
    private String mStreamUrl;
    private String mTitle;
    private String mServerUrl = "http://192.168.1.3:7878";
    private String mInfoHash = "";
    private String mImdbId = "";
    private String mMediaType = "movie";
    private int mSeason = 0;
    private int mEpisode = 0;
    private String mOriginalLanguage = "";
    private long mStartPosMs = 0;
    private long mLastKnownPositionMs = 0;

    // Subtitle state
    private static class SubCue {
        long startMs;
        long endMs;
        String text;
    }
    private final List<SubCue> mLoadedCues = new ArrayList<>();
    private long mSubtitleSyncOffsetMs = 0;
    private boolean mSubtitlesEnabled = true;
    private float mSubtitleFontSizeSp = 24.0f;
    private String mActiveSubLabel = "Off";

    // Aspect Ratio toggle
    private int mAspectModeIndex = 0; // 0: FIT, 1: ZOOM, 2: FILL

    // Seek generation tracking
    private volatile boolean mIsSeeking = false;
    private volatile long mSeekGeneration = 0;

    private static final java.util.concurrent.ExecutorService sStatsExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final Handler mUiHandler = new Handler(Looper.getMainLooper());
    private final Runnable mHideOsdRunnable = this::hideOsd;
    private final Runnable mBufferingStatsRunnable = new Runnable() {
        @Override
        public void run() {
            if (mLoadingSpinner != null && mLoadingSpinner.getVisibility() == View.VISIBLE && !isFinishing()) {
                sStatsExecutor.execute(() -> {
                    try {
                        com.streambravia.tv.engine.TorrentEngine eng = com.streambravia.tv.engine.TorrentEngine.getInstance(NativePlayerActivity.this);
                        org.libtorrent4j.TorrentStatus st = (eng != null) ? eng.getStatus() : null;
                        if (st != null) {
                            int seeds = st.numSeeds();
                            int peers = st.numPeers();
                            long rate = st.downloadRate();
                            float prog = st.progress() * 100f;
                            final String statusText;
                            if (rate > 0) {
                                double speedMb = rate / (1024.0 * 1024.0);
                                statusText = String.format(Locale.US, "Buffering... %.2f MB/s • %d Seeds (%.1f%%)", speedMb, seeds, prog);
                            } else if (peers > 0 || seeds > 0) {
                                statusText = String.format(Locale.US, "Connected to %d peers (%d seeds) • Initializing...", peers, seeds);
                            } else {
                                statusText = "Connecting to swarm trackers & DHT...";
                            }
                            mUiHandler.post(() -> {
                                if (mBufferingStatusText != null && !isFinishing()) {
                                    mBufferingStatusText.setText(statusText);
                                }
                            });
                        }
                    } catch (Throwable ignored) {}
                });
                mUiHandler.postDelayed(this, 500);
            }
        }
    };
    private final Runnable mUpdateProgressRunnable = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            renderActiveSubtitles();
            if (mExoPlayer != null && mExoPlayer.isPlaying()) {
                mUiHandler.postDelayed(this, 250);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Ensure process-level LocalTorrentServer and TorrentEngine are running
        com.streambravia.tv.engine.LocalTorrentServer.getInstance(this);
        com.streambravia.tv.engine.TorrentEngine.getInstance(this);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);

        Intent intent = getIntent();
        mStreamUrl = intent.getStringExtra(EXTRA_STREAM_URL);
        mTitle = intent.getStringExtra(EXTRA_TITLE);
        mStartPosMs = intent.getLongExtra(EXTRA_START_POS_MS, 0);

        String srv = intent.getStringExtra(EXTRA_SERVER_URL);
        if (srv != null && !srv.isEmpty()) mServerUrl = srv;
        mInfoHash = intent.getStringExtra(EXTRA_INFO_HASH);
        if (mInfoHash == null) mInfoHash = "";
        mImdbId = intent.getStringExtra(EXTRA_IMDB_ID);
        if (mImdbId == null) mImdbId = "";
        mMediaType = intent.getStringExtra(EXTRA_MEDIA_TYPE);
        if (mMediaType == null) mMediaType = "movie";
        mSeason = intent.getIntExtra(EXTRA_SEASON, 0);
        mEpisode = intent.getIntExtra(EXTRA_EPISODE, 0);
        mOriginalLanguage = intent.getStringExtra(EXTRA_ORIGINAL_LANGUAGE);
        if (mOriginalLanguage == null) mOriginalLanguage = "";

        mRootLayout = new FrameLayout(this);
        mRootLayout.setBackgroundColor(Color.BLACK);
        setContentView(mRootLayout);

        // AspectRatioFrameLayout: strictly preserves original aspect ratio (FIT mode)
        mAspectRatioLayout = new AspectRatioFrameLayout(this);
        mAspectRatioLayout.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        FrameLayout.LayoutParams aspectParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        aspectParams.gravity = Gravity.CENTER;
        mRootLayout.addView(mAspectRatioLayout, aspectParams);

        // SurfaceView: Pure GPU zero-copy hardware decoding
        mSurfaceView = new SurfaceView(this);
        mSurfaceView.getHolder().addCallback(this);
        mAspectRatioLayout.addView(mSurfaceView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // SubtitleView: Media3 UI Subtitle rendering with custom typography
        mSubtitleView = new SubtitleView(this);
        mSubtitleView.setStyle(new CaptionStyleCompat(
                Color.WHITE,
                Color.TRANSPARENT,
                Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
                Color.parseColor("#CC000000"),
                Typeface.DEFAULT_BOLD
        ));
        mSubtitleView.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, mSubtitleFontSizeSp);
        mSubtitleView.setBottomPaddingFraction(0.08f);
        mRootLayout.addView(mSubtitleView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Buffering spinner & swarm status HUD
        LinearLayout bufferCol = new LinearLayout(this);
        bufferCol.setOrientation(LinearLayout.VERTICAL);
        bufferCol.setGravity(Gravity.CENTER);

        mLoadingSpinner = new ProgressBar(this);
        bufferCol.addView(mLoadingSpinner);

        mBufferingStatusText = new TextView(this);
        mBufferingStatusText.setTextColor(Color.parseColor("#E0E0E0"));
        mBufferingStatusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        mBufferingStatusText.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        mBufferingStatusText.setPadding(0, 16, 0, 0);
        mBufferingStatusText.setGravity(Gravity.CENTER);
        mBufferingStatusText.setText("Preparing high-speed stream...");
        bufferCol.addView(mBufferingStatusText);

        FrameLayout.LayoutParams spinParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        spinParams.gravity = Gravity.CENTER;
        mRootLayout.addView(bufferCol, spinParams);

        setupSubtitleDrawer();
        setupOsd();
        initPlayer();

        // Load pre-passed subtitles if provided, otherwise fetch online
        String subJson = intent.getStringExtra(EXTRA_SUBTITLES_JSON);
        if (subJson != null && !subJson.isEmpty()) {
            parseAndPopulateSubtitles(subJson);
        } else {
            fetchSubtitlesOnline("", false);
        }
    }

    private void initPlayer() {
        // Optimized playback buffer tuned for low-memory (1GB) Android TVs:
        // 12s minimum cushion, 35s max reservoir, 1.5s fast initial startup, 3.5s rebuffer threshold
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(12000, 35000, 1500, 3500)
                .setBackBuffer(8000, false)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();

        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(this)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                .setEnableDecoderFallback(true)
                .setAllowedVideoJoiningTimeMs(6000);

        DeviceCapabilities caps = DeviceCapabilities.getInstance(this);
        String prefAudio = resolveLanguageCode(mOriginalLanguage);
        if (prefAudio == null || prefAudio.isEmpty()) {
            prefAudio = "ta"; // Default for regional, or fall back to track 0
        }
        mTrackSelector = new DefaultTrackSelector(this);
        mTrackSelector.setParameters(
                mTrackSelector.buildUponParameters()
                        .setMaxVideoSize(caps.getMaxDecodeWidth(), caps.getMaxDecodeHeight()) // Dynamically adapts to 4K, 8K, or 1080p hardware
                        .setTunnelingEnabled(false) // Direct buffer delivery without hardware tunneling
                        .setPreferredAudioLanguage(prefAudio)
                        .setForceHighestSupportedBitrate(false)
                        .setExceedRendererCapabilitiesIfNecessary(true)
        );

        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .setUsage(C.USAGE_MEDIA)
                .build();

        DefaultHttpDataSource.Factory httpDataSourceFactory = new DefaultHttpDataSource.Factory()
                .setUserAgent("StreamBraviaTV/1.0")
                .setConnectTimeoutMs(60000)
                .setReadTimeoutMs(60000)
                .setAllowCrossProtocolRedirects(true)
                .setKeepPostFor302Redirects(true);

        androidx.media3.extractor.DefaultExtractorsFactory extractorsFactory = new androidx.media3.extractor.DefaultExtractorsFactory()
                .setConstantBitrateSeekingEnabled(true);

        DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(httpDataSourceFactory, extractorsFactory);

        mExoPlayer = new ExoPlayer.Builder(this, renderersFactory)
                .setMediaSourceFactory(mediaSourceFactory)
                .setLoadControl(loadControl)
                .setTrackSelector(mTrackSelector)
                .setAudioAttributes(audioAttributes, true)
                .build();

        mExoPlayer.setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC);

        mExoPlayer.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT);
        mExoPlayer.setVideoSurfaceView(mSurfaceView);
        mExoPlayer.addListener(new Player.Listener() {
            @Override
            public void onTracksChanged(androidx.media3.common.Tracks tracks) {
                if (mOriginalLanguage != null && !mOriginalLanguage.isEmpty() && mTrackSelector != null) {
                    selectOriginalAudioTrack(tracks, mOriginalLanguage);
                }
            }

            @Override
            public void onVideoSizeChanged(VideoSize videoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    float pixelRatio = videoSize.pixelWidthHeightRatio > 0 ? videoSize.pixelWidthHeightRatio : 1.0f;
                    float aspectRatio = ((float) videoSize.width * pixelRatio) / (float) videoSize.height;
                    mAspectRatioLayout.setAspectRatio(aspectRatio);
                    Log.i(TAG, "Aspect Ratio Preserved: " + videoSize.width + "x" + videoSize.height + " (ratio=" + aspectRatio + ")");
                }
            }

            @Override
            public void onRenderedFirstFrame() {
                mIsSeeking = false;
                mLoadingSpinner.setVisibility(View.GONE);
                mBufferingStatusText.setVisibility(View.GONE);
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_BUFFERING) {
                    mLoadingSpinner.setVisibility(View.VISIBLE);
                    mBufferingStatusText.setVisibility(View.VISIBLE);
                    mUiHandler.post(mBufferingStatsRunnable);
                } else if (state == Player.STATE_READY) {
                    mIsSeeking = false;
                    mLoadingSpinner.setVisibility(View.GONE);
                    mBufferingStatusText.setVisibility(View.GONE);
                    mUiHandler.removeCallbacks(mBufferingStatsRunnable);
                    updateProgress();
                } else if (state == Player.STATE_ENDED) {
                    mIsSeeking = false;
                    finishWithResult();
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (mBtnPlayPause != null) {
                    mBtnPlayPause.setText(isPlaying ? "⏸ Pause" : "▶ Play");
                }
                if (isPlaying) {
                    mIsSeeking = false;
                    mLoadingSpinner.setVisibility(View.GONE);
                    mBufferingStatusText.setVisibility(View.GONE);
                    mUiHandler.removeCallbacks(mBufferingStatsRunnable);
                    mUiHandler.post(mUpdateProgressRunnable);
                }
            }

            @Override
            public void onPlayerError(androidx.media3.common.PlaybackException error) {
                Log.e(TAG, "ExoPlayer error: " + error.getErrorCodeName() + " (" + error.errorCode + ") - " + error.getMessage(), error);
                // If audio or decoder initialization failed, retry with software decoding / stereo downmix
                if (error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                        || error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED) {
                    mBufferingStatusText.setText("Adapting audio/video codec compatibility...");
                    mTrackSelector.setParameters(
                            mTrackSelector.buildUponParameters()
                                    .setExceedRendererCapabilitiesIfNecessary(true)
                                    .setForceLowestBitrate(true)
                    );
                    mExoPlayer.prepare();
                    mExoPlayer.play();
                    return;
                }

                // If mid-stream error during playback (e.g. transient swarm buffer pause, container parser hiccup, IO timeout)
                // Strictly guard with !mIsSeeking to prevent overriding user seeking operations with old position!
                if (mLastKnownPositionMs > 3000 && !mIsSeeking) {
                    final long currentGen = mSeekGeneration;
                    final long resumePos = Math.max(0, mLastKnownPositionMs - 2500);
                    Log.w(TAG, "Mid-stream error (" + error.getErrorCodeName() + ") at " + mLastKnownPositionMs + "ms. Auto-recovering at " + resumePos + "ms...");
                    mLoadingSpinner.setVisibility(View.VISIBLE);
                    mBufferingStatusText.setVisibility(View.VISIBLE);
                    mBufferingStatusText.setText("Buffering swarm & resuming (" + error.getErrorCodeName() + ")...");
                    mUiHandler.removeCallbacks(mBufferingStatsRunnable);
                    mUiHandler.post(mBufferingStatsRunnable);

                    mUiHandler.postDelayed(() -> {
                        if (mExoPlayer != null && !isFinishing()) {
                            if (mSeekGeneration != currentGen || mIsSeeking) {
                                Log.i(TAG, "Ignoring stale auto-recovery seek; new user seek generation in progress");
                                return;
                            }
                            Log.i(TAG, "Executing auto-recovery seek to " + resumePos + "ms...");
                            mExoPlayer.seekTo(resumePos);
                            mExoPlayer.prepare();
                            mExoPlayer.play();
                        }
                    }, 2000);
                    return;
                }

                // Inspect HTTP status codes from local proxy server
                int httpStatusCode = 0;
                Throwable cause = error.getCause();
                while (cause != null) {
                    if (cause instanceof androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                        httpStatusCode = ((androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) cause).responseCode;
                        break;
                    }
                    cause = cause.getCause();
                }

                // RFC 7233 416 Range Not Satisfiable: playhead attempted to read past end of file
                if (httpStatusCode == 416 && mExoPlayer != null) {
                    long dur = mExoPlayer.getDuration();
                    long fallbackPos = (dur > 6000) ? dur - 6000 : 0;
                    Log.w(TAG, "HTTP 416 Range Not Satisfiable. Re-aligning playhead to " + fallbackPos + "ms...");
                    mExoPlayer.seekTo(fallbackPos);
                    mExoPlayer.prepare();
                    mExoPlayer.play();
                    return;
                }

                // If IO / HTTP status / timeout error during initial swarm connection, auto-retry seamlessly
                if (error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
                        || error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                        || error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                        || error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_UNSPECIFIED) {
                    Log.w(TAG, "Swarm buffering or transient IO error (" + error.getErrorCodeName() + " / HTTP " + httpStatusCode + "), auto-reconnecting in 1.5s...");
                    mLoadingSpinner.setVisibility(View.VISIBLE);
                    mBufferingStatusText.setVisibility(View.VISIBLE);
                    mBufferingStatusText.setText("Connecting to swarm trackers & DHT...");
                    mUiHandler.removeCallbacks(mBufferingStatsRunnable);
                    mUiHandler.post(mBufferingStatsRunnable);
                    mUiHandler.postDelayed(() -> {
                        if (mExoPlayer != null && !isFinishing()) {
                            Log.i(TAG, "Auto-retrying ExoPlayer after swarm buffer catchup...");
                            mExoPlayer.prepare();
                            mExoPlayer.play();
                        }
                    }, 1500);
                    return;
                }
                mLoadingSpinner.setVisibility(View.GONE);
                mBufferingStatusText.setVisibility(View.VISIBLE);
                mBufferingStatusText.setText("Buffering swarm or trying alternate track (" + error.getErrorCodeName() + ")");
                mUiHandler.removeCallbacks(mBufferingStatsRunnable);
            }
        });

        if (mStreamUrl != null && !mStreamUrl.isEmpty()) {
            MediaItem.Builder mib = new MediaItem.Builder().setUri(Uri.parse(mStreamUrl));
            // Let ExoPlayer sniff MP4, Matroska, TS dynamically without restricting extractors
            MediaItem item = mib.build();
            mExoPlayer.setMediaItem(item);
            if (mStartPosMs > 0) {
                mExoPlayer.seekTo(mStartPosMs);
            }
            mExoPlayer.prepare();
            mExoPlayer.play();
            mUiHandler.post(mBufferingStatsRunnable);
        }
    }

    private void setupOsd() {
        mOsdLayout = new FrameLayout(this);

        // TOP BAR
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(48, 28, 48, 20);
        GradientDrawable topScrim = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.parseColor("#D9000000"), Color.TRANSPARENT}
        );
        topBar.setBackground(topScrim);

        mBtnBack = createStyledButton("← Back", false);
        mBtnBack.setOnClickListener(v -> finishWithResult());
        topBar.addView(mBtnBack);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleCol.setPadding(24, 0, 24, 0);

        mTitleText = new TextView(this);
        mTitleText.setText(mTitle != null ? mTitle : "StreamBravia Player");
        mTitleText.setTextColor(Color.WHITE);
        mTitleText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        mTitleText.setTypeface(Typeface.DEFAULT_BOLD);
        titleCol.addView(mTitleText);

        TextView subBadge = new TextView(this);
        subBadge.setText("1080p Full HD • GPU Hardware Direct Decode");
        subBadge.setTextColor(Color.parseColor("#FFA726"));
        subBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        titleCol.addView(subBadge);

        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        topBar.addView(titleCol, titleParams);

        mClockText = new TextView(this);
        mClockText.setTextColor(Color.LTGRAY);
        mClockText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        mClockText.setText(new SimpleDateFormat("hh:mm a", Locale.getDefault()).format(new Date()));
        topBar.addView(mClockText);

        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        topParams.gravity = Gravity.TOP;
        mOsdLayout.addView(topBar, topParams);

        // BOTTOM BAR
        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.VERTICAL);
        bottomBar.setPadding(48, 24, 48, 36);
        GradientDrawable botScrim = new GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{Color.parseColor("#E6000000"), Color.TRANSPARENT}
        );
        bottomBar.setBackground(botScrim);

        // Time indicators row
        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        timeRow.setPadding(4, 0, 4, 8);

        mTimePosText = new TextView(this);
        mTimePosText.setText("00:00:00");
        mTimePosText.setTextColor(Color.WHITE);
        mTimePosText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        timeRow.addView(mTimePosText);

        View timeSpacer = new View(this);
        timeRow.addView(timeSpacer, new LinearLayout.LayoutParams(0, 1, 1.0f));

        mTimeDurText = new TextView(this);
        mTimeDurText.setText("00:00:00");
        mTimeDurText.setTextColor(Color.LTGRAY);
        mTimeDurText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        timeRow.addView(mTimeDurText);
        bottomBar.addView(timeRow);

        // Interactive Focusable SeekBar (Scrubber)
        mSeekBar = new SeekBar(this);
        mSeekBar.setMax(1000);
        mSeekBar.setFocusable(true);
        mSeekBar.setFocusableInTouchMode(false);
        mSeekBar.setPadding(12, 12, 12, 12);
        mSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && mExoPlayer != null) {
                    long dur = mExoPlayer.getDuration();
                    if (dur > 0) {
                        long target = (dur * progress) / 1000;
                        mTimePosText.setText(formatTime(target));
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                mUiHandler.removeCallbacks(mHideOsdRunnable);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (mExoPlayer != null) {
                    long dur = mExoPlayer.getDuration();
                    if (dur > 0) {
                        mIsSeeking = true;
                        mSeekGeneration++;
                        mExoPlayer.seekTo((dur * seekBar.getProgress()) / 1000);
                    }
                }
                resetOsdTimer();
            }
        });

        bottomBar.addView(mSeekBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Action Buttons Row (Play, Pause, Seek, Subtitle, Audio, Aspect)
        LinearLayout controlsRow = new LinearLayout(this);
        controlsRow.setOrientation(LinearLayout.HORIZONTAL);
        controlsRow.setGravity(Gravity.CENTER_VERTICAL);
        controlsRow.setPadding(0, 14, 0, 0);

        mBtnRewind = createStyledButton("⏪ -10s", false);
        mBtnRewind.setOnClickListener(v -> seekRelative(-10000));
        controlsRow.addView(mBtnRewind);

        mBtnPlayPause = createStyledButton("⏸ Pause", true);
        mBtnPlayPause.setOnClickListener(v -> togglePlayPause());
        controlsRow.addView(mBtnPlayPause);

        mBtnForward = createStyledButton("⏩ +10s", false);
        mBtnForward.setOnClickListener(v -> seekRelative(10000));
        controlsRow.addView(mBtnForward);

        // Subtitle Icon Button: Tucked neatly into the controls bar
        mBtnSubtitles = createStyledButton("💬 Subtitles (CC)", false);
        mBtnSubtitles.setOnClickListener(v -> toggleSubtitleDrawer());
        controlsRow.addView(mBtnSubtitles);

        mBtnAudio = createStyledButton("🔊 Audio", false);
        mBtnAudio.setOnClickListener(v -> cycleAudioTrack());
        controlsRow.addView(mBtnAudio);

        mBtnAspect = createStyledButton("⛶ Fit", false);
        mBtnAspect.setOnClickListener(v -> cycleAspectRatio());
        controlsRow.addView(mBtnAspect);

        // Explicit D-pad Leanback Focus Navigation Chain
        mBtnBack.setId(View.generateViewId());
        mSeekBar.setId(View.generateViewId());
        mBtnRewind.setId(View.generateViewId());
        mBtnPlayPause.setId(View.generateViewId());
        mBtnForward.setId(View.generateViewId());
        mBtnSubtitles.setId(View.generateViewId());
        mBtnAudio.setId(View.generateViewId());
        mBtnAspect.setId(View.generateViewId());

        mBtnBack.setNextFocusDownId(mSeekBar.getId());

        mSeekBar.setNextFocusUpId(mBtnBack.getId());
        mSeekBar.setNextFocusDownId(mBtnPlayPause.getId());

        // Buttons horizontal chain: Rewind <-> Play/Pause <-> Forward <-> Subtitles <-> Audio <-> Aspect
        mBtnRewind.setNextFocusUpId(mSeekBar.getId());
        mBtnRewind.setNextFocusLeftId(mBtnRewind.getId());
        mBtnRewind.setNextFocusRightId(mBtnPlayPause.getId());

        mBtnPlayPause.setNextFocusUpId(mSeekBar.getId());
        mBtnPlayPause.setNextFocusLeftId(mBtnRewind.getId());
        mBtnPlayPause.setNextFocusRightId(mBtnForward.getId());

        mBtnForward.setNextFocusUpId(mSeekBar.getId());
        mBtnForward.setNextFocusLeftId(mBtnPlayPause.getId());
        mBtnForward.setNextFocusRightId(mBtnSubtitles.getId());

        mBtnSubtitles.setNextFocusUpId(mSeekBar.getId());
        mBtnSubtitles.setNextFocusLeftId(mBtnForward.getId());
        mBtnSubtitles.setNextFocusRightId(mBtnAudio.getId());

        mBtnAudio.setNextFocusUpId(mSeekBar.getId());
        mBtnAudio.setNextFocusLeftId(mBtnSubtitles.getId());
        mBtnAudio.setNextFocusRightId(mBtnAspect.getId());

        mBtnAspect.setNextFocusUpId(mSeekBar.getId());
        mBtnAspect.setNextFocusLeftId(mBtnAudio.getId());
        mBtnAspect.setNextFocusRightId(mBtnAspect.getId());

        bottomBar.addView(controlsRow);

        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bottomParams.gravity = Gravity.BOTTOM;
        mOsdLayout.addView(bottomBar, bottomParams);

        mRootLayout.addView(mOsdLayout, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Focus play button by default
        mBtnPlayPause.requestFocus();
        showOsd();
    }

    private void setupSubtitleDrawer() {
        mSubtitleDrawerLayout = new FrameLayout(this);
        mSubtitleDrawerLayout.setVisibility(View.GONE);
        mSubtitleDrawerLayout.setBackgroundColor(Color.parseColor("#EE0B0E14"));

        LinearLayout drawerContent = new LinearLayout(this);
        drawerContent.setOrientation(LinearLayout.VERTICAL);
        drawerContent.setPadding(40, 32, 40, 32);

        // Drawer Header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("💬 Subtitles & Closed Captions (OpenSubtitles / VLSub)");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        mBtnCloseSubDrawer = createStyledButton("✕ Close (Back)", false);
        mBtnCloseSubDrawer.setOnClickListener(v -> toggleSubtitleDrawer());
        header.addView(mBtnCloseSubDrawer);
        drawerContent.addView(header);

        ScrollView scroll = new ScrollView(this);
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(0, 16, 0, 16);

        // 1. ACTIVE SUBTITLE SECTION
        TextView sec1 = createSectionHeader("Active Subtitle Track");
        inner.addView(sec1);

        mSubActiveContainer = new LinearLayout(this);
        mSubActiveContainer.setOrientation(LinearLayout.VERTICAL);
        inner.addView(mSubActiveContainer);

        // 2. TIMING SYNC OFFSET CONTROLS
        TextView sec2 = createSectionHeader("⏱ Subtitle Sync Offset (±10s)");
        inner.addView(sec2);

        LinearLayout syncRow = new LinearLayout(this);
        syncRow.setOrientation(LinearLayout.HORIZONTAL);
        syncRow.setGravity(Gravity.CENTER_VERTICAL);
        syncRow.setPadding(0, 8, 0, 16);

        Button btnMinus1 = createSmallButton("-1.0s");
        btnMinus1.setOnClickListener(v -> adjustSyncOffset(-1000));
        syncRow.addView(btnMinus1);

        Button btnMinus05 = createSmallButton("-0.5s");
        btnMinus05.setOnClickListener(v -> adjustSyncOffset(-500));
        syncRow.addView(btnMinus05);

        mSubSyncDisplay = new TextView(this);
        mSubSyncDisplay.setText("0.0s");
        mSubSyncDisplay.setTextColor(Color.parseColor("#FFA726"));
        mSubSyncDisplay.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        mSubSyncDisplay.setTypeface(Typeface.DEFAULT_BOLD);
        mSubSyncDisplay.setPadding(20, 0, 20, 0);
        syncRow.addView(mSubSyncDisplay);

        Button btnPlus05 = createSmallButton("+0.5s");
        btnPlus05.setOnClickListener(v -> adjustSyncOffset(500));
        syncRow.addView(btnPlus05);

        Button btnPlus1 = createSmallButton("+1.0s");
        btnPlus1.setOnClickListener(v -> adjustSyncOffset(1000));
        syncRow.addView(btnPlus1);

        Button btnReset = createSmallButton("Reset (0s)");
        btnReset.setOnClickListener(v -> {
            mSubtitleSyncOffsetMs = 0;
            updateSyncDisplay();
        });
        syncRow.addView(btnReset);
        inner.addView(syncRow);

        // 3. FONT SIZE OPTIONS
        TextView sec3 = createSectionHeader("🔤 Font Size");
        inner.addView(sec3);

        LinearLayout fontRow = new LinearLayout(this);
        fontRow.setOrientation(LinearLayout.HORIZONTAL);
        fontRow.setPadding(0, 8, 0, 16);

        Button btnSmall = createSmallButton("Small (18sp)");
        btnSmall.setOnClickListener(v -> setSubtitleFontSize(18.0f));
        fontRow.addView(btnSmall);

        Button btnMed = createSmallButton("Medium (24sp)");
        btnMed.setOnClickListener(v -> setSubtitleFontSize(24.0f));
        fontRow.addView(btnMed);

        Button btnLarge = createSmallButton("Large (30sp)");
        btnLarge.setOnClickListener(v -> setSubtitleFontSize(30.0f));
        fontRow.addView(btnLarge);

        Button btnXLarge = createSmallButton("X-Large (38sp)");
        btnXLarge.setOnClickListener(v -> setSubtitleFontSize(38.0f));
        fontRow.addView(btnXLarge);
        inner.addView(fontRow);

        // 4. AUTOMATED SEARCH & DOWNLOAD ONLINE
        TextView sec4 = createSectionHeader("📥 Search & Download Subtitles (VLSub & OpenSubtitles)");
        inner.addView(sec4);

        // Auto-populated video title search box
        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setPadding(0, 8, 0, 12);

        mSubSearchInput = new EditText(this);
        mSubSearchInput.setText(cleanTitleForSearch(mTitle));
        mSubSearchInput.setTextColor(Color.WHITE);
        mSubSearchInput.setHint("Enter video / movie title...");
        mSubSearchInput.setHintTextColor(Color.GRAY);
        mSubSearchInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        mSubSearchInput.setPadding(16, 12, 16, 12);
        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setColor(Color.parseColor("#222836"));
        inputBg.setCornerRadius(8);
        inputBg.setStroke(1, Color.parseColor("#445566"));
        mSubSearchInput.setBackground(inputBg);
        searchRow.addView(mSubSearchInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        Button btnSearchName = createStyledButton("🔍 Search Name", true);
        btnSearchName.setOnClickListener(v -> {
            String q = mSubSearchInput.getText().toString().trim();
            fetchSubtitlesOnline(q, false);
        });
        searchRow.addView(btnSearchName);

        // Option to search using file hash (OpenSubtitles VLSub 64-bit checksum)
        Button btnSearchHash = createStyledButton("# Search Hash (VLSub)", false);
        btnSearchHash.setOnClickListener(v -> fetchSubtitlesOnline("", true));
        searchRow.addView(btnSearchHash);
        inner.addView(searchRow);

        // Language quick chips
        HorizontalScrollView langScroll = new HorizontalScrollView(this);
        LinearLayout langRow = new LinearLayout(this);
        langRow.setOrientation(LinearLayout.HORIZONTAL);
        langRow.setPadding(0, 4, 0, 12);

        String[] langs = {"all:All Languages", "eng:English", "spa:Spanish", "hin:Hindi", "tam:Tamil", "tel:Telugu", "fre:French", "ger:German"};
        for (String item : langs) {
            String[] parts = item.split(":");
            Button chip = createSmallButton(parts[1]);
            chip.setOnClickListener(v -> {
                mSelectedSubLang = parts[0];
                Toast.makeText(this, "Language: " + parts[1], Toast.LENGTH_SHORT).show();
                fetchSubtitlesOnline(mSubSearchInput.getText().toString().trim(), false);
            });
            langRow.addView(chip);
        }
        langScroll.addView(langRow);
        inner.addView(langScroll);

        mSubResultsContainer = new LinearLayout(this);
        mSubResultsContainer.setOrientation(LinearLayout.VERTICAL);
        inner.addView(mSubResultsContainer);

        scroll.addView(inner);
        drawerContent.addView(scroll);

        FrameLayout.LayoutParams drawerParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        mSubtitleDrawerLayout.addView(drawerContent, drawerParams);
        mRootLayout.addView(mSubtitleDrawerLayout);
    }

    private Button createStyledButton(String text, boolean isPrimary) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setFocusable(true);
        b.setFocusableInTouchMode(false);
        b.setPadding(24, 12, 24, 12);

        int normalColor = isPrimary ? Color.parseColor("#FF5722") : Color.parseColor("#263238");
        int focusedColor = Color.parseColor("#FFA726");

        GradientDrawable normalDraw = new GradientDrawable();
        normalDraw.setColor(normalColor);
        normalDraw.setCornerRadius(10);
        normalDraw.setStroke(1, Color.parseColor("#546E7A"));

        GradientDrawable focusedDraw = new GradientDrawable();
        focusedDraw.setColor(focusedColor);
        focusedDraw.setCornerRadius(10);
        focusedDraw.setStroke(3, Color.WHITE);

        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, focusedDraw);
        states.addState(new int[]{}, normalDraw);
        b.setBackground(states);

        b.setTextColor(Color.WHITE);
        b.setOnFocusChangeListener((v, hasFocus) -> {
            b.setTextColor(hasFocus ? Color.BLACK : Color.WHITE);
            resetOsdTimer();
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(10, 0, 10, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private Button createSmallButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        b.setPadding(16, 8, 16, 8);

        GradientDrawable normalDraw = new GradientDrawable();
        normalDraw.setColor(Color.parseColor("#1E2430"));
        normalDraw.setCornerRadius(6);
        normalDraw.setStroke(1, Color.parseColor("#37474F"));

        GradientDrawable focusedDraw = new GradientDrawable();
        focusedDraw.setColor(Color.parseColor("#FFA726"));
        focusedDraw.setCornerRadius(6);
        focusedDraw.setStroke(2, Color.WHITE);

        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, focusedDraw);
        states.addState(new int[]{}, normalDraw);
        b.setBackground(states);

        b.setTextColor(Color.WHITE);
        b.setOnFocusChangeListener((v, hasFocus) -> b.setTextColor(hasFocus ? Color.BLACK : Color.WHITE));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(6, 4, 6, 4);
        b.setLayoutParams(lp);
        return b;
    }

    private TextView createSectionHeader(String title) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextColor(Color.parseColor("#90CAF9"));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, 16, 0, 8);
        return tv;
    }

    private void showOsd() {
        if (mSubtitleDrawerLayout != null && mSubtitleDrawerLayout.getVisibility() == View.VISIBLE) return;
        if (mOsdLayout != null) {
            mOsdLayout.setVisibility(View.VISIBLE);
        }
        resetOsdTimer();
    }

    private void hideOsd() {
        if (mOsdLayout != null) {
            mOsdLayout.setVisibility(View.GONE);
        }
    }

    private void resetOsdTimer() {
        mUiHandler.removeCallbacks(mHideOsdRunnable);
        mUiHandler.postDelayed(mHideOsdRunnable, 5000);
    }

    private void togglePlayPause() {
        if (mExoPlayer == null) return;
        if (mExoPlayer.isPlaying()) {
            mExoPlayer.pause();
        } else {
            mExoPlayer.play();
        }
        resetOsdTimer();
    }

    private long mPendingSeekTargetMs = -1;
    private final Runnable mPendingSeekRunnable = new Runnable() {
        @Override
        public void run() {
            if (mExoPlayer != null && mPendingSeekTargetMs >= 0) {
                final long target = mPendingSeekTargetMs;
                mPendingSeekTargetMs = -1;
                mExoPlayer.seekTo(target);
            }
        }
    };

    private void seekRelative(long deltaMs) {
        if (mExoPlayer == null) return;
        mIsSeeking = true;
        mSeekGeneration++;
        long dur = mExoPlayer.getDuration();
        long basePos = (mPendingSeekTargetMs >= 0) ? mPendingSeekTargetMs : mExoPlayer.getCurrentPosition();
        long target = Math.max(0, (dur > 0) ? Math.min(dur, basePos + deltaMs) : basePos + deltaMs);
        mPendingSeekTargetMs = target;
        mLastKnownPositionMs = target;

        // Instant UI feedback on seekbar and time text without waiting for debounce
        if (dur > 0) {
            int progress = (int) ((target * 1000) / dur);
            mSeekBar.setProgress(progress);
            mTimePosText.setText(formatTime(target));
            long rem = Math.max(0, dur - target);
            mTimeDurText.setText(formatTime(dur) + " (-" + formatTime(rem) + ")");
        }
        showOsd();
        resetOsdTimer();

        // Debounce actual playback seek by 250ms to coalesce rapid remote D-pad presses
        mUiHandler.removeCallbacks(mPendingSeekRunnable);
        mUiHandler.postDelayed(mPendingSeekRunnable, 250);
    }

    private void updateProgress() {
        if (mExoPlayer == null) return;
        if (mPendingSeekTargetMs >= 0) {
            // User is actively scrubbing / debouncing seek; do not overwrite UI with old playhead position
            return;
        }
        long pos = mExoPlayer.getCurrentPosition();
        if (pos > 0) {
            mLastKnownPositionMs = pos;
        }
        long dur = mExoPlayer.getDuration();
        if (dur > 0) {
            int progress = (int) ((pos * 1000) / dur);
            mSeekBar.setProgress(progress);
            mTimePosText.setText(formatTime(pos));
            long rem = Math.max(0, dur - pos);
            mTimeDurText.setText(formatTime(dur) + " (-" + formatTime(rem) + ")");
        }
    }

    private String formatTime(long ms) {
        long s = ms / 1000;
        long hr = s / 3600;
        long rem = s % 3600;
        long min = rem / 60;
        long sec = rem % 60;
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", hr, min, sec);
    }

    private void toggleSubtitleDrawer() {
        if (mSubtitleDrawerLayout == null) return;
        if (mSubtitleDrawerLayout.getVisibility() == View.VISIBLE) {
            mSubtitleDrawerLayout.setVisibility(View.GONE);
            showOsd();
            if (mBtnSubtitles != null) mBtnSubtitles.requestFocus();
        } else {
            hideOsd();
            mSubtitleDrawerLayout.setVisibility(View.VISIBLE);
            updateSyncDisplay();
            renderActiveTracksList();
            if (mBtnCloseSubDrawer != null) mBtnCloseSubDrawer.requestFocus();
        }
    }

    private void adjustSyncOffset(long deltaMs) {
        mSubtitleSyncOffsetMs += deltaMs;
        updateSyncDisplay();
        Toast.makeText(this, "Subtitle Offset: " + (mSubtitleSyncOffsetMs / 1000.0f) + "s", Toast.LENGTH_SHORT).show();
    }

    private void updateSyncDisplay() {
        if (mSubSyncDisplay != null) {
            float sec = mSubtitleSyncOffsetMs / 1000.0f;
            mSubSyncDisplay.setText(String.format(Locale.getDefault(), "%+.1fs", sec));
        }
    }

    private void setSubtitleFontSize(float sizeSp) {
        mSubtitleFontSizeSp = sizeSp;
        if (mSubtitleView != null) {
            mSubtitleView.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        }
        Toast.makeText(this, "Font Size: " + (int)sizeSp + "sp", Toast.LENGTH_SHORT).show();
    }

    private void cycleAspectRatio() {
        mAspectModeIndex = (mAspectModeIndex + 1) % 3;
        if (mAspectModeIndex == 0) {
            mAspectRatioLayout.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
            mBtnAspect.setText("⛶ Fit");
            Toast.makeText(this, "Aspect Ratio: FIT (Original)", Toast.LENGTH_SHORT).show();
        } else if (mAspectModeIndex == 1) {
            mAspectRatioLayout.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
            mBtnAspect.setText("⛶ Zoom");
            Toast.makeText(this, "Aspect Ratio: ZOOM (Fill screen)", Toast.LENGTH_SHORT).show();
        } else {
            mAspectRatioLayout.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);
            mBtnAspect.setText("⛶ Stretch");
            Toast.makeText(this, "Aspect Ratio: STRETCH", Toast.LENGTH_SHORT).show();
        }
        resetOsdTimer();
    }

    private String resolveLanguageCode(String lang) {
        if (lang == null || lang.isEmpty()) return null;
        String l = lang.toLowerCase();
        if (l.contains("tamil") || l.equals("ta")) return "tam";
        if (l.contains("telugu") || l.equals("te")) return "tel";
        if (l.contains("malayalam") || l.equals("ml")) return "mal";
        if (l.contains("kannada") || l.equals("kn")) return "kan";
        if (l.contains("hindi") || l.equals("hi")) return "hin";
        if (l.contains("korean") || l.equals("ko")) return "kor";
        if (l.contains("japanese") || l.equals("ja")) return "jpn";
        if (l.contains("spanish") || l.equals("es")) return "spa";
        if (l.contains("french") || l.equals("fr")) return "fra";
        if (l.contains("german") || l.equals("de")) return "deu";
        if (l.contains("english") || l.equals("en")) return "eng";
        return l;
    }

    private void selectOriginalAudioTrack(androidx.media3.common.Tracks tracks, String origLang) {
        if (tracks == null || origLang == null || origLang.isEmpty()) return;
        String code = resolveLanguageCode(origLang);
        String name = origLang.toLowerCase();

        for (androidx.media3.common.Tracks.Group group : tracks.getGroups()) {
            if (group.getType() == C.TRACK_TYPE_AUDIO) {
                for (int i = 0; i < group.length; i++) {
                    androidx.media3.common.Format format = group.getTrackFormat(i);
                    String trackLang = format.language != null ? format.language.toLowerCase() : "";
                    String trackLabel = format.label != null ? format.label.toLowerCase() : "";

                    boolean match = (code != null && (trackLang.contains(code) || trackLabel.contains(code)))
                            || trackLang.contains(name) || trackLabel.contains(name);

                    if (match && !group.isTrackSelected(i)) {
                        Log.i(TAG, "Auto-switching to original audio track: " + format.language + " (" + format.label + ")");
                        mTrackSelector.setParameters(
                                mTrackSelector.buildUponParameters()
                                        .setPreferredAudioLanguage(format.language != null ? format.language : code)
                        );
                        return;
                    }
                }
            }
        }
    }

    private void cycleAudioTrack() {
        if (mExoPlayer == null || mTrackSelector == null) return;
        androidx.media3.common.Tracks tracks = mExoPlayer.getCurrentTracks();
        List<androidx.media3.common.Format> audioFormats = new ArrayList<>();
        int selectedIndex = -1;

        for (androidx.media3.common.Tracks.Group group : tracks.getGroups()) {
            if (group.getType() == C.TRACK_TYPE_AUDIO) {
                for (int i = 0; i < group.length; i++) {
                    audioFormats.add(group.getTrackFormat(i));
                    if (group.isTrackSelected(i)) {
                        selectedIndex = audioFormats.size() - 1;
                    }
                }
            }
        }

        if (audioFormats.isEmpty()) {
            Toast.makeText(this, "No audio tracks available", Toast.LENGTH_SHORT).show();
            return;
        }

        int nextIndex = (selectedIndex + 1) % audioFormats.size();
        androidx.media3.common.Format next = audioFormats.get(nextIndex);
        String lang = next.language != null ? next.language : "und";
        String label = next.label != null && !next.label.isEmpty() ? next.label : lang;

        mTrackSelector.setParameters(
                mTrackSelector.buildUponParameters()
                        .setPreferredAudioLanguage(lang)
        );
        Toast.makeText(this, "Audio: " + label.toUpperCase(Locale.US), Toast.LENGTH_SHORT).show();
        resetOsdTimer();
    }

    private String cleanTitleForSearch(String title) {
        if (title == null || title.isEmpty()) return "";
        String t = title.replaceAll("[.\\[\\]()\\-_+]", " ");
        t = t.replaceAll("(?i)\\b(2160p|1080p|720p|480p|4k|uhd|bluray|brrip|bdrip|web-dl|webrip|web|hdtv|x264|x265|hevc|10bit|aac\\d?|dts|ac3|yts|yify|rarbg|psa|imax|remux)\\b.*$", "");
        return t.replaceAll("\\s+", " ").trim();
    }

    private void fetchSubtitlesOnline(String query, boolean byHash) {
        if (mSubResultsContainer != null) {
            mSubResultsContainer.removeAllViews();
            TextView loading = new TextView(this);
            loading.setText("🔍 Searching OpenSubtitles & VLSub (" + (byHash ? "Hash-based..." : "Name query...") + ")");
            loading.setTextColor(Color.LTGRAY);
            loading.setPadding(0, 16, 0, 16);
            mSubResultsContainer.addView(loading);
        }

        new Thread(() -> {
            try {
                StringBuilder urlBuilder = new StringBuilder(mServerUrl + "/api/subtitles/search?");
                urlBuilder.append("lang=").append(URLEncoder.encode(mSelectedSubLang, "UTF-8"));
                if (mImdbId != null && !mImdbId.isEmpty()) {
                    urlBuilder.append("&id=").append(URLEncoder.encode(mImdbId, "UTF-8"));
                }
                if (mInfoHash != null && !mInfoHash.isEmpty()) {
                    urlBuilder.append("&hash=").append(URLEncoder.encode(mInfoHash, "UTF-8"));
                }
                if (query != null && !query.isEmpty()) {
                    urlBuilder.append("&query=").append(URLEncoder.encode(query, "UTF-8"));
                } else if (mTitle != null) {
                    urlBuilder.append("&title=").append(URLEncoder.encode(mTitle, "UTF-8"));
                }
                if (byHash) {
                    urlBuilder.append("&by_hash=1");
                }

                URL url = new URL(urlBuilder.toString());
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                conn.setRequestMethod("GET");

                InputStream is = conn.getInputStream();
                InputStreamReader isr = new InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8);
                char[] buffer = new char[8192];
                StringBuilder sb = new StringBuilder();
                int n;
                while ((n = isr.read(buffer, 0, buffer.length)) != -1) {
                    sb.append(buffer, 0, n);
                }
                isr.close();

                final String respJson = sb.toString();
                runOnUiThread(() -> parseAndPopulateSubtitles(respJson));
            } catch (Exception e) {
                Log.e(TAG, "Subtitle fetch error: " + e.getMessage());
                runOnUiThread(() -> {
                    if (mSubResultsContainer != null) {
                        mSubResultsContainer.removeAllViews();
                        TextView err = new TextView(this);
                        err.setText("Could not fetch online subtitles: " + e.getMessage());
                        err.setTextColor(Color.parseColor("#EF5350"));
                        mSubResultsContainer.addView(err);
                    }
                });
            }
        }).start();
    }

    private void parseAndPopulateSubtitles(String jsonStr) {
        if (jsonStr == null || jsonStr.trim().isEmpty()) return;
        try {
            JSONArray subs = null;
            String trimmed = jsonStr.trim();
            if (trimmed.startsWith("[")) {
                subs = new JSONArray(trimmed);
            } else {
                JSONObject obj = new JSONObject(trimmed);
                subs = obj.optJSONArray("subtitles");
            }
            if (subs == null) subs = new JSONArray();

            if (mSubResultsContainer != null) {
                mSubResultsContainer.removeAllViews();
                if (subs.length() == 0) {
                    TextView none = new TextView(this);
                    none.setText("No subtitles found for this search. Try searching with a different movie name or language.");
                    none.setTextColor(Color.GRAY);
                    none.setPadding(0, 16, 0, 16);
                    mSubResultsContainer.addView(none);
                    return;
                }

                for (int i = 0; i < subs.length(); i++) {
                    JSONObject item = subs.getJSONObject(i);
                    String label = item.optString("label", "");
                    String releaseTitle = item.optString("release_title", item.optString("details", item.optString("subtitleFileName", "")));
                    String lang = item.optString("lang_display", item.optString("lang", "en").toUpperCase());
                    String subUrl = item.optString("url", "");
                    boolean isHash = item.optBoolean("is_hash_match", false);
                    boolean isEmb = item.optBoolean("is_embedded", false);

                    StringBuilder btnText = new StringBuilder();
                    if (isHash) btnText.append("★ [100% SYNC] ");
                    if (isEmb) btnText.append("● [EMBEDDED] ");

                    if (!label.isEmpty()) {
                        btnText.append(label);
                    } else {
                        btnText.append(lang).append(" Subtitle");
                    }
                    if (!releaseTitle.isEmpty() && !releaseTitle.equalsIgnoreCase(label)) {
                        btnText.append("\n").append(releaseTitle);
                    }

                    final String finalLabel = !label.isEmpty() ? label : (lang + " Subtitle");
                    Button btnSub = createStyledButton(btnText.toString(), isHash || isEmb);
                    btnSub.setAllCaps(false);
                    btnSub.setLineSpacing(4f, 1f);
                    btnSub.setOnClickListener(v -> loadSubtitleTrack(subUrl, finalLabel));
                    mSubResultsContainer.addView(btnSub);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Subtitle JSON parse error: " + e.getMessage());
        }
    }

    private void renderActiveTracksList() {
        if (mSubActiveContainer == null) return;
        mSubActiveContainer.removeAllViews();

        Button btnOff = createStyledButton("● Off (Subtitles Disabled)", "Off".equals(mActiveSubLabel));
        btnOff.setOnClickListener(v -> {
            mSubtitlesEnabled = false;
            mActiveSubLabel = "Off";
            mLoadedCues.clear();
            if (mSubtitleView != null) mSubtitleView.setCues(Collections.emptyList());
            Toast.makeText(this, "Subtitles disabled", Toast.LENGTH_SHORT).show();
            renderActiveTracksList();
        });
        mSubActiveContainer.addView(btnOff);

        if (!"Off".equals(mActiveSubLabel)) {
            Button btnCurrent = createStyledButton("✓ " + mActiveSubLabel + " (Active)", true);
            mSubActiveContainer.addView(btnCurrent);
        }
    }

    private void loadSubtitleTrack(String subUrl, String label) {
        if (subUrl == null || subUrl.isEmpty()) return;
        String fullUrl = subUrl.startsWith("http") ? subUrl : (mServerUrl + subUrl);

        Toast.makeText(this, "Loading: " + label, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                URL u = new URL(fullUrl);
                HttpURLConnection conn = (HttpURLConnection) u.openConnection();
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));

                List<SubCue> parsed = parseVttOrSrt(reader);
                reader.close();

                runOnUiThread(() -> {
                    mLoadedCues.clear();
                    mLoadedCues.addAll(parsed);
                    mSubtitlesEnabled = true;
                    mActiveSubLabel = label;
                    renderActiveTracksList();
                    Toast.makeText(this, "✓ Subtitle active (" + parsed.size() + " lines)", Toast.LENGTH_SHORT).show();
                    toggleSubtitleDrawer();
                });
            } catch (Exception e) {
                Log.e(TAG, "Failed to load subtitle file: " + e.getMessage());
                runOnUiThread(() -> Toast.makeText(this, "Failed to load subtitle: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private List<SubCue> parseVttOrSrt(BufferedReader reader) {
        List<SubCue> list = new ArrayList<>();
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.contains("-->")) {
                    String[] parts = line.split("-->");
                    if (parts.length >= 2) {
                        long start = parseTimeMs(parts[0].trim());
                        long end = parseTimeMs(parts[1].trim().split(" ")[0]);

                        StringBuilder textSb = new StringBuilder();
                        while ((line = reader.readLine()) != null && !line.trim().isEmpty()) {
                            if (textSb.length() > 0) textSb.append("\n");
                            textSb.append(line.trim().replaceAll("<[^>]*>", ""));
                        }

                        if (start >= 0 && end > start) {
                            SubCue cue = new SubCue();
                            cue.startMs = start;
                            cue.endMs = end;
                            cue.text = textSb.toString();
                            list.add(cue);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "VTT/SRT parse error: " + e.getMessage());
        }
        return list;
    }

    private long parseTimeMs(String str) {
        try {
            str = str.replace(',', '.');
            String[] parts = str.split(":");
            if (parts.length == 3) {
                long hr = Long.parseLong(parts[0]);
                long min = Long.parseLong(parts[1]);
                String[] secParts = parts[2].split("\\.");
                long sec = Long.parseLong(secParts[0]);
                long ms = secParts.length > 1 ? Long.parseLong(secParts[1]) : 0;
                return (hr * 3600 + min * 60 + sec) * 1000 + ms;
            } else if (parts.length == 2) {
                long min = Long.parseLong(parts[0]);
                String[] secParts = parts[1].split("\\.");
                long sec = Long.parseLong(secParts[0]);
                long ms = secParts.length > 1 ? Long.parseLong(secParts[1]) : 0;
                return (min * 60 + sec) * 1000 + ms;
            }
        } catch (Exception ignored) {}
        return -1;
    }

    private void renderActiveSubtitles() {
        if (!mSubtitlesEnabled || mLoadedCues.isEmpty() || mSubtitleView == null || mExoPlayer == null) {
            return;
        }

        long currentPos = mExoPlayer.getCurrentPosition();
        long effectivePos = currentPos + mSubtitleSyncOffsetMs;

        List<Cue> activeCues = new ArrayList<>();
        for (SubCue sc : mLoadedCues) {
            if (effectivePos >= sc.startMs && effectivePos <= sc.endMs) {
                Cue cue = new Cue.Builder().setText(sc.text).build();
                activeCues.add(cue);
            }
        }
        mSubtitleView.setCues(activeCues);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int keyCode = event.getKeyCode();
            boolean isDrawerVisible = mSubtitleDrawerLayout != null && mSubtitleDrawerLayout.getVisibility() == View.VISIBLE;
            boolean isOsdVisible = mOsdLayout != null && mOsdLayout.getVisibility() == View.VISIBLE && !isDrawerVisible;

            // 1. Back button handling
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (isDrawerVisible) {
                    toggleSubtitleDrawer();
                    return true;
                }
                if (isOsdVisible) {
                    hideOsd();
                    return true;
                }
                finishWithResult();
                return true;
            }

            // 2. Dedicated Hardware TV Remote Media Keys (always executed directly)
            if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                togglePlayPause();
                showOsd();
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY) {
                if (mExoPlayer != null) mExoPlayer.play();
                showOsd();
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) {
                if (mExoPlayer != null) mExoPlayer.pause();
                showOsd();
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_MEDIA_REWIND) {
                seekRelative(-10000);
                showOsd();
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
                seekRelative(10000);
                showOsd();
                return true;
            }

            // 3. If Subtitle Drawer is open, let standard Android focus system navigate inside it
            if (isDrawerVisible) {
                return super.dispatchKeyEvent(event);
            }

            // 4. If OSD is currently hidden, ANY D-pad press wakes up the OSD and focuses Play/Pause
            if (!isOsdVisible) {
                showOsd();
                if (mBtnPlayPause != null) {
                    mBtnPlayPause.requestFocus();
                }
                return true;
            }

            // OSD is visible: reset inactivity timer on user interaction
            resetOsdTimer();

            // 5. When SeekBar has focus:
            if (mSeekBar != null && mSeekBar.hasFocus()) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    seekRelative(-10000);
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    seekRelative(10000);
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (mBtnPlayPause != null) {
                        mBtnPlayPause.requestFocus();
                    }
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    if (mBtnBack != null) {
                        mBtnBack.requestFocus();
                    }
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                    togglePlayPause();
                    return true;
                }
            }

            // 6. When ANY Button has focus:
            // DPAD_LEFT and DPAD_RIGHT navigate between buttons along the explicit focus chain!
            // When user presses DPAD_CENTER / ENTER on the focused button, click it!
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                View cur = getCurrentFocus();
                if (cur != null) {
                    cur.performClick();
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void finishWithResult() {
        Intent data = new Intent();
        if (mExoPlayer != null) {
            long posMs = mExoPlayer.getCurrentPosition();
            long durMs = mExoPlayer.getDuration();
            data.putExtra(RESULT_POSITION_MS, posMs);
            data.putExtra(RESULT_DURATION_MS, durMs);
            if (mImdbId != null && !mImdbId.isEmpty() && durMs > 0) {
                try {
                    com.streambravia.tv.engine.WatchHistoryRepository.getInstance(this).updateProgress(
                            mImdbId, mTitle, mMediaType, "", "", "", 0.0, posMs / 1000, durMs / 1000
                    );
                } catch (Throwable ignored) {}
            }
            mExoPlayer.stop();
            mExoPlayer.release();
            mExoPlayer = null;
        }
        setResult(RESULT_OK, data);
        finish();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (mExoPlayer != null) {
            mExoPlayer.setVideoSurfaceView(mSurfaceView);
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {}

    @Override
    protected void onDestroy() {
        mUiHandler.removeCallbacks(mPendingSeekRunnable);
        mUiHandler.removeCallbacks(mUpdateProgressRunnable);
        mUiHandler.removeCallbacks(mBufferingStatsRunnable);
        mUiHandler.removeCallbacks(mHideOsdRunnable);
        if (mExoPlayer != null) {
            mExoPlayer.release();
            mExoPlayer = null;
        }
        try {
            com.streambravia.tv.engine.TorrentEngine.getInstance(this).stopCurrentStream();
        } catch (Throwable ignored) {}
        super.onDestroy();
    }
}
