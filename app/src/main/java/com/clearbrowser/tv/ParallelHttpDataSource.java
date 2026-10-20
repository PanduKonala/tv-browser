package com.clearbrowser.tv;

import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.TransferListener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Downloads one resource (a video segment, a file) as byte ranges over up to 32 parallel
 * connections, and hands the bytes to the player in order. Hosts that throttle each connection
 * then deliver several times faster. Each piece goes through its own upstream source, which
 * reports to the player's bandwidth meter, so adaptive quality sees the combined speed.
 *
 * Connections per host adapt (start 8, double after each clean download, up to 32). Fallbacks:
 * a refused piece (403/416/429/503: rate limiting) switches the rest of the download to one
 * continuous connection from that exact byte, and that host stays single-stream for the session;
 * a first request that's refused gets one plain retry; a server that ignores ranges gets one plain
 * stream. At most 16 MB is in flight, so RAM stays bounded.
 */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class ParallelHttpDataSource implements DataSource {
    static final int CHUNK = 1024 * 1024;          // first piece
    static final int MAX_PIECE = 4 * 1024 * 1024;  // fewer, larger requests: kinder to rate limits
    static final int MIN_PIECE = 128 * 1024;
    static final int MAX_CONNECTIONS = 32, START_CONNECTIONS = 8;
    static final long MAX_IN_FLIGHT = 16L * 1024 * 1024;
    private static final int ATTEMPTS = 3;

    private static final ExecutorService POOL = Executors.newFixedThreadPool(MAX_CONNECTIONS, r -> {
        Thread t = new Thread(r, "parallel-dl");
        t.setDaemon(true);
        return t;
    });
    /** Connections to use per host, learnt as we go. */
    private static final ConcurrentHashMap<String, Integer> LIMITS = new ConcurrentHashMap<>();

    /** Hosts that refused parallel pieces: plain single stream from now on. */
    private static final java.util.Set<String> SINGLE = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

    private static int limit(String host) {
        Integer n = LIMITS.get(host);
        return n == null ? START_CONNECTIONS : n;
    }

    private static void backOff(String host) {
        LIMITS.compute(host, (h, n) -> Math.max(1, (n == null ? START_CONNECTIONS : n) / 2));
    }

    private static void rampUp(String host) {
        LIMITS.compute(host, (h, n) -> Math.min(MAX_CONNECTIONS, (n == null ? START_CONNECTIONS : n) * 2));
    }

    static final class Factory implements DataSource.Factory {
        private final DataSource.Factory upstream;

        Factory(DataSource.Factory upstream) { this.upstream = upstream; }

        @NonNull
        @Override
        public DataSource createDataSource() { return new ParallelHttpDataSource(upstream); }
    }

    private final DataSource.Factory upstreamFactory;
    private final List<TransferListener> listeners = new ArrayList<>();

    private DataSpec spec;
    private Map<String, List<String>> headers = Collections.emptyMap();
    /** Fallback: a single upstream stream, when the server ignores ranges. */
    @Nullable private DataSource single;
    // Parallel mode: piece 0 is the first CHUNK; the rest are `piece` bytes each.
    private long start, end;
    private int count, next, submitted, connections, piece;
    private String host = "";
    private volatile boolean hadError;
    private final List<Future<byte[]>> pieces = new ArrayList<>();
    private byte[] cur;
    private int curPos;

    private ParallelHttpDataSource(DataSource.Factory upstreamFactory) {
        this.upstreamFactory = upstreamFactory;
    }

    @Override
    public void addTransferListener(@NonNull TransferListener l) { listeners.add(l); }

    private DataSource newUpstream() {
        DataSource d = upstreamFactory.createDataSource();
        for (TransferListener l : listeners) d.addTransferListener(l);
        return d;
    }

    @Override
    public long open(@NonNull DataSpec dataSpec) throws IOException {
        spec = dataSpec;
        start = dataSpec.position;
        host = dataSpec.uri.getHost() == null ? "" : dataSpec.uri.getHost();
        hadError = false;
        if (SINGLE.contains(host)) return openSingle(dataSpec);
        // First piece in the foreground: it tells us the total size and whether ranges work.
        DataSource first = newUpstream();
        long want = dataSpec.length == C.LENGTH_UNSET ? CHUNK : Math.min(CHUNK, dataSpec.length);
        byte[] firstBytes;
        long total;
        try {
            first.open(dataSpec.subrange(0, want));
            headers = first.getResponseHeaders();
            total = totalFromContentRange(headers);
            firstBytes = total < 0 ? null : readAll(first);
        } catch (HttpDataSource.InvalidResponseCodeException e) {
            // Refused straight away (often rate limiting after earlier requests): one plain try.
            Log.w("ClearDL", host + ": first piece refused (" + e.responseCode + "), trying one plain stream");
            SINGLE.add(host);
            try { first.close(); } catch (IOException ignored) { }
            return openSingle(dataSpec);
        } finally {
            first.close();
        }
        if (total < 0) {
            // No range support: one plain stream for the whole thing.
            Log.i("ClearDL", host + ": no byte ranges, single connection");
            single = newUpstream();
            long n = single.open(dataSpec);
            headers = single.getResponseHeaders();
            return n;
        }
        end = dataSpec.length == C.LENGTH_UNSET ? total : Math.min(total, start + dataSpec.length);
        long len = end - start;
        connections = limit(host);
        // Spread what's left over the connections, within [MIN_PIECE, CHUNK].
        long rest = Math.max(0, len - firstBytes.length);
        piece = (int) Math.max(MIN_PIECE, Math.min(MAX_PIECE, (rest + connections - 1) / Math.max(1, connections)));
        count = 1 + (int) ((rest + piece - 1) / piece);
        if (count > 1) Log.i("ClearDL", host + ": " + len / 1024 + " KB in " + count + " pieces over "
                + Math.min(connections, count - 1) + " connections (host limit " + connections + ")");
        pieces.clear();
        firstLen = firstBytes.length;
        pieces.add(new DoneFuture(firstBytes));
        submitted = 1;
        next = 0;
        fill();
        return len;
    }

    /** Keep up to `connections` pieces (and at most MAX_IN_FLIGHT bytes) downloading ahead of the reader. */
    private void fill() {
        int ahead = (int) Math.max(1, Math.min(connections, MAX_IN_FLIGHT / piece));
        while (submitted < count && submitted < next + 1 + ahead) {
            final long off = offsetOf(submitted);
            final long n = Math.min(piece, end - start - off);
            pieces.add(POOL.submit(() -> fetch(off, n)));
            submitted++;
        }
    }

    private long offsetOf(int i) {
        return i == 0 ? 0 : (long) firstLen + (long) (i - 1) * piece;
    }

    private int firstLen;

    /** One piece, retried; any failure halves this host's connection count for what follows. */
    private byte[] fetch(long off, long n) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            DataSource d = newUpstream();
            try {
                d.open(spec.subrange(off, n));
                byte[] b = readAll(d);
                if (b.length == n) return b;
                last = new IOException("short piece " + b.length + "/" + n);
            } catch (HttpDataSource.InvalidResponseCodeException e) {
                last = e;
                int code = e.responseCode;
                if (code == 403 || code == 416 || code == 429 || code == 503) { backOff(host); break; }
            } catch (IOException e) {
                last = e;
            } finally {
                try { d.close(); } catch (IOException ignored) { }
            }
            hadError = true;
            backOff(host);
            Log.w("ClearDL", host + ": piece failed (" + last.getMessage() + "), connections now " + limit(host));
        }
        throw last;
    }

    @Override
    public int read(@NonNull byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (single != null) return single.read(buffer, offset, length);
        while (cur == null || curPos >= cur.length) {
            if (next >= count) {
                if (count > 1 && !hadError) { rampUp(host); count = 0; } // clean download: allow more next time
                return C.RESULT_END_OF_INPUT;
            }
            try {
                cur = pieces.get(next).get();
                pieces.set(next, null); // let the piece's bytes go as soon as they're consumed
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException();
            } catch (ExecutionException e) {
                // A piece was refused: carry on with one continuous connection from its first byte.
                Throwable c = e.getCause();
                Log.w("ClearDL", host + ": switching to one connection at piece " + next + " (" + (c == null ? "?" : c.getMessage()) + ")");
                SINGLE.add(host);
                long from = offsetOf(next);
                for (Future<byte[]> f : pieces) if (f != null) f.cancel(true);
                pieces.clear();
                cur = null;
                count = 0;
                single = newUpstream();
                DataSpec rest = spec.length == C.LENGTH_UNSET ? spec.subrange(from) : spec.subrange(from, spec.length - from);
                single.open(rest);
                return single.read(buffer, offset, length);
            }
            curPos = 0;
            next++;
            fill();
        }
        int n = Math.min(length, cur.length - curPos);
        System.arraycopy(cur, curPos, buffer, offset, n);
        curPos += n;
        return n;
    }

    private long openSingle(DataSpec dataSpec) throws IOException {
        single = newUpstream();
        long n = single.open(dataSpec);
        headers = single.getResponseHeaders();
        return n;
    }

    @Nullable
    @Override
    public Uri getUri() { return spec == null ? null : spec.uri; }

    @NonNull
    @Override
    public Map<String, List<String>> getResponseHeaders() { return headers; }

    @Override
    public void close() throws IOException {
        for (Future<byte[]> f : pieces) if (f != null) f.cancel(true);
        pieces.clear();
        cur = null;
        count = next = submitted = 0;
        if (single != null) {
            DataSource s = single;
            single = null;
            s.close();
        }
    }

    private static byte[] readAll(DataSource d) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(CHUNK);
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = d.read(buf, 0, buf.length)) != C.RESULT_END_OF_INPUT) out.write(buf, 0, n);
        return out.toByteArray();
    }

    /** Total size from "Content-Range: bytes 0-1048575/4812345", or -1 if the server didn't send one. */
    private static long totalFromContentRange(Map<String, List<String>> h) {
        for (Map.Entry<String, List<String>> e : h.entrySet()) {
            if (e.getKey() == null || !e.getKey().equalsIgnoreCase("Content-Range") || e.getValue().isEmpty()) continue;
            String v = e.getValue().get(0);
            int slash = v.lastIndexOf('/');
            if (slash < 0) return -1;
            try { return Long.parseLong(v.substring(slash + 1).trim()); } catch (NumberFormatException x) { return -1; }
        }
        return -1;
    }

    private static final class DoneFuture implements Future<byte[]> {
        private final byte[] v;
        DoneFuture(byte[] v) { this.v = v; }
        @Override public boolean cancel(boolean b) { return false; }
        @Override public boolean isCancelled() { return false; }
        @Override public boolean isDone() { return true; }
        @Override public byte[] get() { return v; }
        @Override public byte[] get(long t, @NonNull java.util.concurrent.TimeUnit u) { return v; }
    }
}
