package net.kdt.pojavlaunch.services;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import net.kdt.pojavlaunch.MainActivity;
import git.artdeell.mojo.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.instances.ServerInstance;
import net.kdt.pojavlaunch.lifecycle.ServerProcessManager;
import net.kdt.pojavlaunch.utils.NotificationUtils;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ForegroundService that keeps Minecraft server processes alive while the app is in the background.
 *
 * Fragments interact via the static registry (ServerForegroundService.getManager) and by
 * sending ACTION_START / ACTION_STOP intents; no explicit binding is required.
 *
 * Usage — start a server:
 *   Intent i = new Intent(ctx, ServerForegroundService.class)
 *       .setAction(ServerForegroundService.ACTION_START)
 *       .putExtra(EXTRA_BASE_PATH, basePath)
 *       .putExtra(EXTRA_INSTANCE_ID, instanceId);
 *   ContextCompat.startForegroundService(ctx, i);
 *
 * Usage — stop a server:
 *   Intent i = new Intent(ctx, ServerForegroundService.class)
 *       .setAction(ServerForegroundService.ACTION_STOP)
 *       .putExtra(EXTRA_INSTANCE_ID, instanceId);
 *   ctx.startService(i);
 *
 * Usage — read status in any fragment:
 *   ServerProcessManager mgr = ServerForegroundService.getManager(serverId); // null if not running
 */
public class ServerForegroundService extends Service {

    private static final String TAG = "ServerFgService";

    public static final String ACTION_START   = "net.kdt.pojavlaunch.SERVER_START";
    public static final String ACTION_STOP    = "net.kdt.pojavlaunch.SERVER_STOP";
    public static final String EXTRA_INSTANCE_ID = "instance_id";
    public static final String EXTRA_BASE_PATH   = "base_path";

    private static final int NOTIFICATION_ID = NotificationUtils.NOTIFICATION_ID_SERVER_SERVICE;

    // Static registry — lets fragments access managers without a bound service connection.
    // Populated only while the service is alive; cleared in onDestroy().
    private static final Map<String, ServerProcessManager> sManagers = new ConcurrentHashMap<>();

    private final IBinder mBinder = new LocalBinder();
    private final ExecutorService mExecutor = Executors.newCachedThreadPool();
    private PowerManager.WakeLock mWakeLock;

    public class LocalBinder extends Binder {
        public ServerForegroundService getService() { return ServerForegroundService.this; }
    }

    // ── Static accessors (usable from any component in the same process) ──────────────────────

    /** @return the manager for the given instance id, or null if the server is not running. */
    @Nullable
    public static ServerProcessManager getManager(String instanceId) {
        return sManagers.get(instanceId);
    }

    /** @return true if a server with the given id is currently running. */
    public static boolean isRunning(String instanceId) {
        ServerProcessManager m = sManagers.get(instanceId);
        return m != null && m.isRunning();
    }

    /** @return an unmodifiable snapshot of all active managers. */
    public static Map<String, ServerProcessManager> getAllManagers() {
        return Collections.unmodifiableMap(sManagers);
    }

    // ── Service lifecycle ─────────────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        Tools.buildNotificationChannel(getApplicationContext());
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MojoLauncher:ServerService");
        mWakeLock.setReferenceCounted(false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        String id     = intent.getStringExtra(EXTRA_INSTANCE_ID);

        if (ACTION_START.equals(action) && id != null) {
            String basePath = intent.getStringExtra(EXTRA_BASE_PATH);
            ServerInstance inst = ServerInstance.loadById(basePath, id);
            if (inst != null) {
                doStart(inst);
            } else {
                Log.e(TAG, "Cannot load instance id=" + id + " from " + basePath);
                stopSelfIfIdle();
            }
        } else if (ACTION_STOP.equals(action) && id != null) {
            doStop(id);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        for (ServerProcessManager m : sManagers.values()) m.forceStop();
        sManagers.clear();
        releaseWakeLock();
        mExecutor.shutdownNow();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return mBinder; }

    // ── Internal operations ───────────────────────────────────────────────────────────────────

    private void doStart(ServerInstance inst) {
        if (sManagers.containsKey(inst.getId())) {
            Log.w(TAG, "Server " + inst.getId() + " already registered — ignoring duplicate start");
            return;
        }

        String resolved = findBundledJava(this);
        ServerProcessManager mgr = new ServerProcessManager(inst, resolved);
        sManagers.put(inst.getId(), mgr);

        mgr.addConsoleListener(new ServerProcessManager.ConsoleListener() {
            @Override public void onLine(String line) {}
            @Override public void onServerStarted() {
                updateNotification(inst.getName() + " — Running");
            }
            @Override public void onServerStopped(int code) {
                cleanupServer(inst.getId(), false);
            }
            @Override public void onCrash(int code, String tail) {
                cleanupServer(inst.getId(), true);
            }
        });

        acquireWakeLock();
        startForegroundCompat(buildNotification(inst.getName() + " — Starting\u2026"));

        mExecutor.submit(() -> {
            try {
                mgr.start();
            } catch (Exception e) {
                Log.e(TAG, "Failed to start server: " + inst.getName(), e);
                cleanupServer(inst.getId(), true);
            }
        });
    }

    private void doStop(String id) {
        ServerProcessManager mgr = sManagers.get(id);
        if (mgr == null) return;
        mExecutor.submit(() -> {
            try { mgr.stopGraceful(); }
            catch (Exception e) { mgr.forceStop(); }
        });
    }

    private void cleanupServer(String id, boolean crashed) {
        sManagers.remove(id);
        if (sManagers.isEmpty()) {
            releaseWakeLock();
            stopSelf();
        } else {
            updateNotification(sManagers.size() + " server(s) running");
        }
    }

    private void stopSelfIfIdle() {
        if (sManagers.isEmpty()) stopSelf();
    }

    // ── Notification helpers ──────────────────────────────────────────────────────────────────

    private Notification buildNotification(String text) {
        PendingIntent openIntent = PendingIntent.getActivity(
                this, 0,
                new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, getString(R.string.notif_channel_id))
                .setContentTitle(getString(R.string.server_notif_title))
                .setContentText(text)
                .setSmallIcon(R.drawable.notif_icon)
                .setContentIntent(openIntent)
                .setNotificationSilent()
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(text));
    }

    private void startForegroundCompat(Notification n) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    // ── WakeLock helpers ──────────────────────────────────────────────────────────────────────

    private void acquireWakeLock() {
        if (mWakeLock != null && !mWakeLock.isHeld()) mWakeLock.acquire();
    }

    private void releaseWakeLock() {
        if (mWakeLock != null && mWakeLock.isHeld()) mWakeLock.release();
    }

    // ── Helper: resolve bundled JRE binary (Android) ──────────────────────────────────────────

    /**
     * Finds the bundled JRE java binary deployed by PojavLauncher into the app's data directory.
     * Tries known component paths (jre-25 → jre-21 → jre-new), then falls back to java.home.
     *
     * @param context any Android context (only getFilesDir() is used)
     * @return absolute path to the java binary, or "java" as a last resort
     */
    public static String findBundledJava(Context context) {
        String dataParent = context.getFilesDir().getParent();
        String[] candidates = { "jre-25", "jre-21", "jre-new" };
        for (String comp : candidates) {
            java.io.File bin = new java.io.File(dataParent + "/components/" + comp + "/bin/java");
            if (bin.exists() && bin.canExecute()) return bin.getAbsolutePath();
        }
        // Fallback: java.home (may be set by launcher init)
        String jh = System.getProperty("java.home");
        if (jh != null) {
            java.io.File bin = new java.io.File(jh, "bin/java");
            if (bin.exists()) return bin.getAbsolutePath();
        }
        return "java";
    }
}
