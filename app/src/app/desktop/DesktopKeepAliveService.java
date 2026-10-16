package app.desktop;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/** Keeps the proot desktop session associated with a foreground Android service. */
public final class DesktopKeepAliveService extends Service {
    private static final String CHANNEL = "linux_desktop_session";
    private static final int NOTIFICATION_ID = 4101;
    private static final String ACTION_STOP = "app.desktop.action.STOP_DESKTOP";

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(new NotificationChannel(CHANNEL,
                        "Linux desktop session", NotificationManager.IMPORTANCE_LOW));
            }
        }
        startForeground(NOTIFICATION_ID, notification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            new Thread(() -> {
                Session.stopDesktop(getApplicationContext());
                new Handler(Looper.getMainLooper()).post(() -> {
                    stopForeground(true);
                    stopSelf(startId);
                });
            }, "stop-linux-desktop").start();
        }
        return START_STICKY;
    }

    private Notification notification() {
        Intent stop = new Intent(this, DesktopKeepAliveService.class).setAction(ACTION_STOP);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent stopIntent = PendingIntent.getService(this, 1, stop, pendingFlags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Linux desktop is running")
                .setContentText("Tap Stop to close the guest desktop")
                .setOngoing(true)
                .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    static void start(Context context) {
        Intent intent = new Intent(context, DesktopKeepAliveService.class);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }
}
