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
 */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class PageCopies {
    private static final int MAX_ENTRIES = 24, MAX_BYTES = 2 * 1024 * 1024;
    /** The text, and the address it finally came from (relative entries resolve against that). */
    private static final class Copy {
        final byte[] body;
        final Uri from;
        Copy(byte[] body, Uri from) { this.body = body; this.from = from; }
    }

    private static final Map<String, Copy> COPIES = Collections.synchronizedMap(
            new LinkedHashMap<String, Copy>(32, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Copy> e) { return size() > MAX_ENTRIES; }
            });

    private PageCopies() { }

    /** From the page (any frame): the address it asked for, the one it ended up at, and the text. */
    static void put(String url, String finalUrl, String body) {
        if (url == null || !url.startsWith("http") || body == null || body.length() > MAX_BYTES || !keep(body)) return;
        String end = finalUrl != null && finalUrl.startsWith("http") ? finalUrl : url;
        Copy c = new Copy(body.getBytes(StandardCharsets.UTF_8), Uri.parse(end));
        COPIES.put(url, c);
        COPIES.put(end, c);
        if (Debug.ON) Log.d("ClearPlayer", "page copy of " + url + " (" + c.body.length + " B)");
    }

    private static boolean keep(String body) {
        String head = body.trim();
        if (head.startsWith("#EXTM3U")) return body.contains("#EXT-X-STREAM-INF") || body.contains("#EXT-X-ENDLIST");
        return head.contains("<MPD") && !body.contains("type=\"dynamic\"");
    }

    @Nullable private static Copy get(Uri uri) { return COPIES.get(uri.toString()); }

    /** Serves a page copy when there is one, else asks {@code upstream}. */
    static final class Factory implements DataSource.Factory {
        private final DataSource.Factory upstream;

        Factory(DataSource.Factory upstream) { this.upstream = upstream; }

        @NonNull
        @Override
        public DataSource createDataSource() { return new Source(upstream.createDataSource()); }
    }

    private static final class Source implements DataSource {
        private final DataSource upstream;
        private final List<TransferListener> listeners = new ArrayList<>();
        private DataSource open;
        @Nullable private Uri copyFrom;

        Source(DataSource upstream) { this.upstream = upstream; }

        @Override
        public void addTransferListener(@NonNull TransferListener l) {
            upstream.addTransferListener(l);
            listeners.add(l);
        }

        @Override
        public long open(@NonNull DataSpec spec) throws IOException {
            Copy copy = get(spec.uri);
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
