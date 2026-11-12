package com.pipmod.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private SharedPreferences sp;

    @SuppressWarnings({"deprecation", "WorldReadableFiles"})
    private SharedPreferences openPrefs() {
        try {
            return getSharedPreferences("settings", Context.MODE_WORLD_READABLE);
        } catch (SecurityException e) {
            Toast.makeText(this, "Module not active in LSPosed - settings won't apply",
                    Toast.LENGTH_LONG).show();
            return getSharedPreferences("settings", Context.MODE_PRIVATE);
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = openPrefs();

        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView info = new TextView(this);
        info.setText("Enable this module in LSPosed with scope \"System UI\", change the "
                + "settings, then tap \"Restart System UI\" and open a new PiP window.");
        root.addView(info);

        Button restart = new Button(this);
        restart.setText("Restart System UI (root)");
        restart.setOnClickListener(v -> restartSystemUi());
        root.addView(restart);

        Button debug = new Button(this);
        debug.setText("Show debug info");
        debug.setOnClickListener(v -> showDebug());
        root.addView(debug);

        Switch pinch = new Switch(this);
        pinch.setText("Enable two-finger pinch zoom");
        pinch.setChecked(sp.getBoolean("pinch_enabled", true));
        pinch.setPadding(0, pad, 0, pad);
        pinch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton v, boolean on) {
                sp.edit().putBoolean("pinch_enabled", on).apply();
            }
        });
        root.addView(pinch);

        addSlider(root, "Default size (% of screen width)", "default_percent", 15, 90, 50, "%");
        addSlider(root, "Minimum size (% of screen width)", "min_percent", 10, 80, 30, "%");
        addSlider(root, "Maximum size (% of screen width)", "max_percent", 30, 100, 90, "%");
        addSlider(root, "Corner radius", "corner_dp", 0, 64, 16, "dp");

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
    }

    private void showDebug() {
        SharedPreferences d = getSharedPreferences("debug", Context.MODE_PRIVATE);
        final String text = d.getString("text", null);
        TextView tv = new TextView(this);
        tv.setTextIsSelectable(true);
        tv.setTextSize(11);
        tv.setPadding(dp(12), dp(12), dp(12), dp(12));
        tv.setText(text == null
                ? "Nothing received yet. Make sure the module is enabled for System UI, "
                + "restart System UI, then start a PiP window and reopen this screen."
                : text);
        ScrollView s = new ScrollView(this);
        s.addView(tv);
        AlertDialog.Builder bld = new AlertDialog.Builder(this)
                .setTitle("Debug info")
                .setView(s)
                .setPositiveButton("Close", null);
        if (text != null) {
            bld.setNeutralButton("Copy", (dlg, w) -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("PipMod debug", text));
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
            });
        }
        bld.show();
    }

    private void restartSystemUi() {
        new Thread(() -> {
            String msg;
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c",
                        "killall com.android.systemui || pkill -f com.android.systemui"});
                int code = p.waitFor();
                msg = code == 0 ? "System UI restarting..." : "Command failed (exit " + code + ")";
            } catch (Exception e) {
                msg = "Root not available: " + e.getMessage();
            }
            final String m = msg;
            runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
        }).start();
    }

    private void addSlider(LinearLayout parent, final String label, final String key,
                           final int min, int max, int def, final String unit) {
        final TextView tv = new TextView(this);
        tv.setPadding(0, dp(16), 0, 0);
        int cur = sp.getInt(key, def);
        tv.setText(label + ": " + cur + unit);
        parent.addView(tv);

        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(cur - min);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                int v = p + min;
                tv.setText(label + ": " + v + unit);
                if (fromUser) sp.edit().putInt(key, v).apply();
            }

            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        parent.addView(sb);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
