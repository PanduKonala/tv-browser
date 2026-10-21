package com.clearbrowser.tv;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.TransferListener;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lets the TV player download through the page. Some servers only answer the page's own player:
 * the session is in cookies Android won't hand out (partitioned ones, set inside an embed), or
 * they check what only a real browser request carries. When such a server refuses the TV player,
 * the frame that plays the video fetches the bytes itself, with everything it normally sends, and
 * passes them over a binary message channel (androidx.webkit web messages with ArrayBuffers).
 *
 * Protocol, per frame: the frame says "hi" when it starts (we keep the latest frame per origin);
 * we send {id, url, range}; it answers with a JSON head {id, status, range, type, error} and then,
 * for a good answer, the body as an ArrayBuffer.
 */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class PagePipe {
    static final String NAME = "ClearPipe";
    private static final String TAG = "ClearPipe";
    /** Bytes per request through the page: large files come in pieces, never whole. */
    private static final int PIECE = 4 * 1024 * 1024;
    private static final long TIMEOUT_MS = 20_000;

    private static final Map<String, JavaScriptReplyProxy> FRAMES = new ConcurrentHashMap<>();
    private static final Map<JavaScriptReplyProxy, Integer> AWAITING_BODY = new ConcurrentHashMap<>();
    private static final Map<Integer, Pending> PENDING = new ConcurrentHashMap<>();
    /** Hosts that refused the TV player: everything from them goes through the page from then on. */
    private static final Set<String> VIA_PAGE = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final AtomicInteger IDS = new AtomicInteger();
    private static final Handler UI = new Handler(Looper.getMainLooper());

    /** What frames report about their video. The origin is WebView's, not the frame's own claim. */
    interface Reports {
        void playlist(String url, String finalUrl, String body, String origin);
        void started(double position, String subtitlesJson, String src, String origin);
    }

    private static volatile Reports reports;

    private static final class Pending {
        final CompletableFuture<Answer> done = new CompletableFuture<>();
        /** The frame we asked: only it may answer (any frame can post to us, ads included). */
        final JavaScriptReplyProxy frame;
        Answer head;
        Pending(JavaScriptReplyProxy frame) { this.frame = frame; }
    }

    private static final class Answer {
        int status;
        long total = C.LENGTH_UNSET; // from Content-Range, when the server sent one
        byte[] body;
        String error;
    }

    private PagePipe() { }

    static boolean supported() {
        return WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
                && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER);
    }

    /** Every frame of {@code web} gets window.ClearPipe. UI thread. */
    static void install(WebView web, Reports r) {
        reports = r;
        if (!supported()) return;
        WebViewCompat.addWebMessageListener(web, NAME, Collections.singleton("*"),
                (view, message, sourceOrigin, isMainFrame, reply) -> onMessage(message, sourceOrigin, reply));
    }

    /** The web view is going away: nobody can answer any more. */
    static void clear() {
        FRAMES.clear();
        AWAITING_BODY.clear();
        for (Pending p : PENDING.values()) p.done.completeExceptionally(new IOException("page closed"));
        PENDING.clear();
    }

    private static String norm(String origin) {
        if (origin == null) return "";
        return origin.endsWith("/") ? origin.substring(0, origin.length() - 1) : origin;
    }

    static boolean canFetchFor(@Nullable String origin) { return origin != null && FRAMES.containsKey(norm(origin)); }

    private static void onMessage(WebMessageCompat m, Uri sourceOrigin, JavaScriptReplyProxy reply) {
        if (m.getType() == WebMessageCompat.TYPE_ARRAY_BUFFER) {
            Integer id = AWAITING_BODY.remove(reply);
            Pending p = id == null ? null : PENDING.get(id);
            if (p == null || p.head == null || p.frame != reply) return;
            PENDING.remove(id);
            p.head.body = m.getArrayBuffer();
            p.done.complete(p.head);
            return;
        }
        String data = m.getData();
        if (data == null) return;
        if (data.equals("hi")) {
            FRAMES.put(norm(sourceOrigin.toString()), reply);
            if (Debug.ON) Log.d(TAG, "frame ready: " + norm(sourceOrigin.toString()));
            return;
        }
        try {
            JSONObject o = new JSONObject(data);
            String type = o.optString("t", "");
            if (!type.isEmpty()) {
                Reports r = reports;
                String origin = norm(sourceOrigin.toString());
                if (r == null || !origin.startsWith("http")) return;
                if (type.equals("pl")) r.playlist(o.optString("url"), o.optString("fin", null), o.optString("body"), origin);
                else if (type.equals("start")) r.started(o.optDouble("pos", 0), o.optString("subs", "[]"), o.optString("src", ""), origin);
                return;
            }
            Pending p = PENDING.get(o.getInt("id"));
            if (p == null || p.frame != reply) return; // not the frame we asked
            Answer a = new Answer();
            a.status = o.optInt("status");
            a.error = o.optString("error", null);
            a.total = totalOf(o.optString("range", ""));
            if (a.error != null || a.status < 200 || a.status >= 300) {
                PENDING.remove(o.getInt("id"));
                p.done.complete(a);
            } else {
                p.head = a;
                AWAITING_BODY.put(reply, o.getInt("id"));
            }
        } catch (Exception ignored) { }
    }

    /** The HTTP status that refused us, wherever it sits in the cause chain; -1 if none. */
    private static int refusedCode(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause())
            if (c instanceof HttpDataSource.InvalidResponseCodeException) return ((HttpDataSource.InvalidResponseCodeException) c).responseCode;
        return -1;
    }

    /** "bytes 0-1023/4096" -> 4096; unknown -> LENGTH_UNSET. */
    private static long totalOf(String contentRange) {
        int slash = contentRange.lastIndexOf('/');
        if (slash < 0) return C.LENGTH_UNSET;
        try { return Long.parseLong(contentRange.substring(slash + 1).trim()); } catch (NumberFormatException e) { return C.LENGTH_UNSET; }
    }

    /** Loader thread: has the frame from {@code origin} fetch {@code url} (bytes from..to, to = -1: open). */
    private static Answer fetch(String origin, String url, long from, long to) throws IOException {
        JavaScriptReplyProxy frame = FRAMES.get(norm(origin));
        if (frame == null) throw new IOException("no frame from " + origin + " to fetch through");
        int id = IDS.incrementAndGet();
        Pending p = new Pending(frame);
        PENDING.put(id, p);
        String range = from <= 0 && to < 0 ? "" : "bytes=" + from + "-" + (to >= 0 ? to : "");
        String ask;
        try {
            ask = new JSONObject().put("id", id).put("url", url).put("range", range).toString();
        } catch (Exception e) { throw new IOException(e); }
        UI.post(() -> {
            try { frame.postMessage(ask); } catch (Exception e) { p.done.completeExceptionally(e); }
        });
        try {
            return p.done.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            PENDING.remove(id);
            throw new IOException("page fetch of " + url + " failed: " + e.getMessage());
        }
    }

    /** Tries the network first; a server that refuses (401/403) is asked through the page from then on. */
    static final class Factory implements DataSource.Factory {
        private final DataSource.Factory upstream;
        @Nullable private final String origin;

        Factory(DataSource.Factory upstream, @Nullable String origin) { this.upstream = upstream; this.origin = origin; }

        @NonNull
        @Override
        public DataSource createDataSource() { return new Source(upstream.createDataSource(), origin); }
    }

    private static final class Source implements DataSource {
        private final DataSource upstream;
        /** The player's bandwidth meter among them: pieces through the page count as network transfers. */
        private final List<TransferListener> listeners = new java.util.ArrayList<>();
        @Nullable private final String origin;
        private boolean viaUpstream;
        private DataSpec spec;
        private Uri uri;
        private byte[] piece;
        private int pieceAt;
        private long next, end = C.LENGTH_UNSET; // next byte to ask for; end: one past the last, if known
        private boolean opened;

        Source(DataSource upstream, @Nullable String origin) {
            this.upstream = upstream;
            this.origin = origin;
        }

        @Override
        public void addTransferListener(@NonNull TransferListener l) {
            upstream.addTransferListener(l);
            listeners.add(l);
        }

        private void transferInitializing(DataSpec s) { for (TransferListener l : listeners) l.onTransferInitializing(this, s, true); }
        private void transferStarted(DataSpec s) { for (TransferListener l : listeners) l.onTransferStart(this, s, true); }
        private void bytesTransferred(int n) { for (TransferListener l : listeners) l.onBytesTransferred(this, spec, true, n); }
        private void transferEnded() { for (TransferListener l : listeners) l.onTransferEnd(this, spec, true); }

        @Override
        public long open(@NonNull DataSpec dataSpec) throws IOException {
            spec = dataSpec;
            uri = dataSpec.uri;
            String scheme = uri.getScheme();
            String host = uri.getHost();
            boolean web = "http".equals(scheme) || "https".equals(scheme);
            if (!web || host == null || !VIA_PAGE.contains(host) || !canFetchFor(origin)) {
                try {
                    viaUpstream = true;
                    return upstream.open(dataSpec);
                } catch (IOException e) {
                    try { upstream.close(); } catch (IOException ignored) { }
                    viaUpstream = false;
                    int code = refusedCode(e);
                    if (!web || host == null || (code != 401 && code != 403)) throw e;
                    if (!canFetchFor(origin)) {
                        Log.w(TAG, host + " refused the TV player (" + code + "), and no frame from " + origin + " can fetch for it"
                                + " (frames: " + FRAMES.keySet() + ")");
                        throw e;
                    }
                    VIA_PAGE.add(host);
                    Log.i(TAG, host + " refused the TV player (" + code + "): fetching through the page from " + origin);
                }
            }
            viaUpstream = false;
            transferInitializing(dataSpec);
            next = dataSpec.position;
            end = dataSpec.length == C.LENGTH_UNSET ? C.LENGTH_UNSET : dataSpec.position + dataSpec.length;
            Answer a = fetchPiece(true);
            opened = true;
            transferStarted(dataSpec);
            if (dataSpec.length != C.LENGTH_UNSET) return dataSpec.length;
            if (a.total != C.LENGTH_UNSET) { end = a.total; return a.total - dataSpec.position; }
            if (next == C.LENGTH_UNSET) return piece.length - pieceAt; // the whole thing in one answer
            return C.LENGTH_UNSET;
        }

        /** Next piece (at most PIECE bytes) into {@code piece}. */
        private Answer fetchPiece(boolean first) throws IOException {
            long to = next + PIECE - 1;
            if (end != C.LENGTH_UNSET) to = Math.min(to, end - 1);
            Answer a = PagePipe.fetch(origin, uri.toString(), next, to);
            if (!first && a.status == 416) { // past the end of a file whose size we didn't know
                piece = new byte[0];
                pieceAt = 0;
                next = C.LENGTH_UNSET;
                return a;
            }
            if (a.error != null || a.status < 200 || a.status >= 300 || a.body == null) {
                throw new HttpDataSource.InvalidResponseCodeException(Math.max(a.status, 0),
                        a.error == null ? "via page" : a.error, null, Collections.emptyMap(), spec, new byte[0]);
            }
            piece = a.body;
            pieceAt = 0;
            if (a.status == 200) {
                // The server ignored the range and sent everything: skip to where we are, and that's all.
                pieceAt = (int) Math.min(piece.length, next);
                next = C.LENGTH_UNSET;
            } else {
                // A short piece is the last one (cross-site servers often hide Content-Range from pages).
                boolean last = piece.length < to - next + 1;
                next += piece.length;
                if (last) end = next;
                if (a.total != C.LENGTH_UNSET && (end == C.LENGTH_UNSET || end > a.total)) end = a.total;
                if (end != C.LENGTH_UNSET && next >= end) next = C.LENGTH_UNSET;
            }
            return a;
        }

        @Override
        public int read(@NonNull byte[] buffer, int offset, int length) throws IOException {
            if (viaUpstream) return upstream.read(buffer, offset, length);
            if (length == 0) return 0;
            if (pieceAt >= piece.length) {
                if (next == C.LENGTH_UNSET) return C.RESULT_END_OF_INPUT;
                fetchPiece(false);
                if (piece.length == 0) return C.RESULT_END_OF_INPUT;
            }
            int n = Math.min(length, piece.length - pieceAt);
            System.arraycopy(piece, pieceAt, buffer, offset, n);
            pieceAt += n;
            bytesTransferred(n);
            return n;
        }

        @Nullable
        @Override
        public Uri getUri() { return viaUpstream ? upstream.getUri() : uri; }

        @NonNull
        @Override
        public Map<String, List<String>> getResponseHeaders() {
            return viaUpstream ? upstream.getResponseHeaders() : Collections.emptyMap();
        }

        @Override
        public void close() throws IOException {
            piece = null;
            if (viaUpstream) { viaUpstream = false; upstream.close(); return; }
            if (opened) { opened = false; transferEnded(); }
        }
    }
}
