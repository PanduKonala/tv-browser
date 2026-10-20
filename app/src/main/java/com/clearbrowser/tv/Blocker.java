package com.clearbrowser.tv;

import android.content.Context;
import android.util.LruCache;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Filter lists compiled by tools/build_filters.py: blocked domains (network) and element hiding
 * (cosmetic). The lists stay memory-mapped in the APK (see {@link SortedLines}), so the ~112k
 * domains and ~45k hiding rules cost almost no heap, and lookups are safe from any thread.
 */
final class Blocker {
    private static final String HIDE = "{display:none!important}\n";
    private static final Blocker INSTANCE = new Blocker();

    static Blocker get() { return INSTANCE; }

    private volatile SortedLines domains, generic, cosmetic;
    /**
     * Generic hiding index, in the heap (~1.5 MB). Pages ask for it synchronously from their main
     * thread, and on a TV that's always swapping, faulting mapped pages back in there stalled
     * rendering (220 ms on one cold page load). Null until loaded; the mapped file serves meanwhile.
     */
    private volatile Map<String, String> genericHeap;
    /** Recent answers per host: a page asks about the same few hosts hundreds of times. */
    private final ConcurrentHashMap<String, Boolean> hostCache = new ConcurrentHashMap<>();
    private String genericAlways = "";
    private final LruCache<String, String> siteCss = new LruCache<>(16);

    /** Just mmap calls: cheap enough for Application.onCreate. */
    synchronized void init(Context ctx) {
        if (domains != null) return;
        Context app = ctx.getApplicationContext();
        generic = SortedLines.map(app, "filters/generic.tsv");
        cosmetic = SortedLines.map(app, "filters/cosmetic.tsv");
        genericAlways = rules(generic.value("*"));
        domains = SortedLines.map(app, "filters/domains.txt");
        Thread t = new Thread(() -> {
            genericHeap = loadMap(app, "filters/generic.tsv");
            cosmetic.warm();
            domains.warm();
        }, "filter-warm");
        t.setDaemon(true);
        t.start();
    }

    private static Map<String, String> loadMap(Context ctx, String asset) {
        Map<String, String> m = new HashMap<>(20000);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(ctx.getAssets().open(asset), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab > 0) m.put(line.substring(0, tab), line.substring(tab + 1));
            }
        } catch (java.io.IOException e) {
            return null;
        }
        return m;
    }

    /** Called from onTrimMemory: the only heap this class holds is the CSS cache. */
    void trim() { siteCss.evictAll(); hostCache.clear(); }

    /** The user's own block list changed: forget cached answers. */
    void userListChanged() { hostCache.clear(); }

    /** True if the host or any parent domain is on a list (built-in or the user's own). */
    boolean isBlocked(String host, Prefs prefs) {
        if (host == null || host.isEmpty() || domains == null) return false;
        Boolean known = hostCache.get(host);
        if (known != null) return known;
        boolean b = lookup(host.toLowerCase(), prefs);
        if (hostCache.size() > 4096) hostCache.clear();
        hostCache.put(host, b);
        return b;
    }

    private boolean lookup(String h, Prefs prefs) {
        while (true) {
            if (domains.contains(h) || prefs.isUserBlocked(h)) return true;
            int dot = h.indexOf('.');
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) return false; // stop before the bare TLD
            h = h.substring(dot + 1);
        }
    }

    /** CSS for a frame on {@code host}: its site-specific rules plus the always-on generic ones. */
    String cssForHost(String host) {
        if (cosmetic == null) return "";
        String key = host == null ? "" : host.toLowerCase();
        String cached = siteCss.get(key);
        if (cached != null) return cached;
        StringBuilder sb = new StringBuilder(genericAlways);
        String h = key;
        while (!h.isEmpty()) {
            sb.append(rules(cosmetic.value(h)));
            int dot = h.indexOf('.');
            if (dot < 0) break;
            h = h.substring(dot + 1);
        }
        String css = sb.toString();
        siteCss.put(key, css);
        return css;
    }

    /** CSS for the generic rules whose leading #id/.class appears in {@code tokens} (space separated). */
    String cssForTokens(String tokens) {
        if (generic == null || tokens == null || tokens.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        Map<String, String> heap = genericHeap;
        for (String t : tokens.split(" ")) {
            if (t.isEmpty() || t.equals("*")) continue;
            sb.append(rules(heap != null ? heap.get(t) : generic.value(t)));
        }
        return sb.toString();
    }

    /** "a\u001fb" -> "a{display:none!important}\nb{display:none!important}\n" */
    private static String rules(String selectors) {
        if (selectors == null || selectors.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String s : selectors.split("\u001f")) sb.append(s).append(HIDE);
        return sb.toString();
    }

    /** Registrable domain, near enough without the full public-suffix list: "a.b.example.co.uk" -> "example.co.uk". */
    static String siteOf(String host) {
        if (host == null) return "";
        String h = host.toLowerCase();
        String[] p = h.split("\\.");
        if (p.length <= 2) return h;
        int n = p.length;
        boolean twoPartSuffix = p[n - 1].length() == 2
                && Arrays.asList("co", "com", "net", "org", "gov", "edu", "ac", "or", "ne", "go").contains(p[n - 2]);
        int keep = twoPartSuffix ? 3 : 2;
        return String.join(".", Arrays.copyOfRange(p, Math.max(0, n - keep), n));
    }

    static boolean sameSite(String a, String b) {
        return a != null && b != null && !a.isEmpty() && siteOf(a).equals(siteOf(b));
    }
}
