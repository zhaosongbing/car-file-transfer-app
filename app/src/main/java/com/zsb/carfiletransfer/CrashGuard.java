package com.zsb.carfiletransfer;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

/**
 * Last line of defence against "opens and immediately dies".
 *
 * <p>The app polls ADB state, reads system files and posts results across
 * threads; an unexpected exception in any of those used to take the whole
 * process down, which is exactly what a user sees as 打开即闪退. This guard
 * keeps one bad callback from killing the app:</p>
 *
 * <ul>
 *   <li>worker thread: the thread dies, the UI keeps running;</li>
 *   <li>main thread: the stack trace is logged and the launcher activity is
 *       restarted once or twice, so the app comes back instead of closing.
 *       Restarting is bounded (2 per minute) - past that the original handler
 *       takes over, so a genuinely broken build still surfaces instead of
 *       looping forever.</li>
 * </ul>
 */
public final class CrashGuard {

    private static final String TAG = "CrashGuard";
    private static final String PREF = "carfile";
    private static final String KEY_AT = "crash_at_ms";
    private static final String KEY_COUNT = "crash_count";

    /** Restarts are only sensible while crashes stay occasional. */
    private static final long WINDOW_MS = 60000L;
    private static final int MAX_RESTARTS = 2;
    private static final int REQUEST_CODE = 9917;

    private static boolean installed = false;

    private CrashGuard() {
    }

    public static synchronized void install(Context ctx) {
        if (installed || ctx == null) return;
        installed = true;
        final Context app = ctx.getApplicationContext();
        if (app == null) return;
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    Log.e(TAG, "uncaught on thread '" + t.getName() + "'", e);
                } catch (Throwable ignored) {
                }
                // Only a dead main thread is fatal to the process. Worker
                // threads (adb poll, download, shell commands) can simply end.
                if (t != Looper.getMainLooper().getThread()) return;

                boolean recovered = false;
                try {
                    if (restartAllowed(app)) recovered = scheduleRestart(app);
                } catch (Throwable ignored) {
                    recovered = false;
                }
                if (recovered) {
                    killProcess();
                    return;
                }
                if (previous != null) {
                    try {
                        previous.uncaughtException(t, e);
                    } catch (Throwable ignored) {
                    }
                }
                killProcess();
            }
        });
    }

    /** Count crashes in a rolling window; stop restarting if they keep coming. */
    private static boolean restartAllowed(Context app) {
        SharedPreferences p = app.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        long at = p.getLong(KEY_AT, 0L);
        int count = p.getInt(KEY_COUNT, 0);
        long now = SystemClock.elapsedRealtime();
        if (now - at > WINDOW_MS) count = 0;      // quiet period: start over
        boolean allowed = count < MAX_RESTARTS;
        // commit(), not apply(): the process is about to be killed.
        p.edit().putLong(KEY_AT, now).putInt(KEY_COUNT, count + 1).commit();
        return allowed;
    }

    private static boolean scheduleRestart(Context app) {
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return false;
        Intent i = new Intent(app, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        int flags = PendingIntent.FLAG_ONE_SHOT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(app, REQUEST_CODE, i, flags);
        if (pi == null) return false;
        am.setExact(AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + 400L, pi);
        return true;
    }

    private static void killProcess() {
        try {
            Process.killProcess(Process.myPid());
        } catch (Throwable ignored) {
        }
    }
}
