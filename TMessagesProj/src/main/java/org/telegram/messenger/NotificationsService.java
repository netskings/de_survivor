package org.telegram.messenger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.telegram.tgnet.ConnectionsManager;

public class NotificationsService extends Service {

    private static final String CHANNEL_ID = "push_service_channel";
    private static final int NOTIFICATION_ID = 101;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        Intent websiteIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Telegram-FOSS-Team/Telegram-FOSS/blob/master/Notifications.md"));

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, websiteIntent, flags);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.call_custom_notification_icon)
                .setContentText("Push service: tap to learn more")
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setShowWhen(false)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .build();

        startForeground(NOTIFICATION_ID, notification);
        ensurePushConnection();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ensurePushConnection();
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        requestRestartIfNeeded();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        requestRestartIfNeeded();
    }

    private void ensurePushConnection() {
        try {
            ApplicationLoader.postInitApplication();
            boolean pushConnectionEnabled = MessagesController.getGlobalNotificationsSettings().getBoolean("pushConnection", true);
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                UserConfig userConfig = UserConfig.getInstance(a);
                userConfig.loadConfig();
                if (a != 0 && !userConfig.isClientActivated()) {
                    continue;
                }
                ConnectionsManager connectionsManager = ConnectionsManager.getInstance(a);
                connectionsManager.setPushConnectionEnabled(pushConnectionEnabled);
                if (pushConnectionEnabled && userConfig.isClientActivated()) {
                    connectionsManager.resumeNetworkMaybe();
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private void requestRestartIfNeeded() {
        try {
            if (!MessagesController.getGlobalNotificationsSettings().getBoolean("pushService", true)) {
                return;
            }
            Intent intent = new Intent(this, AppStartReceiver.class);
            intent.setAction(AppStartReceiver.ACTION_START);
            sendBroadcast(intent);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Background Service",
                    NotificationManager.IMPORTANCE_MIN
            );
            channel.setShowBadge(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
}
