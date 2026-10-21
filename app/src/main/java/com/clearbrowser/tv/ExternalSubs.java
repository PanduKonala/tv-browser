package com.clearbrowser.tv;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.text.Cue;
import androidx.media3.extractor.text.CuesWithTiming;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.extractor.text.SubtitleParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Subtitle files the page's player offers (not inside the video). Downloaded only when picked,
 * with the headers the page's player sends, parsed with Media3's parsers, and drawn by the TV
 * player itself: that's what lets the delay go both ways, and a bad file can't stop playback.
 */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class ExternalSubs {
    static final class Entry {
        final String url, label, lang, type;
        volatile List<CuesWithTiming> cues; // null until loaded
        volatile boolean failed;
        Entry(String url, String label, String lang, String type) {
            this.url = url; this.label = label; this.lang = Lang.norm(lang); this.type = type;
        }
    }

    interface Loaded { void done(Entry e, boolean ok); }

    private static final String TAG = "ClearSubs";
    final List<Entry> entries = new ArrayList<>();
    private final Map<String, String> headers;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "subs");
        t.setDaemon(true);
        return t;
    });
    private final Handler ui = new Handler(Looper.getMainLooper());

    /** False unless the page is on the local network: see SafeHttp. */
    private final boolean allowLocal;

    ExternalSubs(Map<String, String> headers, boolean allowLocal) { this.headers = headers; this.allowLocal = allowLocal; }

    void add(String url, String label, String lang, String type) {
        if (url == null || url.isEmpty() || "json".equals(type)) return;
        for (Entry e : entries) if (e.url.equals(url)) return;
        entries.add(new Entry(url, label, lang, type));
    }

    /** Downloads and parses {@code e} if needed; the callback runs on the UI thread. */
    void load(Entry e, Loaded cb) {
        if (e.cues != null) { cb.done(e, true); return; }
        worker.execute(() -> {
            boolean ok = false;
            try {
                byte[] data = fetch(e.url);
                Format f = new Format.Builder().setSampleMimeType(mime(e, data)).build();
                SubtitleParser p = new DefaultSubtitleParserFactory().create(f);
                List<CuesWithTiming> out = new ArrayList<>();
                p.parse(data, SubtitleParser.OutputOptions.allCues(), out::add);
                Collections.sort(out, (a, b) -> Long.compare(a.startTimeUs, b.startTimeUs));
                e.cues = out;
                ok = !out.isEmpty();
                if (Debug.ON) Log.i(TAG, e.label + ": " + out.size() + " cues");
            } catch (Exception ex) {
                e.failed = true;
                Log.w(TAG, "subtitle " + e.url + " unusable: " + ex.getMessage());
            }
            final boolean result = ok;
            ui.post(() -> cb.done(e, result));
        });
    }

    /** Cues to show at {@code positionUs} (already shifted by the delay). */
    static List<Cue> at(Entry e, long positionUs) {
        List<CuesWithTiming> list = e.cues;
        if (list == null) return Collections.emptyList();
        List<Cue> out = new ArrayList<>();
        // Sorted by start: everything after a start beyond positionUs can't be showing yet.
        for (CuesWithTiming c : list) {
            if (c.startTimeUs > positionUs) break;
            long end = c.durationUs == androidx.media3.common.C.TIME_UNSET ? Long.MAX_VALUE : c.startTimeUs + c.durationUs;
            if (positionUs < end) out.addAll(c.cues);
        }
        return out;
    }

    void close() { worker.shutdownNow(); }

    /** The player's word on the format, else the file name, else the content itself. */
    private static String mime(Entry e, byte[] data) {
        String t = e.type == null ? "" : e.type;
        String path = e.url.replaceAll("[?#].*", "").toLowerCase();
        if (t.equals("srt") || path.endsWith(".srt")) return MimeTypes.APPLICATION_SUBRIP;
        if (t.equals("ass") || t.equals("ssa") || path.endsWith(".ass") || path.endsWith(".ssa")) return MimeTypes.TEXT_SSA;
        if (t.equals("vtt") || path.endsWith(".vtt")) return MimeTypes.TEXT_VTT;
        String head = new String(data, 0, Math.min(64, data.length)).trim();
        if (head.startsWith("WEBVTT")) return MimeTypes.TEXT_VTT;
        if (head.startsWith("[Script Info]")) return MimeTypes.TEXT_SSA;
        return MimeTypes.APPLICATION_SUBRIP;
    }

    private byte[] fetch(String url) throws IOException {
        return SafeHttp.get(allowLocal, url, headers);
    }
}
