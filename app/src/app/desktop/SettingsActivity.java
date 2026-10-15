package app.desktop;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Desktop Settings. Lets you pick a local archive for the Linux system and the Windows add-on
 * instead of downloading them. A chosen file is used the next time that part is installed.
 * The loading screen itself stays free of buttons.
 */
public class SettingsActivity extends Activity {

    private static final int REQ_BASE = 1;
    private static final int REQ_ADDON = 2;
    private static final String PREFS = "desktop";
    private static final String KEY_BASE = "local_base_uri";
    private static final String KEY_ADDON = "local_addon_uri";

    private static final int BG = Color.parseColor("#0b1f3a");
    private static final int BG_LOW = Color.parseColor("#06101f");
    private static final int ACCENT = Color.parseColor("#2f6fd6");
    private static final int MUTED = Color.parseColor("#9fb3d1");

    private TextView baseStatus;
    private TextView addonStatus;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {BG, BG_LOW}));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(24));
        scroll.addView(root);
        setContentView(scroll);

        root.addView(label("Desktop Settings", 22, true, MUTED, 0));
        root.addView(label("Local archives are read from a file you choose. Leave both on "
                + "download to use the project's release files.", 13, false, MUTED, 8));

        baseStatus = label("", 14, false, Color.WHITE, 0);
        addonStatus = label("", 14, false, Color.WHITE, 0);
        root.addView(section("Linux system (rootfs-debian-trixie-arm64.tar.gz)",
                "Used the next time the Linux system is installed. It does not reinstall "
                        + "an existing desktop.",
                baseStatus, REQ_BASE, KEY_BASE));
        root.addView(section("Windows add-on (rootfs-wine-addon-arm64.tar.gz)",
                "Used the next time the Windows add-on is added.",
                addonStatus, REQ_ADDON, KEY_ADDON));
        refresh();
    }

    private LinearLayout section(String title, String help, TextView status, int req, String key) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#12294d"));
        bg.setCornerRadius(dp(12));
        box.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(16), 0, 0);
        box.setLayoutParams(lp);

        box.addView(label(title, 15, true, Color.WHITE, 0));
        box.addView(label(help, 12, false, MUTED, 4));
        box.addView(status);

        Button choose = new Button(this);
        choose.setText("Choose a local file");
        choose.setOnClickListener(v -> pick(req));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.setMargins(0, dp(12), 0, 0);
        box.addView(choose, blp);

        Button download = new Button(this);
        download.setText("Use download");
        download.setOnClickListener(v -> {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(key).apply();
            refresh();
        });
        box.addView(download, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return box;
    }

    private void pick(int req) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                "application/gzip", "application/x-gzip", "application/x-tar",
                "application/octet-stream"});
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(i, req);
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException e) {
            // Not persistable; it still works for this session's install.
        }
        String key = req == REQ_BASE ? KEY_BASE : KEY_ADDON;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(key, uri.toString()).apply();
        refresh();
    }

    private void refresh() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        baseStatus.setText(describe(p.getString(KEY_BASE, null)));
        addonStatus.setText(describe(p.getString(KEY_ADDON, null)));
    }

    private static String describe(String uri) {
        return uri == null ? "Source: download (release v1.0)" : "Source: local file selected";
    }

    private TextView label(String s, int sp, boolean bold, int color, int topDp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.START);
        if (bold) {
            t.setTypeface(null, Typeface.BOLD);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(topDp), 0, 0);
        t.setLayoutParams(lp);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
