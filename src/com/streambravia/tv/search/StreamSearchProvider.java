package com.streambravia.tv.search;

import android.app.SearchManager;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.provider.BaseColumns;
import android.util.Log;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * ContentProvider for Amazon Fire TV & Android TV Voice / Global Search integration.
 * Allows the Fire TV Stick Alexa voice search remote button to query StreamBravia
 * and display rich search suggestions (titles, release year, IMDb ratings, and poster art)
 * directly in Fire OS voice search results.
 */
public class StreamSearchProvider extends ContentProvider {

    private static final String TAG = "StreamSearchProvider";
    public static final String AUTHORITY = "com.streambravia.tv.search";

    private static final String[] SEARCH_SUGGEST_COLUMNS = {
            BaseColumns._ID,
            SearchManager.SUGGEST_COLUMN_TEXT_1,
            SearchManager.SUGGEST_COLUMN_TEXT_2,
            SearchManager.SUGGEST_COLUMN_ICON_1,
            SearchManager.SUGGEST_COLUMN_INTENT_DATA_ID,
            SearchManager.SUGGEST_COLUMN_INTENT_DATA,
            SearchManager.SUGGEST_COLUMN_INTENT_ACTION,
            SearchManager.SUGGEST_COLUMN_SHORTCUT_ID,
            SearchManager.SUGGEST_COLUMN_CONTENT_TYPE,
            SearchManager.SUGGEST_COLUMN_PRODUCTION_YEAR,
            SearchManager.SUGGEST_COLUMN_DURATION
    };

    private static class SearchItem {
        final String id;
        final String title;
        final String subtitle;
        final String poster;
        final String year;

        SearchItem(String id, String title, String subtitle, String poster, String year) {
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
            this.poster = poster;
            this.year = year;
        }
    }

    private static final LruCache<String, List<SearchItem>> sSearchCache = new LruCache<>(50);

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        String query = null;

        if (selectionArgs != null && selectionArgs.length > 0 && selectionArgs[0] != null) {
            query = selectionArgs[0].trim();
        } else if (uri != null && uri.getPathSegments().size() > 1) {
            String seg = uri.getLastPathSegment();
            if (seg != null && !seg.equalsIgnoreCase("search_suggest_query")) {
                query = seg.trim();
            }
        }

        if (query == null || query.isEmpty()) {
            return new MatrixCursor(SEARCH_SUGGEST_COLUMNS);
        }

        List<SearchItem> items = getSearchResults(query);
        MatrixCursor cursor = new MatrixCursor(SEARCH_SUGGEST_COLUMNS, items.size());

        for (int i = 0; i < items.size(); i++) {
            SearchItem it = items.get(i);
            cursor.addRow(new Object[] {
                    (long) (i + 1),
                    it.title,
                    it.subtitle,
                    it.poster,
                    it.id,
                    "streambravia://item/" + it.id,
                    Intent.ACTION_VIEW,
                    it.id,
                    "video/mp4",
                    it.year,
                    7200000L
            });
        }

        return cursor;
    }

    private List<SearchItem> getSearchResults(String query) {
        String cacheKey = query.toLowerCase();
        List<SearchItem> cached = sSearchCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        List<SearchItem> results = new ArrayList<>();
        try {
            // 1. Search Movies on Cinemeta
            searchCinemetaCatalog("movie", query, results);

            // 2. Search Series on Cinemeta if few results
            if (results.size() < 8) {
                searchCinemetaCatalog("series", query, results);
            }

            sSearchCache.put(cacheKey, results);
        } catch (Exception e) {
            Log.w(TAG, "Search query error for: " + query + " - " + e.getMessage());
        }

        return results;
    }

    private void searchCinemetaCatalog(String type, String query, List<SearchItem> outResults) {
        try {
            String encoded = URLEncoder.encode(query, "UTF-8");
            String urlStr = "https://v3-cinemeta.strem.io/catalog/" + type + "/top/search=" + encoded + ".json";

            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(2500);
            conn.setRequestProperty("User-Agent", "StreamBraviaTV/1.0");

            if (conn.getResponseCode() == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();

                JSONObject root = new JSONObject(sb.toString());
                JSONArray metas = root.optJSONArray("metas");
                if (metas != null) {
                    for (int i = 0; i < metas.length() && outResults.size() < 25; i++) {
                        JSONObject m = metas.getJSONObject(i);
                        String id = m.optString("id", "");
                        String name = m.optString("name", "");
                        if (id.isEmpty() || name.isEmpty()) continue;

                        String year = m.optString("year", m.optString("releaseInfo", ""));
                        String rating = m.optString("imdbRating", "");
                        String poster = m.optString("poster", "");

                        StringBuilder sub = new StringBuilder();
                        if (!year.isEmpty()) sub.append(year).append(" • ");
                        if (!rating.isEmpty()) sub.append("★ ").append(rating).append(" • ");
                        sub.append("series".equals(type) ? "TV Series" : "Movie");

                        outResults.add(new SearchItem(id, name, sub.toString(), poster, year));
                    }
                }
            }
            conn.disconnect();
        } catch (Exception ignored) {}
    }

    @Override
    public String getType(Uri uri) {
        return SearchManager.SUGGEST_MIME_TYPE;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
