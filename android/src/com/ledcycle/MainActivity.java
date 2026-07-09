package com.ledcycle;

import android.Manifest;
import android.app.Activity;
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
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * UI over {@link LedService}: Start / Stop buttons, live speed and start-colour
 * sliders, and a colour preview. The service runs as a foreground service, so
 * once started the cycle continues with the screen off / app closed until
 * Stop is pressed (here or from the notification).
 */
public class MainActivity extends Activity {

    private static final double MIN_MINUTES = 0.25, MAX_MINUTES = 60.0;
    private static final int REQ_PERMS = 1;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private double cycleMinutes = 30.0;
    private float startHueDeg = 0f;
    private boolean startAfterPermission;

    private LinearLayout root, colourArea;
    private TextView speedLabel, hueLabel, status;
    private Button startBtn, stopBtn;

    private LedService service;
    private boolean bound;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("ledcycle", Context.MODE_PRIVATE);
        cycleMinutes = prefs.getFloat("cycleMinutes", 30.0f);
        startHueDeg = prefs.getFloat("startHue", 0f);
        buildUi();

        if (LedService.RUNNING) bind();   // reflect a cycle already running
        ui.post(refresh);
    }

    // ---- start / stop ------------------------------------------------------

    private void startCycle() {
        if (needsPermission()) {
            startAfterPermission = true;
            requestPermissions(permsToRequest(), REQ_PERMS);
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
            startBtn.setEnabled(!running);
            stopBtn.setEnabled(running);
            if (running && bound && service != null) {
                colourArea.setBackgroundColor(service.getCurrentColor());
                status.setText(service.getStatus());
            } else if (!running) {
                colourArea.setBackgroundColor(Color.BLACK);
            }
            ui.postDelayed(this, 150);
        }
    };

    // ---- permissions -------------------------------------------------------

    private boolean needsPermission() {
        return Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED;
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
        boolean granted = false;
        for (int i = 0; i < perms.length; i++) {
            if (Manifest.permission.BLUETOOTH_CONNECT.equals(perms[i])
                    && results[i] == PackageManager.PERMISSION_GRANTED) {
                granted = true;
            }
        }
        if (granted && startAfterPermission) {
            startAfterPermission = false;
            startCycle();
        } else if (!granted) {
            status.setText("Bluetooth permission denied");
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
        panel.setPadding(36, 48, 36, 36);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        startBtn = new Button(this);
        startBtn.setText("Start Cycle");
        startBtn.setOnClickListener(v -> startCycle());
        stopBtn = new Button(this);
        stopBtn.setText("Stop Cycle");
        stopBtn.setOnClickListener(v -> stopCycle());
        buttons.addView(startBtn, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        buttons.addView(stopBtn, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        panel.addView(buttons);

        speedLabel = label();
        panel.addView(speedLabel);
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
        panel.addView(speed);

        hueLabel = label();
        panel.addView(hueLabel);
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
        panel.addView(hue);

        status = label();
        status.setText("Idle - press Start Cycle");
        panel.addView(status);
        root.addView(panel);

        colourArea = new LinearLayout(this);
        colourArea.setBackgroundColor(Color.BLACK);
        root.addView(colourArea, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        updateLabels();
    }

    private TextView label() {
        TextView t = new TextView(this);
        t.setTextColor(Color.WHITE);
        t.setTextSize(16);
        t.setPadding(0, 20, 0, 8);
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
        if (bound) {
            unbindService(conn);   // service keeps running if the cycle is active
            bound = false;
        }
        super.onDestroy();
    }
}
