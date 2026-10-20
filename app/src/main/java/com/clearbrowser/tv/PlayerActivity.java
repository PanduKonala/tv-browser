package com.clearbrowser.tv;

import android.app.Activity;
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
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.ui.PlayerView;

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
    static final String EXTRA_URL = "url", EXTRA_HEADERS = "headers", EXTRA_POS = "pos", EXTRA_UA = "ua";
    private static final String TAG = "ClearPlayer";

    private ExoPlayer player;
    private PlayerView view;
    private int stalls, dropped;
    private boolean wasReady;
    private String url;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new PlayerView(this);
        view.setKeepScreenOn(true);
        view.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        setContentView(view);
    }

    @Override
    protected void onStart() {
        super.onStart();
        Intent in = getIntent();
        url = in.getStringExtra(EXTRA_URL);
        Bundle h = in.getBundleExtra(EXTRA_HEADERS);
        Map<String, String> headers = new HashMap<>();
        if (h != null) for (String k : h.keySet()) headers.put(k, h.getString(k));

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
                Log.w(TAG, "can't play " + url, e);
                Toast.makeText(PlayerActivity.this, "TV player can't play this one, using the web player",
                        Toast.LENGTH_LONG).show();
                setResult(RESULT_CANCELED, new Intent().putExtra(EXTRA_URL, url));
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

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        // OK with the controls hidden: play/pause instead of just showing them.
        if (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER && !view.isControllerFullyVisible()) {
            if (e.getAction() == KeyEvent.ACTION_UP && player != null) {
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
