package app.desktop;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.io.IOException;

/**
 * The full-screen desktop. Connects to the local VNC server and reconnects if the
 * connection drops while the desktop is still running.
 */
public class DesktopActivity extends Activity {

    private DesktopView view;
    private TextView overlay;
    private VncClient client;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean finishing;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);

        FrameLayout frame = new FrameLayout(this);
        view = new DesktopView(this);
        frame.addView(view, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        overlay = new TextView(this);
        overlay.setText("Connecting to the desktop...");
        overlay.setTextColor(Color.WHITE);
        overlay.setTextSize(16);
        overlay.setGravity(Gravity.CENTER);
        overlay.setBackgroundColor(0xCC000000);
        int pad = dp(16);
        overlay.setPadding(pad, pad, pad, pad);
        frame.addView(overlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP));

        setContentView(frame);
        hideSystemBars();
        connectAsync();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemBars();
        }
    }

    /** Immersive mode: hides the status and navigation bars, shows them again on swipe. */
    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    private void connectAsync() {
        new Thread(() -> {
            // Every open starts at the login screen, like signing in to a PC.
            Session.lockDesktop(this);
            String password = Session.vncPassword(this);
            if (!Session.isDesktopRunning() && !Session.vncListening()) {
                fail("The desktop is not running. Start it from the main screen.");
                return;
            }
            IOException last = null;
            // The server can take a moment to accept connections after startup.
            for (int attempt = 0; attempt < 20 && !finishing; attempt++) {
                VncClient c = new VncClient("127.0.0.1", Session.VNC_PORT, password, listener());
                try {
                    c.connect(3000);
                    client = c;
                    ui.post(() -> {
                        view.attach(c);
                        overlay.setVisibility(View.GONE);
                        view.requestFocus();
                    });
                    return;
                } catch (IOException e) {
                    last = e;
                    if (e.getMessage() != null && e.getMessage().contains("Wrong VNC password")) {
                        break;
                    }
                    sleep(500);
                }
            }
            fail("Could not connect: " + (last == null ? "unknown error" : last.getMessage()));
        }).start();
    }

    private VncClient.Listener listener() {
        return new VncClient.Listener() {
            @Override
            public void onResize(int width, int height) {
                view.onFramebufferResized(width, height);
            }

            @Override
            public void onRect(int x, int y, int w, int h) {
                view.onFramebufferChanged();
            }

            @Override
            public void onClosed(String reason) {
                if (!finishing) {
                    fail("Disconnected" + (reason == null ? "" : ": " + reason) + ". Reconnecting...");
                    reconnectAfterDrop();
                }
            }
        };
    }

    /** Reconnects while the desktop is still running. Gives up after a while. */
    private void reconnectAfterDrop() {
        new Thread(() -> {
            for (int attempt = 0; attempt < 30 && !finishing; attempt++) {
                sleep(1000);
                if (!Session.isDesktopRunning()) {
                    fail("The desktop stopped. Start it again from the main screen.");
                    return;
                }
                String password = Session.vncPassword(DesktopActivity.this);
                VncClient c = new VncClient("127.0.0.1", Session.VNC_PORT, password, listener());
                try {
                    c.connect(3000);
                    client = c;
                    ui.post(() -> {
                        view.attach(c);
                        overlay.setVisibility(View.GONE);
                    });
                    return;
                } catch (IOException ignored) {
                    // Try again.
                }
            }
            fail("Could not reconnect. Use Back and open the desktop again.");
        }).start();
    }

    private void fail(String message) {
        ui.post(() -> {
            overlay.setText(message);
            overlay.setVisibility(View.VISIBLE);
        });
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    protected void onDestroy() {
        finishing = true;
        if (client != null) {
            client.close();
        }
        super.onDestroy();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
