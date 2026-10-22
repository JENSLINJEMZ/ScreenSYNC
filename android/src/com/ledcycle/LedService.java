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
import android.content.res.Resources;
import android.graphics.Color;
import android.hardware.display.DisplayManager;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import java.nio.ByteBuffer;

/**
 * Foreground service that drives the LEDDMX strips through the hue cycle and
 * keeps running with the screen off / app in the background. Holds a partial
 * wake lock so the tick keeps firing while the phone sleeps (intended to run on
 * charge). The Activity binds to this to drive the sliders and show live state.
 */
public class LedService extends Service {

    /** Address prefix these controllers advertise with; used to filter the scan. */
    public static final String NAME_PREFIX = "LEDDMX";
    /** SharedPreferences key holding the user-selected controller addresses. */
    public static final String PREF_MACS = "macs";
    /** SharedPreferences key holding the per-light layout, serialized {@link #LAYOUT_FMT}. */
    public static final String PREF_LAYOUT = "layout";
    public static final String ACTION_STOP = "com.ledcycle.STOP";
    public static final String ACTION_SCREEN =
            "com.ledcycle.SCREEN";   // extras: "code" (result code), "data" (approval data)

    /** SharedPreferences key for the BLE colour-slot order ("rgb" etc). */
    public static final String PREF_ORDER = "colorOrder";
    /** Orders to cycle through on the Order button. */
    public static final String[] ORDERS = {"rgb", "rbg", "gbr", "grb", "brg", "bgr"};

    /** True while the cycle service is active; read by the Activity for button state. */
    public static volatile boolean RUNNING = false;

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
    /** Per-light layout state (MAC -> state), edited in the Layout screen. */
    private final Map<String, LightState> layout = new LinkedHashMap<>();
    private BluetoothAdapter adapter;
    private SharedPreferences prefs;
    private PowerManager.WakeLock wake;
    private NotificationManager nm;

    private boolean running;
    private double phase = 0.0;
    private volatile double cycleMinutes = 30.0;
    private volatile float startHueDeg = 0f;
    /** Channel order the controller expects: "rgb" (user's strip) or e.g. "rbg". */
    private volatile String colorOrder = "rgb";
    private long lastTick;
    private long lastNotif;
    private volatile int currentColor = Color.BLACK;

    // ---- screen sync (SignalRGB-style: screen -> lights) -------------------
    private static final String TAG = "ledcycle";
    /** Pixels sampled per axis — keeps the per-frame cost tiny. */
    private static final int SAMPLE_COLS = 96, SAMPLE_ROWS = 128;

    private MediaProjection projection;
    private android.hardware.display.VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private volatile boolean screenSync;
    private volatile int[] screenColors;
    private long lastScreenFrame;

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
        colorOrder = prefs.getString(PREF_ORDER, "rgb");
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createChannel();
        layout.putAll(readLayout(this));
        for (String mac : prefs.getStringSet(PREF_MACS, java.util.Collections.emptySet())) {
            strips.add(new Strip(mac));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        boolean startScreen = intent != null && ACTION_SCREEN.equals(intent.getAction());
        int code = startScreen && intent.hasExtra("code") ? intent.getIntExtra("code", 0) : 0;
        Intent data = startScreen && intent.hasExtra("data") ? (Intent) intent.getParcelableExtra("data") : null;

        if (!running) {
            running = true;
            RUNNING = true;
            startForeground(NOTIF_ID, buildNotification(startScreen ? "Screen sync starting…" : "Starting…"));
            acquireWake();
            if (adapter != null && adapter.isEnabled()) {
                for (Strip s : strips) s.connect();
            }
            lastTick = SystemClock.elapsedRealtime();
            ui.post(tick);
        }
        if (startScreen && data != null) startScreenSync(code, data);
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

    /**
     * Build one colour frame {@code 7B FF 07 c1 c2 c3 00 FF BF}. {@code order}
     * says which physical channel the three colour slots expect: "rbg" means
     * slot 2 carries blue and slot 3 carries green (what the original
     * reverse-engineering verified); "rgb" is plain RGB (what this user's strip
     * needed — it showed blue where green was sent under "rbg").
     */
    public static byte[] frameBytes(int color, String order) {
        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        byte[] f = {0x7B, (byte) 0xFF, 0x07, 0, 0, 0, 0x00, (byte) 0xFF, (byte) 0xBF};
        for (int i = 0; i < 3; i++) {
            char ch = order.charAt(i);
            f[3 + i] = (byte) (ch == 'r' ? r : ch == 'g' ? g : b);
        }
        return f;
    }

    /** Restore the persistence flag for the Activity (it can't see the service). */
    public String getColorOrder() { return colorOrder; }

    /** Set the order live and remember it for the next launch. */
    public void setColorOrder(String o) {
        colorOrder = o;
        prefs.edit().putString(PREF_ORDER, o).apply();
    }

    // ---- per-light layout (Layout screen / screen sync) --------------------

    /**
     * Per-light state for the Layout screen. Every light (strip) can follow the
     * hue cycle like before, or be pinned to its own static colour, or be
     * switched off — each independently of the others.
     */
    public static class LightState {
        public boolean followCycle = true;
        public boolean on = true;
        public int color = 0xFF0000;   // static colour when not following the cycle
    }

    /**
     * Serialized per-light state: {@code "mac,follow,on,colorhex;…"} — values
     * {@code follow}/{@code on} are {@code 1}/{@code 0}, colour is 24-bit RRGGBB.
     */
    private static final String LAYOUT_FMT = "%s,%d,%d,%06X;";

    /** Read the saved layout from preferences without touching service state. */
    public static Map<String, LightState> readLayout(Context ctx) {
        Map<String, LightState> out = new LinkedHashMap<>();
        SharedPreferences p = ctx.getSharedPreferences("ledcycle", Context.MODE_PRIVATE);
        for (String entry : p.getString(PREF_LAYOUT, "").split(";")) {
            String[] f = entry.split(",", -1);
            if (f.length != 4) continue;
            LightState st = new LightState();
            st.followCycle = "1".equals(f[1]);
            st.on = "1".equals(f[2]);
            try {
                st.color = (int) (Long.parseLong(f[3], 16) & 0xFFFFFF) | 0xFF000000;
            } catch (NumberFormatException e) {
                continue;
            }
            out.put(f[0], st);
        }
        return out;
    }

    private void saveLayout() {
        prefs.edit().putString(PREF_LAYOUT, serialize(layout)).apply();
    }

    /** Layout serialization: "mac,follow,on,rrggbb;…" */
    private static String serialize(Map<String, LightState> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, LightState> e : m.entrySet()) {
            LightState st = e.getValue();
            sb.append(String.format(LAYOUT_FMT, e.getKey(),
                    st.followCycle ? 1 : 0, st.on ? 1 : 0, st.color & 0xFFFFFF));
        }
        return sb.toString();
    }

    /** Persist one light from outside the service (Layout screen while stopped). */
    public static void writeLightState(Context ctx, String mac,
                                       boolean followCycle, boolean on, int color) {
        Map<String, LightState> m = readLayout(ctx);
        LightState st = m.get(mac);
        if (st == null) {
            st = new LightState();
            m.put(mac, st);
        }
        st.followCycle = followCycle;
        st.on = on;
        st.color = color;
        ctx.getSharedPreferences("ledcycle", Context.MODE_PRIVATE)
                .edit().putString(PREF_LAYOUT, serialize(m)).apply();
    }

    /** State for one light, creating a default (cycle-following) entry if needed. */
    public LightState getLayoutState(String mac) {
        LightState st = layout.get(mac);
        if (st == null) {
            st = new LightState();
            layout.put(mac, st);
        }
        return st;
    }

    /** Update one light from the Layout screen; takes effect on the next tick. */
    public void setLayoutState(String mac, boolean followCycle, boolean on, int color) {
        LightState st = getLayoutState(mac);
        st.followCycle = followCycle;
        st.on = on;
        st.color = color;
        saveLayout();
    }

    /** Live colour currently being sent to that light — the Layout screen reads
     *  this to keep its tiles in sync with the strips (screen sync). */
    public int getLiveColor(String mac) {
        for (Strip s : strips) if (s.mac.equals(mac)) return s.liveColor;
        return previewColor(mac);
    }

    /** First captured screen colour while screen sync is active (hero tile). */
    public int getScreenSyncColor() {
        int[] c = screenColors;
        return screenSync && c != null && c.length > 0 ? c[0] : Color.BLACK;
    }

    /** What the layout says this light should show, for when we're not running. */
    private int previewColor(String mac) {
        LightState st = layout.get(mac);
        if (st == null || !st.on) return Color.BLACK;
        if (st.followCycle) {
            float hue = ((startHueDeg % 360f) + 360f) % 360f;
            return Color.HSVToColor(new float[]{hue, 1f, BRIGHTNESS});
        }
        return st.color;
    }

    /** Colour one strip should show this tick, per its layout state. */
    private int colorFor(String mac, int cycleColor) {
        LightState st = layout.get(mac);
        boolean on = st == null || st.on;
        boolean follow = st == null || st.followCycle;
        return on ? (follow ? cycleColor : st.color) : Color.BLACK;
    }

    // ---- screen sync (SignalRGB-style: screen -> lights) --------------------

    /** True while screen capture feeds the lights. */
    public boolean isScreenSync() { return screenSync; }

    /**
     * Begin mirroring the phone screen onto the lights. {@code resultCode} and
     * {@code data} come straight from the system screen-capture approval.
     */
    public void startScreenSync(int resultCode, Intent data) {
        if (screenSync) return;
        if (adapter == null) return;
        // The system kills the projection within seconds unless a
        // mediaProjection-typed foreground service is running — re-publish the
        // foreground service with that type before asking for the token,
        // otherwise we capture one frame (the consent dialog) and get cut off.
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, buildNotification("Screen sync…"),
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                                | android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            }
        } catch (Exception e) {
            Log.w(TAG, "fgs re-publish failed: " + e);
        }
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        try {
            projection = mpm.getMediaProjection(resultCode, data);
        } catch (SecurityException | IllegalStateException e) {
            Log.w(TAG, "screen sync denied: " + e);
            return;
        }
        if (projection == null) return;

        Resources res = getResources();
        int screenW = res.getDisplayMetrics().widthPixels;
        int dpi = res.getDisplayMetrics().densityDpi;
        // Small capture buffer; aspect kept, size chosen for cheap averaging.
        int w = 320, h = Math.max(1, Math.round(screenW <= 0 ? 1
                : 320 * (float) res.getDisplayMetrics().heightPixels / screenW));

        imageReader = ImageReader.newInstance(w, h,
                android.graphics.PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(reader -> {
            Image img = reader.acquireLatestImage();
            if (img != null) {
                try {
                    processScreen(img);
                } catch (Throwable t) {
                    Log.e(TAG, "screen frame failed", t);
                } finally {
                    try { img.close(); } catch (Exception ignored) {}
                }
            }
        }, ui);
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                Log.w(TAG, "projection stopped (system/user)");
                ui.post(LedService.this::stopScreenSyncImpl);
            }
        }, ui);
        virtualDisplay = projection.createVirtualDisplay("ledcycle-screen",
                w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, ui);
        screenColors = new int[Math.max(1, strips.size())];
        screenSync = true;
        Log.i(TAG, "screen sync on (" + strips.size() + " lights, " + w + "x" + h + ")");
        nm.notify(NOTIF_ID, buildNotification("Screen sync to " + strips.size() + " light(s)"));
    }

    /** Stop mirroring; lights fall back to the hue cycle / layout colours. */
    public void stopScreenSync() {
        if (!screenSync) return;
        stopScreenSyncImpl();
        Log.i(TAG, "screen sync off");
        nm.notify(NOTIF_ID, buildNotification(getStatus()));
    }

    private synchronized void stopScreenSyncImpl() {
        screenSync = false;
        if (virtualDisplay != null) { try { virtualDisplay.release(); } catch (Exception ignored) {} virtualDisplay = null; }
        if (imageReader != null) { try { imageReader.close(); } catch (Exception ignored) {} imageReader = null; }
        if (projection != null) { try { projection.stop(); } catch (Exception ignored) {} projection = null; }
        ui.post(() -> nm.notify(NOTIF_ID, buildNotification(getStatus())));
    }

    /**
     * Average the captured frame per light: with one strip the whole screen is
     * averaged; several strips split the screen into vertical bands (left to
     * right), so a multi-strip wall mirrors the screen like SignalRGB does.
     */
    private void processScreen(Image img) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastScreenFrame < 150) return;   // ~6 fps is plenty for 5 Hz strips
        lastScreenFrame = now;

        Image.Plane plane = img.getPlanes()[0];
        ByteBuffer buf = plane.getBuffer().rewind();
        int w = img.getWidth(), h = img.getHeight();
        int rowStride = plane.getRowStride(), pxStride = plane.getPixelStride();
        int n = Math.max(1, strips.size());
        long[] sums = new long[n * 3];
        int[] counts = new int[n];

        int stepX = Math.max(1, w / SAMPLE_COLS);
        int stepY = Math.max(1, h / SAMPLE_ROWS);
        for (int y = 0; y < h; y += stepY) {
            int row = y * rowStride;
            for (int x = 0; x < w; x += stepX) {
                int idx = row + x * pxStride;
                if (idx + 4 > buf.capacity()) break;
                int r = buf.get(idx) & 0xFF;
                int g = buf.get(idx + 1) & 0xFF;
                int b = buf.get(idx + 2) & 0xFF;
                int s = Math.min(n - 1, (x * n) / w);
                int s3 = s * 3;
                sums[s3] += r; sums[s3 + 1] += g; sums[s3 + 2] += b;
                counts[s]++;
            }
        }
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            int c = counts[i] == 0 ? Color.BLACK
                    : 0xFF000000 | (((int) (sums[i * 3] / counts[i])) << 16)
                    | (((int) (sums[i * 3 + 1] / counts[i])) << 8)
                    | ((int) (sums[i * 3 + 2] / counts[i]));
            out[i] = c;
        }
        screenColors = out;
        Log.d(TAG, "screen color 0x" + String.format("%06X", out[0] & 0xFFFFFF)
                + (n > 1 ? " …" : ""));
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

            if (screenSync && screenColors != null) {
                // Screen sync: mirror the captured screen onto the lights.
                if (now - lastNotif > 2000) {
                    int[] sc = screenColors;
                    int c0 = sc.length > 0 ? sc[0] : Color.BLACK;
                    Log.d(TAG, "tick screenSync " + (imageReader != null ? "capture" : "capture dead")
                            + " tint=0x" + String.format("%06X", c0 & 0xFFFFFF));
                }
                int[] colors = screenColors;
                for (int i = 0; i < strips.size(); i++) {
                    Strip s = strips.get(i);
                    s.setFrame(i < colors.length ? colors[i] : Color.BLACK);
                }
            } else {
                byte[] cycleFrame = frameBytes(rgb, colorOrder);
                for (Strip s : strips) s.setFrame(colorFor(s.mac, rgb));
            }
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
        RUNNING = false;
        ui.removeCallbacks(tick);
        stopScreenSyncImpl();
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
        /** Last colour queued for this strip — the Layout screen's live sync source. */
        volatile int liveColor = Color.BLACK;

        Strip(String mac) { this.mac = mac; }

        String shortName() { return mac.substring(mac.length() - 4).replace(":", ""); }

        /**
         * Queue the frame for this strip's current layout colour and remember
         * the colour for the Layout screen's live sync.
         */
        void setFrame(int color) {
            liveColor = color;
            submit(frameBytes(color, colorOrder));
        }

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
