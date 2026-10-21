package com.clearbrowser.tv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.util.Log;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.os.SystemClock;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.webkit.BackForwardCacheSettings;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

@androidx.annotation.OptIn(markerClass = {WebSettingsCompat.ExperimentalBackForwardCacheSettings.class,
        WebSettingsCompat.ExperimentalSpeculativeLoading.class})
public class MainActivity extends Activity implements CursorLayout.Listener {
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/%s Safari/537.36";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private Speed speed;
    private CursorLayout cursor;
    private FrameLayout fullscreen;
    private ProgressBar progress;
    private View toolbar;
    private EditText urlField;
    private ImageButton star;
    private TextView shield, notice;
    private WebView web;
    private String injectJs;

    /** Host of the page in the main frame; read on the WebView's IO threads. */
    private volatile String topHost = "";
    private volatile boolean shieldsOn = true;
    private final AtomicInteger pageBlocked = new AtomicInteger();
    /** Time spent deciding on requests this page (the filter's network cost), for ClearPerf. */
    private final java.util.concurrent.atomic.AtomicLong filterNanos = new java.util.concurrent.atomic.AtomicLong();
    private final AtomicInteger requests = new AtomicInteger();
    /** Blocked since the running total was last written to prefs. */
    private final AtomicInteger unsaved = new AtomicInteger();
    private final Chrome chrome = new Chrome();
    /** Sites the user let open new windows for this session only. */
    private final Set<String> sessionPopups = Collections.synchronizedSet(new HashSet<>());

    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;

    // TV player hand-off: streams the page requested, and where its video is.
    private static final int REQ_PLAYER = 1;
    private static final class Stream {
        final long at; final String url; final Bundle headers;
        /** Set when a frame's player was seen receiving it: that frame, and whether it lists qualities. */
        volatile String origin;
        volatile boolean master;
        Stream(long at, String url, Bundle headers) { this.at = at; this.url = url; this.headers = headers; }
    }
    private final List<Stream> streams = Collections.synchronizedList(new ArrayList<>());
    /** Subtitle files the page fetched or declared (url -> label/language), handed to the TV player. */
    private final java.util.LinkedHashMap<String, Bundle> subtitles = new java.util.LinkedHashMap<>();
    private final Set<String> nativeFailed = Collections.synchronizedSet(new HashSet<>());
    private volatile long playingAt;
    private volatile double videoPos;
    /** What the page's video element reports it is playing (exact for file players, blob: for MSE). */
    private volatile String playingSrc;
    /** Origin of the frame whose player is playing: servers behind it expect it as Origin/Referer. */
    private volatile String playerOrigin;
    /** WebVTT preview-thumbnail track the page's player loaded (sprite tiles), for the scrub carousel. */
    private volatile String thumbnailsUrl;
    /** Candidates tried for this video; a refused one moves on to the next before giving up. */
    private int playerRetries;
    private volatile String userAgent = "";
    /** When Back was pressed, to log how long the previous page took to come back (ClearNav). */
    private long backAt;
    private final android.content.BroadcastReceiver testHold = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context c, Intent i) {
            String d = String.valueOf(i.getStringExtra("dir"));
            int key = d.equals("up") ? KeyEvent.KEYCODE_DPAD_UP : d.equals("left") ? KeyEvent.KEYCODE_DPAD_LEFT
                    : d.equals("right") ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_DOWN;
            cursor.testHold(key, i.getIntExtra("ms", 3000));
        }
    };
    private boolean resumed;
    /** When we last returned from the TV player, so a page that resumes playing doesn't bounce us straight back. */
    private long backFromPlayerAt;
    /** The TV player is in front: the page's video stays paused behind it. */
    private boolean playerOpen;
    private boolean centerDown, centerLong;
    private AlertDialog dialog;
    /** Keeps Wi-Fi out of power-save while the browser is open: power-save naps are a classic cause of video stalls. */
    private WifiManager.WifiLock wifiLock;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
        prefs = new Prefs(this);
        cursor = findViewById(R.id.cursor);
        fullscreen = findViewById(R.id.fullscreen);
        progress = findViewById(R.id.progress);
        toolbar = findViewById(R.id.toolbar);
        urlField = findViewById(R.id.url);
        star = findViewById(R.id.star);
        shield = findViewById(R.id.shield);
        notice = findViewById(R.id.notice);
        cursor.setListener(this);
        WifiManager wm = getApplicationContext().getSystemService(WifiManager.class);
        if (wm != null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "clear:stream");
            wifiLock.setReferenceCounted(false);
        }
        injectJs = readAsset("inject.js");
        speed = new Speed(this);
        wireToolbar();
        createWebView();

        Uri data = getIntent().getData();
        if (state != null) web.restoreState(state);
        if (state == null || web.getUrl() == null) load(data != null ? data.toString() : prefs.home());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getData() != null) load(intent.getData().toString());
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        web.resumeTimers();
        if (wifiLock != null) wifiLock.acquire();
        resumed = true;
        if (Debug.ON) {
            // adb shell am broadcast -a com.clearbrowser.tv.TEST_HOLD --es dir down --ei ms 6000
            IntentFilter f = new IntentFilter("com.clearbrowser.tv.TEST_HOLD");
            if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(testHold, f, RECEIVER_EXPORTED);
            else registerReceiver(testHold, f);
        }
        // Fresh block lists when due, a little after opening, and never while a video is playing
        // in the TV player (this activity is paused then).
        ui.postDelayed(() -> {
            if (resumed && FilterUpdater.due(prefs)) FilterUpdater.run(this, prefs, false, null);
        }, 30_000);
    }

    @Override
    protected void onPause() {
        resumed = false;
        if (Debug.ON) try { unregisterReceiver(testHold); } catch (IllegalArgumentException ignored) { }
        web.onPause();
        // Behind the TV player the page may have to fetch for it (PagePipe): keep its scripts running.
        if (!playerOpen) web.pauseTimers();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        prefs.addBlocked(unsaved.getAndSet(0));
        speed.save();
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        tearDown(web);
        web = null;
        super.onDestroy();
    }

    /**
     * Android's recommended WebView teardown: detach, stop, drop history and the JS bridge, then
     * destroy, so no Activity reference or native page state is kept alive afterwards.
     */
    private void tearDown(WebView w) {
        if (w == null) return;
        if (w.getParent() instanceof ViewGroup) ((ViewGroup) w.getParent()).removeView(w);
        w.stopLoading();
        w.setWebChromeClient(null);
        w.setWebViewClient(new WebViewClient());
        w.removeJavascriptInterface("ClearBridge");
        PagePipe.clear();
        w.clearHistory();
        w.destroy();
    }

    // ---------------------------------------------------------------- WebView

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void createWebView() {
        web = new WebView(this);
        web.setBackgroundColor(0xFF070A14);
        web.setFocusable(true);
        // Keep the page's renderer (and its video decoder) at foreground priority while we're visible.
        web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true);
        cursor.addView(web, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false); // muted previews are held back in inject.js instead
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        // Pop-ups: scripts can't open windows on their own; ones that follow a click come to
        // onCreateWindow, where we decide.
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        applyUserAgent();
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true); // embedded video players need them

        // Lets the TV player download through the page when a server refuses it (see PagePipe).
        PagePipe.install(web);
        web.addJavascriptInterface(new Bridge(new Bridge.Host() {
            @Override public boolean shieldsOn() { return shieldsOn; }
            @Override public boolean holdPreviews() { return !prefs.autoplay(); }
            @Override public boolean debug() { return Debug.ON; }
            @Override public boolean liteEffects() { return prefs.liteEffects(); }
            @Override public void onVideo(String event, double position) {
                videoPos = position;
                if ("playing".equals(event)) playingAt = SystemClock.elapsedRealtime();
            }
            @Override public void onVideoStarted(double position, String subtitlesJson, String src, String frameOrigin) {
                videoPos = position;
                playingSrc = src;
                playerOrigin = frameOrigin != null && frameOrigin.startsWith("http") ? frameOrigin : null;
                try {
                    org.json.JSONArray a = new org.json.JSONArray(subtitlesJson == null ? "[]" : subtitlesJson);
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject o = a.getJSONObject(i);
                        noteSubtitle(o.optString("src"), o.optString("label"), o.optString("lang"), o.optString("type"));
                    }
                } catch (JSONException ignored) { }
                // Give the stream's own request a moment to be seen, then move it to the TV player:
                // smooth 1080p and Dolby audio (which the in-page player can't decode).
                ui.postDelayed(() -> {
                    if (!prefs.tvPlayer() || customView != null || isFinishing()) return;
                    if (SystemClock.elapsedRealtime() - backFromPlayerAt < 4000) return; // just came back
                    // Some page players restart themselves in the background (enma's did): no second TV player.
                    if (playerOpen) { media("'pause'"); return; }
                    if (playInTvPlayer()) notice("Playing in the TV player · Back returns to the page");
                }, 700);
            }
            @Override public void onPlaylist(String url, String frameOrigin, boolean master) {
                notePlaylist(url, frameOrigin, master);
            }
            @Override public void removeBookmark(String url) {
                ui.post(() -> {
                    // Any page can call the bridge, so only honour this from our own home page.
                    if (!Prefs.isHome(web.getUrl()) || url == null) return;
                    if (prefs.isBookmarked(url)) prefs.toggleBookmark(url, null);
                    renderHome();
                    notice("Bookmark removed");
                });
            }
            @Override public void clearRecent() {
                ui.post(() -> {
                    if (!Prefs.isHome(web.getUrl())) return;
                    prefs.clearRecents();
                    renderHome();
                });
            }
            @Override public void prefetch(String url) {
                ui.post(() -> {
                    // Only links within the site being shown: a page can't use us to fetch elsewhere.
                    if (shieldsOn && Blocker.sameSite(hostOf(url), topHost)) speed.prefetch(url);
                });
            }
        }), "ClearBridge");
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, injectJs, Collections.singleton("*"));
        }

        // Back/forward cache: Back shows the previous page straight from memory instead of
        // reloading it (heavy pages took 5-10 s). Kept small for a 1 GB TV: 1 page, 10 minutes;
        // Chromium also drops them by itself under memory pressure.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.BACK_FORWARD_CACHE)) {
            WebSettingsCompat.setBackForwardCacheEnabled(s, true);
            if (WebViewFeature.isFeatureSupported(WebViewFeature.BACK_FORWARD_CACHE_SETTINGS)) {
                try {
                    BackForwardCacheSettings b = WebSettingsCompat.getBackForwardCacheSettings(s);
                    b.setMaxPagesInCache(1);
                    b.setTimeoutSeconds(600);
                } catch (RuntimeException ignored) { }
            }
        }
        // Let pages' own speculation rules (prefetch/prerender hints) work.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SPECULATIVE_LOADING)) {
            WebSettingsCompat.setSpeculativeLoadingStatus(s, WebSettingsCompat.SPECULATIVE_LOADING_PRERENDER_ENABLED);
        }
        Log.i("ClearSpeed", "bfcache=" + WebViewFeature.isFeatureSupported(WebViewFeature.BACK_FORWARD_CACHE)
                + " speculative=" + WebViewFeature.isFeatureSupported(WebViewFeature.SPECULATIVE_LOADING));
        speed.setUp(this, web);
        web.setWebViewClient(new Client());
        web.setWebChromeClient(chrome);
        web.setDownloadListener((url, ua, cd, mime, len) -> notice("Downloads are off in this browser"));
    }

    private void applyUserAgent() {
        WebSettings s = web.getSettings();
        String def = WebSettings.getDefaultUserAgent(this);
        if (prefs.desktop()) {
            String ver = "120.0.0.0";
            int i = def.indexOf("Chrome/");
            if (i >= 0) ver = def.substring(i + 7).split(" ")[0];
            s.setUserAgentString(String.format(Locale.US, DESKTOP_UA, ver));
        } else {
            // Without the WebView markers, sites treat us as ordinary Chrome instead of an in-app view.
            s.setUserAgentString(def.replace("; wv", "").replace("Version/4.0 ", ""));
        }
        userAgent = s.getUserAgentString();
    }

    private void load(String url) {
        hideToolbar();
        speed.navigating(url);
        web.loadUrl(url);
    }

    private class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            if (!req.isForMainFrame()) return false;
            Uri u = req.getUrl();
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.US);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                if (scheme.equals("about") || scheme.equals("data") || scheme.equals("blob") || scheme.equals("file")) return false;
                // intent://, market:// and friends: ads use them to throw you into an app store.
                if (shieldsOn) blocked("app link", scheme + ":");
                return true;
            }
            if (!shieldsOn) return false;
            String host = u.getHost();
            boolean sameSite = Blocker.sameSite(host, topHost) || topHost.isEmpty();
            if (!sameSite && Blocker.get().isBlocked(host, prefs)) {
                blocked("ad site", host);
                return true;
            }
            // Leaving the site without a click is a hijack (pop-under tabs, timed redirects).
            if (!sameSite && !req.hasGesture() && !req.isRedirect() && !popupsAllowed(host)) {
                blocked("redirect", host);
                return true;
            }
            speed.navigating(u.toString());
            return false;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            if (req.isForMainFrame()) return null;
            String host = req.getUrl().getHost();
            if (host == null) return null;
            long t0 = System.nanoTime();
            boolean block = shieldsOn && !Blocker.sameSite(host, topHost) && Blocker.get().isBlocked(host, prefs);
            filterNanos.addAndGet(System.nanoTime() - t0);
            requests.incrementAndGet();
            if (Debug.ON) Log.d("ClearReq", (block ? "BLOCK " : "") + req.getMethod() + " " + req.getUrl());
            if (!block) {
                speed.used(req.getUrl().getScheme(), host);
                noteStream(req);
                return null;
            }
            unsaved.incrementAndGet();
            if (Debug.ON) Log.i("ClearBlock", "request " + host);
            if (pageBlocked.incrementAndGet() % 4 == 1) ui.post(MainActivity.this::updateShield);
            // No body stream: WebView fails the request like a network error, as uBlock/Brave do,
            // so the page's ad code sees "blocked" rather than an empty file it might retry.
            return new WebResourceResponse("text/plain", "utf-8", null);
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            setTop(url);
            speed.pageStarted(hostOf(url));
            pageBlocked.set(0);
            streams.clear();
            playingSrc = null;
            playerOrigin = null;
            thumbnailsUrl = null;
            playerRetries = 0;
            synchronized (subtitles) { subtitles.clear(); }
            filterNanos.set(0);
            requests.set(0);
            updateShield();
            progress.setVisibility(View.VISIBLE);
        }

        @Override
        public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
            if (backAt > 0) {
                if (Debug.ON) Log.i("ClearNav", "back to " + hostOf(url) + " shown in " + (SystemClock.uptimeMillis() - backAt) + " ms");
                backAt = 0;
            }
            String host = hostOf(url);
            if (!Blocker.sameSite(host, topHost)) setTop(url); // same-document navigations in SPAs
            updateToolbarState();
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            progress.setVisibility(View.GONE);
            updateToolbarState();
            if (Prefs.isHome(url)) renderHome();
            else if (url != null && url.startsWith("http") && view.getTitle() != null) prefs.addRecent(url, view.getTitle());
            logPerf(url);
            // WebView too old for document-start scripts: inject after load instead (weaker, but works).
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                web.evaluateJavascript(injectJs, null);
            }
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            // The page's renderer crashed or was killed for memory (1 GB TVs): rebuild rather than die.
            String url = view.getUrl();
            tearDown(view);
            createWebView();
            load(url != null ? url : prefs.home());
            notice("Page crashed and was reloaded");
            return true;
        }
    }

    private class Chrome extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int p) {
            progress.setProgress(p);
        }

        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean userGesture, Message resultMsg) {
            if (shieldsOn && !userGesture) {
                blocked("pop-up", null);
                return false;
            }
            // Catch the new window's address with a throwaway WebView, then decide here.
            WebView probe = new WebView(MainActivity.this);
            probe.setWebViewClient(new WebViewClient() {
                boolean done;

                void take(String url) {
                    if (done || url == null || url.equals("about:blank")) return;
                    done = true;
                    ui.post(() -> { probe.stopLoading(); probe.destroy(); onPopup(url); });
                }

                @Override
                public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                    take(r.getUrl().toString());
                    return true;
                }

                @Override
                public void onPageStarted(WebView v, String url, Bitmap f) {
                    take(url);
                }
            });
            ui.postDelayed(probe::destroy, 5000); // window.open('') that never navigates
            WebView.WebViewTransport t = (WebView.WebViewTransport) resultMsg.obj;
            t.setWebView(probe);
            resultMsg.sendToTarget();
            return true;
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            if (customView != null) { callback.onCustomViewHidden(); return; }
            // The site's player went fullscreen: play the stream in the TV player instead, if we have it.
            if (prefs.tvPlayer() && playInTvPlayer()) { callback.onCustomViewHidden(); return; }
            customView = view;
            customCallback = callback;
            hideToolbar();
            fullscreen.addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            fullscreen.setVisibility(View.VISIBLE);
            cursor.setAutoHide(true);
        }

        @Override
        public void onHideCustomView() {
            if (customView == null) return;
            fullscreen.removeView(customView);
            fullscreen.setVisibility(View.GONE);
            customView = null;
            if (customCallback != null) customCallback.onCustomViewHidden();
            customCallback = null;
            cursor.setAutoHide(false);
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            request.deny();
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
            cb.invoke(origin, false, false);
        }

        @Override
        public void onReceivedIcon(WebView view, Bitmap icon) {
            // Keep a small copy for the home page's tiles (works offline, no favicon service).
            String host = hostOf(view.getUrl());
            if (host.isEmpty() || icon == null) return;
            Bitmap small = Bitmap.createScaledBitmap(icon, 64, 64, true);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            small.compress(Bitmap.CompressFormat.PNG, 100, out);
            prefs.putIcon(host, "data:image/png;base64," + android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP));
        }

        @Override
        public Bitmap getDefaultVideoPoster() {
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888); // no grey play-button placeholder
        }
    }

    /** A click wants a new window. There are no tabs, so it opens here or not at all. */
    private void onPopup(String url) {
        String host = hostOf(url);
        String site = Blocker.siteOf(host);
        if (!shieldsOn) { load(url); return; }
        if (!Blocker.sameSite(host, topHost) && Blocker.get().isBlocked(host, prefs)) {
            blocked("pop-up", host);
            return;
        }
        if (topHost.isEmpty() || Blocker.sameSite(host, topHost) || popupsAllowed(host)) {
            load(url);
            return;
        }
        if (dialog != null && dialog.isShowing()) return; // pop-up storms: one question at a time
        String[] choices = {
                "Open " + site,
                "Always open " + site,
                "Block",
                "Always block " + site,
        };
        dialog = new AlertDialog.Builder(this)
                .setTitle("This page wants to open " + site)
                .setItems(choices, (d, which) -> {
                    switch (which) {
                        case 0: sessionPopups.add(site); load(url); break;
                        case 1: prefs.allowPopups(site); load(url); break;
                        case 2: blocked("pop-up", host); break;
                        case 3: prefs.blockSite(site); blocked("pop-up", host); break;
                    }
                })
                .setOnCancelListener(d -> blocked("pop-up", host))
                .show();
    }

    /** The user said yes to this site (or to new windows from the current one). */
    private boolean popupsAllowed(String host) {
        String site = Blocker.siteOf(host);
        return prefs.popupsAllowed(site) || sessionPopups.contains(site) || prefs.popupsAllowed(Blocker.siteOf(topHost));
    }

    private static final String PERF_JS = "(function(){var n=performance.getEntriesByType('navigation')[0]||{},"
            + "p=performance.getEntriesByType('paint'),f=0;for(var i=0;i<p.length;i++)if(p[i].name==='first-contentful-paint')f=p[i].startTime;"
            + "var s=window.__clearStats||{};return JSON.stringify({fcp:Math.round(f),dcl:Math.round(n.domContentLoadedEventEnd||0),"
            + "load:Math.round(n.loadEventEnd||0),dns:Math.round(n.domainLookupEnd-n.domainLookupStart||0),"
            + "connect:Math.round(n.connectEnd-n.connectStart||0),ttfb:Math.round(n.responseStart-n.requestStart||0),"
            + "html:Math.round(n.responseEnd-n.responseStart||0),res:performance.getEntriesByType('resource').length,"
            + "nodes:document.getElementsByTagName('*').length,bridgeMs:Math.round(s.bridgeMs||0),bridgeCalls:s.bridgeCalls,"
            + "scanMs:Math.round(s.scanMs||0),scanned:s.scanned,appends:s.appends,cssKB:Math.round(s.cssKB||0),previewsHeld:s.previewsHeld,longMs:Math.round(s.longMs||0),longN:s.longN,lcp:Math.round(s.lcp||0),"
            + "nrr:(function(r){if(!r)return null;var o=[];(function w(x){(x.reasons||[]).forEach(function(y){o.push(y.reason)});(x.children||[]).forEach(w)})(r);return o})(n.notRestoredReasons),"
            + "heapMB:performance.memory?Math.round(performance.memory.usedJSHeapSize/1048576):-1})})()";

    /** One log line per page: how fast it loaded and what our blocking cost it (logcat -s ClearPerf). */
    private void logPerf(String url) {
        if (!Debug.ON || url == null || !url.startsWith("http")) return;
        ui.postDelayed(() -> {
            if (!url.equals(web.getUrl())) return;
            web.evaluateJavascript(PERF_JS, r -> Log.i("ClearPerf", hostOf(url) + " " + r + " filterMs="
                    + filterNanos.get() / 1_000_000 + " requests=" + requests.get() + " blocked=" + pageBlocked.get()));
        }, 8000);
    }

    private static boolean isStreamPath(String path, boolean ranged) {
        if (path == null) return false;
        String p = path.toLowerCase(Locale.US);
        if (p.endsWith(".m3u8") || p.endsWith(".mpd")) return true;
        return ranged && (p.endsWith(".mp4") || p.endsWith(".webm") || p.endsWith(".mkv") || p.endsWith(".m4v"));
    }

    private static final java.util.regex.Pattern SUB_PATH = java.util.regex.Pattern.compile("(?i).*\\.(vtt|srt|ass|ssa)$");
    private static final java.util.regex.Pattern LANG = java.util.regex.Pattern.compile(
            "(?i)(?:^|[^a-z])(en|eng|english|es|spa|spanish|fr|fre|fra|french|de|ger|deu|german|it|ita|italian|pt|por|portuguese|"
            + "hi|hin|hindi|ta|tam|tamil|te|tel|telugu|ar|ara|arabic|ja|jpn|japanese|ko|kor|korean|zh|chi|zho|chinese|ru|rus|russian)(?:[^a-z]|$)");

    /** A subtitle file (fetched by the page or declared as a track): keep it for the TV player. */
    private void noteSubtitle(String url, String label, String lang) { noteSubtitle(url, label, lang, ""); }

    private void noteSubtitle(String url, String label, String lang, String type) {
        if (url == null || !url.startsWith("http")) return;
        // Players also use .vtt files for scrubbing thumbnails; those feed the TV player's preview carousel.
        String lower = url.toLowerCase(Locale.US);
        if (lower.matches(".*(thumb|sprite|storyboard|preview|seek).*")) { thumbnailsUrl = url; return; }
        if (lower.contains("chapters")) return;
        Uri u = Uri.parse(url);
        String name = u.getLastPathSegment() == null ? "Subtitles" : Uri.decode(u.getLastPathSegment());
        if (lang == null || lang.isEmpty()) {
            java.util.regex.Matcher m = LANG.matcher(url.toLowerCase(Locale.US));
            lang = m.find() ? m.group(1) : "";
        }
        Bundle b = new Bundle();
        b.putString("url", url);
        b.putString("label", label == null || label.isEmpty() ? name : label);
        b.putString("lang", lang);
        b.putString("type", type == null ? "" : type.toLowerCase(Locale.US));
        synchronized (subtitles) {
            // The player's own list is better labelled than a file we only saw downloaded: let it win.
            Bundle had = subtitles.get(url);
            if (had == null || label != null && !label.isEmpty()) subtitles.put(url, b);
            if (subtitles.size() > 40) subtitles.remove(subtitles.keySet().iterator().next());
        }
    }

    /** IO thread: remember stream manifests / video files with the headers the page sent for them. */
    private void noteStream(WebResourceRequest req) {
        if (SUB_PATH.matcher(req.getUrl().getPath() == null ? "" : req.getUrl().getPath()).matches()) {
            noteSubtitle(req.getUrl().toString(), null, null);
            return;
        }
        Map<String, String> h = req.getRequestHeaders();
        boolean ranged = h != null && (h.containsKey("Range") || h.containsKey("range"));
        if (!isStreamPath(req.getUrl().getPath(), ranged)) return;
        String url = req.getUrl().toString();
        synchronized (streams) {
            for (Stream s : streams) if (s.url.equals(url)) return;
            Bundle b = new Bundle();
            if (h != null) for (Map.Entry<String, String> e : h.entrySet()) {
                String k = e.getKey();
                if (k.equalsIgnoreCase("Range") || k.equalsIgnoreCase("Accept-Encoding") || k.equalsIgnoreCase("User-Agent")) continue;
                b.putString(k, e.getValue());
            }
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null && !b.containsKey("Cookie")) b.putString("Cookie", cookie);
            streams.add(new Stream(SystemClock.elapsedRealtime(), url, b));
            if (Debug.ON) Log.d("ClearReq", "stream " + url + " headers=" + h);
            if (streams.size() > 40) streams.remove(0);
        }
    }

    /**
     * A playlist a frame's player received, whatever its address looks like (some sites serve them
     * from /proxy?url=... or without an extension, which noteStream can't recognise). Known ones
     * just learn their frame; new ones get the headers that frame's player sends.
     */
    private void notePlaylist(String url, String frameOrigin, boolean master) {
        if (url == null || !url.startsWith("http")) return;
        String origin = frameOrigin != null && frameOrigin.startsWith("http") ? frameOrigin : null;
        synchronized (streams) {
            for (Stream s : streams) if (s.url.equals(url)) { s.origin = origin; s.master = master; return; }
            Bundle b = new Bundle();
            if (origin != null) {
                b.putString("Referer", origin + "/");
                // Cross-site playlist requests from a player carry its Origin; same-site ones don't.
                if (!origin.equals(Uri.parse(url).getScheme() + "://" + Uri.parse(url).getAuthority())) b.putString("Origin", origin);
            }
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) b.putString("Cookie", cookie);
            Stream s = new Stream(SystemClock.elapsedRealtime(), url, b);
            s.origin = origin;
            s.master = master;
            streams.add(s);
            if (Debug.ON) Log.d("ClearReq", "playlist from " + origin + (master ? " (qualities) " : " ") + url);
            if (streams.size() > 40) streams.remove(0);
        }
    }

    /**
     * The stream behind the video that's playing: the newest quality list the playing frame's
     * player received, else the first manifest requested around when it started (later ones are
     * its per-quality playlists), else the first one on the page.
     */
    private Stream currentStream() {
        String src = playingSrc;
        if (src != null && src.startsWith("http") && !nativeFailed.contains(src)) {
            // File players tell us exactly what they play. If its address redirected, the player is
            // now fetching the final address from the same server: that's the newest request there.
            String host = Uri.parse(src).getHost();
            synchronized (streams) {
                for (int i = streams.size() - 1; i >= 0; i--) {
                    Stream s = streams.get(i);
                    if (!nativeFailed.contains(s.url) && host != null && host.equals(Uri.parse(s.url).getHost())) return s;
                }
                for (Stream s : streams) if (s.url.equals(src)) return s;
            }
            Bundle b = new Bundle();
            String page = web.getUrl();
            if (page != null) b.putString("Referer", page);
            String cookie = CookieManager.getInstance().getCookie(src);
            if (cookie != null) b.putString("Cookie", cookie);
            return new Stream(SystemClock.elapsedRealtime(), src, b);
        }
        synchronized (streams) {
            String origin = playerOrigin;
            if (origin != null) {
                Stream any = null;
                for (int i = streams.size() - 1; i >= 0; i--) {
                    Stream s = streams.get(i);
                    if (nativeFailed.contains(s.url) || !origin.equals(s.origin)) continue;
                    if (s.master) return s;
                    if (any == null) any = s;
                }
                if (any != null) return any;
            }
            long since = playingAt - 20_000;
            for (Stream s : streams) if (s.at >= since && !nativeFailed.contains(s.url)) return s;
            for (Stream s : streams) if (!nativeFailed.contains(s.url)) return s;
        }
        return null;
    }

    private boolean playInTvPlayer() {
        if (playerOpen) { media("'pause'"); return true; }
        Stream s = currentStream();
        if (s == null) return false;
        media("'pause'");
        Intent i = new Intent(this, PlayerActivity.class)
                .putExtra(PlayerActivity.EXTRA_URL, s.url)
                .putExtra(PlayerActivity.EXTRA_HEADERS, s.headers)
                .putExtra(PlayerActivity.EXTRA_UA, userAgent)
                .putExtra(PlayerActivity.EXTRA_POS, videoPos);
        synchronized (subtitles) {
            i.putParcelableArrayListExtra(PlayerActivity.EXTRA_SUBS, new ArrayList<>(subtitles.values()));
        }
        if (playerOrigin != null) {
            i.putExtra(PlayerActivity.EXTRA_ORIGIN, playerOrigin);
            // The web view hides the Referer it sends; the player's own origin is what these servers check.
            if (!s.headers.containsKey("Referer")) s.headers.putString("Referer", playerOrigin + "/");
        }
        if (thumbnailsUrl != null) i.putExtra(PlayerActivity.EXTRA_THUMBS, thumbnailsUrl);
        // The frame that can fetch for the TV player: where the stream was seen, else the playing frame.
        String pipe = s.origin != null ? s.origin : playerOrigin;
        if (pipe != null) i.putExtra(PlayerActivity.EXTRA_PIPE, pipe);
        startActivityForResult(i, REQ_PLAYER);
        playerOpen = true;
        // The page may fetch for the TV player (PagePipe): don't let its renderer drop to "cached"
        // when it's hidden, or the TV's Memory Shrinker kills it (adj 900 over ~100 MB) and the page reloads.
        web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        return true;
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != REQ_PLAYER) return;
        playerOpen = false;
        if (web != null) web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true);
        backFromPlayerAt = SystemClock.elapsedRealtime();
        if (data == null) return;
        if (result == RESULT_OK) {
            // Back from the TV player: leave the page's video paused at the same spot.
            double pos = data.getDoubleExtra(PlayerActivity.EXTRA_POS, -1);
            if (pos >= 0) media(String.format(Locale.US, "{seek:%.1f}", pos));
        } else {
            String url = data.getStringExtra(PlayerActivity.EXTRA_URL);
            if (url != null) nativeFailed.add(url);
            // Pages often probe several copies; if this one was refused, try the next before giving up.
            if (playerRetries++ < 2 && playInTvPlayer()) return;
            media("'play'");
        }
    }

    private void setTop(String url) {
        String host = hostOf(url);
        topHost = host;
        shieldsOn = prefs.shieldsUp(Blocker.siteOf(host));
    }

    private static String hostOf(String url) {
        if (url == null || url.startsWith("file:")) return "";
        String h = Uri.parse(url).getHost();
        return h == null ? "" : h.toLowerCase(Locale.US);
    }

    private void blocked(String what, String target) {
        pageBlocked.incrementAndGet();
        unsaved.incrementAndGet();
        if (Debug.ON) Log.i("ClearBlock", what + " " + target);
        updateShield();
        notice("Blocked " + what + (target == null ? "" : " · " + target));
    }

    private long totalBlocked() {
        return prefs.totalBlocked() + unsaved.get();
    }

    private void renderHome() {
        try {
            JSONObject o = new JSONObject();
            o.put("bookmarks", withIcons(prefs.bookmarks()));
            o.put("recent", withIcons(prefs.recents()));
            o.put("blocked", totalBlocked());
            web.evaluateJavascript("render(" + o + ")", null);
        } catch (JSONException ignored) { }
    }

    private org.json.JSONArray withIcons(org.json.JSONArray list) throws JSONException {
        for (int i = 0; i < list.length(); i++) {
            JSONObject e = list.getJSONObject(i);
            String icon = prefs.icon(hostOf(e.optString("u")));
            if (icon != null) e.put("i", icon);
        }
        return list;
    }

    // ---------------------------------------------------------------- keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;

        if (toolbar.getVisibility() == View.VISIBLE) {
            // The release of the long-press that opened the toolbar mustn't reach the URL field
            // (it would pop the keyboard straight up).
            if (centerLong && !down && (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER)) {
                centerLong = centerDown = false;
                return true;
            }
            if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_MENU) {
                if (!down) hideToolbar();
                return true;
            }
            return super.dispatchKeyEvent(e);
        }

        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return cursor.onDpad(e);

            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                if (down) {
                    if (e.getRepeatCount() == 0) { centerDown = true; centerLong = false; }
                    else if (e.isLongPress() && centerDown && !centerLong) { centerLong = true; showToolbar(); }
                } else {
                    // Fullscreen video with the pointer faded out: OK only brings the pointer back.
                    if (centerDown && !centerLong && !cursor.wakeIfHidden()) cursor.tap();
                    centerDown = false;
                }
                return true;

            case KeyEvent.KEYCODE_BACK:
                if (!down) {
                    if (customView != null) chrome.onHideCustomView();
                    else if (web.canGoBack()) { backAt = SystemClock.uptimeMillis(); web.goBack(); }
                    else showToolbar();
                }
                return true;

            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_BOOKMARK:
                if (!down) showToolbar();
                return true;

            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_SPACE:
                if (!down) media("'toggle'");
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (!down) media("'play'");
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (!down) media("'pause'");
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (down) media("10");
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                if (down) media("-10");
                return true;

            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_PAGE_UP:
                if (down) scrollPage(0, -0.8f);
                return true;
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                if (down) scrollPage(0, 0.8f);
                return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private void media(String arg) {
        web.evaluateJavascript("window.__clearMedia&&__clearMedia(" + arg + ")", null);
    }

    @Override
    public boolean canScroll() {
        return customView == null;
    }

    /** Channel up/down: a quick swipe of about {@code fdy} screens (negative scrolls up). */
    private void scrollPage(float fdx, float fdy) {
        if (customView == null) cursor.swipe(fdy);
    }

    // ---------------------------------------------------------------- toolbar

    private void wireToolbar() {
        findViewById(R.id.back).setOnClickListener(v -> { if (web.canGoBack()) { backAt = SystemClock.uptimeMillis(); web.goBack(); } });
        findViewById(R.id.forward).setOnClickListener(v -> { if (web.canGoForward()) web.goForward(); });
        findViewById(R.id.reload).setOnClickListener(v -> { hideToolbar(); web.reload(); });
        findViewById(R.id.home).setOnClickListener(v -> load(prefs.home()));
        findViewById(R.id.bookmarks).setOnClickListener(v -> load(Prefs.HOME_PAGE + "#bookmarks"));
        star.setOnClickListener(v -> {
            String url = web.getUrl();
            if (url == null || Prefs.isHome(url)) return;
            boolean on = prefs.toggleBookmark(url, web.getTitle());
            notice(on ? "Bookmarked" : "Bookmark removed");
            updateToolbarState();
        });
        shield.setOnClickListener(v -> {
            String site = Blocker.siteOf(topHost);
            if (site.isEmpty()) return;
            boolean up = !prefs.shieldsUp(site);
            prefs.setShields(site, up);
            shieldsOn = up;
            notice(up ? "Blocking on for " + site : "Blocking off for " + site);
            hideToolbar();
            web.reload();
        });
        findViewById(R.id.menu).setOnClickListener(v -> showMenu());
        urlField.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE
                    || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER && ev.getAction() == KeyEvent.ACTION_DOWN)) {
                go(urlField.getText().toString());
                return true;
            }
            return false;
        });
    }

    private void go(String input) {
        String s = input.trim();
        if (s.isEmpty()) return;
        String url;
        if (s.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) url = s;
        else if (!s.contains(" ") && s.matches("^[^.]+\\..{2,}$")) url = "https://" + s;
        else url = "https://www.google.com/search?q=" + Uri.encode(s);
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        imm.hideSoftInputFromWindow(urlField.getWindowToken(), 0);
        load(url);
    }

    private void showToolbar() {
        if (customView != null) return;
        cursor.stopMoving();
        updateToolbarState();
        String url = web.getUrl();
        urlField.setText(url == null || Prefs.isHome(url) ? "" : url);
        toolbar.setVisibility(View.VISIBLE);
        urlField.requestFocus();
    }

    private void hideToolbar() {
        if (toolbar.getVisibility() != View.VISIBLE) return;
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        imm.hideSoftInputFromWindow(urlField.getWindowToken(), 0);
        toolbar.setVisibility(View.GONE);
        web.requestFocus();
    }

    private void updateToolbarState() {
        String url = web.getUrl();
        star.setImageResource(url != null && prefs.isBookmarked(url) ? R.drawable.ic_star : R.drawable.ic_star_border);
        findViewById(R.id.back).setAlpha(web.canGoBack() ? 1f : 0.4f);
        findViewById(R.id.forward).setAlpha(web.canGoForward() ? 1f : 0.4f);
        updateShield();
    }

    private void updateShield() {
        shield.setText(shieldsOn ? String.valueOf(pageBlocked.get()) : "Off");
        shield.setCompoundDrawablesRelativeWithIntrinsicBounds(
                shieldsOn ? R.drawable.ic_shield : R.drawable.ic_shield_off, 0, 0, 0);
    }

    private void showMenu() {
        String site = Blocker.siteOf(topHost);
        boolean desktop = prefs.desktop();
        String[] items = {
                "Set this page as home",
                "Use the Clear home page as home",
                desktop ? "Desktop site: on  (switch to mobile)" : "Desktop site: off  (switch to desktop)",
                site.isEmpty() ? "Ad blocking: on" : (shieldsOn ? "Turn ad blocking off for " : "Turn ad blocking on for ") + site,
                prefs.tvPlayer() ? "Videos play in the TV player: on" : "Videos play in the TV player: off",
                prefs.autoplay() ? "Autoplay: on (videos and previews start by themselves)" : "Autoplay: off (videos wait for you to press play)",
                "Play this page's video in the TV player",
                "Forget allowed / blocked sites",
                "Clear cookies and site data",
                "Update ad-block lists now  (last: " + (prefs.filtersUpdated() == 0 ? "built in"
                        : android.text.format.DateFormat.getMediumDateFormat(this).format(new java.util.Date(prefs.filtersUpdated()))) + ")",
                prefs.liteEffects() ? "Lighter effects (no blur, light backgrounds): on" : "Lighter effects (no blur, light backgrounds): off",
                "Exit",
        };
        dialog = new AlertDialog.Builder(this)
                .setTitle("Clear Browser · " + String.format(Locale.US, "%,d", totalBlocked()) + " blocked")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: {
                            String url = web.getUrl();
                            if (url != null) { prefs.setHome(url); notice("Home set to " + (topHost.isEmpty() ? "the Clear home page" : topHost)); }
                            break;
                        }
                        case 1: prefs.setHome(Prefs.HOME_PAGE); notice("Home is the Clear home page"); break;
                        case 2: prefs.setDesktop(!desktop); applyUserAgent(); hideToolbar(); web.reload(); break;
                        case 3: shield.performClick(); break;
                        case 4:
                            prefs.setTvPlayer(!prefs.tvPlayer());
                            notice(prefs.tvPlayer() ? "Videos open in the TV player" : "Videos stay in the page");
                            break;
                        case 5:
                            prefs.setAutoplay(!prefs.autoplay());
                            notice(prefs.autoplay() ? "Autoplay on" : "Autoplay off: videos wait for play");
                            web.reload();
                            break;
                        case 6:
                            hideToolbar();
                            if (!playInTvPlayer()) notice("No video stream found on this page yet: start the video first");
                            break;
                        case 7: prefs.resetSiteChoices(); sessionPopups.clear(); notice("Site choices cleared"); break;
                        case 8:
                            CookieManager.getInstance().removeAllCookies(null);
                            WebStorage.getInstance().deleteAllData();
                            prefs.clearRecents();
                            speed.forget();
                            web.clearCache(true);
                            notice("Cookies and site data cleared");
                            break;
                        case 9:
                            notice("Updating ad-block lists…");
                            FilterUpdater.run(this, prefs, true, (ok, msg) ->
                                    notice(ok ? "Ad-block lists updated · " + msg : "Update failed, keeping current lists"));
                            break;
                        case 10:
                            prefs.setLiteEffects(!prefs.liteEffects());
                            notice(prefs.liteEffects() ? "Lighter effects on" : "Sites keep their full effects");
                            hideToolbar();
                            web.reload();
                            break;
                        case 11: finishAndRemoveTask(); break;
                    }
                })
                .show();
    }

    // ---------------------------------------------------------------- misc

    private final Runnable hideNotice = () -> notice.animate().alpha(0f).setDuration(250)
            .withEndAction(() -> notice.setVisibility(View.GONE));

    private void notice(String text) {
        ui.post(() -> {
            notice.setText(text);
            notice.animate().cancel();
            notice.setAlpha(1f);
            notice.setVisibility(View.VISIBLE);
            ui.removeCallbacks(hideNotice);
            ui.postDelayed(hideNotice, 2600);
        });
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            byte[] buf = new byte[in.available()];
            int off = 0, n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
            return new String(buf, 0, off, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
