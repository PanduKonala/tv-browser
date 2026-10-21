package com.clearbrowser.tv;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Rect;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Preview pictures for the TV player's scrub carousel. Prefers the site's own WebVTT thumbnail
 * track (sprite sheets: "img1.jpg#xywh=0,0,160,90" per 10 s, the format web players use); with no
 * track, grabs frames from the video file itself. One background thread, newest request first,
 * sprite sheets kept compressed and decoded tile by tile, so RAM stays small.
 */
final class Thumbnails {
    interface Callback { void onThumb(long bucketMs, Bitmap bmp); }

    private static final String TAG = "ClearThumbs";
    private static final Pattern TIME = Pattern.compile("(?:(\\d+):)?(\\d{1,2}):(\\d{2})(?:[.,](\\d{1,3}))?");
    private static final Pattern XYWH = Pattern.compile("#xywh=(\\d+),(\\d+),(\\d+),(\\d+)");

    private static final class Cue {
        final long start, end; final String image; final Rect rect;
        Cue(long start, long end, String image, Rect rect) { this.start = start; this.end = end; this.image = image; this.rect = rect; }
    }

    private final String trackUrl, videoUrl;
    private final Map<String, String> headers;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "thumbs");
        t.setPriority(Thread.MIN_PRIORITY);
        t.setDaemon(true);
        return t;
    });
    private final LruCache<Long, Bitmap> bitmaps = new LruCache<Long, Bitmap>(6 * 1024 * 1024) {
        @Override protected int sizeOf(Long k, Bitmap b) { return b.getByteCount(); }
    };
    /** Sprite sheets, still JPEG-compressed (~50-150 KB each). */
    private final LinkedHashMap<String, byte[]> sheets = new LinkedHashMap<String, byte[]>(8, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, byte[]> e) { return size() > 40; }
    };
    private volatile List<Cue> cues;       // null until loaded; empty if the track failed
    private volatile boolean trackFailed;
    private MediaMetadataRetriever retriever;
    private boolean retrieverFailed;
    /** Bucket the user most recently asked for: stale requests are skipped. */
    private volatile long wanted = -1;
    private volatile boolean closed;

    Thumbnails(String trackUrl, String videoUrl, Map<String, String> headers) {
        this.trackUrl = trackUrl;
        this.videoUrl = videoUrl;
        this.headers = headers;
        if (trackUrl != null) worker.execute(this::loadTrack);
    }

    /** Spacing between distinct previews: the track's cue length, or 10 s for frame grabs. */
    long interval() {
        List<Cue> c = cues;
        if (c != null && !c.isEmpty()) return Math.max(1000, c.get(0).end - c.get(0).start);
        return 10_000;
    }

    long bucket(long ms) {
        long i = interval();
        return Math.max(0, ms / i * i);
    }

    /** The preview at {@code ms}; the callback runs on the UI thread (cached ones right away). */
    void get(long ms, boolean priority, Callback cb) {
        long b = bucket(ms);
        Bitmap hit = bitmaps.get(b);
        if (hit != null) { cb.onThumb(b, hit); return; }
        if (priority) wanted = b;
        worker.execute(() -> {
            if (closed) return;
            // The centre picture the user is looking at now beats neighbours still queued.
            if (!priority && wanted >= 0 && Math.abs(b - wanted) > 3 * interval()) return;
            Bitmap bmp = bitmaps.get(b);
            if (bmp == null) {
                bmp = useTrack() ? fromTrack(b) : fromVideo(b);
                if (bmp != null) bitmaps.put(b, bmp);
            }
            final Bitmap out = bmp;
            if (out != null) ui.post(() -> { if (!closed) cb.onThumb(b, out); });
        });
    }

    /** Decodes the previews around {@code ms} ahead of time (scrub start), nearest first. */
    void preload(long ms, int each) {
        long step = interval();
        for (int i = 0; i <= each; i++) {
            for (int sign : new int[]{1, -1}) {
                if (i == 0 && sign < 0) continue;
                long t = ms + sign * i * step;
                if (t < 0) continue;
                final long b = bucket(t);
                worker.execute(() -> {
                    if (closed || bitmaps.get(b) != null) return;
                    Bitmap bmp = useTrack() ? fromTrack(b) : null; // video frames are too slow to guess ahead
                    if (bmp != null) bitmaps.put(b, bmp);
                });
            }
        }
    }

    private boolean useTrack() {
        return trackUrl != null && !trackFailed && cues != null && !cues.isEmpty();
    }

    void close() {
        closed = true;
        worker.execute(() -> {
            if (retriever != null) try { retriever.release(); } catch (Exception ignored) { }
            retriever = null;
            bitmaps.evictAll();
            sheets.clear();
        });
        worker.shutdown();
    }

    // ------------------------------------------------------------- WebVTT sprite track

    private void loadTrack() {
        try {
            String text = new String(fetch(trackUrl), StandardCharsets.UTF_8);
            List<Cue> list = new ArrayList<>();
            String[] lines = text.split("\r?\n");
            for (int i = 0; i < lines.length; i++) {
                int arrow = lines[i].indexOf("-->");
                if (arrow < 0 || i + 1 >= lines.length) continue;
                long s = time(lines[i].substring(0, arrow)), e = time(lines[i].substring(arrow + 3));
                String target = lines[i + 1].trim();
                if (s < 0 || e <= s || target.isEmpty()) continue;
                Matcher m = XYWH.matcher(target);
                Rect r = null;
                String img = target;
                if (m.find()) {
                    int x = Integer.parseInt(m.group(1)), y = Integer.parseInt(m.group(2));
                    r = new Rect(x, y, x + Integer.parseInt(m.group(3)), y + Integer.parseInt(m.group(4)));
                    img = target.substring(0, m.start());
                }
                list.add(new Cue(s, e, resolve(trackUrl, img), r));
            }
            cues = list;
            if (Debug.ON) Log.i(TAG, "thumbnail track: " + list.size() + " previews");
            // Fetch every sprite sheet now (a few hundred KB in all), so scrubbing never waits on the network.
            java.util.LinkedHashSet<String> images = new java.util.LinkedHashSet<>();
            for (Cue c : list) images.add(c.image);
            for (String img : images) {
                if (closed) return;
                try {
                    byte[] b = fetch(img);
                    synchronized (sheets) { sheets.put(img, b); }
                } catch (IOException e) {
                    if (Debug.ON) Log.w(TAG, "sheet " + img + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            trackFailed = true;
            cues = new ArrayList<>();
            Log.w(TAG, "thumbnail track unavailable, using video frames: " + e.getMessage());
        }
    }

    private Bitmap fromTrack(long ms) {
        Cue cue = null;
        for (Cue c : cues) if (ms >= c.start && ms < c.end) { cue = c; break; }
        if (cue == null) return null;
        try {
            byte[] sheet;
            synchronized (sheets) { sheet = sheets.get(cue.image); }
            if (sheet == null) {
                sheet = fetch(cue.image);
                synchronized (sheets) { sheets.put(cue.image, sheet); }
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            if (cue.rect == null) return BitmapFactory.decodeByteArray(sheet, 0, sheet.length, o);
            BitmapRegionDecoder d = BitmapRegionDecoder.newInstance(sheet, 0, sheet.length, false);
            try { return d.decodeRegion(cue.rect, o); } finally { d.recycle(); }
        } catch (Exception e) {
            if (Debug.ON) Log.w(TAG, "preview tile failed: " + e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------- frames from the video

    private Bitmap fromVideo(long ms) {
        if (retrieverFailed || videoUrl == null) return null;
        try {
            if (retriever == null) {
                retriever = new MediaMetadataRetriever();
                retriever.setDataSource(videoUrl, new HashMap<>(headers));
            }
            return retriever.getScaledFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180);
        } catch (Exception e) {
            retrieverFailed = true; // can't read this video's frames: no previews, scrubbing still works
            Log.w(TAG, "no frame previews for this video: " + e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------- helpers

    /** Fetches with the headers a browser sends cross-site, which thumbnail servers check. */
    private byte[] fetch(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(15_000);
        for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
        c.setRequestProperty("Sec-Fetch-Mode", "cors");
        c.setRequestProperty("Sec-Fetch-Site", "cross-site");
        c.setRequestProperty("Sec-Fetch-Dest", "empty");
        int code = c.getResponseCode();
        if (code != 200) throw new IOException("HTTP " + code + " " + url);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static String resolve(String base, String rel) {
        try { return new URL(new URL(base), rel).toString(); } catch (IOException e) { return rel; }
    }

    /** "01:02:03.500", "02:03", "00:00:10" -> ms; -1 if not a time. */
    private static long time(String s) {
        Matcher m = TIME.matcher(s.trim());
        if (!m.find()) return -1;
        long h = m.group(1) == null ? 0 : Long.parseLong(m.group(1));
        long ms = m.group(4) == null ? 0 : Long.parseLong((m.group(4) + "00").substring(0, 3));
        return ((h * 60 + Long.parseLong(m.group(2))) * 60 + Long.parseLong(m.group(3))) * 1000 + ms;
    }
}
