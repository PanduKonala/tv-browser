package com.clearbrowser.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.WindowManager;
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
            EXTRA_SUBS = "subs";
    private static final String[] SIZES = {"Small", "Medium", "Large", "Extra large"};
    private static final float[] SIZE_SCALE = {0.8f, 1.0f, 1.3f, 1.65f};
    private static final String[] STYLES = {"White, outlined", "Yellow, outlined", "White on dark box", "Yellow on dark box"};
    private static final String[] POSITIONS = {"Bottom", "Raised"};
    private static final String TAG = "ClearPlayer";

    private ExoPlayer player;
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

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new PlayerView(this);
        view.setKeepScreenOn(true);
        view.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        view.setShowSubtitleButton(true); // CC in the on-screen controls
        setContentView(view);
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
        Log.i(TAG, "open " + url + " headers=" + headers.keySet() + " referer=" + headers.get("Referer"));

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(in.getStringExtra(EXTRA_UA))
                .setDefaultRequestProperties(headers)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(15000);
        // Up to 1080p / 60 fps, whatever the screen's UI resolution; within that, ExoPlayer adapts to the connection.
        DefaultTrackSelector selector = new DefaultTrackSelector(this);
        selector.setParameters(selector.buildUponParameters()
                .clearViewportSizeConstraints()
                .setMaxVideoSize(1920, 1080)
                .setMaxVideoFrameRate(60));
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
        view.postDelayed(stats, 10_000);
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
        subtitleMenu = new AlertDialog.Builder(this)
                .setTitle("Subtitles")
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
                        else prefs.setSubtitlePosition((prefs.subtitlePosition() + 1) % POSITIONS.length);
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

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
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
