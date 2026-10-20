package com.clearbrowser.tv;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns raw filter lists into the three sorted files the app memory-maps (domains.txt,
 * generic.tsv, cosmetic.tsv). A line-for-line port of tools/build_filters.py, so updates made on
 * the TV match what the build ships. No Android code here: it runs on a desktop JVM for checking.
 */
final class FilterCompiler {
    /** name, URL (HTTPS only), kind: abp (network + cosmetic), abp-network, hosts. */
    static final String[][] LISTS = {
            {"EasyList", "https://easylist.to/easylist/easylist.txt", "abp"},
            {"EasyPrivacy", "https://easylist.to/easylist/easyprivacy.txt", "abp-network"},
            {"uBlock filters", "https://ublockorigin.github.io/uAssets/filters/filters.txt", "abp"},
            {"uBlock badware", "https://ublockorigin.github.io/uAssets/filters/badware.txt", "abp"},
            {"uBlock privacy", "https://ublockorigin.github.io/uAssets/filters/privacy.txt", "abp-network"},
            {"AdGuard Base", "https://filters.adtidy.org/extension/ublock/filters/2_without_easylist.txt", "abp"},
            {"AdGuard Popups", "https://filters.adtidy.org/extension/ublock/filters/19.txt", "abp"},
            {"Peter Lowe", "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext", "hosts"},
    };

    interface Source {
        /** Opens one list for reading, line by line. */
        BufferedReader open(String url) throws IOException;
    }

    static final class Result {
        int domains, generic, cosmeticSites, listsRead;
        /** Shortest "! Expires:" any list declared, in hours; -1 if none did. */
        int expiresHours = -1;
    }

    private static final Set<String> OK_OPTS = new HashSet<>(Arrays.asList(
            "third-party", "3p", "first-party", "1p", "~third-party", "popup", "popunder", "script", "image", "xhr",
            "xmlhttprequest", "subdocument", "frame", "all", "doc", "document", "important", "media", "object",
            "ping", "websocket", "other", "stylesheet", "css", "font", "match-case", "~image", "~script",
            "~stylesheet", "~xmlhttprequest", "~subdocument", "~media", "~font", "~document"));
    private static final Pattern DOMAIN_RE = Pattern.compile("\\|\\|([a-z0-9.-]+\\.[a-z0-9-]{2,})\\^(?:\\$(.*))?");
    private static final Pattern PROCEDURAL = Pattern.compile(
            ":-abp-|:has-text|:upward|:xpath|:matches-css|:min-text-length|:watch-attr|:remove|:style\\(|"
            + ":others|:matches-path|:matches-attr|:matches-prop|:matches-media|:if\\(|:if-not|:nth-ancestor|"
            + ":spath|:contains|:properties|\\{|\\}|^\\^|^\\+js");
    // Python's \w is Unicode-aware (letters, digits, underscore); spelled out, because Android's
    // regex engine rejects the UNICODE_CHARACTER_CLASS flag.
    private static final Pattern SAFE_GENERIC = Pattern.compile("[#.\\[a-zA-Z][\\p{L}\\p{N}_\\-#.\\[\\]=\"'^$*~|:>+ ()]*");
    private static final Pattern TOKEN = Pattern.compile("[#.][A-Za-z0-9_-]+");
    private static final Pattern EXPIRES = Pattern.compile("(?i)^!\\s*Expires:\\s*(\\d+)\\s*(day|hour)");

    private FilterCompiler() { }

    /** Python's str.strip(): Unicode whitespace on both ends. */
    private static String strip(String s) {
        int a = 0, b = s.length();
        while (a < b && Character.isWhitespace(s.charAt(a)) || a < b && Character.isSpaceChar(s.charAt(a))) a++;
        while (b > a && (Character.isWhitespace(s.charAt(b - 1)) || Character.isSpaceChar(s.charAt(b - 1)))) b--;
        return s.substring(a, b);
    }

    static Result compile(Source source, File outDir) throws IOException {
        Result r = new Result();
        Set<String> blocked = new HashSet<>(200_000), unblocked = new HashSet<>();
        Set<String> generic = new HashSet<>(20_000);
        Map<String, Set<String>> cosmetic = new HashMap<>(40_000);

        for (String[] list : LISTS) {
            String url = list[1], kind = list[2];
            if (!url.startsWith("https://")) throw new IOException("not HTTPS: " + url);
            try (BufferedReader in = source.open(url)) {
                String raw;
                int lineNo = 0;
                while ((raw = in.readLine()) != null) {
                    String line = strip(raw);
                    if (lineNo++ < 60) {
                        Matcher e = EXPIRES.matcher(line);
                        if (e.find()) {
                            int h = Integer.parseInt(e.group(1)) * (e.group(2).equalsIgnoreCase("day") ? 24 : 1);
                            r.expiresHours = r.expiresHours < 0 ? h : Math.min(r.expiresHours, h);
                        }
                    }
                    if (line.isEmpty() || line.startsWith("!") || line.startsWith("[")) continue;
                    if (kind.equals("hosts")) {
                        String[] parts = line.split("\\s+");
                        if (parts.length >= 2 && (parts[0].equals("0.0.0.0") || parts[0].equals("127.0.0.1")) && parts[1].contains(".")) {
                            blocked.add(parts[1].toLowerCase(Locale.ROOT));
                        }
                        continue;
                    }
                    int hh = line.indexOf("##");
                    if (hh >= 0 && kind.equals("abp")) {
                        String dom = line.substring(0, hh), sel = strip(line.substring(hh + 2));
                        if (sel.isEmpty() || PROCEDURAL.matcher(sel).find() || line.contains("#@#")
                                || line.contains("#?#") || line.contains("#$#")) continue;
                        if (dom.isEmpty()) {
                            if (SAFE_GENERIC.matcher(sel).matches()) generic.add(sel);
                            continue;
                        }
                        for (String d0 : dom.split(",", -1)) {
                            String d = strip(d0).toLowerCase(Locale.ROOT);
                            if (!d.isEmpty() && !d.startsWith("~") && !d.contains("*") && !d.contains("/")) {
                                cosmetic.computeIfAbsent(d, k -> new HashSet<>()).add(sel);
                            }
                        }
                        continue;
                    }
                    if (line.startsWith("#")) continue;
                    boolean exc = line.startsWith("@@");
                    Matcher m = DOMAIN_RE.matcher(exc ? line.substring(2) : line);
                    if (!m.matches()) continue;
                    boolean ok = true;
                    String opts = m.group(2);
                    if (opts != null) {
                        for (String o : opts.split(",")) if (!o.isEmpty() && !OK_OPTS.contains(o)) { ok = false; break; }
                    }
                    if (!ok) continue;
                    (exc ? unblocked : blocked).add(m.group(1));
                }
            }
            r.listsRead++;
        }

        blocked.removeAll(unblocked);
        Set<String> clean = new HashSet<>(blocked.size() * 2);
        for (String d : blocked) if (d.contains(".")) clean.add(trimDots(d));
        List<String> domains = new ArrayList<>(clean.size());
        for (String d : clean) if (!covered(d, clean)) domains.add(d);
        domains.sort(UTF8);

        Map<String, List<String>> byToken = new HashMap<>();
        for (String sel : generic) {
            Matcher t = TOKEN.matcher(sel);
            String key = t.lookingAt() ? t.group() : "*";
            byToken.computeIfAbsent(key, k -> new ArrayList<>()).add(sel);
        }

        if (!outDir.isDirectory() && !outDir.mkdirs()) throw new IOException("can't create " + outDir);
        try (BufferedWriter w = writer(new File(outDir, "domains.txt"))) {
            for (String d : domains) w.write(d + "\n");
        }
        List<String> tokens = new ArrayList<>(byToken.keySet());
        tokens.sort(UTF8);
        try (BufferedWriter w = writer(new File(outDir, "generic.tsv"))) {
            for (String t : tokens) {
                List<String> sels = byToken.get(t);
                Collections.sort(sels, CODEPOINT);
                w.write(t + "\t" + String.join("\u001f", sels) + "\n");
            }
        }
        List<String> sites = new ArrayList<>(cosmetic.keySet());
        sites.sort(UTF8);
        try (BufferedWriter w = writer(new File(outDir, "cosmetic.tsv"))) {
            for (String d : sites) {
                List<String> sels = new ArrayList<>(cosmetic.get(d));
                Collections.sort(sels, CODEPOINT);
                w.write(d + "\t" + String.join("\u001f", sels) + "\n");
            }
        }
        r.domains = domains.size();
        r.generic = generic.size();
        r.cosmeticSites = cosmetic.size();
        return r;
    }

    /** A parent domain already blocks its subdomains; drop the redundant children. */
    private static boolean covered(String d, Set<String> all) {
        String[] p = d.split("\\.", -1);
        for (int i = 1; i < p.length - 1; i++) {
            if (all.contains(String.join(".", Arrays.copyOfRange(p, i, p.length)))) return true;
        }
        return false;
    }

    private static String trimDots(String d) {
        int a = 0, b = d.length();
        while (a < b && d.charAt(a) == '.') a++;
        while (b > a && d.charAt(b - 1) == '.') b--;
        return d.substring(a, b);
    }

    private static BufferedWriter writer(File f) throws IOException {
        return new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8), 1 << 16);
    }

    /** Byte order of the UTF-8 encoding: what SortedLines' binary search expects. */
    static final Comparator<String> UTF8 = (a, b) -> {
        byte[] x = a.getBytes(StandardCharsets.UTF_8), y = b.getBytes(StandardCharsets.UTF_8);
        int n = Math.min(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int c = (x[i] & 0xff) - (y[i] & 0xff);
            if (c != 0) return c;
        }
        return x.length - y.length;
    };

    /** Python's str ordering (by code point). */
    private static final Comparator<String> CODEPOINT = (a, b) -> {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i), cb = b.codePointAt(j);
            if (ca != cb) return Integer.compare(ca, cb);
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    };
}
