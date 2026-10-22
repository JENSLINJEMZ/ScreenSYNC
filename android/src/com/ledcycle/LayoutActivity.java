package com.ledcycle;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The Layout option: a live screen-sync view of the strip setup. Every light is
 * a tile that mirrors exactly what the app is sending to that strip in real
 * time (screen sync), and every light can be controlled individually — follow
 * the hue cycle, pin its own static colour (hue + brightness sliders), or
 * switch it off. Changes take effect live while the cycle runs, and are saved
 * for the next start when it doesn't.
 */
public class LayoutActivity extends Activity {

    private static final long SYNC_MS = 150;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    private LinearLayout cardList;
    private TextView syncText, hint;
    private GradientDrawable syncDotBg;
    private Button startBtn, stopBtn;

    private final List<LightCard> cards = new ArrayList<>();

    private LedService service;
    private boolean bound;

    /** Everything for one light: live tile, label and its controls. */
    private final class LightCard {
        final String mac;
        final LedService.LightState st;
        View swatch;
        GradientDrawable swatchBg;
        TextView name, stLabel;
        Button cycleBtn, colourBtn, offBtn;
        LinearLayout picker;

        LightCard(String mac, LedService.LightState st) {
            this.mac = mac;
            this.st = st;
        }

        void apply() {
            if (bound && service != null) {
                service.setLayoutState(mac, st.followCycle, st.on, st.color);
            } else {
                LedService.writeLightState(LayoutActivity.this, mac,
                        st.followCycle, st.on, st.color);
            }
            refreshButtons();
        }

        void refreshButtons() {
            cycleBtn.setText(st.followCycle ? "Cycle ✓" : "Cycle ✗");
            offBtn.setText(st.on ? "On" : "Off");
            stLabel.setText(!st.on ? "off"
                    : st.followCycle ? "follows hue cycle" : "static colour");
            stLabel.setTextColor(!st.on ? Ui.IDLE
                    : st.followCycle ? Ui.GREEN : Ui.ACC2);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("ledcycle", Context.MODE_PRIVATE);
        android.app.ActionBar ab = getActionBar();
        if (ab != null) ab.hide();
        getWindow().setNavigationBarColor(Ui.BG);
        buildUi();
        rebuildCards();
        ui.post(sync);
    }

    // ---- screen sync loop --------------------------------------------------

    private final Runnable sync = new Runnable() {
        @Override
        public void run() {
            boolean live = LedService.RUNNING;
            syncText.setText(live ? "LIVE SCREEN SYNC — SCREEN = STRIPS"
                    : "PREVIEW — CYCLE OFF");
            syncText.setTextColor(live ? Ui.GREEN : Ui.IDLE);
            syncDotBg.setColor(live ? Ui.GREEN : Ui.IDLE);
            for (LightCard card : cards) {
                int color;
                if (live && bound && service != null) {
                    color = service.getLiveColor(card.mac);
                } else {
                    color = card.st.on
                            ? (card.st.followCycle ? startHueColor() : card.st.color)
                            : Color.BLACK;
                }
                card.swatchBg.setColor(color);
            }
            startBtn.setEnabled(!LedService.RUNNING);
            startBtn.setAlpha(LedService.RUNNING ? 0.35f : 1f);
            stopBtn.setEnabled(LedService.RUNNING);
            stopBtn.setAlpha(LedService.RUNNING ? 1f : 0.35f);
            ui.postDelayed(this, SYNC_MS);
        }
    };

    private int startHueColor() {
        float hue = ((prefs.getFloat("startHue", 0f) % 360f) + 360f) % 360f;
        return Color.HSVToColor(new float[]{hue, 1f, 1f});
    }

    // ---- start / stop (like the main screen) -------------------------------

    private void startCycle() {
        if (LedService.RUNNING) return;
        if (prefs.getStringSet(LedService.PREF_MACS, new java.util.HashSet<>()).isEmpty()) {
            hint.setText("Select lights on the main screen first (Scan + tick)");
            return;
        }
        Intent i = new Intent(this, LedService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
        bind();
    }

    private void stopCycle() {
        if (bound) {
            unbindService(conn);
            bound = false;
            service = null;
        }
        startService(new Intent(this, LedService.class).setAction(LedService.ACTION_STOP));
    }

    // ---- binding -----------------------------------------------------------

    @Override
    protected void onResume() {
        super.onResume();
        rebuildCards();
        if (LedService.RUNNING && !bound) bind();
    }

    @Override
    protected void onPause() {
        if (bound) {
            unbindService(conn);
            bound = false;
            service = null;
        }
        super.onPause();
    }

    private void bind() {
        bindService(new Intent(this, LedService.class), conn, Context.BIND_AUTO_CREATE);
    }

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder b) {
            service = ((LedService.LocalBinder) b).getService();
            bound = true;
            // Push the current card states into the service so its in-memory
            // layout can't be stale relative to what the screen is showing.
            for (LightCard card : cards) {
                service.setLayoutState(card.mac, card.st.followCycle,
                        card.st.on, card.st.color);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            service = null;
        }
    };

    // ---- cards -------------------------------------------------------------

    /** Rebuild the light cards from preferences (fresh on every resume). */
    private void rebuildCards() {
        cards.clear();
        cardList.removeAllViews();
        Map<String, LedService.LightState> saved = LedService.readLayout(this);
        // Selected lights first, then any light that only exists in the layout.
        java.util.LinkedHashSet<String> macs = new java.util.LinkedHashSet<>();
        macs.addAll(prefs.getStringSet(LedService.PREF_MACS, new java.util.HashSet<>()));
        macs.addAll(saved.keySet());
        for (String mac : macs) {
            LightCard card = new LightCard(mac, lightState(saved, mac));
            buildCardUi(card);
            cards.add(card);
        }
    }

    private static LedService.LightState lightState(Map<String, LedService.LightState> saved, String mac) {
        LedService.LightState st = saved.get(mac);
        return st != null ? st : new LedService.LightState();
    }

    private void buildCardUi(final LightCard card) {
        LinearLayout row = Ui.card(this);
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        // The live-sync tile: this View's paint is what the strip shows.
        card.swatch = new View(this);
        card.swatchBg = Ui.fill(Color.BLACK, 18, this);
        card.swatch.setBackground(card.swatchBg);
        head.addView(card.swatch, new LinearLayout.LayoutParams(dp(64), dp(64)));

        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(dp(20), 0, 0, 0);
        card.name = Ui.text(this, "LEDDMX …-" + shortMac(card.mac), 15f, Ui.TXT, true);
        card.stLabel = Ui.text(this, "", 12f, Ui.GREEN, true);
        names.addView(card.name);
        names.addView(card.stLabel);
        head.addView(names, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(head);

        LinearLayout ctrls = new LinearLayout(this);
        ctrls.setOrientation(LinearLayout.HORIZONTAL);
        ctrls.setPadding(0, dp(14), 0, 0);
        card.cycleBtn = Ui.pill(this, "Cycle", 2, v -> {
            card.st.followCycle = !card.st.followCycle;
            card.apply();
        });
        card.colourBtn = Ui.pill(this, "Colour", 2, v -> {
            card.picker.setVisibility(
                    card.picker.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        });
        card.offBtn = Ui.pill(this, "On", 2, v -> {
            card.st.on = !card.st.on;
            card.apply();
        });
        ctrls.addView(card.cycleBtn, Ui.grow());
        ctrls.addView(new View(this), new LinearLayout.LayoutParams(dp(6), 1));
        ctrls.addView(card.colourBtn, Ui.grow());
        ctrls.addView(new View(this), new LinearLayout.LayoutParams(dp(6), 1));
        ctrls.addView(card.offBtn, Ui.grow());
        row.addView(ctrls);

        // Static-colour picker: pin this light to its own colour (hue + level).
        card.picker = new LinearLayout(this);
        card.picker.setOrientation(LinearLayout.VERTICAL);
        card.picker.setVisibility(View.GONE);
        card.picker.setBackground(Ui.fill(Ui.CARD2, 14, this));
        card.picker.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        pp.topMargin = dp(12);
        card.picker.setLayoutParams(pp);
        card.picker.addView(Ui.tag(this, "Pin a colour — hue & level"));
        card.picker.addView(tintedBar(makeHueBar(card)));
        card.picker.addView(spacer(8));
        card.picker.addView(tintedBar(makeLevelBar(card)));
        row.addView(card.picker);

        card.refreshButtons();
        cardList.addView(row, gap());
    }

    /** Moving either slider pins the light to a static colour, live. */
    private SeekBar makeHueBar(final LightCard card) {
        SeekBar hue = new SeekBar(this);
        hue.setMax(359);
        float[] hsv = new float[3];
        Color.colorToHSV(card.st.color, hsv);
        hue.setProgress((int) hsv[0]);
        hue.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (!fromUser) return;
                pinColor(card, p, levelOf(card));
            }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {}
        });
        return hue;
    }

    private SeekBar makeLevelBar(final LightCard card) {
        SeekBar level = new SeekBar(this);
        level.setMax(100);
        float[] hsv = new float[3];
        Color.colorToHSV(card.st.color, hsv);
        level.setProgress((int) (hsv[2] * 100));
        level.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (!fromUser) return;
                float[] c = new float[3];
                Color.colorToHSV(card.st.color, c);
                pinColor(card, (int) c[0], p);
            }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {}
        });
        return level;
    }

    private int levelOf(LightCard card) {
        float[] hsv = new float[3];
        Color.colorToHSV(card.st.color, hsv);
        return (int) (hsv[2] * 100);
    }

    private void pinColor(LightCard card, int hue, int levelPct) {
        card.st.color = Color.HSVToColor(new float[]{(float) hue, 1f, levelPct / 100f});
        card.st.followCycle = false;
        card.st.on = true;
        card.apply();
        // Show the chosen colour right away; live sync resumes the next tick.
        card.swatchBg.setColor(card.st.color);
    }

    // ---- UI ----------------------------------------------------------------

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        // ---- header ------------------------------------------------------
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackground(Ui.header(this, 26));
        int hp = Ui.dp(this, 20);
        header.setPadding(hp, Ui.dp(this, 24), hp, hp);

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.HORIZONTAL);
        brand.setGravity(android.view.Gravity.CENTER_VERTICAL);
        Button back = Ui.pill(this, "‹  Main screen", 2, v -> finish());
        brand.addView(back, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        header.addView(brand);

        TextView title = Ui.text(this, "LAYOUT", 22f, Ui.TXT, true);
        title.setPadding(0, Ui.dp(this, 12), 0, 0);
        header.addView(title);
        TextView sub = Ui.text(this, "ONE TILE PER LIGHT · TAP TO CONTROL", 10.5f, 0xB8FFFFFF, true);
        sub.setPadding(0, Ui.dp(this, 2), 0, 0);
        header.addView(sub);
        root.addView(header);

        // ---- content -----------------------------------------------------
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 14);
        panel.setPadding(pad, pad, pad, pad);

        // sync-state chip
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(android.view.Gravity.CENTER_VERTICAL);
        chip.setBackground(Ui.fill(Ui.CARD, 999, this));
        int cp = Ui.dp(this, 12);
        chip.setPadding(cp, cp / 2, cp, cp / 2);
        syncDotBg = Ui.fill(Ui.IDLE, Ui.dp(this, 5), this);
        View dot = new View(this);
        dot.setBackground(syncDotBg);
        chip.addView(dot, new LinearLayout.LayoutParams(Ui.dp(this, 10), Ui.dp(this, 10)));
        syncText = Ui.text(this, "PREVIEW — CYCLE OFF", 11f, Ui.IDLE, true);
        syncText.setPadding(Ui.dp(this, 10), 0, 0, 0);
        chip.addView(syncText);
        panel.addView(chip, gap());

        // start/stop pills
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        startBtn = Ui.pill(this, "Start cycle", 0, v -> startCycle());
        stopBtn = Ui.pill(this, "Stop", 1, v -> stopCycle());
        row.addView(startBtn, Ui.grow());
        row.addView(new View(this),
                new LinearLayout.LayoutParams(Ui.dp(this, 8), 1));
        row.addView(stopBtn, Ui.grow());
        panel.addView(row, gap());

        // light cards live here (rebuilt on every resume)
        cardList = new LinearLayout(this);
        cardList.setOrientation(LinearLayout.VERTICAL);
        panel.addView(cardList, gap());

        hint = Ui.text(this, "", 13f, Ui.MUTED, false);
        hint.setPadding(0, Ui.dp(this, 4), 0, 0);
        panel.addView(hint);

        ScrollView scroller = new ScrollView(this);
        scroller.addView(panel);
        root.addView(scroller, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        getWindow().setStatusBarColor(Ui.BG);
    }

    private LinearLayout.LayoutParams gap() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 12);
        return lp;
    }

    private int dp(int v) {
        return Ui.dp(this, v);
    }

    private Button button(String text, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(onClick);
        return b;
    }

    private View spacer(int dp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(dp)));
        return v;
    }

    private SeekBar tintedBar(SeekBar sb) {
        android.content.res.ColorStateList accent =
                android.content.res.ColorStateList.valueOf(Ui.ACC2);
        android.content.res.ColorStateList track =
                android.content.res.ColorStateList.valueOf(Ui.TRACK);
        sb.setProgressTintList(accent);
        sb.setProgressBackgroundTintList(track);
        sb.setThumbTintList(accent);
        sb.setPadding(0, dp(8), 0, 0);
        return sb;
    }

    private static String shortMac(String mac) {
        return mac.length() <= 4 ? mac : mac.substring(mac.length() - 4).replace(":", "");
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(sync);
        super.onDestroy();
    }
}