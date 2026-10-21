package com.clearbrowser.tv;

import android.app.Activity;
import android.app.AlertDialog;
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
    private Prefs prefs;
    private AlertDialog subtitleMenu;
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
        view = new PlayerView(this);
        view.setKeepScreenOn(true);
        view.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        view.setShowSubtitleButton(true); // CC in the on-screen controls
        // Controls only when asked for (Up/Down, or OK to pause): auto-showing them on pause would
        // grab Left/Right away from scrubbing.
        view.setControllerAutoShow(false);
        styleControls();
        FrameLayout root = new FrameLayout(this);
        root.addView(view);
        scrub = new ScrubOverlay(this);
        root.addView(scrub, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        prefs = new Prefs(this);
        applySubtitleLook();
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
                .setMediaSourceFactory(new DefaultMediaSourceFactory(
                        new DefaultDataSource.Factory(this, new ParallelHttpDataSource.Factory(http))))
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
        // Subtitle files the page used, alongside any the video carries itself.
        ArrayList<Bundle> subs = in.getParcelableArrayListExtra(EXTRA_SUBS);
        if (subs != null && !subs.isEmpty()) {
            List<MediaItem.SubtitleConfiguration> configs = new ArrayList<>();
            for (Bundle b : subs) {
                String su = b.getString("url", "");
                String ext = su.replaceAll("[?#].*", "").toLowerCase();
                String mime = ext.endsWith(".srt") ? MimeTypes.APPLICATION_SUBRIP
                        : ext.endsWith(".ass") || ext.endsWith(".ssa") ? MimeTypes.TEXT_SSA : MimeTypes.TEXT_VTT;
                configs.add(new MediaItem.SubtitleConfiguration.Builder(Uri.parse(su))
                        .setMimeType(mime).setLanguage(b.getString("lang", "")).setLabel(b.getString("label")).build());
            }
            subConfigs.addAll(configs);
            item.setSubtitleConfigurations(configs);
        }
        // Last subtitle choice: off, or a preferred language.
        String lang = prefs.subtitleLanguage();
        TrackSelectionParameters.Builder tsp = player.getTrackSelectionParameters().buildUpon();
        if ("off".equals(lang)) tsp.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true);
        else if (!lang.isEmpty()) tsp.setPreferredTextLanguage(lang).setSelectUndeterminedTextLanguage(true);
        player.setTrackSelectionParameters(tsp.build());
        player.addListener(new Player.Listener() {
            @Override
            public void onTracksChanged(@NonNull Tracks tracks) {
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
        SubtitleView sv = view.getSubtitleView();
        if (sv == null) return;
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

    /** On/off, which track, size, style, position. Opened with Menu, a held OK, or the Captions key. */
    private void showSubtitleMenu() {
        if (player == null) return;
        final List<Tracks.Group> groups = new ArrayList<>();
        final List<Integer> indexes = new ArrayList<>();
        boolean anyOn = false;
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() != C.TRACK_TYPE_TEXT) continue;
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSupported(i)) continue;
                groups.add(g);
                indexes.add(i);
                if (g.isTrackSelected(i)) anyOn = true;
            }
        }
        boolean disabled = player.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_TEXT);
        final boolean on = anyOn && !disabled;
        List<String> items = new ArrayList<>();
        items.add(groups.isEmpty() ? "No subtitles in this video" : on ? "Subtitles: On  (turn off)" : "Subtitles: Off  (turn on)");
        for (int k = 0; k < groups.size(); k++) {
            Tracks.Group g = groups.get(k);
            int i = indexes.get(k);
            items.add((on && g.isTrackSelected(i) ? "●  " : "○  ") + trackName(g.getTrackFormat(i)));
        }
        final int first = 1, afterTracks = 1 + groups.size();
        items.add("Size: " + SIZES[prefs.subtitleSize()]);
        items.add("Style: " + STYLES[prefs.subtitleStyle()]);
        items.add("Position: " + POSITIONS[prefs.subtitlePosition()]);
        items.add(prefs.tunneling()
                ? "Smooth playback (hardware tunneling): On" + (player.isTunnelingEnabled() ? "" : " — not used for this video")
                : "Smooth playback (hardware tunneling): Off");
        subtitleMenu = new AlertDialog.Builder(this)
                .setTitle("Subtitles & playback")
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    TrackSelectionParameters.Builder b = player.getTrackSelectionParameters().buildUpon();
                    if (which == 0) {
                        if (groups.isEmpty()) return;
                        if (on) {
                            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true);
                            prefs.setSubtitleLanguage("off");
                        } else {
                            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false);
                            Tracks.Group g = groups.get(0);
                            b.setOverrideForType(new TrackSelectionOverride(g.getMediaTrackGroup(), indexes.get(0)));
                            remember(g.getTrackFormat(indexes.get(0)));
                        }
                        player.setTrackSelectionParameters(b.build());
                    } else if (which >= first && which < afterTracks) {
                        Tracks.Group g = groups.get(which - first);
                        int i = indexes.get(which - first);
                        TrackGroup tg = g.getMediaTrackGroup();
                        player.setTrackSelectionParameters(b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                .setOverrideForType(new TrackSelectionOverride(tg, i)).build());
                        remember(g.getTrackFormat(i));
                    } else {
                        int opt = which - afterTracks;
                        if (opt == 0) prefs.setSubtitleSize((prefs.subtitleSize() + 1) % SIZES.length);
                        else if (opt == 1) prefs.setSubtitleStyle((prefs.subtitleStyle() + 1) % STYLES.length);
                        else if (opt == 2) prefs.setSubtitlePosition((prefs.subtitlePosition() + 1) % POSITIONS.length);
                        else {
                            // Off switch in case a TV's tunneling misbehaves (audio drift, black frames).
                            prefs.setTunneling(!prefs.tunneling());
                            selector.setParameters(selector.buildUponParameters().setTunnelingEnabled(prefs.tunneling()));
                            view.postDelayed(this::showSubtitleMenu, 600);
                            return;
                        }
                        applySubtitleLook();
                        // Re-open so the next press keeps cycling, with the change visible behind it.
                        view.post(this::showSubtitleMenu);
                        return;
                    }
                    // Track changes land after the dialog closes; reopen to show the new state.
                    view.postDelayed(this::showSubtitleMenu, 250);
                })
                .create();
        subtitleMenu.show();
    }

    private void remember(Format f) {
        prefs.setSubtitleLanguage(f.language != null && !f.language.equals("und") ? f.language : "");
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
        }
        styleTree(view);
    }

    private void styleTree(View v) {
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) styleTree(g.getChildAt(i));
        }
        if (!v.isFocusable() || v instanceof android.view.ViewGroup) return;
        int id = v.getId();
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
        if (Debug.ON && down) Log.i(TAG, "key " + KeyEvent.keyCodeToString(k) + " repeat=" + e.getRepeatCount() + " controls=" + view.isControllerFullyVisible());
        // Left/Right with the controls hidden (and FF/Rewind any time): scrub with previews.
        boolean panelOpen = subtitleMenu != null && subtitleMenu.isShowing();
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
            if (e.getAction() == KeyEvent.ACTION_UP) showSubtitleMenu();
            return true;
        }
        // Holding OK opens the subtitle panel too (not every remote has Menu).
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() == 0) { centerDown = true; centerLong = false; }
                else if (e.isLongPress() && centerDown && !centerLong) { centerLong = true; showSubtitleMenu(); return true; }
                if (centerLong) return true;
            } else if (centerLong) {
                centerLong = centerDown = false;
                return true;
            }
        }
        // OK with the controls hidden: play/pause instead of just showing them.
        if (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER && !view.isControllerFullyVisible()) {
            if (e.getAction() == KeyEvent.ACTION_UP && player != null && centerDown) {
                centerDown = false;
                if (player.isPlaying()) { player.pause(); view.showController(); } else player.play();
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
