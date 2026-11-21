package com.lfkdsk.openlisttv;

import android.service.notification.NotificationListenerService;

/**
 * 小米开机会强制停止所有第三方应用,BootReceiver 收不到开机广播,前台服务也被拒绝。
 * 已授权的通知监听服务会被系统开机后(以及强制停止后)重新绑定,借它常驻并拉起服务器。不读取任何通知。
 * 授权:cmd notification allow_listener com.lfkdsk.openlisttv/com.lfkdsk.openlisttv.KeepAliveListener
 */
public class KeepAliveListener extends NotificationListenerService {
    @Override
    public void onCreate() {
        super.onCreate();
        ServerService.start(this);
    }

    @Override
    public void onListenerConnected() {
        ServerService.start(this);
    }
}
