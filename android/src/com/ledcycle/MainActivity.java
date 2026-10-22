package com.ledcycle;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * UI over {@link LedService}. Scans for {@code LEDDMX-*} controllers, lets the
 * user tick which lights to drive, and starts/stops the cycle. The selection is
 * saved to preferences and read by the service — no addresses are hardcoded.
 */
public class MainActivity extends Activity {

    private static final double MIN_MINUTES = 0.25, MAX_MINUTES = 60.0;
    private static final int REQ_PERMS = 1;
    private static final long SCAN_MS = 8000;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private double cycleMinutes = 30.0;
    private float startHueDeg = 0f;

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private boolean scanning;

    // address -> checkbox row (insertion-ordered so saved lights show first)
    private final Map<String, CheckBox> rows = new LinkedHashMap<>();

    private LinearLayout root, deviceList;
    private View colourArea;
    private TextView speedLabel, hueLabel, status;
    private Button scanBtn, startBtn, stopBtn, layoutBtn, screenBtn, orderBtn;
    private static final int REQ_SCREEN = 2;

    private LedService service;
    private boolean bound;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("ledcycle", Context.MODE_PRIVATE);
        cycleMinutes = prefs.getFloat("cycleMinutes", 30.0f);        startHueDeg = prefs.getFloat("startHue", 0f);
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        adapter = bm != null ? bm.getAdapter() : null;

        buildUi();
        android.app.ActionBar ab = getActionBar();
        if (ab != null) ab.hide();   // SignalRGB-style: own gradient header only
        getWindow().setNavigationBarColor(Ui.BG);
        showSavedDevices();

        if (needsPermission()) requestPermissions(permsToRequest(), REQ_PERMS);
        if (LedService.RUNNING) bind();
        ui.post(refresh);
    }

    // ---- device list -------------------------------------------------------

    private void showSavedDevices() {
        for (String mac : prefs.getStringSet(LedService.PREF_MACS, new HashSet<>())) {
            addRow(mac, "(saved)", true);
        }
    }

    /** Add or update a row for a discovered/saved controller. */
    private void addRow(String address, String label, boolean checked) {
        CheckBox cb = rows.get(address);
        if (cb == null) {
            cb = new CheckBox(this);
            cb.setTextColor(Ui.TXT);
            cb.setTextSize(14);
            cb.setButtonTintList(
                    android.content.res.ColorStateList.valueOf(Ui.ACC1));
            cb.setTag(address);
            cb.setChecked(checked);
            cb.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
            rows.put(address, cb);
            deviceList.addView(cb);
        }
        cb.setText(label + "  " + address);
    }

    private Set<String> checkedMacs() {
        Set<String> out = new HashSet<>();
        for (CheckBox cb : rows.values()) {
            if (cb.isChecked()) out.add((String) cb.getTag());
        }
        return out;
    }

    // ---- scanning ----------------------------------------------------------

    private void scan() {
        if (needsPermission()) {
            requestPermissions(permsToRequest(), REQ_PERMS);
            return;
        }
        if (adapter == null || !adapter.isEnabled()) {
            status.setText("Turn Bluetooth on, then Scan");
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null || scanning) return;
        scanning = true;
        status.setText("Scanning for lights…");
        try {
            scanner.startScan(null,
                    new ScanSettings.Builder()
                            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                    scanCallback);
        } catch (SecurityException e) {
            scanning = false;
            return;
        }
        ui.postDelayed(this::stopScan, SCAN_MS);
    }

    private void stopScan() {
        if (!scanning || scanner == null) return;
        scanning = false;
        try { scanner.stopScan(scanCallback); } catch (SecurityException ignored) {}
        int found = rows.size();
        status.setText(found == 0
                ? "No LEDDMX lights found — is Bluetooth on and the strip powered?"
                : "Found " + found + " — tick lights, then Start Cycle");
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int type, ScanResult result) {
            BluetoothDevice dev = result.getDevice();
            String name;
            try { name = dev.getName(); } catch (SecurityException e) { return; }
            if (name == null || !name.toUpperCase().startsWith(LedService.NAME_PREFIX)) return;
            String label = name + "  " + result.getRssi() + "dBm";
            boolean wasSelected = prefs.getStringSet(LedService.PREF_MACS, new HashSet<>())
                    .contains(dev.getAddress());
            ui.post(() -> addRow(dev.getAddress(), label, wasSelected || rowChecked(dev.getAddress())));
        }
    };

    private boolean rowChecked(String address) {
        CheckBox cb = rows.get(address);
        return cb != null && cb.isChecked();
    }

    // ---- start / stop ------------------------------------------------------

    private void startCycle() {
        if (needsPermission()) {
            requestPermissions(permsToRequest(), REQ_PERMS);
            return;
        }
        Set<String> sel = checkedMacs();
        if (sel.isEmpty()) {
            status.setText("Select at least one light (press Scan)");
            return;
        }
        prefs.edit().putStringSet(LedService.PREF_MACS, sel).apply();
        stopScan();
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
        status.setText("Stopped - strip holds its last colour");
    }

    // ---- colour order ------------------------------------------------------

    private String currentOrder() {
        return bound && service != null
                ? service.getColorOrder()
                : prefs.getString(LedService.PREF_ORDER, "rgb");
    }

    /** Cycle rgb → rbg → gbr → grb → brg → bgr, live, and remember it. */
    private void nextOrder() {
        String[] orders = LedService.ORDERS;
        int idx = 0;
        String cur = currentOrder();
        for (int i = 0; i < orders.length; i++) if (orders[i].equals(cur)) idx = i;
        String next = orders[(idx + 1) % orders.length];
        if (bound && service != null) {
            service.setColorOrder(next);
        } else {
            if (LedService.RUNNING) bind();   // running but not yet bound: bind, apply on next tap
            prefs.edit().putString(LedService.PREF_ORDER, next).apply();
        }
        status.setText("Colour order " + next + " — check the strip: a green screen must show green");
    }

    /** Open the Layout screen: live screen sync + per-light control. */
    private void openLayout() {
        stopScan();
        startActivity(new Intent(this, LayoutActivity.class));
    }

    /**
     * Screen sync like SignalRGB: capture the screen and feed its colours to
     * the strips. Press again (while bound) to switch it off.
     */
    private void screenSync() {
        if (LedService.RUNNING && bound && service != null && service.isScreenSync()) {
            service.stopScreenSync();
            status.setText("Screen sync off — lights are back on the cycle");
            return;
        }
        if (needsPermission()) { requestPermissions(permsToRequest(), REQ_PERMS); return; }
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_SCREEN);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        if (request != REQ_SCREEN) return;
        if (result != Activity.RESULT_OK) {
            status.setText("Screen sync needs capture permission");
            return;
        }
        Intent i = new Intent(this, LedService.class)
                .setAction(LedService.ACTION_SCREEN)
                .putExtra("code", result)
                .putExtra("data", data);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
        if (!bound) bind();
        status.setText("Screen sync starting…");
    }

    private void bind() {
        bindService(new Intent(this, LedService.class), conn, Context.BIND_AUTO_CREATE);
    }

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder b) {
            service = ((LedService.LocalBinder) b).getService();
            bound = true;
            service.setCycleMinutes(cycleMinutes);
            service.setStartHue(startHueDeg);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            service = null;
        }
    };

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            boolean running = LedService.RUNNING;
            startBtn.setEnabled(!running && anyChecked());
            startBtn.setAlpha(running ? 0.35f : 1f);
            stopBtn.setEnabled(running);
            stopBtn.setAlpha(running ? 1f : 0.35f);
            screenBtn.setAlpha(1f);
            scanBtn.setEnabled(!running && !scanning);
            orderBtn.setText(currentOrder().toUpperCase());

            boolean live = running && bound && service != null;
            GradientDrawable mirror = (GradientDrawable) colourArea.getBackground();
            if (live) {
                mirror.setColor(service.isScreenSync()
                        ? service.getScreenSyncColor() : service.getCurrentColor());
                status.setText(service.getStatus());
                mirrorTag.setText(service.isScreenSync() ? "SCREEN" : "CYCLE");
                mirrorTag.setTextColor(Ui.GREEN);
            } else {
                mirror.setColor(Color.BLACK);
                mirrorTag.setText("IDLE");
                mirrorTag.setTextColor(Ui.IDLE);
            }
            statusDotBg.setColor(running ? Ui.GREEN : Ui.IDLE);
            statusText.setText(running ? "RUNNING" : "IDLE");
            statusText.setTextColor(running ? Ui.GREEN : Ui.IDLE);
            ui.postDelayed(this, 150);
        }
    };

    private boolean anyChecked() {
        for (CheckBox cb : rows.values()) if (cb.isChecked()) return true;
        return false;
    }

    // ---- permissions -------------------------------------------------------

    private boolean hasPermission(String p) {
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean needsPermission() {
        if (Build.VERSION.SDK_INT >= 31) {
            return !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    || !hasPermission(Manifest.permission.BLUETOOTH_SCAN);
        }
        // Pre-Android 12: legacy permissions (BLUETOOTH is normal/install-time,
        // BLE discovery needs ACCESS_FINE_LOCATION at runtime).
        return !hasPermission(Manifest.permission.ACCESS_FINE_LOCATION);
    }

    private String[] permsToRequest() {
        if (Build.VERSION.SDK_INT >= 33) {
            return new String[]{
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.POST_NOTIFICATIONS};
        }
        if (Build.VERSION.SDK_INT >= 31) {
            return new String[]{
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN};
        }
        return new String[]{
                Manifest.permission.BLUETOOTH,
                Manifest.permission.ACCESS_FINE_LOCATION};
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        if (req != REQ_PERMS) return;
        if (needsPermission()) {
            status.setText("Bluetooth permission needed to scan and connect");
        }
    }

    // ---- UI ----------------------------------------------------------------

    private GradientDrawable heroBg, statusDotBg;
    private TextView statusText, mirrorTag;

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        // ---- gradient header --------------------------------------------
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackground(Ui.header(this, 26));
        int hp = Ui.dp(this, 20);
        header.setPadding(hp, Ui.dp(this, 30), hp, hp);

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.HORIZONTAL);
        brand.setGravity(android.view.Gravity.CENTER_VERTICAL);
        brand.addView(Ui.text(this, "LED CYCLE", 23f, Ui.TXT, true));
        brand.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        statusText = Ui.text(this, "IDLE", 12f, Ui.IDLE, true);
        statusText.setPadding(0, 0, Ui.dp(this, 8), 0);
        brand.addView(statusText);
        statusDotBg = Ui.fill(Ui.IDLE, Ui.dp(this, 5), this);
        View dot = new View(this);
        dot.setBackground(statusDotBg);
        brand.addView(dot, new LinearLayout.LayoutParams(Ui.dp(this, 10), Ui.dp(this, 10)));
        header.addView(brand);

        TextView sub = Ui.text(this, "SCREEN SYNC · RGBIC STRIPS", 10.5f, 0xB8FFFFFF, true);
        sub.setPadding(0, Ui.dp(this, 4), 0, 0);
        header.addView(sub);
        root.addView(header);

        // ---- scrolling content ------------------------------------------
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 14);
        panel.setPadding(pad, pad, pad, pad);

        // live-mirror hero: the card's own paint follows the live colours
        LinearLayout hero = Ui.card(this);
        heroBg = (GradientDrawable) hero.getBackground();
        LinearLayout heroHead = new LinearLayout(this);
        heroHead.setOrientation(LinearLayout.HORIZONTAL);
        heroHead.setGravity(android.view.Gravity.CENTER_VERTICAL);
        heroHead.addView(Ui.tag(this, "Live mirror"));
        heroHead.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        mirrorTag = Ui.text(this, "IDLE", 10.5f, Ui.IDLE, true);
        heroHead.addView(mirrorTag);
        hero.addView(heroHead);
        colourArea = new View(this);
        colourArea.setBackground(Ui.fill(Color.BLACK, 14, this));
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 120));
        mp.topMargin = Ui.dp(this, 12);
        hero.addView(colourArea, mp);
        panel.addView(hero, gap());

        // actions card
        LinearLayout actions = Ui.card(this);
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        startBtn = Ui.pill(this, "Start", 0, v -> startCycle());
        stopBtn = Ui.pill(this, "Stop", 1, v -> stopCycle());
        row1.addView(startBtn, Ui.grow());
        row1.addView(rowGap());
        row1.addView(stopBtn, Ui.grow());
        actions.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams row2lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        row2lp.topMargin = Ui.dp(this, 10);
        row2.setLayoutParams(row2lp);
        scanBtn = Ui.pill(this, "Scan", 2, v -> scan());
        layoutBtn = Ui.pill(this, "Layout", 2, v -> openLayout());
        screenBtn = Ui.pill(this, "Screen", 2, v -> screenSync());
        orderBtn = Ui.pill(this, "RGB", 2, v -> nextOrder());
        row2.addView(scanBtn, Ui.grow());
        row2.addView(rowGap());
        row2.addView(layoutBtn, Ui.grow());
        row2.addView(rowGap());
        row2.addView(screenBtn, Ui.grow());
        row2.addView(rowGap());
        row2.addView(orderBtn, Ui.grow());
        actions.addView(row2);
        panel.addView(actions, gap());

        // tuning card (speed + start colour)
        LinearLayout tune = Ui.card(this);
        tune.addView(Ui.tag(this, "Speed"));
        speedLabel = Ui.text(this, "", 14f, Ui.TXT, true);
        speedLabel.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 10));
        tune.addView(speedLabel);
        tune.addView(tintedBar(makeSpeedBar()));
        tune.addView(spacer(14));
        tune.addView(Ui.tag(this, "Start colour"));
        hueLabel = Ui.text(this, "", 14f, Ui.TXT, true);
        hueLabel.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 10));
        tune.addView(hueLabel);
        tune.addView(tintedBar(makeHueBar()));
        panel.addView(tune, gap());

        // devices card
        LinearLayout devices = Ui.card(this);
        devices.addView(Ui.tag(this, "Devices"));
        deviceList = new LinearLayout(this);
        deviceList.setOrientation(LinearLayout.VERTICAL);
        deviceList.setPadding(0, Ui.dp(this, 6), 0, 0);
        devices.addView(deviceList);
        panel.addView(devices, gap());

        status = Ui.text(this, "Press Scan to find your lights", 13f, Ui.MUTED, false);
        status.setPadding(pad, 0, pad, pad);
        panel.addView(status);

        ScrollView scroller = new ScrollView(this);
        scroller.addView(panel);
        root.addView(scroller, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        updateLabels();
    }

    private LinearLayout.LayoutParams gap() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 12);
        return lp;
    }

    private View rowGap() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 8), 1));
        return v;
    }

    private View spacer(int dp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, Ui.dp(this, dp)));
        return v;
    }

    /** Accent-tinted seekbar to match the theme. */
    private SeekBar tintedBar(SeekBar sb) {
        android.content.res.ColorStateList accent =
                android.content.res.ColorStateList.valueOf(Ui.ACC2);
        android.content.res.ColorStateList track =
                android.content.res.ColorStateList.valueOf(Ui.TRACK);
        sb.setProgressTintList(accent);
        sb.setProgressBackgroundTintList(track);
        sb.setThumbTintList(accent);
        sb.setPadding(0, Ui.dp(this, 8), 0, 0);
        return sb;
    }

    private SeekBar makeSpeedBar() {
        SeekBar speed = new SeekBar(this);
        speed.setMax(1000);
        speed.setProgress(minutesToProgress(cycleMinutes));
        speed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                cycleMinutes = progressToMinutes(p);
                if (bound && service != null) service.setCycleMinutes(cycleMinutes);
                updateLabels();
            }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {
                prefs.edit().putFloat("cycleMinutes", (float) cycleMinutes).apply();
            }
        });
        return speed;
    }

    private SeekBar makeHueBar() {
        SeekBar hue = new SeekBar(this);
        hue.setMax(359);
        hue.setProgress((int) startHueDeg);
        hue.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                startHueDeg = p;
                if (bound && service != null) service.setStartHue(startHueDeg);
                updateLabels();
            }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {
                prefs.edit().putFloat("startHue", startHueDeg).apply();
            }
        });
        return hue;
    }

    private Button button(String text, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(onClick);
        return b;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setTextColor(Color.WHITE);
        t.setTextSize(15);
        t.setPadding(0, 18, 0, 6);
        t.setText(text);
        return t;
    }

    private void updateLabels() {
        speedLabel.setText(String.format("Full cycle in %.1f min", cycleMinutes));
        hueLabel.setText(String.format("Hue %d° offset", (int) startHueDeg));
    }

    private int minutesToProgress(double m) {
        return (int) Math.round((m - MIN_MINUTES) / (MAX_MINUTES - MIN_MINUTES) * 1000);
    }
    private double progressToMinutes(int p) {
        return MIN_MINUTES + (p / 1000.0) * (MAX_MINUTES - MIN_MINUTES);
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(refresh);
        stopScan();
        if (bound) {
            unbindService(conn);
            bound = false;
        }
        super.onDestroy();
    }
}
