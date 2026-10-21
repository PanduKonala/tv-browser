package com.clearbrowser.tv;

import android.app.Activity;
import android.graphics.Color;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.SubtitleView;

import java.util.ArrayList;
import java.util.List;

import java.util.HashMap;
import java.util.Map;

/**
 * Plays a stream the page was playing, natively. ExoPlayer hands decoded frames straight to the
 * TV's video plane, so 1080p doesn't go through WebView's GPU compositing, and RAM is just the
 * buffer below. Finishes with RESULT_CANCELED + the URL if the stream won't play here, and the
 * browser then uses its own player for it.
 */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class PlayerActivity extends Activity {
    static final String EXTRA_URL = "url", EXTRA_HEADERS = "headers", EXTRA_POS = "pos", EXTRA_UA = "ua",
            EXTRA_SUBS = "subs", EXTRA_ORIGIN = "origin", EXTRA_THUMBS = "thumbs";
    private static final String[] SIZES = {"Small", "Medium", "Large", "Extra large"};
    private static final float[] SIZE_SCALE = {0.8f, 1.0f, 1.3f, 1.65f};
    private static final String[] STYLES = {"White, outlined", "Yellow, outlined", "White on dark box", "Yellow on dark box"};
    private static final String[] POSITIONS = {"Bottom", "Raised"};
    private static final String TAG = "ClearPlayer";

    private ExoPlayer player;
    private DefaultTrackSelector selector;
    private PlayerView view;
    private int stalls, dropped;
    private boolean wasReady;
    private String url;
    /** The item being played, so a failed subtitle file can be dropped without stopping playback. */
    private MediaItem.Builder itemBuilder;
    private final List<MediaItem.SubtitleConfiguration> subConfigs = new ArrayList<>();
    /** Subtitle file addresses, and the headers their servers expect (the page player's Origin). */
    private final java.util.Set<String> subtitleUris = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private final Map<String, String> subtitleHeaders = new HashMap<>();
    private Prefs prefs;
    private PlayerPanel panel;
    /** Subtitle files the page offered; the one being shown (drawn by us), and the delay. */
    private ExternalSubs ext;
    private ExternalSubs.Entry activeExt;
    private long subDelayMs;
    private SubtitleView ourSubs;
    private String expandedLang;
    private boolean subsDecided;
    private boolean centerDown, centerLong;
    // Scrubbing with Left/Right: target time, where it started, and whether to resume playing.
    private ScrubOverlay scrub;
    private Thumbnails thumbs;
    private boolean scrubbing, scrubWasPlaying;
    private long scrubStart, scrubTarget;
    private final Runnable commitScrub = this::commitScrub;
    /** Scrub started on the controls' timeline: bring the controls back there afterwards. */
    private boolean scrubFromTimeline;
    private static final int ACCENT = 0xFFE50914; // red: visible on any picture, unlike the default white

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // Our one-bar controls (layout/clear_controls): timeline on top, buttons in a single row.
        view = (PlayerView) getLayoutInflater().inflate(R.layout.player_view, null);
        view.setKeepScreenOn(true);
        // Controls only when asked for (Up/Down, or OK to pause): auto-showing them on pause would
        // grab Left/Right away from scrubbing.
        view.setControllerAutoShow(false);
        styleControls();
        FrameLayout root = new FrameLayout(this);
        root.addView(view);
        // Subtitles we draw ourselves (files from the page, and delayed built-in ones).
        ourSubs = new SubtitleView(this);
        root.addView(ourSubs, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        scrub = new ScrubOverlay(this);
        root.addView(scrub, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        centerSign = new android.widget.ImageView(this);
        android.graphics.drawable.GradientDrawable disc = new android.graphics.drawable.GradientDrawable();
        disc.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        disc.setColor(0x99000000);
        centerSign.setBackground(disc);
        int signPad = Math.round(22 * getResources().getDisplayMetrics().density);
        centerSign.setPadding(signPad, signPad, signPad, signPad);
        centerSign.setVisibility(View.GONE);
        int signSize = Math.round(104 * getResources().getDisplayMetrics().density);
        root.addView(centerSign, new FrameLayout.LayoutParams(signSize, signSize, android.view.Gravity.CENTER));
        panel = new PlayerPanel(this, this::panelRows);
        root.addView(panel, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        prefs = new Prefs(this);
        applySubtitleLook();
        wireBar();
        // The controls' CC and gear buttons open our panel instead of the stock pop-ups.
        View cc = view.findViewById(androidx.media3.ui.R.id.exo_subtitle);
        if (cc != null) cc.setOnClickListener(v -> openPanel(PlayerPanel.SUBS));
        View gear = view.findViewById(androidx.media3.ui.R.id.exo_settings);
        if (gear != null) gear.setOnClickListener(v -> openPanel(PlayerPanel.QUALITY));
    }

    @Override
    protected void onStart() {
        super.onStart();
        Intent in = getIntent();
        url = in.getStringExtra(EXTRA_URL);
        Bundle h = in.getBundleExtra(EXTRA_HEADERS);
        Map<String, String> headers = new HashMap<>();
        if (h != null) for (String k : h.keySet()) headers.put(k, h.getString(k));
        if (Debug.ON) Log.i(TAG, "open " + url + " headers=" + headers.keySet());
        // Preview pictures for scrubbing: the site's thumbnail track, else frames from the video.
        // Thumbnail servers check where the request comes from, like the page's own player.
        Map<String, String> thumbHeaders = new HashMap<>(headers);
        thumbHeaders.put("User-Agent", in.getStringExtra(EXTRA_UA));
        String origin = in.getStringExtra(EXTRA_ORIGIN);
        if (origin != null) {
            thumbHeaders.put("Origin", origin);
            if (!thumbHeaders.containsKey("Referer")) thumbHeaders.put("Referer", origin + "/");
            subtitleHeaders.put("Origin", origin);
            subtitleHeaders.put("Referer", origin + "/");
            subtitleHeaders.put("Sec-Fetch-Mode", "cors");
            subtitleHeaders.put("Sec-Fetch-Site", "cross-site");
            subtitleHeaders.put("Sec-Fetch-Dest", "empty");
        }
        thumbs = new Thumbnails(in.getStringExtra(EXTRA_THUMBS), url, thumbHeaders);

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(in.getStringExtra(EXTRA_UA))
                .setDefaultRequestProperties(headers)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(15000);
        // Up to 1080p / 60 fps, whatever the screen's UI resolution; within that, ExoPlayer adapts to the connection.
        selector = new DefaultTrackSelector(this);
        // Tunneled playback when the TV's decoder supports it (these TCL/MediaTek ones do): the video
        // hardware handles frame timing and audio sync instead of the app pushing every frame, so a
        // weak, busy CPU can't cause stutter. ExoPlayer quietly skips it for formats that can't tunnel.
        selector.setParameters(selector.buildUponParameters()
                .clearViewportSizeConstraints()
                .setMaxVideoSize(1920, 1080)
                .setMaxVideoFrameRate(60)
                .setTunnelingEnabled(prefs.tunneling()));
        // Lean RAM: buffer 15-40 s ahead (about 30 MB at 1080p), start after 1.5 s.
        DefaultLoadControl load = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(15_000, 40_000, 1_500, 3_000)
                .setTargetBufferBytes(32 * 1024 * 1024)
                .setPrioritizeTimeOverSizeThresholds(false)
                .build();
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(new androidx.media3.datasource.ResolvingDataSource.Factory(
                        new DefaultDataSource.Factory(this, new ParallelHttpDataSource.Factory(http)),
                        // Subtitle servers check where requests come from, like the page's player does.
                        spec -> subtitleUris.contains(spec.uri.toString()) && !subtitleHeaders.isEmpty()
                                ? spec.withAdditionalHeaders(subtitleHeaders) : spec)))
                .setTrackSelector(selector)
                .setLoadControl(load)
                .build();
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int s) {
                if (s == Player.STATE_READY) wasReady = true;
                else if (s == Player.STATE_BUFFERING && wasReady) stalls++;
                else if (s == Player.STATE_ENDED) finish();
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException e) {
                if (dropFailedSubtitle(e)) return;
                Log.w(TAG, "can't play " + url, e);
                for (Throwable c = e; c != null; c = c.getCause()) {
                    if (c instanceof androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                        androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException r =
                                (androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) c;
                        StringBuilder hb = new StringBuilder();
                        for (java.util.Map.Entry<String, java.util.List<String>> h : r.headerFields.entrySet())
                            if (h.getKey() != null && h.getKey().toLowerCase().matches("server|cf-.*|content-type|x-.*|retry-after"))
                                hb.append(h.getKey()).append('=').append(h.getValue()).append(' ');
                        String body = new String(r.responseBody, 0, Math.min(300, r.responseBody.length));
                        Log.w(TAG, "refused " + r.responseCode + " " + r.dataSpec.uri + " | " + hb + "| body: " + body.replaceAll("\\s+", " "));
                    }
                }
                Toast.makeText(PlayerActivity.this, "TV player can't play this one, using the web player",
                        Toast.LENGTH_LONG).show();
                setResult(RESULT_CANCELED, new Intent().putExtra(EXTRA_URL, url));
                // Release here, so onStop doesn't overwrite this result with a normal "back" one.
                view.removeCallbacks(stats);
                player.release();
                player = null;
                finish();
            }
        });
        player.addAnalyticsListener(new AnalyticsListener() {
            @Override
            public void onDroppedVideoFrames(@NonNull EventTime t, int count, long elapsedMs) {
                dropped += count;
            }

            @Override
            public void onVideoInputFormatChanged(@NonNull EventTime t, @NonNull Format f, DecoderReuseEvaluation r) {
                Log.i(TAG, "video " + f.width + "x" + f.height + " @" + f.frameRate + "fps " + f.bitrate / 1000 + "kbps");
            }
        });
        view.setPlayer(player);

        MediaItem.Builder item = new MediaItem.Builder().setUri(Uri.parse(url));
        String path = Uri.parse(url).getPath();
        path = path == null ? "" : path.toLowerCase();
        if (path.endsWith(".m3u8")) item.setMimeType(MimeTypes.APPLICATION_M3U8);
        else if (path.endsWith(".mpd")) item.setMimeType(MimeTypes.APPLICATION_MPD);
        // Subtitle files the page offers: listed in our panel, fetched only when picked, drawn by us.
        Map<String, String> extHeaders = new HashMap<>(subtitleHeaders);
        extHeaders.put("User-Agent", in.getStringExtra(EXTRA_UA));
        ext = new ExternalSubs(extHeaders);
        ArrayList<Bundle> subs = in.getParcelableArrayListExtra(EXTRA_SUBS);
        if (subs != null) for (Bundle b : subs) {
            ext.add(b.getString("url", ""), b.getString("label", ""), b.getString("lang", ""), b.getString("type", ""));
        }
        // Built-in subtitles stay off until we've looked at what's available (decideSubtitles):
        // otherwise a file's "default" Spanish track would win over English from the page.
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
        subsDecided = false;
        player.addListener(new Player.Listener() {
            @Override
            public void onCues(@NonNull androidx.media3.common.text.CueGroup cg) {
                // Built-in subtitles with a delay: shown late, by us.
                if (activeExt != null || subDelayMs <= 0) return;
                List<androidx.media3.common.text.Cue> cues = cg.cues;
                ourSubs.postDelayed(() -> { if (activeExt == null && subDelayMs > 0) ourSubs.setCues(cues); }, subDelayMs);
            }

            @Override
            public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
                updatePlayIcon();
                if (playWhenReady && pauseSignShown) hideCenterSign(); // resumed some other way
            }

            @Override
            public void onTracksChanged(@NonNull Tracks tracks) {
                if (!subsDecided && player != null && !tracks.isEmpty()) { subsDecided = true; decideSubtitles(tracks); }
                if (Debug.ON) Log.i(TAG, "tunneling " + (player != null && player.isTunnelingEnabled() ? "active" : "off"));
                for (Tracks.Group g : tracks.getGroups()) {
                    if (g.getType() != C.TRACK_TYPE_TEXT && g.getType() != C.TRACK_TYPE_AUDIO) continue;
                    for (int i = 0; i < g.length; i++) {
                        Format f = g.getTrackFormat(i);
                        Log.i(TAG, (g.getType() == C.TRACK_TYPE_TEXT ? "subtitle " : "audio ") + trackName(f)
                                + " " + f.sampleMimeType + (g.isTrackSelected(i) ? " [on]" : ""));
                    }
                }
            }
        });
        itemBuilder = item;
        player.setMediaItem(item.build(), Math.max(0, (long) (in.getDoubleExtra(EXTRA_POS, 0) * 1000)));
        player.prepare();
        player.play();
        if (Debug.ON) view.postDelayed(stats, 10_000);
    }

    private final Runnable stats = new Runnable() {
        @Override
        public void run() {
            if (player == null) return;
            Format f = player.getVideoFormat();
            Log.i(TAG, "pos=" + player.getCurrentPosition() / 1000 + "s stalls=" + stalls + " dropped=" + dropped
                    + " buffered=" + (player.getBufferedPosition() - player.getCurrentPosition()) / 1000 + "s"
                    + (f == null ? "" : " " + f.height + "p"));
            view.postDelayed(this, 10_000);
        }
    };

    /** A subtitle file the page used was refused or broken: play on without it, from the same spot. */
    private boolean dropFailedSubtitle(PlaybackException e) {
        if (subConfigs.isEmpty() || player == null) return false;
        Uri failed = null;
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof androidx.media3.datasource.HttpDataSource.HttpDataSourceException) {
                failed = ((androidx.media3.datasource.HttpDataSource.HttpDataSourceException) c).dataSpec.uri;
                break;
            }
        }
        boolean removed = false;
        for (int i = subConfigs.size() - 1; i >= 0; i--) {
            // Unknown culprit: drop all page subtitles once; the video's own tracks stay.
            if (failed == null || subConfigs.get(i).uri.equals(failed)) { subConfigs.remove(i); removed = true; }
        }
        if (!removed) return false;
        Log.w(TAG, "subtitle file failed, playing on without it: " + failed);
        long pos = player.getCurrentPosition();
        if (pos <= 0) pos = (long) (getIntent().getDoubleExtra(EXTRA_POS, 0) * 1000);
        itemBuilder.setSubtitleConfigurations(new ArrayList<>(subConfigs));
        player.setMediaItem(itemBuilder.build(), pos);
        player.prepare();
        player.play();
        return true;
    }

    // ---------------------------------------------------------------- subtitles

    /** Size, colours and position from the saved choices, applied to the player's subtitle view. */
    private void applySubtitleLook() {
        if (ourSubs != null) applyLook(ourSubs);
        if (view.getSubtitleView() != null) applyLook(view.getSubtitleView());
    }

    private void applyLook(SubtitleView sv) {
        int style = prefs.subtitleStyle();
        int fg = (style == 1 || style == 3) ? Color.rgb(255, 221, 64) : Color.WHITE;
        boolean box = style >= 2;
        sv.setStyle(new CaptionStyleCompat(fg, box ? 0xB3000000 : Color.TRANSPARENT, Color.TRANSPARENT,
                box ? CaptionStyleCompat.EDGE_TYPE_NONE : CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, null));
        // Our look wins over colours/fonts baked into the file, so the choice always shows.
        sv.setApplyEmbeddedStyles(false);
        sv.setApplyEmbeddedFontSizes(false);
        sv.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * SIZE_SCALE[Math.min(3, prefs.subtitleSize())]);
        sv.setBottomPaddingFraction(prefs.subtitlePosition() == 1 ? 0.2f : SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION);
    }

    private static String trackName(Format f) {
        String l = f.label != null ? f.label : "";
        String lang = f.language != null && !f.language.equals("und") ? new java.util.Locale(f.language).getDisplayLanguage() : "";
        if (l.isEmpty()) return lang.isEmpty() ? "Subtitles" : lang;
        return lang.isEmpty() || l.toLowerCase().contains(lang.toLowerCase()) ? l : l + " (" + lang + ")";
    }

    // ---------------------------------------------------------------- subtitles: choosing and drawing

    /** One subtitle choice: a track inside the video, or a file the page offered. */
    private static final class SubOption {
        final String name, lang;
        final Tracks.Group group; final int index;   // built-in
        final ExternalSubs.Entry entry;               // from the page
        SubOption(String name, String lang, Tracks.Group group, int index, ExternalSubs.Entry entry) {
            this.name = name; this.lang = lang; this.group = group; this.index = index; this.entry = entry;
        }
    }

    private List<SubOption> subOptions() {
        List<SubOption> out = new ArrayList<>();
        if (player != null) for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() != C.TRACK_TYPE_TEXT) continue;
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSupported(i)) continue;
                Format f = g.getTrackFormat(i);
                out.add(new SubOption(trackName(f) + "  · in video", Lang.norm(f.language), g, i, null));
            }
        }
        if (ext != null) for (ExternalSubs.Entry e : ext.entries) {
            String n = e.label != null && !e.label.isEmpty() ? e.label : Lang.name(e.lang);
            out.add(new SubOption(n.isEmpty() ? "Subtitles" : n, e.lang, null, 0, e));
        }
        return out;
    }

    private boolean isActive(SubOption o) {
        if (o.entry != null) return o.entry == activeExt;
        if (activeExt != null || player == null) return false;
        if (player.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) return false;
        return o.group.isTrackSelected(o.index);
    }

    /** First look at the tracks: the remembered language, else the TV's, else off — never a random default. */
    private void decideSubtitles(Tracks tracks) {
        String want = prefs.subtitleLanguage();
        if ("off".equals(want)) { selectOff(false); return; }
        String lang = Lang.norm(want.isEmpty() ? java.util.Locale.getDefault().getLanguage() : want);
        SubOption pick = null;
        for (SubOption o : subOptions()) if (o.group != null && lang.equals(o.lang)) { pick = o; break; }
        if (pick == null) for (SubOption o : subOptions()) if (o.entry != null && lang.equals(o.lang)) { pick = o; break; }
        if (pick != null) select(pick, false); else selectOff(false);
    }

    private void select(SubOption o, boolean remember) {
        if (o.entry != null) {
            // From the page: built-in off, fetch the file, draw it ourselves.
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
            ExternalSubs.Entry e = o.entry;
            activeExt = e;
            ourSubs.setCues(java.util.Collections.emptyList());
            ext.load(e, (entry, ok) -> {
                if (activeExt != entry) return;
                if (!ok) {
                    activeExt = null;
                    Toast.makeText(this, "Couldn't load those subtitles; pick another", Toast.LENGTH_SHORT).show();
                    panel.refresh();
                    return;
                }
                ourSubs.removeCallbacks(extTick);
                ourSubs.post(extTick);
                panel.refresh();
            });
        } else {
            activeExt = null;
            ourSubs.removeCallbacks(extTick);
            ourSubs.setCues(java.util.Collections.emptyList());
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(new TrackSelectionOverride(o.group.getMediaTrackGroup(), o.index)).build());
        }
        updateSubtitleViews();
        if (remember) prefs.setSubtitleLanguage(o.lang == null ? "" : o.lang);
    }

    private void selectOff(boolean remember) {
        activeExt = null;
        ourSubs.removeCallbacks(extTick);
        ourSubs.setCues(java.util.Collections.emptyList());
        if (player != null) player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
        if (remember) prefs.setSubtitleLanguage("off");
    }

    /** Built-in subtitles show in the player's own view unless delayed; ours show page files and delayed ones. */
    private void updateSubtitleViews() {
        SubtitleView sv = view.getSubtitleView();
        boolean ownDelayed = activeExt == null && subDelayMs > 0;
        if (sv != null) sv.setVisibility(activeExt != null || ownDelayed ? View.INVISIBLE : View.VISIBLE);
        if (activeExt == null && !ownDelayed) ourSubs.setCues(java.util.Collections.emptyList());
    }

    /** Draws the page's subtitle file at the playing position, shifted by the delay. */
    private final Runnable extTick = new Runnable() {
        private List<androidx.media3.common.text.Cue> last = java.util.Collections.emptyList();
        @Override public void run() {
            if (activeExt == null || player == null) return;
            long pos = (player.getCurrentPosition() - subDelayMs) * 1000;
            List<androidx.media3.common.text.Cue> now = scrubbing ? java.util.Collections.emptyList() : ExternalSubs.at(activeExt, pos);
            if (!now.equals(last)) { ourSubs.setCues(now); last = now; }
            ourSubs.postDelayed(this, 80);
        }
    };

    private void openPanel(String category) {
        view.hideController();
        hideCenterSign();
        panel.open(category);
    }

    private static String delayText(long ms) {
        return (ms >= 0 ? "+" : "−") + String.format(java.util.Locale.US, "%.1f s", Math.abs(ms) / 1000f);
    }

    /** What the panel shows for each category. */
    private List<PlayerPanel.Row> panelRows(String cat) {
        List<PlayerPanel.Row> rows = PlayerPanel.list();
        if (player == null) return rows;
        switch (cat) {
            case PlayerPanel.SUBS: subtitleRows(rows); break;
            case PlayerPanel.AUDIO: audioRows(rows); break;
            case PlayerPanel.QUALITY: qualityRows(rows); break;
            case PlayerPanel.STYLE:
                rows.add(PlayerPanel.Row.of("Size").detail(SIZES[prefs.subtitleSize()]).adjust(d -> {
                    prefs.setSubtitleSize(Math.max(0, Math.min(SIZES.length - 1, prefs.subtitleSize() + d))); applySubtitleLook(); }));
                rows.add(PlayerPanel.Row.of("Colour").detail(STYLES[prefs.subtitleStyle()]).adjust(d -> {
                    prefs.setSubtitleStyle((prefs.subtitleStyle() + d + STYLES.length) % STYLES.length); applySubtitleLook(); }));
                rows.add(PlayerPanel.Row.of("Position").detail(POSITIONS[prefs.subtitlePosition()]).adjust(d -> {
                    prefs.setSubtitlePosition((prefs.subtitlePosition() + d + POSITIONS.length) % POSITIONS.length); applySubtitleLook(); }));
                break;
            case PlayerPanel.PLAYBACK:
                rows.add(PlayerPanel.Row.of("Smooth playback (hardware tunneling)")
                        .detail(prefs.tunneling() ? (player.isTunnelingEnabled() ? "On" : "On · not used here") : "Off")
                        .click(() -> {
                            prefs.setTunneling(!prefs.tunneling());
                            selector.setParameters(selector.buildUponParameters().setTunnelingEnabled(prefs.tunneling()));
                        }));
                rows.add(PlayerPanel.Row.of("Change server (back to the page)").click(() -> {
                    // The page's own player switches servers; the new one comes back here when it plays.
                    setResult(RESULT_OK, new Intent().putExtra(EXTRA_POS, player.getCurrentPosition() / 1000.0));
                    finish();
                }));
                break;
        }
        return rows;
    }

    private void subtitleRows(List<PlayerPanel.Row> rows) {
        boolean builtInActive = activeExt == null && !player.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_TEXT);
        rows.add(PlayerPanel.Row.of("Delay").detail(delayText(subDelayMs)).adjust(d -> {
            long next = Math.max(-30_000, Math.min(30_000, subDelayMs + d * 500L));
            // Built-in subtitles can be shown later, not earlier (they arrive with the video).
            if (builtInActive && next < 0) next = 0;
            subDelayMs = next;
            updateSubtitleViews();
        }));
        List<SubOption> all = subOptions();
        boolean anyOn = false;
        for (SubOption o : all) if (isActive(o)) anyOn = true;
        rows.add(PlayerPanel.Row.of("Off").checked(!anyOn).click(() -> selectOff(true)));
        if (all.isEmpty()) { rows.add(PlayerPanel.Row.of("No subtitles offered for this video").dim()); return; }
        // Group by language, the TV's language first, then A-Z.
        String mine = Lang.norm(java.util.Locale.getDefault().getLanguage());
        java.util.LinkedHashMap<String, List<SubOption>> byLang = new java.util.LinkedHashMap<>();
        List<String> keys = new ArrayList<>();
        for (SubOption o : all) {
            String key = o.lang == null || o.lang.isEmpty() ? "?" + o.name.toLowerCase() : o.lang;
            if (!byLang.containsKey(key)) { byLang.put(key, new ArrayList<>()); keys.add(key); }
            byLang.get(key).add(o);
        }
        keys.sort((a, b) -> a.equals(mine) ? -1 : b.equals(mine) ? 1 : nameFor(a, byLang).compareToIgnoreCase(nameFor(b, byLang)));
        for (String key : keys) {
            List<SubOption> group = byLang.get(key);
            String name = nameFor(key, byLang);
            String flag = key.startsWith("?") ? "\uD83C\uDF10" : Lang.flag(key);
            boolean groupOn = false;
            for (SubOption o : group) if (isActive(o)) groupOn = true;
            if (group.size() == 1) {
                SubOption o = group.get(0);
                rows.add(PlayerPanel.Row.of(o.entry != null || key.startsWith("?") ? name : o.name).flag(flag).checked(groupOn)
                        .click(() -> select(o, true)));
                continue;
            }
            boolean open = key.equals(expandedLang) || groupOn && expandedLang == null;
            PlayerPanel.Row head = PlayerPanel.Row.of(name).flag(flag).detail(String.valueOf(group.size())).checked(groupOn && !open)
                    .click(() -> expandedLang = open ? "" : key);
            if (open) head.chevronOpen = true; else head.chevronClosed = true;
            rows.add(head);
            if (!open) continue;
            int n = 1;
            for (SubOption o : group) {
                String label = o.name.equalsIgnoreCase(name) ? name + (n > 1 ? " " + n : "") : o.name;
                n++;
                rows.add(PlayerPanel.Row.of(label).indent(1).checked(isActive(o)).click(() -> select(o, true)));
            }
        }
    }

    private static String nameFor(String key, java.util.Map<String, List<SubOption>> byLang) {
        if (key.startsWith("?")) return byLang.get(key).get(0).name;
        String n = Lang.name(key);
        return n.isEmpty() ? key : n;
    }

    private void audioRows(List<PlayerPanel.Row> rows) {
        boolean any = false;
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() != C.TRACK_TYPE_AUDIO) continue;
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSupported(i)) continue;
                any = true;
                Format f = g.getTrackFormat(i);
                String lang = Lang.norm(f.language);
                String name = f.label != null ? f.label : lang.isEmpty() ? "Audio " + (i + 1) : Lang.name(lang);
                final Tracks.Group grp = g; final int idx = i;
                rows.add(PlayerPanel.Row.of(name).flag(lang.isEmpty() ? null : Lang.flag(lang)).detail(codec(f))
                        .checked(g.isTrackSelected(i)).click(() -> player.setTrackSelectionParameters(
                                player.getTrackSelectionParameters().buildUpon()
                                        .setOverrideForType(new TrackSelectionOverride(grp.getMediaTrackGroup(), idx)).build())));
            }
        }
        if (!any) rows.add(PlayerPanel.Row.of("No audio tracks to choose from").dim());
    }

    private static String codec(Format f) {
        String m = f.sampleMimeType == null ? "" : f.sampleMimeType;
        String c = m.contains("eac3") ? "Dolby Digital Plus" : m.contains("ac3") ? "Dolby Digital" : m.contains("ac4") ? "Dolby AC-4"
                : m.contains("dts") ? "DTS" : m.contains("mp4a") || m.contains("aac") ? "AAC" : m.contains("opus") ? "Opus"
                : m.contains("vorbis") ? "Vorbis" : m.contains("mpeg") ? "MP3" : m.replace("audio/", "").toUpperCase();
        String ch = f.channelCount == 6 ? "5.1" : f.channelCount == 8 ? "7.1" : f.channelCount == 2 ? "Stereo"
                : f.channelCount == 1 ? "Mono" : f.channelCount > 0 ? f.channelCount + " ch" : "";
        return ch.isEmpty() ? c : c + " · " + ch;
    }

    private void qualityRows(List<PlayerPanel.Row> rows) {
        List<int[]> opts = new ArrayList<>(); // {groupIndex, trackIndex, height, bitrate}
        List<Tracks.Group> groups = player.getCurrentTracks().getGroups();
        boolean overridden = false;
        for (int gi = 0; gi < groups.size(); gi++) {
            Tracks.Group g = groups.get(gi);
            if (g.getType() != C.TRACK_TYPE_VIDEO) continue;
            if (player.getTrackSelectionParameters().overrides.containsKey(g.getMediaTrackGroup())) overridden = true;
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSupported(i)) continue;
                Format f = g.getTrackFormat(i);
                opts.add(new int[]{gi, i, f.height, f.bitrate});
            }
        }
        opts.sort((a, b) -> b[2] != a[2] ? b[2] - a[2] : b[3] - a[3]);
        Format playing = player.getVideoFormat();
        String now = playing == null ? "" : "playing " + playing.height + "p";
        if (opts.size() <= 1) {
            rows.add(PlayerPanel.Row.of((opts.isEmpty() || opts.get(0)[2] <= 0 ? (playing == null ? "?" : playing.height + "p") : opts.get(0)[2] + "p"))
                    .detail("the only quality this server offers").checked(true).dim());
            return;
        }
        rows.add(PlayerPanel.Row.of("Auto").detail(overridden ? "" : now).checked(!overridden).click(() ->
                player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_VIDEO).build())));
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int[] o : opts) {
            if (o[2] <= 0 || !seen.add(o[2])) continue; // one row per resolution
            Tracks.Group g = groups.get(o[0]);
            boolean on = overridden && g.isTrackSelected(o[1]);
            String rate = o[3] > 0 ? String.format(java.util.Locale.US, "%.1f Mbps", o[3] / 1e6) : "";
            rows.add(PlayerPanel.Row.of(o[2] + "p").detail(rate).checked(on).click(() ->
                    player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                            .setOverrideForType(new TrackSelectionOverride(g.getMediaTrackGroup(), o[1])).build())));
        }
    }

    private void remember(Format f) {
        prefs.setSubtitleLanguage(f.language != null && !f.language.equals("und") ? f.language : "");
    }

    // ---------------------------------------------------------------- the control bar

    private boolean muted;
    /** The big play/pause sign in the middle of the picture (OK with the bar hidden). */
    private android.widget.ImageView centerSign;
    private boolean pauseSignShown;
    /** When play/pause last showed the bar: the bar's "visible" event can fire more than once. */
    private long toggledAt;

    private void wireBar() {
        click(R.id.clear_play, this::togglePlayCentered);
        click(R.id.clear_back10, () -> seekBy(-10_000));
        click(R.id.clear_fwd10, () -> seekBy(10_000));
        click(R.id.clear_volume, () -> {
            muted = !muted;
            if (player != null) player.setVolume(muted ? 0f : 1f);
            ((android.widget.ImageButton) view.findViewById(R.id.clear_volume))
                    .setImageResource(muted ? R.drawable.ic_p_mute : R.drawable.ic_p_volume);
            ((android.widget.ProgressBar) view.findViewById(R.id.clear_volume_bar)).setProgress(muted ? 0 : 100);
        });
        click(R.id.clear_subs, () -> openPanel(PlayerPanel.SUBS));
        click(R.id.clear_servers, () -> openPanel(PlayerPanel.PLAYBACK)); // "Change server" lives there
        click(R.id.clear_settings, () -> openPanel(PlayerPanel.QUALITY));
        // When the bar appears, start on play/pause.
        view.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) visibility -> {
            boolean shown = visibility == View.VISIBLE;
            // Subtitles move up while the bar is there, so they don't sit on top of it.
            for (SubtitleView sv : new SubtitleView[]{view.getSubtitleView(), ourSubs}) {
                if (sv != null) sv.setBottomPaddingFraction(shown ? 0.24f
                        : prefs.subtitlePosition() == 1 ? 0.2f : SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION);
            }
            View tbar = view.findViewById(androidx.media3.ui.R.id.exo_progress);
            if (tbar instanceof androidx.media3.ui.DefaultTimeBar && !tbar.hasFocus()) {
                ((androidx.media3.ui.DefaultTimeBar) tbar).hideScrubber(false); // knob only when the timeline is selected
            }
            if (shown) {
                if (android.os.SystemClock.uptimeMillis() - toggledAt > 1500) hideCenterSign();
                View b = view.findViewById(R.id.clear_play);
                if (b != null) b.requestFocus();
            }
        });
    }

    private void click(int id, Runnable r) {
        View v = view.findViewById(id);
        if (v != null) v.setOnClickListener(x -> r.run());
    }

    private void seekBy(long ms) {
        if (player == null) return;
        long d = player.getDuration();
        long to = Math.max(0, player.getCurrentPosition() + ms);
        player.seekTo(d > 0 ? Math.min(to, d - 1000) : to);
    }

    /** Play or pause: a sign in the centre, and the control bar comes up too (play/pause selected). */
    private void togglePlayCentered() {
        if (Debug.ON) Log.i(TAG, "toggle play/pause, playWhenReady=" + (player != null && player.getPlayWhenReady()));
        if (player == null) return;
        boolean pausing = player.getPlayWhenReady();
        if (pausing) player.pause(); else player.play();
        centerSign.animate().cancel();
        centerSign.setAlpha(1f);
        centerSign.setScaleX(1f);
        centerSign.setScaleY(1f);
        centerSign.setVisibility(View.VISIBLE);
        pauseSignShown = pausing;
        if (pausing) {
            centerSign.setImageResource(R.drawable.ic_p_pause); // stays while paused
        } else {
            centerSign.setImageResource(R.drawable.ic_p_play);  // flashes, then fades away
            centerSign.animate().alpha(0f).scaleX(1.3f).scaleY(1.3f).setStartDelay(350).setDuration(300)
                    .withEndAction(() -> centerSign.setVisibility(View.GONE)).start();
        }
        toggledAt = android.os.SystemClock.uptimeMillis();
        if (!view.isControllerFullyVisible()) view.showController();
    }

    private void hideCenterSign() {
        pauseSignShown = false;
        if (centerSign == null) return;
        centerSign.animate().cancel();
        centerSign.setVisibility(View.GONE);
    }

    private void updatePlayIcon() {
        View b = view.findViewById(R.id.clear_play);
        if (b instanceof android.widget.ImageButton && player != null) {
            ((android.widget.ImageButton) b).setImageResource(player.getPlayWhenReady() ? R.drawable.ic_p_pause : R.drawable.ic_p_play);
        }
    }

    // ---------------------------------------------------------------- controls look

    /**
     * Red focus highlight on every control (the default faint white ring is hard to see on a TV),
     * and a red timeline that steps 1 s per key if it ever handles keys itself.
     */
    private void styleControls() {
        View tb = view.findViewById(androidx.media3.ui.R.id.exo_progress);
        if (tb instanceof androidx.media3.ui.DefaultTimeBar) {
            androidx.media3.ui.DefaultTimeBar bar = (androidx.media3.ui.DefaultTimeBar) tb;
            bar.setPlayedColor(ACCENT);
            bar.setScrubberColor(ACCENT);
            bar.setBufferedColor(0x80FFFFFF);
            bar.setUnplayedColor(0x40FFFFFF);
            bar.setKeyTimeIncrement(1000);
            // A plain red line until it's selected; then the knob shows for scrubbing.
            bar.hideScrubber(false);
            bar.setOnFocusChangeListener((v, has) -> { if (has) bar.showScrubber(); else bar.hideScrubber(false); });
        }
        styleTree(view);
    }

    private void styleTree(View v) {
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) styleTree(g.getChildAt(i));
        }
        int id = v.getId();
        if (v.isFocusable() && (id == R.id.clear_back10 || id == R.id.clear_fwd10)) {
            // Icon plus "10": the whole frame is the button.
            v.setBackground(focusDisc());
            v.setOnFocusChangeListener((x, has) -> grow(x, has));
            return;
        }
        if (!v.isFocusable() || v instanceof android.view.ViewGroup) return;
        if (id == androidx.media3.ui.R.id.exo_rew_with_amount || id == androidx.media3.ui.R.id.exo_ffwd_with_amount) {
            // "−5" / "+15": the button's own background is its icon, so the red disc goes on the
            // frame around it (which takes the button's focused state).
            View frame = (View) v.getParent();
            frame.setBackground(focusDisc());
            v.setOnFocusChangeListener((x, has) -> grow(frame, has));
        } else if (v instanceof android.widget.ImageView) {
            final boolean playPause = id == androidx.media3.ui.R.id.exo_play_pause;
            // Play/pause is a white disc with the symbol cut out: a red disc behind it would fill the
            // cut-out, so that one only gets its disc tinted red.
            if (!playPause) v.setBackground(focusDisc());
            v.setOnFocusChangeListener((x, has) -> {
                grow(x, has);
                // Play/pause draws its own white disc over the background: tint that red instead.
                if (playPause) ((android.widget.ImageView) x).setImageTintList(has
                        ? android.content.res.ColorStateList.valueOf(ACCENT) : null);
            });
        }
    }

    /** Red disc when focused, nothing otherwise. */
    private static android.graphics.drawable.Drawable focusDisc() {
        android.graphics.drawable.GradientDrawable on = new android.graphics.drawable.GradientDrawable();
        on.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        on.setColor(ACCENT);
        android.graphics.drawable.StateListDrawable sel = new android.graphics.drawable.StateListDrawable();
        sel.addState(new int[]{android.R.attr.state_focused}, on);
        sel.addState(new int[]{}, new android.graphics.drawable.ColorDrawable(0));
        return sel;
    }

    /** The selected control grows a little, so it stands out even over a bright picture. */
    private static void grow(View v, boolean focused) {
        v.animate().scaleX(focused ? 1.25f : 1f).scaleY(focused ? 1.25f : 1f).setDuration(120).start();
    }

    // ---------------------------------------------------------------- scrubbing

    /** Seconds per key event while held: a tap is exactly 1 s, holding speeds up to 30 s steps. */
    private static long stepFor(int repeat) {
        if (repeat < 8) return 1_000;
        if (repeat < 20) return 2_000;
        if (repeat < 35) return 5_000;
        if (repeat < 55) return 10_000;
        return 30_000;
    }

    private void scrubBy(long delta) {
        if (player == null) return;
        long duration = player.getDuration();
        if (!scrubbing) {
            scrubbing = true;
            scrubWasPlaying = player.getPlayWhenReady();
            hideCenterSign();
            player.pause();
            view.hideController();
            if (view.getSubtitleView() != null) view.getSubtitleView().setVisibility(View.INVISIBLE); // not behind the previews
            scrubStart = scrubTarget = player.getCurrentPosition();
            if (thumbs != null) thumbs.preload(scrubStart, 8);
        }
        scrubTarget = Math.max(0, scrubTarget + delta);
        if (duration > 0) scrubTarget = Math.min(scrubTarget, duration - 1000);
        scrub.update(scrubTarget, scrubStart, player.getCurrentPosition(), duration, thumbs);
        if (Debug.ON) Log.i(TAG, "scrub to " + ScrubOverlay.fmt(scrubTarget) + " (" + (scrubTarget - scrubStart) / 1000 + " s) overlay=" + scrub.isShown() + " h=" + scrub.getHeight());
        view.removeCallbacks(commitScrub);
        view.postDelayed(commitScrub, 1000);
    }

    /** Jump to the target (after a second without keys, or on OK). */
    private void commitScrub() {
        if (!scrubbing) return;
        view.removeCallbacks(commitScrub);
        scrubbing = false;
        scrub.hide();
        if (view.getSubtitleView() != null) view.getSubtitleView().setVisibility(View.VISIBLE);
        if (player == null) return;
        player.seekTo(scrubTarget);
        if (scrubWasPlaying) player.play();
        backToTimeline();
    }

    /** Back while scrubbing: stay where we were. */
    private void cancelScrub() {
        view.removeCallbacks(commitScrub);
        scrubbing = false;
        scrub.hide();
        if (view.getSubtitleView() != null) view.getSubtitleView().setVisibility(View.VISIBLE);
        if (player != null && scrubWasPlaying) player.play();
        backToTimeline();
    }

    private void backToTimeline() {
        if (!scrubFromTimeline) return;
        scrubFromTimeline = false;
        view.showController();
        View tb = view.findViewById(androidx.media3.ui.R.id.exo_progress);
        if (tb != null) tb.requestFocus();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (Debug.ON) Log.i(TAG, "key " + KeyEvent.keyCodeToString(k) + (down ? " down" : " up") + " repeat=" + e.getRepeatCount()
                + " controls=" + view.isControllerFullyVisible() + " centerDown=" + centerDown + " panel=" + panel.isOpen());
        // Left/Right with the controls hidden (and FF/Rewind any time): scrub with previews.
        boolean panelOpen = panel.isOpen();
        if (panelOpen) {
            if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_MENU || k == KeyEvent.KEYCODE_CAPTIONS) {
                if (!down) panel.close();
                return true;
            }
            return super.dispatchKeyEvent(e); // D-pad moves through the panel
        }
        // Left/Right on the controls' timeline scrub the same way (1 s steps, previews), instead of
        // the timeline's own jumps of 5% of the video.
        View focused = getCurrentFocus();
        if (!panelOpen && focused != null && focused.getId() == androidx.media3.ui.R.id.exo_progress
                && view.isControllerFullyVisible() && (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            if (down) {
                scrubFromTimeline = true;
                scrubBy((k == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1) * stepFor(e.getRepeatCount()));
            }
            return true;
        }
        if (!panelOpen && (k == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || k == KeyEvent.KEYCODE_MEDIA_REWIND
                || (!view.isControllerFullyVisible() && (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT)))) {
            if (down) {
                int dir = (k == KeyEvent.KEYCODE_DPAD_RIGHT || k == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) ? 1 : -1;
                boolean jump = k == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || k == KeyEvent.KEYCODE_MEDIA_REWIND;
                scrubBy(dir * (jump ? 10_000 : stepFor(e.getRepeatCount())));
            }
            return true;
        }
        if (!scrubbing && (k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || k == KeyEvent.KEYCODE_MEDIA_PLAY
                || k == KeyEvent.KEYCODE_MEDIA_PAUSE)) {
            if (!down && player != null) {
                boolean playing = player.getPlayWhenReady();
                if (k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || (k == KeyEvent.KEYCODE_MEDIA_PLAY) != playing) togglePlayCentered();
            }
            return true;
        }
        if (scrubbing) {
            if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) {
                if (!down) commitScrub();
                centerDown = centerLong = false;
                return true;
            }
            if (k == KeyEvent.KEYCODE_BACK) {
                if (!down) cancelScrub();
                return true;
            }
        }
        if (k == KeyEvent.KEYCODE_MENU || k == KeyEvent.KEYCODE_CAPTIONS) {
            if (e.getAction() == KeyEvent.ACTION_UP) openPanel(PlayerPanel.SUBS);
            return true;
        }
        // Holding OK opens the subtitle panel too (not every remote has Menu).
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() == 0) { centerDown = true; centerLong = false; }
                else if (e.isLongPress() && centerDown && !centerLong) { centerLong = true; openPanel(PlayerPanel.SUBS); return true; }
                if (centerLong) return true;
            } else if (centerLong) {
                centerLong = centerDown = false;
                return true;
            }
        }
        // OK with the controls hidden: play/pause. Act on the release alone: on these TCL TVs the
        // press half of OK doesn't always reach the app (the system or an accessibility service
        // can swallow it), and waiting for both made OK do nothing.
        if ((k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) && !view.isControllerFullyVisible()) {
            if (!down && player != null && !centerLong) {
                centerDown = false;
                togglePlayCentered();
            }
            return true;
        }
        return view.dispatchKeyEvent(e) || super.dispatchKeyEvent(e);
    }

    @Override
    protected void onStop() {
        // Release the decoder and buffers the moment we're not on screen: on a 1 GB TV that's the RAM back.
        view.removeCallbacks(stats);
        view.removeCallbacks(commitScrub);
        if (thumbs != null) { thumbs.close(); thumbs = null; }
        ourSubs.removeCallbacks(extTick);
        if (ext != null) { ext.close(); ext = null; }
        activeExt = null;
        if (player != null) {
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_POS, player.getCurrentPosition() / 1000.0));
            player.release();
            player = null;
        }
        super.onStop();
    }

    @Override
    public void onBackPressed() {
        if (player != null) setResult(RESULT_OK, new Intent().putExtra(EXTRA_POS, player.getCurrentPosition() / 1000.0));
        super.onBackPressed();
    }
}
