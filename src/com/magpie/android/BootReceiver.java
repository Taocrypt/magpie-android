package com.magpie.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/* loaded from: classes.dex */
public final class BootReceiver extends BroadcastReceiver {
    @Override // android.content.BroadcastReceiver
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if ("android.intent.action.MY_PACKAGE_REPLACED".equals(action)) UpdateNotifier.shellReplaced(context);
        if (("android.intent.action.BOOT_COMPLETED".equals(action) || "android.intent.action.MY_PACKAGE_REPLACED".equals(action)) && MagpiePrefs.autostart(context)) {
            MagpieService.start(context);
        }
    }
}
