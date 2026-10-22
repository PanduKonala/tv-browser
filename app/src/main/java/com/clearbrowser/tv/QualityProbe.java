package com.clearbrowser.tv;

import android.net.Uri;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Picks the best playlist the page offers, not just the one its player loaded. Tube sites' players
 * often start on a reduced list (xvideos loads hls_low.m3u8: 480p and 360p, while hls.m3u8 next to
 * it has 1080p). Candidates: playlists mentioned in the page, on the stream's server, and the same
 * name without a "low" marker in the same folder. Each is fetched (they're small) and the one whose
 * best quality is highest wins; anything that fails or is slow just doesn't count. Loader thread.
 */
final class QualityProbe {
    private static final String TAG = "ClearPlayer";
    private static final Pattern RES = Pattern.compile("RESOLUTION=\\d+x(\\d+)");
    private static final Pattern DASH_H = Pattern.compile("height=\"(\\d+)\"");
    private static final Pattern LOW = Pattern.compile("(?i)[_-]low(?=[._-])");
    private static final int MAX_TRIES = 6;

    private QualityProbe() { }

    /** {@code url}, or a better playlist among the candidates. */
    static String best(String url, String currentText, List<String> alts, Map<String, String> headers, boolean localOk) {
        Set<String> tries = new LinkedHashSet<>();
        String sibling = withoutLow(url);
        if (sibling != null) tries.add(sibling);
        String host = Uri.parse(url).getHost();
        if (alts != null) for (String a : alts) {
            if (a != null && !a.equals(url) && host != null && host.equals(Uri.parse(a).getHost())) tries.add(a);
        }
        if (tries.isEmpty()) return url;
        int have = currentText != null ? maxHeight(currentText) : fetchHeight(url, headers, localOk);
        String best = url;
        int n = 0;
        for (String t : tries) {
            if (++n > MAX_TRIES) break;
            int h = fetchHeight(t, headers, localOk);
            if (Debug.ON) Log.i(TAG, "quality probe: " + h + "p in " + t);
            if (h > have) { have = h; best = t; }
        }
        if (!best.equals(url)) Log.i(TAG, "better quality list: " + have + "p in " + best);
        return best;
    }

    /** ".../hls_low.m3u8?x" -> ".../hls.m3u8?x"; null if the name has no low marker. */
    static String withoutLow(String url) {
        Uri u = Uri.parse(url);
        String name = u.getLastPathSegment();
        if (name == null) return null;
        Matcher m = LOW.matcher(name);
        if (!m.find()) return null;
        String better = m.replaceFirst("");
        String path = u.getEncodedPath();
        if (path == null) return null;
        String newPath = path.substring(0, path.length() - Uri.encode(name).length()) + Uri.encode(better);
        return u.buildUpon().encodedPath(newPath).build().toString();
    }

    private static int fetchHeight(String url, Map<String, String> headers, boolean localOk) {
        try {
            return maxHeight(new String(SafeHttp.getQuick(localOk, url, headers), StandardCharsets.UTF_8));
        } catch (Exception e) {
            if (Debug.ON) Log.i(TAG, "quality probe skipped " + url + ": " + e.getMessage());
            return -1;
        }
    }

    /** Highest variant height in an HLS master or DASH manifest; 0 for a single-quality playlist. */
    static int maxHeight(String text) {
        if (text == null || text.length() > 2_000_000) return 0;
        int best = 0;
        Matcher m = (text.contains("<MPD") ? DASH_H : RES).matcher(text);
        while (m.find()) {
            try { best = Math.max(best, Integer.parseInt(m.group(1))); } catch (NumberFormatException ignored) { }
        }
        return best;
    }

    /** Playlist addresses from a page scan (JSON array), as a list. */
    static List<String> list(String json) {
        List<String> out = new ArrayList<>();
        try {
            org.json.JSONArray a = new org.json.JSONArray(json == null ? "[]" : json);
            for (int i = 0; i < a.length() && out.size() < 30; i++) {
                String s = a.optString(i);
                if (s.startsWith("http")) out.add(s);
            }
        } catch (Exception ignored) { }
        return out;
    }
}
