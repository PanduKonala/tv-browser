package com.clearbrowser.tv;

import android.webkit.JavascriptInterface;

/**
 * Called from inject.js in every frame: element-hiding CSS, and what the page's video is doing.
 * It can't tell which frame is calling, so nothing here may trust a frame's word about who it is:
 * reports tied to a frame (playlists, a video starting) come over PagePipe, where WebView names the
 * sender.
 */
final class Bridge {
    interface Host {
        boolean shieldsOn();
        /** Hold back muted autoplay (previews, hero trailers) until the user clicks on the page. */
        boolean holdPreviews();
        /** Test logging on (page timing, video stats): see Debug. */
        boolean debug();
        /** Drop GPU-heavy effects (frosted-glass blur) for smoother scrolling. */
        boolean liteEffects();
        /** event: "playing" when a video starts, "time" every couple of seconds while it plays. */
        void onVideo(String event, double position);
        /** From the bookmarks page only (the host checks which page is showing). */
        void removeBookmark(String url);
        /** From the home page only. */
        void clearRecent();
        /** From the home page only: one entry of Recently visited. */
        void removeRecent(String url);
        /** The pointer rests on a link in the top page. */
        void prefetch(String url);
        /** The scrollable box last under the pointer in the top page (JSON rect + directions), or "". */
        void scroller(String json);
    }

    private final Host host;

    Bridge(Host host) { this.host = host; }

    @JavascriptInterface
    public String css(String hostname) {
        return host.shieldsOn() ? Blocker.get().cssForHost(hostname) : "";
    }

    /** The app's own fixes for this host: [[key, value], ...] to put in localStorage, or "". */
    @JavascriptInterface
    public String storage(String hostname) {
        return host.shieldsOn() ? SiteFixes.storageFor(hostname) : "";
    }

    @JavascriptInterface
    public String generic(String tokens) {
        return host.shieldsOn() ? Blocker.get().cssForTokens(tokens) : "";
    }

    @JavascriptInterface
    public boolean liteEffects() {
        return host.liteEffects();
    }

    @JavascriptInterface
    public boolean debug() {
        return host.debug();
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
    public void removeRecent(String url) {
        host.removeRecent(url);
    }

    @JavascriptInterface
    public void clearRecent() {
        host.clearRecent();
    }

    @JavascriptInterface
    public void scroller(String json) {
        host.scroller(json);
    }

    @JavascriptInterface
    public void prefetch(String url) {
        host.prefetch(url);
    }

    /** Periodic playback health from a frame with a playing video (logged as ClearVideo). */
    @JavascriptInterface
    public void videoStats(String json) {
        if (Debug.ON) android.util.Log.i("ClearVideo", json);
    }

    @JavascriptInterface
    public void video(String event, double position) {
        host.onVideo(event, position);
    }
}
