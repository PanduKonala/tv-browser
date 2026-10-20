package com.clearbrowser.tv;

import android.content.Context;
import android.os.Process;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.HttpsURLConnection;

/**
 * Keeps the block lists fresh without reinstalling, the way uBlock Origin / Adblock Plus do:
 * honour the lists' own "Expires" (they say 4 days; 5 if none), clamped to 1-14 days; HTTPS only;
 * if anything fails keep the last good copy and retry in an hour; and swap in the new set in one
 * step, so blocking never has a gap. Pop-up networks change domains every few weeks.
 */
final class FilterUpdater {
    private static final String TAG = "ClearFilters";
    private static final long HOUR = 3_600_000L, DAY = 24 * HOUR;
    private static final AtomicBoolean running = new AtomicBoolean();

    interface Done { void done(boolean ok, String message); }

    private FilterUpdater() { }

    static boolean due(Prefs prefs) {
        return System.currentTimeMillis() >= prefs.filtersNextUpdate();
    }

    /** Runs in the background if due (or if forced); calls back on that background thread. */
    static void run(Context ctx, Prefs prefs, boolean force, Done done) {
        if (!force && !due(prefs)) return;
        if (!running.compareAndSet(false, true)) { if (done != null) done.done(false, "already updating"); return; }
        Context app = ctx.getApplicationContext();
        Thread t = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            File base = app.getFilesDir();
            File fresh = new File(base, "filters.new"), live = new File(base, "filters"), old = new File(base, "filters.old");
            long t0 = System.currentTimeMillis();
            try {
                delete(fresh);
                FilterCompiler.Result r = FilterCompiler.compile(FilterUpdater::open, fresh);
                // Sanity check before trusting it: a truncated download must not shrink the lists.
                if (r.listsRead != FilterCompiler.LISTS.length || r.domains < 50_000 || r.generic < 5_000 || r.cosmeticSites < 10_000) {
                    throw new IOException("lists look incomplete: " + r.domains + " domains, " + r.generic + " generic");
                }
                delete(old);
                if (live.exists() && !live.renameTo(old)) throw new IOException("can't move old lists");
                if (!fresh.renameTo(live)) { old.renameTo(live); throw new IOException("can't move new lists"); }
                Blocker.get().reload(app);
                delete(old); // still-mapped old files stay readable until released
                long hours = r.expiresHours > 0 ? r.expiresHours : 5 * 24;
                long interval = Math.max(DAY, Math.min(14 * DAY, hours * HOUR));
                prefs.setFiltersUpdated(System.currentTimeMillis(), System.currentTimeMillis() + interval);
                String msg = r.domains + " ad domains, " + r.generic + " hiding rules";
                Log.i(TAG, "updated in " + (System.currentTimeMillis() - t0) / 1000 + " s: " + msg + ", next in " + interval / HOUR + " h");
                if (done != null) done.done(true, msg);
            } catch (Throwable e) {
                delete(fresh);
                prefs.setFiltersNextUpdate(System.currentTimeMillis() + HOUR);
                Log.w(TAG, "update failed, keeping current lists: " + e);
                if (done != null) done.done(false, String.valueOf(e.getMessage()));
            } finally {
                running.set(false);
            }
        }, "filter-update");
        t.setDaemon(true);
        t.start();
    }

    private static BufferedReader open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        if (!(c instanceof HttpsURLConnection)) throw new IOException("not HTTPS: " + url);
        c.setConnectTimeout(20_000);
        c.setReadTimeout(30_000);
        c.setRequestProperty("User-Agent", "ClearBrowser-TV filter update");
        int code = c.getResponseCode();
        if (code != 200) throw new IOException("HTTP " + code + " for " + url);
        return new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8), 1 << 16);
    }

    private static void delete(File f) {
        if (f.isDirectory()) { File[] kids = f.listFiles(); if (kids != null) for (File k : kids) delete(k); }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
