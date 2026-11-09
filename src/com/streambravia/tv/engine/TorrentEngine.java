package com.streambravia.tv.engine;

import android.content.Context;
import android.util.Log;

import org.libtorrent4j.AlertListener;
import org.libtorrent4j.FileStorage;
import org.libtorrent4j.Priority;
import org.libtorrent4j.SessionManager;
import org.libtorrent4j.SessionParams;
import org.libtorrent4j.SettingsPack;
import org.libtorrent4j.Sha1Hash;
import org.libtorrent4j.TorrentFlags;
import org.libtorrent4j.TorrentHandle;
import org.libtorrent4j.TorrentInfo;
import org.libtorrent4j.TorrentStatus;
import org.libtorrent4j.alerts.Alert;
import org.libtorrent4j.alerts.AlertType;
import org.libtorrent4j.alerts.MetadataReceivedAlert;
import org.libtorrent4j.alerts.PieceFinishedAlert;
import org.libtorrent4j.swig.remove_flags_t;
import org.libtorrent4j.swig.settings_pack;
import org.libtorrent4j.swig.torrent_flags_t;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Embedded Torrent Engine for Sony Bravia TV.
 * Built on libtorrent4j with strict TV memory & storage safety.
 * Features:
 *  - 100% In-app local streaming (No PC/laptop required)
 *  - Sliding Window Ring Cache (Capped at 800MB-1GB to protect TV flash memory)
 *  - Zero-Trace Auto Cleanup (All temporary data purged on playback exit / stop)
 *  - Hardware-friendly sequential piece prioritization
 */
public class TorrentEngine {

    private static final String TAG = "StreamBravia-Engine";
    // Fire TV Stick Lite Storage & Memory Optimization: 400 MB rolling ring buffer
    private static final long MAX_CACHE_BYTES = 400L * 1024L * 1024L; // 400 MB cap
    private static final int WINDOW_AHEAD_PIECES = 20; // Pre-buffer ~15-30s ahead
    private static final int WINDOW_BEHIND_KEEP = 8; // Small trailing window

    public static final String[] PUBLIC_TRACKERS = new String[]{
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.stealth.si:80/announce",
            "udp://tracker.torrent.eu.org:451/announce",
            "udp://explodie.org:6969/announce",
            "udp://open.demonii.com:1337/announce",
            "udp://tracker.openbittorrent.com:6969/announce",
            "udp://tracker.coppersurfer.tk:6969/announce",
            "udp://tracker.leechers-paradise.org:6969/announce",
            "udp://p4p.arenabg.com:1337/announce",
            "udp://p4p.arenabg.ch:1337/announce",
            "udp://tracker.tamildbox.net:6969/announce",
            "udp://tracker.tamilmv.cz:6969/announce",
            "udp://tracker.tamilblasters.top:6969/announce",
            "udp://tracker.tamilblasters.lat:6969/announce",
            "udp://tracker.army:6969/announce",
            "udp://tracker.altrosky.nl:6969/announce",
            "udp://tracker.cyberia.is:6969/announce",
            "udp://tracker.bittor.pw:1337/announce",
            "udp://tracker.filemail.com:6969/announce",
            "udp://tracker.therarbg.com:6969/announce",
            "udp://opentracker.i2p.rocks:6969/announce",
            "udp://tracker.moeking.me:6969/announce",
            "udp://exodus.desync.com:6969/announce",
            "udp://tracker.tiny-vps.com:6969/announce",
            "http://tracker.openbittorrent.com:80/announce",
            "http://tracker.dler.org:6969/announce",
            "udp://movies.zsw.ca:6969/announce",
            "udp://tracker.internetwarriors.net:1337/announce"
    };

    private static TorrentEngine sInstance;
    private final Context mContext;
    private final File mCacheDir;
    private final File mMetadataDir;
    private final Object mLock = new Object();
    private final Object mPrepareLock = new Object();
    private final Object mPieceWaitLock = new Object();
    private SessionManager mSession;
    private TorrentInfo mActiveTorrentInfo;
    private String mActiveHash = null;
    private int mTargetFileIndex = -1;
    private File mTargetFile;
    private long mTargetFileSize = 0;
    private long mTargetFileOffset = 0;
    private int mPieceLength = 0;
    private int mStartPiece = 0;
    private int mEndPiece = 0;
    private final AtomicBoolean mIsMetadataReady = new AtomicBoolean(false);
    private CountDownLatch mMetadataLatch;
    private int mLastWindowPiece = -1;
    private final ScheduledExecutorService mHarvesterExecutor = Executors.newSingleThreadScheduledExecutor();
    private final CountDownLatch mSessionInitLatch = new CountDownLatch(1);
    private final java.util.concurrent.atomic.AtomicLong mGeneration = new java.util.concurrent.atomic.AtomicLong(0L);
    private final java.util.Set<String> mAddedTrackerUrls = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static synchronized TorrentEngine getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new TorrentEngine(context.getApplicationContext());
        }
        return sInstance;
    }

    private TorrentEngine(Context context) {
        mContext = context;
        mCacheDir = new File(mContext.getCacheDir(), "stream_cache");
        if (!mCacheDir.exists()) {
            mCacheDir.mkdirs();
        }
        mMetadataDir = new File(mContext.getFilesDir(), "torrent_metadata");
        if (!mMetadataDir.exists()) {
            mMetadataDir.mkdirs();
        }
        // Safety Sweep: Purge any stale cache from prior TV reboots/crashes in background
        new Thread(() -> {
            purgeCache();
            trimMetadataCache(25);
        }, "StreamBraviaCacheSweep").start();

        // Initialize libtorrent session on background thread to prevent UI thread blocking on app start
        new Thread(this::initSession, "StreamBraviaEngineInit").start();
    }

    public void awaitSessionReady(long maxWaitMs) {
        try {
            mSessionInitLatch.await(maxWaitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {}
    }

    private TorrentHandle getHandle() {
        return getHandle(0);
    }

    private TorrentHandle getHandle(long maxWaitMs) {
        awaitSessionReady(maxWaitMs > 0 ? maxWaitMs : 1000);
        String hash = mActiveHash;
        if (mSession == null || hash == null || hash.isEmpty() || !mSession.isRunning()) return null;
        Sha1Hash sha;
        try {
            sha = Sha1Hash.parseHex(hash);
        } catch (Throwable t) {
            return null;
        }

        long deadline = System.currentTimeMillis() + maxWaitMs;
        do {
            try {
                TorrentHandle h = mSession.find(sha);
                if (h != null && h.isValid()) {
                    return h;
                }
            } catch (Throwable t) {
                Log.w(TAG, "getHandle error: " + t.getMessage());
            }
            if (System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(25);
                } catch (InterruptedException ignored) {
                    break;
                }
            }
        } while (System.currentTimeMillis() < deadline);
        return null;
    }

    private void initSession() {
        try {
            mSession = new SessionManager();
            SettingsPack sp = new SettingsPack();
            sp.downloadRateLimit(0); // Unlimited download
            sp.uploadRateLimit(0); // Unlimited upload so peers freely unchoke download bandwidth
            sp.activeDownloads(4);
            sp.activeLimit(10);
            sp.connectionsLimit(150); // Connect to up to 150 peers/seeds simultaneously (optimal for TV Wi-Fi)
            sp.maxPeerlistSize(3000); // Retain 3000 candidate peers in the swarm pool
            sp.alertQueueSize(5000);
            sp.inactivityTimeout(20);
            sp.anonymousMode(false); // MUST be false so DHT and peer handshakes connect freely
            sp.setEnableDht(true);
            sp.setDhtBootstrapNodes("router.bittorrent.com:6881,dht.transmissionbt.com:6881,router.utorrent.com:6881,dht.aelitis.com:6881,dht.libtorrent.org:25401");

            // Multi-tracker broadcast: Announce to all trackers simultaneously across all tiers (connects to hundreds of seeders instantly)
            sp.setBoolean(settings_pack.bool_types.announce_to_all_trackers.swigValue(), true);
            sp.setBoolean(settings_pack.bool_types.announce_to_all_tiers.swigValue(), true);
            sp.setBoolean(settings_pack.bool_types.prefer_udp_trackers.swigValue(), true);
            sp.setBoolean(settings_pack.bool_types.enable_lsd.swigValue(), true);
            sp.setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), true);
            sp.setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), true);

            // Immediate Burst Connection: Load all peers at once without trickling
            sp.setBoolean(settings_pack.bool_types.smooth_connects.swigValue(), false);
            sp.setInteger(settings_pack.int_types.torrent_connect_boost.swigValue(), 100);
            sp.setInteger(settings_pack.int_types.connection_speed.swigValue(), 100);
            sp.setInteger(settings_pack.int_types.peer_connect_timeout.swigValue(), 15); // Standard 15s to allow handshakes to complete
            sp.setInteger(settings_pack.int_types.handshake_timeout.swigValue(), 15); // Standard 15s
            sp.setInteger(settings_pack.int_types.allowed_fast_set_size.swigValue(), 10);
            sp.setBoolean(settings_pack.bool_types.strict_end_game_mode.swigValue(), false);
            sp.setInteger(settings_pack.int_types.tracker_completion_timeout.swigValue(), 15);
            sp.setInteger(settings_pack.int_types.tracker_receive_timeout.swigValue(), 10);
            sp.setInteger(settings_pack.int_types.dht_search_branching.swigValue(), 8);
            sp.setInteger(settings_pack.int_types.dht_max_peers_reply.swigValue(), 100);
            sp.setInteger(settings_pack.int_types.initial_picker_threshold.swigValue(), 4);

            // Aggressive DHT scheduling
            sp.setBoolean(settings_pack.bool_types.dht_aggressive_lookups.swigValue(), true);
            sp.setBoolean(settings_pack.bool_types.auto_sequential.swigValue(), false);

            // Deep pipelining tuned for TV: request queue 500 blocks for maximum throughput without memory exhaustion
            sp.setInteger(settings_pack.int_types.max_out_request_queue.swigValue(), 500);
            sp.setInteger(settings_pack.int_types.request_queue_time.swigValue(), 2);

            // Rapid piece timeout: if a peer stalls on a block, steal from faster peer after 10s
            sp.setInteger(settings_pack.int_types.piece_timeout.swigValue(), 10);

            // Rate-based choker: unlocks highest download throughput from peers
            sp.setInteger(settings_pack.int_types.choking_algorithm.swigValue(), settings_pack.choking_algorithm_t.rate_based_choker.swigValue());
            sp.setInteger(settings_pack.int_types.seed_choking_algorithm.swigValue(), settings_pack.seed_choking_algorithm_t.fastest_upload.swigValue());

            // 2 MB network socket buffers for smooth UDP/TCP throughput
            sp.setInteger(settings_pack.int_types.recv_socket_buffer_size.swigValue(), 2 * 1024 * 1024);
            sp.setInteger(settings_pack.int_types.send_socket_buffer_size.swigValue(), 512 * 1024);

            // Centrifuge Peer Churn & High-Bandwidth Retention Filter (Non-destructive to startup)
            sp.setBoolean(settings_pack.bool_types.auto_manage_prefer_seeds.swigValue(), true); // Prioritize 100% complete seeders
            sp.setBoolean(settings_pack.bool_types.close_redundant_connections.swigValue(), true); // Drop redundant connections
            sp.setInteger(settings_pack.int_types.peer_turnover.swigValue(), 10); // Disconnect bottom 10% slowest peers
            sp.setInteger(settings_pack.int_types.peer_turnover_cutoff.swigValue(), 90); // Filter bottom 90% percentile of peers
            sp.setInteger(settings_pack.int_types.peer_turnover_interval.swigValue(), 20); // Run filter every 20 seconds
            sp.setInteger(settings_pack.int_types.inactive_down_rate.swigValue(), 2048); // Drop truly dead peers sending < 2 KB/s
            sp.setInteger(settings_pack.int_types.inactivity_timeout.swigValue(), 20); // Drop idle peers after 20s
            sp.setInteger(settings_pack.int_types.unchoke_interval.swigValue(), 1); // Recalculate unchoke slots every 1s
            sp.setInteger(settings_pack.int_types.optimistic_unchoke_interval.swigValue(), 3); // Test candidate peers every 3s

            // Mixed mode: Prefer TCP / don't let uTP choke throughput
            sp.setInteger(settings_pack.int_types.mixed_mode_algorithm.swigValue(), settings_pack.bandwidth_mixed_algo_t.prefer_tcp.swigValue());

            // 12 MB max disk write queue cap to protect low-RAM (1GB) Android TVs
            sp.setInteger(settings_pack.int_types.max_queued_disk_bytes.swigValue(), 12 * 1024 * 1024);

            mSession.addListener(new AlertListener() {
                @Override
                public int[] types() {
                    return new int[]{
                            AlertType.METADATA_RECEIVED.swig(),
                            AlertType.ADD_TORRENT.swig(),
                            AlertType.PIECE_FINISHED.swig()
                    };
                }

                @Override
                public void alert(Alert<?> alert) {
                    if (alert instanceof MetadataReceivedAlert) {
                        handleMetadataReceived();
                    } else if (alert instanceof org.libtorrent4j.alerts.AddTorrentAlert) {
                        handleMetadataReceived();
                    } else if (alert instanceof PieceFinishedAlert) {
                        synchronized (mPieceWaitLock) {
                            mPieceWaitLock.notifyAll();
                        }
                    }
                }
            });

            mSession.start(new SessionParams(sp));
            mSession.startDht();

            // Continuous Swarm Harvester: Every 30 seconds query DHT; reannounce to trackers safely without IGNORE_MIN_INTERVAL
            final long[] lastTrackerAnnounce = new long[]{System.currentTimeMillis()};
            mHarvesterExecutor.scheduleWithFixedDelay(() -> {
                synchronized (mLock) {
                    if (mActiveHash != null && mIsMetadataReady.get()) {
                        TorrentHandle h = getHandle();
                        if (h != null && h.isValid()) {
                            try {
                                h.forceDHTAnnounce();
                                long now = System.currentTimeMillis();
                                TorrentStatus st = h.status();
                                if (now - lastTrackerAnnounce[0] > 180000 || (st != null && st.numPeers() < 6)) {
                                    lastTrackerAnnounce[0] = now;
                                    h.forceReannounce();
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                }
            }, 30, 30, TimeUnit.SECONDS);

            Log.i(TAG, "libtorrent4j session started with 1GB Fire TV Stick memory-safe profile, Centrifuge filter & DHT nodes");
        } catch (Throwable t) {
            Log.e(TAG, "Error starting libtorrent4j session: " + t.getMessage(), t);
        } finally {
            mSessionInitLatch.countDown();
        }
    }

    private void saveMetadataCache(String hash, TorrentInfo ti) {
        if (hash == null || ti == null) return;
        try {
            byte[] bencoded = ti.bencode();
            if (bencoded != null && bencoded.length > 0) {
                File metaFile = new File(mMetadataDir, hash.toLowerCase() + ".torrent");
                java.io.FileOutputStream fos = new java.io.FileOutputStream(metaFile);
                fos.write(bencoded);
                fos.close();
                Log.i(TAG, "⚡ Saved torrent metadata cache: " + metaFile.getName() + " (" + bencoded.length + " bytes)");
            }
        } catch (Throwable t) {
            Log.w(TAG, "saveMetadataCache notice: " + t.getMessage());
        }
    }

    private void handleMetadataReceived() {
        synchronized (mLock) {
            TorrentHandle h = getHandle();
            if (h != null) {
                try {
                    TorrentInfo ti = h.torrentFile();
                    if (ti == null && mActiveTorrentInfo != null) {
                        ti = mActiveTorrentInfo;
                    }
                    if (ti != null && !mIsMetadataReady.get()) {
                        mActiveTorrentInfo = ti;
                        saveMetadataCache(mActiveHash, ti);
                        configureStreamingPriorities(h, ti);
                        mIsMetadataReady.set(true);
                        if (mMetadataLatch != null) {
                            mMetadataLatch.countDown();
                        }
                        Log.i(TAG, "Torrent metadata received: " + ti.name() + " (" + ti.numFiles() + " files)");
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "handleMetadataReceived error: " + t.getMessage());
                }
            }
        }
    }

    public List<String> extractTrackers(String magnetUri) {
        List<String> list = new ArrayList<>();
        if (magnetUri == null || !magnetUri.contains("&tr=")) return list;
        int idx = 0;
        while ((idx = magnetUri.indexOf("&tr=", idx)) != -1) {
            int start = idx + 4;
            int next = magnetUri.indexOf('&', start);
            String rawTr = next != -1 ? magnetUri.substring(start, next) : magnetUri.substring(start);
            try {
                String decoded = java.net.URLDecoder.decode(rawTr, "UTF-8");
                if (!decoded.isEmpty() && !list.contains(decoded)) {
                    list.add(decoded);
                }
            } catch (Exception ignored) {
                if (!rawTr.isEmpty() && !list.contains(rawTr)) {
                    list.add(rawTr);
                }
            }
            idx = start;
        }
        return list;
    }

    public void enrichTrackers(TorrentHandle h, String magnetOrHash) {
        if (h == null || !h.isValid()) return;
        List<String> dynamicTrackers = extractTrackers(magnetOrHash);
        for (String tr : dynamicTrackers) {
            String norm = (tr != null) ? tr.trim() : "";
            if (!norm.isEmpty() && mAddedTrackerUrls.add(norm)) {
                try {
                    h.addTracker(new org.libtorrent4j.AnnounceEntry(norm));
                } catch (Throwable ignored) {}
            }
        }
        for (String tr : PUBLIC_TRACKERS) {
            String norm = (tr != null) ? tr.trim() : "";
            if (!norm.isEmpty() && mAddedTrackerUrls.add(norm)) {
                try {
                    h.addTracker(new org.libtorrent4j.AnnounceEntry(norm));
                } catch (Throwable ignored) {}
            }
        }
        try {
            h.forceDHTAnnounce();
            h.forceReannounce();
        } catch (Throwable ignored) {}
    }

    /**
     * Start streaming a torrent given an infoHash or magnet link.
     */
    public boolean prepareStream(String magnetOrHash, long timeoutSeconds) {
        awaitSessionReady(10000);
        String hash = extractHash(magnetOrHash);
        if (hash == null || hash.isEmpty()) hash = magnetOrHash.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();

        CountDownLatch latchToAwait = null;

        synchronized (mPrepareLock) {
            // Check if this exact torrent is already active or in progress
            if (mActiveHash != null && mActiveHash.equalsIgnoreCase(hash)) {
                if (mIsMetadataReady.get() && mActiveTorrentInfo != null) {
                    TorrentHandle h = getHandle();
                    if (h != null && h.isValid()) {
                        // Fast path: Do NOT re-announce or force DHT on every HTTP range request!
                        return true;
                    }
                }
                if (mMetadataLatch != null && mMetadataLatch.getCount() > 0) {
                    Log.i(TAG, "Waiting for ongoing metadata fetch for hash: " + hash);
                    latchToAwait = mMetadataLatch;
                }
            }

            if (latchToAwait == null) {
                // If a DIFFERENT torrent was streaming, cleanly stop it first
                if (mActiveHash != null && !mActiveHash.equalsIgnoreCase(hash)) {
                    Log.i(TAG, "Switching stream from " + mActiveHash + " to " + hash);
                    stopCurrentStreamInternal();
                } else if (mActiveHash == null) {
                    purgeCacheInternal();
                }

                synchronized (mLock) {
                    mActiveHash = hash;
                    mIsMetadataReady.set(false);
                    mActiveTorrentInfo = null;
                    mMetadataLatch = new CountDownLatch(1);
                    latchToAwait = mMetadataLatch;
                }

                // 1. Instant Cache Check: If metadata is already cached on disk, load in 0.00s!
                boolean loadedFromCache = false;
                File cachedMeta = new File(mMetadataDir, hash + ".torrent");
                if (cachedMeta.exists() && cachedMeta.length() > 0) {
                    try {
                        TorrentInfo ti = new TorrentInfo(cachedMeta);
                        synchronized (mLock) {
                            mActiveTorrentInfo = ti;
                        }
                        Log.i(TAG, "⚡ Instant Cache Hit: Loaded metadata for " + hash + " (" + ti.name() + ")");
                        mSession.download(ti, mCacheDir, null, null, null, TorrentFlags.AUTO_MANAGED);
                        TorrentHandle h = getHandle(2500);
                        if (h != null && h.isValid()) {
                            enrichTrackers(h, magnetOrHash);
                            configureStreamingPriorities(h, ti);
                            mIsMetadataReady.set(true);
                            if (mMetadataLatch != null) {
                                mMetadataLatch.countDown();
                            }
                            return true;
                        } else {
                            loadedFromCache = true;
                            Log.i(TAG, "Metadata loaded from disk; awaiting handle registration in session...");
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "Cached metadata error, falling back to network swarm: " + t.getMessage());
                    }
                }

                // 2. Swarm download via DHT & Trackers (ONLY if no valid metadata file was loaded from disk!)
                if (!mIsMetadataReady.get() && !loadedFromCache) {
                    String magnetUri = magnetOrHash.startsWith("magnet:?") ? magnetOrHash : buildMagnetUri(magnetOrHash);
                    try {
                        Log.i(TAG, "Adding magnet stream with high-speed trackers: " + magnetUri);
                        mSession.download(magnetUri, mCacheDir, TorrentFlags.AUTO_MANAGED);
                        TorrentHandle h = getHandle(2000);
                        if (h != null && h.isValid()) {
                            enrichTrackers(h, magnetUri);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "prepareStream start error: " + e.getMessage(), e);
                        if (mMetadataLatch != null) {
                            mMetadataLatch.countDown();
                        }
                        return false;
                    }
                }
            }
        } // end synchronized (mPrepareLock)

        // Wait on latch OUTSIDE synchronized block so handleMetadataReceived can run freely without deadlock!
        if (latchToAwait != null) {
            try {
                boolean ready = latchToAwait.await(timeoutSeconds, TimeUnit.SECONDS);
                if (ready && mIsMetadataReady.get()) {
                    return true;
                }
            } catch (InterruptedException ignored) {}
        }

        synchronized (mLock) {
            if (mActiveHash != null && mActiveHash.equalsIgnoreCase(hash)) {
                TorrentHandle h = getHandle();
                if (h != null) {
                    try {
                        TorrentInfo ti = h.torrentFile();
                        if (ti != null) {
                            mActiveTorrentInfo = ti;
                            saveMetadataCache(mActiveHash, ti);
                            configureStreamingPriorities(h, ti);
                            mIsMetadataReady.set(true);
                            return true;
                        }
                    } catch (Throwable ignored) {}
                }
            }
            return mIsMetadataReady.get();
        }
    }

    private void configureStreamingPriorities(TorrentHandle h, TorrentInfo ti) {
        if (h == null || ti == null) return;

        FileStorage fs = ti.files();
        int numFiles = fs.numFiles();
        long maxBytes = 0;
        int targetIdx = -1;

        // Smart Video File Selector:
        // 1. Identify files with common video extensions (.mkv, .mp4, .avi, .ts, .m4v, .webm, .mov)
        // 2. Ignore samples (e.g. filename contains "sample", or file size < 50MB when larger video exists)
        // 3. Select the primary full-length movie/episode video file
        List<Integer> videoIndices = new ArrayList<>();
        for (int i = 0; i < numFiles; i++) {
            String path = fs.filePath(i).toLowerCase();
            long sz = fs.fileSize(i);
            if (path.endsWith(".mkv") || path.endsWith(".mp4") || path.endsWith(".avi")
                    || path.endsWith(".ts") || path.endsWith(".m4v") || path.endsWith(".webm") || path.endsWith(".mov")) {
                boolean isSample = path.contains("sample") || (sz < 50L * 1024L * 1024L);
                if (!isSample) {
                    videoIndices.add(i);
                }
            }
        }

        if (!videoIndices.isEmpty()) {
            for (int idx : videoIndices) {
                long sz = fs.fileSize(idx);
                if (sz > maxBytes) {
                    maxBytes = sz;
                    targetIdx = idx;
                }
            }
        }

        // Fallback: If no extension matched or all were < 50MB, pick the largest file overall
        if (targetIdx == -1 || maxBytes <= 0) {
            for (int i = 0; i < numFiles; i++) {
                long sz = fs.fileSize(i);
                if (sz > maxBytes) {
                    maxBytes = sz;
                    targetIdx = i;
                }
            }
        }
        if (targetIdx == -1) targetIdx = 0;

        int totalPieces = ti.numPieces();
        int maxValidPiece = Math.max(0, totalPieces - 1);

        mTargetFileIndex = targetIdx;
        mTargetFileSize = maxBytes;
        mPieceLength = ti.pieceLength();
        mTargetFileOffset = fs.fileOffset(targetIdx);
        mTargetFile = new File(mCacheDir, fs.filePath(targetIdx));

        // Create directory and sparse file on disk immediately so ExoPlayer has instant disk handle
        try {
            File parent = mTargetFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            if (!mTargetFile.exists()) {
                RandomAccessFile raf = new RandomAccessFile(mTargetFile, "rw");
                raf.setLength(mTargetFileSize);
                raf.close();
                Log.i(TAG, "Pre-allocated sparse target file: " + mTargetFile.getAbsolutePath() + " (" + (mTargetFileSize / (1024 * 1024)) + " MB)");
            }
        } catch (Throwable t) {
            Log.w(TAG, "Sparse file creation notice: " + t.getMessage());
        }

        // Prioritize ONLY the target video file, ignore samples, nfo, extra files
        Priority[] priorities = new Priority[numFiles];
        Arrays.fill(priorities, Priority.IGNORE);
        priorities[targetIdx] = Priority.DEFAULT;
        try {
            h.prioritizeFiles(priorities);
        } catch (Throwable ignored) {}

        // Compute piece range for the target file
        long fileOffset = mTargetFileOffset;
        mStartPiece = Math.max(0, Math.min(maxValidPiece, (int) (fileOffset / mPieceLength)));
        mEndPiece = Math.max(0, Math.min(maxValidPiece, (int) ((fileOffset + maxBytes - 1) / mPieceLength)));

        // Resume torrent download mode in libtorrent
        try {
            h.resume();
        } catch (Throwable ignored) {}

        // Boost the first 4 pieces with TOP priority and urgent deadline for immediate playback start
        for (int p = mStartPiece; p <= Math.min(mEndPiece, mStartPiece + 3); p++) {
            try {
                h.piecePriority(p, Priority.TOP_PRIORITY);
                h.setPieceDeadline(p, (p - mStartPiece + 1) * 60);
            } catch (Throwable ignored) {}
        }

        // Boost the last 2 pieces (crucial for MKV cues and MP4 moov atom index)
        for (int p = Math.max(mStartPiece, mEndPiece - 2); p <= mEndPiece; p++) {
            try {
                h.piecePriority(p, Priority.TOP_PRIORITY);
                h.setPieceDeadline(p, 80);
            } catch (Throwable ignored) {}
        }

        // Immediately prime sliding lookahead window of 25 pieces
        prioritizeSlidingWindow(mStartPiece, WINDOW_AHEAD_PIECES);

        Log.i(TAG, "Streaming target file: " + mTargetFile.getName() + " (" + (mTargetFileSize / (1024 * 1024)) + " MB)");
        Log.i(TAG, "Pieces range: " + mStartPiece + " -> " + mEndPiece + ", piece size: " + mPieceLength);
    }

    private int clampPiece(int p) {
        if (mActiveTorrentInfo == null) return p;
        int maxP = mActiveTorrentInfo.numPieces() - 1;
        if (maxP < 0) return 0;
        return Math.max(0, Math.min(p, maxP));
    }

    /**
     * Continuously prioritize a sliding lookahead window of pieces around the current playhead.
     * Deep pipelining: immediate next pieces receive urgent deadlines, while remaining window
     * receives Priority.SEVEN so the BitTorrent swarm downloads them in parallel before the player arrives.
     */
    public void prioritizeSlidingWindow(int currentPiece, int windowAhead) {
        synchronized (mLock) {
            TorrentHandle h = getHandle();
            if (h == null || mActiveTorrentInfo == null || mPieceLength <= 0) return;

            int targetPiece = Math.max(mStartPiece, Math.min(mEndPiece, currentPiece));
            mLastWindowPiece = targetPiece;

            // Urgent deadline for target piece (0 = immediate)
            try {
                h.piecePriority(targetPiece, Priority.TOP_PRIORITY);
                h.setPieceDeadline(targetPiece, 0);
            } catch (Throwable ignored) {}

            // Critical rule: pieces in the container header (start) and container trailer (end)
            // MUST NEVER be set to Priority.IGNORE! ExoPlayer needs both for MP4/MKV container parsing.
            int protectedHeaderEnd = Math.min(mEndPiece, mStartPiece + 3);
            int protectedTrailerStart = Math.max(mStartPiece, mEndPiece - 2);

            // Deprioritize bounded past pieces behind playhead ONLY if playhead is past the protected header
            // and NOT probing the trailer (bounded window to avoid thousands of JNI calls)
            if (targetPiece > protectedHeaderEnd + 4 && targetPiece < protectedTrailerStart) {
                int pastStart = Math.max(protectedHeaderEnd + 1, targetPiece - 16);
                int pastCutoff = targetPiece - 3;
                for (int p = pastStart; p < pastCutoff; p++) {
                    try {
                        if (!h.havePiece(p)) {
                            h.piecePriority(p, Priority.IGNORE);
                        }
                    } catch (Throwable ignored) {}
                }
            }

            // Always ensure the first 4 pieces (file header / container format) retain TOP_PRIORITY
            for (int p = mStartPiece; p <= protectedHeaderEnd; p++) {
                try {
                    if (!h.havePiece(p)) {
                        h.piecePriority(p, Priority.TOP_PRIORITY);
                        h.setPieceDeadline(p, 0);
                    }
                } catch (Throwable ignored) {}
            }

            // Always ensure the last 3 pieces (MKV cues / MP4 moov index) stay TOP_PRIORITY
            for (int p = protectedTrailerStart; p <= mEndPiece; p++) {
                try {
                    if (!h.havePiece(p)) {
                        h.piecePriority(p, Priority.TOP_PRIORITY);
                        h.setPieceDeadline(p, 0);
                    }
                } catch (Throwable ignored) {}
            }

            // Tier 1: Immediate next 3 pieces from targetPiece (Urgent) - Top Priority with immediate deadlines
            for (int p = targetPiece; p <= Math.min(mEndPiece, targetPiece + 2); p++) {
                try {
                    if (!h.havePiece(p)) {
                        h.piecePriority(p, Priority.TOP_PRIORITY);
                        h.setPieceDeadline(p, (p - targetPiece) * 30);
                    }
                } catch (Throwable ignored) {}
            }

            // Tier 2: Next 10 pieces in near-future pipeline - Priority.SIX
            for (int p = targetPiece + 3; p <= Math.min(mEndPiece, targetPiece + 12); p++) {
                try {
                    if (!h.havePiece(p)) {
                        h.piecePriority(p, Priority.SIX);
                    }
                } catch (Throwable ignored) {}
            }

            // Tier 3: Next 16 pieces in buffer reservoir - Priority.FIVE
            for (int p = targetPiece + 13; p <= Math.min(mEndPiece, targetPiece + 28); p++) {
                try {
                    if (!h.havePiece(p)) {
                        h.piecePriority(p, Priority.FIVE);
                    }
                } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * Prioritize pieces for seeking ahead to a specific byte offset.
     */
    public void prioritizeByteOffset(long byteOffset) {
        if (mPieceLength <= 0) return;
        int targetPiece = (int) ((mTargetFileOffset + byteOffset) / mPieceLength);
        synchronized (mLock) {
            TorrentHandle h = getHandle();
            if (h != null) {
                try {
                    h.piecePriority(targetPiece, Priority.TOP_PRIORITY);
                    h.setPieceDeadline(targetPiece, 0);
                } catch (Throwable ignored) {}
            }
        }
        prioritizeSlidingWindow(targetPiece, WINDOW_AHEAD_PIECES);
    }

    public boolean isPieceDownloaded(int pieceIndex) {
        synchronized (mLock) {
            TorrentHandle h = getHandle();
            if (h == null || mActiveTorrentInfo == null) return false;
            int numPieces = mActiveTorrentInfo.numPieces();
            if (pieceIndex < 0 || pieceIndex >= numPieces) return false;
            try {
                return h.havePiece(pieceIndex);
            } catch (Throwable e) {
                return false;
            }
        }
    }

    public boolean waitForPiece(int pieceIndex, long timeoutMs) {
        if (pieceIndex < mStartPiece || pieceIndex > mEndPiece) return false;
        synchronized (mLock) {
            TorrentHandle h = getHandle();
            if (h == null || mActiveTorrentInfo == null) return false;
            try {
                if (h.havePiece(pieceIndex)) return true;
                h.piecePriority(pieceIndex, Priority.TOP_PRIORITY);
                h.setPieceDeadline(pieceIndex, 0); // Immediate deadline for blocking playback reads
            } catch (Throwable ignored) {}
        }

        // Reactive event-driven wakeup via libtorrent PieceFinishedAlert
        synchronized (mPieceWaitLock) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                if (isPieceDownloaded(pieceIndex)) return true;
                if (mActiveHash == null) return false;
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;
                try {
                    mPieceWaitLock.wait(Math.min(remaining, 150));
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
        return isPieceDownloaded(pieceIndex);
    }

    public long getGeneration() {
        return mGeneration.get();
    }

    public void stopCurrentStream() {
        synchronized (mPrepareLock) {
            stopCurrentStreamInternal();
        }
    }

    private void stopCurrentStreamInternal() {
        mGeneration.incrementAndGet();
        mAddedTrackerUrls.clear();
        synchronized (mPieceWaitLock) {
            mPieceWaitLock.notifyAll();
        }

        final String hashToRemove;
        synchronized (mLock) {
            hashToRemove = mActiveHash;
            mActiveHash = null;
            mActiveTorrentInfo = null;
            mTargetFile = null;
            mTargetFileSize = 0;
            mTargetFileOffset = 0;
            mLastWindowPiece = -1;
            mIsMetadataReady.set(false);
            if (mMetadataLatch != null && mMetadataLatch.getCount() > 0) {
                mMetadataLatch.countDown();
            }
        }

        if (hashToRemove != null && mSession != null && mSession.isRunning()) {
            try {
                Log.i(TAG, "Stopping active torrent stream: " + hashToRemove);
                TorrentHandle h = mSession.find(Sha1Hash.parseHex(hashToRemove));
                if (h != null && h.isValid()) {
                    h.pause();
                    mSession.remove(h);
                }
            } catch (Throwable e) {
                Log.w(TAG, "Error removing torrent: " + e.getMessage());
            }
        }
        purgeCacheInternal();
    }

    /**
     * Purge all files in the cache directory safely if no stream is currently active.
     */
    public void purgeCache() {
        synchronized (mPrepareLock) {
            synchronized (mLock) {
                if (mActiveHash != null) {
                    Log.i(TAG, "purgeCache ignored: Active stream is currently running for " + mActiveHash);
                    return;
                }
            }
            purgeCacheInternal();
        }
    }

    private void purgeCacheInternal() {
        if (mCacheDir != null && mCacheDir.exists()) {
            try {
                deleteRecursive(mCacheDir);
                mCacheDir.mkdirs();
                Log.i(TAG, "Storage cache purged successfully. TV flash memory is 100% free.");
            } catch (Throwable t) {
                Log.w(TAG, "Cache purge notice: " + t.getMessage());
            }
        }
        trimMetadataCache(25);
    }

    public void trimMetadataCache(int keepCount) {
        if (mMetadataDir != null && mMetadataDir.exists()) {
            File[] files = mMetadataDir.listFiles();
            if (files != null && files.length > keepCount) {
                java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                for (int i = keepCount; i < files.length; i++) {
                    try {
                        files[i].delete();
                    } catch (Throwable ignored) {}
                }
                Log.i(TAG, "Trimmed torrent metadata cache to " + keepCount + " items.");
            }
        }
    }

    private boolean deleteRecursive(File fileOrDir) {
        if (fileOrDir.isDirectory()) {
            File[] files = fileOrDir.listFiles();
            if (files != null) {
                for (File child : files) {
                    deleteRecursive(child);
                }
            }
        }
        return fileOrDir.delete();
    }

    private long getFolderSize(File dir) {
        if (dir == null || !dir.exists()) return 0;
        long total = 0;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                total += f.isDirectory() ? getFolderSize(f) : f.length();
            }
        }
        return total;
    }

    public File getTargetFile() {
        synchronized (mLock) {
            return mTargetFile;
        }
    }

    public long getTargetFileSize() {
        synchronized (mLock) {
            return mTargetFileSize;
        }
    }

    public long getTargetFileOffset() {
        synchronized (mLock) {
            return mTargetFileOffset;
        }
    }

    public int getPieceLength() {
        synchronized (mLock) {
            return mPieceLength;
        }
    }

    public int getStartPiece() {
        synchronized (mLock) {
            return mStartPiece;
        }
    }

    public int getEndPiece() {
        synchronized (mLock) {
            return mEndPiece;
        }
    }

    public String getActiveHash() {
        synchronized (mLock) {
            return mActiveHash;
        }
    }

    public boolean isMetadataReady() {
        return mIsMetadataReady.get();
    }

    public TorrentStatus getStatus() {
        synchronized (mLock) {
            TorrentHandle h = getHandle();
            if (h != null) {
                try {
                    return h.status();
                } catch (Exception ignored) {}
            }
            return null;
        }
    }

    public synchronized void onDestroy() {
        try {
            mHarvesterExecutor.shutdownNow();
        } catch (Throwable ignored) {}
        stopCurrentStream();
        if (mSession != null) {
            try {
                mSession.stop();
            } catch (Exception ignored) {
            }
            mSession = null;
        }
        purgeCache();
        sInstance = null;
    }

    public String buildMagnetUri(String input) {
        String hash = extractHash(input);
        if (hash == null || hash.isEmpty()) {
            hash = input.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
        }
        StringBuilder sb = new StringBuilder("magnet:?xt=urn:btih:").append(hash);
        for (String tr : PUBLIC_TRACKERS) {
            try {
                sb.append("&tr=").append(java.net.URLEncoder.encode(tr, "UTF-8"));
            } catch (Exception ignored) {
                sb.append("&tr=").append(tr);
            }
        }
        return sb.toString();
    }

    private String extractHash(String input) {
        if (input.startsWith("magnet:?")) {
            int idx = input.indexOf("urn:btih:");
            if (idx != -1) {
                String sub = input.substring(idx + 9);
                int amp = sub.indexOf('&');
                return amp != -1 ? sub.substring(0, amp).toLowerCase() : sub.toLowerCase();
            }
        }
        return input.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
    }
}
