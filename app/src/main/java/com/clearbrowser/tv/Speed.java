package com.clearbrowser.tv;

import android.content.Context;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.util.Log;
import android.webkit.WebView;

import androidx.webkit.PrefetchException;
import androidx.webkit.Profile;
import androidx.webkit.WebViewOutcomeReceiver;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Page-load speed-ups, after what Chrome does on low-end phones:
 *  - a bigger on-disk HTTP cache (WebView's default is 20 MB), so repeat visits read pages, images
 *    and compiled scripts from storage instead of the network, without costing RAM;
 *  - Chrome's "loading predictor": remember which servers each site pulls from, and on the next
 *    visit open connections to all of them (DNS + TLS) the moment navigation starts, in parallel;
 *  - starting the page renderer while the app opens, before the first page is asked for.
 */
@androidx.annotation.OptIn(markerClass = {Profile.ExperimentalPreconnect.class,
        Profile.ExperimentalWarmUpRendererProcess.class, Profile.ExperimentalUrlPrefetch.class})
final class Speed {
    private static final String TAG = "ClearSpeed";
    /** Disk cache: up to 1 GB, but always leave this much storage free for the TV itself. */
    private static final long CACHE_MAX = 1024L * 1024 * 1024, KEEP_FREE = 300L * 1024 * 1024,
            CACHE_MIN = 64L * 1024 * 1024;
    private static final int ORIGINS_PER_SITE = 12, MAX_SITES = 300, MIN_SEEN = 2;

    private final File store;
    private final ExecutorService bg = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "clear-speed");
        t.setDaemon(true);
        return t;
    });
    /** site -> origin -> number of page loads that used it. Touched on IO threads: synchronized. */
    private final Map<String, Map<String, Integer>> learned = new HashMap<>();
    /** Origins already counted for the page that's loading. */
    private final Set<String> thisPage = new HashSet<>();
    private String thisSite = "";
    private Profile profile;
    private boolean canPreconnect, canPrefetch;
    private final java.util.LinkedHashSet<String> prefetched = new java.util.LinkedHashSet<>();

    Speed(Context ctx) {
        store = new File(ctx.getFilesDir(), "learned-origins.json");
        bg.execute(this::load);
    }

    /** Once, with the first WebView (on the UI thread, before its first load). */
    void setUp(Context ctx, WebView web) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            Log.i(TAG, "no profile API in this WebView; preconnect falls back to DNS warm-up");
            return;
        }
        profile = WebViewCompat.getProfile(web);
        if (WebViewFeature.isFeatureSupported(WebViewFeature.HTTP_CACHE_MANAGER)) {
            // What's free now plus what the cache already holds, minus the reserve, capped at 1 GB.
            // Measuring the cache means walking its files, so do that off the UI thread.
            final Profile p = profile;
            final File cacheDir = ctx.getCacheDir();
            final Handler ui = new Handler(Looper.getMainLooper());
            bg.execute(() -> {
                long free = new StatFs(cacheDir.getPath()).getAvailableBytes();
                long quota = Math.max(CACHE_MIN, Math.min(CACHE_MAX, free + sizeOf(cacheDir) - KEEP_FREE));
                ui.post(() -> {
                    try {
                        p.getHttpCache().setQuotaBytes(quota);
                        Log.i(TAG, "HTTP cache quota " + (p.getHttpCache().getQuotaBytes() >> 20) + " MB (default "
                                + (p.getHttpCache().getDefaultQuotaBytes() >> 20) + " MB, free " + (free >> 20) + " MB)");
                    } catch (RuntimeException e) {
                        Log.w(TAG, "HTTP cache quota not applied", e);
                    }
                });
            });
        }
        canPrefetch = WebViewFeature.isFeatureSupported(WebViewFeature.PROFILE_URL_PREFETCH);
        canPreconnect = WebViewFeature.isFeatureSupported(WebViewFeature.PRECONNECT);
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WARM_UP_RENDERER_PROCESS)) {
            try { profile.warmUpRendererProcess(); } catch (RuntimeException ignored) { }
        }
        Log.i(TAG, "preconnect=" + canPreconnect + " prefetch=" + canPrefetch + " warmup="
                + WebViewFeature.isFeatureSupported(WebViewFeature.WARM_UP_RENDERER_PROCESS));
    }

    /** Navigation to {@code url} is starting: connect to it and to the servers its site used before. */
    void navigating(String url) {
        if (url == null || !url.startsWith("http")) return;
        Uri u = Uri.parse(url);
        if (u.getHost() == null) return;
        List<String> origins = new ArrayList<>();
        origins.add(u.getScheme() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : ""));
        origins.addAll(predicted(Blocker.siteOf(u.getHost())));
        if (canPreconnect && profile != null) {
            for (String o : origins) {
                try { profile.preconnect(o); } catch (RuntimeException e) { canPreconnect = false; break; }
            }
        }
        if (!canPreconnect) {
            // Older WebView: at least get the (slow, DNS-over-TLS) lookups done ahead, in parallel.
            for (String o : origins) bg.execute(() -> {
                try { InetAddress.getAllByName(Uri.parse(o).getHost()); } catch (Exception ignored) { }
            });
        }
    }

    /**
     * The pointer is resting on (or pressing) a link: fetch its page into the cache now, so the
     * click lands on a warm response. HTML only (WebView's prefetch), never scripts or images.
     */
    void prefetch(String url) {
        if (!canPrefetch || profile == null || url == null || !url.startsWith("https://")) return;
        synchronized (prefetched) {
            if (!prefetched.add(url)) return;
            if (prefetched.size() > 40) prefetched.remove(prefetched.iterator().next());
        }
        try {
            profile.prefetchUrlAsync(url, new CancellationSignal(), bg, new WebViewOutcomeReceiver<Void, PrefetchException>() {
                @Override public void onResult(Void v) { Log.i(TAG, "prefetched " + url); }
                @Override public void onError(PrefetchException e) { Log.i(TAG, "prefetch skipped " + url + ": " + e.getMessage()); }
            });
        } catch (RuntimeException e) {
            canPrefetch = false;
        }
    }

    private static long sizeOf(File f) {
        if (f.isFile()) return f.length();
        long n = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) n += sizeOf(k);
        return n;
    }

    private List<String> predicted(String site) {
        List<Map.Entry<String, Integer>> list;
        synchronized (learned) {
            Map<String, Integer> m = learned.get(site);
            if (m == null) return Collections.emptyList();
            list = new ArrayList<>(m.entrySet());
        }
        list.sort((a, b) -> b.getValue() - a.getValue());
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : list) {
            if (e.getValue() < MIN_SEEN || out.size() >= ORIGINS_PER_SITE) break;
            out.add(e.getKey());
        }
        return out;
    }

    /** A new page started on {@code host}. */
    void pageStarted(String host) {
        synchronized (learned) {
            thisSite = Blocker.siteOf(host);
            thisPage.clear();
        }
    }

    /** IO thread: the page fetched something (not blocked) from {@code origin}. */
    void used(String scheme, String host) {
        if (host == null || scheme == null) return;
        String origin = scheme + "://" + host;
        synchronized (learned) {
            if (thisSite.isEmpty() || !thisPage.add(origin)) return;
            Map<String, Integer> m = learned.get(thisSite);
            if (m == null) {
                if (learned.size() >= MAX_SITES) learned.remove(learned.keySet().iterator().next());
                m = new HashMap<>();
                learned.put(thisSite, m);
            }
            Integer n = m.get(origin);
            m.put(origin, n == null ? 1 : n + 1);
            if (m.size() > ORIGINS_PER_SITE * 4) { // forget the rarely used ones
                Iterator<Map.Entry<String, Integer>> it = m.entrySet().iterator();
                while (it.hasNext()) if (it.next().getValue() < MIN_SEEN) it.remove();
            }
        }
    }

    void save() {
        final String json;
        synchronized (learned) {
            JSONObject root = new JSONObject();
            try {
                for (Map.Entry<String, Map<String, Integer>> e : learned.entrySet()) root.put(e.getKey(), new JSONObject(e.getValue()));
            } catch (Exception ignored) { }
            json = root.toString();
        }
        bg.execute(() -> {
            File tmp = new File(store.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                return;
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(store);
        });
    }

    void forget() {
        synchronized (learned) { learned.clear(); }
        //noinspection ResultOfMethodCallIgnored
        store.delete();
    }

    private void load() {
        if (!store.exists()) return;
        try (FileInputStream in = new FileInputStream(store)) {
            byte[] b = new byte[(int) store.length()];
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            JSONObject root = new JSONObject(new String(b, 0, off, StandardCharsets.UTF_8));
            synchronized (learned) {
                for (Iterator<String> it = root.keys(); it.hasNext(); ) {
                    String site = it.next();
                    JSONObject o = root.getJSONObject(site);
                    Map<String, Integer> m = new HashMap<>();
                    for (Iterator<String> k = o.keys(); k.hasNext(); ) {
                        String origin = k.next();
                        m.put(origin, o.getInt(origin));
                    }
                    learned.put(site, m);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "learned origins unreadable, starting fresh", e);
        }
    }
}
