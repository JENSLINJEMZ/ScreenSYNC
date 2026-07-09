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

    private LinearLayout root, colourArea, deviceList;
    private TextView speedLabel, hueLabel, status;
    private Button scanBtn, startBtn, stopBtn;

    private LedService service;
    private boolean bound;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("ledcycle", Context.MODE_PRIVATE);
        cycleMinutes = prefs.getFloat("cycleMinutes", 30.0f);
        startHueDeg = prefs.getFloat("startHue", 0f);
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        adapter = bm != null ? bm.getAdapter() : null;

        buildUi();
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
            cb.setTextColor(Color.WHITE);
            cb.setTag(address);
            cb.setChecked(checked);
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
        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
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
            stopBtn.setEnabled(running);
            scanBtn.setEnabled(!running && !scanning);
            if (running && bound && service != null) {
                colourArea.setBackgroundColor(service.getCurrentColor());
                status.setText(service.getStatus());
            } else if (!running) {
                colourArea.setBackgroundColor(Color.BLACK);
            }
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
        return !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
                || !hasPermission(Manifest.permission.BLUETOOTH_SCAN);
    }

    private String[] permsToRequest() {
        if (Build.VERSION.SDK_INT >= 33) {
            return new String[]{
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.POST_NOTIFICATIONS};
        }
        return new String[]{
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN};
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        if (req != REQ_PERMS) return;
        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)
                || !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            status.setText("Bluetooth permission needed to scan and connect");
        }
    }

    // ---- UI ----------------------------------------------------------------

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xCC000000);
        panel.setPadding(36, 40, 36, 28);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        scanBtn = button("Scan", v -> scan());
        startBtn = button("Start", v -> startCycle());
        stopBtn = button("Stop", v -> stopCycle());
        buttons.addView(scanBtn, weight());
        buttons.addView(startBtn, weight());
        buttons.addView(stopBtn, weight());
        panel.addView(buttons);

        panel.addView(label("Lights:"));
        deviceList = new LinearLayout(this);
        deviceList.setOrientation(LinearLayout.VERTICAL);
        panel.addView(deviceList);

        speedLabel = label("");
        panel.addView(speedLabel);
        panel.addView(makeSpeedBar());

        hueLabel = label("");
        panel.addView(hueLabel);
        panel.addView(makeHueBar());

        status = label("Press Scan to find your lights");
        panel.addView(status);

        ScrollView scroller = new ScrollView(this);
        scroller.addView(panel);
        root.addView(scroller, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        colourArea = new LinearLayout(this);
        colourArea.setBackgroundColor(Color.BLACK);
        root.addView(colourArea, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        updateLabels();
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
        speedLabel.setText(String.format("Speed: full cycle in %.1f min", cycleMinutes));
        hueLabel.setText(String.format("Start colour: hue %d deg", (int) startHueDeg));
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
