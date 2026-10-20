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
        /** Pointer is pinned at an edge and still pushing: scroll by (dx, dy) pixels. */
        void onEdgeScroll(int dx, int dy);
    }

    private static final float BASE_SPEED = 6f;   // px per frame when a key is first pressed
    private static final float MAX_SPEED = 38f;
    private static final float ACCEL = 0.9f;       // px per frame, per frame held
    private static final long HIDE_AFTER_MS = 4000;

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

    void stopMoving() { up = down = left = right = false; }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            int dirX = (right ? 1 : 0) - (left ? 1 : 0);
            int dirY = (down ? 1 : 0) - (up ? 1 : 0);
            if (dirX == 0 && dirY == 0) { running = false; return; }
            float speed = Math.min(MAX_SPEED, BASE_SPEED + ACCEL * heldFrames++);
            float nx = x + dirX * speed, ny = y + dirY * speed;
            float maxX = getWidth() - 1, maxY = getHeight() - 1;
            int sx = 0, sy = 0;
            if (nx < 0) { sx = (int) nx; nx = 0; } else if (nx > maxX) { sx = (int) (nx - maxX); nx = maxX; }
            if (ny < 0) { sy = (int) ny; ny = 0; } else if (ny > maxY) { sy = (int) (ny - maxY); ny = maxY; }
            x = nx; y = ny;
            // Pushing into an edge scrolls at the pointer's speed; give it a floor so it never crawls.
            if ((sx != 0 || sy != 0) && listener != null) {
                int min = (int) (MAX_SPEED * 0.6f);
                listener.onEdgeScroll(sx == 0 ? 0 : Integer.signum(sx) * Math.max(min, Math.abs(sx) * 2),
                        sy == 0 ? 0 : Integer.signum(sy) * Math.max(min, Math.abs(sy) * 2));
            }
            if (++hoverSkip % 3 == 0) hover();
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
