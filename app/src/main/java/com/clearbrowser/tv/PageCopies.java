package com.clearbrowser.tv;

import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.datasource.ByteArrayDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Playlists (HLS .m3u8, DASH .mpd) exactly as the page's player received them. Some servers tie
 * the playlist to a session the app can't reproduce: partitioned cookies, which Android's
 * CookieManager doesn't hand out ("Forbidden: Missing stream session"). The TV player reads the
 * page's copy instead of asking again. Only playlists that won't change are kept: a master list
 * of qualities, or a finished (VOD) one; live playlists still come from the network.
 *
 * Any frame can hand us a copy, ads included, so each copy belongs to the frame (origin) that
 * received it, and the TV player only reads copies from the frame playing its video: an ad frame
 * can't swap the stream. All copies together stay under 8 MB.
 */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class PageCopies {
    private static final int MAX_ENTRIES = 24, MAX_BYTES = 2 * 1024 * 1024, MAX_TOTAL = 8 * 1024 * 1024;
    /** The text, and the address it finally came from (relative entries resolve against that). */
    private static final class Copy {
        final byte[] body;
        final Uri from;
        final String frame;
        Copy(byte[] body, Uri from, String frame) { this.body = body; this.from = from; this.frame = frame; }
    }

    private static final Map<String, Copy> COPIES = Collections.synchronizedMap(
            new LinkedHashMap<String, Copy>(32, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Copy> e) { return size() > MAX_ENTRIES; }
            });

    /** Keyed by frame and address: the same address from two frames is two copies. */
    private static String key(String frame, String url) { return frame + " " + url; }

    private PageCopies() { }

    /** From the page (any frame): the address it asked for, the one it ended up at, and the text. */
    static void put(String url, String finalUrl, String body, String frameOrigin) {
        if (url == null || !url.startsWith("http") || body == null || body.length() > MAX_BYTES || !keep(body)) return;
        if (frameOrigin == null || !frameOrigin.startsWith("http")) return;
        String end = finalUrl != null && finalUrl.startsWith("http") ? finalUrl : url;
        Copy c = new Copy(body.getBytes(StandardCharsets.UTF_8), Uri.parse(end), frameOrigin);
        synchronized (COPIES) {
            COPIES.put(key(frameOrigin, url), c);
            COPIES.put(key(frameOrigin, end), c);
            trim();
        }
        if (Debug.ON) Log.d("ClearPlayer", "page copy of " + url + " (" + c.body.length + " B)");
    }

    private static boolean keep(String body) {
        String head = body.trim();
        if (head.startsWith("#EXTM3U")) return body.contains("#EXT-X-STREAM-INF") || body.contains("#EXT-X-ENDLIST");
        return head.contains("<MPD") && !body.contains("type=\"dynamic\"");
    }

    /** Oldest copies go first until all of them fit in MAX_TOTAL. Holding COPIES. */
    private static void trim() {
        java.util.IdentityHashMap<Copy, Boolean> seen = new java.util.IdentityHashMap<>();
        long total = 0;
        for (Copy c : COPIES.values()) if (seen.put(c, Boolean.TRUE) == null) total += c.body.length;
        java.util.Iterator<Map.Entry<String, Copy>> it = COPIES.entrySet().iterator();
        while (total > MAX_TOTAL && it.hasNext()) {
            Copy c = it.next().getValue();
            it.remove();
            if (!COPIES.containsValue(c)) total -= c.body.length;
        }
    }

    @Nullable private static Copy get(Uri uri, @Nullable String frame) {
        return frame == null ? null : COPIES.get(key(frame, uri.toString()));
    }

    /** HLS or DASH, from the playing frame's copy of {@code uri}; null if there's no copy. */
    @Nullable static String mimeOf(Uri uri, @Nullable String frame) {
        Copy c = get(uri, frame);
        if (c == null) return null;
        String head = new String(c.body, 0, Math.min(c.body.length, 512), StandardCharsets.UTF_8).trim();
        if (head.startsWith("#EXTM3U")) return androidx.media3.common.MimeTypes.APPLICATION_M3U8;
        return head.contains("<MPD") ? androidx.media3.common.MimeTypes.APPLICATION_MPD : null;
    }

    /** Serves a page copy when there is one, else asks {@code upstream}. */
    static final class Factory implements DataSource.Factory {
        @Nullable private final String frame;
        private final DataSource.Factory upstream;

        /** {@code frame}: the origin of the frame playing the video; only its copies are used. */
        Factory(@Nullable String frame, DataSource.Factory upstream) { this.frame = frame; this.upstream = upstream; }

        @NonNull
        @Override
        public DataSource createDataSource() { return new Source(upstream.createDataSource(), frame); }
    }

    private static final class Source implements DataSource {
        private final DataSource upstream;
        @Nullable private final String frame;
        private final List<TransferListener> listeners = new ArrayList<>();
        private DataSource open;
        @Nullable private Uri copyFrom;

        Source(DataSource upstream, @Nullable String frame) { this.upstream = upstream; this.frame = frame; }

        @Override
        public void addTransferListener(@NonNull TransferListener l) {
            upstream.addTransferListener(l);
            listeners.add(l);
        }

        @Override
        public long open(@NonNull DataSpec spec) throws IOException {
            Copy copy = get(spec.uri, frame);
            copyFrom = copy == null ? null : copy.from;
            if (copy != null) {
                if (Debug.ON) Log.d("ClearPlayer", "using the page's copy of " + spec.uri);
                ByteArrayDataSource b = new ByteArrayDataSource(copy.body);
                for (TransferListener l : listeners) b.addTransferListener(l);
                open = b;
            } else {
                open = upstream;
            }
            return open.open(spec);
        }

        @Override
        public int read(@NonNull byte[] buffer, int offset, int length) throws IOException {
            return open.read(buffer, offset, length);
        }

        @Nullable
        @Override
        public Uri getUri() { return copyFrom != null ? copyFrom : open == null ? null : open.getUri(); }

        @NonNull
        @Override
        public Map<String, List<String>> getResponseHeaders() {
            return open == null ? Collections.emptyMap() : open.getResponseHeaders();
        }

        @Override
        public void close() throws IOException {
            DataSource o = open;
            open = null;
            copyFrom = null;
            if (o != null) o.close();
        }
    }
}
