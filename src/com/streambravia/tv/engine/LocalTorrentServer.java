package com.streambravia.tv.engine;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.libtorrent4j.TorrentStatus;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.streambravia.tv.update.UpdateManager;
import fi.iki.elonen.NanoHTTPD;

/**
 * Embedded HTTP Loopback Server for Sony Bravia TV.
 * Listens on 127.0.0.1:7878.
 * Serves:
 *   - Offline Web UI assets from APK assets/web/
 *   - REST APIs (Cinemeta catalog, Torrentio streams, MediaFusion TamilBlasters, Subtitles)
 *   - HTTP 206 Partial Content byte-range video streaming to ExoPlayer
 *   - Live stream telemetry & diagnostics
 */
public class LocalTorrentServer extends NanoHTTPD {

    private static final String TAG = "StreamBravia-Server";
    public static final int PORT = 7878;

    private static final String CINEMETA_BASE = "https://v3-cinemeta.strem.io";
    private static final String TORRENTIO_BASE = "https://torrentio.strem.fun";
    private static final String MEDIAFUSION_BASE = "https://mediafusion.elfhosted.com";
    private static final String STREMIO_SUBS_BASE = "https://opensubtitles-v3.strem.io";

    private final Context mContext;
    private final TorrentEngine mEngine;
    private final SharedPreferences mPrefs;
    private final Map<String, String> mMetaCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, String> mStreamsCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, String> mMagnetRegistry = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, String> mOriginalLangCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, List<String>> mMovieAliasesMap = new java.util.concurrent.ConcurrentHashMap<>();

    private static class CatalogCacheEntry {
        final String json;
        final long expiresAt;
        CatalogCacheEntry(String json, long ttlMs) {
            this.json = json;
            this.expiresAt = System.currentTimeMillis() + ttlMs;
        }
    }
    private final Map<String, CatalogCacheEntry> mCatalogResponseCache = new java.util.concurrent.ConcurrentHashMap<>();

    private static class RecCacheEntry {
        final String json;
        final long expiresAt;
        RecCacheEntry(String json, long ttlMs) {
            this.json = json;
            this.expiresAt = System.currentTimeMillis() + ttlMs;
        }
    }
    private volatile RecCacheEntry mRecommendationsCache = null;

    private static String normalizeTitle(String title) {
        if (title == null) return "";
        String s = title.toLowerCase().trim();
        s = s.replaceAll("\\([0-9]{4}\\)|\\[[0-9]{4}\\]", "");
        s = s.replaceAll("\\((tamil|telugu|malayalam|kannada|hindi)\\)|\\[(tamil|telugu|malayalam|kannada|hindi)\\]", "");
        s = s.replaceAll("[^a-z0-9]", " ");
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }

    private static int parseYear(String str) {
        if (str == null || str.isEmpty()) return 0;
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d{4}").matcher(str);
            if (m.find()) {
                return Integer.parseInt(m.group());
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    private static double parseRating(String str) {
        if (str == null || str.isEmpty()) return 0.0;
        try {
            return Double.parseDouble(str);
        } catch (Throwable ignored) {}
        return 0.0;
    }

    private String getOriginalLanguage(String imdbId) {
        if (imdbId == null || imdbId.isEmpty()) return "English";
        String cached = mOriginalLangCache.get(imdbId);
        if (cached != null && !cached.isEmpty()) return cached;
        return "English";
    }

    private long mUpdateBytesRead = 0;
    private long mUpdateTotalBytes = 0;
    private int mUpdatePercent = 0;
    private String mUpdateStatus = "idle";
    private String mUpdateError = null;

    public LocalTorrentServer(Context context) {
        super(null, PORT);
        mContext = context;
        mEngine = TorrentEngine.getInstance(context);
        mPrefs = context.getSharedPreferences("StreamBraviaSettings", Context.MODE_PRIVATE);
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();
        Map<String, String> params = session.getParms();
        Map<String, String> headers = session.getHeaders();

        // CORS preflight
        if (Method.OPTIONS.equals(method)) {
            Response resp = newFixedLengthResponse(Response.Status.OK, "text/plain", "");
            resp.addHeader("Access-Control-Allow-Origin", "*");
            resp.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            resp.addHeader("Access-Control-Allow-Headers", "*");
            return resp;
        }

        // 1. Resolve endpoint for player launch (POST)
        if (uri.equals("/api/resolve")) {
            return handleResolveRequest(session);
        }

        // 2. Prewarm endpoint (POST)
        if (uri.equals("/api/prewarm")) {
            return handlePrewarmRequest(session);
        }

        // 3. Torrent Video Streaming endpoint (HTTP 206 Range requests for ExoPlayer)
        if (uri.equals("/api/stream") || uri.startsWith("/api/stream/")) {
            return handleStreamRequest(session, params, headers);
        }

        // 4. Stream Status & HUD Diagnostics endpoint
        if (uri.equals("/api/status") || uri.equals("/api/stream_status")) {
            return handleStatusRequest();
        }

        // 5. Stop Stream & Storage Wipe endpoint
        if (uri.equals("/api/stop")) {
            mEngine.stopCurrentStream();
            return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"stopped\"}");
        }

        // 6. Catalog Endpoint (Cinemeta)
        if (uri.equals("/api/catalog")) {
            return handleCatalogRequest(params);
        }

        // 7. Search Endpoint (Cinemeta)
        if (uri.equals("/api/search")) {
            return handleSearchRequest(params);
        }

        // 8. Metadata Endpoint (Cinemeta)
        if (uri.equals("/api/meta")) {
            return handleMetaRequest(params);
        }

        // 9. Streams Endpoint (Torrentio)
        if (uri.equals("/api/streams")) {
            return handleStreamsRequest(params);
        }

        // 10. TamilBlasters Streams Endpoint (MediaFusion)
        if (uri.equals("/api/streams/tamilblasters")) {
            return handleTamilblastersRequest(params);
        }

        // 11. Subtitles Search Endpoint
        if (uri.equals("/api/subtitles") || uri.equals("/api/subtitles/search")) {
            return handleSubtitlesRequest(params);
        }

        // 12. Settings endpoint (Real-Debrid & adult filters)
        if (uri.equals("/api/settings")) {
            return handleSettingsRequest(session, method);
        }

        // 13. Live Logs endpoint
        if (uri.equals("/api/log") || uri.equals("/api/logs")) {
            if (Method.POST.equals(method)) {
                try {
                    Map<String, String> body = new HashMap<>();
                    session.parseBody(body);
                } catch (Exception ignored) {}
            }
            return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"ok\",\"logs\":[\"[TV Engine] Standalone mode active\"]}");
        }

        // 14. Dynamic Device Video Hardware Capabilities
        if (uri.equals("/api/device_capabilities") || uri.equals("/api/caps")) {
            return jsonResponse(DeviceCapabilities.getInstance(mContext).toJson().toString());
        }

        // 15. Watch History Endpoints
        if (uri.equals("/api/history")) {
            return handleHistoryRequest();
        }
        if (uri.equals("/api/history/update")) {
            return handleHistoryUpdateRequest(session);
        }
        if (uri.equals("/api/history/remove")) {
            return handleHistoryRemoveRequest(session);
        }
        if (uri.equals("/api/history/reaction")) {
            return handleHistoryReactionRequest(session);
        }

        // 16. Recommendations Endpoint (Personalized AI Picks)
        if (uri.equals("/api/recommendations")) {
            return handleRecommendationsRequest();
        }

        // Swarm Race Prewarm Endpoint
        if (uri.equals("/api/race_prewarm")) {
            try {
                Map<String, String> body = new HashMap<>();
                session.parseBody(body);
            } catch (Exception ignored) {}
            return jsonResponse("{\"status\":\"ok\",\"winner\":null}");
        }

        // Media Tracks Endpoint
        if (uri.equals("/api/media_tracks")) {
            return jsonResponse("{\"status\":\"ok\",\"audio\":[],\"subtitles\":[]}");
        }

        // 14. OTA Update Endpoints (GitHub Releases)
        if (uri.equals("/api/update/check")) {
            return handleUpdateCheck();
        }

        if (uri.equals("/api/update/download")) {
            return handleUpdateDownload(params);
        }

        if (uri.equals("/api/update/progress")) {
            return handleUpdateProgress();
        }

        // Consume unhandled POST/PUT body to avoid keep-alive socket poisoning
        if (Method.POST.equals(method) || Method.PUT.equals(method)) {
            try {
                Map<String, String> body = new HashMap<>();
                session.parseBody(body);
            } catch (Exception ignored) {}
        }

        // 15. Serve Static Assets (HTML / CSS / JS / Fonts) from APK Assets
        return serveAsset(uri);
    }

    private Response handleCatalogRequest(Map<String, String> params) {
        String type = params.get("type");
        if (type == null || type.isEmpty()) type = "movie";
        String genre = params.get("genre");
        String lang = params.get("lang");
        String reqYear = params.get("year");

        String cacheKey = (type != null ? type : "") + "|" + (genre != null ? genre : "") + "|" + (lang != null ? lang : "") + "|" + (reqYear != null ? reqYear : "");
        CatalogCacheEntry cached = mCatalogResponseCache.get(cacheKey);
        if (cached != null && System.currentTimeMillis() < cached.expiresAt) {
            return jsonResponse(cached.json);
        }

        final boolean isSouthIndian = "Tamil".equalsIgnoreCase(genre) 
                || "Regional".equalsIgnoreCase(genre) 
                || "SouthIndian".equalsIgnoreCase(genre) 
                || "South Indian".equalsIgnoreCase(genre);

        List<JSONObject> rawList = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        List<String> urls = new ArrayList<>();

        if (isSouthIndian) {
            if ("telugu".equalsIgnoreCase(lang)) {
                urls.add(MEDIAFUSION_BASE + "/catalog/" + type + "/telugu_hdrip.json");
            } else if ("tamil".equalsIgnoreCase(lang)) {
                urls.add(MEDIAFUSION_BASE + "/catalog/" + type + "/tamil_hdrip.json");
            } else {
                urls.add(MEDIAFUSION_BASE + "/catalog/" + type + "/tamil_hdrip.json");
                urls.add(MEDIAFUSION_BASE + "/catalog/" + type + "/telugu_hdrip.json");
                if ("series".equalsIgnoreCase(type)) {
                    urls.add(MEDIAFUSION_BASE + "/catalog/series/tamil_series.json");
                }
            }
        } else {
            String genrePart = (genre != null && !genre.isEmpty()) ? "/genre=" + URLEncoder.encode(genre) : "";
            int currentCalendarYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);

            if (reqYear != null && !reqYear.isEmpty() && !"all".equalsIgnoreCase(reqYear)) {
                if (reqYear.matches("\\d{4}")) {
                    urls.add(CINEMETA_BASE + "/catalog/" + type + "/year/genre=" + reqYear + ".json");
                }
                if (genre != null && !genre.isEmpty()) {
                    urls.add(CINEMETA_BASE + "/catalog/" + type + "/top" + genrePart + ".json");
                } else {
                    urls.add(CINEMETA_BASE + "/catalog/" + type + "/top.json");
                }
            } else if (genre == null || genre.isEmpty()) {
                // Dynamically fetch current calendar year and previous year based on TV system time
                urls.add(CINEMETA_BASE + "/catalog/" + type + "/year/genre=" + currentCalendarYear + ".json");
                urls.add(CINEMETA_BASE + "/catalog/" + type + "/year/genre=" + (currentCalendarYear - 1) + ".json");
                urls.add(CINEMETA_BASE + "/catalog/" + type + "/top.json");
            } else {
                urls.add(CINEMETA_BASE + "/catalog/" + type + "/top" + genrePart + ".json");
            }
        }

        // Parallel HTTP fetch for blazing fast catalog loading (~0.6s total)
        int poolSize = Math.min(4, Math.max(1, urls.size()));
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(poolSize);
        List<java.util.concurrent.Future<String>> futures = new ArrayList<>();
        for (final String u : urls) {
            futures.add(pool.submit(new java.util.concurrent.Callable<String>() {
                @Override
                public String call() {
                    return httpGet(u);
                }
            }));
        }
        pool.shutdown();

        for (int i = 0; i < urls.size(); i++) {
            String u = urls.get(i);
            try {
                String json = futures.get(i).get(3500, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (json != null) {
                    JSONObject obj = new JSONObject(json);
                    JSONArray arr = obj.optJSONArray("metas");
                    if (arr != null) {
                        String itemLang = isSouthIndian ? (u.contains("telugu") ? "Telugu" : "Tamil") : null;
                        for (int j = 0; j < arr.length(); j++) {
                            JSONObject m = arr.getJSONObject(j);
                            String id = m.optString("id");
                            if (!id.isEmpty() && seenIds.add(id)) {
                                if (itemLang != null) {
                                    m.put("original_language", itemLang);
                                    mOriginalLangCache.put(id, itemLang);
                                }
                                rawList.add(m);
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // Deduplicate multiple uploads/scrapers into a single canonical card per title
        Map<String, JSONObject> deduplicated = new LinkedHashMap<>();
        for (JSONObject m : rawList) {
            try {
                String id = m.optString("id", "").trim();
                String name = m.optString("name", "").trim();
                if (name.isEmpty() || id.isEmpty()) continue;

                // Auto-heal missing, empty, or placeholder posters with canonical Metahub poster
                String poster = m.optString("poster", "").trim();
                if (poster.isEmpty() || poster.equals("None") || poster.equals("null") || poster.contains("placeholder")) {
                    if (id.startsWith("tt")) {
                        poster = "https://images.metahub.space/poster/medium/" + id + "/img";
                        m.put("poster", poster);
                    }
                }

                String norm = normalizeTitle(name);
                if (norm.isEmpty()) norm = id;

                if (!deduplicated.containsKey(norm)) {
                    JSONArray aliases = new JSONArray();
                    aliases.put(id);
                    m.put("alias_ids", aliases);
                    deduplicated.put(norm, m);
                } else {
                    JSONObject canonical = deduplicated.get(norm);
                    JSONArray aliases = canonical.optJSONArray("alias_ids");
                    if (aliases == null) {
                        aliases = new JSONArray();
                        aliases.put(canonical.optString("id"));
                        canonical.put("alias_ids", aliases);
                    }
                    boolean exists = false;
                    for (int a = 0; a < aliases.length(); a++) {
                        if (id.equals(aliases.optString(a))) {
                            exists = true;
                            break;
                        }
                    }
                    if (!exists) {
                        aliases.put(id);
                    }

                    // Poster healing: if canonical has a missing/placeholder poster, replace with valid poster
                    String cPost = canonical.optString("poster", "");
                    String mPost = m.optString("poster", "");
                    if ((cPost.isEmpty() || cPost.contains("placeholder")) && !mPost.isEmpty()) {
                        canonical.put("poster", mPost);
                    }
                    // Year healing
                    if (canonical.optString("releaseInfo", "").isEmpty() && !m.optString("releaseInfo", "").isEmpty()) {
                        canonical.put("releaseInfo", m.optString("releaseInfo"));
                    }
                    // Rating healing
                    double cRate = parseRating(canonical.optString("imdbRating", "0"));
                    double mRate = parseRating(m.optString("imdbRating", "0"));
                    if (mRate > cRate) {
                        canonical.put("imdbRating", m.optString("imdbRating"));
                    }
                }
            } catch (Exception ignored) {}
        }

        List<JSONObject> metaList = new ArrayList<>(deduplicated.values());

        // Update alias lookup map for stream resolution
        for (JSONObject it : metaList) {
            String canId = it.optString("id");
            JSONArray aliases = it.optJSONArray("alias_ids");
            if (aliases != null && aliases.length() > 1) {
                List<String> list = new ArrayList<>();
                for (int a = 0; a < aliases.length(); a++) {
                    list.add(aliases.optString(a));
                }
                mMovieAliasesMap.put(canId, list);
            }
        }

        // Apply dynamic year filter if requested (works for both general and South Indian)
        if (reqYear != null && !reqYear.isEmpty() && !"all".equalsIgnoreCase(reqYear)) {
            List<JSONObject> filtered = new ArrayList<>();
            for (JSONObject item : metaList) {
                int y = parseYear(item.optString("releaseInfo", item.optString("year", "0")));
                if (reqYear.matches("\\d{4}")) {
                    int targetY = Integer.parseInt(reqYear);
                    if (y == targetY) filtered.add(item);
                } else if ("2010s".equalsIgnoreCase(reqYear)) {
                    if (y >= 2010 && y <= 2019) filtered.add(item);
                } else if ("2000s".equalsIgnoreCase(reqYear)) {
                    if (y >= 2000 && y <= 2009) filtered.add(item);
                } else if ("classics".equalsIgnoreCase(reqYear)) {
                    if (y > 0 && y < 2000) filtered.add(item);
                }
            }
            if (!filtered.isEmpty()) {
                metaList = filtered;
            }
        }

        // Two-level sorting: 1st Level = Recent Release (Year desc), 2nd Level = Rating desc
        Collections.sort(metaList, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject a, JSONObject b) {
                int yA = parseYear(a.optString("releaseInfo", a.optString("year", "0")));
                int yB = parseYear(b.optString("releaseInfo", b.optString("year", "0")));
                if (yB != yA) {
                    return Integer.compare(yB, yA);
                }
                double rA = parseRating(a.optString("imdbRating", "0"));
                double rB = parseRating(b.optString("imdbRating", "0"));
                return Double.compare(rB, rA);
            }
        });

        try {
            JSONArray sortedArray = new JSONArray();
            for (JSONObject item : metaList) {
                sortedArray.put(item);
            }
            JSONObject res = new JSONObject();
            res.put("status", "ok");
            res.put("items", sortedArray);
            String responseJson = res.toString();
            if (sortedArray.length() > 0) {
                mCatalogResponseCache.put(cacheKey, new CatalogCacheEntry(responseJson, 10 * 60 * 1000L));
            }
            return jsonResponse(responseJson);
        } catch (Exception e) {
            return jsonResponse("{\"status\":\"ok\",\"items\":[]}");
        }
    }

    private Response handleSearchRequest(Map<String, String> params) {
        String q = params.get("q");
        String type = params.get("type");
        if (type == null || type.isEmpty()) type = "movie";
        if (q == null || q.isEmpty()) {
            return jsonResponse("{\"status\":\"ok\",\"items\":[]}");
        }

        String url = CINEMETA_BASE + "/catalog/" + type + "/top/search=" + URLEncoder.encode(q) + ".json";
        String json = httpGet(url);
        if (json == null) json = "{\"metas\":[]}";

        try {
            JSONObject orig = new JSONObject(json);
            JSONArray metas = orig.optJSONArray("metas");
            JSONObject res = new JSONObject();
            res.put("status", "ok");
            res.put("items", metas != null ? metas : new JSONArray());
            return jsonResponse(res.toString());
        } catch (Exception e) {
            return jsonResponse("{\"status\":\"ok\",\"items\":[]}");
        }
    }

    private Response handleMetaRequest(Map<String, String> params) {
        String id = params.get("id");
        String type = params.get("type");
        if (type == null || type.isEmpty()) type = "movie";
        if (id == null || id.isEmpty()) {
            return jsonResponse("{\"status\":\"error\",\"message\":\"Missing id\"}");
        }

        String cacheKey = type + ":" + id;
        String cached = mMetaCache.get(cacheKey);
        if (cached != null) {
            return jsonResponse(cached);
        }

        String url = CINEMETA_BASE + "/meta/" + type + "/" + id + ".json";
        String json = httpGet(url);
        if (json == null) json = "{\"meta\":null}";

        try {
            JSONObject orig = new JSONObject(json);
            JSONObject meta = orig.optJSONObject("meta");
            JSONObject res = new JSONObject();
            res.put("status", "ok");
            res.put("meta", meta);
            String out = res.toString();
            if (meta != null) {
                mMetaCache.put(cacheKey, out);
            }
            return jsonResponse(out);
        } catch (Exception e) {
            return jsonResponse("{\"status\":\"ok\",\"meta\":null}");
        }
    }

    private Response handleStreamsRequest(Map<String, String> params) {
        String id = params.get("id");
        String type = params.get("type");
        String season = params.get("season");
        String episode = params.get("episode");

        if (id == null || id.isEmpty() || id.equals("undefined") || id.equals("null")) {
            return jsonResponse("{\"status\":\"ok\",\"streams\":[]}");
        }
        if (type == null || type.isEmpty()) type = "movie";

        String targetId = id;
        if (season != null && episode != null) {
            targetId = id + ":" + season + ":" + episode;
        }

        String cachedStreams = mStreamsCache.get(targetId);
        if (cachedStreams != null) {
            return jsonResponse(cachedStreams);
        }

        List<String> allQueryIds = new ArrayList<>();
        allQueryIds.add(id);
        List<String> aliases = mMovieAliasesMap.get(id);
        if (aliases != null) {
            for (String a : aliases) {
                if (!allQueryIds.contains(a)) allQueryIds.add(a);
            }
        }

        try {
            final JSONArray rawStreams = new JSONArray();
            final Set<String> seenHashes = new HashSet<>();
            final Object streamLock = new Object();

            List<String> queryUrls = new ArrayList<>();
            for (String qId : allQueryIds) {
                String qTargetId = qId;
                if (season != null && episode != null) {
                    qTargetId = qId + ":" + season + ":" + episode;
                }
                queryUrls.add(TORRENTIO_BASE + "/stream/" + type + "/" + qTargetId + ".json");
                queryUrls.add(MEDIAFUSION_BASE + "/stream/" + type + "/" + qTargetId + ".json");
            }

            int poolSize = Math.min(8, Math.max(1, queryUrls.size()));
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(poolSize);
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (final String url : queryUrls) {
                futures.add(pool.submit(new Runnable() {
                    @Override
                    public void run() {
                        String json = httpGet(url);
                        if (json != null) {
                            try {
                                JSONArray arr = new JSONObject(json).optJSONArray("streams");
                                if (arr != null) {
                                    synchronized (streamLock) {
                                        for (int i = 0; i < arr.length(); i++) {
                                            JSONObject s = arr.getJSONObject(i);
                                            String hash = s.optString("infoHash", "");
                                            if (hash.isEmpty() || seenHashes.add(hash.toLowerCase())) {
                                                rawStreams.put(s);
                                            }
                                        }
                                    }
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                }));
            }
            pool.shutdown();
            for (java.util.concurrent.Future<?> f : futures) {
                try {
                    f.get(3500, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (Exception ignored) {}
            }

            JSONArray processed = new JSONArray();
            final DeviceCapabilities caps = DeviceCapabilities.getInstance(mContext);
            final boolean allow4K = caps.supports4K();
            final boolean allow8K = caps.supports8K();

            List<JSONObject> streamList = new ArrayList<>();
            for (int i = 0; i < rawStreams.length(); i++) {
                JSONObject s = rawStreams.getJSONObject(i);
                JSONObject item = parseStreamItem(s, "Torrentio");
                if (item != null) {
                    String res = item.optString("resolution", "");
                    String rawT = item.optString("raw_title", "").toLowerCase();
                    boolean is8K = res.contains("8K") || res.contains("4320p") || rawT.contains("4320p") || rawT.contains("8k");
                    boolean is4K = res.contains("4K") || res.contains("2160p") || rawT.contains("2160p") || rawT.contains("4k");

                    // Dynamic hardware resolution gating based on device hardware support
                    if (is8K && !allow8K) continue;
                    if (is4K && !allow4K) continue;

                    streamList.add(item);
                }
            }
            final String origLang = getOriginalLanguage(id);
            // Sort by hardware-aware quality, original language match, & swarm health score
            Collections.sort(streamList, new Comparator<JSONObject>() {
                @Override
                public int compare(JSONObject a, JSONObject b) {
                    return Integer.compare(calculateStreamScore(b, caps, origLang), calculateStreamScore(a, caps, origLang));
                }
            });
            for (JSONObject it : streamList) {
                processed.put(it);
            }

            JSONObject res = new JSONObject();
            res.put("status", "ok");
            res.put("original_language", origLang);
            res.put("streams", processed);
            String outStr = res.toString();
            if (processed.length() > 0) {
                mStreamsCache.put(targetId, outStr);
            }
            return jsonResponse(outStr);
        } catch (Exception e) {
            return jsonResponse("{\"status\":\"ok\",\"streams\":[]}");
        }
    }

    private Response handleTamilblastersRequest(Map<String, String> params) {
        String id = params.get("id");
        String type = params.get("type");
        String season = params.get("season");
        String episode = params.get("episode");

        if (id == null || id.isEmpty() || id.equals("undefined") || id.equals("null")) {
            return jsonResponse("{\"status\":\"ok\",\"streams\":[],\"count\":0}");
        }
        if (type == null || type.isEmpty()) type = "movie";

        List<String> allQueryIds = new ArrayList<>();
        allQueryIds.add(id);
        List<String> aliases = mMovieAliasesMap.get(id);
        if (aliases != null) {
            for (String a : aliases) {
                if (!allQueryIds.contains(a)) allQueryIds.add(a);
            }
        }

        try {
            final JSONArray rawStreams = new JSONArray();
            final Set<String> seenHashes = new HashSet<>();
            final Object tbLock = new Object();

            List<String> queryUrls = new ArrayList<>();
            for (String qId : allQueryIds) {
                String qTargetId = qId;
                if (season != null && episode != null) {
                    qTargetId = qId + ":" + season + ":" + episode;
                }
                queryUrls.add(MEDIAFUSION_BASE + "/stream/" + type + "/" + qTargetId + ".json");
            }

            int poolSize = Math.min(6, Math.max(1, queryUrls.size()));
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(poolSize);
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (final String url : queryUrls) {
                futures.add(pool.submit(new Runnable() {
                    @Override
                    public void run() {
                        String json = httpGet(url);
                        if (json != null) {
                            try {
                                JSONArray arr = new JSONObject(json).optJSONArray("streams");
                                if (arr != null) {
                                    synchronized (tbLock) {
                                        for (int i = 0; i < arr.length(); i++) {
                                            JSONObject s = arr.getJSONObject(i);
                                            String hash = s.optString("infoHash", "");
                                            if (hash.isEmpty() || seenHashes.add(hash.toLowerCase())) {
                                                rawStreams.put(s);
                                            }
                                        }
                                    }
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                }));
            }
            pool.shutdown();
            for (java.util.concurrent.Future<?> f : futures) {
                try {
                    f.get(3500, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (Exception ignored) {}
            }

            JSONArray processed = new JSONArray();
            final DeviceCapabilities caps = DeviceCapabilities.getInstance(mContext);
            final boolean allow4K = caps.supports4K();
            final boolean allow8K = caps.supports8K();

            List<JSONObject> streamList = new ArrayList<>();
            for (int i = 0; i < rawStreams.length(); i++) {
                JSONObject s = rawStreams.getJSONObject(i);
                String name = s.optString("name", "");
                String title = s.optString("title", "");
                if (name.toLowerCase().contains("tamilblasters") || title.toLowerCase().contains("tamilblasters")) {
                    JSONObject item = parseStreamItem(s, "TamilBlasters");
                    if (item != null) {
                        String res = item.optString("resolution", "");
                        String rawT = item.optString("raw_title", "").toLowerCase();
                        boolean is8K = res.contains("8K") || res.contains("4320p") || rawT.contains("4320p") || rawT.contains("8k");
                        boolean is4K = res.contains("4K") || res.contains("2160p") || rawT.contains("2160p") || rawT.contains("4k");

                        if (is8K && !allow8K) continue;
                        if (is4K && !allow4K) continue;

                        streamList.add(item);
                    }
                }
            }
                final String origLang = getOriginalLanguage(id);
                Collections.sort(streamList, new Comparator<JSONObject>() {
                    @Override
                    public int compare(JSONObject a, JSONObject b) {
                        return Integer.compare(calculateStreamScore(b, caps, origLang), calculateStreamScore(a, caps, origLang));
                    }
                });
                for (JSONObject it : streamList) {
                    processed.put(it);
                }

            JSONObject res = new JSONObject();
            res.put("status", "ok");
            res.put("original_language", getOriginalLanguage(id));
            res.put("streams", processed);
            res.put("count", processed.length());
            return jsonResponse(res.toString());
        } catch (Exception e) {
            return jsonResponse("{\"status\":\"ok\",\"streams\":[],\"count\":0}");
        }
    }

    private JSONObject parseStreamItem(JSONObject s, String defaultProvider) {
        try {
            JSONObject item = new JSONObject();
            String name = s.optString("name", defaultProvider);
            String rawTitle = s.optString("title", "");
            String infoHash = s.optString("infoHash", "").toLowerCase().trim();
            if (infoHash.isEmpty()) {
                infoHash = s.optString("info_hash", "").toLowerCase().trim();
            }
            String directUrl = s.optString("url", "").trim();
            if (infoHash.isEmpty() && directUrl.startsWith("magnet:?")) {
                int idx = directUrl.indexOf("urn:btih:");
                if (idx != -1) {
                    String sub = directUrl.substring(idx + 9);
                    int amp = sub.indexOf('&');
                    infoHash = (amp != -1 ? sub.substring(0, amp) : sub).toLowerCase().trim();
                }
            }

            item.put("name", name);
            item.put("raw_title", rawTitle);
            item.put("title", rawTitle);
            item.put("info_hash", infoHash);
            String cleanProvider = name.replace("Torrentio\n", "").replace("[RD+]\n", "").trim();
            item.put("provider", !cleanProvider.isEmpty() ? cleanProvider : defaultProvider);
            item.put("stream_url", "/api/stream/video.mkv?info_hash=" + infoHash);
            item.put("status_url", "/api/status?info_hash=" + infoHash);

            String fullText = (rawTitle + " " + name).toLowerCase();

            // Resolution detection
            String resolution = "1080p Full HD";
            if (fullText.contains("4320p") || fullText.contains("8k") || fullText.contains("4k") || fullText.contains("2160p") || fullText.contains("uhd")) {
                resolution = "4K 2160p";
            } else if (fullText.contains("1080p") || fullText.contains("fhd") || fullText.contains("1080i")) {
                resolution = "1080p Full HD";
            } else if (fullText.contains("720p") || fullText.matches(".*\\b(720|hd)\\b.*")) {
                resolution = "720p HD";
            }
            item.put("resolution", resolution);

            // Hardware Codec detection
            String codec = "H.264 / AVC";
            String codecTag = "H.264";
            if (fullText.contains("av01") || fullText.contains("av1")) {
                codec = "AV1";
                codecTag = "AV1";
            } else if (fullText.contains("h.265") || fullText.contains("hevc") || fullText.contains("x265")) {
                codec = "HEVC / H.265";
                codecTag = "HEVC";
            } else if (fullText.contains("h.264") || fullText.contains("avc") || fullText.contains("x264")) {
                codec = "H.264 / AVC";
                codecTag = "H.264";
            }
            item.put("codec", codec);
            item.put("codec_tag", codecTag);

            // Audio track detection
            String audio = "Stereo AAC";
            String audioTag = "Stereo";
            if (fullText.contains("atmos") || fullText.contains("truehd")) {
                audio = "Dolby Atmos";
                audioTag = "Atmos";
            } else if (fullText.contains("ddp") || fullText.contains("dd+") || fullText.contains("eac3")) {
                audio = "Dolby Digital Plus";
                audioTag = "DD+";
            } else if (fullText.contains("dts")) {
                audio = "DTS-HD";
                audioTag = "DTS";
            } else if (fullText.contains("5.1") || fullText.contains("ac3")) {
                audio = "5.1 Surround";
                audioTag = "5.1";
            }
            item.put("audio", audio);
            item.put("audio_tag", audioTag);

            // HDR & Dolby Vision
            boolean hdr = fullText.contains("hdr") || fullText.contains("hdr10");
            boolean dv = fullText.contains("dv") || fullText.contains("dolby vision") || fullText.contains("dolbyvision");
            item.put("hdr", hdr);
            item.put("dolby_vision", dv);

            // Seeders extraction
            int seeders = 0;
            java.util.regex.Matcher sm = java.util.regex.Pattern.compile("(?:👤|👥|seeds?|peers?)\\s*(\\d+)").matcher(rawTitle);
            if (sm.find()) {
                try {
                    seeders = Integer.parseInt(sm.group(1));
                } catch (Exception ignored) {}
            }
            item.put("seeders", seeders);

            // Size extraction
            String sizeStr = "Unknown";
            java.util.regex.Matcher szm = java.util.regex.Pattern.compile("(?:💾|size:?)\\s*([0-9.]+\\s*(?:GB|MB|GiB|MiB))", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(rawTitle);
            if (szm.find()) {
                sizeStr = szm.group(1);
            }
            item.put("size", sizeStr);

            boolean isDebridCached = name.contains("[RD+]") || fullText.contains("rd+ cached");
            item.put("is_debrid_cached", isDebridCached);

            // Extract announce trackers from Torrentio "sources" array if present
            JSONArray sources = s.optJSONArray("sources");
            if (sources != null) {
                JSONArray trackers = new JSONArray();
                for (int k = 0; k < sources.length(); k++) {
                    String src = sources.optString(k, "");
                    if (src.startsWith("tracker:")) {
                        trackers.put(src.substring("tracker:".length()));
                    }
                }
                if (trackers.length() > 0) {
                    item.put("trackers", trackers);
                }
            }

            if (directUrl != null && !directUrl.isEmpty() && (directUrl.startsWith("http://") || directUrl.startsWith("https://"))) {
                item.put("direct_url", directUrl);
            }
            if (!infoHash.isEmpty()) {
                buildFullMagnetUri(item);
            }
            return item;
        } catch (Exception e) {
            return null;
        }
    }

    public String buildFullMagnetUri(JSONObject streamItem) {
        if (streamItem == null) return "";
        String infoHash = streamItem.optString("info_hash", "").toLowerCase().trim();
        if (infoHash.isEmpty()) {
            infoHash = streamItem.optString("infoHash", "").toLowerCase().trim();
        }
        if (infoHash.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("magnet:?xt=urn:btih:").append(infoHash);
        Set<String> distinctTrackers = new HashSet<>();

        JSONArray trackers = streamItem.optJSONArray("trackers");
        if (trackers != null) {
            for (int i = 0; i < trackers.length(); i++) {
                String tr = trackers.optString(i, "").trim();
                if (!tr.isEmpty()) distinctTrackers.add(tr);
            }
        }
        JSONArray compTrackers = streamItem.optJSONArray("companion_trackers");
        if (compTrackers != null) {
            for (int i = 0; i < compTrackers.length(); i++) {
                String tr = compTrackers.optString(i, "").trim();
                if (!tr.isEmpty()) distinctTrackers.add(tr);
            }
        }
        JSONArray sources = streamItem.optJSONArray("sources");
        if (sources != null) {
            for (int i = 0; i < sources.length(); i++) {
                String src = sources.optString(i, "").trim();
                if (src.startsWith("tracker:")) {
                    distinctTrackers.add(src.substring("tracker:".length()).trim());
                }
            }
        }
        for (String tr : TorrentEngine.PUBLIC_TRACKERS) {
            distinctTrackers.add(tr);
        }

        for (String tr : distinctTrackers) {
            try {
                sb.append("&tr=").append(URLEncoder.encode(tr, "UTF-8"));
            } catch (Exception ignored) {
                sb.append("&tr=").append(tr);
            }
        }
        String magnet = sb.toString();
        mMagnetRegistry.put(infoHash, magnet);
        return magnet;
    }

    private Response handleResolveRequest(IHTTPSession session) {
        try {
            Map<String, String> body = new HashMap<>();
            session.parseBody(body);
            String postData = body.get("postData");
            if (postData == null || postData.isEmpty()) {
                return jsonResponse("{\"status\":\"error\",\"message\":\"Empty payload\"}");
            }

            JSONObject streamItem = new JSONObject(postData);
            String infoHash = streamItem.optString("info_hash", "").toLowerCase().trim();
            String directUrl = streamItem.optString("direct_url", "").trim();

            if (!directUrl.isEmpty() && directUrl.startsWith("http")) {
                JSONObject resolved = new JSONObject();
                resolved.put("type", "direct_http");
                resolved.put("url", directUrl);
                resolved.put("raw_url", directUrl);
                resolved.put("instant", true);
                resolved.put("message", "Direct High-Speed Stream");
                JSONObject res = new JSONObject();
                res.put("status", "ok");
                res.put("resolved", resolved);
                return jsonResponse(res.toString());
            }

            // Standalone Torrent Video Stream over localhost
            if (!infoHash.isEmpty()) {
                final String magnetUri = buildFullMagnetUri(streamItem);
                mMagnetRegistry.put(infoHash, magnetUri);

                // Start resolving torrent metadata asynchronously so /api/resolve returns in <1ms!
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            mEngine.prepareStream(magnetUri, 30);
                        } catch (Throwable ignored) {}
                    }
                }, "ResolveAsyncPrep").start();
            }

            String localStreamUrl = "http://127.0.0.1:" + PORT + "/api/stream/video.mkv?info_hash=" + infoHash;
            JSONObject resolved = new JSONObject();
            resolved.put("type", "torrent_http");
            resolved.put("url", localStreamUrl);
            resolved.put("raw_url", localStreamUrl);
            resolved.put("info_hash", infoHash);
            resolved.put("instant", false);
            resolved.put("message", "⚡ Direct Hardware Zero-Copy Stream");

            JSONObject res = new JSONObject();
            res.put("status", "ok");
            res.put("resolved", resolved);
            return jsonResponse(res.toString());
        } catch (Exception e) {
            Log.e(TAG, "Resolve error: " + e.getMessage(), e);
            return jsonResponse("{\"status\":\"error\",\"message\":\"" + e.getMessage() + "\"}");
        }
    }

    private Response handlePrewarmRequest(IHTTPSession session) {
        try {
            Map<String, String> body = new HashMap<>();
            session.parseBody(body);
            String postData = body.get("postData");
            if (postData != null && !postData.isEmpty()) {
                JSONObject obj = new JSONObject(postData);
                final String hash = obj.optString("info_hash", "").toLowerCase().trim();
                if (!hash.isEmpty()) {
                    final String magnetUri = buildFullMagnetUri(obj);
                    // Prewarm in background if no active stream is running
                    if (mEngine.getActiveHash() == null) {
                        new Thread(() -> {
                            try {
                                mEngine.prepareStream(magnetUri, 15);
                            } catch (Throwable ignored) {}
                        }, "PrewarmWorker").start();
                    }
                }
            }
        } catch (Exception ignored) {}
        return jsonResponse("{\"status\":\"ok\"}");
    }

    private int calculateStreamScore(JSONObject item, DeviceCapabilities caps, String origLang) {
        String res = item.optString("resolution", "");
        int seeds = item.optInt("seeders", 0);
        boolean isCached = item.optBoolean("is_debrid_cached", false);
        String rawTitle = (item.optString("raw_title", "") + " " + item.optString("title", "")).toLowerCase();

        int resScore = 0;
        if (res.contains("8K") || res.contains("4320p")) {
            resScore = caps.supports8K() ? 4000 : 0;
        } else if (res.contains("4K") || res.contains("2160p")) {
            resScore = caps.supports4K() ? 3000 : 0;
        } else if (res.contains("1080p") || res.contains("FHD")) {
            resScore = 2000;
        } else if (res.contains("720p") || res.contains("HD")) {
            resScore = 1000;
        } else {
            resScore = 500;
        }

        // Original Language Matching & Priority Boost
        int langScore = 0;
        if (origLang != null && !origLang.isEmpty() && !"Unknown".equalsIgnoreCase(origLang)) {
            String l = origLang.toLowerCase();
            boolean hasMulti = rawTitle.contains("multi") || rawTitle.contains("dual audio") || rawTitle.contains("multi audio");
            boolean hasOrig = false;

            if (l.contains("tamil") || l.equals("ta")) {
                hasOrig = rawTitle.contains("tamil") || rawTitle.contains("tam") || rawTitle.contains("tamilblasters") || rawTitle.contains("1tamilmv");
            } else if (l.contains("telugu") || l.equals("te")) {
                hasOrig = rawTitle.contains("telugu") || rawTitle.contains("tel") || rawTitle.contains("telugumv");
            } else if (l.contains("malayalam") || l.equals("ml")) {
                hasOrig = rawTitle.contains("malayalam") || rawTitle.contains("mal");
            } else if (l.contains("kannada") || l.equals("kn")) {
                hasOrig = rawTitle.contains("kannada") || rawTitle.contains("kan");
            } else if (l.contains("korean") || l.equals("ko")) {
                hasOrig = rawTitle.contains("korean") || rawTitle.contains("kor");
            } else if (l.contains("japanese") || l.equals("ja")) {
                hasOrig = rawTitle.contains("japanese") || rawTitle.contains("jap");
            } else if (l.contains("spanish") || l.equals("es")) {
                hasOrig = rawTitle.contains("spanish") || rawTitle.contains("esp") || rawTitle.contains("castellano");
            } else if (l.contains("hindi") || l.equals("hi")) {
                hasOrig = rawTitle.contains("hindi") || rawTitle.contains("hin");
            } else if (l.contains("english") || l.equals("en")) {
                hasOrig = rawTitle.contains("english") || rawTitle.contains("eng") || !hasMulti;
            }

            if (hasOrig || hasMulti) {
                langScore += 5000; // Prioritize original language torrents
            } else {
                // Penalize conflicting dubbed torrents that lack the original language
                boolean isConflictingDub = rawTitle.contains("dub") || rawTitle.contains("hindi") || rawTitle.contains("telugu") || rawTitle.contains("tamil");
                if (isConflictingDub) {
                    langScore -= 4000;
                }
            }
        }

        // Penalty for 0 seeds on non-cached torrents so dead swarms never rank #1
        if (seeds == 0 && !isCached) {
            resScore -= 2000;
        }

        int seedScore = Math.min(seeds, 500);
        int debridBonus = isCached ? 1000 : 0;
        return resScore + seedScore + debridBonus + langScore;
    }

    private static final Map<String, String> LANG_NAMES = new HashMap<>();
    static {
        LANG_NAMES.put("eng", "English");
        LANG_NAMES.put("en", "English");
        LANG_NAMES.put("spa", "Spanish");
        LANG_NAMES.put("es", "Spanish");
        LANG_NAMES.put("fre", "French");
        LANG_NAMES.put("fra", "French");
        LANG_NAMES.put("fr", "French");
        LANG_NAMES.put("ger", "German");
        LANG_NAMES.put("deu", "German");
        LANG_NAMES.put("de", "German");
        LANG_NAMES.put("ita", "Italian");
        LANG_NAMES.put("it", "Italian");
        LANG_NAMES.put("por", "Portuguese");
        LANG_NAMES.put("pt", "Portuguese");
        LANG_NAMES.put("pob", "Portuguese (Brazil)");
        LANG_NAMES.put("rus", "Russian");
        LANG_NAMES.put("ru", "Russian");
        LANG_NAMES.put("tam", "Tamil");
        LANG_NAMES.put("ta", "Tamil");
        LANG_NAMES.put("hin", "Hindi");
        LANG_NAMES.put("hi", "Hindi");
        LANG_NAMES.put("tel", "Telugu");
        LANG_NAMES.put("te", "Telugu");
        LANG_NAMES.put("mal", "Malayalam");
        LANG_NAMES.put("ml", "Malayalam");
        LANG_NAMES.put("kan", "Kannada");
        LANG_NAMES.put("kn", "Kannada");
        LANG_NAMES.put("ara", "Arabic");
        LANG_NAMES.put("ar", "Arabic");
        LANG_NAMES.put("chi", "Chinese");
        LANG_NAMES.put("zho", "Chinese");
        LANG_NAMES.put("zh", "Chinese");
        LANG_NAMES.put("jpn", "Japanese");
        LANG_NAMES.put("ja", "Japanese");
        LANG_NAMES.put("kor", "Korean");
        LANG_NAMES.put("ko", "Korean");
        LANG_NAMES.put("tur", "Turkish");
        LANG_NAMES.put("tr", "Turkish");
        LANG_NAMES.put("pol", "Polish");
        LANG_NAMES.put("pl", "Polish");
        LANG_NAMES.put("dut", "Dutch");
        LANG_NAMES.put("nld", "Dutch");
        LANG_NAMES.put("nl", "Dutch");
        LANG_NAMES.put("ind", "Indonesian");
        LANG_NAMES.put("id", "Indonesian");
    }

    public static String getLanguageDisplayName(String code) {
        if (code == null || code.isEmpty()) return "English";
        String lower = code.toLowerCase(Locale.US).trim();
        String name = LANG_NAMES.get(lower);
        if (name != null) return name;
        if (lower.length() <= 3) return lower.toUpperCase(Locale.US);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private Response handleSubtitlesRequest(Map<String, String> params) {
        String id = params.get("id");
        String type = params.get("type");
        if (type == null || type.isEmpty()) type = "movie";

        if (id == null || id.isEmpty()) {
            return jsonResponse("{\"status\":\"ok\",\"subtitles\":[]}");
        }

        String url = STREMIO_SUBS_BASE + "/subtitles/" + type + "/" + id + ".json";
        String json = httpGet(url);
        if (json == null) json = "{\"subtitles\":[]}";

        try {
            JSONObject orig = new JSONObject(json);
            JSONArray subs = orig.optJSONArray("subtitles");
            JSONArray processedSubs = new JSONArray();

            if (subs != null) {
                for (int i = 0; i < subs.length(); i++) {
                    JSONObject raw = subs.getJSONObject(i);
                    JSONObject s = new JSONObject(raw.toString());

                    String lang = raw.optString("lang", "en");
                    String releaseName = raw.optString("movieReleaseName", "");
                    String fileName = raw.optString("subtitleFileName", "");
                    String format = raw.optString("releaseFormat", "");
                    String group = raw.optString("releaseGroup", "");
                    int fps = raw.optInt("fpsMilli", 0);

                    String langFull = getLanguageDisplayName(lang);

                    // Extract clean release title without .srt extension
                    String cleanTitle = !releaseName.isEmpty() ? releaseName : fileName;
                    if (cleanTitle.toLowerCase(Locale.US).endsWith(".srt") || cleanTitle.toLowerCase(Locale.US).endsWith(".vtt")) {
                        cleanTitle = cleanTitle.substring(0, cleanTitle.lastIndexOf('.'));
                    }

                    // Build formatted label: e.g. "English [Blu-ray] (23.98 fps)"
                    StringBuilder labelBuilder = new StringBuilder(langFull);
                    if (!format.isEmpty()) {
                        labelBuilder.append(" [").append(format).append("]");
                    } else if (!group.isEmpty()) {
                        labelBuilder.append(" [").append(group).append("]");
                    }
                    if (fps > 0) {
                        double fpsSec = fps / 1000.0;
                        labelBuilder.append(String.format(Locale.US, " (%.2f fps)", fpsSec));
                    }

                    s.put("label", labelBuilder.toString());
                    s.put("lang_display", langFull);
                    s.put("release_title", !cleanTitle.isEmpty() ? cleanTitle : (langFull + " Track #" + (i + 1)));
                    s.put("details", cleanTitle);
                    processedSubs.put(s);
                }
            }

            JSONObject res = new JSONObject();
            res.put("status", "ok");
            res.put("subtitles", processedSubs);
            return jsonResponse(res.toString());
        } catch (Exception e) {
            return jsonResponse("{\"status\":\"ok\",\"subtitles\":[]}");
        }
    }

    private Response handleUpdateCheck() {
        try {
            UpdateManager.UpdateInfo info = UpdateManager.getInstance(mContext).checkLatestReleaseSync();
            JSONObject res = info != null ? info.toJson() : new JSONObject();
            res.put("status", "ok");
            return jsonResponse(res.toString());
        } catch (Exception e) {
            Log.w(TAG, "Update check endpoint error: " + e.getMessage());
            JSONObject res = new JSONObject();
            try {
                res.put("status", "error");
                res.put("has_update", false);
                res.put("current_version", UpdateManager.getInstance(mContext).getCurrentVersion());
                res.put("error", e.getMessage());
            } catch (Exception ignored) {}
            return jsonResponse(res.toString());
        }
    }

    private Response handleUpdateDownload(Map<String, String> params) {
        String apkUrl = params.get("apk_url");
        String apkName = params.get("apk_name");
        if (apkUrl == null || apkUrl.isEmpty()) {
            return jsonResponse("{\"status\":\"error\",\"message\":\"Missing apk_url parameter\"}");
        }

        mUpdateStatus = "downloading";
        mUpdatePercent = 0;
        mUpdateBytesRead = 0;
        mUpdateTotalBytes = 0;
        mUpdateError = null;

        UpdateManager.getInstance(mContext).startDownloadAndInstall(apkUrl, apkName, new UpdateManager.DownloadProgressListener() {
            @Override
            public void onProgress(long bytesRead, long totalBytes, int percent) {
                mUpdateBytesRead = bytesRead;
                mUpdateTotalBytes = totalBytes;
                mUpdatePercent = percent;
                mUpdateStatus = "downloading";
            }

            @Override
            public void onCompleted(File apkFile) {
                mUpdatePercent = 100;
                mUpdateStatus = "completed";
            }

            @Override
            public void onError(String error) {
                mUpdateStatus = "error";
                mUpdateError = error;
            }
        });

        return jsonResponse("{\"status\":\"started\",\"message\":\"Downloading update in background\"}");
    }

    private Response handleUpdateProgress() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("status", mUpdateStatus);
            obj.put("percent", mUpdatePercent);
            obj.put("bytes_read", mUpdateBytesRead);
            obj.put("total_bytes", mUpdateTotalBytes);
            if (mUpdateTotalBytes > 0) {
                obj.put("progress_text", String.format("%.1f MB / %.1f MB", (mUpdateBytesRead / (1024.0 * 1024.0)), (mUpdateTotalBytes / (1024.0 * 1024.0))));
            }
            if (mUpdateError != null) {
                obj.put("error", mUpdateError);
            }
        } catch (Exception ignored) {}
        return jsonResponse(obj.toString());
    }

    private Response handleStreamRequest(IHTTPSession session, Map<String, String> params, Map<String, String> headers) {
        String hash = params.get("info_hash");
        if (hash == null || hash.isEmpty()) {
            hash = params.get("magnet");
        }
        if (hash == null || hash.isEmpty()) {
            String uri = session.getUri();
            if (uri.startsWith("/api/stream/")) {
                String sub = uri.substring("/api/stream/".length());
                int q = sub.indexOf('?');
                if (q != -1) sub = sub.substring(0, q);
                if (!sub.isEmpty()) {
                    hash = sub.trim();
                }
            }
        }
        if (hash == null || hash.isEmpty()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing info_hash or magnet");
        }

        // Retrieve full magnet with all trackers
        String magnetUri = mMagnetRegistry.get(hash.toLowerCase());
        if (magnetUri == null || magnetUri.isEmpty()) {
            magnetUri = mEngine.buildMagnetUri(hash);
        }

        // Prepare torrent stream if not already active (allow ample time for cold DHT swarms)
        boolean ready = mEngine.prepareStream(magnetUri, 45);
        if (!ready) {
            ready = mEngine.isMetadataReady();
        }
        if (!ready) {
            Response resp = newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "Buffering torrent metadata from swarm...");
            resp.addHeader("Retry-After", "1");
            return resp;
        }

        File targetFile = mEngine.getTargetFile();
        long totalLength = mEngine.getTargetFileSize();

        if (targetFile == null || totalLength <= 0) {
            Response resp = newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "Resolving target video file...");
            resp.addHeader("Retry-After", "1");
            return resp;
        }

        // Parse HTTP Range header from ExoPlayer (RFC 7233 compliant)
        long startByte = 0;
        long endByte = totalLength - 1;
        String rangeHeader = headers.get("range");
        boolean isRangeRequest = false;

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            String cleanRange = rangeHeader.substring(6).trim();
            int dashPos = cleanRange.indexOf('-');
            if (dashPos != -1) {
                isRangeRequest = true;
                String startPart = cleanRange.substring(0, dashPos).trim();
                String endPart = cleanRange.substring(dashPos + 1).trim();
                try {
                    if (!startPart.isEmpty() && !endPart.isEmpty()) {
                        startByte = Long.parseLong(startPart);
                        endByte = Long.parseLong(endPart);
                    } else if (!startPart.isEmpty() && endPart.isEmpty()) {
                        startByte = Long.parseLong(startPart);
                        endByte = totalLength - 1;
                    } else if (startPart.isEmpty() && !endPart.isEmpty()) {
                        // Suffix range: bytes=-N (request last N bytes)
                        long suffixLen = Long.parseLong(endPart);
                        startByte = Math.max(0, totalLength - suffixLen);
                        endByte = totalLength - 1;
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        // Validate range bounds (RFC 7233 416 Range Not Satisfiable)
        if (isRangeRequest && (startByte >= totalLength || startByte > endByte || startByte < 0)) {
            Response resp = newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "Requested Range Not Satisfiable");
            resp.addHeader("Content-Range", "bytes */" + totalLength);
            return resp;
        }

        if (endByte >= totalLength) {
            endByte = totalLength - 1;
        }

        long contentLength = endByte - startByte + 1;

        // Prioritize pieces starting at the requested byte offset (P0 immediate)
        mEngine.prioritizeByteOffset(startByte);

        // MIME Type Detection
        String mimeType = resolveMimeType(targetFile.getName());

        try {
            InputStream stream = new TorrentRangeInputStream(targetFile, mEngine, startByte, contentLength);
            Response.Status status = isRangeRequest ? Response.Status.PARTIAL_CONTENT : Response.Status.OK;
            Response response = newFixedLengthResponse(status, mimeType, stream, contentLength);
            if (isRangeRequest) {
                response.addHeader("Content-Range", "bytes " + startByte + "-" + endByte + "/" + totalLength);
            }
            response.addHeader("Accept-Ranges", "bytes");
            response.addHeader("Content-Length", String.valueOf(contentLength));
            response.addHeader("Connection", "keep-alive");
            response.addHeader("Access-Control-Allow-Origin", "*");
            return response;
        } catch (Exception e) {
            Log.e(TAG, "Error opening torrent range input stream: " + e.getMessage(), e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.getMessage());
        }
    }

    private Response handleStatusRequest() {
        TorrentStatus st = mEngine.getStatus();
        JSONObject json = new JSONObject();
        try {
            if (st != null) {
                json.put("state", st.state().name().toLowerCase());
                json.put("progress", (double) st.progress());
                json.put("download_rate", st.downloadRate());
                json.put("download_speed_mb", String.format("%.2f", (st.downloadRate() / (1024.0 * 1024.0))));
                json.put("upload_rate", st.uploadRate());
                json.put("num_peers", st.numPeers());
                json.put("num_seeds", st.numSeeds());
                json.put("total_done", st.totalDone());
            } else {
                json.put("state", "idle");
                json.put("progress", 0.0);
                json.put("download_speed_mb", "0.00");
                json.put("num_peers", 0);
                json.put("num_seeds", 0);
            }
        } catch (Exception ignored) {
        }
        return jsonResponse(json.toString());
    }

    private Response handleSettingsRequest(IHTTPSession session, Method method) {
        if (Method.POST.equals(method)) {
            try {
                Map<String, String> body = new HashMap<>();
                session.parseBody(body);
                String postData = body.get("postData");
                if (postData != null) {
                    JSONObject obj = new JSONObject(postData);
                    SharedPreferences.Editor ed = mPrefs.edit();
                    if (obj.has("real_debrid_token")) {
                        ed.putString("rd_token", obj.getString("real_debrid_token").trim());
                    }
                    if (obj.has("filter_adult")) {
                        ed.putBoolean("filter_adult", obj.getBoolean("filter_adult"));
                    }
                    if (obj.has("filter_parodies")) {
                        ed.putBoolean("filter_parodies", obj.getBoolean("filter_parodies"));
                    }
                    ed.apply();
                }
            } catch (Exception e) {
                Log.w(TAG, "Settings save error: " + e.getMessage());
            }
        }

        String rdToken = mPrefs.getString("rd_token", "");
        boolean filterAdult = mPrefs.getBoolean("filter_adult", true);
        boolean filterParodies = mPrefs.getBoolean("filter_parodies", true);

        JSONObject json = new JSONObject();
        try {
            json.put("real_debrid_configured", !rdToken.isEmpty());
            json.put("real_debrid_masked", rdToken.length() > 6 ? rdToken.substring(0, 4) + "..." : "");
            json.put("filter_adult", filterAdult);
            json.put("filter_parodies", filterParodies);
            json.put("message", "Settings updated successfully");
        } catch (Exception ignored) {
        }

        return jsonResponse(json.toString());
    }

    private Response handleHistoryRequest() {
        WatchHistoryRepository repo = WatchHistoryRepository.getInstance(mContext);
        JSONObject res = new JSONObject();
        try {
            res.put("status", "ok");
            res.put("items", repo.getHistoryArray(100));
        } catch (Exception ignored) {}
        return jsonResponse(res.toString());
    }

    private Response handleHistoryUpdateRequest(IHTTPSession session) {
        try {
            Map<String, String> files = new HashMap<>();
            session.parseBody(files);
            String postData = files.get("postData");
            if (postData != null) {
                JSONObject obj = new JSONObject(postData);
                String id = obj.optString("id");
                String name = obj.optString("name", obj.optString("title", ""));
                String type = obj.optString("type", "movie");
                String poster = obj.optString("poster", "");
                String year = obj.optString("year", "");
                String genres = obj.optString("genres", "");
                double rating = obj.optDouble("rating", 0.0);
                long pos = obj.optLong("position", 0);
                long dur = obj.optLong("duration", 7200);
                WatchHistoryRepository.getInstance(mContext).updateProgress(id, name, type, poster, year, genres, rating, pos, dur);
            }
        } catch (Throwable t) {
            Log.w(TAG, "History update error: " + t.getMessage());
        }
        return jsonResponse("{\"status\":\"ok\"}");
    }

    private Response handleHistoryRemoveRequest(IHTTPSession session) {
        try {
            Map<String, String> files = new HashMap<>();
            session.parseBody(files);
            String postData = files.get("postData");
            if (postData != null) {
                JSONObject obj = new JSONObject(postData);
                String id = obj.optString("id");
                if (!id.isEmpty()) {
                    WatchHistoryRepository.getInstance(mContext).removeItem(id);
                }
            }
        } catch (Throwable ignored) {}
        return jsonResponse("{\"status\":\"ok\"}");
    }

    private Response handleHistoryReactionRequest(IHTTPSession session) {
        try {
            Map<String, String> files = new HashMap<>();
            session.parseBody(files);
            String postData = files.get("postData");
            if (postData != null) {
                JSONObject obj = new JSONObject(postData);
                String id = obj.optString("id");
                int reaction = obj.optInt("reaction", 0);
                if (!id.isEmpty()) {
                    WatchHistoryRepository.getInstance(mContext).setReaction(id, reaction);
                }
            }
        } catch (Throwable ignored) {}
        return jsonResponse("{\"status\":\"ok\"}");
    }

    private Response handleRecommendationsRequest() {
        RecCacheEntry cached = mRecommendationsCache;
        if (cached != null && System.currentTimeMillis() < cached.expiresAt) {
            return jsonResponse(cached.json);
        }

        WatchHistoryRepository repo = WatchHistoryRepository.getInstance(mContext);
        List<String> topGenres = repo.getTopFavoriteGenres();
        Set<String> disliked = repo.getDislikedIds();
        Set<String> watched = repo.getWatchedIds();

        JSONArray recItems = new JSONArray();
        Set<String> addedIds = new HashSet<>();

        if (!topGenres.isEmpty()) {
            List<String> urls = new ArrayList<>();
            for (String genre : topGenres) {
                try {
                    urls.add(CINEMETA_BASE + "/catalog/movie/top/genre=" + URLEncoder.encode(genre, "UTF-8") + ".json");
                } catch (Exception ignored) {}
            }

            int poolSize = Math.min(4, Math.max(1, urls.size()));
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(poolSize);
            List<java.util.concurrent.Future<String>> futures = new ArrayList<>();
            for (final String u : urls) {
                futures.add(pool.submit(() -> httpGet(u)));
            }
            pool.shutdown();

            for (java.util.concurrent.Future<String> f : futures) {
                if (recItems.length() >= 25) break;
                try {
                    String json = f.get(3000, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (json != null) {
                        JSONObject root = new JSONObject(json);
                        JSONArray metas = root.optJSONArray("metas");
                        if (metas != null) {
                            for (int i = 0; i < metas.length() && recItems.length() < 25; i++) {
                                JSONObject m = metas.getJSONObject(i);
                                String mid = m.optString("id");
                                if (!mid.isEmpty() && !disliked.contains(mid) && !watched.contains(mid) && !addedIds.contains(mid)) {
                                    addedIds.add(mid);
                                    recItems.put(m);
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        }

        JSONObject res = new JSONObject();
        try {
            res.put("status", "ok");
            res.put("items", recItems);
            res.put("genres", new JSONArray(topGenres));
        } catch (Exception ignored) {}

        String outJson = res.toString();
        mRecommendationsCache = new RecCacheEntry(outJson, 15 * 60 * 1000L); // Cache 15 minutes
        return jsonResponse(outJson);
    }

    private Response jsonResponse(String json) {
        Response resp = newFixedLengthResponse(Response.Status.OK, "application/json", json);
        resp.addHeader("Access-Control-Allow-Origin", "*");
        resp.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        resp.addHeader("Access-Control-Allow-Headers", "*");
        return resp;
    }

    private Response serveAsset(String uri) {
        if (uri == null || uri.equals("/") || uri.isEmpty()) {
            uri = "/index.html";
        }
        if (uri.startsWith("/")) {
            uri = uri.substring(1);
        }

        int qIdx = uri.indexOf('?');
        if (qIdx != -1) {
            uri = uri.substring(0, qIdx);
        }

        String assetPath = "web/" + uri;
        AssetManager am = mContext.getAssets();
        try {
            InputStream is = am.open(assetPath);
            String mime = resolveMimeType(uri);
            Response resp = newChunkedResponse(Response.Status.OK, mime, is);
            String lower = uri.toLowerCase();
            if (lower.endsWith(".js") || lower.endsWith(".css") || lower.endsWith(".png")
                    || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".woff2")
                    || lower.endsWith(".woff") || lower.endsWith(".svg") || lower.endsWith(".ico")) {
                resp.addHeader("Cache-Control", "public, max-age=86400");
            } else {
                resp.addHeader("Cache-Control", "no-cache, no-store, must-revalidate");
            }
            resp.addHeader("Access-Control-Allow-Origin", "*");
            return resp;
        } catch (IOException e) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Asset not found: " + assetPath);
        }
    }

    private String resolveMimeType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html; charset=utf-8";
        if (lower.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (lower.endsWith(".css")) return "text/css; charset=utf-8";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".mp4") || lower.endsWith(".m4v")) return "video/mp4";
        if (lower.endsWith(".mkv")) return "video/x-matroska";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".ts") || lower.endsWith(".m2ts")) return "video/mp2t";
        if (lower.endsWith(".avi")) return "video/x-msvideo";
        if (lower.endsWith(".mov")) return "video/quicktime";
        if (lower.endsWith(".flv")) return "video/x-flv";
        if (lower.endsWith(".vtt")) return "text/vtt; charset=utf-8";
        if (lower.endsWith(".srt")) return "text/plain; charset=utf-8";
        return "application/octet-stream";
    }

    private String httpGet(String urlStr) {
        int redirects = 0;
        String curUrl = urlStr;
        while (redirects < 4) {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(curUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(12000);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
                conn.setRequestProperty("Accept", "application/json, text/plain, */*");
                conn.setRequestProperty("Accept-Encoding", "gzip, deflate");

                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = conn.getHeaderField("Location");
                    if (loc != null && !loc.isEmpty()) {
                        if (loc.startsWith("/")) {
                            URL base = new URL(curUrl);
                            loc = base.getProtocol() + "://" + base.getHost() + loc;
                        }
                        curUrl = loc;
                        redirects++;
                        conn.disconnect();
                        continue;
                    }
                }
                if (code == 200) {
                    String encoding = conn.getContentEncoding();
                    InputStream rawIs = conn.getInputStream();
                    InputStream is = ("gzip".equalsIgnoreCase(encoding))
                            ? new java.util.zip.GZIPInputStream(rawIs)
                            : rawIs;
                    InputStreamReader isr = new InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8);
                    char[] buffer = new char[8192];
                    StringBuilder sb = new StringBuilder();
                    int n;
                    while ((n = isr.read(buffer, 0, buffer.length)) != -1) {
                        sb.append(buffer, 0, n);
                    }
                    isr.close();
                    return sb.toString();
                } else {
                    Log.w(TAG, "httpGet response " + code + " for " + curUrl);
                    return null;
                }
            } catch (Exception e) {
                Log.w(TAG, "httpGet failed for " + curUrl + ": " + e.getMessage());
                return null;
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
        return null;
    }

    private static class TorrentRangeInputStream extends InputStream {
        private final File mFile;
        private final TorrentEngine mEngine;
        private final long mStartOffset;
        private final long mTotalLength;
        private long mCurrentOffset;
        private long mRafPosition = -1;
        private int mLastPrioritizedPiece = -1;
        private RandomAccessFile mRaf;
        private final byte[] mSingleByte = new byte[1]; // Reusable single-byte buffer to avoid heap allocations
        private volatile boolean mClosed = false;

        public TorrentRangeInputStream(File file, TorrentEngine engine, long startOffset, long totalLength) throws IOException {
            mFile = file;
            mEngine = engine;
            mStartOffset = startOffset;
            mTotalLength = totalLength;
            mCurrentOffset = startOffset;
            openFile();
        }

        private void openFile() throws IOException {
            if (mClosed) return;
            if (mFile.exists()) {
                mRaf = new RandomAccessFile(mFile, "r");
                mRaf.seek(mCurrentOffset);
                mRafPosition = mCurrentOffset;
            }
        }

        @Override
        public synchronized int read() throws IOException {
            int r = read(mSingleByte, 0, 1);
            return r == 1 ? (mSingleByte[0] & 0xFF) : -1;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            if (b == null) throw new NullPointerException("Buffer is null");
            if (off < 0 || len < 0 || len > b.length - off) throw new IndexOutOfBoundsException();
            if (mClosed) throw new IOException("Stream closed");

            while (true) {
                if (mClosed) {
                    throw new IOException("Stream closed");
                }
                if (mCurrentOffset >= mStartOffset + mTotalLength) {
                    return -1;
                }

                int pieceLength = mEngine.getPieceLength();
                if (pieceLength <= 0) {
                    throw new IOException("Torrent piece length not initialized");
                }

                int pieceIndex = (int) ((mEngine.getTargetFileOffset() + mCurrentOffset) / pieceLength);

                // Continuously maintain sliding lookahead window of 28 pieces ahead of playhead
                if (pieceIndex != mLastPrioritizedPiece) {
                    mLastPrioritizedPiece = pieceIndex;
                    mEngine.prioritizeSlidingWindow(pieceIndex, 28);
                }

                // Wait for piece to download if not ready yet - NEVER return sparse zero bytes!
                if (!mEngine.isPieceDownloaded(pieceIndex)) {
                    mEngine.prioritizeSlidingWindow(pieceIndex, 28);

                    long waitStart = System.currentTimeMillis();
                    boolean downloaded = false;
                    while (System.currentTimeMillis() - waitStart < 40000) {
                        if (mClosed) {
                            throw new IOException("Stream closed");
                        }
                        if (mEngine.getActiveHash() == null) {
                            throw new IOException("Stream stopped");
                        }
                        downloaded = mEngine.waitForPiece(pieceIndex, 350);
                        if (downloaded) break;
                        // Periodically reinforce urgent piece priorities every 2.5s
                        if ((System.currentTimeMillis() - waitStart) % 2500 < 350) {
                            mEngine.prioritizeSlidingWindow(pieceIndex, 30);
                        }
                    }
                    if (!downloaded) {
                        throw new IOException("Swarm buffering timeout at byte " + mCurrentOffset + " (piece " + pieceIndex + ")");
                    }
                }

                // Ensure file handle is open and ready
                if (mRaf == null) {
                    if (mFile.exists()) {
                        openFile();
                    } else {
                        long waitStart = System.currentTimeMillis();
                        while (mRaf == null) {
                            if (mClosed) {
                                throw new IOException("Stream closed");
                            }
                            if (mFile.exists()) {
                                openFile();
                                break;
                            }
                            if (System.currentTimeMillis() - waitStart > 15000) {
                                throw new IOException("Timeout waiting for target file on disk: " + mFile.getName());
                            }
                            try {
                                Thread.sleep(100);
                            } catch (InterruptedException e) {
                                throw new IOException("Stream interrupted while waiting for data file");
                            }
                        }
                    }
                }
                if (mRaf == null) {
                    throw new IOException("File not available: " + mFile.getAbsolutePath());
                }

                // Batch read across contiguous verified pieces in the lookahead pipeline:
                // Allows reading full 16KB/32KB/64KB arrays in one disk call without piece fragmentation
                long contiguousEndOffset = (long) (pieceIndex + 1) * pieceLength - mEngine.getTargetFileOffset();
                int checkPiece = pieceIndex + 1;
                while (contiguousEndOffset - mCurrentOffset < len && mEngine.isPieceDownloaded(checkPiece)) {
                    contiguousEndOffset = (long) (checkPiece + 1) * pieceLength - mEngine.getTargetFileOffset();
                    checkPiece++;
                }

                long remainingInContiguous = contiguousEndOffset - mCurrentOffset;
                long remainingInRange = (mStartOffset + mTotalLength) - mCurrentOffset;
                int bytesToRead = (int) Math.min(len, Math.min(remainingInRange, remainingInContiguous));

                if (bytesToRead <= 0) {
                    // Current piece boundary reached, loop forward to next piece
                    continue;
                }

                // Zero-overhead seek: only call OS file seek when playhead discontinues
                if (mRafPosition != mCurrentOffset) {
                    mRaf.seek(mCurrentOffset);
                    mRafPosition = mCurrentOffset;
                }

                int read = mRaf.read(b, off, bytesToRead);
                if (read > 0) {
                    mCurrentOffset += read;
                    mRafPosition += read;
                    return read;
                }

                // Robust retry loop: retry up to 5 times with backoff before declaring EOF
                for (int retry = 0; retry < 5; retry++) {
                    if (mClosed) {
                        throw new IOException("Stream closed");
                    }
                    try {
                        Thread.sleep(80);
                    } catch (Exception ignored) {}
                    if (mRafPosition != mCurrentOffset) {
                        mRaf.seek(mCurrentOffset);
                        mRafPosition = mCurrentOffset;
                    }
                    read = mRaf.read(b, off, bytesToRead);
                    if (read > 0) {
                        mCurrentOffset += read;
                        mRafPosition += read;
                        return read;
                    }
                }

                if (mCurrentOffset < mStartOffset + mTotalLength) {
                    throw new IOException("Unexpected EOF from disk at offset " + mCurrentOffset + " of " + (mStartOffset + mTotalLength));
                }
                return -1;
            }
        }

        @Override
        public void close() throws IOException {
            mClosed = true;
            if (mRaf != null) {
                try {
                    mRaf.close();
                } catch (Exception ignored) {
                }
                mRaf = null;
            }
        }
    }
}
