package com.magpie.android;

import android.app.PendingIntent;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile "magpie": same as tapping the logo - minimised floating window, then the home screen. */
public final class FloatTileService extends TileService {
    @Override
    public void onStartListening() {
        refresh();
    }

    @Override
    public void onClick() {
        if (!Settings.canDrawOverlays(this)) {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivityAndCollapse(PendingIntent.getActivity(this, 11, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            return;
        }
        MagpieService.start(this);
        MagpiePrefs.saveFloatMini(this, true);
        FloatService.show(this);
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivityAndCollapse(PendingIntent.getActivity(this, 12, home, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        } catch (RuntimeException ignored) {
        }
        new Handler(Looper.getMainLooper()).postDelayed(this::refresh, 400L);
    }

    private void refresh() {
        Tile t = getQsTile();
        if (t == null) return;
        t.setLabel("magpie");
        t.setState(FloatService.isShowing() ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setSubtitle(FloatService.isShowing() ? "悬浮窗已开" : "点按打开悬浮窗");
        t.updateTile();
    }
}
