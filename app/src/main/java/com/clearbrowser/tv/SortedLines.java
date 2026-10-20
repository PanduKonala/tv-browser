package com.clearbrowser.tv;

import android.content.Context;
import android.content.res.AssetFileDescriptor;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;

/**
 * A sorted "key[TAB value]" lines file, memory-mapped straight out of the APK and binary-searched in
 * place. Nothing is copied into the Java heap: the pages are clean and file-backed, so Android can
 * drop them under memory pressure and fault them back in on the next lookup.
 */
final class SortedLines {
    private static final SortedLines EMPTY = new SortedLines(ByteBuffer.allocate(0));

    private final ByteBuffer buf;
    private final int len;

    private SortedLines(ByteBuffer buf) {
        this.buf = buf;
        this.len = buf.limit();
    }

    /** Maps an uncompressed asset (see noCompress in build.gradle). */
    static SortedLines map(Context ctx, String asset) {
        try (AssetFileDescriptor fd = ctx.getAssets().openFd(asset);
             FileInputStream in = fd.createInputStream()) {
            return new SortedLines(in.getChannel().map(FileChannel.MapMode.READ_ONLY, fd.getStartOffset(), fd.getLength()));
        } catch (IOException e) {
            return EMPTY;
        }
    }

    /** Touch every page once (background thread), so the first lookups don't wait on storage. */
    void warm() {
        int sum = 0;
        for (int i = 0; i < len; i += 4096) sum += buf.get(i);
        if (sum == 42) len_ignored = sum; // keep the loop from being optimised away
    }

    @SuppressWarnings("unused") private int len_ignored;

    /** Maps a file written by FilterUpdater. */
    static SortedLines map(java.io.File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            return new SortedLines(in.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, in.getChannel().size()));
        } catch (IOException e) {
            return EMPTY;
        }
    }

    boolean isEmpty() { return len == 0; }

    boolean contains(String key) {
        return find(key.getBytes(StandardCharsets.UTF_8)) >= 0;
    }

    /** The text after the tab on the line whose key matches, or null. */
    String value(String key) {
        int s = find(key.getBytes(StandardCharsets.UTF_8));
        if (s < 0) return null;
        int i = s;
        while (i < len && buf.get(i) != '\t' && buf.get(i) != '\n') i++;
        if (i >= len || buf.get(i) != '\t') return "";
        int start = i + 1, end = start;
        while (end < len && buf.get(end) != '\n') end++;
        byte[] out = new byte[end - start];
        for (int j = 0; j < out.length; j++) out[j] = buf.get(start + j); // absolute gets: thread-safe
        return new String(out, StandardCharsets.UTF_8);
    }

    /** Offset of the line whose key equals {@code key}, or -1. lo and hi always sit on line starts. */
    private int find(byte[] key) {
        int lo = 0, hi = len;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            int s = mid;
            while (s > lo && buf.get(s - 1) != '\n') s--;
            int c = compareKey(s, key);
            if (c == 0) return s;
            if (c > 0) {
                hi = s;
            } else {
                int e = s;
                while (e < len && buf.get(e) != '\n') e++;
                lo = e + 1;
            }
        }
        return -1;
    }

    private int compareKey(int s, byte[] key) {
        for (int i = 0; ; i++) {
            int p = s + i;
            boolean lineEnded = p >= len || buf.get(p) == '\t' || buf.get(p) == '\n';
            if (i == key.length) return lineEnded ? 0 : 1;
            if (lineEnded) return -1;
            int a = buf.get(p) & 0xff, b = key[i] & 0xff;
            if (a != b) return a - b;
        }
    }
}
