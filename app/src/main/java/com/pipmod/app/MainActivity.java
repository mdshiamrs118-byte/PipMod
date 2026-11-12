package com.pipmod.app;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
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
            // Works when the module is active in LSPosed (xposedsharedprefs).
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
        info.setText("Enable this module in LSPosed with scope \"System UI\", then restart "
                + "SystemUI (or reboot). Changes apply the next time a PiP window is created.");
        root.addView(info);

        // Pinch toggle
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

    private void addSlider(LinearLayout parent, String label, final String key,
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
                tv.setText(label(tv.getText().toString()) + ": " + v + unit);
                if (fromUser) sp.edit().putInt(key, v).apply();
            }

            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        parent.addView(sb);
    }

    private static String label(String text) {
        int i = text.lastIndexOf(':');
        return i > 0 ? text.substring(0, i) : text;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
