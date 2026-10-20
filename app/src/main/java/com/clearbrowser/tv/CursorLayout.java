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
        float w = getWidth(), h = getHeight(), m = h * 0.12f;
        long now = SystemClock.uptimeMillis();
        if (!dragging) {
            dragging = true;
            dragDownTime = now;
            fingerX = dx == 0 ? Math.max(m, Math.min(w - m, x)) : (dx > 0 ? w - m : m);
            fingerY = dy == 0 ? Math.max(m, Math.min(h - m, y)) : (dy > 0 ? h - m : m);
            send(MotionEvent.ACTION_DOWN, now);
        }
        float nx = fingerX - dx, ny = fingerY - dy;
        if (nx < m || nx > w - m || ny < m || ny > h - m) {
            // Out of room: lift here, then put the finger back at the start of its run.
            send(MotionEvent.ACTION_UP, now);
            dragDownTime = now;
            fingerX = dx == 0 ? fingerX : (dx > 0 ? w - m : m);
            fingerY = dy == 0 ? fingerY : (dy > 0 ? h - m : m);
            send(MotionEvent.ACTION_DOWN, now);
            nx = fingerX - dx;
            ny = fingerY - dy;
        }
        fingerX = nx;
        fingerY = ny;
        send(MotionEvent.ACTION_MOVE, now);
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
        float h = getHeight(), m = h * 0.12f;
        float dist = fraction * h, step = dist / 6f;
        long t0 = SystemClock.uptimeMillis();
        dragDownTime = t0;
        fingerX = getWidth() / 2f;
        fingerY = fraction > 0 ? h - m : m;
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
