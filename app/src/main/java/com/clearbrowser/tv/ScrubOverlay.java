package com.clearbrowser.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * The TV player's scrub carousel: five previews around the target time (the centre one large and
 * outlined), the target time and how far it is from where you started, and a progress bar.
 */
final class ScrubOverlay extends FrameLayout {
    private static final int TILES = 5;
    private final ImageView[] tiles = new ImageView[TILES];
    private final TextView[] labels = new TextView[TILES];
    private final long[] shown = new long[TILES];
    private final TextView time, delta;
    private final Bar bar;
    private final float density;

    ScrubOverlay(Context c) {
        super(c);
        density = getResources().getDisplayMetrics().density;
        setVisibility(GONE);
        setClickable(false);

        LinearLayout panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        GradientDrawable shade = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0xCC000000, 0xF2000000});
        panel.setBackground(shade);
        panel.setPadding(dp(24), dp(40), dp(24), dp(18));

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int bigW = (int) (screenW * 0.24f), smallW = (int) (screenW * 0.14f);
        for (int i = 0; i < TILES; i++) {
            boolean centre = i == TILES / 2;
            int w = centre ? bigW : smallW, h = w * 9 / 16;
            LinearLayout cell = new LinearLayout(c);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            ImageView iv = new ImageView(c);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            GradientDrawable frame = new GradientDrawable();
            frame.setColor(0xFF1A1F2E);
            frame.setCornerRadius(dp(6));
            if (centre) frame.setStroke(dp(3), Color.WHITE);
            iv.setBackground(frame);
            iv.setPadding(centre ? dp(3) : 0, centre ? dp(3) : 0, centre ? dp(3) : 0, centre ? dp(3) : 0);
            iv.setClipToOutline(true);
            iv.setAlpha(centre ? 1f : 0.65f);
            cell.addView(iv, new LinearLayout.LayoutParams(w, h));
            TextView label = new TextView(c);
            label.setTextColor(centre ? Color.WHITE : 0xB3FFFFFF);
            label.setTextSize(centre ? 15 : 12);
            label.setGravity(Gravity.CENTER);
            label.setPadding(0, dp(4), 0, 0);
            cell.addView(label);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(8), 0, dp(8), 0);
            row.addView(cell, lp);
            tiles[i] = iv;
            labels[i] = label;
            shown[i] = -1;
        }
        panel.addView(row);

        LinearLayout info = new LinearLayout(c);
        info.setOrientation(LinearLayout.HORIZONTAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        info.setPadding(0, dp(14), 0, dp(8));
        time = new TextView(c);
        time.setTextColor(Color.WHITE);
        time.setTextSize(22);
        time.getPaint().setFakeBoldText(true);
        delta = new TextView(c);
        delta.setTextColor(0xFF8FB4FF);
        delta.setTextSize(16);
        delta.setPadding(dp(14), 0, 0, 0);
        info.addView(time);
        info.addView(delta);
        panel.addView(info);

        bar = new Bar(c);
        panel.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(10)));

        addView(panel, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
    }

    private int dp(float v) { return Math.round(v * density); }

    /** Redraws for a new target; previews arrive asynchronously from {@code thumbs}. */
    void update(long target, long start, long position, long duration, Thumbnails thumbs) {
        setVisibility(VISIBLE);
        time.setText(fmt(target) + "  /  " + fmt(duration));
        long d = target - start;
        delta.setText((d >= 0 ? "+" : "−") + fmt(Math.abs(d)));
        bar.set(position, target, duration);
        long step = thumbs == null ? 10_000 : thumbs.interval();
        for (int i = 0; i < TILES; i++) {
            final int idx = i;
            long t = target + (i - TILES / 2) * step;
            boolean inRange = t >= 0 && (duration <= 0 || t < duration);
            tiles[i].setVisibility(inRange ? VISIBLE : INVISIBLE);
            labels[i].setVisibility(inRange ? VISIBLE : INVISIBLE);
            if (!inRange) continue;
            labels[i].setText(fmt(i == TILES / 2 ? target : t));
            if (thumbs == null) continue;
            final long b = thumbs.bucket(t);
            if (shown[i] == b) continue;
            shown[i] = b;
            tiles[i].setImageDrawable(null);
            thumbs.get(t, i == TILES / 2, (bucket, bmp) -> {
                if (shown[idx] == bucket) tiles[idx].setImageBitmap(bmp);
            });
        }
    }

    void hide() {
        setVisibility(GONE);
        for (int i = 0; i < TILES; i++) { shown[i] = -1; tiles[i].setImageDrawable(null); }
    }

    static String fmt(long ms) {
        long s = Math.max(0, ms / 1000), h = s / 3600, m = s / 60 % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, s % 60) : String.format(Locale.US, "%d:%02d", m, s % 60);
    }

    /** Track, how far playback had got, and the target marker. */
    private static final class Bar extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), played = new Paint(Paint.ANTI_ALIAS_FLAG),
                knob = new Paint(Paint.ANTI_ALIAS_FLAG);
        private long position, target, duration;

        Bar(Context c) {
            super(c);
            track.setColor(0x40FFFFFF);
            played.setColor(0xFF6A96FF);
            knob.setColor(Color.WHITE);
        }

        void set(long position, long target, long duration) {
            this.position = position; this.target = target; this.duration = duration;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), mid = h / 2, th = h * 0.4f, r = h / 2;
            c.drawRoundRect(r, mid - th / 2, w - r, mid + th / 2, th / 2, th / 2, track);
            if (duration <= 0) return;
            float px = r + (w - 2 * r) * Math.min(1f, position / (float) duration);
            float tx = r + (w - 2 * r) * Math.min(1f, target / (float) duration);
            c.drawRoundRect(r, mid - th / 2, px, mid + th / 2, th / 2, th / 2, played);
            c.drawCircle(tx, mid, r, knob);
        }
    }
}
