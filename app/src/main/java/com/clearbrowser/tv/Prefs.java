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
    /** The built-in home page: search, bookmarks, recently visited. Loads instantly, no network. */
    static final String HOME_PAGE = "file:///android_asset/home.html";
    private static final int MAX_RECENT = 12, MAX_ICONS = 60;

    static boolean isHome(String url) { return url != null && url.startsWith(HOME_PAGE); }

    private final SharedPreferences sp;
    /** Site icons as small PNG data URIs, by host, so the home page shows them offline. */
    private final SharedPreferences icons;
    // Read on WebView IO threads, so keep immutable snapshots and swap them on change.
    private volatile Set<String> userBlocked;
    private volatile Set<String> allowedPopups;
    private volatile Set<String> shieldsOff;

    Prefs(Context ctx) {
        sp = ctx.getSharedPreferences("clear", Context.MODE_PRIVATE);
        icons = ctx.getSharedPreferences("icons", Context.MODE_PRIVATE);
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

    String home() { return sp.getString("home", HOME_PAGE); }
    void setHome(String url) { sp.edit().putString("home", url).apply(); }

    /** Subtitle choice carried to the next video: a language code, "off", or "" (player default). */
    String subtitleLanguage() { return sp.getString("sub_lang", ""); }
    void setSubtitleLanguage(String lang) { sp.edit().putString("sub_lang", lang).apply(); }
    /** 0 small, 1 medium, 2 large, 3 extra large. */
    int subtitleSize() { return sp.getInt("sub_size", 2); }
    void setSubtitleSize(int s) { sp.edit().putInt("sub_size", s).apply(); }

    /** 0 white + outline, 1 yellow + outline, 2 white on dark box, 3 yellow on dark box. */
    int subtitleStyle() { return sp.getInt("sub_style", 0); }
    void setSubtitleStyle(int s) { sp.edit().putInt("sub_style", s).apply(); }
    /** 0 bottom, 1 raised. */
    int subtitlePosition() { return sp.getInt("sub_pos", 0); }
    void setSubtitlePosition(int p) { sp.edit().putInt("sub_pos", p).apply(); }

    /** TV player: tunneled playback (the TV's video hardware does frame timing and A/V sync). */
    boolean tunneling() { return sp.getBoolean("tunneling", true); }
    void setTunneling(boolean on) { sp.edit().putBoolean("tunneling", on).apply(); }

    /** Block-list updates: when the current set was fetched, and when the next is due. */
    long filtersUpdated() { return sp.getLong("filters_updated", 0); }
    long filtersNextUpdate() {
        // First run: the lists in the APK are as old as the build; next one due 4 days after it.
        return sp.getLong("filters_next", Math.max(BuildConfig.FILTERS_BUILT, filtersUpdated()) + 4 * 24 * 3_600_000L);
    }
    void setFiltersNextUpdate(long t) { sp.edit().putLong("filters_next", t).apply(); }
    void setFiltersUpdated(long when, long next) { sp.edit().putLong("filters_updated", when).putLong("filters_next", next).apply(); }

    /** Smoother scrolling: skip frosted-glass blur effects the TV's GPU can't afford. */
    boolean liteEffects() { return sp.getBoolean("lite_effects", true); }
    void setLiteEffects(boolean on) { sp.edit().putBoolean("lite_effects", on).apply(); }

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

    JSONArray recents() {
        try { return new JSONArray(sp.getString("recent", "[]")); }
        catch (JSONException e) { return new JSONArray(); }
    }

    /** Newest first, one entry per address. */
    void addRecent(String url, String title) {
        JSONArray old = recents(), next = new JSONArray();
        try { next.put(new JSONObject().put("u", url).put("t", title == null || title.isEmpty() ? url : title)); }
        catch (JSONException ignored) { }
        for (int i = 0; i < old.length() && next.length() < MAX_RECENT; i++) {
            JSONObject o = old.optJSONObject(i);
            if (o != null && !url.equals(o.optString("u"))) next.put(o);
        }
        sp.edit().putString("recent", next.toString()).apply();
    }

    void clearRecents() { sp.edit().remove("recent").apply(); }

    /** Drops one entry from Recently visited, and the site's saved icon if nothing else of it is kept. */
    void removeRecent(String url) {
        JSONArray old = recents(), next = new JSONArray();
        for (int i = 0; i < old.length(); i++) {
            JSONObject o = old.optJSONObject(i);
            if (o != null && !url.equals(o.optString("u"))) next.put(o);
        }
        sp.edit().putString("recent", next.toString()).apply();
        String host = android.net.Uri.parse(url).getHost();
        if (host == null) return;
        JSONArray marks = bookmarks();
        for (JSONArray list : new JSONArray[]{next, marks}) {
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.optJSONObject(i);
                if (o != null && host.equals(android.net.Uri.parse(o.optString("u")).getHost())) return;
            }
        }
        icons.edit().remove(host).apply();
    }

    String icon(String host) { return host == null ? null : icons.getString(host, null); }

    void putIcon(String host, String dataUri) {
        if (host == null || host.isEmpty()) return;
        SharedPreferences.Editor e = icons.edit().putString(host, dataUri);
        if (icons.getAll().size() >= MAX_ICONS) e.clear().putString(host, dataUri); // simple cap: start over
        e.apply();
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
