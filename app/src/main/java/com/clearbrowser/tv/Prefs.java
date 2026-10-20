package com.clearbrowser.tv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Settings and per-site choices, kept in SharedPreferences. */
final class Prefs {
    static final String BOOKMARKS_PAGE = "file:///android_asset/bookmarks.html";
    static final String GOOGLE = "https://www.google.com/";

    private final SharedPreferences sp;
    // Read on WebView IO threads, so keep immutable snapshots and swap them on change.
    private volatile Set<String> userBlocked;
    private volatile Set<String> allowedPopups;
    private volatile Set<String> shieldsOff;

    Prefs(Context ctx) {
        sp = ctx.getSharedPreferences("clear", Context.MODE_PRIVATE);
        userBlocked = load("user_blocked");
        allowedPopups = load("allowed_popups");
        shieldsOff = load("shields_off");
    }

    private Set<String> load(String key) {
        return Collections.unmodifiableSet(new HashSet<>(sp.getStringSet(key, Collections.emptySet())));
    }

    private Set<String> edit(String key, Set<String> cur, String value, boolean add) {
        Set<String> next = new HashSet<>(cur);
        if (add) next.add(value); else next.remove(value);
        sp.edit().putStringSet(key, next).apply();
        return Collections.unmodifiableSet(next);
    }

    String home() { return sp.getString("home", GOOGLE); }
    void setHome(String url) { sp.edit().putString("home", url).apply(); }

    boolean autoplay() { return sp.getBoolean("autoplay", false); }
    void setAutoplay(boolean on) { sp.edit().putBoolean("autoplay", on).apply(); }

    boolean tvPlayer() { return sp.getBoolean("tv_player", true); }
    void setTvPlayer(boolean on) { sp.edit().putBoolean("tv_player", on).apply(); }

    boolean desktop() { return sp.getBoolean("desktop", false); }
    void setDesktop(boolean on) { sp.edit().putBoolean("desktop", on).apply(); }

    long totalBlocked() { return sp.getLong("total_blocked", 0); }
    void addBlocked(int n) { sp.edit().putLong("total_blocked", totalBlocked() + n).apply(); }

    boolean isUserBlocked(String domain) { return userBlocked.contains(domain); }
    void blockSite(String site) {
        userBlocked = edit("user_blocked", userBlocked, site, true);
        Blocker.get().userListChanged();
    }

    boolean popupsAllowed(String site) { return allowedPopups.contains(site); }
    void allowPopups(String site) { allowedPopups = edit("allowed_popups", allowedPopups, site, true); }

    boolean shieldsUp(String site) { return site == null || site.isEmpty() || !shieldsOff.contains(site); }
    void setShields(String site, boolean up) { shieldsOff = edit("shields_off", shieldsOff, site, !up); }

    void resetSiteChoices() {
        sp.edit().remove("user_blocked").remove("allowed_popups").remove("shields_off").apply();
        userBlocked = allowedPopups = shieldsOff = Collections.emptySet();
        Blocker.get().userListChanged();
    }

    JSONArray bookmarks() {
        try { return new JSONArray(sp.getString("bookmarks", "[]")); }
        catch (JSONException e) { return new JSONArray(); }
    }

    boolean isBookmarked(String url) {
        JSONArray b = bookmarks();
        for (int i = 0; i < b.length(); i++) if (url.equals(b.optJSONObject(i).optString("u"))) return true;
        return false;
    }

    /** Adds the page, or removes it if it's already there. Returns true when it's now bookmarked. */
    boolean toggleBookmark(String url, String title) {
        JSONArray b = bookmarks(), next = new JSONArray();
        boolean removed = false;
        for (int i = 0; i < b.length(); i++) {
            JSONObject o = b.optJSONObject(i);
            if (url.equals(o.optString("u"))) removed = true; else next.put(o);
        }
        if (!removed) {
            try { next.put(new JSONObject().put("u", url).put("t", title == null || title.isEmpty() ? url : title)); }
            catch (JSONException ignored) { }
        }
        sp.edit().putString("bookmarks", next.toString()).apply();
        return !removed;
    }
}
