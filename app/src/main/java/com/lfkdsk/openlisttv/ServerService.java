package com.lfkdsk.openlisttv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

/**
 * 托着 OpenList 服务器进程的服务。会尝试前台服务,但小米电视会拒绝第三方应用的前台服务
 * (AppsRelaunchController),真正让进程常驻的是 KeepAliveListener。
 */
public class ServerService extends Service {
    static void start(Context context) {
        try {
            context.startService(new Intent(context, ServerService.class));
        } catch (RuntimeException e) {
            // 后台限制:退回直接在当前进程里拉起服务器
            OpenListServer.get(context).ensureRunning();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("server", "OpenList 服务", NotificationManager.IMPORTANCE_MIN));
            startForeground(1, new Notification.Builder(this, "server")
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle("OpenList 运行中")
                    .setOngoing(true)
                    .build());
        } catch (RuntimeException e) {
            Log.w("OpenListTV", "no foreground service: " + e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        OpenListServer.get(this).ensureRunning();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
