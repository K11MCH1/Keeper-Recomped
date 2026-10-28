package org.keeperrecomped.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Foreground service for downloads, extraction and the build step, so they keep
 * going with the screen off or the launcher in the background. One task at a time.
 */
public final class PrepareService extends Service {
    static final String EXTRA_TASK = "task";       // prepare | update | workshop
    static final String EXTRA_TREE = "tree";       // prepare: picked folder (optional)
    static final String EXTRA_WS_ID = "ws_id";
    static final String EXTRA_WS_NAME = "ws_name";
    static final String EXTRA_WS_URL = "ws_url";
    static final String EXTRA_WS_FILE = "ws_file";
    static final String EXTRA_WS_SIZE = "ws_size";
    static final String ACTION_CANCEL = "org.keeperrecomped.launcher.CANCEL";

    private static final String CHANNEL = "prepare";
    private static final int NOTIFICATION = 1;

    /** Latest progress, for an activity that (re)attaches mid-task. */
    private static volatile JSONObject last = new JSONObject();
    private static volatile Prepare.Listener observer;
    private static volatile Prepare running;

    static JSONObject lastProgress() { return last; }

    static boolean isRunning() { return running != null; }

    static void observe(Prepare.Listener l) { observer = l; }

    static void start(Context c, Intent task) {
        task.setClass(c, PrepareService.class);
        c.startForegroundService(task);
    }

    private NotificationManager nm;
    private PowerManager.WakeLock wake;
    private long lastNotify;

    @Override
    public void onCreate() {
        super.onCreate();
        Net.configure(this);
        AppLog.init(this);
        nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Preparing the game", NotificationManager.IMPORTANCE_LOW));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            Prepare p = running;
            if (p != null)
                p.cancel();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION, notification("Starting", 0, true), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        if (running != null || intent == null) {
            AppLog.w("a task is already running");
            return START_NOT_STICKY;
        }
        final Intent task = intent;
        final Prepare prepare = new Prepare(this, this::publish);
        running = prepare;
        wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KeeperRecomped:prepare");
        wake.acquire(4 * 60 * 60 * 1000L);
        new Thread(() -> run(prepare, task), "prepare").start();
        return START_NOT_STICKY;
    }

    private void run(Prepare prepare, Intent task) {
        String kind = task.getStringExtra(EXTRA_TASK);
        String error = null;
        boolean cancelled = false;
        try {
            if ("update".equals(kind)) {
                prepare.update();
            } else if ("workshop".equals(kind)) {
                prepare.installWorkshop(task.getStringExtra(EXTRA_WS_ID), task.getStringExtra(EXTRA_WS_NAME),
                        task.getStringExtra(EXTRA_WS_URL), task.getStringExtra(EXTRA_WS_FILE), task.getLongExtra(EXTRA_WS_SIZE, -1));
            } else {
                String tree = task.getStringExtra(EXTRA_TREE);
                prepare.prepare(tree != null ? Uri.parse(tree) : null);
            }
        } catch (Prepare.Cancelled e) {
            cancelled = true;
        } catch (Throwable t) {
            Throwable cause = t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null ? t.getCause() : t;
            AppLog.e("task " + kind + " failed", cause);
            error = cause.getMessage() != null ? cause.getMessage() : cause.toString();
            if (prepare.isCancelled())
                cancelled = true;
        }
        try {
            JSONObject done = new JSONObject(last.toString());
            done.put("running", false).put("task", kind == null ? "prepare" : kind);
            if (cancelled) {
                done.put("result", "cancelled").put("label", "Cancelled");
            } else if (error != null) {
                done.put("result", "error").put("error", error).put("label", "Failed");
                State.put(this, "last_error", error);
            } else {
                done.put("result", "ok").put("percent", 100);
                State.put(this, "last_error", "");
            }
            done.remove("detail");
            publish(done);
        } catch (Exception ignored) {
        }
        running = null;
        if (wake != null && wake.isHeld())
            wake.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void publish(JSONObject progress) {
        last = progress;
        Prepare.Listener l = observer;
        if (l != null)
            l.update(progress);
        long now = System.currentTimeMillis();
        boolean running = progress.optBoolean("running", true);
        if (running && now - lastNotify < 500)
            return;
        lastNotify = now;
        if (running)
            nm.notify(NOTIFICATION, notification(progress.optString("label"), progress.optInt("percent"), true));
    }

    private Notification notification(String text, int percent, boolean ongoing) {
        Intent open = new Intent(this, LauncherActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        Intent cancel = new Intent(this, PrepareService.class).setAction(ACTION_CANCEL);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Keeper Recomped")
                .setContentText(text)
                .setProgress(100, percent, false)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true)
                .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE))
                .addAction(new Notification.Action.Builder(null, "Cancel",
                        PendingIntent.getService(this, 1, cancel, PendingIntent.FLAG_IMMUTABLE)).build())
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    static JSONObject idle() {
        try {
            return new JSONObject().put("running", false);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }
}
