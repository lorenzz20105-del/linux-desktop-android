package app.desktop;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.animation.LinearInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;

/**
 * The loading screen. It only shows the extraction, with an animated ring and the percentage.
 * On the first run it unpacks the Linux system and the Windows add-on. After that it starts the
 * desktop and opens it, and the desktop shows its login screen.
 */
public class MainActivity extends Activity {

    /**
     * The archives are packaged in the APK (assets/). The SHA-256 values are checked while they
     * are unpacked, so a damaged APK fails instead of starting a broken desktop.
     */
    static final String BASE_ASSET = "rootfs-debian-trixie-arm64.tar.gz";
    static final String BASE_SHA = "1ba8c154ded6d3e52cf1c813b99d1617da6f0b5f56c3ecdcc524e9fd95ebacde";
    static final String WINE_ASSET = "rootfs-wine-addon-arm64.tar.gz";
    static final String WINE_SHA = "867b60f1fe2de707ccce52b31f968c98c3c179ed3d659a2e1bfbef977572463b";
    static final String AGENTS_ASSET = "rootfs-agents-arm64.tar.gz";
    static final String AGENTS_SHA = "6f092085e466b239751a074358e38a02d0bea2810218eaafb170ab3f798da19b";

    private static final int BG = Color.parseColor("#0b1f3a");
    private static final int BG_LOW = Color.parseColor("#06101f");
    private static final int ACCENT = Color.parseColor("#2f6fd6");

    private LoaderView loader;
    private TextView stage;
    private TextView percent;
    private TextView detail;
    private long lastPost;
    private boolean openedDesktop;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildLayout();
        if (state == null) {
            new Thread(this::runFlow).start();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Back from the desktop closes the app, so the next launch starts from the login screen.
        if (openedDesktop) {
            finish();
        }
    }

    private void buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {BG, BG_LOW}));
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        setContentView(scroll);

        loader = new LoaderView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(160), dp(160));
        lp.setMargins(0, 0, 0, dp(24));
        root.addView(loader, lp);

        stage = text("Preparing", 20, true);
        root.addView(stage);
        percent = text("", 15, false);
        root.addView(percent);
        detail = text("", 13, false);
        detail.setSingleLine(true);
        detail.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        root.addView(detail);
    }

    /** Extracts what is missing from the APK, then starts the desktop and opens it. Worker thread. */
    private void runFlow() {
        try {
            if (!Session.isInstalled(this)) {
                setStage("Extracting the Linux system", "");
                Installer.installBase(this, Installer.openAsset(this, BASE_ASSET), BASE_SHA, progress());
                // A fresh base removes the add-ons, so they must be added again.
                markWine(false);
                markAgents(false);
            }
            if (!prefsWine()) {
                try {
                    Installer.installAddon(this, Installer.openAsset(this, WINE_ASSET), WINE_SHA, progress());
                    markWine(true);
                } catch (IOException e) {
                    // The desktop works without Windows support, so carry on.
                    toast("Windows support was not added: " + e.getMessage());
                }
            }
            if (prefsWine() && !prefsAgents()) {
                try {
                    Installer.installOverlay(this, Installer.openAsset(this, AGENTS_ASSET), AGENTS_SHA,
                            progress(), "usr/local/bin/claude", "Adding developer tools");
                    markAgents(true);
                } catch (IOException e) {
                    toast("Developer tools were not added: " + e.getMessage());
                }
            }
            setStage("Starting the desktop", "");
            runOnUiThread(() -> {
                loader.setIndeterminate();
                percent.setText("");
            });
            if (!Session.isDesktopRunning()) {
                Session.startDesktop(this);
            }
            if (!Session.waitForVnc(120_000)) {
                throw new IOException("the desktop did not start" + Session.desktopExitInfo() + "\n\n"
                        + Session.logTail(this, 80));
            }
            runOnUiThread(() -> {
                loader.setProgress(1f);
                openedDesktop = true;
                startActivity(new Intent(this, DesktopActivity.class));
            });
        } catch (IOException e) {
            String message = e.getMessage() == null ? "Setup failed" : e.getMessage();
            setStage("Could not set up the desktop", message);
            runOnUiThread(() -> {
                loader.setIdle();
                percent.setText("");
                // Show the whole message, including the log lines, in a readable block.
                detail.setSingleLine(false);
                detail.setEllipsize(null);
                detail.setTextSize(11);
                detail.setTypeface(android.graphics.Typeface.MONOSPACE);
                detail.setGravity(Gravity.START);
            });
            toast(message.split("\n", 2)[0]);
        }
    }

    private boolean prefsWine() {
        return getSharedPreferences("desktop", MODE_PRIVATE).getBoolean("wine_addon_installed", false);
    }

    private boolean prefsAgents() {
        return getSharedPreferences("desktop", MODE_PRIVATE).getBoolean("agents_installed", false);
    }

    private void markAgents(boolean installed) {
        getSharedPreferences("desktop", MODE_PRIVATE).edit().putBoolean("agents_installed", installed).apply();
    }

    private void markWine(boolean installed) {
        getSharedPreferences("desktop", MODE_PRIVATE).edit().putBoolean("wine_addon_installed", installed).apply();
    }

    private Installer.Progress progress() {
        return new Installer.Progress() {
            @Override
            public void update(String message) {
                String[] parts = message.split(": ", 2);
                setStage(parts[0], parts.length > 1 ? parts[1] : "");
            }

            @Override
            public void fraction(double value) {
                long now = System.currentTimeMillis();
                if (value < 1.0 && now - lastPost < 100) {
                    return;
                }
                lastPost = now;
                int pct = (int) Math.round(value * 100);
                runOnUiThread(() -> {
                    loader.setProgress((float) value);
                    percent.setText(pct + "%");
                });
            }
        };
    }

    private void setStage(String stageText, String detailText) {
        runOnUiThread(() -> {
            stage.setText(stageText);
            detail.setText(detailText);
        });
    }

    private void toast(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Color.WHITE);
        t.setGravity(Gravity.CENTER);
        if (bold) {
            t.setTypeface(null, Typeface.BOLD);
        }
        t.setPadding(dp(4), dp(4), dp(4), dp(4));
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** A ring that spins while work runs and fills with the progress. */
    static final class LoaderView extends android.view.View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF oval = new RectF();
        private final ValueAnimator spin;
        private float progress = -1f;   // -1 means indeterminate (spinning)
        private float angle;
        private final float stroke;

        LoaderView(Context c) {
            super(c);
            stroke = 12 * c.getResources().getDisplayMetrics().density;
            track.setStyle(Paint.Style.STROKE);
            track.setStrokeWidth(stroke);
            track.setColor(Color.parseColor("#1c3a63"));
            arc.setStyle(Paint.Style.STROKE);
            arc.setStrokeWidth(stroke);
            arc.setStrokeCap(Paint.Cap.ROUND);
            arc.setColor(ACCENT);
            spin = ValueAnimator.ofFloat(0f, 360f);
            spin.setDuration(1100);
            spin.setRepeatCount(ValueAnimator.INFINITE);
            spin.setInterpolator(new LinearInterpolator());
            spin.addUpdateListener(a -> {
                angle = (float) a.getAnimatedValue();
                invalidate();
            });
        }

        void setIndeterminate() {
            progress = -1f;
            if (!spin.isRunning()) spin.start();
            invalidate();
        }

        void setProgress(float p) {
            progress = Math.max(0f, Math.min(1f, p));
            if (spin.isRunning()) spin.cancel();
            invalidate();
        }

        void setIdle() {
            progress = 0f;
            if (spin.isRunning()) spin.cancel();
            invalidate();
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (progress < 0f && !spin.isRunning()) spin.start();
        }

        @Override
        protected void onDetachedFromWindow() {
            spin.cancel();
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float half = Math.min(getWidth(), getHeight()) / 2f - stroke;
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            oval.set(cx - half, cy - half, cx + half, cy + half);
            canvas.drawOval(oval, track);
            if (progress < 0f) {
                canvas.drawArc(oval, angle, 110f, false, arc);
            } else if (progress > 0f) {
                canvas.drawArc(oval, -90f, 360f * progress, false, arc);
            }
        }
    }
}
