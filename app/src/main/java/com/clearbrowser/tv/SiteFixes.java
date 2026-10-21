package com.clearbrowser.tv;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The app's own annoyance fixes (assets/site_fixes.txt), kept apart from the downloaded lists so
 * filter updates never drop them. Only uBlock Origin's set-local-storage-item is supported: it
 * records a "dismissed" flag the way the site's own close button would.
 */
final class SiteFixes {
    private static final Pattern RULE = Pattern.compile(
            "^([a-z0-9.-]+)##\\+js\\(set-local-storage-item,\\s*([^,]+?),\\s*([^)]*?)\\s*\\)$");
    /** host -> [[key, value], ...] as JSON, ready for inject.js. */
    private static final Map<String, String> BY_HOST = new HashMap<>();

    private SiteFixes() { }

    static synchronized void init(Context ctx) {
        if (!BY_HOST.isEmpty()) return;
        Map<String, JSONArray> rules = new HashMap<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                ctx.getAssets().open("site_fixes.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                Matcher m = RULE.matcher(line.trim());
                if (!m.matches()) continue;
                JSONArray item = new JSONArray().put(m.group(2)).put(m.group(3));
                rules.computeIfAbsent(m.group(1), k -> new JSONArray()).put(item);
            }
        } catch (Exception e) {
            Log.w("ClearFilters", "site fixes unreadable: " + e.getMessage());
        }
        for (Map.Entry<String, JSONArray> e : rules.entrySet()) BY_HOST.put(e.getKey(), e.getValue().toString());
    }

    /** Storage items for a frame on {@code host} (its own rules and its parent domains'), or "". */
    static String storageFor(String host) {
        if (host == null || BY_HOST.isEmpty()) return "";
        String h = host.toLowerCase();
        StringBuilder out = null;
        while (!h.isEmpty()) {
            String items = BY_HOST.get(h);
            if (items != null) {
                String inner = items.substring(1, items.length() - 1);
                out = out == null ? new StringBuilder(inner) : out.append(',').append(inner);
            }
            int dot = h.indexOf('.');
            if (dot < 0) break;
            h = h.substring(dot + 1);
        }
        return out == null ? "" : "[" + out + "]";
    }
}
