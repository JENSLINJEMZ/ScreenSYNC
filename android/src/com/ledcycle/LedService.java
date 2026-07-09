package com.ledcycle;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Foreground service that drives the LEDDMX strips through the hue cycle and
 * keeps running with the screen off / app in the background. Holds a partial
 * wake lock so the tick keeps firing while the phone sleeps (intended to run on
 * charge). The Activity binds to this to drive the sliders and show live state.
 */
public class LedService extends Service {

    public static final String[] MACS = {
            "41:42:6D:B6:90:22",   // LEDDMX-03-9022
            "41:42:95:7A:BF:4D",   // LEDDMX-03-BF4D
    };
    public static final String ACTION_STOP = "com.ledcycle.STOP";

    private static final long STEP_MS = 200;
    private static final float BRIGHTNESS = 1.0f;
    private static final UUID SVC = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    private static final UUID CHR = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");
    private static final byte[] POWER_ON =
            {0x7B, (byte) 0xFF, 0x04, 0x03, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xBF};
    private static final String CHANNEL = "ledcycle";
    private static final int NOTIF_ID = 1;

    private final IBinder binder = new LocalBinder();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Strip> strips = new ArrayList<>();
    private BluetoothAdapter adapter;
    private SharedPreferences prefs;
    private PowerManager.WakeLock wake;
    private NotificationManager nm;

    private boolean running;
    private double phase = 0.0;
    private volatile double cycleMinutes = 30.0;
    private volatile float startHueDeg = 0f;
    private long lastTick;
    private long lastNotif;
    private volatile int currentColor = Color.BLACK;

    public final class LocalBinder extends Binder {
        LedService getService() { return LedService.this; }
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public void onCreate() {
        super.onCreate();
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        adapter = bm != null ? bm.getAdapter() : null;
        prefs = getSharedPreferences("ledcycle", Context.MODE_PRIVATE);
        cycleMinutes = prefs.getFloat("cycleMinutes", 30.0f);
        startHueDeg = prefs.getFloat("startHue", 0f);
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createChannel();
        for (String mac : MACS) strips.add(new Strip(mac));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!running) {
            running = true;
            startForeground(NOTIF_ID, buildNotification("Starting…"));
            acquireWake();
            if (adapter != null && adapter.isEnabled()) {
                for (Strip s : strips) s.connect();
            }
            lastTick = SystemClock.elapsedRealtime();
            ui.post(tick);
        }
        return START_STICKY;
    }

    // ---- public API for the Activity --------------------------------------

    public void setCycleMinutes(double m) { cycleMinutes = m; }
    public void setStartHue(float h) { startHueDeg = h; }
    public double getCycleMinutes() { return cycleMinutes; }
    public float getStartHue() { return startHueDeg; }
    public int getCurrentColor() { return currentColor; }

    public String getStatus() {
        long now = SystemClock.elapsedRealtime();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < strips.size(); i++) {
            if (i > 0) sb.append("  ");
            Strip s = strips.get(i);
            String state = s.ffe1 == null ? "…" : (now - s.lastAck < 2000 ? "on" : "?");
            sb.append(s.shortName()).append(':').append(state);
        }
        return sb.toString();
    }

    // ---- hue loop ----------------------------------------------------------

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            long now = SystemClock.elapsedRealtime();
            double dt = now - lastTick;
            lastTick = now;

            phase = (phase + dt / (cycleMinutes * 60000.0)) % 1.0;
            float hueDeg = (float) ((phase * 360.0 + startHueDeg) % 360.0);
            int rgb = Color.HSVToColor(new float[]{hueDeg, 1f, BRIGHTNESS});
            currentColor = rgb;
            int r = Color.red(rgb), g = Color.green(rgb), b = Color.blue(rgb);
            // Colour order R, B, G (green/blue swapped) — verified on hardware.
            byte[] frame = {0x7B, (byte) 0xFF, 0x07, (byte) r, (byte) b, (byte) g,
                    0x00, (byte) 0xFF, (byte) 0xBF};
            for (Strip s : strips) s.submit(frame);

            if (now - lastNotif > 1000) {
                lastNotif = now;
                nm.notify(NOTIF_ID, buildNotification(
                        String.format("hue %.0f deg  |  %s", hueDeg, getStatus())));
            }
            ui.postDelayed(this, STEP_MS);
        }
    };

    // ---- notification / wake lock -----------------------------------------

    private void createChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL, "LED Cycle", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(String text) {
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent open = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class), piFlags);
        PendingIntent stop = PendingIntent.getService(
                this, 1, new Intent(this, LedService.class).setAction(ACTION_STOP), piFlags);
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle("LED Cycle running")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.star_on)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(
                        null, "Stop", stop).build())
                .setOngoing(true)
                .build();
    }

    @SuppressWarnings("WakelockTimeout")
    private void acquireWake() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ledcycle:cycle");
        wake.acquire();
    }

    @Override
    public void onDestroy() {
        running = false;
        ui.removeCallbacks(tick);
        for (Strip s : strips) s.close();
        if (wake != null && wake.isHeld()) wake.release();
        stopForeground(true);
        super.onDestroy();
    }

    // ---- one BLE strip (flow-controlled writes) ---------------------------

    private final class Strip {
        final String mac;
        BluetoothGatt gatt;
        volatile BluetoothGattCharacteristic ffe1;
        private volatile byte[] pending;
        private boolean busy;
        private long writeStart;
        volatile long lastAck;

        Strip(String mac) { this.mac = mac; }

        String shortName() { return mac.substring(mac.length() - 4).replace(":", ""); }

        void connect() {
            close();
            try {
                BluetoothDevice dev = adapter.getRemoteDevice(mac);
                gatt = dev.connectGatt(LedService.this, false, cb, BluetoothDevice.TRANSPORT_LE);
            } catch (SecurityException ignored) {
            }
        }

        void scheduleReconnect() {
            ffe1 = null;
            busy = false;
            if (running) ui.postDelayed(this::connect, 2000);
        }

        void submit(byte[] frame) {
            pending = frame;
            pump();
        }

        private synchronized void pump() {
            if (ffe1 == null || gatt == null) return;
            long now = SystemClock.elapsedRealtime();
            if (busy && now - writeStart < 1200) return;
            byte[] value = pending;
            if (value == null) return;
            busy = true;
            writeStart = now;
            if (!doWrite(value)) busy = false;
        }

        @SuppressWarnings("deprecation")
        private boolean doWrite(byte[] value) {
            BluetoothGattCharacteristic c = ffe1;
            if (gatt == null || c == null) return false;
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    return gatt.writeCharacteristic(c, value,
                            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS;
                } else {
                    c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                    c.setValue(value);
                    return gatt.writeCharacteristic(c);
                }
            } catch (SecurityException e) {
                return false;
            }
        }

        private final BluetoothGattCallback cb = new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt g, int st, int newState) {
                if (newState == BluetoothGatt.STATE_CONNECTED) {
                    try { g.discoverServices(); } catch (SecurityException ignored) {}
                } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                    scheduleReconnect();
                }
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt g, int st) {
                BluetoothGattService s = g.getService(SVC);
                if (s == null) return;
                BluetoothGattCharacteristic c = s.getCharacteristic(CHR);
                if (c == null) return;
                ffe1 = c;
                busy = false;
                pending = POWER_ON;
                pump();
            }

            @Override
            public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int st) {
                lastAck = SystemClock.elapsedRealtime();
                busy = false;
            }
        };

        void close() {
            if (gatt != null) {
                try { gatt.close(); } catch (SecurityException ignored) {}
                gatt = null;
            }
            ffe1 = null;
            busy = false;
        }
    }
}
