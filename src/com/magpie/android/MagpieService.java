package com.magpie.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.IBinder;

public final class MagpieService extends Service {
    public static final String ACTION_START = "com.magpie.android.action.START";
    public static final String ACTION_STOP = "com.magpie.android.action.STOP";
    public static final String ACTION_RESTART = "com.magpie.android.action.RESTART";
    private static final String CHANNEL_ALERT = "magpie-alert";
    private static final String CHANNEL = "magpie";
    private static final int NOTIFICATION_ID = 1;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static void start(Context context) {
        MagpieGateway.userStopped = false;
        context.startForegroundService(new Intent(context, MagpieService.class).setAction(ACTION_START));
    }

    public static void stop(Context context) {
        MagpieGateway.userStopped = true;
        try {
            context.startService(new Intent(context, MagpieService.class).setAction(ACTION_STOP));
        } catch (IllegalStateException e) {
            new Thread(MagpieGateway::stop, "magpie-stop").start();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        CrashLog.install(this);
        channel(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            MagpieGateway.userStopped = true;
            new Thread(() -> { Tunnels.stopAll(getApplicationContext()); MagpieGateway.stop(); }, "magpie-stop").start();
            FloatService.hide(this);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        MagpieGateway.userStopped = false;
        startForeground(NOTIFICATION_ID, notification(this));
        final Context app = getApplicationContext();
        if (ACTION_RESTART.equals(action)) {
            new Thread(() -> {
                MagpieGateway.stop();
                MagpieGateway.start(app);
            }, "magpie-restart").start();
            android.widget.Toast.makeText(this, "正在重启网关…", android.widget.Toast.LENGTH_SHORT).show();
            return START_STICKY;
        }
        new Thread(() -> {
            MagpieGateway.start(app);
            Tunnels.resume(app);
        }, "magpie-start").start();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        new Thread(MagpieGateway::stop, "magpie-stop").start();
        super.onDestroy();
    }

    private static void channel(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, c.getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(c.getString(R.string.notification_channel_desc));
        nm.createNotificationChannel(ch);
        NotificationChannel alert = new NotificationChannel(CHANNEL_ALERT, "magpie 异常提醒", NotificationManager.IMPORTANCE_DEFAULT);
        alert.setDescription("网关多次启动失败、自动回退版本时提醒");
        nm.createNotificationChannel(alert);
    }

    /** A one-off alert (the gateway keeps failing, an update was rolled back); tapping it opens the log. */
    public static void notifyProblem(Context c, String title, String text) {
        try {
            channel(c);
            Intent i = new Intent(c, MainActivity.class).setAction(MainActivity.ACTION_SHOW_LOG).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(c, 5, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(c, CHANNEL_ALERT)
                    .setSmallIcon(R.drawable.ic_stat_magpie)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            c.getSystemService(NotificationManager.class).notify(2, n);
        } catch (RuntimeException ignored) {
        }
    }

    private Notification notification(Context c) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent open = PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class), flags);
        PendingIntent stop = PendingIntent.getService(c, 1, new Intent(c, MagpieService.class).setAction(ACTION_STOP), flags);
        PendingIntent restart = PendingIntent.getService(c, 3, new Intent(c, MagpieService.class).setAction(ACTION_RESTART), flags);
        PendingIntent floating = PendingIntent.getService(c, 2, new Intent(c, FloatService.class).setAction(FloatService.ACTION_TOGGLE), flags);
        String gw = (MagpiePrefs.lan(c) ? "0.0.0.0:" : "127.0.0.1:") + MagpiePrefs.gatewayPort(c);
        return new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_magpie)
                .setContentTitle(c.getString(R.string.notification_title))
                .setContentText(c.getString(R.string.notification_text, gw, "127.0.0.1:" + MagpiePrefs.webPort(c)))
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder((Icon) null, c.getString(R.string.notification_float), floating).build())
                .addAction(new Notification.Action.Builder((Icon) null, "重启", restart).build())
                .addAction(new Notification.Action.Builder((Icon) null, c.getString(R.string.notification_stop), stop).build())
                .build();
    }
}
