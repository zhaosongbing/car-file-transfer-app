package com.zsb.carfiletransfer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.pm.ServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Foreground service that owns the transfer end points.
 *
 * <p>Running the HTTP server from a started foreground service (instead of the
 * Activity) keeps uploads alive while the UI is rotated, re-created or pushed to
 * the background. The ongoing notification doubles as the entry point back into
 * the app and carries a "stop" action, so the user always stays in control.</p>
 */
public class TransferService extends Service {

    private static final String TAG = "TransferService";

    public static final String ACTION_STOP = "com.zsb.carfiletransfer.action.STOP";
    public static final String CHANNEL_ID = "carfile_transfer";
    public static final int NOTIFICATION_ID = 1001;

    private static volatile TransferService instance;

    /** Listeners registered before the service exists are attached on create. */
    private static final CopyOnWriteArrayList<HttpFileServer.ReceiveListener>
            LISTENERS = new CopyOnWriteArrayList<HttpFileServer.ReceiveListener>();

    private FileRepository repo;
    private HttpFileServer server;

    // ------------------------------------------------------------ static facade

    public static void start(Context c) {
        Intent i = new Intent(c, TransferService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                c.startForegroundService(i);
            } else {
                c.startService(i);
            }
        } catch (Exception e) {
            Log.e(TAG, "start: " + e.getMessage());
        }
    }

    public static void stop(Context c) {
        try {
            c.stopService(new Intent(c, TransferService.class));
        } catch (Exception e) {
            Log.e(TAG, "stop: " + e.getMessage());
        }
    }

    public static boolean isRunning() {
        TransferService s = instance;
        return s != null && s.server != null && s.server.isRunning();
    }

    public static void addListener(HttpFileServer.ReceiveListener l) {
        LISTENERS.add(l);
        TransferService s = instance;
        if (s != null && s.server != null) s.server.addListener(l);
    }

    public static void removeListener(HttpFileServer.ReceiveListener l) {
        LISTENERS.remove(l);
    }

    // ------------------------------------------------------------ lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        repo = new FileRepository(this);
        server = new HttpFileServer(HttpFileServer.DEFAULT_PORT, repo,
                readAsset("portal.html"), getString(R.string.device_car));

        for (HttpFileServer.ReceiveListener l : LISTENERS) {
            server.addListener(l);
        }
        server.addListener(new HttpFileServer.ReceiveListener() {
            public void onFileReceived(String name, long size) {
                updateNotification();
            }
        });

        try {
            server.start();
        } catch (Exception e) {
            Log.e(TAG, "server: " + e.getMessage());
        }

        createChannel();
        try {
            startForegroundCompat(buildNotification());
        } catch (Exception e) {
            Log.e(TAG, "foreground: " + e.getMessage());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (server != null && !server.isRunning()) {
            try {
                server.start();
            } catch (Exception e) {
                Log.e(TAG, "restart server: " + e.getMessage());
            }
        }
        // keep the notification in sync when the service is re-delivered
        updateNotification();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "stopped");
        if (server != null) server.stop();
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public FileRepository getRepository() {
        return repo;
    }

    // ------------------------------------------------------------ notification

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(getString(R.string.channel_desc));
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private void startForegroundCompat(Notification n) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, n,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
        } catch (Throwable t) {
            // Some ROMs reject the typed variant even when declared in the manifest.
            Log.w(TAG, "typed startForeground failed: " + t.getMessage());
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private Notification buildNotification() {
        int count = repo != null ? repo.listJson().length() : 0;
        String text = getString(R.string.notify_text, address(), count);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent openPi = PendingIntent.getActivity(this, 1, open, piFlags);

        Intent stop = new Intent(this, TransferService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 2, stop, piFlags);

        b.setContentTitle(getString(R.string.notify_title))
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_stat_transfer)
                .setContentIntent(openPi)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(
                        R.drawable.ic_stat_transfer,
                        getString(R.string.notify_stop), stopPi).build());
        return b.build();
    }

    private void updateNotification() {
        try {
            NotificationManager nm =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification());
        } catch (Exception e) {
            Log.w(TAG, "notify: " + e.getMessage());
        }
    }

    private String address() {
        String ip = SoftApManager.getApIp();
        if (ip == null) ip = "0.0.0.0";
        return ip + ":" + HttpFileServer.DEFAULT_PORT;
    }

    // ------------------------------------------------------------ helpers

    private String readAsset(String name) {
        try {
            AssetManager am = getAssets();
            InputStream is = am.open(name);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            Log.e(TAG, "asset " + name + ": " + e.getMessage());
            return "<html><body>portal missing</body></html>";
        }
    }
}
