package com.lfkdsk.openlisttv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 普通电视/盒子上开机拉起;小米上靠 KeepAliveListener。 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        ServerService.start(context);
    }
}
