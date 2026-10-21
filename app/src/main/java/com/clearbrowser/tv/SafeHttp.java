package com.clearbrowser.tv;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * The HTTP client for everything the TV player downloads (video, subtitles, thumbnails). Those
 * addresses come from web pages, and the app fetches them natively, outside the browser's guard
 * against public pages reaching into the home network. So unless the page itself is on the local
 * network, this client never talks to a local address: names that resolve to one are refused at
 * lookup, and every connection (redirects too, and numeric addresses, which skip the lookup) is
 * checked against the address it actually reached, so DNS rebinding can't slip through.
 */
final class SafeHttp {
    private static OkHttpClient any, publicOnly;

    private SafeHttp() { }

    static synchronized OkHttpClient client(boolean allowLocal) {
        if (any == null) {
            any = new OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .build();
            publicOnly = any.newBuilder()
                    .dns(host -> {
                        List<InetAddress> ok = new ArrayList<>();
                        for (InetAddress a : Dns.SYSTEM.lookup(host)) if (!isLocal(a)) ok.add(a);
                        if (ok.isEmpty()) throw new UnknownHostException(host + ": a local network address, refused");
                        return ok;
                    })
                    .addNetworkInterceptor(chain -> {
                        InetAddress a = chain.connection() == null ? null
                                : chain.connection().route().socketAddress().getAddress();
                        if (a != null && isLocal(a)) throw new IOException("local network address refused: " + a.getHostAddress());
                        return chain.proceed(chain.request());
                    })
                    .build();
        }
        return allowLocal ? any : publicOnly;
    }

    /** Loopback, private, link-local, carrier-grade NAT and IPv6 unique-local addresses. */
    static boolean isLocal(InetAddress a) {
        if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress()) return true;
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) return (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64; // 100.64.0.0/10
        if (a instanceof Inet6Address) return (b[0] & 0xfe) == 0xfc;                           // fc00::/7
        return false;
    }

    /** True when {@code host} resolves to any local address (or can't be resolved). */
    static boolean resolvesLocal(String host) {
        try {
            for (InetAddress a : InetAddress.getAllByName(host)) if (isLocal(a)) return true;
            return false;
        } catch (UnknownHostException e) {
            return true;
        }
    }

    /** GET {@code url} with {@code headers}; the body, or an IOException for anything but 200. */
    static byte[] get(boolean allowLocal, String url, Map<String, String> headers) throws IOException {
        Request.Builder r = new Request.Builder().url(url);
        for (Map.Entry<String, String> h : headers.entrySet()) r.header(h.getKey(), h.getValue());
        try (Response res = client(allowLocal).newCall(r.build()).execute()) {
            ResponseBody body = res.body();
            if (res.code() != 200 || body == null) throw new IOException("HTTP " + res.code() + " " + url);
            return body.bytes();
        }
    }
}
