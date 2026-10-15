package app.desktop;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseIntArray;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

/**
 * The remote desktop view, with touchpad-style mouse control and an on-screen controller.
 *
 * Mouse (touchpad mode, like a laptop trackpad):
 *  - Dragging one finger moves the cursor by the same amount, scaled by sensitivity.
 *    Touching the screen does NOT jump the cursor to the finger.
 *  - Quick tap: left click at the cursor.
 *  - Tap, then tap-and-drag: drag with the left button held.
 *  - Long press (no movement): right click.
 *  - Two-finger tap: right click. Two-finger drag: scroll.
 *
 * Controller (bar at the bottom, hide/show with the arrow in the top-right corner):
 *  L, M, R hold the mouse buttons while pressed. Esc, Tab, Enter send keys.
 *  Ctrl, Alt, Shift, Super are sticky modifiers. Kbd opens the keyboard.
 *  Speed cycles the pointer sensitivity.
 */
public class DesktopView extends View {

    // RFB button mask bits.
    private static final int BTN_LEFT = 1;
    private static final int BTN_MIDDLE = 2;
    private static final int BTN_RIGHT = 4;
    private static final int BTN_WHEEL_UP = 8;
    private static final int BTN_WHEEL_DOWN = 16;

    private static final long LONG_PRESS_MS = 500;
    private static final long TAP_MAX_MS = 300;
    private static final long DOUBLE_TAP_MS = 350;
    private static final float SCROLL_STEP_DP = 28f;
    private static final float[] SPEEDS = {0.8f, 1.2f, 1.8f, 2.6f};

    // Controller button ids.
    private static final int B_LEFT = 0, B_MIDDLE = 1, B_RIGHT = 2, B_ESC = 3, B_TAB = 4, B_ENTER = 5,
            B_CTRL = 6, B_ALT = 7, B_SHIFT = 8, B_SUPER = 9, B_KBD = 10, B_SPEED = 11;
    private static final int B_TOGGLE = 100; // show/hide arrow, always visible
    private static final String[] LABELS = {"L", "M", "R", "Esc", "Tab", "Enter",
            "Ctrl", "Alt", "Shift", "Super", "Kbd", "Speed"};
    private static final int COLS = 6;
    private static final int ROWS = 2;

    private VncClient client;
    private Bitmap bitmap;
    private int fbW;
    private int fbH;
    private boolean dirty = true;
    private final Matrix matrix = new Matrix();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final float density;
    private final float touchSlop;

    // Cursor in framebuffer coordinates. Only changes when the pad moves.
    private float cx;
    private float cy;
    private float speed = SPEEDS[1];

    private boolean barVisible = true;
    private boolean[] modDown = new boolean[4]; // ctrl, alt, shift, super
    private final boolean[] pressedButton = new boolean[16];
    private final SparseIntArray buttonForPointer = new SparseIntArray();
    private final RectF[] buttonRects = new RectF[COLS * ROWS];
    private final RectF toggleRect = new RectF();

    // Pad state. Only one finger drives the cursor. The second finger scrolls or right-clicks.
    private int padId = -1;
    private float padX;
    private float padY;
    private float padDownX;
    private float padDownY;
    private long padDownT;
    private boolean padMoved;
    private boolean padDrag;
    private boolean padLongFired;
    private int secondId = -1;
    private float secondX;
    private float secondY;
    private float scrollAnchorY;
    private long secondDownT;
    private boolean secondMoved;
    private long lastTapT;
    private float lastTapCx;
    private float lastTapCy;

    private final Runnable longPress = () -> {
        if (padId != -1 && !padMoved && !padDrag && !padLongFired && secondId == -1) {
            padLongFired = true;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            send(BTN_RIGHT);
            send(0);
        }
    };

    public DesktopView(Context context) {
        super(context);
        setBackgroundColor(Color.BLACK);
        setFocusable(true);
        setFocusableInTouchMode(true);
        density = context.getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        fill.setStyle(Paint.Style.FILL);
        text.setColor(Color.WHITE);
        text.setTextSize(13 * density);
        text.setTextAlign(Paint.Align.CENTER);
        cursorPaint.setStyle(Paint.Style.FILL);
    }

    /** Attaches a connected client. Call on the UI thread. */
    public void attach(VncClient c) {
        client = c;
        invalidate();
    }

    /** Called from the reader thread when the framebuffer size changes. */
    public void onFramebufferResized(int w, int h) {
        post(() -> {
            fbW = w;
            fbH = h;
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            cx = w / 2f;
            cy = h / 2f;
            dirty = true;
            invalidate();
        });
    }

    /** Called from the reader thread when pixels change. */
    public void onFramebufferChanged() {
        dirty = true;
        postInvalidate();
    }

    // Layout.

    private float barHeight() {
        return barVisible ? ROWS * 46 * density + 8 * density : 0;
    }

    /** Height of the remote picture area (everything above the controller bar). */
    private float pictureHeight() {
        return getHeight() - barHeight();
    }

    private float scale() {
        if (fbW == 0 || fbH == 0) return 1f;
        return Math.min((float) getWidth() / fbW, pictureHeight() / fbH);
    }

    private float offsetX() { return (getWidth() - fbW * scale()) / 2f; }
    private float offsetY() { return (pictureHeight() - fbH * scale()) / 2f; }

    private void layoutBar() {
        float top = getHeight() - barHeight() + 4 * density;
        float gap = 4 * density;
        float w = (getWidth() - gap * (COLS + 1)) / COLS;
        float h = 42 * density;
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                float l = gap + c * (w + gap);
                float t = top + r * (h + gap);
                buttonRects[r * COLS + c] = new RectF(l, t, l + w, t + h);
            }
        }
        float ts = 36 * density;
        toggleRect.set(getWidth() - ts - 8 * density, 8 * density, getWidth() - 8 * density, 8 * density + ts);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        layoutBar();
    }

    // Drawing.

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap != null && client != null && dirty) {
            synchronized (client) {
                int[] px = client.pixels();
                if (px.length == fbW * fbH) {
                    bitmap.setPixels(px, 0, fbW, 0, 0, fbW, fbH);
                    dirty = false;
                }
            }
        }
        if (bitmap != null && fbW > 0) {
            float s = scale();
            matrix.reset();
            matrix.setScale(s, s);
            matrix.postTranslate(offsetX(), offsetY());
            canvas.drawBitmap(bitmap, matrix, paint);
            drawCursor(canvas, s);
        }
        if (barVisible) {
            drawBar(canvas);
        }
        drawToggle(canvas);
    }

    private void drawCursor(Canvas canvas, float s) {
        float x = offsetX() + cx * s;
        float y = offsetY() + cy * s;
        float u = 1.6f * density;
        Path p = new Path();
        p.moveTo(x, y);
        p.lineTo(x, y + 16 * u);
        p.lineTo(x + 4 * u, y + 12 * u);
        p.lineTo(x + 7 * u, y + 18 * u);
        p.lineTo(x + 9 * u, y + 17 * u);
        p.lineTo(x + 6 * u, y + 11 * u);
        p.lineTo(x + 11 * u, y + 11 * u);
        p.close();
        cursorPaint.setColor(Color.BLACK);
        canvas.drawPath(p, cursorPaint);
        // White outline so the cursor shows on dark and light backgrounds.
        Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(1.2f * density);
        outline.setColor(Color.WHITE);
        canvas.drawPath(p, outline);
    }

    private void drawBar(Canvas canvas) {
        fill.setColor(0xB0101418);
        canvas.drawRect(0, getHeight() - barHeight(), getWidth(), getHeight(), fill);
        for (int i = 0; i < buttonRects.length; i++) {
            RectF r = buttonRects[i];
            if (r == null) continue;
            boolean on = isButtonOn(i);
            boolean pressed = pressedButton[i];
            fill.setColor(pressed ? 0xFF3A7BD5 : (on ? 0xFF2E5C8A : 0xFF2A2F36));
            canvas.drawRoundRect(r, 8 * density, 8 * density, fill);
            String label = i == B_SPEED ? "x" + speedLabel() : LABELS[i];
            float base = r.centerY() - (text.descent() + text.ascent()) / 2f;
            canvas.drawText(label, r.centerX(), base, text);
        }
    }

    private void drawToggle(Canvas canvas) {
        fill.setColor(0x80000000);
        canvas.drawRoundRect(toggleRect, 8 * density, 8 * density, fill);
        float cx0 = toggleRect.centerX();
        float cy0 = toggleRect.centerY();
        float a = 7 * density;
        Path p = new Path();
        if (barVisible) {
            p.moveTo(cx0 - a, cy0 - a / 2);
            p.lineTo(cx0 + a, cy0 - a / 2);
            p.lineTo(cx0, cy0 + a / 2);
        } else {
            p.moveTo(cx0 - a, cy0 + a / 2);
            p.lineTo(cx0 + a, cy0 + a / 2);
            p.lineTo(cx0, cy0 - a / 2);
        }
        p.close();
        cursorPaint.setColor(Color.WHITE);
        canvas.drawPath(p, cursorPaint);
    }

    private String speedLabel() {
        for (int i = 0; i < SPEEDS.length; i++) {
            if (SPEEDS[i] == speed) return String.valueOf(i + 1);
        }
        return "";
    }

    private boolean isButtonOn(int i) {
        if (i == B_CTRL) return modDown[0];
        if (i == B_ALT) return modDown[1];
        if (i == B_SHIFT) return modDown[2];
        if (i == B_SUPER) return modDown[3];
        return false;
    }

    // Helpers.

    private int buttonAt(float x, float y) {
        if (toggleRect.contains(x, y)) return B_TOGGLE;
        if (!barVisible) return -1;
        for (int i = 0; i < buttonRects.length; i++) {
            RectF r = buttonRects[i];
            if (r != null && r.contains(x, y)) return i;
        }
        return -1;
    }

    private void send(int buttons) {
        VncClient c = client;
        if (c != null) {
            c.pointer(buttons | (padDrag ? BTN_LEFT : 0), Math.round(cx), Math.round(cy));
        }
    }

    /** Sends a pointer event with the given buttons at the current cursor. */
    private void sendAt(int buttons) {
        VncClient c = client;
        if (c != null) {
            c.pointer(buttons, Math.round(cx), Math.round(cy));
        }
    }

    private void sendKey(int keysym, boolean down) {
        VncClient c = client;
        if (c != null && keysym != 0) {
            c.key(down, keysym);
        }
    }

    private void tapKey(int keysym) {
        sendKey(keysym, true);
        sendKey(keysym, false);
    }

    // Touch handling.

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (client == null || fbW == 0) {
            return true;
        }
        int action = e.getActionMasked();
        int idx = e.getActionIndex();
        int id = e.getPointerId(idx);
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                onDown(id, e.getX(idx), e.getY(idx), e.getEventTime());
                return true;
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) {
                    onMove(e.getPointerId(i), e.getX(i), e.getY(i));
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                onUp(id, e.getX(idx), e.getY(idx), e.getEventTime());
                return true;
            case MotionEvent.ACTION_CANCEL:
                cancelAll();
                return true;
            default:
                return true;
        }
    }

    private void onDown(int id, float x, float y, long t) {
        int b = buttonAt(x, y);
        if (b == B_TOGGLE) {
            barVisible = !barVisible;
            layoutBar();
            invalidate();
            return;
        }
        if (b >= 0) {
            buttonForPointer.put(id, b);
            pressButton(b, true);
            invalidate();
            return;
        }
        if (padId == -1) {
            padId = id;
            padX = x;
            padY = y;
            padDownX = x;
            padDownY = y;
            padDownT = t;
            padMoved = false;
            padLongFired = false;
            // Quick second tap near the last tap starts a drag.
            boolean nearLastTap = t - lastTapT < DOUBLE_TAP_MS;
            if (nearLastTap) {
                padDrag = true;
                sendAt(BTN_LEFT);
            } else {
                padDrag = false;
                ui.postDelayed(longPress, LONG_PRESS_MS);
            }
        } else if (secondId == -1) {
            // Second finger: scroll or two-finger tap for right click.
            ui.removeCallbacks(longPress);
            if (padDrag) {
                padDrag = false;
                sendAt(0);
            }
            padMoved = true; // the pad's own lift must not click
            secondId = id;
            secondX = x;
            secondY = y;
            secondDownT = t;
            secondMoved = false;
            scrollAnchorY = (padY + secondY) / 2f;
        }
    }

    private void onMove(int id, float x, float y) {
        if (buttonForPointer.indexOfKey(id) >= 0) {
            return;
        }
        if (id == padId) {
            if (secondId != -1) {
                // Scroll mode: the pad moves the scroll anchor, not the cursor.
                padX = x;
                padY = y;
                scrollWithFingers();
                return;
            }
            float dx = x - padX;
            float dy = y - padY;
            padX = x;
            padY = y;
            if (!padMoved && dist(x, y, padDownX, padDownY) > touchSlop) {
                padMoved = true;
                ui.removeCallbacks(longPress);
            }
            if (dx == 0 && dy == 0) {
                return;
            }
            // Same as a trackpad: finger movement becomes cursor movement.
            float s = scale();
            moveCursor(dx * speed / s, dy * speed / s);
            // While dragging, the left button stays held in every move.
            sendAt(padDrag ? BTN_LEFT : 0);
        } else if (id == secondId) {
            secondX = x;
            secondY = y;
            if (!secondMoved && dist(x, y, secondX, secondY) > touchSlop) {
                secondMoved = true;
            }
            scrollWithFingers();
        }
    }

    private void moveCursor(float dx, float dy) {
        cx = clampF(cx + dx, 0, fbW - 1);
        cy = clampF(cy + dy, 0, fbH - 1);
    }

    private void scrollWithFingers() {
        if (secondId == -1) return;
        float avg = (padY + secondY) / 2f;
        float step = SCROLL_STEP_DP * density;
        float delta = avg - scrollAnchorY;
        while (delta >= step) {
            sendAt(BTN_WHEEL_UP);
            sendAt(0);
            scrollAnchorY += step;
            delta -= step;
        }
        while (delta <= -step) {
            sendAt(BTN_WHEEL_DOWN);
            sendAt(0);
            scrollAnchorY -= step;
            delta += step;
        }
    }

    private void onUp(int id, float x, float y, long t) {
        int b = buttonForPointer.get(id, -1);
        if (b >= 0) {
            buttonForPointer.delete(id);
            releaseButton(b);
            invalidate();
            return;
        }
        if (id == secondId) {
            if (!secondMoved && t - secondDownT < TAP_MAX_MS) {
                sendAt(BTN_RIGHT);
                sendAt(0);
            }
            secondId = -1;
            return;
        }
        if (id == padId) {
            ui.removeCallbacks(longPress);
            if (padDrag) {
                padDrag = false;
                sendAt(0);
                lastTapT = 0;
            } else if (!padMoved && !padLongFired && t - padDownT < TAP_MAX_MS && secondId == -1) {
                sendAt(BTN_LEFT);
                sendAt(0);
                lastTapT = t;
                lastTapCx = cx;
                lastTapCy = cy;
            }
            padId = -1;
        }
    }

    private void cancelAll() {
        ui.removeCallbacks(longPress);
        for (int i = 0; i < buttonForPointer.size(); i++) {
            releaseButton(buttonForPointer.valueAt(i));
        }
        buttonForPointer.clear();
        if (padDrag) {
            padDrag = false;
            sendAt(0);
        }
        padId = -1;
        secondId = -1;
        invalidate();
    }

    // Controller actions.

    private void pressButton(int b, boolean down) {
        // Pressed state drives the highlight.
        pressedButton[b] = down;
        switch (b) {
            case B_LEFT:
                sendAt(down ? BTN_LEFT : 0);
                break;
            case B_MIDDLE:
                sendAt(down ? BTN_MIDDLE : 0);
                break;
            case B_RIGHT:
                sendAt(down ? BTN_RIGHT : 0);
                break;
            case B_ESC:
                if (down) tapKey(0xff1b);
                break;
            case B_TAB:
                if (down) tapKey(0xff09);
                break;
            case B_ENTER:
                if (down) tapKey(0xff0d);
                break;
            case B_CTRL:
                if (down) toggleMod(0, 0xffe3);
                break;
            case B_ALT:
                if (down) toggleMod(1, 0xffe9);
                break;
            case B_SHIFT:
                if (down) toggleMod(2, 0xffe1);
                break;
            case B_SUPER:
                if (down) toggleMod(3, 0xffeb);
                break;
            case B_KBD:
                if (down) showKeyboard();
                break;
            case B_SPEED:
                if (down) cycleSpeed();
                break;
            default:
                break;
        }
    }

    private void releaseButton(int b) {
        pressedButton[b] = false;
        if (b == B_LEFT || b == B_MIDDLE || b == B_RIGHT) {
            sendAt(0);
        }
    }

    private void toggleMod(int m, int keysym) {
        modDown[m] = !modDown[m];
        sendKey(keysym, modDown[m]);
    }

    private void cycleSpeed() {
        int i = 0;
        for (int k = 0; k < SPEEDS.length; k++) {
            if (SPEEDS[k] == speed) i = k;
        }
        speed = SPEEDS[(i + 1) % SPEEDS.length];
    }

    private void showKeyboard() {
        requestFocus();
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private static float dist(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2;
        float dy = y1 - y2;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static float clampF(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // Keyboard input.

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        outAttrs.inputType = android.text.InputType.TYPE_NULL;
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_ACTION_NONE;
        return new BaseInputConnection(this, true) {
            @Override
            public boolean commitText(CharSequence t, int newCursorPosition) {
                for (int i = 0; i < t.length(); ) {
                    int cp = Character.codePointAt(t, i);
                    tapKey(keysymFor(cp));
                    i += Character.charCount(cp);
                }
                return true;
            }

            @Override
            public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                for (int i = 0; i < beforeLength; i++) {
                    tapKey(0xff08); // BackSpace
                }
                return true;
            }

            @Override
            public boolean sendKeyEvent(KeyEvent event) {
                return handleKeyEvent(event);
            }
        };
    }

    private boolean handleKeyEvent(KeyEvent event) {
        VncClient c = client;
        if (c == null) return false;
        int keysym = keysymForKeyCode(event.getKeyCode(), event.getUnicodeChar());
        if (keysym == 0) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            c.key(true, keysym);
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            c.key(false, keysym);
        } else {
            return false;
        }
        return true;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return handleKeyEvent(event) || super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        return handleKeyEvent(event) || super.onKeyUp(keyCode, event);
    }

    /** X11 keysym for a Unicode code point. Latin-1 keysyms equal the code point. */
    static int keysymFor(int cp) {
        if (cp < 0x100) return cp;
        return 0x01000000 | cp;
    }

    static int keysymForKeyCode(int keyCode, int unicode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                return 0xff0d;
            case KeyEvent.KEYCODE_DEL:
                return 0xff08;
            case KeyEvent.KEYCODE_FORWARD_DEL:
                return 0xffff;
            case KeyEvent.KEYCODE_TAB:
                return 0xff09;
            case KeyEvent.KEYCODE_ESCAPE:
                return 0xff1b;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return 0xff51;
            case KeyEvent.KEYCODE_DPAD_UP:
                return 0xff52;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return 0xff53;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return 0xff54;
            case KeyEvent.KEYCODE_MOVE_HOME:
                return 0xff50;
            case KeyEvent.KEYCODE_MOVE_END:
                return 0xff57;
            case KeyEvent.KEYCODE_PAGE_UP:
                return 0xff55;
            case KeyEvent.KEYCODE_PAGE_DOWN:
                return 0xff56;
            case KeyEvent.KEYCODE_SHIFT_LEFT:
                return 0xffe1;
            case KeyEvent.KEYCODE_CTRL_LEFT:
                return 0xffe3;
            case KeyEvent.KEYCODE_ALT_LEFT:
                return 0xffe9;
            default:
                return unicode != 0 ? keysymFor(unicode) : 0;
        }
    }
}
