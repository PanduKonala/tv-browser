package com.clearbrowser.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.widget.FrameLayout;

/**
 * A mouse pointer driven by the remote's D-pad, drawn over the page. Holding a direction
 * accelerates; pushing past an edge scrolls instead. OK taps whatever is under the pointer, so
 * pages see a real touch (videos start, menus open) rather than a key press they ignore.
 */
public class CursorLayout extends FrameLayout {
    interface Listener {
        /** Whether pushing into an edge may scroll what's under the pointer (not in fullscreen video). */
        boolean canScroll();
        /**
         * The scrollable box (an episode list, a side panel) in the pointer's column at {@code x}
         * that can still scroll that way ({@code dirY} 1 down, -1 up), in this view's pixels; null
         * for the page itself.
         */
        android.graphics.RectF scrollArea(float x, int dirY);
    }

    private static final float BASE_SPEED = 6f;   // px per frame when a key is first pressed
    private static final float MAX_SPEED = 38f;
    private static final float ACCEL = 0.9f;       // px per frame, per frame held
    private static final long HIDE_AFTER_MS = 4000;
    // Edge scrolling: a virtual finger drags the page, so Chromium scrolls it on its compositor
    // thread (smooth even while the page's own scripts are busy). px per frame, accelerating.
    private static final float SCROLL_BASE = 10f, SCROLL_ACCEL = 1.1f, SCROLL_MAX = 46f;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float radius;
    private float x = -1, y = -1;
    private boolean up, down, left, right;
    private int heldFrames;
    private long lastMove = SystemClock.uptimeMillis();
    private boolean autoHide;
    private boolean running;
    private int hoverSkip;
    // Virtual finger for edge scrolling.
    private boolean dragging;
    private long dragDownTime;
    private float fingerX, fingerY;
    /** Where the virtual finger may move: the scrollable box being dragged, or the whole screen. */
    private float dragL, dragT, dragR, dragB;
    /** Where each vertical stroke starts (the finger is put back here when it runs out of room). */
    private float strokeStartY;
    private int scrollFrames;
    private Listener listener;

    public CursorLayout(Context c) { this(c, null); }

    public CursorLayout(Context c, AttributeSet a) {
        super(c, a);
        setWillNotDraw(false);
        float d = getResources().getDisplayMetrics().density;
        radius = 9 * d;
        fill.setColor(0xF2FFFFFF);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2.5f * d);
        ring.setColor(0xCC0B1020);
    }

    void setListener(Listener l) { listener = l; }

    /** In fullscreen video the pointer fades out when idle so it doesn't sit on the picture. */
    void setAutoHide(boolean on) {
        autoHide = on;
        lastMove = SystemClock.uptimeMillis();
        invalidate();
        if (on) postDelayed(this::invalidate, HIDE_AFTER_MS + 50);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (x < 0) { x = w / 2f; y = h / 2f; }
        x = Math.min(x, w - 1); y = Math.min(y, h - 1);
    }

    /** Handles D-pad arrows. Returns true if consumed. */
    boolean onDpad(KeyEvent e) {
        boolean pressed = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP: up = pressed; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: down = pressed; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: left = pressed; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: right = pressed; break;
            default: return false;
        }
        wake();
        if (!running && (up || down || left || right)) {
            running = true;
            heldFrames = 0;
            postOnAnimation(tick);
        }
        return true;
    }

    /** Test hook (Debug only): hold a D-pad direction for {@code ms}, exactly as a remote would. */
    void testHold(int keyCode, long ms) {
        long t = SystemClock.uptimeMillis();
        onDpad(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0));
        postDelayed(() -> onDpad(new KeyEvent(t, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0)), ms);
    }

    void stopMoving() { up = down = left = right = false; endDrag(); }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            int dirX = (right ? 1 : 0) - (left ? 1 : 0);
            int dirY = (down ? 1 : 0) - (up ? 1 : 0);
            if (dirX == 0 && dirY == 0) { running = false; endDrag(); return; }
            float speed = Math.min(MAX_SPEED, BASE_SPEED + ACCEL * heldFrames++);
            float nx = x + dirX * speed, ny = y + dirY * speed;
            float maxX = getWidth() - 1, maxY = getHeight() - 1;
            int sx = 0, sy = 0;
            if (nx < 0) { sx = (int) nx; nx = 0; } else if (nx > maxX) { sx = (int) (nx - maxX); nx = maxX; }
            if (ny < 0) { sy = (int) ny; ny = 0; } else if (ny > maxY) { sy = (int) (ny - maxY); ny = maxY; }
            x = nx; y = ny;
            // Pushing into an edge drags the page instead; letting go ends the drag.
            if ((sx != 0 || sy != 0) && listener != null && listener.canScroll()) {
                float v = Math.min(SCROLL_MAX, SCROLL_BASE + SCROLL_ACCEL * scrollFrames++);
                dragBy(Integer.signum(sx) * v, Integer.signum(sy) * v);
            } else {
                endDrag();
                // Hover only while the pointer itself moves: hover effects mid-scroll cost frames.
                if (++hoverSkip % 3 == 0) hover();
            }
            wake();
            postOnAnimation(this);
        }
    };

    private void wake() {
        lastMove = SystemClock.uptimeMillis();
        invalidate();
        if (autoHide) postDelayed(this::invalidate, HIDE_AFTER_MS + 50);
    }

    /** Shows the pointer if it had faded out; returns true if it was hidden. */
    boolean wakeIfHidden() {
        boolean hidden = autoHide && SystemClock.uptimeMillis() - lastMove > HIDE_AFTER_MS;
        wake();
        return hidden;
    }

    float cursorX() { return x; }
    float cursorY() { return y; }

    /**
     * Scrolls what's under the pointer by (dx, dy) px with a virtual finger: positive dy scrolls the
     * content down (the finger moves up). The finger starts on the far side of the pointer's line
     * and is lifted and put back down when it runs out of room; putting it down stops any fling,
     * so the scroll stays continuous and under the remote's control.
     */
    private void dragBy(float dx, float dy) {
        long now = SystemClock.uptimeMillis();
        if (!dragging) {
            dragging = true;
            dragDownTime = now;
            dragBounds(dy);
            float mx = (dragR - dragL) * 0.12f, my = (dragB - dragT) * 0.12f;
            fingerX = dx == 0 ? Math.max(dragL + mx, Math.min(dragR - mx, x)) : (dx > 0 ? dragR - mx : dragL + mx);
            fingerY = dy == 0 ? Math.max(dragT + my, Math.min(dragB - my, y)) : strokeStartY;
            send(MotionEvent.ACTION_DOWN, now);
        }
        float mx = (dragR - dragL) * 0.12f, my = (dragB - dragT) * 0.12f;
        float nx = fingerX - dx, ny = fingerY - dy;
        if (nx < dragL + mx || nx > dragR - mx || ny < dragT + my || ny > dragB - my) {
            // Out of room: lift here, then put the finger back at the start of its run.
            send(MotionEvent.ACTION_UP, now);
            dragDownTime = now;
            fingerX = dx == 0 ? fingerX : (dx > 0 ? dragR - mx : dragL + mx);
            fingerY = dy == 0 ? fingerY : strokeStartY;
            send(MotionEvent.ACTION_DOWN, now);
            nx = fingerX - dx;
            ny = fingerY - dy;
        }
        fingerX = nx;
        fingerY = ny;
        send(MotionEvent.ACTION_MOVE, now);
    }

    /**
     * Vertical drags go inside the scrollable box in the pointer's column when it can still scroll
     * that way: pushing into the top edge from an episode list on the right used to put the finger
     * on the site's header (above the list), so the list scrolled down but never back up.
     */
    private void dragBounds(float dy) {
        android.graphics.RectF a = dy == 0 || listener == null ? null : listener.scrollArea(x, dy > 0 ? 1 : -1);
        float minSide = 60 * getResources().getDisplayMetrics().density;
        if (Debug.ON) android.util.Log.d("ClearScroll", "drag dy=" + dy + " at x=" + x + " area=" + a);
        if (a != null && a.height() >= minSide && a.width() >= minSide) {
            dragL = a.left; dragT = a.top; dragR = a.right; dragB = a.bottom;
        } else {
            dragL = 0; dragT = 0; dragR = getWidth(); dragB = getHeight();
        }
        // Strokes start near the middle (of the box, or of the screen in the pointer's column), where
        // the content is: the top of a box or of the screen is usually a header laid over it, and a
        // finger put down there drags the header (lists then scrolled down but never back up).
        strokeStartY = dragT + (dragB - dragT) * (dy > 0 ? 0.62f : 0.38f);
    }

    /** Lift the virtual finger without a fling: hold still for a moment first, so the velocity is zero. */
    private void endDrag() {
        scrollFrames = 0;
        if (!dragging) return;
        dragging = false;
        long t = SystemClock.uptimeMillis();
        send(MotionEvent.ACTION_MOVE, t);
        postDelayed(() -> send(MotionEvent.ACTION_MOVE, SystemClock.uptimeMillis()), 30);
        postDelayed(() -> send(MotionEvent.ACTION_UP, SystemClock.uptimeMillis()), 70);
    }

    /** A quick swipe (Channel up/down): content moves by about {@code fraction} of the screen, with a fling. */
    void swipe(float fraction) {
        if (dragging) return;
        dragBounds(fraction);
        float h = dragB - dragT, m = h * 0.12f;
        float dist = fraction * getHeight(), step = dist / 6f;
        long t0 = SystemClock.uptimeMillis();
        dragDownTime = t0;
        fingerX = dragR - dragL < getWidth() ? (dragL + dragR) / 2f : getWidth() / 2f;
        fingerY = strokeStartY;
        send(MotionEvent.ACTION_DOWN, t0);
        for (int i = 1; i <= 6; i++) {
            final int k = i;
            postDelayed(() -> {
                fingerY -= step;
                send(k == 6 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE, SystemClock.uptimeMillis());
            }, i * 16L);
        }
    }

    private void send(int action, long time) {
        MotionEvent.PointerProperties pp = new MotionEvent.PointerProperties();
        pp.id = 0;
        pp.toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords pc = new MotionEvent.PointerCoords();
        pc.x = fingerX; pc.y = fingerY; pc.pressure = 1; pc.size = 1;
        MotionEvent ev = MotionEvent.obtain(dragDownTime, time, action, 1, new MotionEvent.PointerProperties[]{pp},
                new MotionEvent.PointerCoords[]{pc}, 0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        dispatchTouchEvent(ev);
        ev.recycle();
    }

    /** A finger tap at the pointer, delivered to whichever child is on top there. */
    void tap() {
        wake();
        long t = SystemClock.uptimeMillis();
        dispatchTouchEvent(touch(t, t, MotionEvent.ACTION_DOWN));
        postDelayed(() -> dispatchTouchEvent(touch(t, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP)), 40);
    }

    private MotionEvent touch(long downTime, long time, int action) {
        MotionEvent.PointerProperties pp = new MotionEvent.PointerProperties();
        pp.id = 0;
        pp.toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords pc = new MotionEvent.PointerCoords();
        pc.x = x; pc.y = y; pc.pressure = 1; pc.size = 1;
        return MotionEvent.obtain(downTime, time, action, 1, new MotionEvent.PointerProperties[]{pp},
                new MotionEvent.PointerCoords[]{pc}, 0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
    }

    /** Mouse hover, so desktop-style menus open and links highlight under the pointer. */
    private void hover() {
        MotionEvent.PointerProperties pp = new MotionEvent.PointerProperties();
        pp.id = 0;
        pp.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords pc = new MotionEvent.PointerCoords();
        pc.x = x; pc.y = y;
        long t = SystemClock.uptimeMillis();
        MotionEvent ev = MotionEvent.obtain(t, t, MotionEvent.ACTION_HOVER_MOVE, 1,
                new MotionEvent.PointerProperties[]{pp}, new MotionEvent.PointerCoords[]{pc},
                0, 0, 1, 1, 0, 0, InputDevice.SOURCE_MOUSE, 0);
        dispatchGenericMotionEvent(ev);
        ev.recycle();
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (autoHide && SystemClock.uptimeMillis() - lastMove > HIDE_AFTER_MS) return;
        canvas.drawCircle(x, y, radius, fill);
        canvas.drawCircle(x, y, radius, ring);
    }
}
