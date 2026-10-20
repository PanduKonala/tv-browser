package com.clearbrowser.tv;

import android.webkit.JavascriptInterface;

/** Called from inject.js in every frame: element-hiding CSS, and what the page's video is doing. */
final class Bridge {
    interface Host {
        boolean shieldsOn();
        /** Hold back muted autoplay (previews, hero trailers) until the user clicks on the page. */
        boolean holdPreviews();
        /** event: "playing" when a video starts, "time" every couple of seconds while it plays. */
        void onVideo(String event, double position);
        /** From the bookmarks page only (the host checks which page is showing). */
        void removeBookmark(String url);
    }

    private final Host host;

    Bridge(Host host) { this.host = host; }

    @JavascriptInterface
    public String css(String hostname) {
        return host.shieldsOn() ? Blocker.get().cssForHost(hostname) : "";
    }

    @JavascriptInterface
    public String generic(String tokens) {
        return host.shieldsOn() ? Blocker.get().cssForTokens(tokens) : "";
    }

    @JavascriptInterface
    public boolean holdPreviews() {
        return host.holdPreviews();
    }

    @JavascriptInterface
    public void removeBookmark(String url) {
        host.removeBookmark(url);
    }

    @JavascriptInterface
    public void video(String event, double position) {
        host.onVideo(event, position);
    }
}
