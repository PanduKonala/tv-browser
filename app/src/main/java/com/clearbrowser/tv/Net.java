package com.clearbrowser.tv;

import java.util.Locale;

/** Address helpers for security checks. */
final class Net {
    private Net() { }

    /**
     * True for the home network and the TV itself: loopback, private and link-local IP literals
     * (v4 and v6), "localhost", single-label names and the home-network suffixes (.local, .lan,
 * .home.arpa, .internal). Names aren't resolved here (SafeHttp checks real addresses).
     */
    static boolean isLocal(String host) {
        if (host == null || host.isEmpty()) return false;
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
        if (h.equals("localhost") || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".lan")
                || h.endsWith(".home.arpa") || h.endsWith(".internal") || !h.contains(".") && !h.contains(":")) return true;
        if (h.contains(":")) { // IPv6
            return h.equals("::1") || h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe80:")
                    || h.startsWith("::ffff:") && isLocal(h.substring(7));
        }
        String[] p = h.split("\\.");
        if (p.length != 4) return false;
        int[] b = new int[4];
        for (int i = 0; i < 4; i++) {
            try { b[i] = Integer.parseInt(p[i]); } catch (NumberFormatException e) { return false; }
            if (b[i] < 0 || b[i] > 255) return false;
        }
        return b[0] == 127 || b[0] == 10 || b[0] == 0
                || b[0] == 172 && b[1] >= 16 && b[1] <= 31
                || b[0] == 192 && b[1] == 168
                || b[0] == 169 && b[1] == 254
                || b[0] == 100 && b[1] >= 64 && b[1] <= 127; // carrier-grade NAT
    }
}
