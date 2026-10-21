package com.clearbrowser.tv;

import android.webkit.JavascriptInterface;

/** Called from inject.js in every frame: element-hiding CSS, and what the page's video is doing. */
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
        /** A full-length video with sound started playing in some frame. */
        /** src: the file the page's player is actually playing (blob: for MSE players). */
        void onVideoStarted(double position, String subtitlesJson, String src, String frameOrigin);
        /** From the bookmarks page only (the host checks which page is showing). */
        void removeBookmark(String url);
        /** From the home page only. */
        void clearRecent();
        /** The pointer rests on a link in the top page. */
        void prefetch(String url);
        /** A frame's player received a playlist (it may not have a playlist-like address). */
        void onPlaylist(String url, String frameOrigin, boolean master);
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
    public void clearRecent() {
        host.clearRecent();
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
    public void videoStarted(double position, String subtitlesJson, String src, String frameOrigin) {
        host.onVideoStarted(position, subtitlesJson, src, frameOrigin);
    }

    /** A playlist the page's player just received (requested address, final address, text, frame). */
    @JavascriptInterface
    public void playlist(String url, String finalUrl, String body, String frameOrigin) {
        if (body == null || !(body.startsWith("#EXTM3U") || body.contains("<MPD"))) return;
        // A playlist the page built itself (decoded in script, handed to its player as a blob:):
        // only our copy can answer for it, under an address of its own that never hits the network.
        if (url != null && url.startsWith("blob:")) {
            url = "https://blob.clear.invalid/" + android.net.Uri.encode(url);
            finalUrl = null;
        }
        PageCopies.put(url, finalUrl, body, frameOrigin);
        boolean master = body.contains("#EXT-X-STREAM-INF") || body.contains("<MPD");
        host.onPlaylist(url, frameOrigin, master);
    }

    @JavascriptInterface
    public void video(String event, double position) {
        host.onVideo(event, position);
    }
}
