package com.clearbrowser.tv;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The TV player's settings panel, remote-first: categories on the left (Subtitles, Audio, Quality,
 * Style, Playback), their options on the right. Red highlight on the focused row, a red check on
 * the current choice; Left/Right adjust rows that have a value (delay, size).
 */
final class PlayerPanel extends FrameLayout {
    static final String SUBS = "Subtitles", AUDIO = "Audio", QUALITY = "Quality", STYLE = "Style", PLAYBACK = "Playback";
    private static final String[] CATEGORIES = {SUBS, AUDIO, QUALITY, STYLE, PLAYBACK};
    private static final int ACCENT = 0xFFE50914, ACCENT_TEXT = 0xFFFF5A64;

    /** One line in the options list. */
    static final class Row {
        String flag, text, detail;
        boolean checked, chevronOpen, chevronClosed, dim;
        int indent;
        Runnable onClick;
        /** Left (-1) / Right (+1) on this row; null if it has no value to adjust. */
        java.util.function.IntConsumer onAdjust;

        static Row of(String text) { Row r = new Row(); r.text = text; return r; }
        Row flag(String f) { flag = f; return this; }
        Row detail(String d) { detail = d; return this; }
        Row checked(boolean c) { checked = c; return this; }
        Row indent(int i) { indent = i; return this; }
        Row click(Runnable r) { onClick = r; return this; }
        Row adjust(java.util.function.IntConsumer a) { onAdjust = a; return this; }
        Row dim() { dim = true; return this; }
    }

    interface Host { List<Row> rows(String category); }

    private final Host host;
    private final LinearLayout categories, list;
    private final ScrollView scroll;
    private final TextView title;
    private final float density;
    private String current = SUBS;

    PlayerPanel(Context c, Host host) {
        super(c);
        this.host = host;
        density = getResources().getDisplayMetrics().density;
        setVisibility(GONE);
        setBackground(new ColorDrawable(0x66000000)); // dims the picture behind

        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF2141414);
        bg.setCornerRadius(dp(16));
        card.setBackground(bg);
        card.setPadding(dp(18), dp(14), dp(18), dp(14));

        title = new TextView(c);
        title.setText("Settings");
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setPadding(dp(6), 0, 0, dp(10));
        card.addView(title);

        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.HORIZONTAL);
        categories = new LinearLayout(c);
        categories.setOrientation(LinearLayout.VERTICAL);
        for (String cat : CATEGORIES) categories.addView(categoryView(c, cat));
        body.addView(categories, new LinearLayout.LayoutParams(dp(170), LinearLayout.LayoutParams.WRAP_CONTENT));
        View divider = new View(c);
        divider.setBackgroundColor(0x22FFFFFF);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(1), LinearLayout.LayoutParams.MATCH_PARENT);
        dl.setMargins(dp(12), 0, dp(12), 0);
        body.addView(divider, dl);
        scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        body.addView(scroll, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
        card.addView(body, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        int w = (int) (getResources().getDisplayMetrics().widthPixels * 0.62f);
        LayoutParams lp = new LayoutParams(w, LayoutParams.MATCH_PARENT, Gravity.END);
        lp.setMargins(0, dp(24), dp(24), dp(24));
        addView(card, lp);
    }

    private int dp(float v) { return Math.round(v * density); }

    private View categoryView(Context c, String cat) {
        TextView t = new TextView(c);
        t.setText(cat);
        t.setTextSize(16);
        t.setTextColor(textColors());
        t.setPadding(dp(14), dp(12), dp(14), dp(12));
        t.setFocusable(true);
        t.setBackground(rowBackground(true));
        t.setTag(cat);
        // Moving onto a category shows its options, like the site players do.
        t.setOnFocusChangeListener((v, has) -> { if (has && !cat.equals(current)) show(cat, false); });
        t.setOnClickListener(v -> focusFirstRow());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(2), 0, dp(2));
        t.setLayoutParams(lp);
        return t;
    }

    boolean isOpen() { return getVisibility() == VISIBLE; }

    /** Opens on {@code category} with the focus on its first option. */
    void open(String category) {
        setVisibility(VISIBLE);
        show(category, true);
    }

    void close() { setVisibility(GONE); }

    /** Re-renders the current category, keeping the focus on the same line. */
    void refresh() {
        if (!isOpen()) return;
        int at = list.indexOfChild(list.findFocus() == null ? null : rowOf(list.findFocus()));
        show(current, false);
        if (at >= 0 && at < list.getChildCount()) list.getChildAt(at).requestFocus();
    }

    private View rowOf(View v) {
        while (v != null && v.getParent() != list) v = (View) v.getParent();
        return v;
    }

    private void show(String category, boolean focusRows) {
        current = category;
        for (int i = 0; i < categories.getChildCount(); i++) {
            View v = categories.getChildAt(i);
            boolean on = category.equals(v.getTag());
            v.setSelected(on);
            ((TextView) v).setTextColor(on ? 0xFFFFFFFF : 0xB3FFFFFF);
            if (on) ((TextView) v).setTextColor(textColors());
        }
        list.removeAllViews();
        for (Row r : host.rows(category)) list.addView(rowView(r));
        scroll.scrollTo(0, 0);
        if (focusRows) focusFirstRow();
    }

    /**
     * Opens on the current choice (the ✓ row), else the first option without a value to adjust:
     * on a ◀ ▶ row, Left would change the value instead of reaching the categories.
     */
    private void focusFirstRow() {
        View fallback = null;
        for (int i = 0; i < list.getChildCount(); i++) {
            View v = list.getChildAt(i);
            if (!v.isFocusable()) continue;
            if (Boolean.TRUE.equals(v.getTag(R.id.panel_checked))) { v.requestFocus(); return; }
            if (fallback == null && !Boolean.TRUE.equals(v.getTag(R.id.panel_adjust))) fallback = v;
        }
        if (fallback == null) for (int i = 0; i < list.getChildCount(); i++) {
            if (list.getChildAt(i).isFocusable()) { fallback = list.getChildAt(i); break; }
        }
        if (fallback != null) fallback.requestFocus();
    }

    private View rowView(Row r) {
        Context c = getContext();
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14 + 26 * r.indent), dp(11), dp(14), dp(11));
        boolean interactive = r.onClick != null || r.onAdjust != null;
        row.setFocusable(interactive);
        row.setTag(R.id.panel_checked, r.checked);
        row.setTag(R.id.panel_adjust, r.onAdjust != null);
        row.setBackground(rowBackground(interactive));

        if (r.indent > 0) {
            View bar = new View(c);
            bar.setBackgroundColor(0x66FFFFFF);
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(dp(14), dp(2));
            bl.setMargins(0, 0, dp(10), 0);
            row.addView(bar, bl);
        }
        TextView text = new TextView(c);
        text.setText(r.flag == null ? r.text : r.flag + "   " + r.text);
        text.setTextSize(r.indent > 0 ? 15 : 16);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setTextColor(r.dim ? 0x80FFFFFF : r.checked ? 0xFFFFFFFF : 0xE6FFFFFF);
        if (interactive) text.setDuplicateParentStateEnabled(true);
        if (interactive && !r.dim) text.setTextColor(textColors());
        row.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        String right = r.detail == null ? "" : r.detail;
        if (r.onAdjust != null) right = "◀  " + right + "  ▶";
        if (r.chevronOpen) right += "  ▴";
        if (r.chevronClosed) right += "  ▾";
        if (!right.isEmpty()) {
            TextView d = new TextView(c);
            d.setText(right);
            d.setTextSize(14);
            d.setTextColor(r.onAdjust != null ? ACCENT_TEXT : 0xB3FFFFFF);
            d.setPadding(dp(10), 0, 0, 0);
            row.addView(d);
        }
        if (r.checked) {
            TextView tick = new TextView(c);
            tick.setText("✓");
            tick.setTextSize(18);
            tick.setTextColor(ACCENT);
            tick.setPadding(dp(12), 0, 0, 0);
            row.addView(tick);
        }
        if (r.onClick != null) row.setOnClickListener(v -> { r.onClick.run(); refresh(); });
        if (r.onAdjust != null) {
            row.setOnKeyListener((v, keyCode, e) -> {
                if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                if (e.getAction() == KeyEvent.ACTION_DOWN) {
                    r.onAdjust.accept(keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                    refresh();
                }
                return true;
            });
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(2), 0, dp(2));
        row.setLayoutParams(lp);
        return row;
    }

    /** Dark red with a red edge when focused (selected categories keep a faint tint). */
    private StateListDrawable rowBackground(boolean interactive) {
        StateListDrawable s = new StateListDrawable();
        if (!interactive) return s;
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(0xFF3A0D12);
        focused.setStroke(dp(2), ACCENT);
        focused.setCornerRadius(dp(10));
        GradientDrawable selected = new GradientDrawable();
        selected.setColor(0xFF2A1416);
        selected.setCornerRadius(dp(10));
        s.addState(new int[]{android.R.attr.state_focused}, focused);
        s.addState(new int[]{android.R.attr.state_selected}, selected);
        s.addState(new int[]{}, new ColorDrawable(0));
        return s;
    }

    private static android.content.res.ColorStateList textColors() {
        return new android.content.res.ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{ACCENT_TEXT, 0xE6FFFFFF});
    }

    static List<Row> list() { return new ArrayList<>(); }
}
