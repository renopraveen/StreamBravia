package com.streambravia.tv.engine;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lightweight, thread-safe repository tracking the last 100 watched movies & shows.
 * Runs on a dedicated background thread to guarantee zero impact on playback or UI performance.
 * Used for "Continue Watching" and intelligent taste-based Recommendations (IMDb/YTS/Cinemeta).
 */
public class WatchHistoryRepository {

    private static final String TAG = "StreamBravia-History";
    private static final String FILE_NAME = "streambravia_watch_history.json";
    private static final int MAX_ITEMS = 100;

    private static WatchHistoryRepository sInstance;

    private final Context mContext;
    private final File mHistoryFile;
    private final Map<String, JSONObject> mMemoryCache = new HashMap<>();
    private final Object mLock = new Object();
    private final ExecutorService mDiskExecutor = Executors.newSingleThreadExecutor();

    public static synchronized WatchHistoryRepository getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new WatchHistoryRepository(context.getApplicationContext());
        }
        return sInstance;
    }

    private WatchHistoryRepository(Context context) {
        mContext = context;
        mHistoryFile = new File(context.getFilesDir(), FILE_NAME);
        loadFromDisk();
    }

    private void loadFromDisk() {
        synchronized (mLock) {
            mMemoryCache.clear();
            if (!mHistoryFile.exists() || mHistoryFile.length() == 0) return;
            try {
                FileInputStream fis = new FileInputStream(mHistoryFile);
                byte[] data = new byte[(int) mHistoryFile.length()];
                int read = fis.read(data);
                fis.close();
                if (read > 0) {
                    String jsonStr = new String(data, 0, read, StandardCharsets.UTF_8);
                    JSONObject root = new JSONObject(jsonStr);
                    Iterator<String> keys = root.keys();
                    while (keys.hasNext()) {
                        String k = keys.next();
                        JSONObject obj = root.optJSONObject(k);
                        if (obj != null) {
                            mMemoryCache.put(k, obj);
                        }
                    }
                    Log.i(TAG, "⚡ Loaded " + mMemoryCache.size() + " items from persistent watch history");
                }
            } catch (Throwable t) {
                Log.w(TAG, "Error loading watch history: " + t.getMessage());
            }
        }
    }

    private void persistToDiskAsync() {
        mDiskExecutor.execute(() -> {
            try {
                JSONObject root = new JSONObject();
                synchronized (mLock) {
                    for (Map.Entry<String, JSONObject> entry : mMemoryCache.entrySet()) {
                        root.put(entry.getKey(), entry.getValue());
                    }
                }
                FileOutputStream fos = new FileOutputStream(mHistoryFile);
                fos.write(root.toString().getBytes(StandardCharsets.UTF_8));
                fos.flush();
                fos.close();
            } catch (Throwable t) {
                Log.w(TAG, "Error saving watch history: " + t.getMessage());
            }
        });
    }

    public void updateProgress(String id, String name, String type, String poster, String year,
                               String genresJson, double rating, long positionSec, long durationSec) {
        if (id == null || id.trim().isEmpty()) return;
        id = id.trim();

        synchronized (mLock) {
            JSONObject entry = mMemoryCache.get(id);
            if (entry == null) {
                entry = new JSONObject();
            }
            try {
                entry.put("id", id);
                entry.put("name", name != null ? name : "");
                entry.put("type", type != null ? type : "movie");
                entry.put("poster", poster != null ? poster : "");
                entry.put("year", year != null ? year : "");
                if (genresJson != null && !genresJson.isEmpty()) {
                    try {
                        entry.put("genres", new JSONArray(genresJson));
                    } catch (Exception e) {
                        JSONArray ga = new JSONArray();
                        for (String g : genresJson.split("[,•]")) {
                            if (!g.trim().isEmpty()) ga.put(g.trim());
                        }
                        entry.put("genres", ga);
                    }
                }
                if (rating > 0) entry.put("rating", rating);
                entry.put("position", positionSec);
                entry.put("duration", durationSec > 0 ? durationSec : 7200);
                entry.put("timestamp", System.currentTimeMillis());
                if (!entry.has("reaction")) {
                    entry.put("reaction", 0); // 0 = neutral, 1 = liked, -1 = disliked
                }

                mMemoryCache.put(id, entry);

                // Trim to MAX_ITEMS
                if (mMemoryCache.size() > MAX_ITEMS) {
                    List<JSONObject> list = new ArrayList<>(mMemoryCache.values());
                    Collections.sort(list, (a, b) -> Long.compare(b.optLong("timestamp", 0), a.optLong("timestamp", 0)));
                    for (int i = MAX_ITEMS; i < list.size(); i++) {
                        mMemoryCache.remove(list.get(i).optString("id"));
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "Error updating history progress: " + t.getMessage());
            }
        }
        persistToDiskAsync();
    }

    public void setReaction(String id, int reaction) {
        if (id == null) return;
        synchronized (mLock) {
            JSONObject entry = mMemoryCache.get(id);
            if (entry != null) {
                try {
                    entry.put("reaction", reaction); // 1 = liked, -1 = disliked, 0 = neutral
                    entry.put("timestamp", System.currentTimeMillis());
                } catch (Exception ignored) {}
            } else {
                entry = new JSONObject();
                try {
                    entry.put("id", id);
                    entry.put("reaction", reaction);
                    entry.put("timestamp", System.currentTimeMillis());
                    mMemoryCache.put(id, entry);
                } catch (Exception ignored) {}
            }
        }
        persistToDiskAsync();
    }

    public void removeItem(String id) {
        if (id == null) return;
        synchronized (mLock) {
            mMemoryCache.remove(id);
        }
        persistToDiskAsync();
    }

    public JSONArray getHistoryArray(int maxResults) {
        JSONArray arr = new JSONArray();
        synchronized (mLock) {
            List<JSONObject> list = new ArrayList<>(mMemoryCache.values());
            Collections.sort(list, (a, b) -> Long.compare(b.optLong("timestamp", 0), a.optLong("timestamp", 0)));
            int count = Math.min(maxResults, list.size());
            for (int i = 0; i < count; i++) {
                arr.put(list.get(i));
            }
        }
        return arr;
    }

    public Set<String> getDislikedIds() {
        Set<String> set = new HashSet<>();
        synchronized (mLock) {
            for (JSONObject obj : mMemoryCache.values()) {
                if (obj.optInt("reaction", 0) < 0) {
                    set.add(obj.optString("id"));
                }
            }
        }
        return set;
    }

    public Set<String> getWatchedIds() {
        Set<String> set = new HashSet<>();
        synchronized (mLock) {
            set.addAll(mMemoryCache.keySet());
        }
        return set;
    }

    public List<String> getTopFavoriteGenres() {
        Map<String, Integer> genreWeights = new HashMap<>();
        synchronized (mLock) {
            for (JSONObject obj : mMemoryCache.values()) {
                int reaction = obj.optInt("reaction", 0);
                if (reaction < 0) continue; // Skip disliked entries

                long pos = obj.optLong("position", 0);
                long dur = obj.optLong("duration", 1);
                double pct = dur > 0 ? (double) pos / dur : 0.0;

                int weight = 1;
                if (reaction > 0) weight += 4; // Thumbs up = 5x weight
                if (pct > 0.4) weight += 2;   // Watched >40% = +2 weight

                JSONArray genres = obj.optJSONArray("genres");
                if (genres != null) {
                    for (int i = 0; i < genres.length(); i++) {
                        String g = genres.optString(i, "").trim();
                        if (!g.isEmpty()) {
                            genreWeights.put(g, genreWeights.getOrDefault(g, 0) + weight);
                        }
                    }
                }
            }
        }

        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(genreWeights.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

        List<String> topGenres = new ArrayList<>();
        for (int i = 0; i < Math.min(3, sorted.size()); i++) {
            topGenres.add(sorted.get(i).getKey());
        }

        if (topGenres.isEmpty()) {
            topGenres.add("Action");
            topGenres.add("Sci-Fi");
            topGenres.add("Thriller");
        }
        return topGenres;
    }
}
